package org.adriandeleon.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmacsEditingTest {

    @Test
    void killRegionNormalizesSelectionBounds() {
        EmacsEditing.EditOperation operation = EmacsEditing.killRegion("abcdef", 5, 2);

        assertEquals(2, operation.start());
        assertEquals(5, operation.end());
        assertEquals("", operation.replacement());
        assertEquals(2, operation.caretPosition());
        assertEquals("cde", operation.affectedText());
    }

    @Test
    void deleteForwardCharIsNoOpAtEndOfBuffer() {
        EmacsEditing.EditOperation operation = EmacsEditing.deleteForwardChar("abc", 3);

        assertTrue(operation.isNoOp());
        assertEquals(3, operation.caretPosition());
    }

    @Test
    void killLineRemovesToEndOfLineWithoutDeletingNewlineMidLine() {
        EmacsEditing.EditOperation operation = EmacsEditing.killLine("abc\ndef", 1);

        assertEquals(1, operation.start());
        assertEquals(3, operation.end());
        assertEquals("bc", operation.affectedText());
        assertEquals(1, operation.caretPosition());
    }

    @Test
    void killLineDeletesNewlineWhenCaretIsAtLineEnd() {
        EmacsEditing.EditOperation operation = EmacsEditing.killLine("abc\ndef", 3);

        assertEquals(3, operation.start());
        assertEquals(4, operation.end());
        assertEquals("\n", operation.affectedText());
        assertEquals(3, operation.caretPosition());
    }

    @Test
    void killWordOperationsFollowNavigationBoundaries() {
        EmacsEditing.EditOperation forward = EmacsEditing.killWordForward("foo_bar + baz", 7);
        EmacsEditing.EditOperation backward = EmacsEditing.killWordBackward("foo_bar + baz", 10);

        assertEquals(7, forward.start());
        assertEquals(13, forward.end());
        assertEquals(" + baz", forward.affectedText());
        assertEquals(7, forward.caretPosition());

        assertEquals(0, backward.start());
        assertEquals(10, backward.end());
        assertEquals("foo_bar + ", backward.affectedText());
        assertEquals(0, backward.caretPosition());
    }

    @Test
    void yankReplacesSelectionAndPlacesCaretAfterInsertedText() {
        EmacsEditing.EditOperation operation = EmacsEditing.yank("hello world", 6, 11, "Editora");

        assertEquals(6, operation.start());
        assertEquals(11, operation.end());
        assertEquals("Editora", operation.replacement());
        assertEquals(13, operation.caretPosition());
        assertEquals("world", operation.affectedText());
    }
}
