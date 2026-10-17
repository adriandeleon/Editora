package com.editora.editops;

/**
 * Pure decision logic for auto-closing brackets and quotes (no toolkit), so it is unit-tested.
 * {@link EditorBuffer} runs {@link #decide} on a typed character and performs the resulting edit.
 *
 * <ul>
 *   <li>Typing an opener {@code ( [ {} inserts the matching closer and keeps the caret between.</li>
 *   <li>Typing a quote {@code " ' `} inserts a pair, unless that would be awkward (next to a word
 *       char or another quote — e.g. an apostrophe in {@code don't}).</li>
 *   <li>Typing a closer/quote when the next char is exactly that char "types over" it (skip).</li>
 *   <li>Typing an opener/quote with a selection wraps the selection in the pair.</li>
 * </ul>
 */
public final class AutoClose {

    private AutoClose() {}

    public enum Action {
        NONE,
        INSERT_PAIR,
        /** The third quote of a Python triple quote: insert it and a closing triple quote, caret between. */
        INSERT_TRIPLE,
        SKIP_OVER,
        WRAP_SELECTION
    }

    /** What to do for a typed char, plus the closer to use (0 when not applicable). */
    public record Decision(Action action, char closer) {
        static final Decision NONE = new Decision(Action.NONE, (char) 0);
    }

    /** The closer for an opener/quote, or {@code 0} if {@code c} is not an opener or quote. */
    public static char closerFor(char c) {
        return switch (c) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            case '"' -> '"';
            case '\'' -> '\'';
            case '`' -> '`';
            default -> 0;
        };
    }

    public static boolean isOpener(char c) {
        return c == '(' || c == '[' || c == '{';
    }

    public static boolean isCloser(char c) {
        return c == ')' || c == ']' || c == '}';
    }

    public static boolean isQuote(char c) {
        return c == '"' || c == '\'' || c == '`';
    }

    /**
     * Decides the auto-close action. {@code prev}/{@code next} are the chars immediately before/after
     * the caret (0 if at a boundary); {@code hasSelection} is whether a non-empty selection exists.
     */
    public static Decision decide(char typed, char prev, char next, boolean hasSelection) {
        if (hasSelection && (isOpener(typed) || isQuote(typed))) {
            return new Decision(Action.WRAP_SELECTION, closerFor(typed));
        }
        if (!hasSelection && (isCloser(typed) || isQuote(typed)) && next == typed) {
            return new Decision(Action.SKIP_OVER, typed); // type over the existing closer/quote
        }
        if (isOpener(typed)) {
            return new Decision(Action.INSERT_PAIR, closerFor(typed));
        }
        if (isQuote(typed) && shouldPairQuote(prev, next, typed)) {
            return new Decision(Action.INSERT_PAIR, typed);
        }
        return Decision.NONE; // plain closer or anything else: normal insertion
    }

    /**
     * As {@link #decide(char, char, char, boolean)}, knowing the line before the caret and the language. In
     * Python a quote after a string prefix ({@code f'}, {@code rb"}, {@code t'} …) pairs, the third quote of
     * a triple quote closes it as well (caret between), and a backtick (not Python syntax) does not pair.
     */
    public static Decision decide(
            char typed, String lineBeforeCaret, char next, boolean hasSelection, String language) {
        char prev = lineBeforeCaret.isEmpty() ? 0 : lineBeforeCaret.charAt(lineBeforeCaret.length() - 1);
        if (!"python".equals(language) || hasSelection) {
            return decide(typed, prev, next, hasSelection);
        }
        if (typed == '`') {
            return Decision.NONE;
        }
        if ((typed == '"' || typed == '\'') && next != typed) {
            if (opensTripleQuote(lineBeforeCaret, typed)) {
                return new Decision(Action.INSERT_TRIPLE, typed);
            }
            if (Character.isLetter(prev) && isStringPrefix(lineBeforeCaret) && !Character.isLetterOrDigit(next)) {
                return new Decision(Action.INSERT_PAIR, typed);
            }
        }
        return decide(typed, prev, next, hasSelection);
    }

    /** Whether the line ends in two {@code q} that open an empty string (not the end of a longer quote run). */
    private static boolean opensTripleQuote(String before, char q) {
        int n = before.length();
        if (n < 2 || before.charAt(n - 1) != q || before.charAt(n - 2) != q) {
            return false;
        }
        if (n > 2 && before.charAt(n - 3) == q) {
            return false; // already a triple quote (or more)
        }
        // The two quotes must open a string here, not close one: an even number of q before them on the line.
        int count = 0;
        for (int i = 0; i < n - 2; i++) {
            if (before.charAt(i) == q && (i == 0 || before.charAt(i - 1) != '\\')) {
                count++;
            }
        }
        return count % 2 == 0;
    }

    /** Whether the word that ends the line is a Python string prefix ({@code r b u f t} and their pairs). */
    static boolean isStringPrefix(String before) {
        int end = before.length();
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(before.charAt(start - 1)) || before.charAt(start - 1) == '_')) {
            start--;
        }
        String word = before.substring(start, end).toLowerCase(java.util.Locale.ROOT);
        return switch (word) {
            case "r", "u", "b", "f", "t", "br", "rb", "fr", "rf", "tr", "rt" -> true;
            default -> false;
        };
    }

    /** Whether typing a quote should auto-insert its pair (avoid contractions / mid-word / closings). */
    private static boolean shouldPairQuote(char prev, char next, char quote) {
        if (Character.isLetterOrDigit(prev) || prev == quote) {
            return false; // e.g. don't, identifier", or closing an existing string
        }
        if (isCloser(prev) || prev == '.') {
            return false; // "call(x)" / "Done." — a quote here ends a string, it does not start one
        }
        return !Character.isLetterOrDigit(next); // not right before a word
    }

    /** True when the caret sits inside a freshly auto-inserted empty pair, e.g. {@code (|)} or {@code "|"}. */
    public static boolean isEmptyPair(char prev, char next) {
        return (isOpener(prev) && next == closerFor(prev)) || (isQuote(prev) && next == prev);
    }
}
