package com.editora.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for the stacked-{@code BACK_SPACE}-filter over-delete: the auto-close empty-pair handler and the
 * smart-backspace handler are both {@code KEY_PRESSED} filters on the same {@code area}, and JavaFX runs every
 * filter on a node regardless of {@code consume()} — so after the empty-pair handler deletes {@code ()} and
 * consumes, the smart-backspace handler used to run anyway on the now-blank indent and delete the indentation
 * (and the preceding newline). One Backspace deleted three things. Fires a real {@code BACK_SPACE} event
 * through the buffer's filters so both handlers participate.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmartBackspacePairFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static void backspace(EditorBuffer b) {
        b.getArea()
                .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.BACK_SPACE, false, false, false, false));
    }

    @Test
    void backspaceOnAnEmptyPairInLeadingWhitespaceRemovesOnlyThePair() throws Exception {
        String result = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("x\n    ()"); // an indented blank line where "(" was just typed → "()"
            b.getArea().moveTo(7); // caret between "(" (index 6) and ")" (index 7)
            backspace(b);
            return b.getArea().getText();
        });
        // The pair is gone; the line's indentation and the newline survive.
        assertEquals("x\n    ", result, "one Backspace must remove only the empty pair, not the indent + newline");
    }

    @Test
    void smartBackspaceStillCollapsesABlankIndentedLine() throws Exception {
        // Guard the fix didn't disable the normal smart-backspace (empty-pair handler doesn't fire here).
        String result = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("x\n    "); // a blank auto-indented line, caret at end
            b.getArea().moveTo(6);
            backspace(b);
            return b.getArea().getText();
        });
        assertEquals("x", result, "Backspace on a blank indented line still jumps back to the previous line's end");
    }
}
