package com.editora.ui;

import com.editora.editor.EditorBuffer;
import com.editora.snippet.Snippet;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An edit of a mirrored snippet field that is not a typed character — Backspace, Delete, a paste — reaches the
 * session after the fact, and the mirrors are rewritten as separate edits. They have to be undone with the
 * edit that caused them: one Ctrl-Z used to revert the last mirror only, leaving {@code a = ab;}.
 */
@Tag("fx")
class SnippetMirrorUndoStepFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static CodeArea expanded(String body) {
        EditorBuffer b = new EditorBuffer();
        b.setContent("");
        b.getNode();
        b.insertSnippet(new Snippet("t", "t", body, "", ""));
        b.typeString("ab");
        CodeArea area = FxTestSupport.field(b, "area");
        assertEquals("ab = ab + ab;", area.getText(), "precondition: typing mirrors");
        return area;
    }

    @Test
    void backspaceInAMirroredFieldIsOneUndoStep() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = expanded("$1 = $1 + $1;");
            area.deletePreviousChar();
            assertEquals("a = a + a;", area.getText());
            area.undo();
            assertEquals("ab = ab + ab;", area.getText(), "one undo restores the field and both mirrors");
            area.redo();
            assertEquals("a = a + a;", area.getText(), "and one redo applies them again");
        });
    }

    @Test
    void aPasteOverAMirroredFieldIsOneUndoStep() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = expanded("$1 = $1 + $1;");
            area.selectRange(0, 2);
            area.replaceSelection("pasted"); // what a paste does: one edit, not typed characters
            assertEquals("pasted = pasted + pasted;", area.getText());
            area.undo();
            assertEquals("ab = ab + ab;", area.getText());
        });
    }

    @Test
    void consecutiveBackspacesUndoOneAtATimeAndNeverHalfway() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = expanded("$1 = $1 + $1;");
            area.deletePreviousChar();
            area.deletePreviousChar();
            assertEquals(" =  + ;", area.getText());
            area.undo();
            assertEquals("a = a + a;", area.getText());
            area.undo();
            assertEquals("ab = ab + ab;", area.getText());
        });
    }
}
