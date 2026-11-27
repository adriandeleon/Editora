package com.editora.review;

import com.editora.config.ReviewThread;
import com.editora.config.TextAnchor;
import com.editora.editor.notes.NoteAnchors;
import org.jspecify.annotations.Nullable;

/**
 * Where a review thread's lines are in a file's current text, and what its suggestion does to them. A thread is
 * anchored to whole lines, as a review comment on a diff is: the anchor holds those lines' text and a little context
 * on each side, and is found again the way a Personal Note is ({@link NoteAnchors#relocate}). When the text can no
 * longer be found the thread is <em>outdated</em>: it keeps its last known lines, and nothing is applied to them.
 * Pure; lines are 0-based, as {@link TextAnchor}'s are.
 */
public final class ReviewAnchors {

    private ReviewAnchors() {}

    /** Where a thread's lines are now. {@code moved} when they are no longer where the anchor says. */
    public record Located(TextAnchor anchor, boolean outdated, boolean moved) {

        public int startLine() {
            return anchor.line();
        }

        public int endLine() {
            return anchor.endLine();
        }
    }

    /**
     * An anchor on lines {@code startLine}..{@code endLine} (inclusive, clamped to the text) of {@code text}: their
     * whole text, with up to {@link TextAnchor#MAX_CONTEXT} characters of context before and after.
     */
    public static TextAnchor capture(String text, int startLine, int endLine) {
        int[] starts = lineStarts(text);
        int last = starts.length - 1;
        int first = clamp(Math.min(startLine, endLine), 0, last);
        int end = clamp(Math.max(startLine, endLine), first, last);
        int from = starts[first];
        int to = lineEnd(text, starts, end);
        String lines = text.substring(from, to);
        String prefix = text.substring(Math.max(0, from - TextAnchor.MAX_CONTEXT), from);
        String suffix = text.substring(to, Math.min(text.length(), to + TextAnchor.MAX_CONTEXT));
        return new TextAnchor(first, 0, end, to - starts[end], lines, prefix, suffix, lines.length());
    }

    /** Where {@code thread}'s lines are in {@code text}, or its last known lines, outdated. */
    public static Located locate(ReviewThread thread, String text) {
        TextAnchor a = thread.anchor();
        int[] starts = lineStarts(text);
        int savedStart = a.line() < starts.length ? starts[a.line()] + Math.max(0, a.column()) : -1;
        int[] found = NoteAnchors.relocate(
                text, savedStart, savedStart + a.length(), a.selectedText(), a.prefix(), a.suffix(), a.length());
        if (found == null) {
            int last = starts.length - 1;
            int s = clamp(a.line(), 0, last);
            return new Located(a.at(s, 0, clamp(a.endLine(), s, last), a.endColumn()), true, false);
        }
        int startLine = lineOf(starts, found[0]);
        // The span ends after the last character of the anchored lines: the line holding the character before it.
        int endLine = found[1] > found[0] ? lineOf(starts, found[1] - 1) : startLine;
        TextAnchor at = a.at(startLine, found[0] - starts[startLine], endLine, found[1] - starts[endLine]);
        boolean moved = startLine != a.line() || endLine != a.endLine();
        return new Located(at, false, moved);
    }

    /**
     * {@code text} with the thread's lines replaced by {@code suggestion}, as a GitHub suggestion replaces the
     * lines it is on, or {@code null} when the thread is outdated (its lines are not there to replace). The line
     * ending after the last replaced line is kept.
     */
    public static @Nullable String applySuggestion(ReviewThread thread, String text, String suggestion) {
        Located at = locate(thread, text);
        if (at.outdated()) {
            return null;
        }
        int[] starts = lineStarts(text);
        int from = starts[at.startLine()];
        int to = lineEnd(text, starts, at.endLine());
        return text.substring(0, from) + suggestion + text.substring(to);
    }

    /** The offset of each line's first character; a text always has line 0. */
    static int[] lineStarts(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        int[] starts = new int[count];
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts[n++] = i + 1;
            }
        }
        return starts;
    }

    /** The offset just past line {@code line}'s last character, before its line ending ({@code \n} or {@code \r\n}). */
    private static int lineEnd(String text, int[] starts, int line) {
        boolean more = line + 1 < starts.length;
        int end = more ? starts[line + 1] - 1 : text.length();
        if (more && end > starts[line] && text.charAt(end - 1) == '\r') {
            end--;
        }
        return end;
    }

    private static int lineOf(int[] starts, int offset) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
