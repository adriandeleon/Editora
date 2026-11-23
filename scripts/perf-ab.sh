#!/usr/bin/env bash
# A/B performance comparison of this checkout against an earlier ref (normally the previous release tag): the
# pre-release performance review, step 3 of "Cutting a release" in docs/release.md.
#
# Usage:
#   scripts/perf-ab.sh <baseline-ref> [reps]          e.g. scripts/perf-ab.sh v0.28.0 3
#
# What it does:
#   1. Checks <baseline-ref> out in a detached worktree beside the main checkout
#      (../<repo>-worktrees/perf-base-<ref>), or reuses it, and builds both trees (mvn test-compile).
#   2. Compiles ONE probe source, src/perf/java/com/editora/ui/PerfProbe.java from this checkout, against each
#      build, so both sides run the same measurement code. If it does not compile against the baseline (an API
#      the probe needs directly is missing there), it falls back to the baseline's own copy of the probe, if any.
#   3. Runs every mode headless, interleaved (new, base, then base, new on the next rep, ...) so machine load
#      hits both sides alike, and prints a median before/after table. Anything more than 10 % worse is flagged.
#
# Startup is not covered: it needs a display. Use scripts/measure-startup.sh on both builds.
#
# Environment:
#   PERF_MODES      modes to run, "mode:arg" separated by spaces
#                   (default "typing:2000 scroll:20000 idle hl:79000 open:20 memory:30 find:45 save bare:2000")
#   PERF_CPUS       cores the runs may use (default: a quarter of the machine, 2..4). Shared machine: never all.
#   PERF_JAVA_HOME  JDK to build and run with (default ~/.jkit/global/jdk if present, else JAVA_HOME). JDK 25:
#                   Spotless's palantir formatter breaks on newer JDKs, and the release builds on 25.
#   PERF_JAVA_OPTS  extra JVM flags for every probe run, e.g. to record JFR:
#                   PERF_JAVA_OPTS="-XX:StartFlightRecording -Dprobe.jfr=/tmp/probe.jfr"
#   PERF_OUT        output directory (default target/perf-ab/<timestamp>): run logs, metrics.tsv, summary.md
#   PERF_BASE_DIR   where the baseline worktree goes
#   PERF_SKIP_BUILD set to 1 to reuse both trees' existing target/ (after a first run)
#   PERF_TIMEOUT    seconds before one probe run is killed (default 900)
#   PERF_RESUMMARIZE set to 1 (with PERF_OUT=<an earlier run's directory>) to print its table again, running nothing
#
# Every metric is lower-is-better. Medians of 2 reps are coarse; use 3 or more for a release, and re-run a
# flagged mode alone (PERF_MODES=typing:2000 ...) before chasing it. The "spread" column is the larger of the two
# sides' (max - min) / median. A worse change smaller than the spread is marked ▲? (likely noise: re-run that mode,
# with more reps or on a quieter machine); a worse change larger than the spread is marked ▲.
set -euo pipefail

