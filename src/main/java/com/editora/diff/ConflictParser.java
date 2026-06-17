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
     * (empty unless the markers were written in {@code diff3}/{@code zdiff3} style), and the labels from the
     * markers.
     */
    public record Conflict(
            String oursLabel,
            List<String> ours,
            String baseLabel,
            List<String> base,
            String theirsLabel,
            List<String> theirs) {

        /** Whether this conflict carries a captured 3-way common-ancestor region. */
        public boolean hasBase() {
            return !base.isEmpty();
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

    private static final String OURS = "<<<<<<<";
    private static final String BASE = "|||||||";
    private static final String SEP = "=======";
    private static final String THEIRS = ">>>>>>>";

    /** Fast check (used to offer the merge view) — any line beginning with the ours marker. */
    public static boolean hasConflictMarkers(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            if (line.startsWith(OURS)) {
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
            if (line.startsWith(OURS)) {
                if (!plain.isEmpty()) {
                    segments.add(new PlainSegment(List.copyOf(plain)));
                    plain.clear();
                }
                String oursLabel = label(line, OURS);
                List<String> ours = new ArrayList<>();
                List<String> base = new ArrayList<>();
                List<String> theirs = new ArrayList<>();
                String baseLabel = "";
                String theirsLabel = "";
                i++;
                // ours lines until the base (|||||||) or separator (=======)
                while (i < n
                        && !lines.get(i).startsWith(SEP)
                        && !lines.get(i).startsWith(BASE)
                        && !lines.get(i).startsWith(THEIRS)) {
                    ours.add(lines.get(i));
                    i++;
                }
                // optional base region (3-way diff3/zdiff3 style) — capture it
                if (i < n && lines.get(i).startsWith(BASE)) {
                    baseLabel = label(lines.get(i), BASE);
                    i++;
                    while (i < n
                            && !lines.get(i).startsWith(SEP)
                            && !lines.get(i).startsWith(THEIRS)) {
                        base.add(lines.get(i));
                        i++;
                    }
                }
                if (i < n && lines.get(i).startsWith(SEP)) {
                    i++;
                }
                while (i < n && !lines.get(i).startsWith(THEIRS)) {
                    theirs.add(lines.get(i));
                    i++;
                }
                if (i < n && lines.get(i).startsWith(THEIRS)) {
                    theirsLabel = label(lines.get(i), THEIRS);
                    i++;
                }
                segments.add(new ConflictSegment(new Conflict(
                        oursLabel, List.copyOf(ours), baseLabel, List.copyOf(base), theirsLabel, List.copyOf(theirs))));
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
    private static String label(String markerLine, String marker) {
        String rest = markerLine.substring(marker.length()).strip();
        return rest;
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
                        out.add(OURS + (c.oursLabel().isEmpty() ? "" : " " + c.oursLabel()));
                        out.addAll(c.ours());
                        if (c.hasBase()) {
                            out.add(BASE + (c.baseLabel().isEmpty() ? "" : " " + c.baseLabel()));
                            out.addAll(c.base());
                        }
                        out.add(SEP);
                        out.addAll(c.theirs());
                        out.add(THEIRS + (c.theirsLabel().isEmpty() ? "" : " " + c.theirsLabel()));
                    }
                }
            }
        }
        return out;
    }
}
