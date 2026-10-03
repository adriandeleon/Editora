package com.editora.command;

import org.junit.jupiter.api.Test;

import static com.editora.command.ChordFormat.Style.EMACS;
import static com.editora.command.ChordFormat.Style.MAC;
import static com.editora.command.ChordFormat.Style.PLATFORM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one chord formatter: Emacs tokens for the Emacs keymap, platform notation for every other. */
class ChordFormatTest {

    @Test
    void emacsStyleIsTheRawTokens() {
        assertEquals("C-x C-s", ChordFormat.format("C-x C-s", EMACS));
        assertEquals("M-S-x", ChordFormat.format("M-S-x", EMACS));
    }

    @Test
    void platformStyleSpellsModifiersAndUppercasesTheKey() {
        assertEquals("Ctrl+Shift+P", ChordFormat.format("C-S-p", PLATFORM));
        assertEquals("Alt+X", ChordFormat.format("M-x", PLATFORM));
        assertEquals("Ctrl+Alt+Shift+T", ChordFormat.format("C-M-S-t", PLATFORM));
        assertEquals("Meta+S", ChordFormat.format("Cmd-s", PLATFORM));
    }

    @Test
    void multiKeySequenceIsSpaceSeparatedChords() {
        assertEquals("Ctrl+K Ctrl+W", ChordFormat.format("C-k C-w", PLATFORM));
        assertEquals("⌘K ⌘W", ChordFormat.format("Cmd-k Cmd-w", MAC));
    }

    @Test
    void macStyleUsesGlyphsInAppleOrder() {
        // Apple's order is Control, Option, Shift, Command — not the keymap's token order.
        assertEquals("⇧⌘P", ChordFormat.format("Cmd-S-p", MAC));
        assertEquals("⌥⌘F", ChordFormat.format("M-Cmd-f", MAC));
        assertEquals("⌃⌥⇧⌘K", ChordFormat.format("C-M-Cmd-S-k", MAC));
        assertEquals("⌃G", ChordFormat.format("C-g", MAC));
    }

    @Test
    void namedKeysAreSpelledForThePlatform() {
        assertEquals("Ctrl+Space", ChordFormat.format("C-space", PLATFORM));
        assertEquals("Shift+F3", ChordFormat.format("S-f3", PLATFORM));
        assertEquals("F11", ChordFormat.format("f11", PLATFORM));
        assertEquals("Esc", ChordFormat.format("escape", PLATFORM));
        assertEquals("Alt+Shift+Down", ChordFormat.format("M-S-down", PLATFORM));
        assertEquals("Ctrl+PageUp", ChordFormat.format("C-pageup", PLATFORM));
        assertEquals("⌥↓", ChordFormat.format("M-down", MAC));
        assertEquals("⌘⌫", ChordFormat.format("Cmd-backspace", MAC));
        assertEquals("F1", ChordFormat.format("f1", MAC));
    }

    @Test
    void punctuationKeysSurviveIncludingAMinusKeyAfterAModifier() {
        assertEquals("Ctrl+/", ChordFormat.format("C-/", PLATFORM));
        assertEquals("Ctrl+-", ChordFormat.format("C--", PLATFORM)); // "C-" + the minus key, not a bare prefix
        assertEquals("Ctrl+Shift+-", ChordFormat.format("C-S--", PLATFORM));
        assertEquals("-", ChordFormat.format("-", PLATFORM));
        assertEquals("⌘,", ChordFormat.format("Cmd-,", MAC));
        assertEquals("Ctrl+`", ChordFormat.format("C-back-quote", PLATFORM));
    }

    @Test
    void aLetterKeyThatLooksLikeAModifierIsStillAKey() {
        assertEquals("Ctrl+C", ChordFormat.format("C-c", PLATFORM));
        assertEquals("Ctrl+S", ChordFormat.format("C-s", PLATFORM));
        assertEquals("Ctrl+M", ChordFormat.format("C-m", PLATFORM));
        assertEquals("S", ChordFormat.format("s", PLATFORM));
    }

    @Test
    void blankFormatsToEmpty() {
        assertEquals("", ChordFormat.format(null, PLATFORM));
        assertEquals("", ChordFormat.format("  ", MAC));
    }

    @Test
    void styleFollowsTheKeymapAndThePlatform() {
        assertEquals(EMACS, ChordFormat.styleFor("emacs", false));
        assertEquals(EMACS, ChordFormat.styleFor("emacs", true)); // Emacs notation on every OS
        assertEquals(PLATFORM, ChordFormat.styleFor("vscode", false));
        assertEquals(MAC, ChordFormat.styleFor("vscode", true));
        assertEquals(EMACS, ChordFormat.styleFor("no-such-keymap", true)); // falls back with the keymap
        assertEquals(EMACS, ChordFormat.styleFor(null, false));
    }

    @Test
    void aCommandChordIsNotTypableOffTheMac() {
        assertFalse(ChordFormat.typable("Cmd-S-e", false));
        assertFalse(ChordFormat.typable("C-x Cmd-s", false));
        assertTrue(ChordFormat.typable("Cmd-S-e", true));
        assertTrue(ChordFormat.typable("M-S-x", false));
        assertTrue(ChordFormat.typable("C-c m", false)); // the key "m" is not a modifier
        assertFalse(ChordFormat.typable(null, true));
    }
}
