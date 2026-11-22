package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure decisions behind Ctrl/Cmd-hover links: which modifiers count and which word the mouse is on. */
class DefinitionLinksTest {

    @Test
    void theShortcutKeyAloneIsTheChord() {
        assertTrue(DefinitionLinks.chord(true, false, false));
        assertFalse(DefinitionLinks.chord(false, false, false));
        assertFalse(DefinitionLinks.chord(true, true, false), "Ctrl+Shift is another gesture");
        assertFalse(DefinitionLinks.chord(true, false, true), "Ctrl+Alt is another gesture");
    }

    @Test
    void theWordIsTheOneTheCharacterUnderTheMouseBelongsTo() {
        String line = "    list.add(item);";
        assertArrayEquals(new int[] {4, 8}, DefinitionLinks.wordAt(line, 4, "java"));
        assertArrayEquals(new int[] {4, 8}, DefinitionLinks.wordAt(line, 7, "java"));
        assertArrayEquals(new int[] {9, 12}, DefinitionLinks.wordAt(line, 10, "java"));
    }

    @Test
    void punctuationAndTheGapAfterAWordAreNoWord() {
        String line = "    list.add(item);";
        assertNull(DefinitionLinks.wordAt(line, 8, "java"), "the dot after list is not list");
        assertNull(DefinitionLinks.wordAt(line, 0, "java"), "indentation");
        assertNull(DefinitionLinks.wordAt(line, line.length(), "java"), "past the end of the line");
        assertNull(DefinitionLinks.wordAt(line, -1, "java"));
    }

    @Test
    void aLanguageWithDollarIdentifiersKeepsTheDollar() {
        assertArrayEquals(new int[] {0, 3}, DefinitionLinks.wordAt("$el.focus()", 1, "javascript"));
    }
}
