package com.editora.lsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The JVM options jdtls is launched with ({@link #OPTIONS}): ordered document lifecycle and completion
 * processing ({@code -Djava.lsp.joinOnCompletion=true}), Eclipse's project metadata kept in the workspace
 * instead of the user's project folder, and vscode-java's heap and GC defaults.
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

    /**
     * One JVM option and the prefix that says the user already chose its value — on the command, or in an
     * inherited {@code JDK_JAVA_OPTIONS}-style variable — in which case it is left out.
     */
    record Option(String prefix, String value) {}

    /**
     * What jdtls runs with unless the command or its environment says otherwise:
     *
     * <ul>
     *   <li>{@code joinOnCompletion}: JDT waits for pending document jobs before completion and resolve, so
     *       an auto-import is computed against the text being typed.
     *   <li>{@code generatesMetadataFilesAtProjectRoot=false}: Eclipse's {@code .project}, {@code .classpath}
     *       and {@code .settings/} go into jdtls's {@code -data} workspace instead of every imported project
     *       and module, where they showed up in {@code git status} (Java review C3). vscode-java's default.
     *   <li>The heap and GC: the launcher reserves {@code -Xms1G} and sets no cap, so a seven-class project
     *       cost 1.3–2.5 GB per server (C11). These are vscode-java's {@code java.jdt.ls.vmargs} defaults; a
     *       later {@code -Xms} wins over the launcher's.
     * </ul>
     */
    static final List<Option> OPTIONS = List.of(
            new Option("-Djava.lsp.joinOnCompletion", "-Djava.lsp.joinOnCompletion=true"),
            new Option(
                    "-Djava.import.generatesMetadataFilesAtProjectRoot",
                    "-Djava.import.generatesMetadataFilesAtProjectRoot=false"),
            new Option("-Xmx", "-Xmx2G"),
            new Option("-Xms", "-Xms100m"),
            new Option("-XX:+Use", "-XX:+UseParallelGC"),
            new Option("-XX:GCTimeRatio", "-XX:GCTimeRatio=4"),
            new Option("-XX:AdaptiveSizePolicyWeight", "-XX:AdaptiveSizePolicyWeight=90"),
            new Option("-Dsun.zip.disableMemoryMapping", "-Dsun.zip.disableMemoryMapping=true"));

    private JavaServerEnvironment() {}

    /**
     * Returns the command to start {@code serverId} with — {@code command} itself, or a copy carrying the
     * option — and, for a command the option cannot be added to, puts it in {@code environment} instead.
     * {@code command} is the resolved launch command (its first element the executable's path when known).
     */
    static List<String> configure(String serverId, List<String> command, Map<String, String> environment) {
        return configure(serverId, command, environment, JavaServerEnvironment::isJdtlsLauncher);
    }

    /**
     * As {@link #configure(String, List, Map)}, with {@code isLauncher} deciding whether an executable is
     * jdtls's own launcher script — on a remote host that is asked of the host, not of this machine's disk.
     */
    static List<String> configure(
            String serverId,
            List<String> command,
            Map<String, String> environment,
            java.util.function.Predicate<String> isLauncher) {
        if (!"java".equals(serverId) || command.isEmpty()) return command;
        // A command or inherited JVM option is an intentional override, including an explicit false.
        List<String> missing = new ArrayList<>();
        for (Option option : OPTIONS) {
            if (!chosen(option.prefix(), command, environment)) {
                missing.add(option.value());
            }
        }
        if (missing.isEmpty()) return command;
        String executable = command.get(0);
        boolean java = isJavaExecutable(executable);
        boolean launcher = !java && isLauncherName(executable) && isLauncher.test(executable);
        if (java || launcher) {
            List<String> extended = new ArrayList<>(command);
            for (int i = 0; i < missing.size(); i++) {
                // A JVM option goes before -jar / the main class; the launcher forwards --jvm-arg=.
                extended.add(1 + i, java ? missing.get(i) : "--jvm-arg=" + missing.get(i));
            }
            return List.copyOf(extended);
        }
        // Anything else: only the environment reaches the JVM behind a wrapper we know nothing about. Only the
        // option jdtls needs to be correct goes there — every JVM jdtls starts inherits this variable, a debugged
        // program included, and heap or GC flags meant for the server would reach it too.
        String join = OPTIONS.get(0).value();
        if (missing.contains(join)) {
            String options = environment.getOrDefault("JDK_JAVA_OPTIONS", "");
            environment.put("JDK_JAVA_OPTIONS", options.isBlank() ? join : options + " " + join);
        }
        return command;
    }

    private static boolean chosen(String prefix, List<String> command, Map<String, String> environment) {
        if (command.stream().anyMatch(arg -> arg.contains(prefix))) return true;
        for (String key : List.of("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")) {
            if (environment.getOrDefault(key, "").contains(prefix)) return true;
        }
        return false;
    }

    /**
     * {@code command} with {@code --java-executable=javaExecutable} when it is jdtls's own launcher and does not
     * name one already; otherwise {@code command} unchanged. The launcher otherwise runs {@code $JAVA_HOME/bin/java}
     * (or {@code java} on the PATH) and refuses anything older than 21 (Java review C5).
     */
    static List<String> withJavaExecutable(
            List<String> command, @Nullable String javaExecutable, java.util.function.Predicate<String> isLauncher) {
        if (javaExecutable == null
                || command.isEmpty()
                || command.stream().anyMatch(arg -> arg.startsWith("--java-executable"))
                || !isLauncherName(command.get(0))
                || !isLauncher.test(command.get(0))) {
            return command;
        }
        List<String> extended = new ArrayList<>(command);
        extended.add(1, "--java-executable=" + javaExecutable);
        return List.copyOf(extended);
    }

    private static String fileName(String executable) {
        int slash = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        return executable.substring(slash + 1).toLowerCase(Locale.ROOT);
    }

    /** Named like jdtls's launcher script; whether it is one is for the caller's predicate to say. */
    static boolean isLauncherName(String executable) {
        String name = fileName(executable);
        return name.equals("jdtls") || name.equals("jdtls.bat") || name.equals("jdtls.py");
    }

    /**
     * Whether {@code executable}, as the host of remote {@code root} finds it on its {@code PATH}, is the
     * launcher jdtls ships: a {@code jdtls.py} beside its real file. Runs a command: off the FX thread.
     */
    static boolean isRemoteJdtlsLauncher(Path root, String executable) {
        com.editora.process.ProcessRunner.Result r = com.editora.process.ProcessRunner.run(
                root,
                java.time.Duration.ofSeconds(10),
                List.of(
                        "sh",
                        "-c",
                        "p=$(command -v \"$1\") || exit 1; r=$(readlink -f \"$p\" 2>/dev/null || printf '%s' \"$p\");"
                                + " [ -f \"${r%/*}/jdtls.py\" ]",
                        "sh",
                        executable));
        return r.ok();
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
        if (!isLauncherName(executable)) {
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
