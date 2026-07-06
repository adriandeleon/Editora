package com.editora.ui;

import java.util.List;

import com.editora.agent.AcpJson;
import com.editora.config.AgentSessionHistory;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for {@link AgentCoordinator#modelDisplayName}/{@link AgentCoordinator#modeDisplayName} —
 *  the model/mode id → display-name lookup backing the AI Agent header labels — plus the resume-history
 *  display helpers ({@code sessionLabel}/{@code relativeTimeLabel}/{@code homeCollapsed}/
 *  {@code sessionDetail}/{@code displayLabel}). */
class AgentDisplayNameTest {

    @BeforeAll
    static void initMessages() {
        Messages.init("en");
    }

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

    @Test
    void sessionLabelFlattensAndTruncates() {
        assertEquals("hello world", AgentCoordinator.sessionLabel("  hello\n  world  "));
        String label = AgentCoordinator.sessionLabel("x".repeat(200));
        assertEquals(81, label.length()); // 80 chars + ellipsis
        assertTrue(label.endsWith("…"));
        assertEquals("", AgentCoordinator.sessionLabel(null));
    }

    @Test
    void relativeTimeLabelBuckets() {
        long now = 1_000_000L;
        assertEquals("just now", AgentCoordinator.relativeTimeLabel(now, now));
        assertEquals("5 minutes ago", AgentCoordinator.relativeTimeLabel(now - 5 * 60, now));
        assertEquals("2 hours ago", AgentCoordinator.relativeTimeLabel(now - 2 * 3600, now));
        assertEquals("3 days ago", AgentCoordinator.relativeTimeLabel(now - 3 * 86400, now));
    }

    @Test
    void homeCollapsedAbbreviatesHomePrefix() {
        String home = System.getProperty("user.home");
        assertEquals("~", AgentCoordinator.homeCollapsed(home));
        assertEquals("~/proj", AgentCoordinator.homeCollapsed(home + java.io.File.separator + "proj"));
        assertEquals("/etc/x", AgentCoordinator.homeCollapsed("/etc/x"));
        assertEquals("", AgentCoordinator.homeCollapsed(null));
    }

    @Test
    void sessionDetailCombinesTimeAndPath() {
        long now = 1_000_000L;
        AgentSessionHistory.Entry e = new AgentSessionHistory.Entry("s1", "/etc/x", "Title", now - 3 * 86400);
        assertEquals("3 days ago · /etc/x", AgentCoordinator.sessionDetail(e, now));
    }

    @Test
    void displayLabelFallsBackForBlank() {
        assertEquals("Title", AgentCoordinator.displayLabel(new AgentSessionHistory.Entry("s1", "/p", "Title", 1L)));
        assertEquals(
                "Untitled session", AgentCoordinator.displayLabel(new AgentSessionHistory.Entry("s1", "/p", "", 1L)));
    }
}
