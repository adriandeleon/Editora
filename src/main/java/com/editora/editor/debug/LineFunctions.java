package com.editora.editor.debug;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Pure: the functions that start on one line of code, where a breakpoint can stop inside them — Java's lambdas
 * ({@link JavaLambdas}), JavaScript's and TypeScript's arrow functions and function expressions ({@link JsFunctions}).
 * Each has the column it starts at (what the caret and its number go by) and the column a debugger stops in it at
 * (Java: the lambda's start, as java-debug matches it; JavaScript: the start of its body, which V8 stops at).
 */
public final class LineFunctions {

    private LineFunctions() {}

    /** A function starting on a line: its 0-based start column, the 0-based column to stop at, its text from there. */
    public record Function(int start, int stop, String preview) {}

    /** The editor languages whose files' debuggers stop inside a function by column. */
    private static final Set<String> JS = Set.of("javascript", "typescript", "javascriptreact", "typescriptreact");

    /** Whether a breakpoint can stop inside the functions of {@code language}'s lines (an editor language id). */
    public static boolean supports(@Nullable String language) {
        return "java".equals(language) || javascript(language);
    }

    /** Whether {@code language} is JavaScript's or TypeScript's. */
    public static boolean javascript(@Nullable String language) {
        return language != null && JS.contains(language);
    }

    /** Every function starting on {@code line} of {@code language}, left to right (the order their numbers go in). */
    public static List<Function> on(@Nullable String language, @Nullable String line) {
        if (line == null || !supports(language)) {
            return List.of();
        }
        if (javascript(language)) {
            return JsFunctions.on(line);
        }
        List<Function> out = new ArrayList<>();
        for (JavaLambdas.Lambda l : JavaLambdas.on(line)) {
            out.add(new Function(l.column(), l.column(), l.preview()));
        }
        return out;
    }

    /**
     * The number (from 1) of the function {@code column} (0-based) is in or after: the last one starting at or before
     * it, or the first when it is before them all; 0 when there is none.
     */
    public static int numberAt(List<Function> functions, int column) {
        int n = functions.isEmpty() ? 0 : 1;
        for (int i = 0; i < functions.size(); i++) {
            if (functions.get(i).start() <= column) {
                n = i + 1;
            }
        }
        return n;
    }

    /** The editor language of {@code file} by its extension, for a file with no tab; null when none of these. */
    public static @Nullable String languageOf(@Nullable Path file) {
        Path name = file == null ? null : file.getFileName();
        if (name == null) {
            return null;
        }
        String n = name.toString().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".java")) {
            return "java";
        }
        if (n.endsWith(".js") || n.endsWith(".mjs") || n.endsWith(".cjs") || n.endsWith(".jsx")) {
            return "javascript";
        }
        if (n.endsWith(".ts") || n.endsWith(".mts") || n.endsWith(".cts") || n.endsWith(".tsx")) {
            return "typescript";
        }
        return null;
    }
}
