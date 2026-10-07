package com.editora.editor;

import java.util.List;

/**
 * Computes the one text replacement that puts a hunk's {@code HEAD} lines back into a buffer.
 *
 * <p>The buffer holds its text with bare {@code \n} between lines, whatever the file's line ending is, and a
 * final newline shows as a trailing empty line. A hunk is in git's terms: whole lines, each normally followed
 * by a terminator, except a last line git flags as having none. This is where the two meet — which is the
 * part that is easy to get wrong at the end of the document (a hunk that reaches the last line, a file with
 * or without a final newline on either side) and for a hunk with no lines on one side.
 *
 * <p>Pure; unit-tested.
 */
public final class HunkRevert {

    private HunkRevert() {}

    /** Replace {@code [start, end)} of the buffer text with {@code text}. */
    public record Edit(int start, int end, String text) {}

    /**
     * @param text the buffer's text ({@code \n} between lines)
     * @param startLine 0-based buffer line of the first changed line; for a pure deletion the line the
     *     removed lines go back in front of
     * @param lineCount buffer lines the hunk covers (0 for a pure deletion)
     * @param oldLines the {@code HEAD} lines; a trailing {@code \r} (a CRLF file as git prints it) is dropped,
     *     since the buffer keeps line endings out of its text
     * @param oldUnterminated the last old line was the last line of the {@code HEAD} file, with no newline
     */
    public static Edit plan(
            CharSequence text, int startLine, int lineCount, List<String> oldLines, boolean oldUnterminated) {
        int length = text.length();
        int lines = 1;
        for (int i = 0; i < length; i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        boolean endsWithNewline = length > 0 && text.charAt(length - 1) == '\n';
        int first = Math.max(0, startLine);
        int after = first + Math.max(0, lineCount);
        // The hunk runs to the end of the document when nothing follows it — or, for an old side without a
        // final newline, when only the empty line that *is* the buffer's final newline does.
        boolean toEnd = after >= lines || (oldUnterminated && after == lines - 1 && endsWithNewline);
        int start = first >= lines ? length : lineOffset(text, first);
        int end = toEnd ? length : lineOffset(text, after);

        StringBuilder replacement = new StringBuilder();
        if (first >= lines && length > 0 && !oldLines.isEmpty()) {
            replacement.append('\n'); // past the last line: the restored lines start a line of their own
        }
        for (int i = 0; i < oldLines.size(); i++) {
            String line = oldLines.get(i);
            replacement.append(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            if (i < oldLines.size() - 1 || !oldUnterminated) {
                replacement.append('\n');
            }
        }
        if (first >= lines && replacement.length() > 1 && !oldUnterminated) {
            replacement.setLength(replacement.length() - 1); // the leading newline already terminates them
        }
        return new Edit(start, end, replacement.toString());
    }

    /** Offset of the first character of 0-based {@code line}; the text length when there is no such line. */
    private static int lineOffset(CharSequence text, int line) {
        int offset = 0;
        for (int seen = 0; seen < line; seen++) {
            int next = indexOf(text, '\n', offset);
            if (next < 0) {
                return text.length();
            }
            offset = next + 1;
        }
        return offset;
    }

    private static int indexOf(CharSequence text, char c, int from) {
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }
}
