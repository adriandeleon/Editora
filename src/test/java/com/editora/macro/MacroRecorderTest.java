package com.editora.macro;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroRecorderTest {

    @Test
    void nothingIsRecordedBeforeStart() {
        MacroRecorder r = new MacroRecorder();
        r.recordCommand("edit.copy");
        r.recordText("x", false);
        r.recordKey("DOWN", false);
        assertTrue(r.isEmpty());
        assertFalse(r.isRecording());
    }

    @Test
    void consecutiveTextCoalescesAndCommandsAndKeysBreakTheRun() {
        MacroRecorder r = new MacroRecorder();
        r.start();
        r.recordText("a", false);
        r.recordText("b", false);
        r.recordCommand("nav.lineEnd");
        r.recordText("c", false);
        r.recordKey("BACK_SPACE", false);
        r.recordText("d", false);
        assertEquals(
                List.of(
                        MacroStep.text("ab"),
                        MacroStep.command("nav.lineEnd"),
                        MacroStep.text("c"),
                        MacroStep.key("BACK_SPACE"),
                        MacroStep.text("d")),
                r.stop());
        assertFalse(r.isRecording());
    }

    /** Text for the document and text for a prompt are different steps even when typed back to back. */
    @Test
    void textForADifferentTargetStartsANewStep() {
        MacroRecorder r = new MacroRecorder();
        r.start();
        r.recordText("fo", true);
        r.recordText("o", true);
        r.recordKey("ENTER", true);
        r.recordText("Z", false);
        r.recordText("q", true);
        assertEquals(
                List.of(
                        MacroStep.text("foo", true),
                        MacroStep.key("ENTER", true),
                        MacroStep.text("Z"),
                        MacroStep.text("q", true)),
                r.steps());
    }

    @Test
    void startDiscardsThePreviousRecordingAndCancelDropsThisOne() {
        MacroRecorder r = new MacroRecorder();
        r.start();
        r.recordText("old", false);
        r.stop();
        r.start();
        assertTrue(r.isEmpty());
        r.recordText("new", false);
        r.cancel();
        assertFalse(r.isRecording());
        assertTrue(r.isEmpty());
        assertEquals(List.of(), r.steps());
    }

    @Test
    void blankKeysAndNullCommandsAreIgnored() {
        MacroRecorder r = new MacroRecorder();
        r.start();
        r.recordKey(null, false);
        r.recordKey(" ", false);
        r.recordCommand(null);
        r.recordText("", false);
        r.recordText(null, false);
        assertTrue(r.isEmpty());
    }

    /** A large repeat (C-u 100000 x) is one append, not a hundred thousand string copies. */
    @Test
    void aLongRunIsOneStep() {
        MacroRecorder r = new MacroRecorder();
        r.start();
        r.recordText("x".repeat(100_000), false);
        r.recordText("y", false);
        List<MacroStep> steps = r.stop();
        assertEquals(1, steps.size());
        assertEquals(100_001, steps.get(0).value().length());
    }
}
