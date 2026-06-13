package com.editora.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeymapManagerTest {

    private KeymapManager keymap;

    @BeforeEach
    void setUp() {
        keymap = new KeymapManager();
        keymap.loadNamed("emacs");
    }

    @Test
    void resolvesMultiKeyChord() {
        assertEquals("file.save", keymap.commandFor("C-x C-s"));
        assertEquals("file.find", keymap.commandFor("C-x C-f"));
    }

    @Test
    void resolvesSingleChord() {
        assertEquals("palette.show", keymap.commandFor("M-x"));
    }

    @Test
    void recognizesPrefixButNotFullBinding() {
        assertTrue(keymap.isPrefix("C-x"));
        assertNull(keymap.commandFor("C-x"));
    }

    @Test
    void fullBindingIsNotAPrefix() {
        assertFalse(keymap.isPrefix("M-x"));
    }

    @Test
    void userOverridesTakePrecedence() {
        keymap.applyOverrides(Map.of("C-x C-s", "custom.save"));
        assertEquals("custom.save", keymap.commandFor("C-x C-s"));
    }
}
