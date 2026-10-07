package com.editora.ui;

import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.editor.LspTextEdit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code EditorBuffer.applyLspEditsAtomically}: a workspace edit's share for one file lands completely or
 * not at all. The lenient {@code applyLspEdits} skips an edit it cannot place, which is how a refactoring
 * across files the user is not looking at came out half-applied and was reported as done.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LspEditsAtomicFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Outcome(boolean placeable, boolean applied, String content) {}

    private static Outcome atomically(String content, LspTextEdit... edits) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            b.getNode();
            boolean placeable = b.canPlaceLspEdits(List.of(edits));
            String untouched = b.getContent();
            boolean applied = b.applyLspEditsAtomically(List.of(edits));
            assertEquals(content, untouched, "asking must not change the document");
            return new Outcome(placeable, applied, b.getContent());
        });
    }

    private static String leniently(String content, LspTextEdit... edits) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            b.getNode();
            b.applyLspEdits(List.of(edits));
            return b.getContent();
        });
    }

    @Test
    void aValidSetIsApplied() throws Exception {
        assertEquals(
                new Outcome(true, true, "AAAA();\nBBBB();\n"),
                atomically("a();\nbb();\n", new LspTextEdit(1, 0, 1, 2, "BBBB"), new LspTextEdit(0, 0, 0, 1, "AAAA")));
    }

    @Test
    void insertsFollowedByAReplaceAtTheSameStartAreValid() throws Exception {
        // The one same-start combination the protocol allows.
        assertEquals(
                new Outcome(true, true, "aIXdef\n"),
                atomically("abcdef\n", new LspTextEdit(0, 1, 0, 1, "I"), new LspTextEdit(0, 1, 0, 3, "X")));
    }

    @Test
    void overlappingEditsApplyNothing() throws Exception {
        assertEquals(
                new Outcome(false, false, "abcdef\n"),
                atomically(
                        "abcdef\n",
                        new LspTextEdit(0, 0, 0, 3, "X"),
                        new LspTextEdit(0, 2, 0, 4, "Y"),
                        new LspTextEdit(0, 5, 0, 6, "Z")));
        assertEquals(
                "XdeZ\n",
                leniently(
                        "abcdef\n",
                        new LspTextEdit(0, 0, 0, 3, "X"),
                        new LspTextEdit(0, 2, 0, 4, "Y"),
                        new LspTextEdit(0, 5, 0, 6, "Z")),
                "the lenient single-buffer path still skips the edit it cannot place");
    }

    @Test
    void aReplaceFollowedByAnInsertAtItsStartAppliesNothing() throws Exception {
        assertEquals(
                new Outcome(false, false, "abcdef\n"),
                atomically("abcdef\n", new LspTextEdit(0, 1, 0, 3, "X"), new LspTextEdit(0, 1, 0, 1, "I")));
    }

    @Test
    void aNegativePositionAppliesNothing() throws Exception {
        assertEquals(
                new Outcome(false, false, "abc\ndef\n"), atomically("abc\ndef\n", new LspTextEdit(-1, 0, 0, 2, "N")));
        assertEquals(
                new Outcome(false, false, "abc\ndef\n"), atomically("abc\ndef\n", new LspTextEdit(0, -3, 0, 2, "N")));
    }

    @Test
    void anEditBeyondTheDocumentAppliesNothing() throws Exception {
        assertEquals(
                new Outcome(false, false, "abc\n"),
                atomically("abc\n", new LspTextEdit(0, 0, 0, 1, "A"), new LspTextEdit(9, 0, 9, 1, "stale")));
    }

    @Test
    void aReadOnlyBufferAppliesNothing() throws Exception {
        boolean applied = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("abc\n");
            b.getNode();
            b.setViewMode(true);
            return b.canPlaceLspEdits(List.of(new LspTextEdit(0, 0, 0, 1, "A")))
                    || b.applyLspEditsAtomically(List.of(new LspTextEdit(0, 0, 0, 1, "A")));
        });
        assertFalse(applied);
        assertTrue(atomically("abc\n").applied(), "no edits is a valid no-op");
    }

    /** A column between the two halves of a surrogate pair used to split it into two lone surrogates. */
    @Test
    void aColumnInsideASurrogatePairSnapsToTheStartOfThePair() throws Exception {
        String smiley = "😀";
        assertEquals("|" + smiley + "x\n", leniently(smiley + "x\n", new LspTextEdit(0, 1, 0, 1, "|")));
        assertEquals(
                new Outcome(true, true, "a|" + smiley + "\n"),
                atomically("a" + smiley + "\n", new LspTextEdit(0, 2, 0, 2, "|")));
        // A column on a pair boundary is left alone.
        assertEquals(smiley + "|x\n", leniently(smiley + "x\n", new LspTextEdit(0, 2, 0, 2, "|")));
    }
}
