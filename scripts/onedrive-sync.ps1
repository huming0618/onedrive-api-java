<#
.SYNOPSIS
  Copy all files from OneDrive to a local folder, with progress and resume.

.PARAMETER Dest
  Local destination directory.

.PARAMETER RemoteRoot
  OneDrive folder to sync (default /).

.PARAMETER RefreshManifest
  Rebuild the remote file list even if a manifest already exists.

.EXAMPLE
  .\scripts\onedrive-sync.ps1 -Dest D:\OneDriveBackup
  .\scripts\onedrive-sync.ps1 -Dest D:\Backup -RemoteRoot Documents -RefreshManifest

Environment:
  ONEDRIVE_JAR   path to fat jar
  ONEDRIVE_JAVA  java.exe path (default: java)
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Dest,

    [Parameter(Mandatory = $false, Position = 1)]
    [string]$RemoteRoot = "/",

    [switch]$RefreshManifest
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir
$JavaBin = if ($env:ONEDRIVE_JAVA) { $env:ONEDRIVE_JAVA } else { "java" }
$Jar = if ($env:ONEDRIVE_JAR) { $env:ONEDRIVE_JAR } else {
    Join-Path $RepoRoot "target\onedrive-api-java-1.0.0.jar"
}

if (-not (Test-Path -LiteralPath $Jar)) {
    Write-Error "jar not found: $Jar — build with mvn package or set ONEDRIVE_JAR"
    exit 1
}

function Invoke-OneDriveCli {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
    & $JavaBin -jar $Jar @CliArgs
    if ($LASTEXITCODE -ne 0) {
        throw "CLI exited with code $LASTEXITCODE : java -jar $Jar $($CliArgs -join ' ')"
    }
}

New-Item -ItemType Directory -Force -Path $Dest | Out-Null
$State = Join-Path $Dest ".onedrive-sync"
New-Item -ItemType Directory -Force -Path $State | Out-Null
$Manifest = Join-Path $State "manifest.tsv"
$DoneFile = Join-Path $State "done.txt"
$FailedFile = Join-Path $State "failed.txt"

if (-not (Test-Path -LiteralPath $DoneFile)) {
    New-Item -ItemType File -Path $DoneFile | Out-Null
}

Write-Host "Auth check..."
try {
    Invoke-OneDriveCli auth | Out-Null
} catch {
    Write-Error "auth failed. Run: java -jar `"$Jar`" auth"
    exit 2
}

$needManifest = $RefreshManifest -or $env:ONEDRIVE_REFRESH_MANIFEST -eq "1" -or -not (Test-Path -LiteralPath $Manifest) -or ((Get-Item $Manifest).Length -eq 0)
if ($needManifest) {
    Write-Host "Listing remote files under '$RemoteRoot' ..."
    $tmp = "$Manifest.tmp"
    try {
        & $JavaBin -jar $Jar --tsv ls-recursive $RemoteRoot | Set-Content -LiteralPath $tmp -Encoding utf8
        if ($LASTEXITCODE -ne 0) { throw "ls-recursive failed ($LASTEXITCODE)" }
        Move-Item -LiteralPath $tmp -Destination $Manifest -Force
    } catch {
        Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
        throw
    }
} else {
    Write-Host "Using existing manifest: $Manifest"
}

$lines = @(Get-Content -LiteralPath $Manifest -Encoding utf8 | Where-Object { $_.Trim() -ne "" })
$Total = $lines.Count
Write-Host "Manifest: $Total files"

$DoneSet = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
Get-Content -LiteralPath $DoneFile -Encoding utf8 -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.Trim()) { [void]$DoneSet.Add($_) }
}

Set-Content -LiteralPath $FailedFile -Value "" -Encoding utf8

$idx = 0
$ok = 0
$skipped = 0
$failed = 0

foreach ($line in $lines) {
    $parts = $line -split "`t", 6
    $path = $parts[0]
    $size = if ($parts.Count -gt 1) { $parts[1] } else { "" }
    $kind = if ($parts.Count -gt 4) { $parts[4] } else { "file" }
    if ($kind -and $kind -ne "file") { continue }

    $idx++
    $pct = if ($Total -gt 0) { [math]::Round(($idx * 100.0) / $Total, 1) } else { 0 }

    if ($DoneSet.Contains($path)) {
        $skipped++
        Write-Host "[$idx/$Total] $pct% SKIP (done) $path"
        Write-Progress -Activity "OneDrive sync" -Status "Skip $path" -PercentComplete $pct
        continue
    }

    $localPath = Join-Path $Dest ($path -replace "/", [IO.Path]::DirectorySeparatorChar)
    Write-Host "[$idx/$Total] $pct% $path ($size bytes)"
    Write-Progress -Activity "OneDrive sync" -Status $path -PercentComplete $pct

    try {
        $out = & $JavaBin -jar $Jar download --skip-same-size $path $localPath 2>&1
        if ($LASTEXITCODE -ne 0) { throw ($out | Out-String) }
        Add-Content -LiteralPath $DoneFile -Value $path -Encoding utf8
        [void]$DoneSet.Add($path)
        $ok++
        Write-Host "  -> $out"
    } catch {
        $errMsg = "$_" -replace "[\r\n]+", " "
        Write-Host "  FAILED: $errMsg"
        Add-Content -LiteralPath $FailedFile -Value ("{0}`t{1}" -f $path, $errMsg) -Encoding utf8
        $failed++
    }
}

Write-Progress -Activity "OneDrive sync" -Completed
Write-Host ""
Write-Host "Summary: total=$Total newly_ok=$ok already_done_skipped=$skipped failed=$failed"
Write-Host "State dir: $State"

if ($failed -gt 0) {
    exit 3
}
exit 0
