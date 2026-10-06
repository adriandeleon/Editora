package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedTextTest {

    @Test
    void anUnmodifiedCharacterIsTextEverywhere() {
        assertTrue(TypedText.isText(true, false, false, false));
        assertTrue(TypedText.isText(false, false, false, false));
    }

    @Test
    void altGrOnWindowsIsText() {
        // AltGr is delivered as Control+Alt; that is how { [ ] } \ | @ are typed on German and Spanish layouts.
        assertTrue(TypedText.isText(true, true, true, false));
        assertFalse(TypedText.isText(true, true, false, false), "Control alone is a shortcut");
        assertFalse(TypedText.isText(true, false, true, false), "Alt alone is a menu mnemonic");
        assertFalse(TypedText.isText(true, true, true, true));
    }

    @Test
    void optionOnMacIsText() {
        assertTrue(TypedText.isText(false, false, true, false), "Option composes characters");
        assertFalse(TypedText.isText(false, true, false, false), "Control is a shortcut");
        assertFalse(TypedText.isText(false, false, false, true), "Command is a shortcut");
        assertFalse(TypedText.isText(false, true, true, false));
    }
}
