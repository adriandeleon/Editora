package com.editora.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds JUnit test methods + the enclosing top-level test class in Java source text, for the editor's gutter
 * ▶ Run markers. A lightweight, length-preserving blank-comments-and-literals pass + brace-depth walk — no
 * real parser (the {@code editor/CompactSource} / {@code run/MakefileTargets} heuristic style; the blanker is
 * duplicated here because the pure {@code test} package must not depend on {@code editor}). Recognizes JUnit 5
 * ({@code @Test}/{@code @ParameterizedTest}/{@code @RepeatedTest}/{@code @TestFactory}/{@code @TestTemplate})
 * and JUnit 4 ({@code @Test}), simple or fully-qualified.
 *
 * <p>v1 deliberately reports only methods declared <b>directly</b> in the top-level class (depth 1) — methods
 * inside a {@code @Nested} inner class are skipped (their build-tool filters need {@code Outer$Inner} forms);
 * the class-level ▶ still runs them via the whole-class run. Pure — no toolkit.
 */
public final class JavaTestScanner {

    private JavaTestScanner() {}

    /**
     * A runnable test target on {@code line} (0-based): the fully-qualified {@code className} plus
     * {@code methodName}, or {@code methodName == null} for the class-declaration line (run the whole class).
     */
    public record TestTarget(int line, String className, String methodName) {}

    private static final Set<String> TEST_ANNOTATIONS =
            Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE_DECL = Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern TEST_ANNO =
            Pattern.compile("@(?:\\w+\\.)*(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");
    private static final Pattern LEADING_ANNOTATIONS = Pattern.compile("^(?:@[\\w.]+(?:\\s*\\([^)]*\\))?\\s*)+");
    // A method declaration: a return type (or modifiers/generics) then the name immediately before "(".
    private static final Pattern METHOD = Pattern.compile("^\\s*(?:[A-Za-z_$][\\w$.<>\\[\\],?\\s]*?\\s+)(\\w+)\\s*\\(");

    /** Ordered targets: the class-level target first (only when ≥1 test method was found), then the methods. */
    public static List<TestTarget> scan(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        String[] lines = blank(source).split("\n", -1);

        String pkg = "";
        String outerClass = null;
        int outerLine = -1;
        int classBodyDepth = -1;
        boolean pendingAnno = false;
        int depth = 0;
        List<TestTarget> methods = new ArrayList<>();

        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            String trimmed = line.strip();

            if (pkg.isEmpty()) {
                Matcher pm = PACKAGE.matcher(line);
                if (pm.find()) {
                    pkg = pm.group(1);
                }
            }
            if (outerClass == null && depth == 0) {
                Matcher tm = TYPE_DECL.matcher(line);
                if (tm.find()) {
                    outerClass = tm.group(1);
                    outerLine = li;
                }
            }
            // Detection at the outer class body depth only (nested-class methods sit one level deeper).
            if (classBodyDepth >= 0 && depth == classBodyDepth) {
                if (TEST_ANNO.matcher(trimmed).find()) {
                    pendingAnno = true;
                }
                if (pendingAnno) {
                    String afterAnno = LEADING_ANNOTATIONS.matcher(trimmed).replaceFirst("");
                    Matcher mm = METHOD.matcher(afterAnno);
                    if (mm.find() && outerClass != null) {
                        methods.add(new TestTarget(li, fqcn(pkg, outerClass), mm.group(1)));
                        pendingAnno = false;
                    }
                }
            }

            int delta = braceDelta(line);
            depth += delta;
            if (depth < 0) {
                depth = 0;
            }
            if (outerClass != null && classBodyDepth < 0 && depth >= 1) {
                classBodyDepth = 1; // the top-level class body — its methods live here
            }
            if (classBodyDepth >= 0 && depth < classBodyDepth) {
                pendingAnno = false; // fell out of the class body without a method — drop a dangling annotation
            }
        }

        if (methods.isEmpty()) {
            return List.of();
        }
        List<TestTarget> out = new ArrayList<>(methods.size() + 1);
        out.add(new TestTarget(outerLine, fqcn(pkg, outerClass), null));
        out.addAll(methods);
        return out;
    }

    private static String fqcn(String pkg, String cls) {
        return pkg.isEmpty() ? cls : pkg + "." + cls;
    }

    private static int braceDelta(String line) {
        int d = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '{') {
                d++;
            } else if (c == '}') {
                d--;
            }
        }
        return d;
    }

    /**
     * Replaces comment + string/char/text-block content with spaces, preserving length + newline positions
     * (so a match's line index maps back to the source). Duplicated from {@code editor/CompactSource}.
     */
    static String blank(String s) {
        int n = s.length();
        StringBuilder out = new StringBuilder(n);
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/')) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
            } else if (c == '"' && i + 2 < n && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"') {
                out.append("   ");
                i += 3;
                while (i < n
                        && !(s.charAt(i) == '"' && i + 2 < n && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"')) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("   ");
                    i += 3;
                }
            } else if (c == '"') {
                i = blankQuoted(s, n, '"', out, i);
            } else if (c == '\'') {
                i = blankQuoted(s, n, '\'', out, i);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int blankQuoted(String s, int n, char quote, StringBuilder out, int i) {
        out.append(' ');
        i++; // opening quote
        while (i < n && s.charAt(i) != quote) {
            if (s.charAt(i) == '\\' && i + 1 < n) {
                out.append("  ");
                i += 2;
            } else {
                out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                i++;
            }
        }
        if (i < n) {
            out.append(' ');
            i++; // closing quote
        }
        return i;
    }
}
