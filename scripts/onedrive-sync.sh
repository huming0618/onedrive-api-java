#!/usr/bin/env bash
# Copy all files from OneDrive to a local folder, with progress and resume.
#
# Usage:
#   ./scripts/onedrive-sync.sh <localDest> [remoteRoot]
#
# Env:
#   ONEDRIVE_JAR   path to fat jar (default: <repo>/target/onedrive-api-java-1.0.0.jar)
#   ONEDRIVE_JAVA  java binary (default: java)
#   ONEDRIVE_REFRESH_MANIFEST=1  rebuild file list even if manifest exists
#
# State (under <localDest>/.onedrive-sync/):
#   manifest.tsv   remote path list (path\tsize\tid\tlastModified\tkind\tname)
#   done.txt       completed remote paths (one per line)
#   failed.txt     failed remote paths + error (tab-separated)
#
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: onedrive-sync.sh <localDest> [remoteRoot]

  localDest    Local directory to receive files (created if missing)
  remoteRoot   OneDrive folder to sync (default: /)

Environment:
  ONEDRIVE_JAR              Fat jar path
  ONEDRIVE_JAVA             Java launcher (default: java)
  ONEDRIVE_REFRESH_MANIFEST Set to 1 to rebuild the remote file list

Resume: completed paths are recorded in <dest>/.onedrive-sync/done.txt.
Re-run the same command to continue after interruptions.
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || $# -lt 1 ]]; then
  usage
  exit 1
fi

DEST="${1%/}"
REMOTE_ROOT="${2:-/}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JAVA_BIN="${ONEDRIVE_JAVA:-java}"
JAR="${ONEDRIVE_JAR:-$REPO_ROOT/target/onedrive-api-java-1.0.0.jar}"

if [[ ! -f "$JAR" ]]; then
  echo "ERROR: jar not found: $JAR" >&2
  echo "Build with: mvn -q package  (or set ONEDRIVE_JAR)" >&2
  exit 1
fi

cli() {
  "$JAVA_BIN" -jar "$JAR" "$@"
}

mkdir -p "$DEST"
STATE="$DEST/.onedrive-sync"
mkdir -p "$STATE"
MANIFEST="$STATE/manifest.tsv"
DONE_FILE="$STATE/done.txt"
FAILED_FILE="$STATE/failed.txt"
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
  # Prefer --tsv (no jq required). Fall back to --json + jq if present.
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

# Build done set
declare -A DONE_MAP=()
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" ]] && continue
  DONE_MAP["$line"]=1
done <"$DONE_FILE"

TOTAL=$(grep -c . "$MANIFEST" || true)
if [[ "$TOTAL" -eq 0 ]]; then
  echo "Nothing to download." >&2
  exit 0
fi

# Clear previous failed list for this run (append during run)
: >"$FAILED_FILE"

idx=0
ok=0
skipped=0
failed=0

while IFS=$'\t' read -r path size id lastModified kind name || [[ -n "${path:-}" ]]; do
  [[ -z "${path:-}" ]] && continue
  # Only files (tsv kind column)
  if [[ -n "${kind:-}" && "$kind" != "file" ]]; then
    continue
  fi
  idx=$((idx + 1))
  if [[ -n "${DONE_MAP[$path]:-}" ]]; then
    skipped=$((skipped + 1))
    pct=$(awk -v i="$idx" -v t="$TOTAL" 'BEGIN{printf "%.1f", (i*100)/t}')
    echo "[$idx/$TOTAL] ${pct}% SKIP (done) $path" >&2
    continue
  fi

  local_path="$DEST/$path"
  pct=$(awk -v i="$idx" -v t="$TOTAL" 'BEGIN{printf "%.1f", (i*100)/t}')
  size_disp="${size:-?}"
  echo "[$idx/$TOTAL] ${pct}% $path ($size_disp bytes)" >&2

  if out=$(cli download --skip-same-size "$path" "$local_path" 2>/tmp/onedrive-sync-err.$$); then
    echo "$path" >>"$DONE_FILE"
    DONE_MAP["$path"]=1
    ok=$((ok + 1))
    echo "  -> $out" >&2
  else
    err_msg=$(tr '\n' ' ' </tmp/onedrive-sync-err.$$ 2>/dev/null || true)
    echo "  FAILED: $err_msg" >&2
    printf '%s\t%s\n' "$path" "$err_msg" >>"$FAILED_FILE"
    failed=$((failed + 1))
  fi
  rm -f /tmp/onedrive-sync-err.$$
done <"$MANIFEST"

echo >&2
echo "Summary: total=$TOTAL newly_ok=$ok already_done_skipped=$skipped failed=$failed" >&2
echo "State dir: $STATE" >&2

if [[ "$failed" -gt 0 ]]; then
  exit 3
fi
exit 0
