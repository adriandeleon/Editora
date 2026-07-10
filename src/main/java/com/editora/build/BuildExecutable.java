package com.editora.build;

import java.util.List;

import com.editora.run.ProgramArgs;

/**
 * Chooses the argv prefix (before a task's own arguments) for launching a build tool, generalizing the old
 * {@code MavenExecutable.chooseArgv}: the project's own wrapper when present (Maven's {@code mvnw}, Gradle's
 * {@code gradlew}), else a user override command (tokenized), else the tool's bare default command (resolved
 * later against the augmented PATH via {@code ProcessRunner.resolveExecutable}). Pure — the caller does the
 * {@code Files.isRegularFile} wrapper probe (and, for npm, the package-manager detection) and passes the
 * resolved wrapper argv + default command in.
 *
 * <ul>
 *   <li>Maven/Gradle: {@code wrapperArgv} = {@code ["./mvnw"]}/{@code ["gradlew.cmd"]} when the wrapper file
 *       exists, else empty; default {@code "mvn"}/{@code "gradle"}.
 *   <li>npm: {@code wrapperArgv} empty; default = the detected package manager ({@code npm}/{@code yarn}/
 *       {@code pnpm}/{@code bun}).
 *   <li>Cargo/Go: {@code wrapperArgv} empty; default {@code "cargo"}/{@code "go"}.
 * </ul>
 */
public final class BuildExecutable {

    private BuildExecutable() {}

    public static List<String> resolve(List<String> wrapperArgv, String override, String defaultCommand) {
        if (wrapperArgv != null && !wrapperArgv.isEmpty()) {
            return List.copyOf(wrapperArgv);
        }
        if (override != null && !override.isBlank()) {
            return ProgramArgs.tokenize(override);
        }
        return List.of(defaultCommand);
    }
}
