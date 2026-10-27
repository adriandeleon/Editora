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

    /**
     * {@code argv} with {@code -Duser.home=<home>} after its {@code java} launcher, so a JVM server keeps what it
     * stores under the home Editora runs with rather than the passwd one the JVM would read (lemminx-maven's
     * {@code ~/.m2/.lemminx-maven}, review D14). Unchanged when {@code argv} does not start with a {@code java}
     * program, already sets {@code user.home}, or {@code home} is blank.
     */
    public static List<String> withUserHome(List<String> argv, String home) {
        if (argv == null || argv.isEmpty() || home == null || home.isBlank() || !isJava(argv.get(0))) {
            return argv == null ? List.of() : argv;
        }
        for (String arg : argv) {
            if (arg.startsWith("-Duser.home=")) {
                return argv;
            }
        }
        List<String> out = new java.util.ArrayList<>(argv.size() + 1);
        out.add(argv.get(0));
        out.add("-Duser.home=" + home);
        out.addAll(argv.subList(1, argv.size()));
        return List.copyOf(out);
    }

    /** Whether {@code program} is a {@code java} launcher ({@code java}, {@code /…/bin/java}, {@code java.exe}). */
    static boolean isJava(String program) {
        if (program == null) {
            return false;
        }
        String p = program.replace('\\', '/');
        String name = p.substring(p.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        return name.equals("java") || name.equals("java.exe") || name.equals("javaw.exe");
    }
}
