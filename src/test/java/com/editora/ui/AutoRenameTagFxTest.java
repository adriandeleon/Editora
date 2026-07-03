package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end (headless-FX) coverage of the Auto Rename Tag wiring: a real {@link EditorBuffer} with a
 * document edit must mirror a tag-name change onto the paired tag (the pure {@code TagRename} core is
 * unit-tested separately — this exercises the {@code plainTextChanges} subscription, the re-entrancy
 * guard, and the caret restore around the mirrored {@code replaceText}).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoRenameTagFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private EditorBuffer htmlBuffer(String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("html");
            b.setContent(text);
            b.setAutoRenameTag(true);
            b.getNode();
            CodeArea area = FxTestSupport.field(b, "area");
            area.getUndoManager().forgetHistory(); // the initial setContent must not be undoable in tests
            return b;
        });
    }

    @Test
    void realKeyTypedEventMirrorsLikeTheApp() throws Exception {
        // The running app's path: multi-caret installed + a KEY_TYPED event through the area's filters.
        EditorBuffer b = htmlBuffer("<div>text</div>");
        FxTestSupport.runOnFx(() -> {
            b.setMultiCaretEnabled(true);
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(4);
            area.requestFocus();
            javafx.event.Event.fireEvent(
                    area,
                    new javafx.scene.input.KeyEvent(
                            javafx.scene.input.KeyEvent.KEY_TYPED,
                            "x",
                            "x",
                            javafx.scene.input.KeyCode.UNDEFINED,
                            false,
                            false,
                            false,
                            false));
        });
        assertEquals("<divx>text</divx>", FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void typingInAnOpenTagNameRenamesTheCloser() throws Exception {
        EditorBuffer b = htmlBuffer("<div>text</div>");
        FxTestSupport.runOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(4);
            area.replaceText(4, 4, "x"); // type "x": <div|> → <divx>
        });
        assertEquals("<divx>text</divx>", FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void typingInACloseTagNameRenamesTheOpener() throws Exception {
        EditorBuffer b = htmlBuffer("<div>text</div>");
        FxTestSupport.runOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(14);
            area.replaceText(14, 14, "x"); // </div|> → </divx>
        });
        assertEquals("<divx>text</divx>", FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void caretStaysWhereTheUserTyped() throws Exception {
        EditorBuffer b = htmlBuffer("<div>text</div>");
        int caret = FxTestSupport.callOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(4);
            area.replaceText(4, 4, "x");
            return area.getCaretPosition();
        });
        assertEquals(5, caret, "caret right after the typed char, not at the mirrored closer");
    }

    @Test
    void undoRedoDoesNotReMirror() throws Exception {
        EditorBuffer b = htmlBuffer("<div>text</div>");
        FxTestSupport.runOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(4);
            area.replaceText(4, 4, "x");
        });
        assertEquals("<divx>text</divx>", FxTestSupport.callOnFx(b::getContent));
        // Undo everything: both the mirror and the typed char revert, with no mirror loop re-adding them.
        FxTestSupport.runOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            while (area.isUndoAvailable()) {
                area.undo();
            }
        });
        assertEquals("<div>text</div>", FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void disabledSettingLeavesTheCloserAlone() throws Exception {
        EditorBuffer b = htmlBuffer("<div>text</div>");
        FxTestSupport.runOnFx(() -> {
            b.setAutoRenameTag(false);
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(4);
            area.replaceText(4, 4, "x");
        });
        assertEquals("<divx>text</div>", FxTestSupport.callOnFx(b::getContent));
    }
}
