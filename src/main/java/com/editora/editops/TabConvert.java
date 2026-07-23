package com.editora.editops;

/**
 * Emacs {@code tabify} / {@code untabify}: convert between tab and space indentation over a block of
 * text, honouring the tab width. Pure and toolkit-free (mirroring {@link LineTransforms}); the column is
 * tracked per line (it resets at each newline), so tab stops are measured from column 0 of every line —
 * which is why the caller passes a region that starts at a line boundary.
 *
 * <p><b>Character columns, not display columns.</b> Each character counts as one column, so like the
 * rectangle commands this does not account for wide (CJK) glyphs; for ASCII source — the overwhelming
 * case for tab/space conversion — it is exact.
 */
public final class TabConvert {

    private TabConvert() {}

    /** Expands every tab to the spaces that reach the next tab stop. */
    public static String untabify(String text, int tabWidth) {
        if (text == null || text.isEmpty() || tabWidth < 1 || text.indexOf('\t') < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        int col = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                sb.append(c);
                col = 0;
            } else if (c == '\t') {
                int spaces = tabWidth - (col % tabWidth);
                for (int k = 0; k < spaces; k++) {
                    sb.append(' ');
                }
                col += spaces;
            } else {
                sb.append(c);
                col++;
            }
        }
        return sb.toString();
    }

    /**
     * Converts each maximal run of whitespace to the tabs (plus trailing spaces) that reach the same
     * column. Only runs of <b>two or more</b> whitespace characters are touched — matching Emacs'
     * {@code tabify-regexp} {@code "[ \t][ \t]+"} — so a lone space or tab is left exactly as it is.
     */
    public static String tabify(String text, int tabWidth) {
        if (text == null || text.isEmpty() || tabWidth < 1) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int col = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\n') {
                sb.append(c);
                col = 0;
                i++;
                continue;
            }
            if (c != ' ' && c != '\t') {
                sb.append(c);
                col++;
                i++;
                continue;
            }
            // Consume the maximal whitespace run and compute the column it ends at.
            int startCol = col;
            int endCol = col;
            int j = i;
            while (j < n && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) {
                endCol += text.charAt(j) == '\t' ? tabWidth - (endCol % tabWidth) : 1;
                j++;
            }
            if (j - i >= 2) {
                int c2 = startCol;
                for (int stop = (startCol / tabWidth + 1) * tabWidth; stop <= endCol; stop += tabWidth) {
                    sb.append('\t');
                    c2 = stop;
                }
                while (c2 < endCol) {
                    sb.append(' ');
                    c2++;
                }
            } else {
                sb.append(text, i, j); // a lone space/tab: untouched
            }
            col = endCol;
            i = j;
        }
        return sb.toString();
    }
}
