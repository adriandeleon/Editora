package com.editora.markdown;

/**
 * Words and reading time of a piece of prose. Pure, one pass, no allocation.
 *
 * <p>A word is a run of letters and digits that holds at least one of them; an apostrophe or a hyphen
 * between two such characters stays inside the word ("don't", "well-known"). Markup characters are not
 * words, so a Markdown source counts close to what its rendered text would — a {@code #}, {@code **} or
 * {@code |} adds nothing, though a URL written out counts as the few words it is made of.
 */
public final class WordCount {

    /** The reading speed the time is estimated at, in words per minute. */
    public static final int WORDS_PER_MINUTE = 200;

    private WordCount() {}

    public static int words(CharSequence text) {
        return text == null ? 0 : words(text, 0, text.length());
    }

    /** The words of {@code text[start, end)}. */
    public static int words(CharSequence text, int start, int end) {
        int count = 0;
        boolean inWord = false;
        int from = Math.max(0, start);
        int to = Math.min(text.length(), end);
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            boolean wordChar = Character.isLetterOrDigit(c) || Character.isSurrogate(c);
            if (!wordChar && inWord && (c == '\'' || c == '’' || c == '-') && i + 1 < to) {
                char next = text.charAt(i + 1);
                wordChar = Character.isLetterOrDigit(next);
            }
            if (wordChar && !inWord) {
                count++;
            }
            inWord = wordChar;
        }
        return count;
    }

    /** Whole minutes to read {@code words} words, rounded up; 0 only for no words. */
    public static int readingMinutes(int words) {
        return words <= 0 ? 0 : (words + WORDS_PER_MINUTE - 1) / WORDS_PER_MINUTE;
    }
}
