package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end (headless-FX) coverage of tag auto-closing: a real {@link EditorBuffer} with a real
 * {@code KEY_TYPED} {@code >} through the auto-close key filter must insert the matching closer and
 * leave the caret between the tags (the pure {@code TagAutoClose} decision is unit-tested separately).
 * Also covers the macro-replay path ({@code typeString}) and the setting gate.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoCloseTagsFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private EditorBuffer htmlBuffer(String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("html");
            b.setContent(text);
            b.setAutoCloseTags(true);
            b.getNode();
            return b;
        });
    }

    private static void typeKey(CodeArea area, String ch) {
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

    @Test
    void typingTheClosingBracketInsertsTheCloser() throws Exception {
        EditorBuffer b = htmlBuffer("<body");
        int caret = FxTestSupport.callOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(5);
            typeKey(area, ">");
            return area.getCaretPosition();
        });
        assertEquals("<body></body>", FxTestSupport.callOnFx(b::getContent));
        assertEquals(6, caret, "caret between the tags");
    }

    @Test
    void voidElementDoesNotAutoClose() throws Exception {
        EditorBuffer b = htmlBuffer("<br");
        FxTestSupport.runOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(3);
            typeKey(area, ">");
        });
        assertEquals("<br>", FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void macroReplayTypingAlsoAutoCloses() throws Exception {
        EditorBuffer b = htmlBuffer("");
        FxTestSupport.runOnFx(() -> b.typeString("<section>"));
        assertEquals("<section></section>", FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void disabledSettingInsertsAPlainBracket() throws Exception {
        EditorBuffer b = htmlBuffer("<body");
        FxTestSupport.runOnFx(() -> {
            b.setAutoCloseTags(false);
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(5);
            typeKey(area, ">");
        });
        assertEquals("<body>", FxTestSupport.callOnFx(b::getContent));
    }
}
