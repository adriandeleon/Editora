package com.editora.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Git merge-conflict markers ({@code <<<<<<<} / {@code |||||||} / {@code =======} / {@code >>>>>>>})
 * into an ordered list of segments — plain regions and conflict regions with the "ours", "theirs" and
 * (when the markers were written in {@code diff3}/{@code zdiff3} style) the common-ancestor "base" sides —
 * and {@link #resolve}s a file given a per-conflict {@link Choice}. Pure and unit-tested. The 3-way
 * {@code |||||||} base region is captured when present; with Git's default {@code merge} conflict style it
 * is absent and {@link Conflict#base()} is empty (a 2-way ours/theirs view).
 */
public final class ConflictParser {

    private ConflictParser() {}

    /** What to keep for one conflict. */
    public enum Choice {
        UNRESOLVED,
        OURS,
        THEIRS,
        BASE,
        BOTH
    }

    /**
     * One conflict: the {@code ours}/{@code theirs} lines, the optional common-ancestor {@code base} lines
     * (empty unless the markers were written in {@code diff3}/{@code zdiff3} style), the labels from the
     * markers, and the marker length the file used (seven unless a {@code conflict-marker-size} attribute
     * raised it) so an unresolved conflict is written back with the markers it came with.
     */
    public record Conflict(
            String oursLabel,
            List<String> ours,
            String baseLabel,
            List<String> base,
            String theirsLabel,
            List<String> theirs,
            boolean basePresent,
            int markerSize) {

        public Conflict(
                String oursLabel,
                List<String> ours,
                String baseLabel,
                List<String> base,
                String theirsLabel,
                List<String> theirs,
                boolean basePresent) {
            this(oursLabel, ours, baseLabel, base, theirsLabel, theirs, basePresent, DEFAULT_MARKER_SIZE);
        }

        public Conflict(
                String oursLabel,
                List<String> ours,
                String baseLabel,
                List<String> base,
                String theirsLabel,
                List<String> theirs) {
            this(oursLabel, ours, baseLabel, base, theirsLabel, theirs, base != null && !base.isEmpty());
        }

        public Conflict {
            oursLabel = oursLabel == null ? "" : oursLabel;
            ours = List.copyOf(ours == null ? List.of() : ours);
            baseLabel = baseLabel == null ? "" : baseLabel;
            base = List.copyOf(base == null ? List.of() : base);
            theirsLabel = theirsLabel == null ? "" : theirsLabel;
            theirs = List.copyOf(theirs == null ? List.of() : theirs);
            markerSize = Math.max(DEFAULT_MARKER_SIZE, markerSize);
        }

        /** Whether this conflict carries a captured 3-way common-ancestor region. */
        public boolean hasBase() {
            return basePresent;
        }
    }

    public sealed interface Segment permits PlainSegment, ConflictSegment {}

    /** A run of non-conflicting lines. */
    public record PlainSegment(List<String> lines) implements Segment {}

    /** A conflict region. */
    public record ConflictSegment(Conflict conflict) implements Segment {}

    /** A parsed file: its segments in order, with a count of conflict regions. */
    public record ConflictFile(List<Segment> segments) {
        public int conflictCount() {
            return (int)
                    segments.stream().filter(s -> s instanceof ConflictSegment).count();
        }

        public boolean hasConflicts() {
            return conflictCount() > 0;
        }

        /** Whether any conflict carries a captured 3-way common-ancestor (base) region. */
        public boolean hasBase() {
            return segments.stream()
                    .anyMatch(s ->
                            s instanceof ConflictSegment cs && cs.conflict().hasBase());
        }
    }

    /** Git's default conflict-marker size; a {@code conflict-marker-size} attribute can only raise it here. */
    public static final int DEFAULT_MARKER_SIZE = 7;

    private static final char OURS = '<';
    private static final char BASE = '|';
    private static final char SEP = '=';
    private static final char THEIRS = '>';

    /**
     * The length of the marker run that starts {@code line} — at least seven {@code c} characters followed
     * by the end of the line or a space and a label — or {@code 0} when the line is not such a marker.
     */
    private static int markerRun(String line, char c) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == c) {
            n++;
        }
        return n >= DEFAULT_MARKER_SIZE && (n == line.length() || line.charAt(n) == ' ') ? n : 0;
    }

    /**
     * Whether {@code line} is a {@code c} marker of exactly {@code size} characters. The size comes from the
     * conflict's opening marker (seven, or the file's {@code conflict-marker-size} attribute), so a run of a
     * different length is ordinary text — a Markdown or reStructuredText heading underline
     * ({@code ==========}) inside a seven-marker conflict used to end the "ours" side early and corrupt a
     * marker-fallback merge.
     */
    private static boolean isMarker(String line, char c, int size) {
        return markerRun(line, c) == size;
    }

    /** The ours/theirs separator carries no label: it is the marker run and nothing else. */
    private static boolean isSeparator(String line, int size) {
        return line.length() == size && markerRun(line, SEP) == size;
    }

    /**
     * The marker size of the conflict that opens at {@code lines[i]}, or {@code 0} when that line does not
     * open one. Seven {@code <} always open a conflict (Git's default). A longer run does so only when the
     * separator and closing marker of that same size follow, so a decorative line of {@code <} characters
     * cannot swallow the rest of the file.
     */
    private static int opens(List<String> lines, int i) {
        int size = markerRun(lines.get(i), OURS);
        if (size <= DEFAULT_MARKER_SIZE) {
            return size;
        }
        boolean separated = false;
        for (int k = i + 1; k < lines.size(); k++) {
            String line = lines.get(k);
            if (!separated) {
                separated = isSeparator(line, size);
            } else if (isMarker(line, THEIRS, size)) {
                return size;
            }
        }
        return 0;
    }

    /** Fast check (used to offer the merge view) — any line that opens a conflict. */
    public static boolean hasConflictMarkers(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        List<String> lines = List.of(text.replace("\r\n", "\n").split("\n", -1));
        for (int i = 0; i < lines.size(); i++) {
            if (opens(lines, i) > 0) {
                return true;
            }
        }
        return false;
    }

    public static ConflictFile parse(List<String> lines) {
        List<Segment> segments = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        int i = 0;
        int n = lines.size();
        while (i < n) {
            String line = lines.get(i);
            int size = opens(lines, i);
            if (size > 0) {
                if (!plain.isEmpty()) {
                    segments.add(new PlainSegment(List.copyOf(plain)));
                    plain.clear();
                }
                String oursLabel = label(line, size);
                List<String> ours = new ArrayList<>();
                List<String> base = new ArrayList<>();
                List<String> theirs = new ArrayList<>();
                String baseLabel = "";
                String theirsLabel = "";
                boolean basePresent = false;
                i++;
                // ours lines until the base (|||||||) or separator (=======)
                while (i < n
                        && !isSeparator(lines.get(i), size)
                        && !isMarker(lines.get(i), BASE, size)
                        && !isMarker(lines.get(i), THEIRS, size)) {
                    ours.add(lines.get(i));
                    i++;
                }
                // optional base region (3-way diff3/zdiff3 style) — capture it
                if (i < n && isMarker(lines.get(i), BASE, size)) {
                    basePresent = true;
                    baseLabel = label(lines.get(i), size);
                    i++;
                    while (i < n && !isSeparator(lines.get(i), size) && !isMarker(lines.get(i), THEIRS, size)) {
                        base.add(lines.get(i));
                        i++;
                    }
                }
                if (i < n && isSeparator(lines.get(i), size)) {
                    i++;
                }
                while (i < n && !isMarker(lines.get(i), THEIRS, size)) {
                    theirs.add(lines.get(i));
                    i++;
                }
                if (i < n && isMarker(lines.get(i), THEIRS, size)) {
                    theirsLabel = label(lines.get(i), size);
                    i++;
                }
                segments.add(new ConflictSegment(new Conflict(
                        oursLabel,
                        List.copyOf(ours),
                        baseLabel,
                        List.copyOf(base),
                        theirsLabel,
                        List.copyOf(theirs),
                        basePresent,
                        size)));
            } else {
                plain.add(line);
                i++;
            }
        }
        if (!plain.isEmpty()) {
            segments.add(new PlainSegment(List.copyOf(plain)));
        }
        return new ConflictFile(segments);
    }

    /** The text after a marker (e.g. {@code "<<<<<<< HEAD"} → {@code "HEAD"}). */
    private static String label(String markerLine, int markerSize) {
        return markerLine.substring(markerSize).strip();
    }

    /**
     * Produces the resolved lines. {@code choices} holds one {@link Choice} per conflict region in order;
     * a missing or {@link Choice#UNRESOLVED} choice leaves that conflict's markers intact (so a partial
     * resolution is still valid conflict-marked text).
     */
    public static List<String> resolve(ConflictFile file, List<Choice> choices) {
        List<String> out = new ArrayList<>();
        int ci = 0;
        for (Segment seg : file.segments()) {
            if (seg instanceof PlainSegment p) {
                out.addAll(p.lines());
            } else if (seg instanceof ConflictSegment cs) {
                Choice choice = ci < choices.size() ? choices.get(ci) : Choice.UNRESOLVED;
                ci++;
                Conflict c = cs.conflict();
                switch (choice == null ? Choice.UNRESOLVED : choice) {
                    case OURS -> out.addAll(c.ours());
                    case THEIRS -> out.addAll(c.theirs());
                    case BASE -> out.addAll(c.base());
                    case BOTH -> {
                        out.addAll(c.ours());
                        out.addAll(c.theirs());
                    }
                    default -> { // UNRESOLVED: keep the conflict markers verbatim (incl. the 3-way base, if any)
                        int size = c.markerSize();
                        out.add(marker(OURS, size, c.oursLabel()));
                        out.addAll(c.ours());
                        if (c.hasBase()) {
                            out.add(marker(BASE, size, c.baseLabel()));
                            out.addAll(c.base());
                        }
                        out.add(marker(SEP, size, ""));
                        out.addAll(c.theirs());
                        out.add(marker(THEIRS, size, c.theirsLabel()));
                    }
                }
            }
        }
        return out;
    }

    private static String marker(char c, int size, String label) {
        String run = String.valueOf(c).repeat(size);
        return label.isEmpty() ? run : run + " " + label;
    }
}
