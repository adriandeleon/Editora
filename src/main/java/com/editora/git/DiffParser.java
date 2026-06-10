package com.editora.git;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parser for unified-diff hunk headers (from {@code git diff -U0 HEAD -- <file>}), translating
 * each {@code @@ -a,b +c,d @@} into the new-file line ranges that the gutter colors.
 *
 * <p>Mapping (new-file line numbers are 1-based in the header, 0-based in the result):
 * <ul>
 *   <li>{@code b == 0} (pure insertion) → {@link ChangeType#ADDED} for the {@code d} new lines.</li>
 *   <li>{@code d == 0} (pure deletion) → a single {@link ChangeType#DELETED} marker at the new-file
 *       line just below where content was removed.</li>
 *   <li>otherwise (replacement) → {@link ChangeType#MODIFIED} for the {@code d} new lines.</li>
 * </ul>
 * This is a deliberate v1 approximation (a replacement that grows/shrinks the line count is shown
 * entirely as modified rather than split into modified + added/deleted). No process work happens
 * here — it is fed the raw {@code git} output, so it is unit-testable without a toolkit or a repo.
 */
public final class DiffParser {

    /** One contiguous change in the new file: {@code count} lines starting at 0-based {@code startLine}. */
    public record LineChange(int startLine, int count, ChangeType type) { }

    // @@ -a[,b] +c[,d] @@  — the b/d counts default to 1 when omitted (git's convention).
    private static final Pattern HUNK =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

    private DiffParser() {
    }

    /** Parses every hunk header in {@code diff} into new-file change ranges, in document order. */
    public static List<LineChange> parse(String diff) {
        List<LineChange> out = new ArrayList<>();
        if (diff == null || diff.isBlank()) {
            return out;
        }
        for (String line : diff.split("\n", -1)) {
            Matcher m = HUNK.matcher(line);
            if (!m.find()) {
                continue;
            }
            int oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            int newStart = Integer.parseInt(m.group(3));
            int newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
            if (newCount == 0) {
                // Pure deletion: git reports the line *before* the gap, so the marker sits on the
                // following line (clamped to 0 for a deletion at the very top of the file).
                out.add(new LineChange(Math.max(0, newStart), 1, ChangeType.DELETED));
            } else if (oldCount == 0) {
                out.add(new LineChange(newStart - 1, newCount, ChangeType.ADDED));
            } else {
                out.add(new LineChange(newStart - 1, newCount, ChangeType.MODIFIED));
            }
        }
        return out;
    }

    /** Expands the change ranges into a per-line map (0-based line → {@link ChangeType}). */
    public static Map<Integer, ChangeType> toLineMap(List<LineChange> changes) {
        Map<Integer, ChangeType> map = new LinkedHashMap<>();
        for (LineChange c : changes) {
            for (int i = 0; i < c.count(); i++) {
                map.put(c.startLine() + i, c.type());
            }
        }
        return map;
    }

    /** Convenience: parse raw diff text straight into a per-line change map. */
    public static Map<Integer, ChangeType> parseToLineMap(String diff) {
        return toLineMap(parse(diff));
    }

    /**
     * Maps each gutter-marked line (same 0-based keys as {@link #parseToLineMap}) to its hunk's unified-diff
     * body — the {@code -old}/{@code +new} lines from {@code git diff -U0} — for a hover tooltip on the
     * change bar. Pure; unit-tested.
     */
    public static Map<Integer, String> parseToHunkText(String diff) {
        Map<Integer, String> map = new LinkedHashMap<>();
        if (diff == null || diff.isBlank()) {
            return map;
        }
        String[] lines = diff.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = HUNK.matcher(lines[i]);
            if (!m.find()) {
                continue;
            }
            int newStart = Integer.parseInt(m.group(3));
            int newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
            // The hunk body is the contiguous run of -/+ lines after the header (no context with -U0).
            // File headers (---/+++) only precede the first hunk, so they never enter the body here.
            StringBuilder body = new StringBuilder();
            for (int j = i + 1; j < lines.length; j++) {
                String l = lines[j];
                if ((l.startsWith("-") && !l.startsWith("---")) || (l.startsWith("+") && !l.startsWith("+++"))) {
                    body.append(l).append('\n');
                } else if (l.startsWith("\\")) {
                    // "\ No newline at end of file" — skip, not part of the shown diff.
                    continue;
                } else {
                    break;
                }
            }
            String text = body.toString().strip();
            if (text.isEmpty()) {
                continue;
            }
            int start = newCount == 0 ? Math.max(0, newStart) : newStart - 1;
            int count = newCount == 0 ? 1 : newCount;
            for (int k = 0; k < count; k++) {
                map.put(start + k, text);
            }
        }
        return map;
    }
}
