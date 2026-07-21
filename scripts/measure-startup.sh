#!/usr/bin/env bash
# Measure Editora's time-to-first-paint: process start → the first rendered frame showing the file.
#
# Runs the app N times with the perf instrumentation on (com.editora.perf.Startup), letting each run exit
# the moment it paints, and reports the median plus each phase. Median, not mean: the first run is always
# cold (page cache, GPU driver init) and would drag an average around.
#
# Usage:
#   scripts/measure-startup.sh [-n RUNS] [-c CONFIG_DIR] [--] LAUNCHER [ARGS...] FILE
#
# Examples:
#   scripts/measure-startup.sh -n 7 /opt/editora/bin/Editora --expert --single-window ~/Downloads/cv.typ
#   scripts/measure-startup.sh -n 7 -c /tmp/cfg /opt/editora/bin/Editora --no-session ~/Downloads/cv.typ
#
# Needs a display (it renders a real window). Each run is killed as soon as it reports.
set -euo pipefail

runs=5
config_dir=""
while [ $# -gt 0 ]; do
    case "$1" in
        -n) runs=$2; shift 2 ;;
        -c) config_dir=$2; shift 2 ;;
        --) shift; break ;;
        *) break ;;
    esac
done

if [ $# -lt 1 ]; then
    sed -n '2,17p' "$0" >&2
    exit 2
fi

launcher=$1; shift

export EDITORA_PERF=1
export EDITORA_PERF_EXIT=1   # halt at first paint, so a run measures only what we're timing
[ -n "$config_dir" ] && export EDITORA_CONFIG_DIR=$config_dir

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

echo "measuring $runs runs of: $launcher $*"
[ -n "$config_dir" ] && echo "config dir: $config_dir"
echo

for i in $(seq 1 "$runs"); do
    # Stamp T0 immediately before exec so the app measures from the real launch instant. Without this it
    # falls back to ProcessHandle's process start, which on Linux is derived from boot time and drifted
    # ~500 ms high here — inflating every phase. stderr carries the [perf] report; the app halts itself at
    # first paint, so the timeout is only a backstop.
    EDITORA_PERF_T0=$(date +%s%3N) timeout 60 "$launcher" "$@" >/dev/null 2>"$tmp/run$i.txt" || true
    ttfp=$(grep -o 'TIME-TO-FIRST-PAINT [0-9]*' "$tmp/run$i.txt" | awk '{print $2}' | head -1)
    if [ -z "$ttfp" ]; then
        echo "run $i: NO REPORT — the run never painted (see $tmp/run$i.txt); is a display available?" >&2
        cat "$tmp/run$i.txt" >&2
        exit 1
    fi
    echo "run $i: ${ttfp} ms"
    echo "$ttfp" >> "$tmp/all.txt"
    sleep 1
done

echo
echo "--- phases (last run, ms since process start) ---"
grep '^\[perf\]' "$tmp/run$runs.txt" | grep -v 'startup (ms' || true

echo
sort -n "$tmp/all.txt" | awk '
    { v[NR] = $1 }
    END {
        median = (NR % 2) ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2
        printf "runs=%d  min=%d  median=%d  max=%d ms\n", NR, v[1], median, v[NR]
    }'
