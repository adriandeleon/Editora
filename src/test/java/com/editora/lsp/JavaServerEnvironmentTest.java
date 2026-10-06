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

        assertEquals(List.of(jdtls, "--jvm-arg=" + OPTION, "-data", "/ws"), command);
        assertEquals("-Xmx2g", env.get("JDK_JAVA_OPTIONS"));
        assertEquals(command, JavaServerEnvironment.configure("java", command, env), "not added twice");
    }

    @Test
    void theWindowsLauncherAndASymlinkToTheLauncherAreRecognized() throws Exception {
        Path jdtls = launcher();
        Path bat = Files.writeString(jdtls.resolveSibling("jdtls.bat"), "python \"%~dp0/jdtls\" %*\r\n");
        var env = new HashMap<String, String>();
        assertEquals(
                List.of(bat.toString(), "--jvm-arg=" + OPTION),
                JavaServerEnvironment.configure("java", List.of(bat.toString()), env));

        Path onPath = Files.createDirectories(dir.resolve("usr/bin")).resolve("jdtls");
        try {
            Files.createSymbolicLink(onPath, jdtls);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // no symlinks here (Windows without the privilege)
        }
        assertEquals(
                List.of(onPath.toString(), "--jvm-arg=" + OPTION),
                JavaServerEnvironment.configure("java", List.of(onPath.toString()), env));
        assertTrue(env.isEmpty());
    }

    @Test
    void aDirectJavaCommandGetsTheOptionBeforeItsOtherArguments() {
        var env = new HashMap<String, String>();
        for (String java : List.of("java", "/opt/jdk-25/bin/java", "C:\\jdk\\bin\\java.exe")) {
            assertEquals(
                    List.of(java, OPTION, "-Xmx1g", "-jar", "org.eclipse.equinox.launcher_1.7.0.jar", "-data", "/ws"),
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
        List<String> explicit = List.of(launcher().toString(), "--jvm-arg=-Djava.lsp.joinOnCompletion=false");
        assertEquals(explicit, JavaServerEnvironment.configure("java", explicit, env));
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
            assertEquals(command, JavaServerEnvironment.configure("java", command, env));
            assertEquals(before, env);
        }
    }
}
