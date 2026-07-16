package com.editora.macro;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Recording: interleaving of commands + typed text, and coalescing of consecutive characters. */
class MacroRecorderTest {

    @Test
    void noOpUntilStarted() {
        MacroRecorder r = new MacroRecorder();
        assertFalse(r.isRecording());
        r.recordChar('a');
        r.recordCommand("edit.cut");
        assertTrue(r.steps().isEmpty(), "nothing recorded before start()");
    }

    @Test
    void coalescesConsecutiveCharsIntoOneTextStep() {
        MacroRecorder r = new MacroRecorder();
        r.start();
        r.recordChar('f');
        r.recordChar('o');
        r.recordChar('o');
        List<MacroStep> steps = r.steps();
        assertEquals(1, steps.size());
        assertTrue(steps.get(0).isText());
        assertEquals("foo", steps.get(0).value());
    }

    @Test
    void preservesInterleavedOrderAndSplitsTextAcrossCommands() {
        MacroRecorder r = new MacroRecorder();
        r.start();
        r.recordChar('h');
        r.recordChar('i');
        r.recordCommand("nav.lineStart");
        r.recordChar('x');
        r.recordCommand("edit.cut");
        r.recordCommand("nav.lineDown");

        List<MacroStep> steps = r.steps();
        assertEquals(5, steps.size());
        assertEquals(MacroStep.text("hi"), steps.get(0));
        assertEquals(MacroStep.command("nav.lineStart"), steps.get(1));
        assertEquals(MacroStep.text("x"), steps.get(2));
        assertEquals(MacroStep.command("edit.cut"), steps.get(3));
        assertEquals(MacroStep.command("nav.lineDown"), steps.get(4));
    }

    @Test
    void startClearsPreviousBuffer() {
        MacroRecorder r = new MacroRecorder();
        r.start();
        r.recordChar('a');
        r.start();
        r.recordChar('b');
        assertEquals(List.of(MacroStep.text("b")), r.steps());
    }

    @Test
    void stopFreezesRecordingAndReturnsSnapshot() {
        MacroRecorder r = new MacroRecorder();
        r.start();
        r.recordCommand("edit.copy");
        List<MacroStep> captured = r.stop();
        assertFalse(r.isRecording());
        assertEquals(List.of(MacroStep.command("edit.copy")), captured);
        r.recordChar('z'); // ignored — recording stopped
        assertEquals(captured, r.steps());
    }

    /**
     * Backspace/Delete/arrows are handled natively by the editor area and are bound to no command in any
     * bundled keymap, so they reach neither capture hook — a macro recorded with them used to replay as if
     * they had never been pressed ("x Backspace y" inserted "xy"). They are their own step kind now, and
     * they break the text run so the order survives.
     */
    @Test
    void bareKeysAreRecordedInOrderAndBreakTheTextRun() {
        MacroRecorder r = new MacroRecorder();
        r.start();
        r.recordChar('x');
        r.recordKey("BACK_SPACE");
        r.recordChar('y');
        r.recordKey("DOWN");
        r.recordKey("HOME");
        assertEquals(
                List.of(
                        MacroStep.text("x"),
                        MacroStep.key("BACK_SPACE"),
                        MacroStep.text("y"),
                        MacroStep.key("DOWN"),
                        MacroStep.key("HOME")),
                r.steps());
    }

    @Test
    void recordKeyIsANoOpWhenNotRecordingOrBlank() {
        MacroRecorder r = new MacroRecorder();
        r.recordKey("DOWN"); // not started
        assertTrue(r.isEmpty());
        r.start();
        r.recordKey(null);
        r.recordKey("  ");
        assertTrue(r.isEmpty());
    }
}
