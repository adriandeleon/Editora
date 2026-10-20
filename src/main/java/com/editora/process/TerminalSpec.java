package com.editora.process;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

/**
 * A shell for the integrated terminal, on a target: where it starts and the size of the pseudo-terminal it is
 * given. Kept apart from {@link ExecSpec}, which every tool command uses and which never asks for a PTY: a PTY
 * on a language server's or debug adapter's channel would corrupt its framing (ADR 0013, ADR 0014).
 *
 * @param workingDir where the shell starts, a path on the target's file system; null for the account's home
 * @param shellIntegration the folder holding Editora's bash/zsh integration scripts, as the target names it; null
 *     for none (the setting is off, or they could not be installed there)
 * @param command a program to run instead of the login shell (Run in Terminal, a debugged program), its paths as
 *     the target names them; empty for the shell
 * @param env variables to set for {@code command}, over the login environment; a null value unsets one
 */
public record TerminalSpec(
        Path workingDir,
        int columns,
        int rows,
        @Nullable String shellIntegration,
        java.util.List<String> command,
        java.util.Map<String, String> env) {

    public TerminalSpec {
        columns = Math.max(1, columns);
        rows = Math.max(1, rows);
        command = command == null ? java.util.List.of() : java.util.List.copyOf(command);
        env = env == null
                ? java.util.Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(env));
    }

    public TerminalSpec(Path workingDir, int columns, int rows, @Nullable String shellIntegration) {
        this(workingDir, columns, rows, shellIntegration, java.util.List.of(), java.util.Map.of());
    }

    public TerminalSpec(Path workingDir, int columns, int rows) {
        this(workingDir, columns, rows, null);
    }

    /** A program rather than the login shell. */
    public static TerminalSpec program(
            Path workingDir, int columns, int rows, java.util.List<String> command, java.util.Map<String, String> env) {
        return new TerminalSpec(workingDir, columns, rows, null, command, env);
    }

    public boolean runsProgram() {
        return !command.isEmpty();
    }
}
