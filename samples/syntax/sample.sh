#!/usr/bin/env bash
#
# A small shell sample for syntax highlighting and folding.
# Covers functions, parameter expansion, arrays, an associative array, a case
# statement, loops, a here-document, command substitution, and a trap.
set -euo pipefail

readonly MAX_ITEMS=$((0x40)) # arithmetic expansion
declare -a items=("hammer" "anvil" "saw")
declare -A weights=([hammer]=1 [anvil]=45 [saw]=2)

cleanup() {
  local status=$?
  [[ -n "${tmpfile:-}" ]] && rm -f "$tmpfile"
  return "$status"
}
trap cleanup EXIT

describe() {
  local name="${1:?describe needs a name}"
  local weight="${weights[$name]:-0}"
  case "$weight" in
    0) echo "unknown: $name" ;;
    [1-9]) echo "light: $name" ;;
    *) echo "heavy: ${name^^}" ;;
  esac
}

usage() {
  cat <<USAGE
Usage: $(basename "$0") [name...]
Lists up to ${MAX_ITEMS} items.
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

tmpfile="$(mktemp)"
for name in "${@:-${items[@]}}"; do
  describe "$name" >>"$tmpfile"
done

count=0
while IFS= read -r line; do
  count=$((count + 1))
  printf '%d. %s\n' "$count" "$line"
done <"$tmpfile"

echo "Listed ${#items[@]} known items; the first is '${items[0]}'."
if (( count > MAX_ITEMS )); then
  echo "error: too many items" >&2
  exit 1
fi
