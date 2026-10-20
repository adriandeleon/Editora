package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Debug variables-tree helpers: {@link #runs} splits a debugger value string into runs coloured with the
 * editor's syntax token classes, so values read like code in the active editor theme. Adapters render
 * values language-specifically (Java {@code "text"}, Python {@code 'text'}/{@code None}, JS
 * {@code undefined}), so the lexer is a cross-language heuristic. Pure — unit-tested.
 */
final class DebugValues {

    /** Hex, integers, decimals, and scientific notation — with an optional sign. Hex first: {@code 0x7f} is not
     *  a {@code 0} followed by a word. */
    private static final Pattern NUMBER =
            Pattern.compile("[+-]?(0[xX][0-9a-fA-F]+|\\d[\\d_]*(\\.\\d+)?([eE][+-]?\\d+)?)[LlFfDd]?");

    /**
     * How many children of a variable the tree shows at a time. A {@code byte[65536]} or a 60,000-element list
     * must never become that many tree rows in one go: the rest sits behind a "show more" row.
     */
    static final int PAGE_SIZE = 100;

    /** Terminal control sequences: CSI ({@code ESC [ … letter}, colours and cursor moves) and OSC (titles). */
    private static final Pattern ANSI =
            Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)?");

    private DebugValues() {}

    /** {@code text} without terminal control sequences — the Debug console shows plain text. */
    static String stripAnsi(String text) {
        return text == null || text.indexOf('\u001B') < 0
                ? text
                : ANSI.matcher(text).replaceAll("");
    }

    /**
     * A value as one row shows it: its first non-blank line, with " … }" (or " …") for the rest. vscode-js-debug
     * describes a function by its whole source, which made a Variables row four lines tall and filled an inline
     * value with the function's body (review D9); Copy Value keeps the full text.
     */
    static String display(String value) {
        if (value == null || (value.indexOf('\n') < 0 && value.indexOf('\r') < 0)) {
            return value;
        }
        String[] lines = value.split("\\R");
        String first = "";
        for (String line : lines) {
            if (!line.isBlank()) {
                first = line.stripTrailing();
                break;
            }
        }
        String last = lines[lines.length - 1].strip();
        boolean closes = last.equals("}") || last.equals("};");
        return first + (first.endsWith("{") && closes ? " … }" : " …");
    }

    /**
     * Whether a container's indexed children are <em>requested</em> a page at a time (DAP {@code start} /
     * {@code count}). Only possible when the adapter reported how many there are; an adapter that did not is
     * asked for everything, and the tree then pages what came back.
     */
    static boolean fetchedByPage(int indexedVariables) {
        return indexedVariables > PAGE_SIZE;
    }

    /** How many more children the next "show more" reveals, with {@code shown} of {@code total} on screen. */
    static int nextPage(int shown, int total) {
        int remaining = Math.max(0, total - shown);
        // A short tail comes with the page: debugpy sends a list's first 100 items plus its own "more" group and
        // len(), which made a "Show 4 more…" row stand between the user and them.
        return remaining <= PAGE_SIZE + PAGE_SLACK ? remaining : PAGE_SIZE;
    }

    /** How many rows past a page are shown with it rather than behind a "show more" row of their own. */
    static final int PAGE_SLACK = 25;

    /**
     * The short name shown for a frame's source. Adapters also put things that are not files there — {@code
     * jdt://contents/java.base/java.lang/String.class?=project/…}, {@code <frozen importlib>} — whose last
     * path segment is a piece of a query string, so the query is dropped before the name is taken.
     */
    static String sourceName(java.nio.file.Path file) {
        if (file == null) {
            return "";
        }
        String text = file.toString();
        int query = text.indexOf('?');
        if (query > 0) {
            text = text.substring(0, query);
        }
        int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        return slash >= 0 && slash < text.length() - 1 ? text.substring(slash + 1) : text;
    }

    /** One piece of a rendered value: its text and the editor token class it is drawn in, or null for plain. */
    record Run(String text, @Nullable String token) {}

    /** At most this many runs per value: the rest of a long {@code [1, 2, 3, …]} is one plain run. */
    static final int MAX_RUNS = 64;

    /**
     * {@code value} split into runs coloured like code: the editor's own token classes ({@code .text.string},
     * {@code .text.number}, {@code .text.constant}, {@code .text.type}), so the Debug tree follows the editor
     * theme. A cross-language lexer, not a grammar — adapters render values their own way (Java
     * {@code ArrayList@12 size=3}, Python {@code [1, 'a', None]}, JS {@code {a: 1}}) — and adjacent runs of one
     * class are merged. Strings, numbers, {@code true}/{@code None}/{@code undefined} and capitalised names are
     * coloured; an object id after {@code @} is not a number.
     */
    static List<Run> runs(String value) {
        List<Run> out = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return out;
        }
        int n = value.length();
        int i = 0;
        int plainStart = 0;
        while (i < n) {
            if (out.size() >= MAX_RUNS - 2) {
                break; // room for the plain run before this point and the plain remainder
            }
            char c = value.charAt(i);
            int end = -1;
            String token = null;
            if (c == '"' || c == '\'') {
                end = stringEnd(value, i);
                token = "string";
            } else if (c == '@') {
                end = i + 1; // an object id ("App@8", "Foo@1f2a") is neither a number nor a word
                while (end < n && Character.isLetterOrDigit(value.charAt(end))) {
                    end++;
                }
            } else if (isWordStart(c) && (i == 0 || !isWordPart(value.charAt(i - 1)))) {
                end = i + 1;
                while (end < n && (isWordPart(value.charAt(end)) || qualifiedDot(value, end))) {
                    end++;
                }
                token = wordToken(value.substring(i, end));
            } else if ((Character.isDigit(c)
                            || ((c == '-' || c == '+') && i + 1 < n && Character.isDigit(value.charAt(i + 1))))
                    && (i == 0 || !isWordPart(value.charAt(i - 1)))) {
                Matcher m = NUMBER.matcher(value).region(i, n);
                if (m.lookingAt() && (m.end() == n || !isWordPart(value.charAt(m.end())))) {
                    end = m.end();
                    token = "number";
                }
            }
            if (end < 0) {
                i++;
                continue;
            }
            if (token != null) {
                add(out, value.substring(plainStart, i), null);
                add(out, value.substring(i, end), token);
                plainStart = end;
            }
            i = end;
        }
        add(out, value.substring(plainStart), null);
        return out;
    }

    /** Words drawn as constants: the literal values of Java, Python, JavaScript, Lua/Ruby. */
    private static final java.util.Set<String> CONSTANTS =
            java.util.Set.of("true", "false", "True", "False", "null", "None", "undefined", "nil", "NaN", "Infinity");

    private static @Nullable String wordToken(String word) {
        if (CONSTANTS.contains(word)) {
            return "constant";
        }
        String last = word.substring(word.lastIndexOf('.') + 1);
        if (!last.isEmpty() && Character.isUpperCase(last.charAt(0)) && !last.equals(last.toUpperCase(Locale.ROOT))) {
            return "type"; // ArrayList, java.util.HashMap, Foo — not an ALL_CAPS constant name
        }
        return null;
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** A dot inside a qualified name ({@code java.util.List}), not a sentence's full stop or a decimal point. */
    private static boolean qualifiedDot(String s, int i) {
        return s.charAt(i) == '.' && i + 1 < s.length() && isWordStart(s.charAt(i + 1));
    }

    /** The end (exclusive) of the quoted string starting at {@code start}: past its closing quote, or the end. */
    private static int stringEnd(String s, int start) {
        char quote = s.charAt(start);
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i + 1;
            }
        }
        return s.length();
    }

    private static void add(List<Run> out, String text, @Nullable String token) {
        if (text.isEmpty()) {
            return;
        }
        int last = out.size() - 1;
        if (last >= 0 && Objects.equals(out.get(last).token(), token)) {
            out.set(last, new Run(out.get(last).text() + text, token));
        } else {
            out.add(new Run(text, token));
        }
    }
}
