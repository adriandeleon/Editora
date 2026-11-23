package com.editora.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Finds JUnit test methods + the enclosing top-level test class in Java, Kotlin or Groovy source text (Spock features,
 * Kotest spec classes too: {@link #scan(String, String)}), for the editor's gutter
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
    public record TestTarget(
            int line, String className, @Nullable String methodName, boolean dynamic) {}

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

    /** The JVM languages whose test files are scanned: Java, Kotlin and Groovy (their JUnit tests run through the build). */
    public static boolean supports(@Nullable String language) {
        return "java".equals(language) || "kotlin".equals(language) || "groovy".equals(language);
    }

    /** {@link #scan(String)} for a file of {@code language} ({@link #supports}); nothing for any other. */
    public static List<TestTarget> scan(@Nullable String language, String source) {
        if ("kotlin".equals(language)) {
            return scan(source, Dialect.KOTLIN);
        }
        if ("groovy".equals(language)) {
            return scan(source, Dialect.GROOVY);
        }
        return "java".equals(language) ? scan(source) : List.of();
    }

    /** The language of a source file by its name's extension ({@code .kt}, {@code .groovy}), for {@link #scan(String, String)}. */
    public static @Nullable String languageOf(@Nullable String fileName) {
        if (fileName == null) {
            return null;
        }
        if (fileName.endsWith(".java")) {
            return "java";
        }
        if (fileName.endsWith(".kt")) {
            return "kotlin";
        }
        return fileName.endsWith(".groovy") ? "groovy" : null;
    }

    /** How a language writes what the walk looks for. */
    private enum Dialect {
        /** {@code void name(}, {@code package a.b;}. */
        JAVA,
        /**
         * {@code fun name(} or {@code fun `a name`(}; {@code package a.b} without a semicolon; {@code object}s; a Kotest
         * spec class ({@code : StringSpec(…)}) is a test class as a whole (its tests are strings, run with their class).
         */
        KOTLIN,
        /** {@code void name(} or {@code def name(}; a Spock {@code Specification}'s {@code def "a feature"()}. */
        GROOVY
    }

    private static final Pattern KOTLIN_PACKAGE = Pattern.compile("^\\s*package\\s+([\\p{L}\\p{N}_$.]+)");
    private static final Pattern KOTLIN_TYPE_DECL =
            Pattern.compile("(?<![\\p{L}\\p{N}_$])(?:class|interface|object)\\s+(" + ID + ")");
    private static final Pattern KOTLIN_FUN =
            Pattern.compile("(?<![\\p{L}\\p{N}_$])fun\\s+(?:<[^>]*>\\s*)?(`[^`]+`|" + ID + ")\\s*\\(");
    private static final Pattern KOTEST_SPEC = Pattern.compile(
            ":\\s*(?:io\\.kotest\\.core\\.spec\\.style\\.)?(?:StringSpec|FunSpec|DescribeSpec|ShouldSpec|BehaviorSpec"
                    + "|WordSpec|FreeSpec|FeatureSpec|ExpectSpec|AnnotationSpec)\\b");
    private static final Pattern GROOVY_PACKAGE = Pattern.compile("^\\s*package\\s+([\\p{L}\\p{N}_$.]+)\\s*;?");
    private static final Pattern GROOVY_TYPE_DECL =
            Pattern.compile("(?<![\\p{L}\\p{N}_$])(?:class|interface|enum|trait|record)\\s+(" + ID + ")");
    private static final Pattern SPOCK_SPEC = Pattern.compile("extends\\s+(?:spock\\.lang\\.)?Specification\\b");
    private static final Pattern SPOCK_FEATURE = Pattern.compile("^\\s*def\\s+([\"'])(.+?)\\1\\s*\\(");

    /** A type whose body is being walked: its binary name, where it is declared, and its body's brace depth. */
    private static final class Frame {
        final String binaryName;
        final int line;
        final int bodyDepth;
        boolean entered;
        boolean hasTests;
        /** A Spock specification: its {@code def "…"()} features are its tests. */
        boolean spock;

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
        return scan(source, Dialect.JAVA);
    }

    private static List<TestTarget> scan(String source, Dialect dialect) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        String[] lines = blank(source).split("\n", -1);
        String[] raw = source.split("\n", -1); // a Spock feature's name is a string, which the blanked text hides
        Pattern packagePattern =
                dialect == Dialect.KOTLIN ? KOTLIN_PACKAGE : dialect == Dialect.GROOVY ? GROOVY_PACKAGE : PACKAGE;
        Pattern typeDecl =
                dialect == Dialect.KOTLIN ? KOTLIN_TYPE_DECL : dialect == Dialect.GROOVY ? GROOVY_TYPE_DECL : TYPE_DECL;
        List<Frame> specs = new ArrayList<>();

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
                Matcher pm = packagePattern.matcher(line);
                if (pm.find()) {
                    pkg = pm.group(1);
                }
            }
            Frame top = stack.peek();
            if (outer == null && depth == 0) {
                Matcher tm = typeDecl.matcher(line);
                if (tm.find()) {
                    outer = new Frame(fqcn(pkg, tm.group(1)), li, 1);
                    outer.spock = dialect == Dialect.GROOVY
                            && SPOCK_SPEC.matcher(line).find();
                    if (dialect == Dialect.KOTLIN && KOTEST_SPEC.matcher(line).find()) {
                        specs.add(outer); // its tests are strings in its constructor: it runs as a whole
                    }
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
                String name = null;
                if (top.spock && li < raw.length) {
                    Matcher fm = SPOCK_FEATURE.matcher(raw[li]);
                    if (fm.find()) {
                        name = fm.group(2); // a feature: a test without an annotation
                    }
                }
                if (name == null && pendingAnno) {
                    String afterAnno = LEADING_ANNOTATIONS.matcher(trimmed).replaceFirst("");
                    Matcher mm = (dialect == Dialect.KOTLIN ? KOTLIN_FUN : METHOD).matcher(afterAnno);
                    if (mm.find()) {
                        name = mm.group(1).replace("`", "");
                    }
                }
                if (name != null) {
                    methods.add(new TestTarget(li, top.binaryName, name, pendingDynamic));
                    stack.forEach(f -> f.hasTests = true);
                    pendingAnno = false;
                    method = true;
                }
                Matcher tm = method ? null : typeDecl.matcher(line);
                if (tm != null && tm.find()) {
                    Frame inner = new Frame(top.binaryName + "$" + tm.group(1), li, depth + 1);
                    inner.spock = dialect == Dialect.GROOVY
                            && SPOCK_SPEC.matcher(line).find();
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

        if (outer == null) {
            return List.of();
        }
        if (methods.isEmpty()) {
            // A Kotest spec: its class is the target (its tests are strings, not methods).
            return specs.contains(outer)
                    ? List.of(new TestTarget(outer.line, outer.binaryName, null, false))
                    : List.of();
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

    /**
     * The target the caret on {@code caret} (0-based) means, among {@code targets} (by line, from {@link #scan}), or
     * null outside every test class (Java debug review 3, A205):
     * <ul>
     *   <li>on a target's own line, that target;
     *   <li>on its annotations, or a comment right above them, the target they lead to — as in IntelliJ, where
     *       they are part of the declaration; the last target above used to run, even one in a nested class above;
     *   <li>elsewhere, the innermost target whose body holds the caret: a test method, else its class — a blank
     *       line between two tests is the class's, as in IntelliJ, and after a nested class's closing brace the
     *       caret is in the outer class again.
     * </ul>
     * Pure.
     */
    public static @Nullable TestTarget targetAt(String source, java.util.Map<Integer, TestTarget> targets, int caret) {
        if (source == null || targets == null || targets.isEmpty() || caret < 0) {
            return null;
        }
        return targetAt(caretIndex(source, targets), targets, caret);
    }

    /**
     * What {@link #targetAt} reads of a source, worked out once per scan (off the FX thread, with the targets): which
     * lines are blank, which lead in to a declaration (annotations, comments, blank lines among them), and the line
     * that ends each target's body. With it the target at a caret is a walk over these flags, no parse.
     */
    public record CaretIndex(
            int lines, java.util.BitSet rawBlank, java.util.BitSet leadIn, java.util.Map<Integer, Integer> bodyEnds) {}

    /** The {@link CaretIndex} of {@code source} for {@code targets}. Pure; meant for the scan's own thread. */
    public static CaretIndex caretIndex(String source, java.util.Map<Integer, TestTarget> targets) {
        String[] lines = blank(source).split("\n", -1);
        String[] raw = source.split("\n", -1);
        java.util.BitSet rawBlank = new java.util.BitSet(raw.length);
        java.util.BitSet leadIn = new java.util.BitSet(lines.length);
        for (int i = 0; i < raw.length; i++) {
            if (raw[i].isBlank()) {
                rawBlank.set(i);
            }
        }
        for (int i = 0; i < lines.length; i++) {
            if (leadsToDeclaration(lines[i])) {
                leadIn.set(i);
            }
        }
        java.util.Map<Integer, Integer> bodyEnds = new java.util.HashMap<>();
        for (TestTarget t : targets.values()) {
            bodyEnds.put(t.line(), bodyEnd(lines, t.line()));
        }
        return new CaretIndex(Math.min(lines.length, raw.length), rawBlank, leadIn, java.util.Map.copyOf(bodyEnds));
    }

    /** {@link #targetAt(String, java.util.Map, int)} from a {@link CaretIndex} made for these {@code targets}. */
    public static @Nullable TestTarget targetAt(
            CaretIndex index, java.util.Map<Integer, TestTarget> targets, int caret) {
        if (targets.isEmpty() || caret < 0) {
            return null;
        }
        if (caret < index.lines() && !index.rawBlank().get(caret)) {
            int line = caret;
            while (line < index.lines()
                    && !targets.containsKey(line)
                    && index.leadIn().get(line)) {
                line++; // over annotations, comments, and blank lines between them
            }
            if (targets.containsKey(line)) {
                return targets.get(line);
            }
        }
        java.util.NavigableMap<Integer, TestTarget> above = new java.util.TreeMap<>(targets).headMap(caret, true);
        for (TestTarget t : above.descendingMap().values()) {
            Integer end = index.bodyEnds().get(t.line());
            if (caret <= (end == null ? t.line() : end)) {
                return t;
            }
        }
        return null;
    }

    /** A line of a declaration's lead-in: an annotation, a comment (blanked), or a blank line among them. */
    private static boolean leadsToDeclaration(String blankedLine) {
        String t = blankedLine.strip();
        return t.isEmpty() || t.startsWith("@");
    }

    /** The line of the brace that closes the body opened on or after {@code from}; {@code from} when none opens. */
    private static int bodyEnd(String[] lines, int from) {
        int depth = 0;
        boolean opened = false;
        for (int li = from; li < lines.length; li++) {
            String line = lines[li];
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '{') {
                    depth++;
                    opened = true;
                } else if (c == '}' && opened && --depth == 0) {
                    return li;
                } else if (c == ';' && !opened) {
                    return li; // an abstract or interface method: no body
                }
            }
        }
        return opened ? lines.length - 1 : from;
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
