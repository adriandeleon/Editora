package com.editora.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.editops.EmacsEdits;
import com.editora.editor.EditorBuffer;
import com.editora.editor.QueryReplace;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The keys a modal session owns (query-replace, zap-to-char) must not also reach the buffer's own typing
 * assists. Those are key filters on the area; the session's used to be appended to the same node, so
 * auto-indent, smart Backspace and auto-close ran first and edited the document with the session's key.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModalKeySessionFxTest {

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

    private EditorBuffer open(String content, int caret) throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.getArea().requestFocus();
            b.getArea().moveTo(caret);
            return b;
        });
        FxTestSupport.drainFx();
        return buffer;
    }

    private Object editing() {
        return FxTestSupport.field(fx.controller, "editing");
    }

    private void queryReplace(EditorBuffer b, String query, String replacement) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                editing(),
                "beginQueryReplace",
                new Class[] {EditorBuffer.class, QueryReplace.Spec.class},
                b,
                new QueryReplace.Spec(query, replacement, false, false, false, false)));
    }

    private void type(EditorBuffer b, String ch) throws Exception {
        FxTestSupport.runOnFx(() -> b.getArea()
                .fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED, false, false, false, false)));
    }

    private void press(EditorBuffer b, KeyCode code) throws Exception {
        FxTestSupport.runOnFx(() ->
                b.getArea().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)));
    }

    private String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    private boolean sessionActive() throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.field(editing(), "queryReplaceSession") != null);
    }

    @Test
    void enterEndsQueryReplaceWithoutInsertingANewline() throws Exception {
        EditorBuffer b = open("foo one\nfoo two\n", 0);
        queryReplace(b, "foo", "bar");
        type(b, "y");
        press(b, KeyCode.ENTER);
        assertFalse(sessionActive());
        assertEquals("bar one\nfoo two\n", text(b));
    }

    @Test
    void backspaceSkipsAMatchWithoutEatingItsIndentation() throws Exception {
        String src = "class A {\n    foo();\n    foo();\n}\n";
        EditorBuffer b = open(src, 0);
        queryReplace(b, "foo", "bar");
        press(b, KeyCode.BACK_SPACE);
        press(b, KeyCode.TAB);
        type(b, "(");
        assertEquals(src, text(b));
    }

    @Test
    void quittingWithQDoesNotTypeTheQ() throws Exception {
        EditorBuffer b = open("keep keep\n", 0);
        queryReplace(b, "keep", "drop");
        press(b, KeyCode.Q);
        type(b, "q");
        assertFalse(sessionActive());
        assertEquals("keep keep\n", text(b));
    }

    @Test
    void zapToCharTakesAQuoteThatAutoCloseWouldHaveSkippedOver() throws Exception {
        String src = "call(a, \"bar\") + more(\"x\");";
        int caret = src.indexOf('"');
        EditorBuffer b = open(src, caret);
        FxTestSupport.runOnFx(() -> FxTestSupport.call(editing(), "zapToChar", new Class[] {}));
        type(b, "\"");
        EmacsEdits.Edit edit = EmacsEdits.zapToChar(src, caret, '"');
        assertEquals(src.substring(0, edit.from()) + edit.replacement() + src.substring(edit.to()), text(b));
    }

    @Test
    void movingTheCaretDisarmsZapToChar() throws Exception {
        EditorBuffer b = open("alpha beta gamma", 0);
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(editing(), "zapToChar", new Class[] {});
            b.getArea().moveTo(2);
        });
        type(b, "e");
        assertEquals("alepha beta gamma", text(b), "the e is typed, not taken as the zap target");
    }
}
