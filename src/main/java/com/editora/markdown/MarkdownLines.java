package com.editora.markdown;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Pure (no-toolkit, unit-tested) helpers for Markdown list/blockquote continuation on Enter.
 * {@code EditorBuffer}'s Enter filter (markdown buffers only) uses {@link #continuation(String)} to carry
 * the marker to the next line and {@link #isEmptyItem(String)} to detect an empty item (Enter should end
 * the list by clearing the marker rather than continuing it).
 */
public final class MarkdownLines {

    private MarkdownLines() {}

    /** The length of the leading list/quote marker on {@code line} (where content begins), or 0 if none. */
    public static int markerLength(String line) {
        if (line == null) {
            return 0;
        }
        MarkdownLine l = MarkdownLine.parse(line);
        return l.isListItem() || !l.quote().isEmpty() ? l.prefixLength() : 0;
    }

    /** A thematic break written with spaces ({@code - - -}, {@code * * *}): a rule, not a list item. */
    private static final Pattern SPACED_RULE = Pattern.compile("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$");

    /**
     * {@link #markerLength} for the Enter key: 0 when {@code line} — which starts at {@code lineStart} in
     * {@code text} — only looks like a list item. Inside a fenced code block a leading {@code -} is YAML,
     * a diff or program output, and {@code - - -} is a thematic break; continuing either as a list put a
     * marker on the next line that had to be deleted every time.
     */
    public static int listMarkerLength(String text, int lineStart, String line) {
        return listMarkerLength(() -> text, lineStart, line);
    }

    /**
     * As {@link #listMarkerLength(String, int, String)}, asking for the document only when {@code line}
     * really is a list item: the fence test reads everything above the line, and an editor should not build
     * its whole text on every Enter to learn that an ordinary line has no marker.
     */
    public static int listMarkerLength(java.util.function.Supplier<String> text, int lineStart, String line) {
        int marker = markerLength(line);
        if (marker == 0 || SPACED_RULE.matcher(line).matches() || insideFence(text.get(), lineStart)) {
            return 0;
        }
        return marker;
    }

    /**
     * Whether the line starting at {@code lineStart} is something other than Markdown text — a line of a
     * fenced or indented code block (fences nested in a list item or a quote included), of the front matter,
     * of an HTML comment or a verbatim HTML block — as {@link MarkdownBlocks} classifies it. The editing
     * helpers leave such a line alone: a {@code -} there is YAML or a diff, a pipe is program output.
     *
     * <p>Only the text up to the end of that line is scanned where that is enough: what a line is depends on
     * the lines above it.
     */
    static boolean insideFence(String text, int lineStart) {
        return !kindAt(text, lineStart).equals(MarkdownBlocks.Kind.TEXT);
    }

    /** Whether the line starting at {@code lineStart} is Markdown prose: not code, front matter or raw HTML. */
    public static boolean isProse(String text, int lineStart) {
        return !insideFence(text, lineStart);
    }

    /** Whether the line starting at {@code lineStart} is a line of a fenced or indented code block. */
    static boolean insideCode(String text, int lineStart) {
        return switch (kindAt(text, lineStart)) {
            case FENCE_OPEN, FENCE_BODY, FENCE_CLOSE, INDENTED_CODE -> true;
            default -> false;
        };
    }

    private static MarkdownBlocks.Kind kindAt(String text, int lineStart) {
        int start = Math.max(0, Math.min(lineStart, text.length()));
        int eol = text.indexOf('\n', start);
        // Front matter is only front matter once it is closed, which may be below this line: then scan it all.
        boolean wholeDocument = eol < 0 || text.startsWith("---") || text.startsWith("+++");
        MarkdownBlocks blocks = MarkdownBlocks.scan(wholeDocument ? text : text.substring(0, eol));
        int line = 0;
        for (int i = text.indexOf('\n'); i >= 0 && i < start; i = text.indexOf('\n', i + 1)) {
            line++;
        }
        return blocks.kind(Math.min(line, blocks.lineCount() - 1));
    }

