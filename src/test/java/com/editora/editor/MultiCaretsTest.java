package com.editora.editor;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which keys the fork's own multi-caret bindings edit on. A change made while one of them is delivered is
 * the caret manager's; a change during any other key came from somewhere that knows only the primary caret,
 * and must be followed by a re-sync of the extra carets' anchors.
 */
class MultiCaretsTest {

    private static KeyEvent pressed(KeyCode code, boolean shift, boolean control, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, alt, meta);
    }

    private static KeyEvent typed(String ch, boolean control, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED, false, control, false, meta);
    }

    @Test
    void printableCharactersAreTheManagers() {
        assertTrue(MultiCarets.managerEdits(typed("a", false, false)));
        assertTrue(MultiCarets.managerEdits(typed(" ", false, false)));
        assertFalse(MultiCarets.managerEdits(typed("\r", false, false)), "a control character");
        assertFalse(MultiCarets.managerEdits(typed("\u007f", false, false)));
        assertFalse(MultiCarets.managerEdits(typed("a", true, false)), "a Control chord's by-product");
        assertFalse(MultiCarets.managerEdits(typed("a", false, true)));
        assertFalse(MultiCarets.managerEdits(typed("", false, false)));
    }

    @Test
    void theEditingKeysAreTheManagersOnlyUnmodified() {
        assertTrue(MultiCarets.managerEdits(pressed(KeyCode.ENTER, false, false, false, false)));
        assertTrue(MultiCarets.managerEdits(pressed(KeyCode.TAB, false, false, false, false)));
        assertTrue(MultiCarets.managerEdits(pressed(KeyCode.BACK_SPACE, false, false, false, false)));
        assertTrue(MultiCarets.managerEdits(pressed(KeyCode.DELETE, false, false, false, false)));
        // Shift+Enter is not bound by the fork: the stock handler inserts a newline at the primary caret.
        assertFalse(MultiCarets.managerEdits(pressed(KeyCode.ENTER, true, false, false, false)));
        assertFalse(MultiCarets.managerEdits(pressed(KeyCode.BACK_SPACE, true, false, false, false)));
    }

    @Test
    void movementAndOtherKeysNeverEdit() {
        assertFalse(MultiCarets.managerEdits(pressed(KeyCode.LEFT, false, false, false, false)));
        assertFalse(MultiCarets.managerEdits(pressed(KeyCode.A, false, false, false, false)));
        assertFalse(MultiCarets.managerEdits(pressed(KeyCode.Z, false, true, false, false)), "Undo is foreign");
    }
}
