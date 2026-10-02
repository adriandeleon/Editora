package com.editora.build;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.process.ChildEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The environment a build runs in. Maven, Gradle and {@code javac} are JVMs; forced into {@code LC_ALL=C} (the
 * environment meant for output Editora parses) they decode file names as ASCII, so a project under a path
 * with ñ/é could not be built at all.
 */
class BuildServiceEnvTest {

    @Test
    void aBuildInheritsTheUsersLocaleNotTheParseStableOne(@TempDir Path dir) {
        ProcessBuilder pb = BuildService.processBuilder(dir, List.of("mvn", "package"), Map.of());

        assertTrue(
                ChildEnv.inheritsUserLocale(pb.environment()),
                "LC_ALL=" + pb.environment().get("LC_ALL"));
        assertEquals(dir.toFile(), pb.directory());
    }

    @Test
    void theSelectedToolchainIsAppliedOnTop(@TempDir Path dir) {
        ProcessBuilder pb =
                BuildService.processBuilder(dir, List.of("mvn", "package"), Map.of("JAVA_HOME", "/opt/jdk-25"));

        assertEquals("/opt/jdk-25", pb.environment().get("JAVA_HOME"));
        assertTrue(ChildEnv.inheritsUserLocale(pb.environment()));
    }

    @Test
    void noOverridesAreAccepted(@TempDir Path dir) {
        assertTrue(ChildEnv.inheritsUserLocale(BuildService.processBuilder(dir, List.of("gradle", "build"), null)
                .environment()));
    }
}
