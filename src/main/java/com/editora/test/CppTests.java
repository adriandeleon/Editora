package com.editora.test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The tests of a C or C++ test file, for its ▶ glyphs, Run Test at Caret and Debug Test at Caret: which framework the
 * file uses (by what it includes: GoogleTest, Catch2 3 or 2, doctest), each test's line and name, and the arguments
 * that run only some of them — {@code --gtest_filter}, a Catch2 test spec, doctest's {@code --test-case}. Read from
 * the text, as a regular expression reads it: a test made by a macro of the project's own is not seen. Pure.
 */
public final class CppTests {

    private CppTests() {}

    /** A test of the file: its 0-based {@code line}, its {@code name} as its runner reports it, its {@code filter}. */
    public record Test(int line, String name, String filter) {}

    private static final Pattern GTEST_INCLUDE = Pattern.compile("(?m)^\\s*#\\s*include\\s*[<\"](gtest|gmock)/");
    private static final Pattern CATCH3_INCLUDE = Pattern.compile("(?m)^\\s*#\\s*include\\s*[<\"]catch2/catch_");
    private static final Pattern CATCH2_INCLUDE =
            Pattern.compile("(?m)^\\s*#\\s*include\\s*[<\"](catch2/)?catch(_amalgamated)?\\.h(pp)?[>\"]");
    private static final Pattern DOCTEST_INCLUDE =
            Pattern.compile("(?m)^\\s*#\\s*include\\s*[<\"](doctest/)?doctest(\\.h|\\.hpp)?[>\"]");

    private static final Pattern GTEST =
            Pattern.compile("^\\s*(TEST|TEST_F|TEST_P|TYPED_TEST|TYPED_TEST_P)\\s*\\(\\s*(\\w+)\\s*,\\s*(\\w+)\\s*\\)");

    /** A Catch2 or doctest test macro, up to its opening parenthesis; the name is the first string after it. */
    private static final Pattern CASE = Pattern.compile(
            "^\\s*((?:DOCTEST_)?(?:TEST_CASE|TEST_CASE_METHOD|TEST_CASE_FIXTURE|TEST_CASE_TEMPLATE|SCENARIO"
                    + "|SCENARIO_METHOD|TEMPLATE_TEST_CASE|TEMPLATE_TEST_CASE_SIG|TEMPLATE_PRODUCT_TEST_CASE"
                    + "|TEMPLATE_LIST_TEST_CASE|TEMPLATE_TEST_CASE_METHOD|TEMPLATE_LIST_TEST_CASE_METHOD))\\s*\\(");

    /** The framework of a C or C++ file's {@code text}, from what it includes; null for none of them. */
    public static @Nullable ToolTestKind framework(String text) {
        if (GTEST_INCLUDE.matcher(text).find()) {
            return ToolTestKind.GTEST;
        }
        if (CATCH3_INCLUDE.matcher(text).find()) {
            return ToolTestKind.CATCH2;
        }
        if (CATCH2_INCLUDE.matcher(text).find()) {
            return ToolTestKind.CATCH2_V2;
        }
        if (DOCTEST_INCLUDE.matcher(text).find()) {
            return ToolTestKind.DOCTEST;
        }
        return null;
    }

