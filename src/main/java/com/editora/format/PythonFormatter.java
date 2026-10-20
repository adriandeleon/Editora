package com.editora.format;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

/**
 * Format Document for Python (adl's decision 4): Pyright has no formatter, so a {@code .py} file is piped through
 * Ruff ({@code ruff format -}) or, failing that, Black ({@code black -q -}) — the two formatters Python
 * projects use. Each is looked for in the project's virtual environment first (a project pins its own
 * formatter there), then on PATH: venv Ruff, venv Black, PATH Ruff, PATH Black.
 *
 * <p>{@code --stdin-filename} names the file, so the formatter finds the project's {@code pyproject.toml}
 * settings and its exclude rules, and it runs in the file's folder. Finding is pure apart from the lookups the
 * caller supplies; {@link #run} forks the process and must be called off the FX thread.
 */
public final class PythonFormatter {

    private PythonFormatter() {}

    /** A formatter found: its name ({@code ruff}/{@code black}), the executable, and where it was found. */
    public record Found(String name, String executable, boolean inVenv) {

        /** The argv that formats {@code file}'s text read from stdin and writes the result to stdout. */
        public List<String> argv(@Nullable Path file) {
            List<String> argv = new ArrayList<>();
            argv.add(executable);
            if ("ruff".equals(name)) {
                argv.add("format");
            } else {
                argv.add("-q");
            }
            if (file != null) {
                argv.add("--stdin-filename");
                argv.add(file.toString());
            }
            argv.add("-");
            return List.copyOf(argv);
        }
    }

    /** What a run produced: the formatted text, or an error line to show. */
    public record Outcome(@Nullable String text, @Nullable String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** The formatter names, in preference order. */
    static final List<String> NAMES = List.of("ruff", "black");

    /**
     * The formatter for a file whose interpreter lives in {@code venv} (null when it is not in one).
     *
     * @param isExecutable whether a path is a runnable file
     * @param onPath a bare name → its absolute path on PATH
     */
    public static Optional<Found> find(
            @Nullable Path venv,
            boolean windows,
            Predicate<Path> isExecutable,
            Function<String, Optional<String>> onPath) {
        if (venv != null) {
            for (String name : NAMES) {
                Path exe = venv.resolve(windows ? "Scripts" : "bin").resolve(windows ? name + ".exe" : name);
                if (isExecutable.test(exe)) {
                    return Optional.of(new Found(name, exe.toString(), true));
                }
            }
        }
        for (String name : NAMES) {
            Optional<String> exe = onPath.apply(name);
            if (exe != null && exe.isPresent()) {
                return Optional.of(new Found(name, exe.get(), false));
            }
        }
        return Optional.empty();
    }

    /** {@link #find} on this machine: the real file system and Editora's augmented PATH. */
    public static Optional<Found> findLocal(@Nullable Path venv) {
        boolean windows = com.editora.run.PythonInterpreters.isWindows();
        return find(venv, windows, p -> Files.isRegularFile(p) && Files.isExecutable(p), PythonFormatter::onPath);
    }

    private static Optional<String> onPath(String name) {
        List<String> resolved = ProcessRunner.resolveExecutable(List.of(name));
        String exe = resolved.isEmpty() ? name : resolved.get(0);
        return exe.equals(name) ? Optional.empty() : Optional.of(exe);
    }

    /** Formats {@code text} (the buffer's, unsaved edits included) as {@code file}. Forks: off the FX thread. */
    public static Outcome run(Found formatter, @Nullable Path file, String text) {
        Path dir = file == null ? null : file.getParent();
        ProcessRunner.Result r = ProcessRunner.runWithInputInUserLocale(
                dir,
                Duration.ofSeconds(30),
                formatter.argv(file),
                Map.of("PYTHONIOENCODING", "utf-8"), // Black writes stdout in the locale's encoding otherwise
                text.getBytes(StandardCharsets.UTF_8));
        return outcome(formatter, r.exit(), r.out(), r.err());
    }

    /** Pure: a formatter's exit, stdout and stderr → the formatted text, or the first useful error line. */
    static Outcome outcome(Found formatter, int exit, String out, @Nullable String err) {
        if (exit == 0) {
            return new Outcome(out, null);
        }
        String line = firstLine(err);
        if (line.isEmpty()) {
            line = firstLine(out);
        }
        if (exit == -1 && line.isEmpty()) {
            line = "could not run " + formatter.executable();
        }
        return new Outcome(null, formatter.name() + ": " + (line.isEmpty() ? "exit " + exit : line));
    }

    private static String firstLine(@Nullable String s) {
        if (s == null) {
            return "";
        }
        for (String line : s.split("\\R")) {
            String t = line.strip();
            if (!t.isEmpty()) {
                return t.length() > 200 ? t.substring(0, 200) + "…" : t;
            }
        }
        return "";
    }

    /**
     * The smallest single replacement turning {@code before} into {@code after}: {@code [start, endBefore)} of
     * {@code before} becomes {@code after.substring(start, endAfter)}. Keeps the caret, folds and undo step
     * tight instead of replacing the whole document. Null when nothing changed.
     */
    public static int @Nullable [] changedSpan(String before, String after) {
        if (before.equals(after)) {
            return null;
        }
        int start = 0;
        int max = Math.min(before.length(), after.length());
        while (start < max && before.charAt(start) == after.charAt(start)) {
            start++;
        }
        int endBefore = before.length();
        int endAfter = after.length();
        while (endBefore > start && endAfter > start && before.charAt(endBefore - 1) == after.charAt(endAfter - 1)) {
            endBefore--;
            endAfter--;
        }
        return new int[] {start, endBefore, endAfter};
    }
}
