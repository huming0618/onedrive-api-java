#!/usr/bin/env bash
# Copy all files from OneDrive to a local folder, with progress, resume and
# a final verification report (local vs. remote count / bytes).
#
# Usage:
#   ./scripts/onedrive-sync.sh [--verify-only] [--daemon] <localDest> [remoteRoot]
#   ./scripts/onedrive-sync.sh --status|--stop <localDest>
#
# Env:
#   ONEDRIVE_JAR   path to fat jar (default: <repo>/target/onedrive-api-java-1.0.0.jar)
#   ONEDRIVE_JAVA  java binary (default: java)
#   ONEDRIVE_REFRESH_MANIFEST=1  rebuild file list even if manifest exists
#   ONEDRIVE_VERIFY_ONLY=1       same as --verify-only
#
# State (under <localDest>/.onedrive-sync/):
#   manifest.tsv        remote file list (path\tsize\tid\tlastModified\tkind\tname)
#   done.txt            completed remote paths (one per line)
#   failed.txt          failed remote paths + error (tab-separated)
#   verify-report.txt   last verification report (with timestamp)
#   missing.txt         in manifest but not on disk (path\tremoteSize)
#   size-mismatch.txt   size differs (path\tremoteSize\tlocalSize)
#   extra.txt           local files not in manifest (informational)
#
# Exit codes:
#   0 success (no download failures, verification PASS)
#   1 usage error / jar or manifest missing
#   2 auth failed
#   3 some downloads failed (takes precedence over 5)
#   5 verification FAIL (missing files or size mismatch)
#
# Requires Bash 4+. Must run under bash with LF line endings (not sh/dash, not CRLF).
# Check BASH_VERSION with POSIX syntax first: under dash, set -o pipefail fails
# with "invalid option name" / 无效的选项名.
# If you see /usr/bin/env: "bash\r": No such file — convert CRLF to LF:
#   sed -i 's/\r$//' scripts/onedrive-sync.sh
if [ -z "${BASH_VERSION:-}" ]; then
  echo "ERROR: 请用 bash 运行本脚本（Ubuntu 上 sh 多为 dash，不支持 pipefail）。" >&2
  echo "ERROR: Run with bash, not sh. Example:" >&2
  echo "       bash $0 <localDest> [remoteRoot]" >&2
  exit 1
fi
set -euo pipefail

if [[ "${BASH_VERSINFO[0]}" -lt 4 ]]; then
  echo "ERROR: Bash 4+ required (macOS: brew install bash)" >&2
  exit 1
fi

usage() {
  cat <<'USAGE'
Usage: onedrive-sync.sh [--verify-only] [--daemon] <localDest> [remoteRoot]
       onedrive-sync.sh --status <localDest>
       onedrive-sync.sh --stop   <localDest>

  localDest      Local directory to receive files (created if missing)
  remoteRoot     OneDrive folder to sync (default: /)
  --verify-only  Do not download; only compare the existing manifest
                 (<dest>/.onedrive-sync/manifest.tsv) with local files
  --daemon       Run in the background (setsid + nohup); keeps running after
                 you log out or close the terminal/SSH session. Output goes to
                 <dest>/.onedrive-sync/sync.log, PID to sync.pid
  --status       Show whether a background sync is running, plus the log tail
  --stop         Stop the background sync (re-run later to resume)

Environment:
  ONEDRIVE_JAR              Fat jar path
  ONEDRIVE_JAVA             Java launcher (default: java)
  ONEDRIVE_REFRESH_MANIFEST Set to 1 to rebuild the remote file list
  ONEDRIVE_VERIFY_ONLY      Set to 1 to behave like --verify-only

Resume: completed paths are recorded in <dest>/.onedrive-sync/done.txt.
Re-run the same command to continue after interruptions.

After downloading, a verification report is printed and saved to
<dest>/.onedrive-sync/verify-report.txt (plus missing.txt, size-mismatch.txt).

Exit codes:
  0  success: no download failures and verification PASS
  1  usage error, jar not found, or manifest missing (verify-only)
  2  authentication failed
  3  one or more downloads failed (takes precedence over 5)
  5  verification FAIL (missing files or size mismatch)
USAGE
}

