package com.editora.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

class KeyDispatcherTest {

    private static KeyEvent press(KeyCode code, boolean shift, boolean ctrl, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, meta);
    }

    @Test
    void controlLetterChord() {
        assertEquals("C-x", KeyDispatcher.chord(press(KeyCode.X, false, true, false, false)));
    }

    @Test
    void altLetterIsMeta() {
        assertEquals("M-x", KeyDispatcher.chord(press(KeyCode.X, false, false, true, false)));
    }

    @Test
    void modifierOrderIsControlMetaShift() {
        assertEquals("C-S-p", KeyDispatcher.chord(press(KeyCode.P, true, true, false, false)));
    }

    @Test
    void specialKeyName() {
        assertEquals("C-/", KeyDispatcher.chord(press(KeyCode.SLASH, false, true, false, false)));
    }

    @Test
    void modifierOnlyEventHasNoChord() {
        assertNull(KeyDispatcher.chord(press(KeyCode.CONTROL, false, true, false, false)));
    }
}
