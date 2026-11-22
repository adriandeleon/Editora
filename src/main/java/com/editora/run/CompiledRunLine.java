package com.editora.run;

import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Where Run File starts a C, C++, Go, Rust or Dart file: the line of its program's {@code main}, or -1 when the file has
 * none and so builds no program (a header, a library file, a Go package other than {@code main}). Run File on such
 * a file builds it as Debug File does ({@link com.editora.dap.NativeBuild}, {@code go build}, Cargo) and runs the
 * program in the Run console.
 *
 * <p>Pure, and read line by line with patterns anchored at the start of a line, so a {@code main} that only appears
 * in a comment or a string in the middle of a line does not count.
 */
public final class CompiledRunLine {

    private CompiledRunLine() {}

    /**
     * A C or C++ {@code main} definition: optional return type words ({@code int}, {@code auto}, {@code static}…),
     * or GNU style with {@code main} alone at the start of its line, then {@code main(}.
     */
    private static final Pattern NATIVE_MAIN = Pattern.compile("^(?:[A-Za-z_][\\w:<>]*[\\s*&]+)*main\\s*\\(");

    private static final Pattern GO_PACKAGE_MAIN = Pattern.compile("^package\\s+main\\b");
    private static final Pattern GO_MAIN = Pattern.compile("^func\\s+main\\s*\\(\\s*\\)");
    private static final Pattern GO_TEST = Pattern.compile("^func\\s+(?:Test|Benchmark|Example|Fuzz)\\w*\\s*\\(");
    private static final Pattern RUST_MAIN =
            Pattern.compile("^(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?(?:unsafe\\s+)?fn\\s+main\\s*\\(");

    /**
     * Whether Run File builds and runs files of {@code language} ({@code c}, {@code cpp}, {@code go}, {@code rust},
     * {@code dart}).
     */
    public static boolean supports(@Nullable String language) {
        return ScriptRunCommand.isCompiled(language);
    }

    /**
     * The 0-based line Run File's gutter ▶ sits on for a file of {@code language} named {@code name} with text
     * {@code text}, or -1 when it builds no program.
     */
    public static int runLine(@Nullable String language, @Nullable String name, String text) {
        if (language == null || text == null) {
            return -1;
        }
        return switch (language) {
            case ScriptRunCommand.C, ScriptRunCommand.CPP -> header(name) ? -1 : nativeMainLine(text);
            case ScriptRunCommand.GO -> goRunLine(name, text);
            case ScriptRunCommand.RUST -> first(RUST_MAIN, text);
            case ScriptRunCommand.DART -> DartRun.runLine(text);
            default -> -1;
        };
    }

    /** A header is compiled only as part of another file: it has no program of its own. */
    private static boolean header(@Nullable String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".h")
                || lower.endsWith(".hh")
                || lower.endsWith(".hpp")
                || lower.endsWith(".hxx")
                || lower.endsWith(".h++")
                || lower.endsWith(".inl");
    }

    /** The line of a C or C++ {@code main} definition (a declaration ending in {@code ;} is not one), or -1. */
    static int nativeMainLine(String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (NATIVE_MAIN.matcher(line).find() && !line.strip().endsWith(";")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A Go file's line: a test file's first test (its package's tests are run), else {@code func main} of a file in
     * {@code package main}; -1 for any other package, where {@code go build} makes no program.
     */
    private static int goRunLine(@Nullable String name, String text) {
        if (name != null && name.endsWith("_test.go")) {
            return first(GO_TEST, text);
        }
        return first(GO_PACKAGE_MAIN, text) < 0 ? -1 : first(GO_MAIN, text);
    }

    private static int first(Pattern pattern, String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (pattern.matcher(lines[i]).find()) {
                return i;
            }
        }
        return -1;
    }
}