die() { echo "perf-ab: $*" >&2; exit 1; }
[ $# -ge 1 ] && [ $# -le 2 ] || { sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
BASE_REF=$1
REPS=${2:-3}
[[ $REPS =~ ^[1-9][0-9]*$ ]] || die "reps must be a positive number, got '$REPS'"

NEW_DIR=$(git rev-parse --show-toplevel)
cd "$NEW_DIR"
BASE_SHA=$(git rev-parse --verify --quiet "$BASE_REF^{commit}") || die "unknown ref '$BASE_REF' (git fetch --tags?)"
NEW_SHA=$(git rev-parse HEAD)
MAIN=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")
BASE_DIR=${PERF_BASE_DIR:-$(dirname "$MAIN")/$(basename "$MAIN")-worktrees/perf-base-${BASE_REF//\//-}}
MODES=${PERF_MODES:-"typing:2000 scroll:20000 idle hl:79000 open:20 memory:30 find:45 save bare:2000"}
TIMEOUT=${PERF_TIMEOUT:-900}
OUT=${PERF_OUT:-$NEW_DIR/target/perf-ab/$(date +%Y%m%d-%H%M%S)}
mkdir -p "$OUT/runs"
OUT=$(cd "$OUT" && pwd)

if [ -n "${PERF_JAVA_HOME:-}" ]; then
    export JAVA_HOME=$PERF_JAVA_HOME
elif [ -x "$HOME/.jkit/global/jdk/bin/java" ]; then
    export JAVA_HOME=$HOME/.jkit/global/jdk
fi
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] || die "no JDK: set PERF_JAVA_HOME or JAVA_HOME (JDK 25)"
export PATH=$JAVA_HOME/bin:$PATH

# --- a share of the machine, never all of it -------------------------------------------------------------
NCPU=$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)
CPUS=${PERF_CPUS:-$(( NCPU / 4 ))}
(( CPUS < 2 )) && CPUS=2
(( CPUS > 4 )) && [ -z "${PERF_CPUS:-}" ] && CPUS=4
(( CPUS >= NCPU && NCPU > 1 )) && CPUS=$(( NCPU - 1 ))
PIN=()
if command -v taskset >/dev/null 2>&1; then
    PIN=(taskset -c "0-$(( CPUS - 1 ))") # Linux: the runs really get only these cores
fi

RESUMMARIZE=${PERF_RESUMMARIZE:-}
if [ "$RESUMMARIZE" = 1 ]; then
    [ -f "$OUT/metrics.tsv" ] && [ -f "$OUT/run.env" ] || die "PERF_RESUMMARIZE needs PERF_OUT=<an earlier run's directory>"
    # shellcheck disable=SC1091
    . "$OUT/run.env"
else
# --- the baseline worktree -------------------------------------------------------------------------------
if [ -d "$BASE_DIR" ]; then
    if [ "$(git -C "$BASE_DIR" rev-parse HEAD)" != "$BASE_SHA" ]; then
        [ -z "$(git -C "$BASE_DIR" status --porcelain --untracked-files=no)" ] \
            || die "$BASE_DIR has local changes; remove it or set PERF_BASE_DIR"
        echo "perf-ab: moving $BASE_DIR to $BASE_REF"
        git -C "$BASE_DIR" checkout -q --detach "$BASE_SHA"
        rm -rf "$BASE_DIR/target" # classes of another ref would linger
    fi
else
    echo "perf-ab: creating the baseline worktree $BASE_DIR at $BASE_REF"
    git worktree add -q --detach "$BASE_DIR" "$BASE_SHA"
fi

# --- build both trees ------------------------------------------------------------------------------------
build() { # <dir> <label>
    local dir=$1 label=$2 mvn=mvn
    [ -x "$dir/mvnw" ] && mvn=./mvnw
    if [ "${PERF_SKIP_BUILD:-}" = 1 ] && [ -f "$dir/target/perf-cp.txt" ] && [ -d "$dir/target/test-classes" ]; then
        echo "perf-ab: reusing the $label build in $dir/target"
        return
    fi
    echo "perf-ab: building $label ($dir) ..."
    if ! (cd "$dir" && "${PIN[@]}" "$mvn" -B -q -T1 -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true \
            test-compile dependency:build-classpath -Dmdep.outputFile=target/perf-cp.txt -Dmdep.includeScope=test \
            >"$OUT/build-$label.log" 2>&1); then
        tail -40 "$OUT/build-$label.log" >&2
        die "the $label build failed; log: $OUT/build-$label.log"
    fi
}
build "$NEW_DIR" new
build "$BASE_DIR" base

# --- compile the probe against each build ----------------------------------------------------------------
PROBE_SRC=$NEW_DIR/src/perf/java/com/editora/ui/PerfProbe.java
classpath() { echo "$1/target/classes:$1/target/test-classes:$(cat "$1/target/perf-cp.txt")"; }
compile_probe() { # <dir> <label>
    local dir=$1 label=$2 dest=$1/target/perfprobe/classes cp
    cp=$(classpath "$dir")
    rm -rf "$dest" && mkdir -p "$dest"
    if javac -nowarn -proc:none -encoding UTF-8 -d "$dest" -cp "$cp" "$PROBE_SRC" 2>"$OUT/probe-javac-$label.log"; then
        echo "$PROBE_SRC" >"$OUT/probe-source-$label.txt"
        return
    fi
    local own=$dir/src/perf/java/com/editora/ui/PerfProbe.java
    if [ "$label" = base ] && [ -f "$own" ] && [ "$own" != "$PROBE_SRC" ] \
            && javac -nowarn -proc:none -encoding UTF-8 -d "$dest" -cp "$cp" "$own" 2>>"$OUT/probe-javac-$label.log"; then
        echo "perf-ab: WARNING: the current probe does not compile against $BASE_REF; using $BASE_REF's own probe." >&2
        echo "         Its numbers are only comparable where both probes measure the same way." >&2
        echo "$own" >"$OUT/probe-source-$label.txt"
        return
    fi
    cat "$OUT/probe-javac-$label.log" >&2
    die "the probe does not compile against the $label build. Reach the missing API by reflection in
       src/perf/java/com/editora/ui/PerfProbe.java (see searchMethod/openPath/createFixture there)."
}
compile_probe "$NEW_DIR" new
compile_probe "$BASE_DIR" base

# --- run -------------------------------------------------------------------------------------------------
run_one() { # <dir> <label> <rep> <mode> [arg]
    local dir=$1 label=$2 rep=$3 mode=$4 arg=${5:-}
    local log="$OUT/runs/${mode}${arg:+-$arg}-$label-r$rep.log" work="$OUT/work/$label-$mode${arg:+-$arg}-r$rep"
    mkdir -p "$work"
    printf 'perf-ab: rep %s %-6s %-13s load %s ... ' "$rep" "$label" "$mode${arg:+ $arg}" "$(cut -d' ' -f1 /proc/loadavg 2>/dev/null || echo ?)"
    local t0=$SECONDS status=0
    # shellcheck disable=SC2086 # PERF_JAVA_OPTS is a list of flags
    "${PIN[@]}" timeout "$TIMEOUT" java -XX:ActiveProcessorCount="$CPUS" -Xmx2g -Xms64m -XX:+UseG1GC \
        --enable-native-access=ALL-UNNAMED -Dglass.platform=Headless -Dprism.order=sw -Djava.awt.headless=true \
        -Dprobe.work="$work" -Dprobe.src="$NEW_DIR/src/main/java/com/editora" ${PERF_JAVA_OPTS:-} \
        -cp "$dir/target/perfprobe/classes:$(classpath "$dir")" com.editora.ui.PerfProbe "$mode" ${arg:+"$arg"} \
        >"$log" 2>"$work/stderr.log" || status=$?
    if [ $status -ne 0 ] || ! grep -q '^PROBE done' "$log"; then
        echo "FAILED (exit $status, $(( SECONDS - t0 )) s; see $log and $work/stderr.log)"
        FAILURES=$(( FAILURES + 1 ))
        return
    fi
    echo "$(( SECONDS - t0 )) s"
    awk -v side="$label" '$1 == "METRIC" { printf "%s\t%s\t%s\n", $2, side, $3 }' "$log" >>"$OUT/metrics.tsv"
}

FAILURES=0
: >"$OUT/metrics.tsv"
LOAD0=$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null || echo ?)
echo "perf-ab: new $NEW_SHA vs $BASE_REF $BASE_SHA; $REPS reps on $CPUS of $NCPU cores; output $OUT"
for rep in $(seq 1 "$REPS"); do
    for m in $MODES; do
        mode=${m%%:*}; arg=; [ "$m" != "$mode" ] && arg=${m#*:}
        if (( rep % 2 == 1 )); then
            run_one "$NEW_DIR" new "$rep" "$mode" "$arg"; run_one "$BASE_DIR" base "$rep" "$mode" "$arg"
        else
            run_one "$BASE_DIR" base "$rep" "$mode" "$arg"; run_one "$NEW_DIR" new "$rep" "$mode" "$arg"
        fi
    done
done
LOAD1=$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null || echo ?)
printf 'REPS=%q CPUS=%q NCPU=%q LOAD0=%q LOAD1=%q FAILURES=%q NEW_SHA=%q\n' \
    "$REPS" "$CPUS" "$NCPU" "$LOAD0" "$LOAD1" "$FAILURES" "$NEW_SHA" >"$OUT/run.env"
