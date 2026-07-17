package com.editora.typst;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.markdown.MarkdownEdit;

/**
 * Pure (no-toolkit, unit-tested) editing helpers for Typst markup — the Typst analogue of the
 * {@code com.editora.markdown} editing cores. Typst's markup differs from Markdown: headings use
 * {@code =}/{@code ==}/… (not {@code #}), links are {@code #link("url")[text]}, and list markers are
 * {@code -} (bullet), {@code +} (numbered), or {@code N.}/{@code N)} — deliberately <b>not</b> {@code *},
 * which is Typst's <em>bold</em> markup, so a {@code *bold*} line is never mistaken for a list item.
 *
 * <p>Inline emphasis ({@code *bold*}, {@code _emph_}, {@code `raw`}) is a symmetric wrap, so
 * {@code EditorBuffer} reuses the generic {@code MarkdownInline.toggle} for those; this class supplies the
 * pieces that genuinely differ. All methods return a neutral {@link MarkdownEdit} (the shared "text edit +
 * selection" record the editor applies).
 */
public final class TypstMarkup {

    private static final int MAX_LEVEL = 6;

    //                                        indent     marker    space  content
    private static final Pattern BULLET = Pattern.compile("^(\\s*)([-+])(\\s+)(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\s*)(\\d+)([.)])(\\s+)(.*)$");

    private TypstMarkup() {}

    // --- headings (= / == / …) ---------------------------------------------------------------------

    /** Promote ({@code delta < 0}, fewer {@code =}) or demote ({@code delta > 0}) each line in range. */
    public static MarkdownEdit heading(String text, int selStart, int selEnd, int delta) {
        return rebuildHeadings(text, selStart, selEnd, level -> clamp(level + delta));
    }

    /** Set each line in range to an absolute heading {@code level} (0 = Normal/paragraph). */
    public static MarkdownEdit setHeadingLevel(String text, int selStart, int selEnd, int level) {
        int target = clamp(level);
        return rebuildHeadings(text, selStart, selEnd, ignored -> target);
    }

    private interface LevelFn {
        int next(int current);
    }