VERIFY_ONLY=0
[[ "${ONEDRIVE_VERIFY_ONLY:-0}" == "1" ]] && VERIFY_ONLY=1
DAEMON=0
ACTION=run
POSITIONAL=()
PASS_ARGS=()   # args forwarded to the background child (everything but --daemon)
for arg in "$@"; do
  case "$arg" in
    -h|--help) usage; exit 0 ;;
    --verify-only) VERIFY_ONLY=1; PASS_ARGS+=("$arg") ;;
    --daemon|-d) DAEMON=1 ;;
    --status) ACTION=status ;;
    --stop) ACTION=stop ;;
    -*) echo "ERROR: unknown option: $arg" >&2; usage >&2; exit 1 ;;
    *) POSITIONAL+=("$arg"); PASS_ARGS+=("$arg") ;;
  esac
done

if [[ ${#POSITIONAL[@]} -lt 1 ]]; then
  usage >&2
  exit 1
fi

DEST="${POSITIONAL[0]}"
[[ "$DEST" != "/" ]] && DEST="${DEST%/}"
REMOTE_ROOT="${POSITIONAL[1]:-/}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JAVA_BIN="${ONEDRIVE_JAVA:-java}"
JAR="${ONEDRIVE_JAR:-$REPO_ROOT/target/onedrive-api-java-1.0.0.jar}"

STATE="$DEST/.onedrive-sync"
MANIFEST="$STATE/manifest.tsv"
DONE_FILE="$STATE/done.txt"
FAILED_FILE="$STATE/failed.txt"
REPORT_FILE="$STATE/verify-report.txt"
MISSING_FILE="$STATE/missing.txt"
MISMATCH_FILE="$STATE/size-mismatch.txt"
EXTRA_FILE="$STATE/extra.txt"
PID_FILE="$STATE/sync.pid"
LOG_FILE="$STATE/sync.log"

# ---------- daemon / status / stop ----------

running_pid() {
  # Prints the PID of a live background sync for this destination, if any.
  local pid
  [[ -s "$PID_FILE" ]] || return 1
  pid=$(<"$PID_FILE")
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  echo "$pid"
}

if [[ "$ACTION" == "status" ]]; then
  if pid=$(running_pid); then
    echo "RUNNING: pid=$pid  log=$LOG_FILE"
  else
    echo "NOT RUNNING  (log: $LOG_FILE)"
  fi
  if [[ -f "$LOG_FILE" ]]; then
    echo "----- last 15 log lines -----"
    tail -n 15 "$LOG_FILE"
  fi
  exit 0
fi

if [[ "$ACTION" == "stop" ]]; then
  if pid=$(running_pid); then
    # Kill the whole process group (setsid made the script its leader),
    # so the running java download stops too.
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
      kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
    echo "Stopped background sync (pid=$pid). Re-run the same command to resume."
  else
    rm -f "$PID_FILE"
    echo "No background sync running for $DEST"
  fi
  exit 0
fi

if [[ "$DAEMON" -eq 1 && "${ONEDRIVE_SYNC_CHILD:-0}" != "1" ]]; then
  mkdir -p "$STATE"
  if pid=$(running_pid); then
    echo "ERROR: a background sync is already running (pid=$pid)." >&2
    echo "       Check: bash $0 --status \"$DEST\"   Stop: bash $0 --stop \"$DEST\"" >&2
    exit 1
  fi
  if [[ "$VERIFY_ONLY" -ne 1 && -f "$JAR" ]]; then
    # Log in (or refresh) in the foreground first: a device-code prompt
    # would otherwise be hidden in the background log.
    echo "Auth check (foreground) ..." >&2
    if ! "$JAVA_BIN" -jar "$JAR" auth >/dev/null; then
      echo "ERROR: auth failed. Run: java -jar \"$JAR\" auth" >&2
      exit 2
    fi
  fi
  SELF="$SCRIPT_DIR/$(basename "${BASH_SOURCE[0]}")"
  {
    echo
    echo "===== background sync started $(date '+%Y-%m-%d %H:%M:%S %Z') ====="
  } >>"$LOG_FILE"
  ONEDRIVE_SYNC_CHILD=1 ONEDRIVE_JAR="$JAR" ONEDRIVE_JAVA="$JAVA_BIN" \
    setsid nohup bash "$SELF" "${PASS_ARGS[@]}" >>"$LOG_FILE" 2>&1 </dev/null &
  child=$!
  echo "$child" >"$PID_FILE"
  sleep 1
  if kill -0 "$child" 2>/dev/null; then
    echo "Started background sync: pid=$child"
  else
    echo "WARN: background process exited right away; see log below" >&2
  fi
  echo "Log:    tail -f \"$LOG_FILE\""
  echo "Status: bash \"$SELF\" --status \"$DEST\""
  echo "Stop:   bash \"$SELF\" --stop \"$DEST\""
  [[ -f "$LOG_FILE" ]] && tail -n 5 "$LOG_FILE"
  exit 0
fi

if [[ "${ONEDRIVE_SYNC_CHILD:-0}" == "1" ]]; then
  # Running as the background child: record our PID and clean it up on exit.
  mkdir -p "$STATE"
  echo "$$" >"$PID_FILE"
  trap 'echo "===== background sync stopped (signal) $(date "+%Y-%m-%d %H:%M:%S %Z") ====="; exit 130' TERM INT
fi

# ---------- helpers ----------

file_size() {
  # GNU stat first, then BSD/macOS stat
  stat -c %s -- "$1" 2>/dev/null || stat -f %z -- "$1" 2>/dev/null || stat -f %z "$1"
}

human_size() {
  awk -v b="$1" 'BEGIN{
    split("B KB MB GB TB PB", u, " "); i=1; v=b+0;
    while (v >= 1024 && i < 6) { v /= 1024; i++ }
    if (i == 1) printf "%d B", v; else printf "%.2f %s", v, u[i]
  }'
}

pct() {
  # pct <part> <total>
  awk -v p="$1" -v t="$2" 'BEGIN{ if (t+0 == 0) printf "100.0"; else printf "%.1f", (p*100)/t }'
}

# verify: compare manifest with local files; prints report, writes state files.
# Returns 0 on PASS, 5 on FAIL.
verify() {
  local remote_count=0 remote_bytes=0
  local present_count=0 present_bytes=0
  local ok_count=0 ok_bytes=0
  local missing_count=0 missing_bytes=0
  local mismatch_count=0
  local extra_count=0 extra_bytes=0
  local path size id lastModified kind name local_path lsize

  declare -A IN_MANIFEST=()
  : >"$MISSING_FILE"
  : >"$MISMATCH_FILE"
  : >"$EXTRA_FILE"

  while IFS=$'\t' read -r path size id lastModified kind name || [[ -n "${path:-}" ]]; do
    path="${path%$'\r'}"
    kind="${kind%$'\r'}"
    [[ -z "${path:-}" ]] && continue
    if [[ -n "${kind:-}" && "$kind" != "file" ]]; then
      continue
    fi
    [[ "$size" =~ ^[0-9]+$ ]] || size=""
    IN_MANIFEST["$path"]=1
    remote_count=$((remote_count + 1))
    remote_bytes=$((remote_bytes + ${size:-0}))

    local_path="$DEST/$path"
    if [[ -f "$local_path" ]]; then
      lsize=$(file_size "$local_path")
      present_count=$((present_count + 1))
      present_bytes=$((present_bytes + lsize))
      if [[ -n "$size" && "$lsize" -ne "$size" ]]; then
        mismatch_count=$((mismatch_count + 1))
        printf '%s\t%s\t%s\n' "$path" "$size" "$lsize" >>"$MISMATCH_FILE"
      else
        ok_count=$((ok_count + 1))
        ok_bytes=$((ok_bytes + lsize))
      fi
    else
      missing_count=$((missing_count + 1))
      missing_bytes=$((missing_bytes + ${size:-0}))
      printf '%s\t%s\n' "$path" "${size:-}" >>"$MISSING_FILE"
    fi
  done <"$MANIFEST"

  # Extra local files (informational), excluding state dir and *.part temp files
  local f rel
  while IFS= read -r -d '' f; do
    rel="${f#"$DEST"/}"
    [[ -n "${IN_MANIFEST[$rel]:-}" ]] && continue
    extra_count=$((extra_count + 1))
    lsize=$(file_size "$f")
    extra_bytes=$((extra_bytes + lsize))
    printf '%s\t%s\n' "$rel" "$lsize" >>"$EXTRA_FILE"
  done < <(find "$DEST" -path "$STATE" -prune -o -type f -print0 2>/dev/null)

  local verdict="PASS"
  if [[ $missing_count -gt 0 || $mismatch_count -gt 0 || $ok_count -ne $remote_count ]]; then
    verdict="FAIL"
  fi

  {
    echo "===== OneDrive sync verification report ====="
    echo "Time:        $(date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "Destination: $DEST"
    echo "Manifest:    $MANIFEST"
    echo
    printf 'Remote (manifest): %d files, %s (%s bytes)\n' \
      "$remote_count" "$(human_size "$remote_bytes")" "$remote_bytes"
    printf 'Local present:     %d files, %s (%s bytes)\n' \
      "$present_count" "$(human_size "$present_bytes")" "$present_bytes"
    printf 'Verified OK:       %d files, %s (%s bytes)\n' \
      "$ok_count" "$(human_size "$ok_bytes")" "$ok_bytes"
    printf 'Complete:          %s%% by count, %s%% by bytes\n' \
      "$(pct "$ok_count" "$remote_count")" "$(pct "$ok_bytes" "$remote_bytes")"
    echo
    printf 'Missing:           %d files, %s (%s bytes)  -> %s\n' \
      "$missing_count" "$(human_size "$missing_bytes")" "$missing_bytes" "$MISSING_FILE"
    printf 'Size mismatch:     %d files  -> %s\n' "$mismatch_count" "$MISMATCH_FILE"
    printf 'Extra local (info): %d files, %s (%s bytes)  -> %s\n' \
      "$extra_count" "$(human_size "$extra_bytes")" "$extra_bytes" "$EXTRA_FILE"

    if [[ $missing_count -gt 0 ]]; then
      echo
      echo "First missing files:"
      head -n 10 "$MISSING_FILE" | awk -F'\t' '{printf "  - %s (%s bytes)\n", $1, $2}'
    fi
    if [[ $mismatch_count -gt 0 ]]; then
      echo
      echo "First size mismatches (remote -> local):"
      head -n 10 "$MISMATCH_FILE" | awk -F'\t' '{printf "  - %s (%s -> %s bytes)\n", $1, $2, $3}'
    fi
    echo
    echo "RESULT: $verdict"
    echo "============================================="
  } | tee "$REPORT_FILE" >&2

  [[ "$verdict" == "PASS" ]] && return 0
  return 5
}

# ---------- verify-only mode ----------

if [[ "$VERIFY_ONLY" -eq 1 ]]; then
  if [[ ! -s "$MANIFEST" ]]; then
    echo "ERROR: manifest not found: $MANIFEST (run a normal sync first)" >&2
    exit 1
  fi
  rc=0
  verify || rc=$?
  [[ "${ONEDRIVE_SYNC_CHILD:-0}" == "1" ]] && rm -f "$PID_FILE"
  exit "$rc"
fi

# ---------- download mode ----------

if [[ ! -f "$JAR" ]]; then
  echo "ERROR: jar not found: $JAR" >&2
  echo "Build with: mvn -q package  (or set ONEDRIVE_JAR)" >&2
  exit 1
fi

cli() {
  "$JAVA_BIN" -jar "$JAR" "$@"
}

mkdir -p "$STATE"
touch "$DONE_FILE"

echo "Auth check..." >&2
if ! cli auth >/dev/null; then
  echo "ERROR: auth failed. Run: java -jar \"$JAR\" auth" >&2
  exit 2
fi

need_manifest=0
if [[ "${ONEDRIVE_REFRESH_MANIFEST:-0}" == "1" ]]; then
  need_manifest=1
elif [[ ! -s "$MANIFEST" ]]; then
  need_manifest=1
fi

if [[ "$need_manifest" -eq 1 ]]; then
  echo "Listing remote files under '$REMOTE_ROOT' ..." >&2
  if cli --tsv ls-recursive "$REMOTE_ROOT" >"$MANIFEST.tmp"; then
    mv "$MANIFEST.tmp" "$MANIFEST"
  else
    rm -f "$MANIFEST.tmp"
    echo "ERROR: ls-recursive failed" >&2
    exit 3
  fi
  echo "Manifest: $(wc -l <"$MANIFEST") files -> $MANIFEST" >&2
else
  echo "Using existing manifest: $MANIFEST ($(wc -l <"$MANIFEST") files)" >&2
fi

declare -A DONE_MAP=()
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" ]] && continue
  DONE_MAP["$line"]=1
done <"$DONE_FILE"

TOTAL=$(grep -c . "$MANIFEST" || true)
: >"$FAILED_FILE"

idx=0
ok=0
skipped=0
failed=0
ERR_TMP="$(mktemp "${TMPDIR:-/tmp}/onedrive-sync-err.XXXXXX")"
cleanup() {
  rm -f "$ERR_TMP"
  if [[ "${ONEDRIVE_SYNC_CHILD:-0}" == "1" && -f "$PID_FILE" && "$(<"$PID_FILE")" == "$$" ]]; then
    rm -f "$PID_FILE"
  fi
}
trap cleanup EXIT

while IFS=$'\t' read -r path size id lastModified kind name || [[ -n "${path:-}" ]]; do
  path="${path%$'\r'}"
  kind="${kind%$'\r'}"
  [[ -z "${path:-}" ]] && continue
  if [[ -n "${kind:-}" && "$kind" != "file" ]]; then
    continue
  fi
  idx=$((idx + 1))
  p="$(pct "$idx" "$TOTAL")"
  if [[ -n "${DONE_MAP[$path]:-}" ]]; then
    skipped=$((skipped + 1))
    echo "[$idx/$TOTAL] ${p}% SKIP (done) $path" >&2
    continue
  fi

  local_path="$DEST/$path"
  echo "[$idx/$TOTAL] ${p}% $path (${size:-?} bytes)" >&2

  if out=$(cli download --skip-same-size "$path" "$local_path" 2>"$ERR_TMP"); then
    echo "$path" >>"$DONE_FILE"
    DONE_MAP["$path"]=1
    ok=$((ok + 1))
    echo "  -> $out" >&2
  else
    err_msg=$(tr '\n' ' ' <"$ERR_TMP" 2>/dev/null || true)
    echo "  FAILED: $err_msg" >&2
    printf '%s\t%s\n' "$path" "$err_msg" >>"$FAILED_FILE"
    failed=$((failed + 1))
  fi
done <"$MANIFEST"

echo >&2
echo "Summary: total=$TOTAL newly_ok=$ok already_done_skipped=$skipped failed=$failed" >&2
echo "State dir: $STATE" >&2
echo >&2

verify_rc=0
verify || verify_rc=$?

final_rc="$verify_rc"
[[ "$failed" -gt 0 ]] && final_rc=3
if [[ "${ONEDRIVE_SYNC_CHILD:-0}" == "1" ]]; then
  echo "===== background sync finished $(date '+%Y-%m-%d %H:%M:%S %Z') exit=$final_rc ====="
fi
exit "$final_rc"
