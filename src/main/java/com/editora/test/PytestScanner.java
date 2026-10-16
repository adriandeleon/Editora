package com.editora.test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the tests pytest would collect in one Python file, by text — no interpreter, so the gutter can mark
 * them as the file is typed. Pure.
 *
 * <p>Collected, as pytest does by default: module-level {@code def test*} / {@code async def test*}; classes
 * named {@code Test*}, or deriving from {@code TestCase} (unittest, collected by pytest whatever the name);
 * and their {@code test*} methods. Strings, docstrings and comments are blanked first, so a {@code def test_x}
 * inside a docstring is not a test. Nested classes and parametrize ids are out of scope: the enclosing test
 * is what a gutter ▶ runs.
 */
public final class PytestScanner {

    private PytestScanner() {}

    /**
     * A test: {@code className} is null for a module-level function, {@code functionName} null for a class
     * as a whole. {@code line} is 0-based (the {@code def}/{@code class} line, after any decorators);
     * {@code endLine} is the last line of its block.
     */
    public record TestTarget(int line, int endLine, String className, String functionName) {
        /** The node id after the file: {@code Class::test_x}, {@code test_x} or {@code Class}. */
        public String nodeSuffix() {
            if (className == null) {
                return functionName;
            }
            return functionName == null ? className : className + "::" + functionName;
        }

        /** A short label for menus and the gutter tooltip. */
        public String displayName() {
            return className == null || functionName == null ? nodeSuffix() : className + "." + functionName;
        }
    }

    private static final Pattern DEF = Pattern.compile("^([ \\t]*)(?:async[ \\t]+)?def[ \\t]+(\\w+)");
    private static final Pattern CLASS = Pattern.compile("^([ \\t]*)class[ \\t]+(\\w+)[ \\t]*(\\(([^)]*)\\))?");

    /** Whether pytest's default {@code python_files} pattern matches: {@code test_*.py} or {@code *_test.py}. */
    public static boolean isTestFile(String fileName) {
        if (fileName == null || !fileName.endsWith(".py")) {
            return false;
        }
        return fileName.startsWith("test_") || fileName.endsWith("_test.py");
    }

    /** Every collected test in {@code source}, in file order (a class before its methods). */
    public static List<TestTarget> scan(String source) {
        String[] lines = blank(source == null ? "" : source).split("\n", -1);
        List<TestTarget> out = new ArrayList<>();
        String currentClass = null;
        int classIndent = -1;
        int classIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            int indent = indentOf(line);
            if (currentClass != null && indent <= classIndent) {
                currentClass = null;
                classIndent = -1;
            }
            Matcher c = CLASS.matcher(line);
            if (c.find()) {
                if (indent == 0) {
                    String name = c.group(2);
                    String bases = c.group(4) == null ? "" : c.group(4);
                    if (name.startsWith("Test") || bases.matches(".*\\bTestCase\\b.*")) {
                        currentClass = name;
                        classIndent = 0;
                        classIndex = out.size();
                        out.add(new TestTarget(i, endOf(lines, i, 0), name, null));
                    }
                }
                continue;
            }
            Matcher d = DEF.matcher(line);
            if (!d.find() || !d.group(2).startsWith("test")) {
                continue;
            }
            if (indent == 0) {
                out.add(new TestTarget(i, endOf(lines, i, 0), null, d.group(2)));
            } else if (currentClass != null && indent > classIndent && isMethodLevel(lines, classIndex, out, indent)) {
                out.add(new TestTarget(i, endOf(lines, i, indent), currentClass, d.group(2)));
            }
        }
        return List.copyOf(out);
    }

    /** A method sits at the class body's first indentation level, not inside another method. */
    private static boolean isMethodLevel(String[] lines, int classIndex, List<TestTarget> out, int indent) {
        int classLine = out.get(classIndex).line();
        for (int j = classLine + 1; j < lines.length; j++) {
            if (!lines[j].isBlank()) {
                return indentOf(lines[j]) == indent;
            }
        }
        return false;
    }

    /** The test whose block holds {@code line} (0-based): the innermost one, or null. */
    public static TestTarget atLine(String source, int line) {
        TestTarget best = null;
        for (TestTarget t : scan(source)) {
            if (line >= t.line() && line <= t.endLine()) {
                if (best == null || t.line() >= best.line()) {
                    best = t;
                }
            }
        }
        return best;
    }

    /**
     * The pytest node id for {@code target} in a file at {@code relativePath} (from the folder pytest runs
     * in): {@code tests/test_app.py::TestX::test_y}, with forward slashes on every OS.
     */
    public static String nodeId(String relativePath, TestTarget target) {
        String path = relativePath.replace('\\', '/');
        return target == null ? path : path + "::" + target.nodeSuffix();
    }

    /** The last line of the block opened on {@code start}: the line before the next one indented ≤ {@code indent}. */
    static int endOf(String[] lines, int start, int indent) {
        int last = start;
        for (int j = start + 1; j < lines.length; j++) {
            if (lines[j].isBlank()) {
                continue;
            }
            if (indentOf(lines[j]) <= indent) {
                break;
            }
            last = j;
        }
        return last;
    }

    static int indentOf(String line) {
        int col = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == ' ') {
                col++;
            } else if (ch == '\t') {
                col += 8 - (col % 8);
            } else {
                break;
            }
        }
        return col;
    }

    /**
     * Replaces the contents of strings (incl. triple-quoted, with any prefix) and comments with spaces,
     * keeping every newline, so line numbers and indentation survive. A string's own quotes stay.
     */
    static String blank(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '#') {
                while (i < n && s.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                boolean triple = i + 2 < n && s.charAt(i + 1) == ch && s.charAt(i + 2) == ch;
                int qlen = triple ? 3 : 1;
                out.append(s, i, i + qlen);
                i += qlen;
                while (i < n) {
                    char c = s.charAt(i);
                    if (c == '\\' && i + 1 < n) {
                        out.append(s.charAt(i + 1) == '\n' ? " \n" : "  ");
                        i += 2;
                        continue;
                    }
                    if (triple ? (c == ch && i + 2 < n && s.charAt(i + 1) == ch && s.charAt(i + 2) == ch) : c == ch) {
                        out.append(s, i, i + qlen);
                        i += qlen;
                        break;
                    }
                    if (c == '\n' && !triple) {
                        break; // an unterminated one-line string ends at the line
                    }
                    out.append(c == '\n' ? '\n' : ' ');
                    i++;
                }
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }
}
