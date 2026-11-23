package com.editora.run;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Deno's test at the caret, for Debug Test at Caret: the outermost {@code Deno.test(…)} — or {@code describe},
 * {@code it} or {@code test} of {@code @std/testing/bdd} and {@code node:test} — whose call spans the caret's line.
 * {@code deno test --filter} matches top-level tests alone (a {@code t.step} or a nested {@code it} runs inside its
 * test), so the outermost one is what can be named. Pure.
 */
public final class DenoTests {

    private DenoTests() {}

    /** A test call: {@code Deno.test(}, {@code describe.only(}, {@code it(}… */
    private static final Pattern CALL =
            Pattern.compile("(?<![\\w$.])(?:Deno\\.test|describe|it|test)(?:\\.(?:only|ignore|skip))?\\s*\\(");

    /** {@code name: "…"} in a test's options object. */
    private static final Pattern NAME_FIELD = Pattern.compile("\\bname\\s*:\\s*(['\"`])((?:\\\\.|(?!\\1).)*)\\1");

    /** {@code function named(} as a test's only argument: Deno names the test after the function. */
    private static final Pattern FUNCTION_NAME = Pattern.compile("\\s*(?:async\\s+)?function\\s*\\*?\\s*([\\w$]+)");

    /**
     * The name of the outermost test whose call spans 0-based line {@code caret} of {@code text}; null when the caret
     * is in none, or that test's name is not a plain literal (every test of the file then runs).
     */
    public static @Nullable String at(String text, int caret) {
        Matcher m = CALL.matcher(text);
        int from = 0;
        while (m.find(from)) {
            int open = m.end() - 1;
            int close = closing(text, open);
            int end = close < 0 ? text.length() : close;
            if (inComment(text, m.start())) {
                from = m.end();
                continue;
            }
            if (lineOf(text, m.start()) <= caret && caret <= lineOf(text, end)) {
                return nameOf(text.substring(open + 1, end));
            }
            from = close < 0 ? m.end() : close + 1; // what is inside a test that does not span the caret is skipped
        }
        return null;
    }

    /** The test's name from its call's arguments {@code args}: a string, an options object's name, a function's. */
    static @Nullable String nameOf(String args) {
        String a = args.stripLeading();
        if (a.isEmpty()) {
            return null;
        }
        char q = a.charAt(0);
        if (q == '\'' || q == '"' || q == '`') {
            int end = stringEnd(a, 0);
            if (end < 0) {
                return null;
            }
            String body = a.substring(1, end);
            if (q == '`' && body.contains("${")) {
                return null; // computed: no literal name to filter by
            }
            return unescape(body);
        }
        if (q == '{') {
            int close = closing(a, 0);
            Matcher n = NAME_FIELD.matcher(close < 0 ? a : a.substring(0, close));
            return n.find() && !(n.group(1).equals("`") && n.group(2).contains("${")) ? unescape(n.group(2)) : null;
        }
        Matcher f = FUNCTION_NAME.matcher(a);
        return f.lookingAt() ? f.group(1) : null;
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                out.append(
                        switch (n) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            default -> n;
                        });
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The index of the bracket that closes the one at {@code open}, skipping strings, template literals and
     * comments; -1 when there is none.
     */
    static int closing(String text, int open) {
        int depth = 0;
        int n = text.length();
        for (int i = open; i < n; i++) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                int eol = text.indexOf('\n', i);
                i = eol < 0 ? n : eol;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 1;
            } else if (c == '\'' || c == '"' || c == '`') {
                int end = stringEnd(text, i);
                i = end < 0 ? n : end;
            } else if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The index of the quote that ends the string opening at {@code i}; -1 when it never ends. */
    private static int stringEnd(String text, int i) {
        char quote = text.charAt(i);
        int n = text.length();
        for (int j = i + 1; j < n; j++) {
            char c = text.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == quote) {
                return j;
            } else if (c == '\n' && quote != '`') {
                return j; // an unclosed string ends with its line
            } else if (quote == '`' && c == '$' && j + 1 < n && text.charAt(j + 1) == '{') {
                int close = closing(text, j + 1);
                if (close < 0) {
                    return -1;
                }
                j = close;
            }
        }
        return -1;
    }

    /** Whether {@code index} is on a line's {@code //} comment (a test commented out). */
    private static boolean inComment(String text, int index) {
        int lineStart = text.lastIndexOf('\n', index - 1) + 1;
        String before = text.substring(lineStart, index);
        return before.contains("//") || before.stripLeading().startsWith("*");
    }

    private static int lineOf(String text, int index) {
        int line = 0;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
