<#
.SYNOPSIS
  Copy all files from OneDrive to a local folder, with progress, resume and a
  final verification report (local vs. remote file count / bytes).

.PARAMETER Dest
  Local destination directory.

.PARAMETER RemoteRoot
  OneDrive folder to sync (default /).

.PARAMETER RefreshManifest
  Rebuild the remote file list even if a manifest already exists.

.PARAMETER VerifyOnly
  Do not download; only compare the existing manifest
  (<Dest>\.onedrive-sync\manifest.tsv) with local files.

.EXAMPLE
  .\scripts\onedrive-sync.ps1 -Dest D:\OneDriveBackup
  .\scripts\onedrive-sync.ps1 -Dest D:\Backup -RemoteRoot Documents -RefreshManifest
  .\scripts\onedrive-sync.ps1 -Dest D:\OneDriveBackup -VerifyOnly

.NOTES
  Environment:
    ONEDRIVE_JAR              path to fat jar
    ONEDRIVE_JAVA             java.exe path (default: java)
    ONEDRIVE_REFRESH_MANIFEST 1 = same as -RefreshManifest
    ONEDRIVE_VERIFY_ONLY      1 = same as -VerifyOnly

  State files (<Dest>\.onedrive-sync\):
    manifest.tsv, done.txt, failed.txt,
    verify-report.txt, missing.txt, size-mismatch.txt, extra.txt

  Exit codes:
    0  success: no download failures and verification PASS
    1  usage error, jar not found, or manifest missing (verify-only)
    2  authentication failed
    3  one or more downloads failed (takes precedence over 5)
    5  verification FAIL (missing files or size mismatch)

  Works on Windows PowerShell 5.1 and PowerShell 7+.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Dest,

    [Parameter(Mandatory = $false, Position = 1)]
    [string]$RemoteRoot = "/",

    [switch]$RefreshManifest,

    [switch]$VerifyOnly
)

# Native tools (java) write logs to stderr; "Stop" would turn those into
# terminating errors on Windows PowerShell 5.1. We check $LASTEXITCODE instead.
$ErrorActionPreference = "Continue"

function Write-Err([string]$Message) {
    [Console]::Error.WriteLine($Message)
}

function Format-Size([long]$Bytes) {
    $units = @("B", "KB", "MB", "GB", "TB", "PB")
    $v = [double]$Bytes
    $i = 0
    while ($v -ge 1024 -and $i -lt $units.Count - 1) { $v = $v / 1024; $i++ }
    if ($i -eq 0) { return ("{0} B" -f $Bytes) }
    return ("{0:N2} {1}" -f $v, $units[$i])
}

function Get-Pct([double]$Part, [double]$Total) {
    if ($Total -eq 0) { return "100.0" }
    return ("{0:N1}" -f (($Part * 100.0) / $Total))
}

if ($env:ONEDRIVE_VERIFY_ONLY -eq "1") { $VerifyOnly = $true }

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir
$JavaBin = if ($env:ONEDRIVE_JAVA) { $env:ONEDRIVE_JAVA } else { "java" }
$Jar = if ($env:ONEDRIVE_JAR) { $env:ONEDRIVE_JAR } else {
    Join-Path $RepoRoot "target\onedrive-api-java-1.0.0.jar"
}

$State = Join-Path $Dest ".onedrive-sync"
$Manifest = Join-Path $State "manifest.tsv"
$DoneFile = Join-Path $State "done.txt"
$FailedFile = Join-Path $State "failed.txt"
$ReportFile = Join-Path $State "verify-report.txt"
$MissingFile = Join-Path $State "missing.txt"
$MismatchFile = Join-Path $State "size-mismatch.txt"
$ExtraFile = Join-Path $State "extra.txt"
$Sep = [IO.Path]::DirectorySeparatorChar

function Get-LocalPath([string]$RemotePath) {
    return (Join-Path $Dest ($RemotePath -replace "/", $Sep))
}