    private static MarkdownEdit rebuildHeadings(String text, int selStart, int selEnd, LevelFn fn) {
        int blockStart = lineStartAt(text, selStart);
        int blockEnd = lineEndAt(text, Math.max(selStart, selEnd));
        String[] lines = text.substring(blockStart, blockEnd).split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(rewriteHeading(lines[i], fn));
        }
        String replacement = out.toString();
        return new MarkdownEdit(blockStart, blockEnd, replacement, blockStart, blockStart + replacement.length());
    }

    private static String rewriteHeading(String line, LevelFn fn) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        String indent = line.substring(0, i);
        int eqs = 0;
        while (i < line.length() && line.charAt(i) == '=' && eqs < MAX_LEVEL) {
            eqs++;
            i++;
        }
        // Treat leading '='s as a heading prefix only when followed by a space or end-of-line.
        boolean isHeading = eqs > 0 && (i >= line.length() || line.charAt(i) == ' ');
        int current = isHeading ? eqs : 0;
        String content = isHeading ? line.substring(i).stripLeading() : line.substring(indent.length());
        int level = clamp(fn.next(current));
        if (level == 0) {
            return indent + content;
        }
        return indent + "=".repeat(level) + " " + content;
    }

    // --- links (#link("url")[text]) ----------------------------------------------------------------

    /**
     * Turns {@code [selStart, selEnd)} into a Typst link {@code #link("url")[sel]}. With a blank {@code url}
     * the caret is placed inside the empty {@code ""}; otherwise the link text is selected.
     */
    public static MarkdownEdit link(String text, int selStart, int selEnd, String url) {
        String sel = text.substring(selStart, selEnd);
        String u = url == null ? "" : url.strip();
        String replacement = "#link(\"" + u + "\")[" + sel + "]";
        if (u.isEmpty()) {
            int caret = selStart + "#link(\"".length(); // between the quotes
            return new MarkdownEdit(selStart, selEnd, replacement, caret, caret);
        }
        int textStart = selStart + "#link(\"".length() + u.length() + "\")[".length();
        return new MarkdownEdit(selStart, selEnd, replacement, textStart, textStart + sel.length());
    }

    // --- inserts (table / image / outline) ---------------------------------------------------------

    /** The Typst image call {@code #image("relPath")}. Pure. */
    public static String image(String relPath) {
        return "#image(\"" + (relPath == null ? "" : relPath) + "\")";
    }

    /** The table-of-contents call {@code #outline()}. */
    public static final String OUTLINE = "#outline()";

    /**
     * A {@code #table(...)} skeleton with {@code cols} columns and {@code rows} rows of empty {@code []}
     * cells (the first row reads as a header). {@code caretOffset()} points inside the first cell so the
     * caller can place the caret there. Pure; unit-tested.
     */
    public record Table(String text, int caretOffset) {}

    public static Table table(int rows, int cols) {
        int r = Math.max(1, rows);
        int c = Math.max(1, cols);
        StringBuilder sb = new StringBuilder();
        sb.append("#table(\n  columns: ").append(c).append(",\n");
        int caret = -1;
        for (int row = 0; row < r; row++) {
            sb.append("  ");
            for (int col = 0; col < c; col++) {
                if (caret < 0) {
                    caret = sb.length() + 1; // inside the first "[]"
                }
                sb.append("[]");
                if (col < c - 1) {
                    sb.append(", ");
                }
            }
            sb.append(",\n");
        }
        sb.append(")");
        return new Table(sb.toString(), caret < 0 ? sb.length() : caret);
    }

    // --- list continuation (Enter) -----------------------------------------------------------------

    /** The length of the leading list marker on {@code line} (where content begins), or 0 if none. */
    public static int markerLength(String line) {
        if (line == null) {
            return 0;
        }
        Matcher b = BULLET.matcher(line);
        if (b.matches()) {
            return b.start(4);
        }
        Matcher o = ORDERED.matcher(line);
        if (o.matches()) {
            return o.start(5);
        }
        return 0;
    }

    /**
     * The marker to insert after a newline so the list continues, or {@code null} when {@code line} is not a
     * list item. A {@code +} (auto-numbered) marker carries forward as {@code +}; an explicit {@code N.}
     * increments; leading indentation is preserved.
     */
    public static String continuation(String line) {
        if (line == null) {
            return null;
        }
        Matcher b = BULLET.matcher(line);
        if (b.matches()) {
            return b.group(1) + b.group(2) + b.group(3);
        }
        Matcher o = ORDERED.matcher(line);
        if (o.matches()) {
            // The regex accepts an unbounded digit run; a number that overflows a long can't be
            // auto-incremented, so don't continue the list (Enter falls back to a plain newline) rather
            // than throwing NumberFormatException out of the Enter key filter. (The Markdown sibling,
            // MarkdownLines.continuation, guards this — the Typst copy dropped the guard.)
            long n;
            try {
                n = Long.parseLong(o.group(2));
            } catch (NumberFormatException overflow) {
                return null;
            }
            return o.group(1) + (n + 1) + o.group(3) + o.group(4);
        }
        return null;
    }

    /** Whether {@code line} is a list item whose content is empty (just the marker) — Enter should end the list. */
    public static boolean isEmptyItem(String line) {
        if (line == null) {
            return false;
        }
        Matcher b = BULLET.matcher(line);
        if (b.matches()) {
            return b.group(4).isBlank();
        }
        Matcher o = ORDERED.matcher(line);
        if (o.matches()) {
            return o.group(5).isBlank();
        }
        return false;
    }

    /**
     * Smart Backspace: when the caret (column {@code col}) sits at the end of a line that is only an empty
     * list marker, the number of leading characters to delete to clear the whole marker (back to the line
     * start) — else 0.
     */
    public static int emptyMarkerDeleteLength(String line, int col) {
        return (line != null && col == line.length() && isEmptyItem(line)) ? col : 0;
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
