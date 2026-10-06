package com.editora.editops;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which span of a line a double-click selects.
 *
 * <p>The base rule is the editor component's own: the first run of letters, digits and underscores that ends
 * after the clicked column. On top of it, three kinds of punctuation sit <em>inside</em> a word rather than
 * between two, and splitting there selects half of what was aimed at:
 *
 * <ul>
 *   <li>a decimal point between two numbers ({@code 3.14}) — in every language;
 *   <li>an apostrophe between two letters ({@code don't}, {@code l’été}) — in prose only, where it is not a
 *       quote character;
 *   <li>a hyphen between two word runs ({@code margin-top}) — only where the language's identifiers contain
 *       hyphens.
 * </ul>
 */
public final class DoubleClickWord {

    private static final Pattern RUN = Pattern.compile("\\w+", Pattern.UNICODE_CHARACTER_CLASS);

    private DoubleClickWord() {}

    /**
     * @param line       the paragraph text
     * @param column     the caret column the click put it at
     * @param prose      whether apostrophes are part of words (plain text, Markdown)
     * @param hyphenated whether hyphens are part of identifiers (CSS)
     * @return {@code [start, end)} columns to select, or {@code null} when no word ends after the column
     */
    public static int[] at(String line, int column, boolean prose, boolean hyphenated) {
        Matcher m = RUN.matcher(line);
        int start = -1;
        int end = -1;
        while (m.find()) {
            if (start >= 0 && m.start() == end + 1 && joins(line, start, end, prose, hyphenated)) {
                end = m.end(); // the separator belongs to the word: carry on with the same span
                continue;
            }
            if (start >= 0 && end > column) {
                break;
            }
            start = m.start();
            end = m.end();
        }
        return start >= 0 && end > column ? new int[] {start, end} : null;
    }

    /** Whether the single character at {@code end} glues the span {@code [start, end)} to the run after it. */
    private static boolean joins(String line, int start, int end, boolean prose, boolean hyphenated) {
        char separator = line.charAt(end);
        char before = line.charAt(end - 1);
        char after = line.charAt(end + 1);
        return switch (separator) {
            case '.' -> Character.isDigit(after) && Character.isDigit(line.charAt(numberStart(line, start, end)));
            case '\'', '’' -> prose && Character.isLetter(before) && Character.isLetter(after);
            case '-' -> hyphenated;
            default -> false;
        };
    }

    /** Start of the last dot-separated piece of {@code [start, end)}: {@code 1.2} then {@code .3} is still a number. */
    private static int numberStart(String line, int start, int end) {
        int dot = line.lastIndexOf('.', end - 1);
        return dot >= start ? dot + 1 : start;
    }
}
