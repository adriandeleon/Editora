package com.editora.editops;

import java.util.function.IntFunction;

/**
 * The line commands of the VS Code family — Insert Line Below / Above, Delete Line, Select Line, Indent /
 * Outdent Lines — as the edit each makes for <b>one</b> caret or selection. {@link MultiTab#plan} runs them at
 * every caret as one edit, exactly as it runs Tab.
 *
 * <p>Each result is an {@link Indenter.TabEdit}: replace {@code [from, to)} with {@code replacement}, then
 * select {@code [selStart, selEnd)} — positions in the text after that one replacement. A selection that
 * stops at the very start of a line does not take that line in. Pure.
 */
public final class LineCommands {

    private LineCommands() {}

    /**
     * Ctrl+Enter: a new line below the last line of the selection, indented as Enter at that line's end
     * would indent it ({@code enterAt}), with the caret on it. Nothing after the caret moves down with it.
     */
    public static Indenter.TabEdit insertBelow(
            String text, int selStart, int selEnd, IntFunction<Indenter.EnterEdit> enterAt) {
        int end = lineEnd(text, lastLineOffset(selStart, selEnd, text));
        Indenter.EnterEdit enter = enterAt.apply(end);
        int caret = end + enter.caretOffset();
        return new Indenter.TabEdit(end, end, enter.insert(), caret, caret);
    }

    /** Ctrl+Shift+Enter: a new line above the selection's first line, with that line's indentation. */
    public static Indenter.TabEdit insertAbove(String text, int selStart, int selEnd) {
        int start = lineStart(text, Math.min(selStart, selEnd));
        String indent = leadingWhitespace(text, start);
        int caret = start + indent.length();
        return new Indenter.TabEdit(start, start, indent + "\n", caret, caret);
    }

    /**
     * Ctrl+Shift+K: deletes every line the selection touches, with its line break (the last line takes the
     * break before it). The caret keeps its column on the line that moves up into its place.
     */
    public static Indenter.TabEdit deleteLines(String text, int selStart, int selEnd) {
        int lo = Math.min(selStart, selEnd);
        int first = lineStart(text, lo);
        int column = Math.min(selStart, selEnd) - first;
        int lastEnd = lineEnd(text, lastLineOffset(selStart, selEnd, text));
        if (lastEnd < text.length()) { // a line break follows: it goes too, and the next line moves up
            int to = lastEnd + 1;
            int nextLength = lineEnd(text, to) - to;
            int caret = first + Math.min(column, nextLength);
            return new Indenter.TabEdit(first, to, "", caret, caret);
        }
        if (first == 0) { // the whole document
            return new Indenter.TabEdit(0, text.length(), "", 0, 0);
        }
        int from = first - 1; // the last line: its preceding break goes, the line above stays where it is
        int prevStart = lineStart(text, from);
        int caret = prevStart + Math.min(column, from - prevStart);
        return new Indenter.TabEdit(from, text.length(), "", caret, caret);
    }

    /**
     * Ctrl+L: selects the caret's whole line with its line break; a selection of whole lines grows by the
     * next line, any other selection grows to whole lines.
     */
    public static Indenter.TabEdit selectLine(String text, int selStart, int selEnd) {
        int lo = Math.min(selStart, selEnd);
        int hi = Math.max(selStart, selEnd);
        int start = lineStart(text, lo);
        int end;
        if (hi > lo && lo == start && isLineStart(text, hi)) {
            end = hi >= text.length() ? hi : afterLine(text, hi); // whole lines already: one more
        } else if (hi > lo && isLineStart(text, hi)) {
            end = hi;
        } else {
            end = afterLine(text, hi);
        }
        return new Indenter.TabEdit(start, start, "", start, end);
    }

    /**
     * Ctrl+] / Ctrl+[: indents or outdents every line the selection touches, through the buffer's own
     * Tab / Shift-Tab ({@code tab}, given the whole lines). A bare caret keeps its place in the text.
     * Null when {@code tab} declines.
     */
    public static Indenter.TabEdit indentLines(
            String text, int selStart, int selEnd, boolean outdent, TabFunction tab) {
        int lo = Math.min(selStart, selEnd);
        int hi = Math.max(selStart, selEnd);
        if (hi > lo) {
            return tab.edit(lo, hi, outdent);
        }
        int start = lineStart(text, lo);
        int end = lineEnd(text, lo);
        Indenter.TabEdit edit = tab.edit(start, Math.max(end, start), outdent);
        if (edit == null) {
            return null;
        }
        // Inside the rewritten stretch the caret keeps its distance from the stretch's end: the indent changes
        // at the front of the line, the text the caret is in does not.
        int newEnd = edit.from() + edit.replacement().length();
        int caret = lo < edit.from()
                ? lo
                : lo >= edit.to() ? lo + (newEnd - edit.to()) : Math.max(edit.from(), newEnd - (edit.to() - lo));
        return new Indenter.TabEdit(edit.from(), edit.to(), edit.replacement(), caret, caret);
    }

    /** The buffer's Tab for a selection: {@code (selStart, selEnd, shift)}. */
    @FunctionalInterface
    public interface TabFunction {
        Indenter.TabEdit edit(int selStart, int selEnd, boolean shift);
    }

    /** An offset on the selection's last line: its end, or just before it when it stops at a line start. */
    private static int lastLineOffset(int selStart, int selEnd, String text) {
        int lo = Math.min(selStart, selEnd);
        int hi = Math.max(selStart, selEnd);
        return hi > lo && isLineStart(text, hi) ? hi - 1 : hi;
    }

    private static boolean isLineStart(String text, int pos) {
        return pos == 0 || (pos <= text.length() && text.charAt(pos - 1) == '\n');
    }

    /** Past the line break that ends {@code pos}'s line, or the end of the text on the last line. */
    private static int afterLine(String text, int pos) {
        int end = lineEnd(text, pos);
        return end < text.length() ? end + 1 : end;
    }

    static int lineStart(String text, int pos) {
        int p = Math.clamp(pos, 0, text.length());
        return p == 0 ? 0 : text.lastIndexOf('\n', p - 1) + 1;
    }

    static int lineEnd(String text, int pos) {
        int newline = text.indexOf('\n', Math.clamp(pos, 0, text.length()));
        return newline < 0 ? text.length() : newline;
    }

    private static String leadingWhitespace(String text, int lineStart) {
        int i = lineStart;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return text.substring(lineStart, i);
    }
}
