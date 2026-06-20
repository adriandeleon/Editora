package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoMergeTest {

    @Test
    void insertionBreaksOnlyWhenItEndsAWordOrLine() {
        assertFalse(UndoMerge.breakAfter("a", "")); // mid-word: keep merging
        assertFalse(UndoMerge.breakAfter("foo", ""));
        assertTrue(UndoMerge.breakAfter(" ", "")); // a space ends the word group
        assertTrue(UndoMerge.breakAfter("\n", "")); // a newline ends the line group
        assertTrue(UndoMerge.breakAfter("\t", ""));
        assertTrue(UndoMerge.breakAfter("foo ", "")); // a chunk ending in whitespace
    }

    @Test
    void deletionBreaksWhenItRemovesWhitespace() {
        assertTrue(UndoMerge.breakAfter("", " "));
        assertTrue(UndoMerge.breakAfter("", "foo\n"));
        assertFalse(UndoMerge.breakAfter("", "a"));
        assertFalse(UndoMerge.breakAfter("", "foo"));
    }

    @Test
    void emptyChangeNeverBreaks() {
        assertFalse(UndoMerge.breakAfter("", ""));
        assertFalse(UndoMerge.breakAfter(null, null));
    }
}
