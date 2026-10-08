package com.editora.ui;

/**
 * A selection widened to whole lines, for printing or exporting just that part of a file: the range
 * {@code [start, end)} of the text (from the start of its first line to the end of its last, without that
 * line's break) and the 1-based numbers of those two lines. Pure.
 */
record LineSelection(int start, int end, int firstLine, int lastLine) {

    /**
     * The whole lines touched by the selection {@code [selStart, selEnd)} of {@code text}, or null when it
     * is empty or lies outside the text. A selection that ends at the very start of a line — the usual
     * result of selecting lines downwards — does not take that line in.
     */
    static LineSelection of(String text, int selStart, int selEnd) {
        if (text == null || selStart < 0 || selEnd > text.length() || selEnd <= selStart) {
            return null;
        }
        int last = selEnd;
        if (text.charAt(last - 1) == '\n') {
            last--; // the caret sits on the next line without selecting anything of it
        }
        last = Math.max(last, selStart);
        int start = text.lastIndexOf('\n', selStart - 1) + 1;
        int end = text.indexOf('\n', last);
        if (end < 0) {
            end = text.length();
        }
        if (end > start && text.charAt(end - 1) == '\r') {
            end--;
        }
        int firstLine = 1;
        for (int i = text.indexOf('\n'); i >= 0 && i < start; i = text.indexOf('\n', i + 1)) {
            firstLine++;
        }
        int lastLine = firstLine;
        for (int i = text.indexOf('\n', start); i >= 0 && i < end; i = text.indexOf('\n', i + 1)) {
            lastLine++;
        }
        return new LineSelection(start, end, firstLine, lastLine);
    }
}
