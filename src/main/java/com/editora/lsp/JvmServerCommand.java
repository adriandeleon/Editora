package com.editora.lsp;

import java.util.List;

/**
 * The launch command of a language server that is a jar run by {@code java}: a configured
 * {@code java -jar <jar>} runs on the JDK chosen in Settings (the one Maven, Gradle and Run use) rather than on
 * whatever {@code java} comes first on {@code PATH}. The packaged Editora runtime has no {@code java} of its own,
 * and the first one on {@code PATH} is often older than the server needs (gvy needs 17).
 *
 * <p>Pure: no I/O.
 */
public final class JvmServerCommand {

    private JvmServerCommand() {}

    /**
     * {@code command} with a leading bare {@code java} replaced by {@code jdkHome}'s {@code java}, quoted when
     * needed. Unchanged when {@code jdkHome} is blank, or when the command names its own executable.
     */
    public static String withJdk(String command, String jdkHome) {
        if (command == null || command.isBlank() || jdkHome == null || jdkHome.isBlank()) {
            return command == null ? "" : command;
        }
        List<String> argv = com.editora.run.ProgramArgs.commandLine(command);
        if (argv.isEmpty() || !"java".equals(argv.get(0))) {
            return command;
        }
        String java = com.editora.run.JdkToolchain.javaExecutable(jdkHome);
        if (java.isBlank()) {
            return command;
        }
        String rest = command.stripLeading().substring("java".length());
        return com.editora.install.InstallCatalog.quoteCommandPath(java) + rest;
    }
}
