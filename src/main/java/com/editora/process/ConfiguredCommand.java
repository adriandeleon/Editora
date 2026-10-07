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
        String raw = configured.strip();
        try {
            if (Files.isRegularFile(Path.of(raw))) {
                return List.of(raw);
            }
        } catch (RuntimeException notAPath) {
            // Not a valid path on this platform: a command line.
        }
        List<String> tokens = ProgramArgs.tokenize(raw);
        return tokens.isEmpty() ? List.of(raw) : List.copyOf(tokens);
    }
}
