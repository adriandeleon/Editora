package com.editora.test;

import com.editora.build.BuildTool;

/**
 * Pure helpers for deriving a source-file name hint from a test's grouping/class name, per tool. The
 * coordinator uses the hint to open the file (by name search), then jumps to the method via LSP symbols or a
 * text search; for a failure it prefers the stack-trace frame, which carries an exact file+line.
 */
public final class TestSourceLocator {

    private TestSourceLocator() {}

    /**
     * A bare source-file name to search for, or {@code null} when it can't be derived. JVM: the simple class
     * name + {@code .java}. Go: {@code null} (the package maps to a directory, not one file; navigate by the
     * {@code TestXxx} function name instead). Cargo/npm: {@code null}.
     */
    public static String fileHint(String className, BuildTool tool) {
        if (className == null || className.isBlank()) {
            return null;
        }
        return switch (tool) {
            case MAVEN, GRADLE -> simpleName(className) + ".java";
            case GO, CARGO, NPM -> null;
        };
    }

    /**
     * The source file's path below a source root, {@code com/foo/BarTest.java} — what distinguishes this
     * class from a same-named one in another package or module. {@code null} when {@link #fileHint} is.
     */
    public static String pathHint(String className, BuildTool tool) {
        String file = fileHint(className, tool);
        if (file == null) {
            return null;
        }
        int dot = className.lastIndexOf('.');
        return dot <= 0 ? file : className.substring(0, dot).replace('.', '/') + "/" + file;
    }

    /** A Java file under a {@code test}/{@code tests} directory of the project (root-relative, '/'-separated). */
    public static boolean isTestSourcePath(String rel) {
        if (rel == null || !rel.endsWith(".java")) {
            return false;
        }
        String dirs = "/" + rel.substring(0, rel.lastIndexOf('/') + 1);
        return dirs.contains("/test/") || dirs.contains("/tests/");
    }

    /**
     * The method name a filter can target: {@code isOdd(int)[1]} → {@code isOdd}. A parameterized,
     * repeated or dynamic test reports one leaf per invocation, named with its parameter list and index;
     * neither Surefire's {@code -Dtest=Class#method} nor Gradle's {@code --tests Class.method} accepts
     * those, so a filter built from the raw name selects nothing.
     */
    public static String filterMethodName(String methodName) {
        if (methodName == null) {
            return null;
        }
        String name = methodName.strip();
        for (char sep : new char[] {'(', '['}) {
            int cut = name.indexOf(sep);
            if (cut > 0) {
                name = name.substring(0, cut);
            }
        }
        return name;
    }

    /**
     * The class part of a Maven {@code -Dtest} selector: the last dot-segment, <b>keeping</b> a nested class's
     * {@code $Inner} — {@code com.x.OrderTest$WhenEmpty} → {@code OrderTest$WhenEmpty}. Surefire matches a
     * method filter against the class file that declares the method, so {@code OrderTest#rejects} selects
     * nothing when {@code rejects} lives in the {@code @Nested} class.
     */
    public static String filterClassName(String className) {
        if (className == null) {
            return null;
        }
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    /**
     * The method part of a Maven {@code -Dtest} selector. A JUnit 5 invocation ({@code isOdd(int)[1]}) is
     * filtered by its method name, {@code isOdd}. A bare {@code name[0]} is a JUnit 4 {@code Parameterized}
     * invocation, whose description name really is {@code name[0]}: {@code name} alone matches nothing
     * there, so both forms are offered ({@code name+name[*]} — Surefire's multi-method syntax).
     */
    public static String mavenMethodFilter(String methodName) {
        if (methodName == null) {
            return null;
        }
        String name = methodName.strip();
        int paren = name.indexOf('(');
        if (paren > 0) {
            return name.substring(0, paren);
        }
        int bracket = name.indexOf('[');
        if (bracket > 0) {
            String base = name.substring(0, bracket);
            return base + "+" + base + "[*]";
        }
        return name;
    }

    /**
     * The {@code go test -run} pattern for these test names. {@code -run} is a regular expression per
     * {@code /} level, and a subtest name is arbitrary text ({@code t.Run("a[b", …)}, {@code "C++"}), so
     * joining raw names produced a pattern RE2 rejects and nothing ran. Only the top-level function names
     * are used — they are plain identifiers, and a selected parent runs all its subtests.
     */
    public static String goRunPattern(java.util.Collection<String> testNames) {
        java.util.Set<String> top = new java.util.LinkedHashSet<>();
        for (String name : testNames) {
            int slash = name.indexOf('/');
            String parent = slash >= 0 ? name.substring(0, slash) : name;
            if (!parent.isEmpty()) {
                top.add(parent.replaceAll("[\\\\.+*?()\\[\\]{}|^$]", "\\\\$0"));
            }
        }
        return "^(" + String.join("|", top) + ")$";
    }

    /** The last dot-segment of a fully-qualified name (strips any nested-class {@code $} suffix too). */
    public static String simpleName(String className) {
        String name = className;
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        int dollar = name.indexOf('$');
        if (dollar >= 0) {
            name = name.substring(0, dollar);
        }
        return name;
    }
}
