package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the command-line tool that runs a JVM language's files — {@code groovy}, {@code kotlinc} — on this machine.
 *
 * <p>The tool's own home variable comes first ({@code GROOVY_HOME}: whoever set it chose that install), then the
 * {@linkplain com.editora.process.ProcessRunner#augmentedPath augmented PATH} (the login shell's, which already
 * holds SDKMAN, asdf, mise and Homebrew when the profile sets them up), then SDKMAN's {@code current} candidate
 * for a profile that only sources SDKMAN in interactive shells (its candidate is named for the language:
 * {@code kotlinc} is in {@code candidates/kotlin}).
 *
 * <p>{@link #candidates} is pure; {@link #locate} looks at the disk.
 */
public final class JvmTools {

    private JvmTools() {}

    /** The tools {@link #locate} knows, with the variable naming each one's install. */
    private static final Map<String, String> HOME_VARIABLES = Map.of("groovy", "GROOVY_HOME", "kotlinc", "KOTLIN_HOME");

    /** SDKMAN candidates named otherwise than their tool. */
    private static final Map<String, String> SDKMAN_CANDIDATES = Map.of("kotlinc", "kotlin");

    /** Whether {@code tool} is one this resolves. */
    public static boolean knows(String tool) {
        return tool != null && HOME_VARIABLES.containsKey(tool);
    }

    /**
     * The places {@code tool} may be, in the order they are tried, before the PATH search (which
     * {@link #locate} does itself): {@code $<TOOL>_HOME/bin/<tool>} and SDKMAN's current candidate. On Windows
     * a launcher is a {@code .bat}. Pure.
     */
    static List<Path> candidates(String tool, Map<String, String> env, Path userHome, boolean windows) {
        List<Path> out = new ArrayList<>();
        if (!knows(tool)) {
            return out;
        }
        String file = windows ? tool + ".bat" : tool;
        String home = env == null ? null : env.get(HOME_VARIABLES.get(tool));
        if (home != null && !home.isBlank()) {
            out.add(Path.of(home.strip(), "bin", file));
        }
        String sdkman = env == null ? null : env.get("SDKMAN_DIR");
        Path sdkmanDir = sdkman != null && !sdkman.isBlank()
                ? Path.of(sdkman.strip())
                : userHome == null ? null : userHome.resolve(".sdkman");
        if (sdkmanDir != null) {
            out.add(sdkmanDir
                    .resolve("candidates")
                    .resolve(SDKMAN_CANDIDATES.getOrDefault(tool, tool))
                    .resolve("current")
                    .resolve("bin")
                    .resolve(file));
        }
        return out;
    }

    /**
     * The executable for {@code tool}: its home variable's, else the one on the augmented PATH, else SDKMAN's;
     * {@code null} when none is installed. Looks at the disk (and may wait for the login-shell PATH): off the
     * FX thread where it can be.
     */
    public static String locate(String tool) {
        if (!knows(tool)) {
            return null;
        }
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        Path userHome = Path.of(System.getProperty("user.home", "."));
        List<Path> candidates = candidates(tool, System.getenv(), userHome, windows);
        if (!candidates.isEmpty() && Files.isExecutable(candidates.get(0)) && hasHomeVariable(tool)) {
            return candidates.get(0).toString();
        }
        String onPath = com.editora.process.ProcessRunner.resolveExecutable(List.of(tool))
                .get(0);
        if (!onPath.equals(tool)) {
            return onPath; // resolveExecutable rewrites a bare name only when it found it
        }
        for (Path p : candidates) {
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p.toString();
            }
        }
        return null;
    }

    private static boolean hasHomeVariable(String tool) {
        String home = System.getenv(HOME_VARIABLES.get(tool));
        return home != null && !home.isBlank();
    }
}
