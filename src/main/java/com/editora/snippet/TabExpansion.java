package com.editora.snippet;

import java.util.function.Function;

/**
 * The decisions behind "Tab expands the trigger before the caret": which stretch of text is the trigger,
 * and whether Tab may expand a snippet here at all. Pure, so both are unit-tested without an editor.
 */
public final class TabExpansion {

    /** Longest trigger looked up ({@code [SuppressMessageAttribute]} is the longest bundled one). */
    public static final int MAX_TRIGGER = 40;

    /** Most words a trigger may have ({@code bold and italic}). */
    static final int MAX_WORDS = 3;

    /** A trigger found before the caret: where it starts in the scanned text, and what it expands to. */
    public record Match(int start, Snippet snippet) {}

    private TabExpansion() {}

    /**
     * The snippet whose trigger ends {@code before} (the text up to the caret), or null. Tried longest
     * first: a trigger of several words ({@code else if}, {@code enum class}) on the caret's line, then the
     * whole non-whitespace token ({@code #include}, {@code ?xml}, {@code [PSCustomObject]}), then the
     * identifier run ending at the caret ({@code fori} in {@code (fori}).
     */
    public static Match find(String before, Function<String, Snippet> lookup) {
        int caret = before.length();
        int limit = Math.max(0, caret - MAX_TRIGGER);
        int token = caret;
        while (token > limit && !Character.isWhitespace(before.charAt(token - 1))) {
            token--;
        }
        if (token == caret) {
            return null; // whitespace before the caret: nothing was typed to expand
        }
        // Several words: walk back over single blanks to the starts of the words before the token.
        int[] starts = new int[MAX_WORDS];
        int words = 1;
        starts[0] = token;
        int at = token;
        while (words < MAX_WORDS && at - 1 > limit && before.charAt(at - 1) == ' ') {
            int word = at - 1;
            while (word > limit && !Character.isWhitespace(before.charAt(word - 1))) {
                word--;
            }
            if (word == at - 1) {
                break; // two blanks in a row
            }
            starts[words++] = word;
            at = word;
        }
        for (int w = words - 1; w >= 1; w--) {
            Snippet s = lookup.apply(before.substring(starts[w], caret));
            if (s != null) {
                return new Match(starts[w], s);
            }
        }
        Snippet wide = lookup.apply(before.substring(token, caret));
        if (wide != null) {
            return new Match(token, wide);
        }
        int ident = caret;
        while (ident > token && isIdentifierPart(before.charAt(ident - 1))) {
            ident--;
        }
        if (ident == caret || ident == token) {
            return null;
        }
        Snippet s = lookup.apply(before.substring(ident, caret));
        return s == null ? null : new Match(ident, s);
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Whether Tab may expand a trigger found in a buffer of {@code language}.
     *
     * <ul>
     *   <li>Never in a delimited data file ({@code csv}, which is also {@code .tsv}): Tab is the field
     *       separator there.</li>
     *   <li>In a code buffer, not when the trigger sits in a comment or a string — {@code // see the main}
     *       is a sentence, not a request for a main method. Prose buffers have no such scopes.</li>
     * </ul>
     *
     * The setting that turns Tab expansion off altogether, and the rule that the bundled global snippets
     * are offered in the popup and picker only ({@link SnippetManager#byTabTrigger}), are applied by the
     * callers.
     */
    public static boolean allowed(String language, boolean prose, boolean inCommentOrString) {
        if ("csv".equals(language)) {
            return false;
        }
        return prose || !inCommentOrString;
    }
}
