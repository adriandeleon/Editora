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
 * Multi-caret editing in a real window, where the current-line highlight is on (the default): an edit that
 * shortens the document while an extra caret sits near its end used to throw from the change notification
 * and leave the buffer dropping every later key. {@code MultiCaretEditingFxTest} covers the bare buffer,
 * which has no line highlighter and never showed it.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiCaretNearEndFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        fx.dispose();
    }

    private EditorBuffer open(String content, int primary, int... extras) throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.setMultiCaretEnabled(true);
            b.setLineHighlightOn(true);
            b.getArea().requestFocus();
            return b;
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            buffer.getArea().moveTo(primary);
            Object manager =
                    FxTestSupport.call(FxTestSupport.field(buffer, "multiCaret"), "getManager", new Class<?>[] {});
            for (int offset : extras) {
                FxTestSupport.call(manager, "addCaretAt", new Class<?>[] {int.class}, offset);
            }
        });
        return buffer;
    }

    private static void press(EditorBuffer b, KeyCode code) throws Exception {
        FxTestSupport.runOnFx(() ->
                b.getArea().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)));
    }

    private static void type(EditorBuffer b, String ch) throws Exception {
        FxTestSupport.runOnFx(() -> b.getArea()
                .fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, ch, ch, KeyCode.UNDEFINED, false, false, false, false)));
    }

    private static String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    @Test
    void backspaceWithACaretOnTheLastLineKeepsWorking() throws Exception {
        EditorBuffer b = open("one;\ntwo;\nthree;\n", 4, 9, 16);
        press(b, KeyCode.BACK_SPACE);
        assertEquals("one\ntwo\nthree\n", text(b));
        press(b, KeyCode.BACK_SPACE);
        assertEquals("on\ntw\nthre\n", text(b));
        type(b, "x");
        assertEquals("onx\ntwx\nthrex\n", text(b));
        press(b, KeyCode.ESCAPE);
        type(b, "y");
        assertEquals("onxy\ntwx\nthrex\n", text(b));
    }

    @Test
    void typingOverEveryOccurrenceNearTheEnd() throws Exception {
        String src = "connection = open();\nuse(connection);\nclose(connection);\n";
        EditorBuffer b = open(src, 3);
        FxTestSupport.runOnFx(b::selectAllOccurrences);
        type(b, "c");
        type(b, "o");
        type(b, "n");
        assertEquals("con = open();\nuse(con);\nclose(con);\n", text(b));
    }
}
