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
