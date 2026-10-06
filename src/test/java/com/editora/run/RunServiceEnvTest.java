package com.editora.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.process.ChildEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The environment a Run hands the user's program. It used to be the parse-stable {@code LC_ALL=C} one, under
 * which a JDK 25 child reports {@code stdout.encoding=ANSI_X3.4-1968} and {@code java año/H.java} fails with
 * {@code invalid path for source file: a??o/H.java} — so a project under a path with ñ/é could not be run.
 */
class RunServiceEnvTest {

    @Test
    void theUsersProgramInheritsTheirLocaleNotTheParseStableOne(@TempDir Path dir) {
        ProcessBuilder pb = RunService.processBuilder(dir, List.of("java", "Main.java"), Map.of());

        assertTrue(
                ChildEnv.inheritsUserLocale(pb.environment()),
                "LC_ALL=" + pb.environment().get("LC_ALL"));
        assertEquals(dir.toAbsolutePath().toFile(), pb.directory());
        assertEquals(List.of("java", "Main.java"), pb.command());
    }

    @Test
    void aRunConfigurationsVariablesAreAppliedOnTop(@TempDir Path dir) {
        ProcessBuilder pb = RunService.processBuilder(
                dir,
                List.of("java", "Main.java"),
                Map.of("GREETING", "hola", "LC_ALL", "es_MX.UTF-8", "PATH", "/opt/x"));

        assertEquals("hola", pb.environment().get("GREETING"));
        assertEquals("es_MX.UTF-8", pb.environment().get("LC_ALL"), "a config may still choose a locale");
        assertEquals("/opt/x", pb.environment().get("PATH"), "and may replace PATH");
    }

    /** A debug adapter's runInTerminal spells "unset this variable" as a null value. */
    @Test
    void aNullValueRemovesTheVariableFromTheChildsEnvironment(@TempDir Path dir) {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("GREETING", "hola");
        env.put("PATH", null);
        env.put("NEVER_SET_ANYWHERE", null);

        ProcessBuilder pb = RunService.processBuilder(dir, List.of("java", "Main.java"), env);

        assertEquals("hola", pb.environment().get("GREETING"));
        assertFalse(pb.environment().containsKey("PATH"), "unset, the augmented PATH included");
        assertFalse(pb.environment().containsKey("NEVER_SET_ANYWHERE"));
    }

    @Test
    void noWorkingDirectoryAndNoVariablesAreBothAccepted() {
        ProcessBuilder pb = RunService.processBuilder(null, List.of("python3", "x.py"), null);

        assertNull(pb.directory(), "the JVM's own working directory");
        assertTrue(ChildEnv.inheritsUserLocale(pb.environment()));
    }
}