    /**
     * Toggles a {@code "- "} bullet on every line the selection covers: removes the list marker (and a task
     * box with it) when <em>all</em> non-blank lines are already bullets, otherwise makes each one a bullet —
     * an ordered item's number is replaced, not kept behind a second marker. Returns {@code null} when
     * nothing changes.
     */
    public static @Nullable MarkdownEdit toggleBullet(String text, int selStart, int selEnd) {
        int[] b = LineEdits.block(text, selStart, selEnd);
        return LineEdits.rewrite(text, b[0], b[1], selStart, selEnd, lines -> {
            boolean remove = all(lines, l -> l.isListItem() && !l.isOrdered());
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].isBlank()) {
                    continue;
                }
                MarkdownLine l = MarkdownLine.parse(lines[i]);
                if (remove) {
                    lines[i] = l.withMarker("").text();
                } else if (l.isOrdered() || !l.isListItem()) {
                    lines[i] = l.withMarker("- ").text();
                }
            }
            return lines;
        });
    }

    /**
     * Toggles a GFM task-list checkbox on every line the selection covers: removes the box (the list marker
     * stays) when <em>all</em> non-blank lines are already task items, otherwise adds one — a list item,
     * bulleted or numbered, gains a {@code "[ ] "} box and a bare line becomes {@code "- [ ] "}. Returns
     * {@code null} when nothing changes.
     */
    public static @Nullable MarkdownEdit toggleTask(String text, int selStart, int selEnd) {
        int[] b = LineEdits.block(text, selStart, selEnd);
        return LineEdits.rewrite(text, b[0], b[1], selStart, selEnd, lines -> {
            boolean remove = all(lines, MarkdownLine::isTask);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].isBlank()) {
                    continue;
                }
                MarkdownLine l = MarkdownLine.parse(lines[i]);
                if (remove) {
                    lines[i] = l.withTask("").text();
                } else if (!l.isTask()) {
                    lines[i] = (l.isListItem() ? l : l.withMarker("- "))
                            .withTask("[ ] ")
                            .text();
                }
            }
            return lines;
        });
    }

    /**
     * Toggles a numbered list on every line the selection covers: removes the markers when <em>all</em>
     * non-blank lines are already numbered, otherwise numbers them 1, 2, 3… at each indentation level in
     * turn — a bullet is replaced, a task box kept. Returns {@code null} when nothing changes.
     */
    public static @Nullable MarkdownEdit toggleOrdered(String text, int selStart, int selEnd) {
        int[] b = LineEdits.block(text, selStart, selEnd);
        return LineEdits.rewrite(text, b[0], b[1], selStart, selEnd, lines -> {
            boolean remove = all(lines, MarkdownLine::isOrdered);
            java.util.Map<Integer, Long> next = new java.util.HashMap<>(); // indent width → next number
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].isBlank()) {
                    continue;
                }
                MarkdownLine l = MarkdownLine.parse(lines[i]);
                if (remove) {
                    lines[i] = l.withMarker("").text();
                    continue;
                }
                int width = l.indent().length();
                next.keySet().removeIf(w -> w > width); // a deeper list ended: it starts over next time
                long n = next.merge(width, 1L, Long::sum);
                String task = l.task();
                lines[i] = l.withMarker(n + ". ").withTask(task).text();
            }
            return lines;
        });
    }

    private static final Pattern QUOTE_MARK = Pattern.compile("^( {0,3})> ?");

    /**
     * Toggles a blockquote on the lines the selection covers: removes one {@code >} level when <em>every</em>
     * line (blank ones aside) is quoted, otherwise quotes them all — blank lines inside the block too, so it
     * stays one quote. Returns {@code null} when nothing changes.
     */
    public static @Nullable MarkdownEdit toggleQuote(String text, int selStart, int selEnd) {
        int[] b = LineEdits.block(text, selStart, selEnd);
        return LineEdits.rewrite(text, b[0], b[1], selStart, selEnd, lines -> {
            boolean any = false;
            boolean remove = true;
            for (String l : lines) {
                if (!l.isBlank()) {
                    any = true;
                    remove &= QUOTE_MARK.matcher(l).find();
                }
            }
            for (int i = 0; i < lines.length; i++) {
                if (remove && any) {
                    java.util.regex.Matcher m = QUOTE_MARK.matcher(lines[i]);
                    lines[i] = m.find() ? m.group(1) + lines[i].substring(m.end()) : lines[i];
                } else {
                    lines[i] = lines[i].isBlank() && lines.length > 1 ? ">" : "> " + lines[i];
                }
            }
            return lines;
        });
    }

    private static final Pattern FENCE_LINE = Pattern.compile("^ {0,3}(`{3,}|~{3,})[^`]*$");

    /**
     * Wraps the lines the selection covers in a fenced code block — or, when the selection is exactly the
     * body of one (a fence line directly above and below), takes the fences away. The fence is longer than
     * any run of backticks at the start of a wrapped line. After wrapping the caret is behind the opening
     * fence, where the language goes.
     */
    public static MarkdownEdit toggleFence(String text, int selStart, int selEnd) {
        int[] b = LineEdits.block(text, selStart, selEnd);
        int aboveStart = b[0] == 0 ? -1 : lineStart(text, b[0] - 1);
        int belowEnd = b[1] >= text.length() ? -1 : lineEnd(text, b[1] + 1);
        if (aboveStart >= 0
                && belowEnd >= 0
                && FENCE_LINE.matcher(text.substring(aboveStart, b[0] - 1)).matches()
                && FENCE_LINE.matcher(text.substring(b[1] + 1, belowEnd)).matches()) {
            String body = text.substring(b[0], b[1]);
            int shift = b[0] - aboveStart;
            return new MarkdownEdit(
                    aboveStart,
                    belowEnd,
                    body,
                    Math.max(aboveStart, Math.min(selStart, selEnd) - shift),
                    Math.max(aboveStart, Math.max(selStart, selEnd) - shift));
        }
        String body = text.substring(b[0], b[1]);
        int longest = 2;
        for (String line : body.split("\n", -1)) {
            String t = line.stripLeading();
            int run = 0;
            while (run < t.length() && t.charAt(run) == '`') {
                run++;
            }
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(longest + 1);
        int at = b[0] + fence.length();
        return new MarkdownEdit(b[0], b[1], fence + "\n" + body + "\n" + fence, at, at);
    }

    /**
     * Ticks or unticks the task items among the lines the selection covers: all are unticked when every one
     * is ticked, otherwise all are ticked. Returns {@code null} when none of the lines is a task item.
     */
    public static @Nullable MarkdownEdit toggleDone(String text, int selStart, int selEnd) {
        int[] b = LineEdits.block(text, selStart, selEnd);
        return LineEdits.rewrite(text, b[0], b[1], selStart, selEnd, lines -> {
            boolean allDone = true;
            for (String l : lines) {
                MarkdownLine m = MarkdownLine.parse(l);
                allDone &= !m.isTask() || m.task().charAt(1) != ' ';
            }
            for (int i = 0; i < lines.length; i++) {
                MarkdownLine m = MarkdownLine.parse(lines[i]);
                if (m.isTask()) {
                    lines[i] = m.withTask("[" + (allDone ? ' ' : 'x') + m.task().substring(2))
                            .text();
                }
            }
            return lines;
        });
    }

    /** Whether every non-blank line passes {@code test} (and there is at least one). */
    private static boolean all(String[] lines, java.util.function.Predicate<MarkdownLine> test) {
        boolean any = false;
        for (String l : lines) {
            if (l.isBlank()) {
                continue;
            }
            any = true;
            if (!test.test(MarkdownLine.parse(l))) {
                return false;
            }
        }
        return any;
    }

    /**
     * The marker to insert after a newline so the list/quote continues, or {@code null} when {@code line}
     * is not a list or blockquote item. Ordered lists auto-increment; task items reset to {@code [ ]};
     * indentation and a blockquote prefix in front of the marker are preserved.
     */
    public static @Nullable String continuation(String line) {
        if (line == null) {
            return null;
        }
        MarkdownLine l = MarkdownLine.parse(line);
        return !l.isListItem() && l.quote().isEmpty() ? null : continuationOf(l);
    }

    /** {@link #continuation(String)} of a line that is a list or quote item. */
    private static String continuationOf(MarkdownLine l) {
        if (!l.isListItem()) {
            return l.indent() + l.quote();
        }
        String marker = l.isOrdered() ? l.markerNumbered(l.number() + 1) : l.marker();
        return l.indent() + l.quote() + marker + (l.isTask() ? "[ ] " : "");
    }

    /**
     * Whether {@code line} is a list/quote item whose content is empty (just the marker, possibly an empty
     * task box) — pressing Enter here should step out of the list instead of continuing it.
     */
    public static boolean isEmptyItem(String line) {
        if (line == null) {
            return false;
        }
        MarkdownLine l = MarkdownLine.parse(line);
        return (l.isListItem() || !l.quote().isEmpty()) && l.isBlank();
    }

    /**
     * Smart Backspace: when the caret (column {@code col}) sits at the end of a line that is <em>only</em> an
     * empty list/blockquote marker, the number of leading characters to delete to clear the whole marker —
     * else 0. {@link #backspace} is the document-aware form the editor uses.
     */
    public static int emptyMarkerDeleteLength(String line, int col) {
        return (line != null && col == line.length() && isEmptyItem(line)) ? col : 0;
    }

    // ---- Enter / Backspace / Tab on a list item (document-aware) ----

    /**
     * Enter in a list or blockquote item, with the caret at {@code caret}: continue the item on a new line
     * (renumbering the ordered items that follow), or — on an empty item — step out one level. Returns
     * {@code null} when the caret's line is not an item (or only looks like one: a thematic break, a line
     * inside a fenced block, the caret still inside the marker), so the caller inserts a plain newline.
     */
    public static @Nullable MarkdownEdit enter(String text, int caret) {
        int ls = lineStart(text, caret);
        int le = lineEnd(text, caret);
        String line = text.substring(ls, le);
        MarkdownLine l = MarkdownLine.parse(line);
        if ((!l.isListItem() && l.quote().isEmpty())
                || caret - ls < l.prefixLength()
                || SPACED_RULE.matcher(line).matches()
                || insideFence(text, ls)) {
            return null;
        }
        if (l.isBlank()) {
            return stepOut(text, ls, le, l);
        }
        // Text moved to the new item starts at its first character, not at the blank that preceded it.
        int rest = caret;
        while (rest < le && text.charAt(rest) == ' ') {
            rest++;
        }
        // A list numbered 1. 1. 1. stays that way; any other gets the next number and its tail shifted.
        MarkdownLine next = l.isOrdered() ? nextSibling(text, le, l) : null;
        boolean lazy = next != null && next.number() == l.number();
        String cont = l.isOrdered() && lazy
                ? l.indent() + l.quote() + l.marker() + (l.isTask() ? "[ ] " : "")
                : continuationOf(l);
        int at = caret + 1 + cont.length();
        if (l.isOrdered() && !lazy) {
            int end = runEnd(text, le, l);
            String tail = text.substring(le, end);
            String shifted = renumberTail(tail, l, l.number() + 2);
            if (!shifted.equals(tail)) {
                return new MarkdownEdit(caret, end, "\n" + cont + text.substring(rest, le) + shifted, at, at);
            }
        }
        return new MarkdownEdit(caret, rest, "\n" + cont, at, at);
    }

    /**
     * Backspace at the end of an empty list/quote item: step out one level, like Enter there. Returns
     * {@code null} anywhere else (the caller does a normal Backspace).
     */
    public static @Nullable MarkdownEdit backspace(String text, int caret) {
        int ls = lineStart(text, caret);
        int le = lineEnd(text, caret);
        if (caret != le || caret == ls) {
            return null;
        }
        MarkdownLine l = MarkdownLine.parse(text.substring(ls, le));
        return (l.isListItem() || !l.quote().isEmpty()) && l.isBlank() ? stepOut(text, ls, le, l) : null;
    }

    /** An empty item one level out: a nested item moves to its parent's level, a top-level one loses its marker. */
    private static MarkdownEdit stepOut(String text, int ls, int le, MarkdownLine l) {
        if (l.isListItem() && !l.indent().isEmpty()) {
            MarkdownEdit out = shift(text, ls, le, l, true);
            if (out != null) {
                int at = ls + firstLine(out.replacement()).length();
                return new MarkdownEdit(out.from(), out.to(), out.replacement(), at, at);
            }
        }
        String left = l.isListItem() && !l.quote().isEmpty() ? l.indent() + l.quote() : "";
        return new MarkdownEdit(ls, le, left, ls + left.length(), ls + left.length());
    }

    /**
     * Tab ({@code outdent} false) or Shift-Tab on a list item: nest it under the item above, or move it out
     * to its parent's level; an ordered item is renumbered for its new level, as are the items after it.
     * Returns {@code null} when the caret's line is not a list item, and when there is nothing to outdent, so
     * the caller's ordinary Tab runs; an item that cannot be nested any deeper is an edit that changes
     * nothing (Tab is still consumed: spaces in the middle of the item are never what was meant).
     */
    public static @Nullable MarkdownEdit indentItem(String text, int caret, boolean outdent) {
        int ls = lineStart(text, caret);
        int le = lineEnd(text, caret);
        String line = text.substring(ls, le);
        MarkdownLine l = MarkdownLine.parse(line);
        if (!l.isListItem() || SPACED_RULE.matcher(line).matches() || insideFence(text, ls)) {
            return null;
        }
        if (outdent && l.indent().isEmpty()) {
            return null;
        }
        MarkdownEdit e = shift(text, ls, le, l, outdent);
        if (e == null) {
            return new MarkdownEdit(caret, caret, "", caret, caret);
        }
        int col = LineEdits.mapColumn(caret - ls, line, firstLine(e.replacement()));
        return new MarkdownEdit(e.from(), e.to(), e.replacement(), ls + col, ls + col);
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** Moves the item on {@code [ls, le)} one level in or out; null when there is no level to move to. */
    private static @Nullable MarkdownEdit shift(String text, int ls, int le, MarkdownLine l, boolean outdent) {
        int width = l.indent().length();
        // Walk up: the nearest item that is not deeper than this one (indent), or shallower (outdent).
        MarkdownLine anchor = null;
        int at = ls;
        while (at > 0) {
            int prevStart = lineStart(text, at - 1);
            String prev = text.substring(prevStart, at - 1);
            at = prevStart;
            if (prev.isBlank()) {
                continue; // a loose list: blank lines between its items
            }
            MarkdownLine p = MarkdownLine.parse(prev);
            if (!p.quote().equals(l.quote())) {
                break;
            }
            if (!p.isListItem()) {
                if (p.indent().isEmpty()) {
                    break; // a paragraph at the margin ends the list above
                }
                continue; // a continuation line of some item
            }
            int w = p.indent().length();
            if (outdent ? w < width : w <= width) {
                anchor = p;
                break;
            }
        }
        String indent;
        long number;
        if (outdent) {
            indent = anchor == null ? "" : anchor.indent();
            number = anchor != null && anchor.isOrdered() && anchor.markerKind() == l.markerKind()
                    ? anchor.number() + 1
                    : 1;
        } else {
            if (anchor == null || anchor.indent().length() < width) {
                return null; // first item of its list, or already the first child of the item above
            }
            indent = anchor.indent() + " ".repeat(anchor.marker().length());
            number = 1;
            long sibling = lastChildNumber(text, ls, indent.length(), l);
            if (sibling > 0) {
                number = sibling + 1;
            }
        }
        MarkdownLine moved = l.withIndent(indent);
        if (l.isOrdered()) {
            moved = moved.withMarker(l.markerNumbered(number));
        }
        // Its children — the deeper lines under it — move with it, by the same number of columns.
        int childEnd = childrenEnd(text, le, width);
        String children = reindent(text.substring(le, childEnd), indent.length() - width);
        // The items after it: those at its new level carry on from it, those at its old level close the gap.
        String tail = text.substring(childEnd, runEnd(text, childEnd, l.withIndent(outdent ? indent : l.indent())));
        int end = childEnd + tail.length();
        if (!tail.isEmpty()) {
            long oldLevelNext = outdent ? 1 : (anchor != null && anchor.isOrdered() ? anchor.number() + 1 : l.number());
            tail = renumberTail(tail, moved, l.isOrdered() ? number + 1 : -1);
            tail = renumberTail(
                    tail, l, l.isOrdered() && (outdent || (anchor != null && anchor.isOrdered())) ? oldLevelNext : -1);
        }
        return new MarkdownEdit(ls, end, moved.text() + children + tail, ls, ls);
    }

    /** End of the lines after {@code le} that are deeper than {@code width} columns: an item's children. */
    private static int childrenEnd(String text, int le, int width) {
        int at = le;
        while (at < text.length()) {
            int end = lineEnd(text, at + 1);
            String line = text.substring(at + 1, end);
            if (line.isBlank()) {
                // A blank line belongs to the item only when something deeper follows it.
                int next = end;
                String after = "";
                while (next < text.length() && after.isBlank()) {
                    int nextEnd = lineEnd(text, next + 1);
                    after = text.substring(next + 1, nextEnd);
                    next = nextEnd;
                }
                if (after.isBlank() || MarkdownLine.parse(after).indent().length() <= width) {
                    break;
                }
            } else if (MarkdownLine.parse(line).indent().length() <= width) {
                break;
            }
            at = end;
        }
        return at;
    }

    /** {@code lines} (each starting with a newline) moved {@code delta} columns right (or left, if negative). */
    private static String reindent(String lines, int delta) {
        if (delta == 0 || lines.isEmpty()) {
            return lines;
        }
        String[] parts = lines.split("\n", -1);
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isBlank()) {
                continue;
            }
            if (delta > 0) {
                parts[i] = " ".repeat(delta) + parts[i];
            } else {
                int cut = 0;
                while (cut < -delta && cut < parts[i].length() && parts[i].charAt(cut) == ' ') {
                    cut++;
                }
                parts[i] = parts[i].substring(cut);
            }
        }
        return String.join("\n", parts);
    }

    /**
     * Numbers the ordered list around {@code offset} in sequence again, from the smallest number it holds —
     * after a line was moved up or down inside it. Returns {@code null} when the offset's line is not an
     * ordered item, the list is numbered lazily (every item the same number) or is already in order; the
     * edit's caret is {@code offset} on its text.
     */
    public static @Nullable MarkdownEdit renumber(String text, int offset) {
        return renumber(text, offset, offset);
    }

    /**
     * {@link #renumber(String, int)} for a selection (a block of lines that was moved): the list is the one
     * the selection starts in, and both ends of the selection are mapped into the result.
     */
    public static @Nullable MarkdownEdit renumber(String text, int selStart, int selEnd) {
        int offset = Math.min(selStart, selEnd);
        int ls = lineStart(text, offset);
        int le = lineEnd(text, offset);
        String line = text.substring(ls, le);
        MarkdownLine l = MarkdownLine.parse(line);
        if (!l.isOrdered() || insideFence(text, ls)) {
            return null;
        }
        // Up to the first item of the run: siblings at this level, with their deeper lines in between.
        int first = ls;
        int at = ls;
        while (at > 0) {
            int prevStart = lineStart(text, at - 1);
            String prev = text.substring(prevStart, at - 1);
            MarkdownLine p = MarkdownLine.parse(prev);
            int w = p.indent().length();
            if (prev.isBlank() || w < l.indent().length() || !sameQuote(p, l)) {
                break;
            }
            at = prevStart;
            if (w == l.indent().length()) {
                if (!p.isOrdered() || p.markerKind() != l.markerKind()) {
                    break;
                }
                first = prevStart;
            }
        }
        int firstEnd = lineEnd(text, first);
        MarkdownLine head = MarkdownLine.parse(text.substring(first, firstEnd));
        int end = runEnd(text, firstEnd, head);
        long min = head.number();
        boolean lazy = true;
        for (String s : text.substring(firstEnd, end).split("\n", -1)) {
            MarkdownLine n = MarkdownLine.parse(s);
            if (n.isOrdered() && n.indent().length() == head.indent().length()) {
                lazy &= n.number() == head.number();
                min = Math.min(min, n.number());
            }
        }
        String before = text.substring(first, end);
        String after = head.withMarker(head.markerNumbered(min)).text()
                + renumberTail(text.substring(firstEnd, end), head, min + 1);
        if (lazy || after.equals(before)) {
            return null;
        }
        // A line may have changed width in front of an offset and in its own marker.
        String[] old = before.split("\n", -1);
        String[] now = after.split("\n", -1);
        int from = first;
        int to = end;
        java.util.function.IntUnaryOperator map = o -> {
            if (o < from) {
                return o;
            }
            if (o > to) {
                return o + after.length() - before.length();
            }
            int oldStart = from;
            int newStart = from;
            int i = 0;
            while (i < old.length - 1 && oldStart + old[i].length() < o) {
                oldStart += old[i].length() + 1;
                newStart += now[i].length() + 1;
                i++;
            }
            return newStart + LineEdits.mapColumn(o - oldStart, old[i], now[i]);
        };
        return new MarkdownEdit(first, end, after, map.applyAsInt(selStart), map.applyAsInt(selEnd));
    }

    /** The number of the last ordered item directly above {@code ls} at exactly {@code width} columns; 0 if none. */
    private static long lastChildNumber(String text, int ls, int width, MarkdownLine l) {
        int at = ls;
        while (at > 0) {
            int prevStart = lineStart(text, at - 1);
            String prev = text.substring(prevStart, at - 1);
            at = prevStart;
            if (prev.isBlank()) {
                continue;
            }
            MarkdownLine p = MarkdownLine.parse(prev);
            int w = p.indent().length();
            if (!p.isListItem()) {
                if (w == 0) {
                    return 0;
                }
                continue;
            }
            if (w < width) {
                return 0;
            }
            if (w == width) {
                return p.isOrdered() && p.markerKind() == l.markerKind() ? p.number() : 0;
            }
        }
        return 0;
    }

    /** The next item after offset {@code le} at the level of {@code l}, or null. */
    private static @Nullable MarkdownLine nextSibling(String text, int le, MarkdownLine l) {
        int at = le;
        while (at < text.length()) {
            int end = lineEnd(text, at + 1);
            String line = text.substring(at + 1, end);
            at = end;
            if (line.isBlank()) {
                return null;
            }
            MarkdownLine n = MarkdownLine.parse(line);
            int w = n.indent().length();
            if (w > l.indent().length()) {
                continue;
            }
            return w == l.indent().length() && n.isOrdered() && n.markerKind() == l.markerKind() && sameQuote(n, l)
                    ? n
                    : null;
        }
        return null;
    }

    private static boolean sameQuote(MarkdownLine a, MarkdownLine b) {
        return a.quote().strip().equals(b.quote().strip());
    }

    /** End of the run of lines after {@code le} that belong to the list of {@code l} (up to a blank line). */
    private static int runEnd(String text, int le, MarkdownLine l) {
        int at = le;
        while (at < text.length()) {
            int end = lineEnd(text, at + 1);
            String line = text.substring(at + 1, end);
            if (line.isBlank()) {
                break;
            }
            MarkdownLine n = MarkdownLine.parse(line);
            if (n.indent().length() < l.indent().length() || !sameQuote(n, l)) {
                break;
            }
            if (n.indent().length() == l.indent().length() && !n.isListItem()) {
                break;
            }
            at = end;
        }
        return at;
    }

    /**
     * {@code tail} (starts with a newline, or is empty) with the ordered siblings of {@code level} numbered
     * from {@code next}; deeper lines are left alone and the first shallower one ends the run. A negative
     * {@code next} changes nothing.
     */
    private static String renumberTail(String tail, MarkdownLine level, long next) {
        if (next < 0 || tail.isEmpty()) {
            return tail;
        }
        String[] lines = tail.split("\n", -1);
        int width = level.indent().length();
        for (int i = 1; i < lines.length; i++) {
            MarkdownLine n = MarkdownLine.parse(lines[i]);
            int w = n.indent().length();
            if (w > width) {
                continue;
            }
            if (w < width || !n.isOrdered() || n.markerKind() != level.markerKind()) {
                break;
            }
            lines[i] = n.withMarker(n.markerNumbered(next++)).text();
        }
        return String.join("\n", lines);
    }

    private static int lineStart(String text, int offset) {
        int nl = text.lastIndexOf('\n', offset - 1);
        return nl < 0 ? 0 : nl + 1;
    }

    private static int lineEnd(String text, int offset) {
        int nl = text.indexOf('\n', offset);
        return nl < 0 ? text.length() : nl;
    }
}
