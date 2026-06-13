package com.editora.editor;

/**
 * Pure (no-toolkit, unit-tested) Markdown heading level changes over the line(s) a selection covers:
 * promote/demote by a delta, or set an absolute level (0 = Normal/paragraph, 1–6 = {@code #}…{@code ######}).
 * Leading indentation is preserved. {@code EditorBuffer} applies the returned {@link MarkdownEdit}.
 */
public final class MarkdownHeading {

    private static final int MAX_LEVEL = 6;

    private MarkdownHeading() {}

    /** Promote ({@code delta < 0}, fewer #) or demote ({@code delta > 0}, more #) each line in range. */
    public static MarkdownEdit apply(String text, int selStart, int selEnd, int delta) {
        return rebuild(text, selStart, selEnd, level -> clamp(level + delta));
    }

    /** Set each line in range to an absolute heading {@code level} (0 = Normal). */
    public static MarkdownEdit setLevel(String text, int selStart, int selEnd, int level) {
        int target = clamp(level);
        return rebuild(text, selStart, selEnd, ignored -> target);
    }

    private interface LevelFn {
        int next(int current);
    }

    private static MarkdownEdit rebuild(String text, int selStart, int selEnd, LevelFn fn) {
        int blockStart = lineStartAt(text, selStart);
        int blockEnd = lineEndAt(text, Math.max(selStart, selEnd));
        String[] lines = text.substring(blockStart, blockEnd).split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(rewriteLine(lines[i], fn));
        }
        String replacement = out.toString();
        return new MarkdownEdit(blockStart, blockEnd, replacement, blockStart, blockStart + replacement.length());
    }

    private static String rewriteLine(String line, LevelFn fn) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        String indent = line.substring(0, i);
        int hashes = 0;
        while (i < line.length() && line.charAt(i) == '#' && hashes < MAX_LEVEL) {
            hashes++;
            i++;
        }
        // Treat leading #s as a heading prefix only when followed by a space or end-of-line.
        boolean isHeading = hashes > 0 && (i >= line.length() || line.charAt(i) == ' ');
        int current = isHeading ? hashes : 0;
        String content = isHeading ? line.substring(i).stripLeading() : line.substring(indent.length());
        int level = clamp(fn.next(current));
        if (level == 0) {
            return indent + content;
        }
        return indent + "#".repeat(level) + " " + content;
    }

    private static int clamp(int level) {
        return Math.max(0, Math.min(MAX_LEVEL, level));
    }

    private static int lineStartAt(String text, int offset) {
        int nl = text.lastIndexOf('\n', Math.max(0, offset - 1));
        return nl < 0 ? 0 : nl + 1;
    }

    private static int lineEndAt(String text, int offset) {
        int nl = text.indexOf('\n', offset);
        return nl < 0 ? text.length() : nl;
    }
}
