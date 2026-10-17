package com.editora.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChordQueryTest {

    @Test
    void readsEveryNotationEditoraShows() {
        assertEquals("C-S-s", ChordQuery.parse("Ctrl+Shift+S"));
        assertEquals("C-S-s", ChordQuery.parse("ctrl+shift+s"));
        assertEquals("C-S-s", ChordQuery.parse("C-S-s"));
        assertEquals("Cmd-S-p", ChordQuery.parse("⇧⌘P"));
        assertEquals("Cmd-s", ChordQuery.parse("Cmd+S"));
        assertEquals("Cmd-s", ChordQuery.parse("Cmd-s"));
        assertEquals("C-x C-s", ChordQuery.parse("C-x C-s"));
        assertEquals("C-k C-w", ChordQuery.parse("Ctrl+K Ctrl+W"));
        assertEquals("M-x", ChordQuery.parse("Alt+X"));
        assertEquals("f5", ChordQuery.parse("F5"));
        assertEquals("escape", ChordQuery.parse("Esc"));
        assertEquals("C-pageup", ChordQuery.parse("Ctrl+PageUp"));
        assertEquals("C--", ChordQuery.parse("C--"));
        assertEquals("C-tab", ChordQuery.parse("⌃⇥"));
    }

    @Test
    void wordsAreNotChords() {
        assertNull(ChordQuery.parse("save"));
        assertNull(ChordQuery.parse("file save"));
        assertNull(ChordQuery.parse("Hyper+S"));
        assertNull(ChordQuery.parse(""));
        assertNull(ChordQuery.parse(null));
    }
}
