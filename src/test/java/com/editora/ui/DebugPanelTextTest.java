package com.editora.ui;

import java.util.List;

import javafx.scene.input.KeyCode;

import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The Debug panel's pure text and key decisions (review C2, C7, C15–C18, C20, C21). */
class DebugPanelTextTest {

    @Test
    void stopAndRestartNeedShiftTheOtherLettersDoNot() {
        assertNull(DebugPanelText.controlFor(KeyCode.K, false, false), "a bare k does not stop the program (C2)");
        assertNull(DebugPanelText.controlFor(KeyCode.R, false, false), "a bare r does not restart it");
        assertEquals(DebugPanelText.Control.STOP, DebugPanelText.controlFor(KeyCode.K, true, false));
        assertEquals(DebugPanelText.Control.RESTART, DebugPanelText.controlFor(KeyCode.R, true, false));
        assertEquals(DebugPanelText.Control.STEP_OVER, DebugPanelText.controlFor(KeyCode.N, false, false));
        assertNull(DebugPanelText.controlFor(KeyCode.N, true, false), "a capital N is typing, not a step");
        assertNull(DebugPanelText.controlFor(KeyCode.C, false, true), "C-c is a chord");
        assertEquals("Shift+K", DebugPanelText.Control.STOP.keyLabel());
    }

    @Test
    void aTooltipNamesTheKeymapChordAndThePanelLetter() {
        assertEquals(
                "Step Over  (F10 · in this window: N)",
                DebugPanelText.tooltip("Debug: Step Over", "F10", "N", "in this window:"));
        assertEquals(
                "Pause  (in this window: P)", DebugPanelText.tooltip("Debug: Pause", null, "P", "in this window:"));
    }

    @Test
    void hiddenLambdaFramesAndThreadNamesReadAsTheUsersOwn() {
        assertEquals("<lambda>.accept(Object)", DebugPanelText.frameName("0x000000003f040c00.accept(Object)"));
        assertEquals("Main.main(String[])", DebugPanelText.frameName("Main.main(String[])"));
        assertEquals("main", DebugPanelText.threadName("Thread [main]"));
        var main = new DapModels.ThreadInfo(1, "Thread [main]");
        var handler = new DapModels.ThreadInfo(2, "Thread [Reference Handler]");
        var finalizer = new DapModels.ThreadInfo(3, "Thread [Finalizer]");
        var worker = new DapModels.ThreadInfo(9, "Thread [worker-1]");
        assertEquals(
                List.of(worker, main),
                DebugPanelText.visibleThreads(List.of(main, handler, finalizer, worker), 9),
                "JVM housekeeping threads left out, the stopped one first (C15)");
    }

    @Test
    void copyValueTakesAStringWithoutItsQuotesAndEscapes() {
        assertEquals("a \"b\"\nc", DebugPanelText.copyValue("\"a \\\"b\\\"\\nc\""));
        assertEquals("HashMap@13 size=0", DebugPanelText.copyValue("HashMap@13 size=0"));
        assertEquals("3", DebugPanelText.copyValue("3"));
    }

    @Test
    void aFailedWatchMessageIsOneLine() {
        assertEquals("Cannot evaluate: x", DebugPanelText.oneLine("Cannot evaluate:\nx", 100));
        assertEquals("abc…", DebugPanelText.oneLine("abcdef", 3));
    }

    @Test
    void theEvaluateHistoryWalksBackAndGivesTheDraftBack() {
        DebugPanelText.History h = new DebugPanelText.History();
        assertNull(h.up("x"), "nothing evaluated yet");
        h.add("a");
        h.add("b");
        h.add("b"); // a repeat of the newest is not added again
        assertEquals(List.of("a", "b"), h.entries());
        assertEquals("b", h.up("typing"));
        assertEquals("a", h.up("b"));
        assertNull(h.up("a"), "the oldest");
        assertEquals("b", h.down());
        assertEquals("typing", h.down(), "past the newest: what was being typed");
        assertNull(h.down());
    }

    @Test
    void aStackTraceLineIsStyledAsALink() {
        var spans = DebugConsoleStyle.spans("hello\n\tat Main.main(Main.java:22)\n", "run-stderr");
        List<String> styles = new java.util.ArrayList<>();
        spans.forEach(s -> styles.add(s.getStyle() + "×" + s.getLength()));
        assertEquals(
                List.of("[run-stderr]×7", "[run-stderr, console-link]×27"),
                styles,
                "the line and the frame's tab plain, the frame itself a link");
        assertNull(DebugConsoleStyle.spans("plain output\n", null), "plain text needs no spans");
    }
}
