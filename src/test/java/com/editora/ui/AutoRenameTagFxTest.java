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

    private static void type(CodeArea area, String text) {
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            javafx.event.Event.fireEvent(
                    area,
                    new javafx.scene.input.KeyEvent(
                            javafx.scene.input.KeyEvent.KEY_TYPED,
                            ch,
                            ch,
                            javafx.scene.input.KeyCode.UNDEFINED,
                            false,
                            false,
                            false,
                            false));
        }
    }

    @Test
    void typingSeveralCharactersInACloserKeepsThemInOrder() throws Exception {
        // The mirror renames the opener ABOVE the caret, shifting it. It used to run inside the change
        // event, before RichTextFX placed the caret at a pre-mirror offset — so the caret landed one short
        // and "xy" came out as "</divyx>", with the opener renamed to the same scrambled name.
        EditorBuffer b = htmlBuffer("<div>text</div>");
        int caret = FxTestSupport.callOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(14); // </div|>
            area.requestFocus();
            type(area, "xy");
            return area.getCaretPosition();
        });
        assertEquals("<divxy>text</divxy>", FxTestSupport.callOnFx(b::getContent));
        assertEquals("<divxy>text</divxy".length(), caret, "caret right after the typed characters");
    }

    @Test
    void typingSeveralCharactersInAnOpenerKeepsThemInOrder() throws Exception {
        EditorBuffer b = htmlBuffer("<div>text</div>");
        int caret = FxTestSupport.callOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(4); // <div|>
            area.requestFocus();
            type(area, "xy");
            return area.getCaretPosition();
        });
        assertEquals("<divxy>text</divxy>", FxTestSupport.callOnFx(b::getContent));
        assertEquals("<divxy".length(), caret);
    }

    @Test
    void backspaceInACloserRenamesTheOpenerAndKeepsTheCaretInPlace() throws Exception {
        EditorBuffer b = htmlBuffer("<span>text</span>");
        int caret = FxTestSupport.callOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo("<span>text</span".length());
            area.deletePreviousChar();
            area.deletePreviousChar();
            return area.getCaretPosition();
        });
        assertEquals("<sp>text</sp>", FxTestSupport.callOnFx(b::getContent));
        assertEquals("<sp>text</sp".length(), caret, "caret still at the end of the closer's name");
    }

    @Test
    void backspaceInAnOpenerRenamesTheCloserAndKeepsTheCaretInPlace() throws Exception {
        EditorBuffer b = htmlBuffer("<span>text</span>");
        int caret = FxTestSupport.callOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo("<span".length());
            area.deletePreviousChar();
            area.deletePreviousChar();
            return area.getCaretPosition();
        });
        assertEquals("<sp>text</sp>", FxTestSupport.callOnFx(b::getContent));
        assertEquals("<sp".length(), caret);
    }

    @Test
    void aSelectionBelowTheMirrorSurvivesIt() throws Exception {
        // A programmatic rename of the closer (a completion, a refactor) leaves the caret after it; the
        // opener's mirror sits above and must shift the caret with the text.
        EditorBuffer b = htmlBuffer("<b>x</b> tail");
        int caret = FxTestSupport.callOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.replaceText(6, 7, "strong"); // </b> → </strong>
            return area.getCaretPosition();
        });
        assertEquals("<strong>x</strong> tail", FxTestSupport.callOnFx(b::getContent));
        assertEquals("<strong>x</strong".length(), caret);
    }

    @Test
    void severalCaretsTypingInTagNamesDoNotMirror() throws Exception {
        // With extra carets every caret's edit is its own tag-name change; mirroring each would rename
        // pairs the user never touched (and fight the multi-caret landing positions). It is a no-op.
        EditorBuffer b = htmlBuffer("<a>x</a><b>y</b>");
        FxTestSupport.runOnFx(() -> {
            b.setMultiCaretEnabled(true);
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(2); // <a|>
            org.fxmisc.richtext.multi.MultiCaretController<?, ?, ?> multi = FxTestSupport.field(b, "multiCaret");
            multi.getManager().addCaretAt(10); // <b|>
            area.requestFocus();
            type(area, "z");
        });
        assertEquals("<az>x</a><bz>y</b>", FxTestSupport.callOnFx(b::getContent));
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
