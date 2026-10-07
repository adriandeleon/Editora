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
 * <p>Methods of a nested class ({@code @Nested}, or a static nested test class) are reported under that
 * class's binary name, {@code Outer$Inner}, and the nested class gets a class-level target of its own.
 * Local and anonymous classes are not walked. Pure — no toolkit.
 */
public final class JavaTestScanner {

    private JavaTestScanner() {}

    /**
     * A runnable test target on {@code line} (0-based): the fully-qualified {@code className} plus
     * {@code methodName}, or {@code methodName == null} for the class-declaration line (run the whole class).
     * {@code dynamic} is true for the parameterized family ({@code @ParameterizedTest}/{@code @RepeatedTest}/
     * {@code @TestFactory}/{@code @TestTemplate}) whose runtime case ids ({@code foo(int)[1]}) don't match the
     * method name — so pre-seeding skips them (they'd be grey ghosts); false for a plain {@code @Test} + the
     * class target.
     */
    public record TestTarget(int line, String className, String methodName, boolean dynamic) {}

    private static final Set<String> TEST_ANNOTATIONS =
            Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");

    /**
     * A Java identifier. Not {@code \w+}, which is ASCII-only in {@code java.util.regex}: a test named
     * {@code größeIstKorrekt} or {@code 正常系_ログインできる} got no gutter marker and no seeded row.
     */
    private static final String ID = "[\\p{L}_$][\\p{L}\\p{N}_$]*";

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\p{L}\\p{N}_$.]+)\\s*;");
    private static final Pattern TYPE_DECL =
            Pattern.compile("(?<![\\p{L}\\p{N}_$])(?:class|interface|enum|record)\\s+(" + ID + ")");
    private static final Pattern TEST_ANNO =
            Pattern.compile("@(?:\\w+\\.)*(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");
    private static final Pattern LEADING_ANNOTATIONS = Pattern.compile("^(?:@[\\w.]+(?:\\s*\\([^)]*\\))?\\s*)+");
    // A method declaration: a return type (or modifiers/generics) then the name immediately before "(".
    private static final Pattern METHOD =
            Pattern.compile("^\\s*(?:[\\p{L}_$][\\p{L}\\p{N}_$.<>\\[\\],?\\s]*?\\s+)(" + ID + ")\\s*\\(");

    /** A type whose body is being walked: its binary name, where it is declared, and its body's brace depth. */
    private static final class Frame {
        final String binaryName;
        final int line;
        final int bodyDepth;
        boolean entered;
        boolean hasTests;

        Frame(String binaryName, int line, int bodyDepth) {
            this.binaryName = binaryName;
            this.line = line;
            this.bodyDepth = bodyDepth;
        }
    }

    /**
     * Ordered targets: the top-level class first (only when a test was found anywhere in it), then every
     * nested class that holds tests and every test method, in source order. A method in a nested class
     * carries that class's binary name ({@code pkg.Outer$Inner}), which is the name its results are reported
     * under and the one a Surefire or Gradle filter needs.
     */
    public static List<TestTarget> scan(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        String[] lines = blank(source).split("\n", -1);

        String pkg = "";
        boolean pendingAnno = false;
        boolean pendingDynamic = false;
        int depth = 0;
        Frame outer = null;
        java.util.ArrayDeque<Frame> stack = new java.util.ArrayDeque<>();
        List<Frame> nested = new ArrayList<>();
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
            Frame top = stack.peek();
            if (outer == null && depth == 0) {
                Matcher tm = TYPE_DECL.matcher(line);
                if (tm.find()) {
                    outer = new Frame(fqcn(pkg, tm.group(1)), li, 1);
                    stack.push(outer);
                }
            } else if (top != null && top.entered && depth == top.bodyDepth) {
                // Detection at a type's own body depth only: a method body, a lambda or an anonymous class
                // sits deeper, so neither its annotations nor a local class are mistaken for members.
                Matcher am = TEST_ANNO.matcher(trimmed);
                if (am.find()) {
                    pendingAnno = true;
                    pendingDynamic = !"Test".equals(am.group(1)); // parameterized family → dynamic case ids
                }
                boolean method = false;
                if (pendingAnno) {
                    String afterAnno = LEADING_ANNOTATIONS.matcher(trimmed).replaceFirst("");
                    Matcher mm = METHOD.matcher(afterAnno);
                    if (mm.find()) {
                        methods.add(new TestTarget(li, top.binaryName, mm.group(1), pendingDynamic));
                        stack.forEach(f -> f.hasTests = true);
                        pendingAnno = false;
                        method = true;
                    }
                }
                Matcher tm = method ? null : TYPE_DECL.matcher(line);
                if (tm != null && tm.find()) {
                    Frame inner = new Frame(top.binaryName + "$" + tm.group(1), li, depth + 1);
                    nested.add(inner);
                    stack.push(inner);
                    pendingAnno = false; // an annotation on the class is not one on its first method
                }
            }

            depth = Math.max(0, depth + braceDelta(line));
            while (!stack.isEmpty()) {
                Frame f = stack.peek();
                if (depth >= f.bodyDepth) {
                    f.entered = true;
                    break;
                }
                if (!f.entered) {
                    break; // declared, its opening brace is on a later line
                }
                stack.pop();
                pendingAnno = false; // fell out of a body without a method — drop a dangling annotation
            }
        }

        if (methods.isEmpty() || outer == null) {
            return List.of();
        }
        List<TestTarget> out = new ArrayList<>(methods);
        for (Frame f : nested) {
            if (f.hasTests) {
                out.add(new TestTarget(f.line, f.binaryName, null, false));
            }
        }
        // Stable, so a class declared on the line of its first method (a one-liner) stays ahead of it.
        out.sort(java.util.Comparator.comparingInt(TestTarget::line).thenComparing(t -> t.methodName() != null));
        out.add(0, new TestTarget(outer.line, outer.binaryName, null, false));
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
