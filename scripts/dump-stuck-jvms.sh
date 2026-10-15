#!/usr/bin/env bash
# Thread dumps of every JVM still running after a while, for a test job that hangs.
#
# A test blocked where an interrupt cannot reach it (a socket close, a native call) is never ended by JUnit's
# timeout, so the job runs into its own limit and the log ends without saying where it stuck. Started in the
# background at the beginning of a test step, this waits, then writes `jcmd Thread.print` for each JVM to a
# file; a later `if: always()` step prints it. It writes to a file, not the step's output: a background
# process holding the step's output pipe keeps the step open until it exits.
#
#   scripts/dump-stuck-jvms.sh <seconds> <file> &
set -u
seconds=$1
out=$2
sleep "$seconds"
{
    echo "JVMs still running after ${seconds}s:"
    for pid in $(jps -q 2>/dev/null); do
        echo "===== $(jps -l 2>/dev/null | awk -v p="$pid" '$1 == p') ====="
        jcmd "$pid" Thread.print 2>&1 || true
    done
} > "$out" 2>&1
