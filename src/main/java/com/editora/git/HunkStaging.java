package com.editora.git;

import java.util.ArrayList;
import java.util.List;

import com.editora.git.DiffParser.Hunk;
import org.jspecify.annotations.Nullable;

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
     * the file or the index moved on since the diff was taken: its lines fall outside a text, or the lines it
     * removes are not the index's lines there, or the lines it adds are not the working file's. A count that
     * still fits would otherwise splice whatever lines are there now into the index.
     */
    public static @Nullable String apply(String indexText, String workingText, List<Hunk> hunks) {
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
            // Hunks are applied last to first, so the index lines before this one are still the original ones.
            if (!sameLines(result.subList(at, at + h.oldCount()), h.removed())
                    || !sameLines(working.lines.subList(from, from + h.newCount()), h.added())) {
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

    /**
     * Whether {@code text} (the editor's decoded lines) are the lines {@code fromDiff} names. The texts are in
     * the editor's form — {@code \n} only, decoded in the file's own charset, without a byte-order mark — while
     * the diff's lines are git's bytes read as UTF-8, so a CRLF file's lines end in {@code \r} there and a
     * line of a file in another charset carries U+FFFD. Those differences are not the file having moved on: a
     * trailing {@code \r} and a leading BOM are ignored, and a line the diff could not decode is taken as it is.
     */
    static boolean sameLines(List<String> text, List<String> fromDiff) {
        if (text.size() != fromDiff.size()) {
            return false;
        }
        for (int i = 0; i < text.size(); i++) {
            String diffLine = normalized(fromDiff.get(i));
            if (diffLine.indexOf('\uFFFD') < 0 && !normalized(text.get(i)).equals(diffLine)) {
                return false;
            }
        }
        return true;
    }

    private static String normalized(String line) {
        String out = line.startsWith("\uFEFF") ? line.substring(1) : line;
        return out.endsWith("\r") ? out.substring(0, out.length() - 1) : out;
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
