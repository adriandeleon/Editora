package com.editora.agent;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcpAgentRegistryTest {

    @Test
    void allReturnsSixAgentsInOrder() {
        List<AcpAgentRegistry.AgentDef> all = AcpAgentRegistry.all();
        assertEquals(6, all.size());
        assertEquals(
                List.of("claude", "gemini", "copilot", "codex", "qwen", "opencode"),
                all.stream().map(AcpAgentRegistry.AgentDef::id).toList());
    }

    @Test
    void defaultCommandForEachAgent() {
        assertEquals("claude-code-acp", AcpAgentRegistry.defaultCommandFor("claude"));
        assertEquals("gemini --acp", AcpAgentRegistry.defaultCommandFor("gemini"));
        assertEquals("copilot --acp", AcpAgentRegistry.defaultCommandFor("copilot"));
        assertEquals("codex-acp", AcpAgentRegistry.defaultCommandFor("codex"));
        assertEquals("qwen --acp", AcpAgentRegistry.defaultCommandFor("qwen"));
        assertEquals("opencode acp", AcpAgentRegistry.defaultCommandFor("opencode"));
        assertEquals("", AcpAgentRegistry.defaultCommandFor("unknown"));
    }

    @Test
    void commandForUsesOverrideElseDefault() {
        assertEquals(List.of("gemini", "--acp"), AcpAgentRegistry.commandFor("gemini", Map.of()));
        assertEquals(
                List.of("my-gemini", "--foo"),
                AcpAgentRegistry.commandFor("gemini", Map.of("gemini", "my-gemini --foo")));
        assertEquals(
                List.of("gemini", "--acp"), AcpAgentRegistry.commandFor("gemini", Map.of("gemini", "  "))); // blank
        assertTrue(AcpAgentRegistry.commandFor("unknown", Map.of()).isEmpty());
    }

    @Test
    void fromParsesBlankUnknownAndKnown() {
        assertEquals(AcpAgentRegistry.AgentDef.CLAUDE, AcpAgentRegistry.from(null));
        assertEquals(AcpAgentRegistry.AgentDef.CLAUDE, AcpAgentRegistry.from(""));
        assertEquals(AcpAgentRegistry.AgentDef.CLAUDE, AcpAgentRegistry.from("nope"));
        assertEquals(AcpAgentRegistry.AgentDef.GEMINI, AcpAgentRegistry.from("gemini"));
        assertEquals(AcpAgentRegistry.AgentDef.GEMINI, AcpAgentRegistry.from("GEMINI")); // case-insensitive
    }

    @Test
    void displayNameForFallsBackToId() {
        assertEquals("Codex CLI", AcpAgentRegistry.displayNameFor("codex"));
        assertEquals("mystery", AcpAgentRegistry.displayNameFor("mystery"));
        assertEquals("", AcpAgentRegistry.displayNameFor(null));
    }
}
