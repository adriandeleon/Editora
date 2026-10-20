package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Finds the command-line tool that runs a JVM language's files — {@code groovy}, Scala CLI, {@code kotlinc}, and
 * Clojure's {@code clojure}, {@code lein} and {@code bb} — on this machine.
 *
 * <p>The tool's own home variable comes first ({@code GROOVY_HOME}: whoever set it chose that install), then the
 * {@linkplain com.editora.process.ProcessRunner#augmentedPath augmented PATH} (the login shell's, which already
 * holds SDKMAN, asdf, mise and Homebrew when the profile sets them up), then SDKMAN's {@code current} candidate
 * for a profile that only sources SDKMAN in interactive shells.
 *
 * <p>{@link #candidates} is pure; {@link #locate} looks at the disk.
 */
public final class JvmTools {

    private JvmTools() {}

    /**
     * How one tool is found: the variable naming its install (null when it has none), the names it goes by on
     * {@code PATH} in the order tried, its SDKMAN candidates with the program each holds, and the launcher's
     * extension on Windows.
     */
    private record Tool(
            @Nullable String homeVariable, List<String> pathNames, List<String[]> sdkman, String windowsSuffix) {}

    /** The tools {@link #locate} knows. */
    private static final Map<String, Tool> TOOLS = Map.of(
            "groovy",
            new Tool("GROOVY_HOME", List.of("groovy"), List.<String[]>of(new String[] {"groovy", "groovy"}), ".bat"),
            // Scala CLI: `scala-cli`, or `scala`, which is Scala CLI since Scala 3.5 (SDKMAN's scala candidate).
            "scala-cli",
            new Tool(
                    null,
                    List.of("scala-cli", "scala"),
                    List.of(new String[] {"scalacli", "scala-cli"}, new String[] {"scala", "scala"}),
                    ".exe"),
            "kotlinc",
            new Tool("KOTLIN_HOME", List.of("kotlinc"), List.<String[]>of(new String[] {"kotlin", "kotlinc"}), ".bat"),
            // Clojure's tools: on PATH only (no home variable, no SDKMAN candidate).
            "clojure",
            new Tool(null, List.of("clojure"), List.of(), ".bat"),
            "lein",
            new Tool(null, List.of("lein"), List.of(), ".bat"),
            "bb",
            new Tool(null, List.of("bb"), List.of(), ".exe"));

    /** Whether {@code tool} is one this resolves. */
    public static boolean knows(String tool) {
        return tool != null && TOOLS.containsKey(tool);
    }

    /**
     * The places {@code tool} may be, in the order they are tried, before the PATH search (which
     * {@link #locate} does itself): {@code $<TOOL>_HOME/bin/<tool>} and SDKMAN's current candidates. On Windows
     * a launcher has its extension ({@code groovy.bat}, {@code scala-cli.exe}). Pure.
     */
    static List<Path> candidates(String tool, Map<String, String> env, Path userHome, boolean windows) {
        List<Path> out = new ArrayList<>();
        Tool t = tool == null ? null : TOOLS.get(tool);
        if (t == null) {
            return out;
        }
        String suffix = windows ? t.windowsSuffix() : "";
        String home = env == null || t.homeVariable() == null ? null : env.get(t.homeVariable());
        if (home != null && !home.isBlank()) {
            out.add(Path.of(home.strip(), "bin", tool + suffix));
        }
        String sdkman = env == null ? null : env.get("SDKMAN_DIR");
        Path sdkmanDir = sdkman != null && !sdkman.isBlank()
                ? Path.of(sdkman.strip())
                : userHome == null ? null : userHome.resolve(".sdkman");
        if (sdkmanDir != null) {
            for (String[] candidate : t.sdkman()) {
                out.add(sdkmanDir
                        .resolve("candidates")
                        .resolve(candidate[0])
                        .resolve("current")
                        .resolve("bin")
                        .resolve(candidate[1] + suffix));
            }
        }
        return out;
    }

    /**
     * The executable for {@code tool}: its home variable's, else the one on the augmented PATH, else SDKMAN's;
     * {@code null} when none is installed. Looks at the disk (and may wait for the login-shell PATH): off the
     * FX thread where it can be.
     */
    public static @Nullable String locate(String tool) {
        Tool spec = tool == null ? null : TOOLS.get(tool);
        if (spec == null) {
            return null;
        }
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        Path userHome = Path.of(System.getProperty("user.home", "."));
        List<Path> candidates = candidates(tool, System.getenv(), userHome, windows);
        if (!candidates.isEmpty() && Files.isExecutable(candidates.get(0)) && hasHomeVariable(spec)) {
            return candidates.get(0).toString();
        }
        for (String name : spec.pathNames()) {
            String onPath = com.editora.process.ProcessRunner.resolveExecutable(List.of(name))
                    .get(0);
            if (!onPath.equals(name)) {
                return onPath; // resolveExecutable rewrites a bare name only when it found it
            }
        }
        for (Path p : candidates) {
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p.toString();
            }
        }
        return null;
    }

    private static boolean hasHomeVariable(Tool tool) {
        String variable = tool.homeVariable();
        if (variable == null) {
            return false;
        }
        String home = System.getenv(variable);
        return home != null && !home.isBlank();
    }
}
