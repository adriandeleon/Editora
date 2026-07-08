package com.editora.maven;

import java.util.List;

import com.editora.run.ProgramArgs;

/**
 * Chooses the argv prefix (before the goal/phase arguments) for launching Maven: the project's own
 * wrapper script when present (platform-appropriate), else a user override command, else the bare
 * {@code "mvn"} (resolved later by the caller against the augmented PATH via
 * {@code ProcessRunner.resolveExecutable}). Pure — the caller does the {@code Files.isExecutable} wrapper
 * probes and passes the results in as booleans.
 */
public final class MavenExecutable {

    private MavenExecutable() {}

    public static List<String> chooseArgv(
            boolean hasWrapperUnix, boolean hasWrapperWin, boolean isWindows, String overrideCommand) {
        if (isWindows) {
            if (hasWrapperWin) {
                return List.of("mvnw.cmd");
            }
        } else if (hasWrapperUnix) {
            return List.of("./mvnw");
        }
        if (overrideCommand != null && !overrideCommand.isBlank()) {
            return ProgramArgs.tokenize(overrideCommand);
        }
        return List.of("mvn");
    }
}
