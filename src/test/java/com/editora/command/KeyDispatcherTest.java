package com.editora.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;

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

    @Test
    void editorContextCommandsAreDeferredToFocusedToolWindows() {
        // Only caret/text commands are swallowed by a focused key-owning window…
        assertTrue(KeyDispatcher.isEditorContext("nav.lineDown"));
        assertTrue(KeyDispatcher.isEditorContext("edit.killLine"));
        // …jump/window/view commands stay global so they work while a tool window is focused.
        assertFalse(KeyDispatcher.isEditorContext("palette.show"));
        assertFalse(KeyDispatcher.isEditorContext("tool.project"));
        assertFalse(KeyDispatcher.isEditorContext("tool.jump"));
        assertFalse(KeyDispatcher.isEditorContext("view.toggleZen"));
        assertFalse(KeyDispatcher.isEditorContext(null));
    }

    // --- Windows/Linux Alt menu-mode guard (plainAltActive) ---

    @Test
    void plainAltOnWindowsIsGuarded() {
        // Left-Alt (Meta) held, no Ctrl, non-mac → consumed so Windows can't enter menu mode (which
        // otherwise freezes the keyboard on a bare or unbound Alt key).
        assertTrue(KeyDispatcher.plainAltActive(false, true, false));
    }

    @Test
    void macIsNeverGuarded() {
        // macOS uses Option as Meta and has no menu-activation problem.
        assertFalse(KeyDispatcher.plainAltActive(true, true, false));
    }

    @Test
    void altGrIsNotGuarded() {
        // AltGr is reported as Ctrl+Alt; guarding it would break international character composition,
        // so Alt-down WITH Ctrl-down must NOT be guarded.
        assertFalse(KeyDispatcher.plainAltActive(false, true, true));
    }

    @Test
    void noAltIsNotGuarded() {
        assertFalse(KeyDispatcher.plainAltActive(false, false, false)); // plain key
        assertFalse(KeyDispatcher.plainAltActive(false, false, true)); // Ctrl-only chord
    }
}
