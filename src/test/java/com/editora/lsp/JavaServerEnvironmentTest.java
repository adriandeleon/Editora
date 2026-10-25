package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where jdtls gets {@code -Djava.lsp.joinOnCompletion=true} from: its command line when Editora knows the
 * launcher, the {@code JDK_JAVA_OPTIONS} environment variable only for a command it cannot extend. The
 * environment reaches every JVM jdtls starts — a debugged program printed "NOTE: Picked up
 * JDK_JAVA_OPTIONS" and ran with the property — so it is the fallback, not the rule.
 */
class JavaServerEnvironmentTest {
    private static final String OPTION = "-Djava.lsp.joinOnCompletion=true";

    /** Every option jdtls gets by default, except those whose prefix is in {@code chosen}. */
    private static List<String> options(String... chosen) {
        return JavaServerEnvironment.OPTIONS.stream()
                .filter(o -> java.util.Arrays.stream(chosen).noneMatch(c -> c.startsWith(o.prefix())))
                .map(JavaServerEnvironment.Option::value)
                .toList();
    }

    private static List<String> launcherArgs(String... chosen) {
        return options(chosen).stream().map(o -> "--jvm-arg=" + o).toList();
    }

    private static List<String> concat(List<String> head, List<String> middle, List<String> tail) {
        var out = new java.util.ArrayList<String>(head);
        out.addAll(middle);
        out.addAll(tail);
        return out;
    }

    @TempDir
    Path dir;

    /** A jdtls installation's {@code bin/}: the launcher with the {@code jdtls.py} it runs beside it. */
    private Path launcher() throws Exception {
        Path bin = Files.createDirectories(dir.resolve("jdtls/bin"));
        Files.writeString(bin.resolve("jdtls.py"), "# parses --jvm-arg\n");
        return Files.writeString(bin.resolve("jdtls"), "#!/usr/bin/env python3\n");
    }

    @Test
    void theJdtlsLauncherGetsTheOptionAsAJvmArgumentAndTheEnvironmentIsLeftAlone() throws Exception {
        String jdtls = launcher().toString();
        var env = new HashMap<String, String>();
        env.put("JDK_JAVA_OPTIONS", "-Xmx2g"); // the user's own: neither replaced nor added to

        List<String> command = JavaServerEnvironment.configure("java", List.of(jdtls, "-data", "/ws"), env);

        assertEquals(concat(List.of(jdtls), launcherArgs("-Xmx2g"), List.of("-data", "/ws")), command);
        assertEquals("-Xmx2g", env.get("JDK_JAVA_OPTIONS"));
        assertEquals(command, JavaServerEnvironment.configure("java", command, env), "not added twice");
    }

    @Test
    void theWindowsLauncherAndASymlinkToTheLauncherAreRecognized() throws Exception {
        Path jdtls = launcher();
        Path bat = Files.writeString(jdtls.resolveSibling("jdtls.bat"), "python \"%~dp0/jdtls\" %*\r\n");
        var env = new HashMap<String, String>();
        assertEquals(
                concat(List.of(bat.toString()), launcherArgs(), List.of()),
                JavaServerEnvironment.configure("java", List.of(bat.toString()), env));

        Path onPath = Files.createDirectories(dir.resolve("usr/bin")).resolve("jdtls");
        try {
            Files.createSymbolicLink(onPath, jdtls);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // no symlinks here (Windows without the privilege)
        }
        assertEquals(
                concat(List.of(onPath.toString()), launcherArgs(), List.of()),
                JavaServerEnvironment.configure("java", List.of(onPath.toString()), env));
        assertTrue(env.isEmpty());
    }

    @Test
    void aDirectJavaCommandGetsTheOptionBeforeItsOtherArguments() {
        var env = new HashMap<String, String>();
        for (String java : List.of("java", "/opt/jdk-25/bin/java", "C:\\jdk\\bin\\java.exe")) {
            assertEquals(
                    concat(
                            List.of(java),
                            options("-Xmx1g"),
                            List.of("-Xmx1g", "-jar", "org.eclipse.equinox.launcher_1.7.0.jar", "-data", "/ws")),
                    JavaServerEnvironment.configure(
                            "java",
                            List.of(java, "-Xmx1g", "-jar", "org.eclipse.equinox.launcher_1.7.0.jar", "-data", "/ws"),
                            env));
        }
        assertTrue(env.isEmpty(), "nothing a debugged program could inherit");
    }

