package com.editora.editor;

/**
 * The smallest single replacement that turns one document text into another: the span between their common
 * prefix and common suffix.
 *
 * <p>A command that rewrites the document from its whole text (format, lint fix-all, replace-in-files, a
 * diff hunk, an agent or plugin edit) used to replace <em>everything</em>, so each such undo step recorded the
 * old and the new document in full — 300 of them on a 1 MB file retain 600 MB. Almost every such rewrite
 * changes a small part of the text; replacing only that part keeps the step's cost proportional to the change.
 *
 * @param start offset where the texts begin to differ
 * @param end end (exclusive) of the differing span in the <em>current</em> text
 * @param replacement what the span becomes
 */
record WholeDocumentEdit(int start, int end, String replacement) {

    /**
     * The edit that makes {@code current} equal {@code next}, or null when they already are equal. The span
     * never starts or ends inside a surrogate pair or between a {@code \r} and its {@code \n}, so applying it
     * can neither create a lone surrogate nor turn one line break into two.
     */
    static WholeDocumentEdit between(String current, String next) {
        int currentLength = current.length();
        int nextLength = next.length();
        int limit = Math.min(currentLength, nextLength);
        int prefix = 0;
        while (prefix < limit && current.charAt(prefix) == next.charAt(prefix)) {
            prefix++;
        }
        if (prefix == currentLength && prefix == nextLength) {
            return null;
        }
        if (prefix > 0 && splits(current, next, prefix)) {
            prefix--;
        }
        int suffix = 0;
        int suffixLimit = limit - prefix;
        while (suffix < suffixLimit
                && current.charAt(currentLength - 1 - suffix) == next.charAt(nextLength - 1 - suffix)) {
            suffix++;
        }
        if (suffix > 0 && splits(current, next, currentLength - suffix, nextLength - suffix)) {
            suffix--;
        }
        return new WholeDocumentEdit(prefix, currentLength - suffix, next.substring(prefix, nextLength - suffix));
    }

    /** Whether a boundary at the same {@code index} of both texts separates a pair in either of them. */
    private static boolean splits(String current, String next, int index) {
        return splits(current, next, index, index);
    }

    private static boolean splits(String current, String next, int currentIndex, int nextIndex) {
        return splitsPair(current, currentIndex) || splitsPair(next, nextIndex);
    }

    /** True when {@code index} falls between a high and a low surrogate, or between {@code \r} and {@code \n}. */
    private static boolean splitsPair(String text, int index) {
        if (index <= 0 || index >= text.length()) {
            return false;
        }
        char before = text.charAt(index - 1);
        char after = text.charAt(index);
        return (Character.isHighSurrogate(before) && Character.isLowSurrogate(after))
                || (before == '\r' && after == '\n');
    }
}
