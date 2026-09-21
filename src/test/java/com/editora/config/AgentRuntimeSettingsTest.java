package com.editora.config;

import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeSettingsTest {
    @Test
    void defaultsMigrationAndRoundTripPreserveExistingAgentChoices() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var old = json.readTree("{\"schemaVersion\":104,\"agentClient\":\"codex\",\"aiProvider\":\"lmstudio\"}");
        var migrated = ConfigSchema.SETTINGS.step(104).apply(old);
        assertEquals(old, migrated);
        var settings = json.treeToValue(migrated, Settings.class);
        assertEquals("codex", settings.getAgentClient());
        assertEquals(64, settings.getAgentMaxIterations());
        assertEquals(32768, settings.getAgentContextTokens());
        settings.setAgentClient("builtin");
        settings.setAgentMaxIterations(100);
        settings.setAgentContextTokens(65536);
        var copy = json.readValue(json.writeValueAsString(settings), Settings.class);
        assertEquals("builtin", copy.getAgentClient());
        assertEquals("lmstudio", copy.getAiProvider());
        assertEquals(100, copy.getAgentMaxIterations());
        assertEquals(65536, copy.getAgentContextTokens());
        copy.setAgentMcpServers(java.util.List.of(new AgentMcpServer("local", "mcp-server --stdio", true)));
        var extensions = json.readValue(json.writeValueAsString(copy), Settings.class);
        assertEquals(copy.getAgentMcpServers(), extensions.getAgentMcpServers());
        assertThrows(
                IllegalArgumentException.class,
                () -> copy.setAgentMcpServers(
                        java.util.List.of(new AgentMcpServer("x", "one", true), new AgentMcpServer("x", "two", true))));
        assertEquals(old, ConfigSchema.SETTINGS.step(105).apply(old));
        assertEquals(old, ConfigSchema.SETTINGS.step(106).apply(old));
        assertTrue(copy.getAgentModelProfiles().isEmpty());
        var profile = new AgentModelProfileConfig("lmstudio", "model", 65536, 8192, 16384, "SUPPORTED", true, 0.2, 42L);
        copy.setAgentModelProfiles(java.util.List.of(profile));
        assertEquals(
                profile,
                json.readValue(json.writeValueAsString(copy), Settings.class).agentModelProfile("lmstudio", "model"));
        assertThrows(
                IllegalArgumentException.class, () -> copy.setAgentModelProfiles(java.util.List.of(profile, profile)));
        copy.setAgentMaxIterations(-1);
        copy.setAgentContextTokens(Integer.MAX_VALUE);
        assertEquals(1, copy.getAgentMaxIterations());
        assertEquals(262144, copy.getAgentContextTokens());
        copy.setAgentContextTokens(1);
        assertEquals(Settings.AGENT_CONTEXT_MIN, copy.getAgentContextTokens());
    }
}
