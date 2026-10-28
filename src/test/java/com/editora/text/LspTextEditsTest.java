package com.editora.text;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A whole-file format edit that changes nothing is told apart from one that changes something (Bash review B14). */
class LspTextEditsTest {

    private static final String TEXT = "a\n  b\nc\n";

    @Test
    void aWholeDocumentEditOfTheSameTextChangesNothing() {
        assertTrue(LspTextEdits.changesNothing(TEXT, List.of(new LspTextEdit(0, 0, 3, 0, TEXT))));
        assertTrue(
                LspTextEdits.changesNothing(TEXT, List.of(new LspTextEdit(0, 0, 2, 999, "a\n  b\nc"))),
                "an end past the line is its end");
        assertTrue(LspTextEdits.changesNothing(TEXT, List.of(new LspTextEdit(1, 2, 1, 3, "b"))));
        assertTrue(LspTextEdits.changesNothing(TEXT, List.of()));
    }

    @Test
    void anyDifferenceIsAChange() {
        assertFalse(LspTextEdits.changesNothing(TEXT, List.of(new LspTextEdit(0, 0, 3, 0, "a\n    b\nc\n"))));
        assertFalse(LspTextEdits.changesNothing(TEXT, List.of(new LspTextEdit(1, 0, 1, 0, " "))));
        assertFalse(LspTextEdits.changesNothing(TEXT, List.of(new LspTextEdit(9, 3, 9, 4, ""))), "outside the text");
        assertFalse(LspTextEdits.changesNothing(TEXT, List.of(new LspTextEdit(1, 3, 1, 1, ""))), "a reversed range");
    }
}
