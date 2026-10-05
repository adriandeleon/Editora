package com.editora.editor;

/**
 * Line arithmetic for a narrowed buffer. While narrowed the text area holds only the accessible region, so
 * its paragraph indexes are region-relative, whereas anything that names a place in the <em>file</em> — a
 * Find in Files hit, a Problems entry, a stack-trace link — carries a document line. Pure.
 */
public final class NarrowLines {

    private NarrowLines() {}

    /** The 0-based document line the region starts on: the line breaks before {@code regionStart}. */
    public static int firstLine(CharSequence document, int regionStart) {
        int end = Math.max(0, Math.min(regionStart, document.length()));
        int line = 0;
        for (int i = 0; i < end; i++) {
            if (document.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * The region-relative line for 0-based {@code documentLine}, or -1 when that line is outside the region
     * (the caller widens, as Emacs does for a jump from outside, rather than landing on unrelated text).
     */
    public static int toRegionLine(int regionFirstLine, int regionLineCount, int documentLine) {
        int local = documentLine - regionFirstLine;
        return local >= 0 && local < regionLineCount ? local : -1;
    }
}
