package com.editora.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.run.ProgramArgs;

/**
 * Turns a "command / path" setting (git, gh) into the argv prefix that is actually started. Shared by the
 * services and the Doctor screen, so both check exactly the command that runs. Pure apart from one
 * {@code Files.isRegularFile} look — unit-tested.
 */
public final class ConfiguredCommand {

    private ConfiguredCommand() {}

    /**
     * The argv prefix for {@code configured}; blank ⇒ {@code [fallback]} (resolved on PATH).
     *
     * <p>A value that <em>is</em> an existing file is taken whole — Settings has a Browse… button that writes
     * the raw absolute path, and {@code C:\Program Files\GitHub CLI\gh.exe} split at the spaces could never
     * start. Anything else is tokenized quote-aware ({@link ProgramArgs#tokenize}), so a wrapper command with
     * arguments ({@code flatpak-spawn --host gh}) or a hand-quoted path still works.
     */
    public static List<String> tokens(String configured, String fallback) {
        if (configured == null || configured.isBlank()) {
            return List.of(fallback);
        }
        return argv(configured.strip());
    }

    /**
     * The argv of a non-blank command line or path {@code raw}: itself whole when it names an existing file,
     * else its quote-aware tokens. Whether it is a file is asked only when the answer matters, that is when the
     * tokens are not {@code raw} itself ({@code dot}, {@code /usr/bin/git} need no look at the disk), and the
     * answer is kept for the run ({@link #isFileKept}).
     */
    public static List<String> argv(String raw) {
        List<String> tokens = ProgramArgs.tokenize(raw);
        if (tokens.size() == 1 && tokens.get(0).equals(raw)) {
            return List.of(raw); // the same either way
        }
        if (isFileKept(raw)) {
            return List.of(raw);
        }
        return tokens.isEmpty() ? List.of(raw) : List.copyOf(tokens);
    }

    /**
     * Whether {@code raw} names an existing regular file, as first looked up in this run: a window's set-up and
     * every settings apply build the configured tools' command lines on the FX thread. The launch looks up the
     * configured ones on its config-prefetch thread ({@link #prepare}).
     */
    public static boolean isFileKept(String raw) {
        return IS_FILE.get(raw);
    }

    /** Looks up, on the calling thread, whether each of {@code configured} that would need it names a file. */
    public static void prepare(java.util.Collection<String> configured) {
        for (String c : configured) {
            if (c != null && !c.isBlank()) {
                String raw = c.strip();
                List<String> tokens = ProgramArgs.tokenize(raw);
                if (!(tokens.size() == 1 && tokens.get(0).equals(raw))) {
                    IS_FILE.prepare(raw);
                }
            }
        }
    }

    private static final com.editora.perf.ProbeCache<String, Boolean> IS_FILE =
            new com.editora.perf.ProbeCache<>("tool-path-is-file", ConfiguredCommand::isRegularFile);

    private static boolean isRegularFile(String raw) {
        try {
            return Files.isRegularFile(Path.of(raw));
        } catch (RuntimeException notAPath) {
            return false; // not a valid path on this platform: a command line
        }
    }
}
