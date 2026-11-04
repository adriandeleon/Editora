package com.editora.jstools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

/**
 * Starting ESLint, Prettier and Biome on a buffer's text: the command lines (pure), and {@link #run}, which feeds the
 * text on stdin and must be called off the FX thread. Each reads the file's name from its {@code --stdin-filename}
 * option, so it applies the project's configuration and ignore files to that file.
 */
public final class JsToolRun {

    private JsToolRun() {}

    /** How long one run may take before it is stopped (a cold ESLint with type-aware rules takes seconds). */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** A command line ready to start. */
    public record Invocation(List<String> argv, Path dir, Map<String, String> env) {
        public Invocation {
            argv = List.copyOf(argv);
            env = Map.copyOf(env);
        }
    }

    /**
     * The environment a tool runs with: the project's Node.js's folder first on PATH (a {@code .bin} script's
     * {@code #!/usr/bin/env node}, and anything the tool starts, find that Node.js), and for ESLint which kind of
     * configuration it is to read — ESLint 9 reads only a flat one unless told otherwise, and ESLint 8 before 8.57
     * only an older one.
     */
    static Map<String, String> env(@Nullable Path nodeBin, @Nullable Boolean flatEslintConfig, String path) {
        Map<String, String> env = new LinkedHashMap<>();
        if (nodeBin != null) {
            env.put("PATH", path.isEmpty() ? nodeBin.toString() : nodeBin + java.io.File.pathSeparator + path);
        }
        if (flatEslintConfig != null) {
            env.put("ESLINT_USE_FLAT_CONFIG", flatEslintConfig ? "true" : "false");
        }
        return env;
    }

    /** {@code eslint --format json --stdin --stdin-filename <file>} (with {@code --fix-dry-run}: fixes, unsaved). */
    public static Invocation eslint(
            JsTools.Tool tool,
            JsTools.EslintConfig config,
            Path file,
            List<String> node,
            @Nullable Path nodeBin,
            boolean fix) {
        List<String> argv = new ArrayList<>(tool.command(node));
        argv.addAll(List.of("--format", "json", "--stdin", "--stdin-filename", file.toString()));
        if (fix) {
            argv.add("--fix-dry-run");
        }
        return new Invocation(argv, config.dir(), env(nodeBin, config.flat(), ProcessRunner.augmentedPath()));
    }

    /**
     * Prettier ({@code --stdin-filepath <file>}, with {@code --range-start/--range-end} for a selection: Prettier
     * widens it to the statements it touches) or Biome ({@code format --stdin-file-path=<file>}; Biome has no
     * range).
     */
    public static Invocation format(
            JsTools.FormatPlan plan, Path file, List<String> node, @Nullable Path nodeBin, int @Nullable [] range) {
        JsTools.Tool tool = java.util.Objects.requireNonNull(plan.tool(), "a tool's plan");
        List<String> argv = new ArrayList<>(tool.command(node));
        if (plan.formatter() == JsTools.Formatter.BIOME) {
            argv.addAll(List.of("format", "--stdin-file-path=" + file));
        } else {
            argv.addAll(List.of("--stdin-filepath", file.toString()));
            if (range != null) {
                argv.addAll(
                        List.of("--range-start", String.valueOf(range[0]), "--range-end", String.valueOf(range[1])));
            }
        }
        Path dir = plan.dir() == null ? tool.root() : plan.dir();
        return new Invocation(argv, dir, env(nodeBin, null, ProcessRunner.augmentedPath()));
    }

    /** What a run printed: its exit code, stdout (UTF-8) and stderr; {@code cancelled} when it was stopped. */
    public record Output(int exit, String out, String err, boolean cancelled, boolean timedOut) {}

    /** Runs {@code inv} with {@code text} on stdin. Blocking: off the FX thread. {@code cancel} may stop it. */
    public static Output run(Invocation inv, String text, ProcessRunner.@Nullable Cancellation cancel) {
        ProcessRunner.BytesResult r = ProcessRunner.runBytesWithInput(
                inv.dir(), TIMEOUT, inv.argv(), inv.env(), text.getBytes(StandardCharsets.UTF_8), false, cancel);
        return new Output(
                r.exit(),
                new String(r.out(), StandardCharsets.UTF_8),
                r.err() == null ? "" : r.err(),
                ProcessRunner.cancelled(r),
                ProcessRunner.timedOut(r));
    }

    /** What a formatter run produced: the formatted text, or the error line to show. */
    public record FormatOutcome(
            @Nullable String text, @Nullable String error) {
        public boolean ok() {
            return error == null && text != null;
        }
    }

    /**
     * Pure: a formatter's {@link Output} for {@code input} → the formatted text, or {@code "<Tool>: <first error
     * line>"}. Empty output for a non-empty input is an error, never "format to nothing" (a tool that skipped an
     * ignored file, or printed its result elsewhere, must not empty the buffer).
     */
    public static FormatOutcome formatOutcome(JsTools.Kind kind, Output o, String input) {
        if (o.timedOut()) {
            return new FormatOutcome(null, kind.displayName() + ": timed out");
        }
        if (o.exit() == 0) {
            if (o.out().isEmpty() && !input.isEmpty()) {
                return new FormatOutcome(null, kind.displayName() + ": no output");
            }
            return new FormatOutcome(o.out(), null);
        }
        String line = firstLine(o.err()).or(() -> firstLine(o.out())).orElse("exit " + o.exit());
        return new FormatOutcome(null, kind.displayName() + ": " + line);
    }

    /**
     * Pure: ESLint's {@link Output} for {@code text} → its report, or the first error line. Exit 0 (no errors) and 1
     * (errors found) carry the JSON report; 2 is a configuration problem or a crash, told on stderr.
     */
    public static EslintOutcome eslintOutcome(Output o, String text) {
        if (o.timedOut()) {
            return new EslintOutcome(null, "ESLint: timed out");
        }
        if (o.exit() == 0 || o.exit() == 1) {
            try {
                return new EslintOutcome(EslintReport.parse(o.out(), text), null);
            } catch (IllegalArgumentException e) {
                return new EslintOutcome(null, "ESLint: " + firstLine(o.err()).orElse(e.getMessage()));
            }
        }
        String line =
                firstUsefulEslintLine(o.err()).or(() -> firstLine(o.out())).orElse("exit " + o.exit());
        return new EslintOutcome(null, "ESLint: " + line);
    }

    /** An ESLint run's report, or its error line. */
    public record EslintOutcome(
            EslintReport.@Nullable Result report, @Nullable String error) {}

    /**
     * ESLint's crash text starts "Oops! Something went wrong! :(" then "ESLint: 9.1.0" and a blank line before the
     * reason: the reason is the useful line.
     */
    static Optional<String> firstUsefulEslintLine(@Nullable String err) {
        if (err == null) {
            return Optional.empty();
        }
        for (String line : err.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("Oops! Something went wrong") || t.matches("ESLint: \\d[\\w.\\-]*")) {
                continue;
            }
            return Optional.of(t.length() > 200 ? t.substring(0, 200) + "…" : t);
        }
        return firstLine(err);
    }

    static Optional<String> firstLine(@Nullable String s) {
        if (s == null) {
            return Optional.empty();
        }
        for (String line : s.split("\\R")) {
            String t = line.strip();
            if (!t.isEmpty()) {
                return Optional.of(t.length() > 200 ? t.substring(0, 200) + "…" : t);
            }
        }
        return Optional.empty();
    }
}
