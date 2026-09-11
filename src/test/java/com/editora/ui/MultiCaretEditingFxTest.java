package com.editora.ui;

import java.util.List;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Observable mutation, dirty-state, and undo contracts for the RichTextFX multi-caret integration. */
@Tag("fx")
class MultiCaretEditingFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void typingAtMultipleSelectionsIsOneUndoableDirtyEdit() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EditorBuffer buffer = buffer(async, "alpha beta gamma");
            FxTestSupport.runOnFx(() -> {
                buffer.placeOccurrenceCarets(List.of(new int[] {0, 5}, new int[] {11, 16}), 0);
                type(buffer.getArea(), "X");
            });

            assertEquals("X beta X", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));

            FxTestSupport.runOnFx(buffer.getArea()::undo);
            assertEquals("alpha beta gamma", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertFalse(FxTestSupport.callOnFx(buffer.getArea()::isUndoAvailable), "one undo reverts every caret");

            FxTestSupport.runOnFx(buffer.getArea()::redo);
            assertEquals("X beta X", FxTestSupport.callOnFx(buffer::getContent));
        }
    }

    @Test
    void backspaceAtMultipleCaretsUsesOriginalOffsetsAndUndoesTogether() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EditorBuffer buffer = buffer(async, "abc\ndef\n");
            FxTestSupport.runOnFx(() -> {
                addCaret(buffer, 1, 5);
                press(buffer.getArea(), KeyCode.BACK_SPACE);
            });

            assertEquals("bc\nef\n", FxTestSupport.callOnFx(buffer::getContent));
            FxTestSupport.runOnFx(buffer.getArea()::undo);
            assertEquals("abc\ndef\n", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer.getArea()::isUndoAvailable));
        }
    }

    @Test
    void forwardDeleteAtDocumentBoundariesKeepsEveryCaretValid() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EditorBuffer buffer = buffer(async, "abc");
            FxTestSupport.runOnFx(() -> {
                addCaret(buffer, 0, 3);
                press(buffer.getArea(), KeyCode.DELETE);
            });

            assertEquals("bc", FxTestSupport.callOnFx(buffer::getContent));
            FxTestSupport.runOnFx(buffer.getArea()::undo);
            assertEquals("abc", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer.getArea()::isUndoAvailable));
        }
    }

    @Test
    void pasteDistributesClipboardLinesAcrossCaretsAndUndoesTogether() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EditorBuffer buffer = buffer(async, "a\nb");
            FxTestSupport.runOnFx(() -> {
                addCaret(buffer, 0, 2);
                ClipboardContent content = new ClipboardContent();
                content.putString("ONE\nTWO");
                Clipboard.getSystemClipboard().setContent(content);
                assertTrue(buffer.multiCaretPaste());
            });

            assertEquals("ONEa\nTWOb", FxTestSupport.callOnFx(buffer::getContent));
            FxTestSupport.runOnFx(buffer.getArea()::undo);
            assertEquals("a\nb", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer.getArea()::isUndoAvailable));
        }
    }

    @Test
    void cutCopiesSelectionsInDocumentOrderAndUndoesAsOneEdit() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EditorBuffer buffer = buffer(async, "left middle right");
            FxTestSupport.runOnFx(() -> {
                buffer.placeOccurrenceCarets(List.of(new int[] {0, 4}, new int[] {12, 17}), 0);
                assertTrue(buffer.multiCaretCut());
            });

            assertEquals(" middle ", FxTestSupport.callOnFx(buffer::getContent));
            assertEquals(
                    "left\nright",
                    FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()));
            FxTestSupport.runOnFx(buffer.getArea()::undo);
            assertEquals("left middle right", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer.getArea()::isUndoAvailable));
        }
    }

    @Test
    void zeroWidthCutRemovesWholeLinesAndRestoresThemWithOneUndo() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EditorBuffer buffer = buffer(async, "one\ntwo\nthree");
            FxTestSupport.runOnFx(() -> {
                addCaret(buffer, 0, 8);
                assertTrue(buffer.multiCaretCut());
            });

            assertEquals("two\n", FxTestSupport.callOnFx(buffer::getContent));
            assertEquals(
                    "one\nthree",
                    FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()));
            FxTestSupport.runOnFx(buffer.getArea()::undo);
            assertEquals("one\ntwo\nthree", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer.getArea()::isUndoAvailable));
        }
    }

    private static EditorBuffer buffer(AsyncTestScope async, String text) throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            created.setContent(text);
            created.setMultiCaretEnabled(true);
            created.getNode();
            created.getArea().requestFocus();
            created.getArea().getUndoManager().forgetHistory();
            created.markClean();
            return created;
        });
        async.onClose(() -> FxTestSupport.runOnFx(buffer::dispose));
        return buffer;
    }

    private static void addCaret(EditorBuffer buffer, int primary, int extra) {
        CodeArea area = buffer.getArea();
        area.moveTo(primary);
        Object controller = FxTestSupport.field(buffer, "multiCaret");
        Object manager = FxTestSupport.call(controller, "getManager", new Class<?>[] {});
        FxTestSupport.call(manager, "addCaretAt", new Class<?>[] {int.class}, extra);
    }

    private static void type(CodeArea area, String text) {
        area.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, text, text, KeyCode.UNDEFINED, false, false, false, false));
    }

    private static void press(CodeArea area, KeyCode code) {
        area.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }
}
