package com.editora.markdown;

import java.util.regex.Pattern;

/**
 * Pure (no-toolkit, unit-tested) Markdown heading level changes over the line(s) a selection covers:
 * promote/demote by a delta, or set an absolute level (0 = Normal/paragraph, 1–6 = {@code #}…{@code ######}).
 * Indentation, a blockquote prefix and a list marker in front of the heading are preserved; blank lines and
 * lines inside a fenced code block are left alone; a Setext heading ({@code Title} over {@code ====}) is
 * rewritten as an ATX one. The caret and selection stay on their text. {@code EditorBuffer} applies the
 * returned {@link MarkdownEdit}.
 */
public final class MarkdownHeading {

    private static final int MAX_LEVEL = 6;

    private static final Pattern SETEXT = Pattern.compile("^ {0,3}(=+|-+)[ \\t]*$");

    private MarkdownHeading() {}

    /**
     * Promote ({@code delta < 0}, fewer #) or demote ({@code delta > 0}, more #). With the caret on one line,
     * that line: text becomes a level-1 heading on demote, and a level-1 heading stays one on promote. Over
     * several lines only the lines that already are headings move — unless none is, when demote makes every
     * one a heading. Returns {@code null} when nothing changes.
     */
    public static MarkdownEdit apply(String text, int selStart, int selEnd, int delta) {
        return rebuild(text, selStart, selEnd, (level, several) -> {
            if (level == 0) {
                return several || delta <= 0 ? 0 : Math.min(MAX_LEVEL, delta);
            }
            return Math.max(1, Math.min(MAX_LEVEL, level + delta));
        });
    }

    /** Set each non-blank line in range to an absolute heading {@code level} (0 = Normal). */
    public static MarkdownEdit setLevel(String text, int selStart, int selEnd, int level) {
        int target = Math.max(0, Math.min(MAX_LEVEL, level));
        return rebuild(text, selStart, selEnd, (ignored, several) -> target);
    }

    /** The heading level (0 = none) of the line containing {@code offset}; a Setext heading counts. */
    public static int levelAt(String text, int offset) {
        int o = Math.max(0, Math.min(offset, text.length()));
        int nl = text.lastIndexOf('\n', o - 1);
        int start = nl < 0 ? 0 : nl + 1;
        int end = text.indexOf('\n', o);
        end = end < 0 ? text.length() : end;
        String line = text.substring(start, end);
        int level = MarkdownLine.parse(line).heading();
        if (level > 0 || line.isBlank() || end >= text.length()) {
            return level;
        }
        int nextEnd = text.indexOf('\n', end + 1);
        return setextLevel(line, text.substring(end + 1, nextEnd < 0 ? text.length() : nextEnd));
    }

    private interface LevelFn {
        /** @param several whether the block holds headings among other lines (then plain text is not touched) */
        int next(int current, boolean several);
    }

    /** 1 or 2 when {@code underline} makes the paragraph line {@code line} a Setext heading, else 0. */
    private static int setextLevel(String line, String underline) {
        MarkdownLine l = MarkdownLine.parse(line);
        if (line.isBlank()
                || l.isListItem()
                || !l.quote().isEmpty()
                || l.heading() > 0
                || l.indent().length() > 3) {
            return 0;
        }
        java.util.regex.Matcher m = SETEXT.matcher(underline);
        return m.matches() ? (m.group(1).charAt(0) == '=' ? 1 : 2) : 0;
    }

    private static MarkdownEdit rebuild(String text, int selStart, int selEnd, LevelFn fn) {
        int[] b = LineEdits.block(text, selStart, selEnd);
        int blockStart = b[0];
        int blockEnd = b[1];
        // A Setext heading is two lines: take its underline in when the block ends on the title.
        if (blockEnd < text.length()) {
            int nextEnd = text.indexOf('\n', blockEnd + 1);
            nextEnd = nextEnd < 0 ? text.length() : nextEnd;
            int lastStart = text.lastIndexOf('\n', blockEnd - 1) + 1;
            if (lastStart >= blockStart
                    && setextLevel(text.substring(lastStart, blockEnd), text.substring(blockEnd + 1, nextEnd)) > 0) {
                blockEnd = nextEnd;
            }
        }
        int start = blockStart;
        return LineEdits.rewrite(text, blockStart, blockEnd, selStart, selEnd, lines -> {
            int[] level = new int[lines.length];
            boolean[] skip = new boolean[lines.length];
            int headings = 0;
            int others = 0;
            int at = start;
            for (int i = 0; i < lines.length; i++) {
                int lineStart = at;
                at += lines[i].length() + 1;
                if (skip[i]) {
                    continue; // a Setext underline, already claimed by the line above
                }
                MarkdownLine l = MarkdownLine.parse(lines[i]);
                level[i] = l.heading();
                if (level[i] == 0 && i + 1 < lines.length) {
                    level[i] = -setextLevel(lines[i], lines[i + 1]); // negative: Setext
                    skip[i + 1] = level[i] != 0;
                }
                if (lines[i].isBlank() || (lines[i].indexOf('#') >= 0 && MarkdownLines.insideFence(text, lineStart))) {
                    skip[i] = true;
                } else if (level[i] != 0) {
                    headings++;
                } else {
                    others++;
                }
            }
            boolean several = headings > 0 && others > 0;
            for (int i = 0; i < lines.length; i++) {
                boolean underline = i > 0 && level[i - 1] < 0;
                if (underline) {
                    lines[i] = null; // the ATX form replaces it
                    continue;
                }
                if (skip[i]) {
                    continue;
                }
                int next = fn.next(Math.abs(level[i]), several);
                lines[i] = MarkdownLine.parse(lines[i]).withHeading(next).text();
                if (level[i] < 0 && next == 0) {
                    lines[i + 1] = null; // "Normal" on a Setext heading still has to drop the underline
                }
            }
            return lines;
        });
    }
}
