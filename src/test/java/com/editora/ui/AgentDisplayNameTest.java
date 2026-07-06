package com.editora.ui;

import java.util.List;

import com.editora.agent.AcpJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure tests for {@link AgentCoordinator#modelDisplayName}/{@link AgentCoordinator#modeDisplayName} —
 *  the model/mode id → display-name lookup backing the AI Agent header labels. */
class AgentDisplayNameTest {

    private static final List<AcpJson.ModelInfo> MODELS = List.of(
            new AcpJson.ModelInfo("opus", "Claude Opus", "Most capable"),
            new AcpJson.ModelInfo("sonnet", "Claude Sonnet", "Balanced"));

    private static final List<AcpJson.ModeInfo> MODES = List.of(
            new AcpJson.ModeInfo("default", "Default", "Standard behavior"),
            new AcpJson.ModeInfo("plan", "Plan Mode", "Planning, no execution"));

    @Test
    void modelFoundReturnsDisplayName() {
        assertEquals("Claude Opus", AgentCoordinator.modelDisplayName(MODELS, "opus"));
    }

    @Test
    void modelNotFoundFallsBackToId() {
        assertEquals("mystery-model", AgentCoordinator.modelDisplayName(MODELS, "mystery-model"));
    }

    @Test
    void modelNullIdReturnsEmpty() {
        assertEquals("", AgentCoordinator.modelDisplayName(MODELS, null));
    }

    @Test
    void modeFoundReturnsDisplayName() {
        assertEquals("Plan Mode", AgentCoordinator.modeDisplayName(MODES, "plan"));
    }

    @Test
    void modeNotFoundFallsBackToId() {
        assertEquals("acceptEdits", AgentCoordinator.modeDisplayName(MODES, "acceptEdits"));
    }

    @Test
    void modeNullIdReturnsEmpty() {
        assertEquals("", AgentCoordinator.modeDisplayName(MODES, null));
    }
}