fi # PERF_RESUMMARIZE

# --- summary ---------------------------------------------------------------------------------------------
{
    echo "Performance A/B: \`$(git rev-parse --short "$NEW_SHA")\` (new) vs \`$BASE_REF\` (base, $(git rev-parse --short "$BASE_SHA"))."
    echo "$REPS reps, interleaved; medians; lower is better everywhere. $CPUS of $NCPU cores, $(java -version 2>&1 | head -1 | tr -d '"');"
    echo "load average $LOAD0 at the start, $LOAD1 at the end. **▲** = more than 10 % worse (and more than 0.5 ms / 20 µs / 1 MB) and more than the"
    echo "spread; **▲?** = more than 10 % worse but within the spread (likely noise: re-run that mode)."
    echo
    echo "| Metric | $BASE_REF | new | Change | Spread | |"
    echo "|---|---:|---:|---:|---:|---|"
    sort -t$'\t' -k1,1 -k2,2 -k3,3g "$OUT/metrics.tsv" | awk -F'\t' '
        function flush(   n, med) {
            if (cnt == 0) return
            med = (cnt % 2) ? v[(cnt + 1) / 2] : (v[cnt / 2] + v[cnt / 2 + 1]) / 2
            M[key, side] = med
            S[key, side] = med > 0 ? (v[cnt] - v[1]) / med : 0
            if (!(key in seen)) { seen[key] = 1; order[++nk] = key }
            cnt = 0
        }
        { if ($1 != key || $2 != side) { flush(); key = $1; side = $2 } v[++cnt] = $3 }
        function num(x) { return x >= 100 ? sprintf("%.0f", x) : x >= 10 ? sprintf("%.1f", x) : sprintf("%.2f", x) }
        END {
            flush()
            for (i = 1; i <= nk; i++) {
                k = order[i]
                hb = ((k, "base") in M); hn = ((k, "new") in M)
                b = M[k, "base"]; n = M[k, "new"]
                spread = S[k, "base"] > S[k, "new"] ? S[k, "base"] : S[k, "new"]
                change = ""; flag = ""
                if (hb && hn) {
                    # A change smaller than the unit floor (0.5 ms, 20 µs, 1 MB) is never flagged.
                    floor = k ~ /_ms/ ? 0.5 : k ~ /_us/ ? 20 : k ~ /_mb/ ? 1 : 0
                    big = (n - b > floor || b - n > floor)
                    if (b > 0) {
                        pct = (n - b) / b * 100
                        change = sprintf("%+.0f %%", pct)
                        if (big && pct > 10) {
                            if (pct > spread * 100) { flag = "▲"; worse++ } else { flag = "▲?"; noisy++ }
                        } else if (big && pct < -10) flag = "▼"
                    } else if (n > floor) { change = "new > 0"; flag = "▲"; worse++ }
                    else change = "="
                }
                printf "| %s | %s | %s | %s | %.0f %% | %s |\n", k, hb ? num(b) : "–", hn ? num(n) : "–", change, spread * 100, flag
            }
            printf "\n%d metric(s) more than 10 %% worse beyond the spread (▲); %d more within it (▲?).\n", worse, noisy
        }'
    echo
    echo "Not measured here: startup (needs a display) — run \`scripts/measure-startup.sh -n 7\` on both builds."
    if [ "$FAILURES" -gt 0 ]; then echo; echo "**$FAILURES probe run(s) failed**; their metrics are missing. Logs: $OUT/runs."; fi
    if ! cmp -s "$OUT/probe-source-new.txt" "$OUT/probe-source-base.txt"; then
        echo; echo "The baseline ran its own copy of the probe: $(cat "$OUT/probe-source-base.txt")."
    fi
} >"$OUT/summary.md"
echo
cat "$OUT/summary.md"
echo
echo "perf-ab: summary in $OUT/summary.md; raw numbers in $OUT/metrics.tsv; run logs and folded stacks in $OUT/runs, $OUT/work"
[ "$FAILURES" -eq 0 ]
