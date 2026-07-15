package com.editora.markdown;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure (no-toolkit, unit-tested) helpers for Markdown list/blockquote continuation on Enter.
 * {@code EditorBuffer}'s Enter filter (markdown buffers only) uses {@link #continuation(String)} to carry
 * the marker to the next line and {@link #isEmptyItem(String)} to detect an empty item (Enter should end
 * the list by clearing the marker rather than continuing it).
 */
public final class MarkdownLines {

    //              indent       bullet/quote                 task box                 content
    private static final Pattern BULLET = Pattern.compile("^(\\s*)([-*+])(\\s+)(\\[[ xX]\\]\\s+)?(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\s*)(\\d+)([.)])(\\s+)(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^(\\s*)((?:>\\s?)+)(.*)$");
    private static final Pattern BULLET_PREFIX = Pattern.compile("^(\\s*)[-*+]\\s+");
    private static final Pattern TASK_PREFIX = Pattern.compile("^(\\s*)[-*+]\\s+\\[[ xX]\\]\\s+");

    private MarkdownLines() {}

    /** The length of the leading list/quote marker on {@code line} (where content begins), or 0 if none. */
    public static int markerLength(String line) {
        if (line == null) {
            return 0;
        }
        Matcher b = BULLET.matcher(line);
        if (b.matches()) {
            return b.start(5);
        }
        Matcher o = ORDERED.matcher(line);
        if (o.matches()) {
            return o.start(5);
        }
        Matcher q = QUOTE.matcher(line);
        if (q.matches()) {
            return q.start(3);
        }
        return 0;
    }

    /**
     * Toggles a {@code "- "} bullet on every line the selection covers: removes it when <em>all</em>
     * non-blank lines are already bullets, otherwise adds it. Returns a {@link MarkdownEdit} spanning the
     * affected line block.
     */
    public static MarkdownEdit toggleBullet(String text, int selStart, int selEnd) {
        int blockStart = lineStartAt(text, selStart);
        int blockEnd = lineEndAt(text, Math.max(selStart, selEnd));
        String[] lines = text.substring(blockStart, blockEnd).split("\n", -1);
        boolean anyContent = false;
        boolean allBullets = true;
        for (String l : lines) {
            if (l.isBlank()) {
                continue;
            }
            anyContent = true;
            if (!BULLET_PREFIX.matcher(l).find()) {
                allBullets = false;
            }
        }
        boolean remove = anyContent && allBullets;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(remove ? unbullet(lines[i]) : addBullet(lines[i]));
        }
        String repl = out.toString();
        return new MarkdownEdit(blockStart, blockEnd, repl, blockStart, blockStart + repl.length());
    }

    private static String addBullet(String line) {
        if (line.isBlank()) {
            return line;
        }
        String indent = leadingWhitespace(line);
        return indent + "- " + line.substring(indent.length());
    }

    private static String unbullet(String line) {
        Matcher m = BULLET_PREFIX.matcher(line);
        if (m.find()) {
            return m.group(1) + line.substring(m.end());
        }
        return line;
    }

    /**
     * Toggles a GFM task-list checkbox ({@code "- [ ] "}) on every line the selection covers: removes it
     * when <em>all</em> non-blank lines are already task items, otherwise adds it (a plain {@code "- "}
     * bullet gains a {@code "[ ] "} box; a bare line becomes {@code "- [ ] "}). Returns a
     * {@link MarkdownEdit} spanning the affected line block.
     */
    public static MarkdownEdit toggleTask(String text, int selStart, int selEnd) {
        int blockStart = lineStartAt(text, selStart);
        int blockEnd = lineEndAt(text, Math.max(selStart, selEnd));
        String[] lines = text.substring(blockStart, blockEnd).split("\n", -1);
        boolean anyContent = false;
        boolean allTasks = true;
        for (String l : lines) {
            if (l.isBlank()) {
                continue;
            }
            anyContent = true;
            if (!TASK_PREFIX.matcher(l).find()) {
                allTasks = false;
            }
        }
        boolean remove = anyContent && allTasks;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(remove ? untask(lines[i]) : addTask(lines[i]));
        }
        String repl = out.toString();
        return new MarkdownEdit(blockStart, blockEnd, repl, blockStart, blockStart + repl.length());
    }

    private static String addTask(String line) {
        if (line.isBlank() || TASK_PREFIX.matcher(line).find()) {
            return line; // blank, or already a task item
        }
        Matcher b = BULLET_PREFIX.matcher(line);
        if (b.find()) {
            return line.substring(0, b.end()) + "[ ] " + line.substring(b.end()); // bullet → bullet + box
        }
        String indent = leadingWhitespace(line);
        return indent + "- [ ] " + line.substring(indent.length());
    }

    private static String untask(String line) {
        Matcher m = TASK_PREFIX.matcher(line);
        if (m.find()) {
            return m.group(1) + line.substring(m.end()); // drop the "- [ ] " marker, keep indent + content
        }
        return line;
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    private static int lineStartAt(String text, int offset) {
        int nl = text.lastIndexOf('\n', Math.max(0, offset - 1));
        return nl < 0 ? 0 : nl + 1;
    }

    private static int lineEndAt(String text, int offset) {
        int nl = text.indexOf('\n', offset);
        return nl < 0 ? text.length() : nl;
    }

    /**
     * The marker to insert after a newline so the list/quote continues, or {@code null} when {@code line}
     * is not a list or blockquote item. Ordered lists auto-increment; task items reset to {@code [ ]};
     * leading indentation is preserved.
     */
    public static String continuation(String line) {
        if (line == null) {
            return null;
        }
        Matcher b = BULLET.matcher(line);
        if (b.matches()) {
            String task = b.group(4) != null ? "[ ] " : "";
            return b.group(1) + b.group(2) + b.group(3) + task;
        }
        Matcher o = ORDERED.matcher(line);
        if (o.matches()) {
            // The regex accepts an unbounded digit run; a number that overflows a long can't be
            // auto-incremented, so don't continue the list (Enter falls back to a plain newline) rather
            // than throwing NumberFormatException out of the Enter key filter.
            long n;
            try {
                n = Long.parseLong(o.group(2));
            } catch (NumberFormatException overflow) {
                return null;
            }
            return o.group(1) + (n + 1) + o.group(3) + o.group(4);
        }
        Matcher q = QUOTE.matcher(line);
        if (q.matches()) {
            return q.group(1) + q.group(2);
        }
        return null;
    }

    /**
     * Whether {@code line} is a list/quote item whose content is empty (just the marker, possibly an empty
     * task box) — pressing Enter here should terminate the list (clear the marker) instead of continuing.
     */
    public static boolean isEmptyItem(String line) {
        if (line == null) {
            return false;
        }
        Matcher b = BULLET.matcher(line);
        if (b.matches()) {
            return b.group(5).isBlank();
        }
        Matcher o = ORDERED.matcher(line);
        if (o.matches()) {
            return o.group(5).isBlank();
        }
        Matcher q = QUOTE.matcher(line);
        if (q.matches()) {
            return q.group(3).isBlank();
        }
        return false;
    }

    /**
     * Smart Backspace: when the caret (column {@code col}) sits at the end of a line that is <em>only</em> an
     * empty list/blockquote marker (a {@code "- "}/{@code "1. "}/{@code "> "}/{@code "- [ ] "} item with no
     * content), the number of leading characters to delete to clear the whole marker (back to the line start),
     * leaving an empty line — else 0 (caller does a normal single-char Backspace).
     */
    public static int emptyMarkerDeleteLength(String line, int col) {
        return (line != null && col == line.length() && isEmptyItem(line)) ? col : 0;
    }
}
