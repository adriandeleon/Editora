package com.editora.agent;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.process.ChildEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The environment the agent CLI is started in: the user's own locale, not the {@code LC_ALL=C} reserved for
 * output Editora parses — the agent reads and writes the user's files and prose.
 */
class AcpClientEnvTest {

    @Test
    void theAgentInheritsTheUsersLocale(@TempDir Path dir) {
        ProcessBuilder pb = AcpClient.processBuilder(List.of("/opt/agent/bin/agent", "--acp"), dir, Map.of());

        assertTrue(
                ChildEnv.inheritsUserLocale(pb.environment()),
                "LC_ALL=" + pb.environment().get("LC_ALL"));
        assertEquals(dir.toFile(), pb.directory());
        assertEquals(List.of("/opt/agent/bin/agent", "--acp"), pb.command());
    }

    @Test
    void providerVariablesAreAppliedOnTop() {
        ProcessBuilder pb = AcpClient.processBuilder(
                List.of("/opt/agent/bin/agent"), null, Map.of("OPENAI_BASE_URL", "http://localhost:1234/v1"));

        assertEquals("http://localhost:1234/v1", pb.environment().get("OPENAI_BASE_URL"));
        assertNull(pb.directory());
        assertTrue(ChildEnv.inheritsUserLocale(pb.environment()));
    }
}
