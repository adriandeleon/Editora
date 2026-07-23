package com.editora.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code C-u} prefix argument end to end against a wired window: synthetic key events fire through the
 * real scene-installed {@link com.editora.command.KeyDispatcher}, driving real commands on a real buffer.
 * {@code PrefixArgDispatchTest} covers the dispatcher in isolation; this proves the {@code MainController}
 * wiring — that {@code C-u N C-n} moves the caret N lines and {@code C-u N x} inserts N characters.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrefixArgWindowFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private EditorBuffer open(String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.getArea().moveTo(0);
            return b;
        });
    }

    /** Fires a KEY_PRESSED at the buffer's area so it travels through the scene's dispatcher filter. */
    private void press(EditorBuffer b, KeyCode code, boolean ctrl) throws Exception {
        FxTestSupport.runOnFx(() ->
                b.getArea().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, false, false)));
    }

    /** Fires the press+typed pair for a self-inserting character. */
    private void typeChar(EditorBuffer b, char c) throws Exception {
        KeyCode code = c == '-' ? KeyCode.MINUS : KeyCode.getKeyCode(String.valueOf(Character.toUpperCase(c)));
        FxTestSupport.runOnFx(() -> {
            b.getArea().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            b.getArea()
                    .fireEvent(new KeyEvent(
                            KeyEvent.KEY_TYPED, String.valueOf(c), "", KeyCode.UNDEFINED, false, false, false, false));
        });
    }

    private int caretParagraph(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph());
    }

    @Test
    void universalDigitRepeatsAMotionCommand() throws Exception {
        EditorBuffer b = open("l0\nl1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9");
        press(b, KeyCode.U, true); // C-u
        press(b, KeyCode.DIGIT3, false); // 3
        press(b, KeyCode.N, true); // C-n  → down three lines
        assertEquals(3, caretParagraph(b), "C-u 3 C-n moved the caret three lines");
    }

    @Test
    void bareUniversalMovesFourLines() throws Exception {
        EditorBuffer b = open("l0\nl1\nl2\nl3\nl4\nl5");
        press(b, KeyCode.U, true);
        press(b, KeyCode.N, true);
        assertEquals(4, caretParagraph(b), "a bare C-u is an argument of four");
    }

    @Test
    void universalDigitSelfInsertsACharacter() throws Exception {
        EditorBuffer b = open("");
        press(b, KeyCode.U, true);
        press(b, KeyCode.DIGIT5, false);
        typeChar(b, '-'); // C-u 5 - → five dashes
        assertEquals("-----", FxTestSupport.callOnFx(() -> b.getArea().getText()));
    }
}