    @Test
    void aWrapperEditoraCannotExtendFallsBackToTheEnvironmentWithoutReplacingExistingJvmOptions() throws Exception {
        // A bare name that is not on PATH, a wrapper script of the user's, and a file merely named "jdtls"
        // (a distribution's shell wrapper, with no jdtls.py beside it): none is known to forward --jvm-arg.
        Path lookAlike =
                Files.writeString(Files.createDirectories(dir.resolve("wrap")).resolve("jdtls"), "#!/bin/sh\n");
        for (List<String> wrapper : List.of(
                List.of("jdtls"), List.of("/home/me/bin/start-jdtls.sh", "--fast"), List.of(lookAlike.toString()))) {
            var env = new HashMap<String, String>();
            env.put("JDK_JAVA_OPTIONS", "-Xmx2g");
            assertEquals(wrapper, JavaServerEnvironment.configure("java", wrapper, env), "the command is untouched");
            assertEquals("-Xmx2g " + OPTION, env.get("JDK_JAVA_OPTIONS"));
            JavaServerEnvironment.configure("java", wrapper, env);
            assertEquals("-Xmx2g " + OPTION, env.get("JDK_JAVA_OPTIONS"), "not added twice");
        }
        var empty = new HashMap<String, String>();
        JavaServerEnvironment.configure("java", List.of("my-jdtls"), empty);
        assertEquals(OPTION, empty.get("JDK_JAVA_OPTIONS"));
    }

    @Test
    void respectsCommandAndEnvironmentOverridesAndOtherServers() throws Exception {
        var env = new HashMap<String, String>();
        String jdtls = launcher().toString();
        List<String> explicit = List.of(jdtls, "--jvm-arg=-Djava.lsp.joinOnCompletion=false");
        assertEquals(
                concat(List.of(jdtls), launcherArgs("-Djava.lsp.joinOnCompletion"), explicit.subList(1, 2)),
                JavaServerEnvironment.configure("java", explicit, env),
                "an explicit false is kept; the other options still come");
        assertTrue(env.isEmpty());
        assertEquals(List.of("pylsp"), JavaServerEnvironment.configure("python", List.of("pylsp"), env));
        assertEquals(
                List.of("java", "Server"), JavaServerEnvironment.configure("python", List.of("java", "Server"), env));
        assertTrue(env.isEmpty());
        for (String key : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
            env.clear();
            env.put(key, "-Djava.lsp.joinOnCompletion=false");
            var before = new HashMap<>(env);
            List<String> command = List.of("java", "-jar", "server.jar");
            assertEquals(
                    concat(List.of("java"), options("-Djava.lsp.joinOnCompletion"), command.subList(1, 3)),
                    JavaServerEnvironment.configure("java", command, env));
            assertEquals(before, env);
        }
    }

    /**
     * Eclipse's project files stay in jdtls's workspace (Java review C3), and the heap is capped (C11); a user's
     * own {@code -Xmx} or GC choice wins.
     */
    @Test
    void metadataStaysOutOfTheProjectAndTheHeapIsCappedUnlessChosen() throws Exception {
        String jdtls = launcher().toString();
        var env = new HashMap<String, String>();
        List<String> command = JavaServerEnvironment.configure("java", List.of(jdtls), env);
        assertTrue(command.contains("--jvm-arg=-Djava.import.generatesMetadataFilesAtProjectRoot=false"));
        assertTrue(command.contains("--jvm-arg=-Xmx2G"));
        assertTrue(command.contains("--jvm-arg=-XX:+UseParallelGC"));

        List<String> own = JavaServerEnvironment.configure(
                "java", List.of(jdtls, "--jvm-arg=-Xmx6G", "--jvm-arg=-XX:+UseZGC"), env);
        assertFalse(own.contains("--jvm-arg=-Xmx2G"));
        assertFalse(own.contains("--jvm-arg=-XX:+UseParallelGC"));
        assertTrue(own.contains("--jvm-arg=-Xmx6G"));
    }

    /** A wrapper only gets the option jdtls needs through the environment: heap flags would reach debuggees. */
    @Test
    void onlyTheJoinOptionGoesThroughTheEnvironment() {
        var env = new HashMap<String, String>();
        JavaServerEnvironment.configure("java", List.of("my-jdtls"), env);
        assertEquals(OPTION, env.get("JDK_JAVA_OPTIONS"));
    }

    /** jdtls's launcher refuses a Java older than 21; Editora can name one it found (Java review C5). */
    @Test
    void theLauncherCanBeToldWhichJavaToRunOn() throws Exception {
        String jdtls = launcher().toString();
        assertEquals(
                List.of(jdtls, "--java-executable=/jdk21/bin/java", "-data", "/ws"),
                JavaServerEnvironment.withJavaExecutable(
                        List.of(jdtls, "-data", "/ws"), "/jdk21/bin/java", JavaServerEnvironment::isJdtlsLauncher));
        List<String> named = List.of(jdtls, "--java-executable=/mine/java");
        assertEquals(named, JavaServerEnvironment.withJavaExecutable(named, "/jdk21/bin/java", e -> true));
        assertEquals(List.of(jdtls), JavaServerEnvironment.withJavaExecutable(List.of(jdtls), null, e -> true));
        assertEquals(
                List.of("java", "-jar", "x.jar"),
                JavaServerEnvironment.withJavaExecutable(
                        List.of("java", "-jar", "x.jar"), "/jdk21/bin/java", e -> true));
    }
}
