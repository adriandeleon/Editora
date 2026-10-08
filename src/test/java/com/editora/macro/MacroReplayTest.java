package com.editora.macro;

import com.editora.macro.MacroReplay.Readiness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MacroReplayTest {

    @Test
    void aPromptStepWaitsUntilTheFocusHasLeftTheDocument() {
        // prompt, hasOwner, inEditor, overlayShowing, inOverlay
        assertEquals(Readiness.WAIT_FOR_PROMPT, MacroReplay.readiness(true, true, true, false, false));
        assertEquals(Readiness.WAIT_FOR_PROMPT, MacroReplay.readiness(true, false, false, false, false));
        assertEquals(Readiness.READY, MacroReplay.readiness(true, true, false, false, false), "the find bar");
    }

    /** An overlay card takes the focus a turn after it is shown; until then the old owner still has it. */
    @Test
    void aPromptStepWaitsForAnOverlayThatIsUpButNotFocusedYet() {
        assertEquals(Readiness.WAIT_FOR_PROMPT, MacroReplay.readiness(true, true, false, true, false));
        assertEquals(Readiness.READY, MacroReplay.readiness(true, true, false, true, true));
    }

    @Test
    void aDocumentStepGoesAheadUnlessAnOverlayCoversTheEditor() {
        assertEquals(Readiness.READY, MacroReplay.readiness(false, true, true, false, false));
        assertEquals(Readiness.READY, MacroReplay.readiness(false, true, false, false, false), "focus in the find bar");
        assertEquals(Readiness.READY, MacroReplay.readiness(false, false, false, false, false));
        assertEquals(Readiness.WAIT_FOR_EDITOR, MacroReplay.readiness(false, true, false, true, true));
    }

    @Test
    void theRepeatCountIsHeldToItsRange() {
        assertEquals(1, MacroReplay.parseTimes(null, 10_000));
        assertEquals(1, MacroReplay.parseTimes("abc", 10_000));
        assertEquals(1, MacroReplay.parseTimes("0", 10_000));
        assertEquals(1, MacroReplay.parseTimes("-5", 10_000));
        assertEquals(42, MacroReplay.parseTimes(" 42 ", 10_000));
        assertEquals(10_000, MacroReplay.parseTimes("99999999999", 10_000), "beyond int range too");
        assertEquals(10_000, MacroReplay.clampTimes(100_000, 10_000));
    }
}