# Returns 0 on PASS, 5 on FAIL.
function Invoke-Verify {
    $remoteCount = 0; [long]$remoteBytes = 0
    $presentCount = 0; [long]$presentBytes = 0
    $okCount = 0; [long]$okBytes = 0
    $missingCount = 0; [long]$missingBytes = 0
    $mismatchCount = 0
    $extraCount = 0; [long]$extraBytes = 0

    # Windows file systems are case-insensitive; elsewhere compare exactly
    $cmp = if ($env:OS -eq "Windows_NT") { [StringComparer]::OrdinalIgnoreCase } else { [StringComparer]::Ordinal }
    $inManifest = New-Object 'System.Collections.Generic.HashSet[string]' ($cmp)
    $missing = New-Object 'System.Collections.Generic.List[string]'
    $mismatch = New-Object 'System.Collections.Generic.List[string]'
    $extra = New-Object 'System.Collections.Generic.List[string]'

    foreach ($line in (Get-Content -LiteralPath $Manifest -Encoding utf8)) {
        if (-not $line -or -not $line.Trim()) { continue }
        $parts = $line.TrimEnd("`r") -split "`t", 6
        $path = $parts[0]
        $sizeText = if ($parts.Count -gt 1) { $parts[1] } else { "" }
        $kind = if ($parts.Count -gt 4) { $parts[4] } else { "file" }
        if ($kind -and $kind -ne "file") { continue }

        [long]$size = 0
        $hasSize = [long]::TryParse($sizeText, [ref]$size)
        [void]$inManifest.Add(($path -replace "\\", "/"))
        $remoteCount++
        if ($hasSize) { $remoteBytes += $size }

        $localPath = Get-LocalPath $path
        if (Test-Path -LiteralPath $localPath -PathType Leaf) {
            [long]$lsize = (Get-Item -LiteralPath $localPath -Force).Length
            $presentCount++
            $presentBytes += $lsize
            if ($hasSize -and $lsize -ne $size) {
                $mismatchCount++
                $mismatch.Add(("{0}`t{1}`t{2}" -f $path, $size, $lsize))
            } else {
                $okCount++
                $okBytes += $lsize
            }
        } else {
            $missingCount++
            if ($hasSize) { $missingBytes += $size }
            $missing.Add(("{0}`t{1}" -f $path, $sizeText))
        }
    }

    # Extra local files (informational), excluding the state directory
    $destFull = (Resolve-Path -LiteralPath $Dest).ProviderPath.TrimEnd('\', '/')
    $stateFull = Join-Path $destFull ".onedrive-sync"
    Get-ChildItem -LiteralPath $destFull -Recurse -File -Force -ErrorAction SilentlyContinue | ForEach-Object {
        $full = $_.FullName
        if ($full.StartsWith($stateFull + $Sep, [StringComparison]::OrdinalIgnoreCase)) { return }
        $rel = $full.Substring($destFull.Length + 1) -replace "\\", "/"
        if ($inManifest.Contains($rel)) { return }
        $extra.Add(("{0}`t{1}" -f $rel, $_.Length))
    }
    foreach ($e in $extra) {
        $extraCount++
        $extraBytes += [long](($e -split "`t")[1])
    }

    $verdict = "PASS"
    if ($missingCount -gt 0 -or $mismatchCount -gt 0 -or $okCount -ne $remoteCount) { $verdict = "FAIL" }

    Set-Content -LiteralPath $MissingFile -Value $missing.ToArray() -Encoding utf8
    Set-Content -LiteralPath $MismatchFile -Value $mismatch.ToArray() -Encoding utf8
    Set-Content -LiteralPath $ExtraFile -Value $extra.ToArray() -Encoding utf8

    $r = New-Object 'System.Collections.Generic.List[string]'
    $r.Add("===== OneDrive sync verification report =====")
    $r.Add("Time:        " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz"))
    $r.Add("Destination: $destFull")
    $r.Add("Manifest:    $Manifest")
    $r.Add("")
    $r.Add(("Remote (manifest): {0} files, {1} ({2} bytes)" -f $remoteCount, (Format-Size $remoteBytes), $remoteBytes))
    $r.Add(("Local present:     {0} files, {1} ({2} bytes)" -f $presentCount, (Format-Size $presentBytes), $presentBytes))
    $r.Add(("Verified OK:       {0} files, {1} ({2} bytes)" -f $okCount, (Format-Size $okBytes), $okBytes))
    $r.Add(("Complete:          {0}% by count, {1}% by bytes" -f (Get-Pct $okCount $remoteCount), (Get-Pct $okBytes $remoteBytes)))
    $r.Add("")
    $r.Add(("Missing:           {0} files, {1} ({2} bytes)  -> {3}" -f $missingCount, (Format-Size $missingBytes), $missingBytes, $MissingFile))
    $r.Add(("Size mismatch:     {0} files  -> {1}" -f $mismatchCount, $MismatchFile))
    $r.Add(("Extra local (info): {0} files, {1} ({2} bytes)  -> {3}" -f $extraCount, (Format-Size $extraBytes), $extraBytes, $ExtraFile))
    if ($missingCount -gt 0) {
        $r.Add("")
        $r.Add("First missing files:")
        foreach ($m in ($missing | Select-Object -First 10)) {
            $p = $m -split "`t"
            $r.Add(("  - {0} ({1} bytes)" -f $p[0], $p[1]))
        }
    }
    if ($mismatchCount -gt 0) {
        $r.Add("")
        $r.Add("First size mismatches (remote -> local):")
        foreach ($m in ($mismatch | Select-Object -First 10)) {
            $p = $m -split "`t"
            $r.Add(("  - {0} ({1} -> {2} bytes)" -f $p[0], $p[1], $p[2]))
        }
    }
    $r.Add("")
    $r.Add("RESULT: $verdict")
    $r.Add("=============================================")

    foreach ($l in $r) {
        if ($l -like "RESULT: FAIL*") { Write-Host $l -ForegroundColor Red }
        elseif ($l -like "RESULT: PASS*") { Write-Host $l -ForegroundColor Green }
        else { Write-Host $l }
    }
    Set-Content -LiteralPath $ReportFile -Value $r.ToArray() -Encoding utf8

    if ($verdict -eq "PASS") { return 0 }
    return 5
}

# ---------- verify-only mode ----------

if ($VerifyOnly) {
    if (-not (Test-Path -LiteralPath $Manifest -PathType Leaf) -or ((Get-Item -LiteralPath $Manifest).Length -eq 0)) {
        Write-Err "ERROR: manifest not found: $Manifest (run a normal sync first)"
        exit 1
    }
    $rc = Invoke-Verify
    exit $rc
}

# ---------- download mode ----------

if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
    Write-Err "ERROR: jar not found: $Jar - build with mvn package or set ONEDRIVE_JAR"
    exit 1
}

New-Item -ItemType Directory -Force -Path $State | Out-Null
if (-not (Test-Path -LiteralPath $DoneFile)) {
    New-Item -ItemType File -Path $DoneFile | Out-Null
}

Write-Host "Auth check..."
& $JavaBin -jar $Jar auth | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Err "ERROR: auth failed (exit $LASTEXITCODE). Run: java -jar `"$Jar`" auth"
    exit 2
}

$needManifest = $RefreshManifest -or ($env:ONEDRIVE_REFRESH_MANIFEST -eq "1") -or
    (-not (Test-Path -LiteralPath $Manifest)) -or ((Get-Item -LiteralPath $Manifest).Length -eq 0)
if ($needManifest) {
    Write-Host "Listing remote files under '$RemoteRoot' ..."
    $tmp = "$Manifest.tmp"
    $listing = & $JavaBin -jar $Jar --tsv ls-recursive $RemoteRoot
    $listRc = $LASTEXITCODE
    if ($listRc -ne 0) {
        Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
        Write-Err "ERROR: ls-recursive failed (exit $listRc)"
        exit 3
    }
    Set-Content -LiteralPath $tmp -Value @($listing) -Encoding utf8
    Move-Item -LiteralPath $tmp -Destination $Manifest -Force
} else {
    Write-Host "Using existing manifest: $Manifest"
}

$lines = @(Get-Content -LiteralPath $Manifest -Encoding utf8 | Where-Object { $_.Trim() -ne "" })
$Total = $lines.Count
Write-Host "Manifest: $Total files"

$DoneSet = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
Get-Content -LiteralPath $DoneFile -Encoding utf8 -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.Trim()) { [void]$DoneSet.Add($_) }
}

Set-Content -LiteralPath $FailedFile -Value @() -Encoding utf8

$idx = 0
$ok = 0
$skipped = 0
$failed = 0

foreach ($line in $lines) {
    $parts = $line.TrimEnd("`r") -split "`t", 6
    $path = $parts[0]
    $size = if ($parts.Count -gt 1) { $parts[1] } else { "" }
    $kind = if ($parts.Count -gt 4) { $parts[4] } else { "file" }
    if ($kind -and $kind -ne "file") { continue }

    $idx++
    $pct = Get-Pct $idx $Total

    if ($DoneSet.Contains($path)) {
        $skipped++
        Write-Host "[$idx/$Total] $pct% SKIP (done) $path"
        Write-Progress -Activity "OneDrive sync" -Status "Skip $path" -PercentComplete ([double]$pct)
        continue
    }

    $localPath = Get-LocalPath $path
    Write-Host "[$idx/$Total] $pct% $path ($size bytes)"
    Write-Progress -Activity "OneDrive sync" -Status $path -PercentComplete ([double]$pct)

    $out = & $JavaBin -jar $Jar download --skip-same-size $path $localPath 2>&1 | ForEach-Object { "$_" }
    $dlRc = $LASTEXITCODE
    if ($dlRc -eq 0) {
        Add-Content -LiteralPath $DoneFile -Value $path -Encoding utf8
        [void]$DoneSet.Add($path)
        $ok++
        $result = @($out) | Where-Object { $_ -match "^(downloaded|skipped)`t" } | Select-Object -Last 1
        Write-Host "  -> $result"
    } else {
        $errMsg = (@($out) -join " ") -replace "[\r\n]+", " "
        Write-Host "  FAILED (exit $dlRc): $errMsg" -ForegroundColor Red
        Add-Content -LiteralPath $FailedFile -Value ("{0}`t{1}" -f $path, $errMsg) -Encoding utf8
        $failed++
    }
}

Write-Progress -Activity "OneDrive sync" -Completed
Write-Host ""
Write-Host "Summary: total=$Total newly_ok=$ok already_done_skipped=$skipped failed=$failed"
Write-Host "State dir: $State"
Write-Host ""

$verifyRc = Invoke-Verify

if ($failed -gt 0) { exit 3 }
exit $verifyRc
