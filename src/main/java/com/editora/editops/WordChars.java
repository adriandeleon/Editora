package com.editora.editops;

/**
 * What the word commands (M-f/M-b, M-d, M-t, the case commands) count as part of a word beyond letters
 * and digits: the combining marks that belong to the letter before them. Decomposed text — {@code e} plus
 * U+0301, as macOS file names and much copied text arrive — otherwise ended every accented word at the
 * accent: M-f stopped between the letter and its mark, and M-d left the mark behind to attach itself to
 * whatever came next.
 */
public final class WordChars {

    private WordChars() {}

    /** A combining mark (Unicode categories Mn, Mc, Me): it has no existence apart from its base character. */
    public static boolean isMark(char c) {
        if (c < 0x0300) {
            return false; // no combining marks below the Combining Diacritical Marks block
        }
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    /** A letter, a digit, or a mark combining with one. */
    public static boolean isLetterDigitOrMark(char c) {
        return Character.isLetterOrDigit(c) || isMark(c);
    }
}
