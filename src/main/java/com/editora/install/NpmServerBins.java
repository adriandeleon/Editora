package com.editora.install;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.lsp.LspServerRegistry;
import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

/**
 * Where an npm-installed language server's program is found besides PATH: the bin folder of the Node.js chosen in
 * Settings → Build Tools → Node.js. Install runs {@code npm install -g} with the npm beside that Node.js (HTML review
 * 2, A105), and a global install lands in that Node.js's own folder, which need not be on PATH — so detection, Doctor,
 * the launch and Install's "already installed" all look there too, after PATH (HTML review 3, A203).
 */
public final class NpmServerBins {

    private NpmServerBins() {}

    /** The bin folder of the Node.js {@code setting} names (blank: none chosen), or null. File reads. */
    public static @Nullable Path settingsNodeBin(@Nullable String setting) {
        if (setting == null || setting.isBlank()) {
            return null;
        }
        try {
            return com.editora.run.NodeRuntimes.resolveLocal(null, null, "", setting)
                    .binDir()
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** npm beside the Node.js {@code setting} names, or null (none chosen, or no npm there). File reads. */
    public static @Nullable Path settingsNpm(@Nullable String setting) {
        return InstallService.npmBeside(settingsNodeBin(setting), windows(), Files::isRegularFile);
    }

    /**
     * {@code command}, what {@code serverId} runs on this machine, with its program found in the Settings Node.js's bin
     * folder when it is that npm-installed server's default program and PATH does not have it; otherwise as given (a
     * program on PATH resolves as before). File reads.
     */
    public static List<String> resolve(String serverId, List<String> command, @Nullable String nodeSetting) {
        if (!isDefaultNpmProgram(serverId, command) || onPath(command)) {
            return command;
        }
        Path bin = settingsNodeBin(nodeSetting);
        return bin == null ? command : ProcessRunner.resolveExecutableIn(command, bin);
    }

    /** Whether {@code serverId}'s default program is on PATH or in the Settings Node.js's bin folder. File reads. */
    public static boolean defaultProgramFound(String serverId, @Nullable String nodeSetting) {
        List<String> command = List.of(defaultProgram(serverId));
        return onPath(command) || !resolve(serverId, command, nodeSetting).equals(command);
    }

    /** The program of {@code serverId}'s default command ({@code yaml-language-server}); blank when unknown. Pure. */
    static String defaultProgram(String serverId) {
        String command = LspServerRegistry.defaultCommandFor(serverId).strip();
        int space = command.indexOf(' ');
        return space < 0 ? command : command.substring(0, space);
    }

    /**
     * Whether {@code command}'s program is a bare name, {@code serverId}'s default program, and the server is one
     * Install puts on the machine with npm. Pure.
     */
    static boolean isDefaultNpmProgram(String serverId, @Nullable List<String> command) {
        if (command == null || command.isEmpty() || InstallCatalog.npmPackage(serverId) == null) {
            return false;
        }
        String exe = command.get(0);
        return !exe.isEmpty() && exe.indexOf('/') < 0 && exe.indexOf('\\') < 0 && exe.equals(defaultProgram(serverId));
    }

    private static boolean onPath(List<String> command) {
        return !ProcessRunner.resolveExecutable(command).get(0).equals(command.get(0));
    }

    private static boolean windows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
