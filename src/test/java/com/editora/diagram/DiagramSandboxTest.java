package com.editora.diagram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import com.editora.process.SecretEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The environment a diagram render child runs under. A {@code .puml} is content from whatever repository the
 * user opened; PlantUML's command-line default profile lets it include local files/URLs and read environment
 * variables, so the preview pins {@code PLANTUML_SECURITY_PROFILE=SANDBOX} and scrubs secret-looking variables.
 */
class DiagramSandboxTest {

    @Test
    void plantumlDefaultsToTheSandboxProfile() {
        assertEquals(Map.of("PLANTUML_SECURITY_PROFILE", "SANDBOX"), DiagramKind.plantumlEnvironment(null));
        assertEquals(Map.of("PLANTUML_SECURITY_PROFILE", "SANDBOX"), DiagramKind.plantumlEnvironment(" "));
    }

    @Test
    void aProfileTheUserExportedThemselvesIsLeftAlone() {
        assertEquals(Map.of(), DiagramKind.plantumlEnvironment("INTERNET"));
        assertEquals(Map.of(), DiagramKind.plantumlEnvironment("ALLOWLIST"));
    }

    @Test
    void graphvizNeedsNoExtraEnvironment() {
        assertEquals(Map.of(), DiagramKind.DOT.environment());
    }

    /** A stand-in "plantuml" that writes its own environment where the renderer expects the PNG. */
    private static List<String> envDumpingTool(Path dir) throws IOException {
        Path script = dir.resolve("fake-plantuml.sh");
        Files.writeString(script, "#!/bin/sh\nenv > diagram.png\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return List.of(script.toString());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void thePlantumlChildRunsSandboxedWithSecretsScrubbed(@TempDir Path dir) throws IOException {
        assumeTrue(System.getenv("PLANTUML_SECURITY_PROFILE") == null, "the user's own profile would win");
        DiagramRenderer.Render r =
                DiagramRenderer.renderPng(DiagramKind.PLANTUML, envDumpingTool(dir), "@startuml\n@enduml\n", false);
        assertTrue(r.ok(), r.error());
        String env = new String(r.image(), StandardCharsets.UTF_8);
        assertTrue(env.lines().anyMatch("PLANTUML_SECURITY_PROFILE=SANDBOX"::equals), env);
        assertNoInheritedSecrets(env);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theGraphvizChildHasSecretsScrubbedAndNoPlantumlProfile(@TempDir Path dir) throws IOException {
        DiagramRenderer.Render r = DiagramRenderer.renderPng(DiagramKind.DOT, envDumpingTool(dir), "digraph{}", false);
        assertTrue(r.ok(), r.error());
        assertNoInheritedSecrets(new String(r.image(), StandardCharsets.UTF_8));
    }

    private static void assertNoInheritedSecrets(String envDump) {
        for (String line : envDump.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && System.getenv(line.substring(0, eq)) != null) {
                assertFalse(SecretEnv.isSecretName(line.substring(0, eq)), "leaked: " + line.substring(0, eq));
            }
        }
    }
}
