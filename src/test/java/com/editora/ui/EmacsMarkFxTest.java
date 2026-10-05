package com.editora.ui;

import javafx.scene.input.KeyCode;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Emacs mark is per buffer and a modification of the buffer deactivates it (transient-mark-mode). It
 * used to be one window-wide flag that only a handful of commands cleared: {@code C-SPC}, type, {@code C-n}
 * selected from the typed text and the next key replaced the selection, and a mark set in one tab made
 * motion select in the next. Driven with real key events — the typing that must deactivate the mark is
 * handled by the text area, not by a command.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmacsMarkFxTest {

    private static final String BETA = "beta one\nbeta two\nbeta three\nbeta four\n";

    private EditingFx e;

    @BeforeAll
    void setUp() throws Exception {
        e = EditingFx.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (e != null) {
            e.dispose();
        }
    }

    private int selectionLength(CodeArea area) throws Exception {
        return FxTestSupport.callOnFx(() -> area.getSelection().getLength());
    }

    @Test
    void theMarkStillExtendsTheSelectionWhileNothingIsEdited() throws Exception {
        EditorBuffer b = e.open("mark.txt", BETA);
        CodeArea area = b.getArea();
        FxTestSupport.runOnFx(() -> area.moveTo(0));

        e.ctrl(area, KeyCode.SPACE);
        e.ctrl(area, KeyCode.N);
        e.ctrl(area, KeyCode.N);

        assertEquals("beta one\nbeta two\n", FxTestSupport.callOnFx(area::getSelectedText));
    }

    @Test
    void typingDeactivatesTheMark() throws Exception {
        EditorBuffer b = e.open("typed.txt", BETA);
        CodeArea area = b.getArea();
        FxTestSupport.runOnFx(() -> area.moveTo(0));

        e.ctrl(area, KeyCode.SPACE);
        e.type(area, KeyCode.X, "x");
        e.type(area, KeyCode.Y, "y");
        e.ctrl(area, KeyCode.N);
        e.ctrl(area, KeyCode.N);

        assertEquals(0, selectionLength(area), "motion after typing moves the caret, it does not select");
        e.type(area, KeyCode.Z, "z");
        assertEquals(
                "xybeta one\nbeta two\nbezta three\nbeta four\n",
                FxTestSupport.callOnFx(area::getText),
                "the next key inserts; it replaces nothing");
    }

    @Test
    void undoDeactivatesTheMark() throws Exception {
        EditorBuffer b = e.open("undone.txt", BETA);
        CodeArea area = b.getArea();
        FxTestSupport.runOnFx(() -> area.insertText(0, "Q"));
        FxTestSupport.runOnFx(() -> area.moveTo(0));

        e.ctrl(area, KeyCode.SPACE);
        e.ctrl(area, KeyCode.SLASH); // C-/ = undo
        assertEquals(BETA, FxTestSupport.callOnFx(area::getText), "the insert was undone");
        FxTestSupport.runOnFx(() -> area.moveTo(0));
        e.ctrl(area, KeyCode.N);

        assertEquals(0, selectionLength(area), "undo modified the buffer, so the mark is no longer active");
    }

    @Test
    void theMarkDoesNotFollowTheUserToAnotherTab() throws Exception {
        EditorBuffer a = e.open("a.txt", "alpha one\nalpha two\nalpha three\n");
        EditorBuffer b = e.open("b.txt", BETA);
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(0));
        e.ctrl(b.getArea(), KeyCode.SPACE); // mark set in b.txt only

        e.select(a.getPath());
        FxTestSupport.runOnFx(() -> a.getArea().moveTo(0));
        e.ctrl(a.getArea(), KeyCode.N);
        e.ctrl(a.getArea(), KeyCode.F);
        assertEquals(0, selectionLength(a.getArea()), "no mark was ever set in a.txt");

        e.select(b.getPath()); // back in b.txt its own mark is still active, as in Emacs
        FxTestSupport.runOnFx(() -> b.getArea().selectRange(0, 0));
        e.ctrl(b.getArea(), KeyCode.N);
        assertTrue(selectionLength(b.getArea()) > 0, "b.txt keeps the mark that was set there");
    }
}
