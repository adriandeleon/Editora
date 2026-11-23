package com.editora.dap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Dart's tests at the caret, for Debug Test at Caret: {@code package:test}'s {@code test(…)} and {@code group(…)},
 * and {@code flutter_test}'s {@code testWidgets(…)}. A test's full name is its groups' names and its own, joined by
 * spaces, as {@code dart test --name} matches it. Pure.
 */
public final class DartTests {

    private DartTests() {}

    /**
     * A test or group at the caret.
     *
     * @param name its full name: its groups' names and its own, joined by spaces
     * @param group whether it is a group (every test in it runs)
     */
    public record Target(String name, boolean group) {}

    /** {@code test('name'}, {@code group("name"}, {@code testWidgets('name'}: the call, its quote, its name. */
    private static final Pattern CALL =
            Pattern.compile("\\b(group|test|testWidgets)\\s*\\(\\s*r?(['\"])((?:\\\\.|(?!\\2).)*)\\2");

    /** Whether {@code file} is a Dart test file: {@code *_test.dart}, as {@code dart test} finds them. */
    public static boolean isTestFile(@Nullable Path file) {
        Path leaf = file == null ? null : file.getFileName();
        return leaf != null && leaf.toString().endsWith("_test.dart");
    }

    /** A call found in the text, with the lines it spans (its opening line to its closing parenthesis). */
    private record Call(String name, boolean group, int start, int end) {}

    /**
     * The innermost test or group whose call spans 0-based line {@code caret} of {@code text}, named with the groups
     * around it; null when the caret is in none (the file's {@code main} alone runs every test).
     */
    public static @Nullable Target at(String text, int caret) {
        List<Call> calls = calls(text);
        Call innermost = null;
        List<String> names = new ArrayList<>();
        for (Call c : calls) {
            if (c.start() <= caret && caret <= c.end()) {
                names.add(c.name()); // calls are in order of their start: an outer group comes first
                innermost = c;
            }
        }
        return innermost == null ? null : new Target(String.join(" ", names), innermost.group());
    }

    /** Every test and group call of {@code text}, in order of their start, with the lines each spans. */
    private static List<Call> calls(String text) {
        List<Call> out = new ArrayList<>();
        Matcher m = CALL.matcher(text);
        while (m.find()) {
            int open = text.indexOf('(', m.start());
            int close = closing(text, open);
            out.add(new Call(
                    m.group(3).replace("\\'", "'").replace("\\\"", "\""),
                    "group".equals(m.group(1)),
                    lineOf(text, m.start()),
                    lineOf(text, close < 0 ? text.length() : close)));
        }
        return out;
    }

    /** The index of the parenthesis that closes the one at {@code open}, skipping strings and comments; -1 if none. */
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
            } else if (c == '\'' || c == '"') {
                boolean triple = i + 2 < n && text.charAt(i + 1) == c && text.charAt(i + 2) == c;
                boolean raw = i > 0 && text.charAt(i - 1) == 'r';
                i = stringEnd(text, i, c, triple, raw);
            } else if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return c == ')' ? i : -1;
                }
            }
        }
        return -1;
    }

    /** The index of the last character of the string literal that opens at {@code i}. */
    private static int stringEnd(String text, int i, char quote, boolean triple, boolean raw) {
        int n = text.length();
        int j = i + (triple ? 3 : 1);
        while (j < n) {
            char c = text.charAt(j);
            if (c == '\\' && !raw) {
                j += 2;
                continue;
            }
            if (c == quote && (!triple || (j + 2 < n && text.charAt(j + 1) == quote && text.charAt(j + 2) == quote))) {
                return triple ? j + 2 : j;
            }
            if (c == '\n' && !triple) {
                return j; // an unclosed string ends with its line
            }
            j++;
        }
        return n;
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

    /**
     * What narrows {@code dart test} (or {@code flutter test}) to {@code target}: {@code --name} with a pattern of its
     * full name, whole for a test and followed by a space or the end for a group (its tests' names start with it).
     */
    public static List<String> toolArgs(Target target) {
        String quoted = regexLiteral(target.name());
        return List.of("--name", "^" + quoted + (target.group() ? "( |$)" : "$"));
    }

    /** {@code s} with every character a Dart regular expression gives a meaning escaped. Pure. */
    static String regexLiteral(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
