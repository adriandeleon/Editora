package com.editora.ui;

import java.util.function.UnaryOperator;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A command run from a bare caret must leave a bare caret. A whole-buffer line transform used to select the
 * entire document (caret at its end) and a comment toggle the whole line, so the next typed character
 * replaced it.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BareCaretTransformFxTest {

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
            b.setLanguageOverride("java");
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            return b;
        });
    }

    @Test
    void wholeBufferLineTransformKeepsTheCaretOnItsLine() throws Exception {
        EditorBuffer b = open("one  \ntwo  \nthree  \nfour\n");
        UnaryOperator<String> trim = s -> s.replace("  \n", "\n");
        int[] state = FxTestSupport.callOnFx(() -> {
            CodeArea area = b.getArea();
            area.moveTo(1, 2);
            FxTestSupport.call(
                    FxTestSupport.field(fx.controller, "editing"),
                    "lineTransform",
                    new Class[] {UnaryOperator.class},
                    trim);
            return new int[] {area.getSelection().getLength(), area.getCurrentParagraph(), area.getCaretColumn()};
        });
        assertEquals(
                "one\ntwo\nthree\nfour\n",
                FxTestSupport.callOnFx(() -> b.getArea().getText()));
        assertEquals(0, state[0], "nothing selected");
        assertEquals(1, state[1], "caret still on line 2");
        assertEquals(2, state[2]);
    }

    @Test
    void lineTransformOverASelectionStillReselectsItsResult() throws Exception {
        EditorBuffer b = open("b\na\nc\n");
        UnaryOperator<String> upper = String::toUpperCase;
        int length = FxTestSupport.callOnFx(() -> {
            CodeArea area = b.getArea();
            area.selectRange(0, 3);
            FxTestSupport.call(
                    FxTestSupport.field(fx.controller, "editing"),
                    "lineTransform",
                    new Class[] {UnaryOperator.class},
                    upper);
            return area.getSelection().getLength();
        });
        assertEquals(3, length);
    }

    @Test
    void commentToggleFromABareCaretSelectsNothing() throws Exception {
        EditorBuffer b = open("class A {\n    foo();\n}\n");
        int[] state = FxTestSupport.callOnFx(() -> {
            CodeArea area = b.getArea();
            area.moveTo(1, 6);
            b.toggleComment();
            return new int[] {area.getSelection().getLength(), area.getCurrentParagraph(), area.getCaretColumn()};
        });
        assertEquals(
                "class A {\n    // foo();\n}\n",
                FxTestSupport.callOnFx(() -> b.getArea().getText()));
        assertEquals(0, state[0]);
        assertEquals(1, state[1]);
        assertEquals(9, state[2], "still between the two o's");
    }
}
