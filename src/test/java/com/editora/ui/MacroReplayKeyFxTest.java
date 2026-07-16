package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The replay half of the bare-key fix: a KEY step must do to the document exactly what the key does when
 * pressed by hand. There is no "apply" method to reuse for these — the behavior lives in the area's own
 * input map and in the Backspace filters — so {@code pressKey} re-dispatches a real event at the area.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MacroReplayKeyFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void backspaceDeletesTheCharacterBeforeTheCaret() throws Exception {
        String out = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("abc");
            b.getArea().moveTo(3);
            b.pressKey("BACK_SPACE");
            return b.getArea().getText();
        });
        assertEquals("ab", out);
    }

    @Test
    void deleteRemovesTheCharacterAfterTheCaret() throws Exception {
        String out = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("abc");
            b.getArea().moveTo(0);
            b.pressKey("DELETE");
            return b.getArea().getText();
        });
        assertEquals("bc", out);
    }

    /** The whole point: "type x, Backspace, type y" replays as "y", not "xy". */
    @Test
    void aTextKeyTextSequenceReplaysFaithfully() throws Exception {
        String out = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("");
            b.typeString("x");
            b.pressKey("BACK_SPACE");
            b.typeString("y");
            return b.getArea().getText();
        });
        assertEquals("y", out);
    }

    @Test
    void arrowsMoveTheCaret() throws Exception {
        int caret = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("one\ntwo");
            b.getArea().moveTo(0);
            b.pressKey("DOWN");
            return b.getArea().getCaretPosition();
        });
        assertEquals(4, caret, "DOWN moves to the start of line 2");
    }

    /** A junk token (hand-edited macros.json, or a step typed into the Settings editor) must not throw. */
    @Test
    void anUnknownKeyNameIsIgnored() throws Exception {
        String out = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("abc");
            b.pressKey("NOT_A_KEY");
            b.pressKey("");
            b.pressKey(null);
            return b.getArea().getText();
        });
        assertEquals("abc", out);
    }
}
