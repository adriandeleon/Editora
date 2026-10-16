package com.editora.run;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Pure tokenizers for a user-typed argument string (unit-tested).
 *
 * <p>{@link #tokenize} is the lenient splitter used for configured <em>tool command lines</em> (a language
 * server, Mermaid, a build tool): whitespace-separated, single or double quotes group a token, quotes are
 * stripped and there is no escape processing, so a Windows path such as {@code C:\tools\x.exe} survives.
 *
 * <p>{@link #parse} reads what a person types as a program's arguments or environment, and follows the
 * shell's rules closely enough that what they type is what the program gets: a backslash outside quotes
 * escapes a following space, quote or backslash ({@code tail\ x} is one argument, {@code O\'Brien} keeps
 * its apostrophe); inside double quotes {@code \"}, {@code \\}, {@code \$} and {@code \`} are escapes and
 * any other backslash is literal; single quotes take everything literally. An unclosed quote is an error
 * ({@link UnclosedQuoteException}) rather than silently swallowing the rest of the line into one argument.
 * With a variable lookup it also expands {@code $NAME} and {@code ${NAME}} outside single quotes. Either way
 * this shapes a {@code ProcessBuilder} argv, never a shell command line.
 */
public final class ProgramArgs {

    private ProgramArgs() {}

    /** An argument string with a quote that is never closed; {@link #quote()} is the quote character. */
    public static final class UnclosedQuoteException extends IllegalArgumentException {
        private final char quote;

        UnclosedQuoteException(char quote) {
            super("unclosed " + quote + " quote");
            this.quote = quote;
        }

        public char quote() {
            return quote;
        }
    }

    /**
     * Splits typed program arguments with shell-like quoting and escapes (see the class comment), without
     * variable expansion.
     *
     * @throws UnclosedQuoteException when a quote is opened and never closed
     */
    public static List<String> parse(String text) {
        return parse(text, null);
    }

    /**
     * As {@link #parse(String)}, expanding {@code $NAME} / {@code ${NAME}} outside single quotes through
     * {@code lookup} (an unknown name expands to nothing, as in a shell). {@code null} turns expansion off.
     * A {@code $} not followed by a name is literal.
     */
    public static List<String> parse(String text, Function<String, String> lookup) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean inToken = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quote == '\'') {
                if (c == '\'') {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (quote == '"') {
                if (c == '"') {
                    quote = 0;
                } else if (c == '\\' && i + 1 < n && "\"\\$`".indexOf(text.charAt(i + 1)) >= 0) {
                    cur.append(text.charAt(++i));
                } else if (c == '$' && lookup != null) {
                    i = expand(text, i, lookup, cur);
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (c == '\\' && i + 1 < n && isEscapable(text.charAt(i + 1))) {
                cur.append(text.charAt(++i));
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else if (c == '$' && lookup != null) {
                i = expand(text, i, lookup, cur);
                inToken = true;
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (quote != 0) {
            throw new UnclosedQuoteException(quote);
        }
        if (inToken) {
            out.add(cur.toString());
        }
        return out;
    }

    /** Outside quotes a backslash escapes only what would otherwise split or quote: a Windows path's
     *  {@code C:\dir\file} keeps its separators. */
    private static boolean isEscapable(char next) {
        return Character.isWhitespace(next) || next == '"' || next == '\'' || next == '\\' || next == '$';
    }

    /** Appends the value of the variable starting at {@code text[dollar]}; returns the index of its last char. */
    private static int expand(String text, int dollar, Function<String, String> lookup, StringBuilder cur) {
        int n = text.length();
        int start = dollar + 1;
        if (start < n && text.charAt(start) == '{') {
            int close = text.indexOf('}', start + 1);
            String name = close < 0 ? "" : text.substring(start + 1, close);
            if (close < 0 || !isName(name)) {
                cur.append('$');
                return dollar;
            }
            String value = lookup.apply(name);
            cur.append(value == null ? "" : value);
            return close;
        }
        int end = start;
        while (end < n && isNameChar(text.charAt(end))) {
            end++;
        }
        String name = text.substring(start, end);
        if (!isName(name)) {
            cur.append('$');
            return dollar;
        }
        String value = lookup.apply(name);
        cur.append(value == null ? "" : value);
        return end - 1;
    }

    private static boolean isNameChar(char c) {
        return c == '_' || (c < 128 && Character.isLetterOrDigit(c));
    }

    private static boolean isName(String name) {
        if (name.isEmpty() || Character.isDigit(name.charAt(0))) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (!isNameChar(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Splits a configured command line into argv tokens (lenient, no escapes); blank/null yields an empty list. */
    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean inToken = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            out.add(cur.toString());
        }
        return out;
    }
}