    /** The tests of {@code text} (a file of {@code kind}), in order. */
    public static List<Test> scan(ToolTestKind kind, String text) {
        List<Test> out = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            Test t = kind == ToolTestKind.GTEST ? gtest(lines[i], i) : namedCase(kind, lines, i);
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    /** The test {@code caret} (0-based) is in — on its line or in its body; null for none. */
    public static @Nullable Test at(ToolTestKind kind, String text, int caret) {
        List<Test> tests = scan(kind, text);
        String[] lines = text.split("\\R", -1);
        for (int i = tests.size() - 1; i >= 0; i--) {
            Test t = tests.get(i);
            if (t.line() <= caret) {
                return caret <= bodyEnd(lines, t.line()) ? t : null;
            }
        }
        return null;
    }

    private static @Nullable Test gtest(String line, int index) {
        Matcher m = GTEST.matcher(line);
        if (!m.find()) {
            return null;
        }
        String suite = m.group(2);
        String test = m.group(3);
        String filter = switch (m.group(1)) {
            case "TEST_P" -> "*/" + suite + "." + test + "/*"; // Prefix/Suite.Test/N
            case "TYPED_TEST" -> suite + "/*." + test; // Suite/N.Test
            case "TYPED_TEST_P" -> "*/" + suite + "/*." + test;
            default -> suite + "." + test;
        };
        return new Test(index, suite + "." + test, filter);
    }

    private static @Nullable Test namedCase(ToolTestKind kind, String[] lines, int index) {
        Matcher m = CASE.matcher(lines[index]);
        if (!m.find()) {
            return null;
        }
        String macro = m.group(1).replace("DOCTEST_", "");
        // The name: the first string literal after the parenthesis (a fixture class may come first), on this line or
        // the next few.
        StringBuilder rest = new StringBuilder(lines[index].substring(m.end()));
        for (int j = index + 1; j < lines.length && j <= index + 3; j++) {
            rest.append('\n').append(lines[j]);
        }
        String name = firstString(rest.toString());
        if (name == null) {
            return null;
        }
        boolean template = macro.startsWith("TEMPLATE") || macro.equals("TEST_CASE_TEMPLATE");
        boolean scenario = macro.startsWith("SCENARIO");
        boolean doctest = kind == ToolTestKind.DOCTEST;
        String reported = scenario ? (doctest ? "  Scenario: " : "Scenario: ") + name : name;
        String filter;
        if (doctest) {
            filter = reported.replace(",", "\\,") + (template ? "<*>" : "");
        } else {
            filter = catchEscape(reported) + (template ? " - *" : "");
        }
        return new Test(index, reported, filter);
    }

    /** A Catch2 test spec matching {@code name} literally. */
    public static String catchEscape(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if ("\\,[]*~\"".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** The value of the first C string literal in {@code s} (its escapes read), or null for none. */
    static @Nullable String firstString(String s) {
        int q = s.indexOf('"');
        if (q < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = q + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(
                        switch (n) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            default -> n;
                        });
            } else if (c == '\n') {
                return null;
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    /**
     * The 0-based line a test's body ends on: where the braces opened after its line close again (strings, characters
     * and comments skipped); the last line when they never do.
     */
    static int bodyEnd(String[] lines, int start) {
        int depth = 0;
        boolean opened = false;
        boolean block = false;
        for (int i = start; i < lines.length; i++) {
            String l = lines[i];
            for (int k = 0; k < l.length(); k++) {
                char c = l.charAt(k);
                if (block) {
                    if (c == '*' && k + 1 < l.length() && l.charAt(k + 1) == '/') {
                        block = false;
                        k++;
                    }
                    continue;
                }
                if (c == '/' && k + 1 < l.length() && l.charAt(k + 1) == '/') {
                    break;
                }
                if (c == '/' && k + 1 < l.length() && l.charAt(k + 1) == '*') {
                    block = true;
                    k++;
                } else if (c == '"' || c == '\'') {
                    for (k++; k < l.length() && l.charAt(k) != c; k++) {
                        if (l.charAt(k) == '\\') {
                            k++;
                        }
                    }
                } else if (c == '{') {
                    depth++;
                    opened = true;
                } else if (c == '}') {
                    depth--;
                    if (opened && depth == 0) {
                        return i;
                    }
                }
            }
        }
        return lines.length - 1;
    }

    /** The arguments that run only {@code filters} (each a {@link Test#filter}) under {@code kind}. */
    public static List<String> filterArgs(ToolTestKind kind, List<String> filters) {
        if (filters.isEmpty()) {
            return List.of();
        }
        return switch (kind) {
            case GTEST -> List.of("--gtest_filter=" + String.join(":", filters));
            case CATCH2, CATCH2_V2 -> List.of(String.join(",", filters));
            case DOCTEST -> List.of("--test-case=" + String.join(",", filters));
            default -> List.of();
        };
    }
}
