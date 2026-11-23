package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * Deno for Run File and Debug: a JavaScript or TypeScript file in a Deno project — the nearest folder above it with a
 * {@code deno.json} or {@code deno.jsonc}, before any with a {@code package.json} — runs under {@code deno run}
 * (a test file under {@code deno test}) from that folder, with {@code --allow-all} as VS Code's Deno launch gives it:
 * the Run console has no terminal for Deno to ask for permissions. Debug starts the same command with
 * {@code --inspect-wait} on a free port, and vscode-js-debug attaches there ({@code attachSimplePort}). Pure, except
 * {@link #root(Path, Path)} and {@link #executable()}.
 */
public final class DenoRun {

    private DenoRun() {}

    /** The files that make a folder a Deno project. */
    public static final List<String> CONFIGS = List.of("deno.json", "deno.jsonc");

    /** What a Deno run is allowed: everything (it cannot ask in the Run console). */
    public static final String ALLOW_ALL = "--allow-all";

    /**
     * Carries the Deno test name at the caret through a debug launch's Node arguments (which Node never sees: the
     * Node routes drop it, {@link #withoutFilter}). Followed by the name.
     */
    public static final String FILTER_ARG = "--editora-deno-filter=";

    /** How long js-debug keeps trying Deno's inspector port, in milliseconds. */
    public static final int ATTACH_TIMEOUT_MS = 60_000;

    /** How many folders a file outside any project is looked above (as Node's package root is). */
    private static final int NO_PROJECT_LEVELS = 8;

    /**
     * The Deno project {@code file} belongs to: the nearest folder above it, inside {@code projectRoot} (or, outside
     * any project, a few folders up and below {@code home}), with a {@code deno.json(c)}; empty when a folder with a
     * {@code package.json} comes first (a Node package) or there is none.
     */
    public static Optional<Path> root(
            @Nullable Path file, @Nullable Path projectRoot, @Nullable Path home, Predicate<Path> isFile) {
        if (file == null || file.getParent() == null) {
            return Optional.empty();
        }
        Path dir = file.toAbsolutePath().normalize().getParent();
        Path stop = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        Path homeDir = home == null ? null : home.toAbsolutePath().normalize();
        int levels = 0;
        while (dir != null) {
            if (stop != null && !dir.startsWith(stop)) {
                break;
            }
            if (stop == null && (levels++ >= NO_PROJECT_LEVELS || dir.equals(homeDir))) {
                break;
            }
            for (String config : CONFIGS) {
                if (isFile.test(dir.resolve(config))) {
                    return Optional.of(dir);
                }
            }
            if (isFile.test(dir.resolve("package.json"))) {
                return Optional.empty();
            }
            if (dir.equals(stop)) {
                break;
            }
            dir = dir.getParent();
        }
        return Optional.empty();
    }

    /** {@link #root(Path, Path, Path, Predicate)} on the real file system. Not on the FX thread. */
    public static Optional<Path> root(Path file, @Nullable Path projectRoot) {
        String home = System.getProperty("user.home");
        return root(file, projectRoot, home == null ? null : Path.of(home), Files::isRegularFile);
    }

    /**
     * Whether {@code fileName} is a file {@code deno test} runs: {@code test}, {@code *_test} or {@code *.test} with a
     * JavaScript or TypeScript extension.
     */
    public static boolean isTestFile(@Nullable String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.matches("(?:.*[._])?test\\.(?:ts|tsx|mts|js|mjs|jsx)");
    }

    /** Whether {@code executable} is Deno ({@code deno}, by path or not, {@code deno.exe}). */
    public static boolean isDeno(@Nullable String executable) {
        if (executable == null) {
            return false;
        }
        String name = executable.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.equals("deno") || name.equals("deno.exe");
    }

    /**
     * The Deno command: {@code deno} on PATH, else Deno's own install ({@code $DENO_INSTALL/bin}, then
     * {@code ~/.deno/bin}, where its installer puts it without touching PATH), else the bare name (its absence is
     * then said when it fails to start). Not on the FX thread.
     */
    public static String executable() {
        String onPath = com.editora.process.ProcessRunner.resolveExecutable(List.of("deno"))
                .getFirst();
        if (Path.of(onPath).isAbsolute()) {
            return onPath;
        }
        String exe = com.editora.Os.isWindows() ? "deno.exe" : "deno";
        List<Path> homes = new ArrayList<>();
        String install = System.getenv("DENO_INSTALL");
        if (install != null && !install.isBlank()) {
            homes.add(Path.of(install));
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            homes.add(Path.of(home, ".deno"));
        }
        for (Path h : homes) {
            Path candidate = h.resolve("bin").resolve(exe);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return "deno";
    }

    /**
     * The command that runs {@code target}: {@code deno run --allow-all target args…}, or for tests
     * {@code deno test --allow-all [--filter /^name$/] target} (narrowed to the test {@code filterName} names).
     */
    public static List<String> argv(
            String deno, boolean test, @Nullable String filterName, String target, List<String> args) {
        List<String> argv = new ArrayList<>(List.of(deno, test ? "test" : "run", ALLOW_ALL));
        if (test && filterName != null && !filterName.isEmpty()) {
            argv.add("--filter");
            argv.add(filterPattern(filterName));
        }
        argv.add(target);
        if (!args.isEmpty()) {
            if (test) {
                argv.add("--"); // after the file, deno test hands what follows "--" to the tests
            }
            argv.addAll(args);
        }
        return List.copyOf(argv);
    }

    /** A {@code --filter} that matches the test called {@code name} and no other: {@code /^name$/}. */
    public static String filterPattern(String name) {
        StringBuilder sb = new StringBuilder("/^");
        for (char c : name.toCharArray()) {
            if ("\\^$.|?*+()[]{}/".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append("$/").toString();
    }

    /** The Deno test name {@link #FILTER_ARG} carries in {@code nodeArgs}, or null. */
    public static @Nullable String filterOf(@Nullable List<String> nodeArgs) {
        if (nodeArgs != null) {
            for (String a : nodeArgs) {
                if (a.startsWith(FILTER_ARG)) {
                    return a.substring(FILTER_ARG.length());
                }
            }
        }
        return null;
    }

    /**
     * {@code nodeArgs} without what Editora carries in them for itself — {@link #FILTER_ARG}, and
     * {@link JsTestRunner#NAMES_ARG} (every {@code --editora-} word): what Node is given.
     */
    public static List<String> withoutFilter(@Nullable List<String> nodeArgs) {
        return nodeArgs == null
                ? List.of()
                : nodeArgs.stream().filter(a -> !a.startsWith(EDITORA_ARG)).toList();
    }

    /** What every argument Editora carries for itself starts with. */
    private static final String EDITORA_ARG = "--editora-";

    /**
     * {@code runtime} (Deno, its subcommand and flags) waiting for a debugger on {@code port}:
     * {@code --inspect-wait=127.0.0.1:port} after the subcommand — {@code run} added when there is none (a
     * {@code #!/usr/bin/env deno} shebang) — and any inspect flag it had dropped.
     */
    public static List<String> inspected(List<String> runtime, int port) {
        List<String> out = new ArrayList<>();
        out.add(runtime.getFirst());
        int i = 1;
        if (runtime.size() > 1 && !runtime.get(1).startsWith("-")) {
            out.add(runtime.get(1));
            i = 2;
        } else {
            out.add("run");
        }
        out.add("--inspect-wait=127.0.0.1:" + port);
        for (; i < runtime.size(); i++) {
            String a = runtime.get(i);
            if (!a.startsWith("--inspect")) {
                out.add(a);
            }
        }
        return List.copyOf(out);
    }
}
