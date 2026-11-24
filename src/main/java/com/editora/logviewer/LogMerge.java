package com.editora.logviewer;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Several logs as one, in the order they were written: what Log: Merge Logs… shows. Pure (java.base only).
 *
 * <p>The unit is the record, as the filter's: a line with a time ({@link LogTimes}) and the lines after it that
 * have none (a stack trace, a wrapped message) move together. Each log keeps its own order — logs are only
 * nearly sorted, and reordering lines inside one would garble it — so this is a merge of sorted runs: the next
 * record is the earliest of each log's next one, the earlier log first on a tie. Lines above a log's first time
 * have no time and come first, log by log.
 */
public final class LogMerge {

    private LogMerge() {}

    /** One log to merge: the name its lines are tagged with, and its text. */
    public record Input(String name, String text) {}

    /**
     * The merged text, every line ending in {@code '\n'}, and for each of its lines the index of the input it
     * came from.
     *
     * @param untimed lines that had no time to place them by (each log's lines above its first time)
     */
    public record Merged(String text, int[] sources, List<String> names, int untimed) {
        public int lines() {
            return sources.length;
        }
    }

    /** One log split into lines and records. */
    private static final class Run {
        final int index;
        final List<String> lines = new ArrayList<>();
        /** Start line and time of each record; a record runs to the next one's start. */
        final List<long[]> records = new ArrayList<>();

        int next;

        Run(int index, String text, LogTimes.Context context) {
            this.index = index;
            int start = 0;
            while (start < text.length()) {
                int end = text.indexOf('\n', start);
                int stop = end < 0 ? text.length() : end;
                String line = text.substring(start, stop > start && text.charAt(stop - 1) == '\r' ? stop - 1 : stop);
                long time = LogTimes.of(line, context);
                if (time != LogTimes.NONE || records.isEmpty()) {
                    records.add(new long[] {lines.size(), time == LogTimes.NONE ? Long.MIN_VALUE : time});
                }
                lines.add(line);
                start = stop + 1;
            }
        }

        long time() {
            return records.get(next)[1];
        }

        int from() {
            return (int) records.get(next)[0];
        }

        int to() {
            return next + 1 < records.size() ? (int) records.get(next + 1)[0] : lines.size();
        }
    }

    /** Merges {@code inputs} by time (see the class comment). */
    public static Merged merge(List<Input> inputs, LogTimes.Context context) {
        List<Run> runs = new ArrayList<>();
        int total = 0;
        int untimed = 0;
        for (int i = 0; i < inputs.size(); i++) {
            Run run = new Run(i, inputs.get(i).text(), context);
            runs.add(run);
            total += run.lines.size();
            if (!run.records.isEmpty() && run.records.get(0)[1] == Long.MIN_VALUE) {
                untimed += run.records.size() > 1 ? (int) run.records.get(1)[0] : run.lines.size();
            }
        }
        PriorityQueue<Run> heads = new PriorityQueue<>(
                (a, b) -> a.time() != b.time() ? Long.compare(a.time(), b.time()) : Integer.compare(a.index, b.index));
        for (Run run : runs) {
            if (!run.records.isEmpty()) {
                heads.add(run);
            }
        }
        StringBuilder text = new StringBuilder();
        int[] sources = new int[total];
        int at = 0;
        while (!heads.isEmpty()) {
            Run run = heads.poll();
            for (int line = run.from(); line < run.to(); line++) {
                text.append(run.lines.get(line)).append('\n');
                sources[at++] = run.index;
            }
            run.next++;
            if (run.next < run.records.size()) {
                heads.add(run);
            }
        }
        List<String> names = inputs.stream().map(Input::name).toList();
        return new Merged(text.toString(), sources, names, untimed);
    }
}
