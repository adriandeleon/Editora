package com.editora.command;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void shiftedPunctuationIsSBaseKeyNotTheGlyph() {
        // "?" is Shift+SLASH → "S-/", never "?" (so the Emacs find-references binding must be "M-S-/").
        assertEquals("M-S-/", KeyDispatcher.chord(press(KeyCode.SLASH, true, false, true, false)));
    }

    @Test
    void numpadDigitProducesThePlainDigitToken() {
        // A numpad digit must map to "6" (matching the M-1…M-9 chords), not "Numpad 6" (unmatchable + a space).
        assertEquals("M-6", KeyDispatcher.chord(press(KeyCode.NUMPAD6, false, false, true, false)));
        assertEquals("M-6", KeyDispatcher.chord(press(KeyCode.DIGIT6, false, false, true, false)));
    }

    // --- KEY_TYPED swallow rule (a bound chord's char is eaten; an unbound Option glyph is not) ---

    private static KeyDispatcher dispatcher() {
        KeymapManager km = new KeymapManager();
        km.loadNamed("emacs", false); // C-t is bound to edit.transposeChars
        return new KeyDispatcher(new CommandRegistry(), km, s -> {});
    }

    private static KeyEvent typed(String ch, boolean alt) {
        return new KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED, false, false, alt, false);
    }

    @Test
    void pairedKeyTypedIsSwallowedAfterAConsumedChord() {
        KeyDispatcher d = dispatcher();
        KeyEvent p = press(KeyCode.T, false, true, false, false); // C-t
        d.handle(p);
        assertTrue(p.isConsumed(), "a bound chord consumes its press");
        KeyEvent t = typed("", false);
        d.handleTyped(t);
        assertTrue(t.isConsumed(), "the paired KEY_TYPED must still be swallowed so C-t doesn't also type");
    }

    @Test
    void normalTypedCharIsNotSwallowed() {
        KeyDispatcher d = dispatcher();
        KeyEvent p = press(KeyCode.A, false, false, false, false); // lone unbound 'a'
        d.handle(p);
        assertFalse(p.isConsumed(), "a lone unbound key falls through");
        KeyEvent t = typed("a", false);
        d.handleTyped(t);
        assertFalse(t.isConsumed(), "normal typing is not swallowed");
    }

    @Test
    void unboundOptionCharIsNotSwallowed() {
        // No consumed press → an Alt/Option-produced glyph (macOS accented/symbol input) must pass through.
        // The old code unconditionally ate it on macOS, breaking Option input.
        KeyDispatcher d = dispatcher();
        KeyEvent t = typed("ƒ", true); // e.g. Option+f producing "ƒ", but unbound
        d.handleTyped(t);
        assertFalse(t.isConsumed(), "an unbound Option/Alt character must reach the editor");
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
