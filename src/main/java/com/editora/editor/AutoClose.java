package com.editora.editor;

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
        if ((isCloser(typed) || isQuote(typed)) && next == typed) {
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

    /** Whether typing a quote should auto-insert its pair (avoid contractions / mid-word / closings). */
    private static boolean shouldPairQuote(char prev, char next, char quote) {
        if (Character.isLetterOrDigit(prev) || prev == quote) {
            return false; // e.g. don't, identifier", or closing an existing string
        }
        return !Character.isLetterOrDigit(next); // not right before a word
    }

    /** True when the caret sits inside a freshly auto-inserted empty pair, e.g. {@code (|)} or {@code "|"}. */
    public static boolean isEmptyPair(char prev, char next) {
        return (isOpener(prev) && next == closerFor(prev)) || (isQuote(prev) && next == prev);
    }
}
