package com.editora.ui;

import com.editora.ui.OverlayInput.Enter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What Enter does in an in-scene form card (the pure decision; {@code OverlayCardKeysFxTest} drives it). */
class OverlayInputTest {

    @Test
    void enterAcceptsFromAField() {
        assertEquals(Enter.ACCEPT, OverlayInput.onEnter(false, false, false));
    }

    @Test
    void enterOnAFocusedButtonActivatesThatButtonNotThePrimaryAction() {
        // Tab to Cancel (or a red Delete) + Enter used to run Save/OK.
        assertEquals(Enter.FIRE_FOCUSED_BUTTON, OverlayInput.onEnter(true, false, false));
        assertEquals(Enter.FIRE_FOCUSED_BUTTON, OverlayInput.onEnter(true, true, false));
    }

    @Test
    void enterInAMultiLineBodyIsANewline() {
        assertEquals(Enter.PASS, OverlayInput.onEnter(false, true, false));
    }

    @Test
    void theSubmitChordAlwaysAccepts() {
        assertEquals(Enter.ACCEPT, OverlayInput.onEnter(false, true, true));
        assertEquals(Enter.ACCEPT, OverlayInput.onEnter(true, true, true));
        assertEquals(Enter.ACCEPT, OverlayInput.onEnter(true, false, true));
    }
}
