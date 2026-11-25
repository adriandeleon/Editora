package com.editora.run;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * What Run File and Debug File do with an Elixir file. Pure apart from the disk lookups the caller supplies.
 *
 * <ul>
 *   <li>In a Mix project (the nearest folder with a {@code mix.exs}): a test file ({@code *_test.exs}) runs {@code mix
 *       test file}, or {@code :line} for the test or {@code describe} there; another script ({@code .exs}) runs {@code
 *       mix run file.exs} with the project loaded; a module ({@code .ex}) runs the project: in a Phoenix project
 *       ({@code :phoenix} among its dependencies) its web server, {@code mix phx.server}; otherwise {@code mix run} —
 *       with {@code --no-halt} when its application starts a supervision tree ({@code mod:} in {@code mix.exs}), which
 *       would end at once otherwise.</li>
 *   <li>Outside one: {@code elixir file}. ElixirLS debugs Mix tasks alone, so such a file is not debugged
 *       ({@link #debuggable}).</li>
 * </ul>
 *
 * The task and its arguments are what ElixirLS's {@code mix_task} launch takes.
 */
public final class ElixirRun {

    private ElixirRun() {}

    /** What an Elixir file runs as. */
    public enum Kind {
        /** {@code elixir file}, outside a Mix project. */
        SCRIPT,
        /** {@code mix test}. */
        TESTS,
        /** {@code mix run}, on a script or the project. */
        MIX_RUN,
        /** {@code mix phx.server}: a Phoenix project's web server, which runs until stopped. */
        PHOENIX
    }

    /** The project's scripts that are not programs: its build, its formatter's settings, its configuration. */
    static final Set<String> NOT_RUN = Set.of(
            "mix.exs",
            ".formatter.exs",
            "config.exs",
            "runtime.exs",
            "dev.exs",
            "test.exs",
            "prod.exs",
            "test_helper.exs");

    /** {@code mod: {App.Application, []}} in a {@code mix.exs}'s {@code application}. */
    private static final Pattern MOD = Pattern.compile("\\bmod:\\s*\\{");

    /** {@code {:phoenix, "~> 1.8"}} among a {@code mix.exs}'s dependencies: a Phoenix project (or umbrella web app). */
    private static final Pattern PHOENIX_DEP = Pattern.compile("\\{\\s*:phoenix\\s*,");

    /**
     * How an Elixir file is run.
     *
     * @param kind what it runs as
     * @param command what Run runs ({@code mix test test/x_test.exs:4}, {@code elixir file.exs})
     * @param cwd the folder it runs in
     * @param project the Mix project's folder, or null for none
     * @param task the Mix task ({@code test}, {@code run}), or "" for none
     * @param taskArgs the task's arguments
     * @param requireFiles what ElixirLS loads before the task, so a test file's own modules are debugged too
     */
    public record Plan(
            Kind kind,
            List<String> command,
            Path cwd,
            @Nullable Path project,
            String task,
            List<String> taskArgs,
            List<String> requireFiles) {
        public Plan {
            command = List.copyOf(command);
            taskArgs = List.copyOf(taskArgs);
            requireFiles = List.copyOf(requireFiles);
        }
    }

    /**
     * The plan for {@code file} (text {@code text}) with program arguments {@code args}; {@code line} (0-based, or -1)
     * narrows a test file to the test there. {@code top} bounds the walk up for the project; {@code read} gives a
     * file's text, or null.
     */
    public static Plan plan(
            Path file,
            String text,
            List<String> args,
            int line,
            @Nullable Path top,
            Predicate<Path> isFile,
            Function<Path, @Nullable String> read) {
        Path abs = file.toAbsolutePath();
        Path dir = abs.getParent() == null ? abs : abs.getParent();
        String name = abs.getFileName() == null ? "" : abs.getFileName().toString();
        Path project = project(dir, top, isFile);
        if (project == null) {
            List<String> command = new ArrayList<>(List.of("elixir", abs.toString()));
            command.addAll(args);
            return new Plan(Kind.SCRIPT, command, dir, null, "", List.of(), List.of());
        }
        String rel = project.relativize(abs).toString().replace('\\', '/');
        if (isTest(name)) {
            List<String> taskArgs = new ArrayList<>(List.of(rel + (line >= 0 ? ":" + (line + 1) : "")));
            taskArgs.addAll(args);
            List<String> require = new ArrayList<>();
            if (isFile.test(project.resolve("test/test_helper.exs"))) {
                require.add("test/test_helper.exs");
            }
            require.add(rel);
            return new Plan(Kind.TESTS, mix("test", taskArgs), project, project, "test", taskArgs, require);
        }
        List<String> taskArgs = new ArrayList<>();
        if (name.endsWith(".exs") && !NOT_RUN.contains(name)) {
            taskArgs.add(rel); // a script, run with the project loaded
        } else {
            String mixExs = read.apply(project.resolve("mix.exs"));
            if (mixExs != null && PHOENIX_DEP.matcher(mixExs).find()) {
                // a Phoenix project's module: its web server, as `mix phx.server` (code reloading and all)
                List<String> serverArgs = new ArrayList<>(args);
                return new Plan(
                        Kind.PHOENIX,
                        mix("phx.server", serverArgs),
                        project,
                        project,
                        "phx.server",
                        serverArgs,
                        List.of());
            }
            if (mixExs != null && MOD.matcher(mixExs).find()) {
                taskArgs.add("--no-halt"); // the application's supervision tree runs until stopped
            }
        }
        taskArgs.addAll(args);
        return new Plan(Kind.MIX_RUN, mix("run", taskArgs), project, project, "run", taskArgs, List.of());
    }

    /** Whether {@code name} is an ExUnit test file. */
    public static boolean isTest(String name) {
        return name.endsWith("_test.exs");
    }

    /** The nearest folder from {@code dir} up (to {@code top}) with a {@code mix.exs}, or null. */
    static @Nullable Path project(Path dir, @Nullable Path top, Predicate<Path> isFile) {
        for (Path d = dir; d != null; d = d.getParent()) {
            if (isFile.test(d.resolve("mix.exs"))) {
                return d;
            }
            if (d.equals(top)) {
                break;
            }
        }
        return null;
    }

    /**
     * The 0-based line of an Elixir file's Run ▶, or -1 for none: a test file at its {@code defmodule}, a script
     * ({@code .exs}, not a build or configuration file) from the top; a module ({@code .ex}) has none.
     */
    public static int runLine(String name, String text) {
        if (isTest(name)) {
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].stripLeading().startsWith("defmodule ")) {
                    return i;
                }
            }
            return 0;
        }
        return name.endsWith(".exs") && !NOT_RUN.contains(name) ? 0 : -1;
    }

    private static List<String> mix(String task, List<String> taskArgs) {
        List<String> out = new ArrayList<>(List.of("mix", task));
        out.addAll(taskArgs);
        return out;
    }
}
