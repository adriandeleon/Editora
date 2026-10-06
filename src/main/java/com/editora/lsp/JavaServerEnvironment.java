package com.editora.lsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Launch settings for ordered JDT document lifecycle/completion processing: jdtls must run with
 * {@code -Djava.lsp.joinOnCompletion=true}.
 *
 * <p>The option goes on the <b>command line</b> wherever Editora knows how to put it there — the jdtls
 * launcher script ({@code --jvm-arg=…}) and a direct {@code java …} command ({@code -D…} right after the
 * executable). Only a command of another shape (a user's own wrapper script, whose arguments Editora cannot
 * safely extend) still gets it through the {@code JDK_JAVA_OPTIONS} environment variable. The environment is
 * the fallback rather than the rule because it does not stop at jdtls: every JVM jdtls starts inherits it —
 * a program launched by java-debug printed "NOTE: Picked up JDK_JAVA_OPTIONS: …" on its stderr and ran with
 * a system property it never asked for.
 */
final class JavaServerEnvironment {
    private static final String JOIN = "-Djava.lsp.joinOnCompletion";
    private static final String OPTION = JOIN + "=true";

    private JavaServerEnvironment() {}

    /**
     * Returns the command to start {@code serverId} with — {@code command} itself, or a copy carrying the
     * option — and, for a command the option cannot be added to, puts it in {@code environment} instead.
     * {@code command} is the resolved launch command (its first element the executable's path when known).
     */
    static List<String> configure(String serverId, List<String> command, Map<String, String> environment) {
        if (!"java".equals(serverId) || command.isEmpty()) return command;
        // A command or inherited JVM option is an intentional override, including an explicit false.
        if (command.stream().anyMatch(arg -> arg.contains(JOIN))) return command;
        for (String key : List.of("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")) {
            if (environment.getOrDefault(key, "").contains(JOIN)) return command;
        }
        // JDT joins pending lifecycle jobs inside the server process; the editor never waits on the FX thread.
        String executable = command.get(0);
        String argument = isJavaExecutable(executable)
                ? OPTION // a JVM option, so before -jar / the main class
                : isJdtlsLauncher(executable) ? "--jvm-arg=" + OPTION : null;
        if (argument != null) {
            List<String> extended = new ArrayList<>(command);
            extended.add(1, argument);
            return List.copyOf(extended);
        }
        // Anything else: only the environment reaches the JVM behind a wrapper we know nothing about.
        String options = environment.getOrDefault("JDK_JAVA_OPTIONS", "");
        environment.put("JDK_JAVA_OPTIONS", options.isBlank() ? OPTION : options + " " + OPTION);
        return command;
    }

    private static String fileName(String executable) {
        int slash = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        return executable.substring(slash + 1).toLowerCase(Locale.ROOT);
    }

    /** {@code java} itself, by any path: a {@code -D} right after it is always a JVM option. */
    static boolean isJavaExecutable(String executable) {
        String name = fileName(executable);
        return name.equals("java") || name.equals("java.exe") || name.equals("javaw") || name.equals("javaw.exe");
    }

    /**
     * The launcher script jdtls ships ({@code bin/jdtls}, {@code jdtls.bat}; what Editora's installer puts
     * in place), told from a look-alike by the {@code jdtls.py} it runs sitting beside it: that script
     * parses {@code --jvm-arg=} and forwards everything else. A file merely <em>named</em> {@code jdtls} — a
     * distribution's or the user's own wrapper — may not, so it is not extended.
     */
    static boolean isJdtlsLauncher(String executable) {
        String name = fileName(executable);
        if (!(name.equals("jdtls") || name.equals("jdtls.bat") || name.equals("jdtls.py"))) {
            return false;
        }
        try {
            Path real = Path.of(executable).toRealPath(); // a symlink on PATH points at the real bin/
            Path dir = real.getParent();
            return dir != null && Files.isRegularFile(dir.resolve("jdtls.py"));
        } catch (IOException | RuntimeException e) {
            return false; // not a file we can see (a bare name not on PATH): leave it alone
        }
    }
}
