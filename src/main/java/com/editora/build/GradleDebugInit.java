package com.editora.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Debugs a Gradle task's forked JVM on a port the system picks. {@code --debug-jvm} always listens on 5005 —
 * "address in use" beside any other debug run, and every fork of a test task on the same port — and Gradle has no
 * command-line option for another port. An init script ({@code --init-script}) adds {@link BuildDebug#JDWP_AGENT}
 * ({@code address=127.0.0.1:0}) to the JVM arguments of the named tasks that fork a JVM (a {@code JavaExec} such
 * as {@code run}/{@code bootRun}, a {@code Test}); each JVM then prints its real port in the JDWP banner, which
 * the attach reads, as for Maven.
 *
 * <p>Plain JVM arguments rather than {@code JavaForkOptions.debugOptions}: that exists only from Gradle 7.6, and
 * takes no port the system picks. {@code tasks.matching}/{@code all}, {@code jvmArgs} and
 * {@code outputs.upToDateWhen} are in every Gradle Editora supports. The tasks are never up to date while being
 * debugged — a test task Gradle thought had already run would skip the JVM the debugger waits for.
 */
public final class GradleDebugInit {

    private GradleDebugInit() {}

    /** The init script that debugs the forked JVMs of the tasks called {@code taskNames}. Pure. */
    public static String script(Collection<String> taskNames) {
        StringBuilder names = new StringBuilder();
        for (String name : taskNames) {
            if (!names.isEmpty()) {
                names.append(", ");
            }
            names.append(groovyString(name));
        }
        return "// Editora: debug these tasks' forked JVMs on a port the system picks (--debug-jvm is always 5005).\n"
                + "def editoraDebugTasks = [" + names + "] as Set\n"
                + "allprojects {\n"
                + "    tasks.matching { it.name in editoraDebugTasks && it instanceof JavaForkOptions }.all { task ->\n"
                + "        task.jvmArgs(" + groovyString(BuildDebug.JDWP_AGENT) + ")\n"
                + "        task.outputs.upToDateWhen { false }\n"
                + "    }\n"
                + "}\n";
    }

    /** {@code s} as a single-quoted Groovy string. Pure. */
    static String groovyString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /**
     * Writes {@link #script} to a temporary file (removed when Editora exits) and returns it, or null when it
     * cannot be written — the caller then falls back to {@code --debug-jvm}.
     */
    public static @Nullable Path write(Collection<String> taskNames) {
        try {
            Path file = Files.createTempFile("editora-debug-", ".gradle");
            Files.writeString(file, script(taskNames), StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The arguments that apply {@code initScript} to a Gradle run. Pure. */
    public static List<String> args(Path initScript) {
        return List.of("--init-script", initScript.toString());
    }
}
