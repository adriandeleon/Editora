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
#
# Portable across Linux and macOS: `timeout` and `date +%s%3N` are GNU-only and neither exists on a
# stock macOS, so both are capability-detected below rather than assumed.
set -euo pipefail

# --- portability ------------------------------------------------------------------------------
#
# A millisecond clock. GNU date does it directly; BSD date has no %N and emits a literal (measured
# on macOS: `date +%s%3N` -> "17864736693N"), which is digits followed by junk and so would survive
# a laxer check than the fully-numeric one below. Perl is the fallback because macOS ships it at
# /usr/bin/perl and every Linux has it; python3 is third because a clean macOS has no python3 until
# the Xcode tools are installed. Resolved once, not per run.
if date +%s%3N 2>/dev/null | grep -Eq '^[0-9]+$'; then
    now_ms() { date +%s%3N; }
elif command -v perl >/dev/null 2>&1; then
    now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time() * 1000'; }
elif command -v python3 >/dev/null 2>&1; then
    now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
else
    echo "need a millisecond clock: GNU date, perl, or python3 (none found)" >&2
    exit 2
fi

# `timeout` is only a backstop — the app halts itself at first paint under EDITORA_PERF_EXIT — so
# when it is missing (stock macOS; `gtimeout` is the Homebrew coreutils name) the run simply goes
# without one rather than failing, which is what it used to do.
runner=()
if command -v timeout >/dev/null 2>&1; then
    runner=(timeout 60)
elif command -v gtimeout >/dev/null 2>&1; then
    runner=(gtimeout 60)
fi

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
    # The header block itself, rather than a hardcoded line range: the range had already drifted out
    # of date once, silently truncating the help.
    awk 'NR > 1 && /^#/ { print; next } NR > 1 { exit }' "$0" >&2
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
    EDITORA_PERF_T0=$(now_ms) ${runner[@]+"${runner[@]}"} "$launcher" "$@" >/dev/null 2>"$tmp/run$i.txt" || true
    # `|| true` matters: with `set -e` and `pipefail`, a grep that matches nothing fails the whole
    # pipeline and kills the script HERE — so the diagnostic below was unreachable on the one path
    # that needs it, and a failed measurement exited 1 having explained nothing.
    ttfp=$(grep -o 'TIME-TO-FIRST-PAINT [0-9]*' "$tmp/run$i.txt" | awk '{print $2}' | head -1 || true)
    if [ -z "$ttfp" ]; then
        # Keep the log: $tmp goes away with the EXIT trap, so naming a path inside it sent the reader
        # to a file that had already been deleted.
        # An explicit template, not `mktemp -t`: BSD mktemp treats -t's argument as a prefix and adds
        # its own suffix, leaving a literal "XXXXXX" in the middle of the name.
        tmpbase=${TMPDIR:-/tmp}
        kept=$(mktemp "${tmpbase%/}/editora-startup-run.XXXXXX")
        cp "$tmp/run$i.txt" "$kept"
        echo "run $i: NO REPORT — the run produced no [perf] line. Its output is below and kept at $kept." >&2
        echo "Common causes: no display (it renders a real window), a launcher that is not Editora, or a" >&2
        echo "build without the perf instrumentation. EDITORA_PERF=1 and EDITORA_PERF_EXIT=1 were set." >&2
        cat "$kept" >&2
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
