package com.editora.ui;

import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.editor.LspTextEdit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for the Format-Document sub-item of #415: a multi-edit LSP result (Format Document, an
 * auto-import's additional edits) was applied as one {@code replaceText} per edit, so reverting a multi-line
 * format took many Ctrl-Z. The edits are now one {@code MultiChangeBuilder} commit — a single undo unit.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LspEditsUndoFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aMultiEditFormatIsASingleUndoUnit() throws Exception {
        String result = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent("a\nb\nc\n");
            b.getNode();
            // Three edits (as a formatter would return): uppercase each of the three lines. Applied as one
            // multi-change, so a single undo reverts all three.
            b.applyLspEdits(List.of(
                    new LspTextEdit(0, 0, 0, 1, "A"),
                    new LspTextEdit(1, 0, 1, 1, "B"),
                    new LspTextEdit(2, 0, 2, 1, "C")));
            String afterFormat = b.getContent();
            b.getArea().undo();
            return afterFormat + "|" + b.getContent();
        });
        assertEquals("A\nB\nC\n|a\nb\nc\n", result, "format applies, then ONE undo reverts the whole set");
    }

    @Test
    void aSingleEditStillApplies() throws Exception {
        String content = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent("hello\n");
            b.getNode();
            b.applyLspEdits(List.of(new LspTextEdit(0, 0, 0, 5, "world")));
            return b.getContent();
        });
        assertEquals("world\n", content);
    }
}
