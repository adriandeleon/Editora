package com.editora.git;

import java.util.ArrayList;
import java.util.List;

import com.editora.git.DiffParser.Hunk;

/**
 * Staging one change from the editor: which of a file's <em>unstaged</em> hunks make up the change the user
 * pointed at, and what the index text is once exactly those are applied.
 *
 * <p>The gutter shows the file against {@code HEAD}, but only what differs from the <em>index</em> can be
 * staged. Both diffs number the same working file, so a change's unstaged part is the unstaged hunks that
 * touch its lines; a change with no such hunk is already staged in full.
 *
 * <p>Pure; unit-tested. Texts use {@code \n} between lines.
 */
public final class HunkStaging {

    private HunkStaging() {}

    /**
     * The hunks of {@code unstaged} that touch the working-file lines {@code [line, line + count)} (0-based).
     * {@code count == 0} names the gap just above {@code line} — where a pure deletion sits.
     */
    public static List<Hunk> touching(List<Hunk> unstaged, int line, int count) {
        List<Hunk> out = new ArrayList<>();
        int end = line + count;
        for (Hunk h : unstaged) {
            int from = h.newCount() == 0 ? h.newStart() : h.newStart() - 1;
            int to = from + h.newCount();
            boolean touches = count > 0 && h.newCount() > 0 ? from < end && line < to : from <= end && line <= to;
            if (touches) {
                out.add(h);
            }
        }
        return out;
    }

    /**
     * {@code indexText} with {@code hunks} (a subset of the index → working diff, in file order) applied,
     * taking the new lines from {@code workingText}. Returns {@code null} when a hunk does not fit the texts —
     * the file or the index moved on since the diff was taken.
     */
    public static String apply(String indexText, String workingText, List<Hunk> hunks) {
        Lines index = Lines.of(indexText);
        Lines working = Lines.of(workingText);
        List<String> result = new ArrayList<>(index.lines);
        boolean terminated = index.terminated;
        for (int i = hunks.size() - 1; i >= 0; i--) {
            Hunk h = hunks.get(i);
            int at = h.oldCount() == 0 ? h.oldStart() : h.oldStart() - 1;
            int from = h.newCount() == 0 ? h.newStart() : h.newStart() - 1;
            if (at < 0 || at + h.oldCount() > result.size() || from < 0 || from + h.newCount() > working.lines.size()) {
                return null;
            }
            boolean reachesIndexEnd = at + h.oldCount() == index.lines.size();
            result.subList(at, at + h.oldCount()).clear();
            result.addAll(at, working.lines.subList(from, from + h.newCount()));
            if (h.newCount() > 0 && from + h.newCount() == working.lines.size()) {
                terminated = working.terminated; // the file's last line came along, with or without its newline
            } else if (h.newCount() == 0 && reachesIndexEnd) {
                terminated = true; // the index's last line went; the one before it always had a newline
            }
        }
        if (result.isEmpty()) {
            return "";
        }
        return String.join("\n", result) + (terminated ? "\n" : "");
    }

    /** A text as git's lines: the lines, and whether the last one is terminated. */
    private record Lines(List<String> lines, boolean terminated) {
        static Lines of(String text) {
            if (text == null || text.isEmpty()) {
                return new Lines(List.of(), false);
            }
            boolean terminated = text.endsWith("\n");
            String body = terminated ? text.substring(0, text.length() - 1) : text;
            return new Lines(List.of(body.split("\n", -1)), terminated);
        }
    }
}
