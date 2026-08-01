package com.editora.ui;

import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.input.KeyEvent;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The buffer half of smart-semicolon placement (#746): the coordinate translation that moves a just-typed
 * {@code ;} to where the server said it belongs. That translation is the part most easily wrong and least
 * visible — the server answers in <b>pre-insert</b> coordinates while the document already contains the
 * semicolon, so a same-line target needs shifting past it and a later-line target does not.
 *
 * <p>The requester is stubbed, so no server is involved; the wire shapes are pinned in
 * {@code JdtlsSmartSemicolonTest}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmartSemicolonFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @org.junit.jupiter.api.AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /**
     * Answers {@code target} to every request, and records what it was asked. The buffer goes into a real
     * window: the correction refuses to touch a detached buffer (a {@code getScene() == null} guard), so a
     * bare buffer would silently exercise nothing.
     */
    private EditorBuffer buffer(String src, int[] target, AtomicReference<int[]> asked) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.setContent(src);
            b.setLspActive(true);
            b.setSmartSemicolonEnabled(true);
            b.setLspSmartSemicolonRequester((line, character, cb) -> {
                asked.set(new int[] {line, character});
                // Answer on a LATER pulse, as LspManager always does — the correction is only valid once
                // the semicolon has actually landed, and a synchronous answer arrives before it has.
                javafx.application.Platform.runLater(() -> cb.accept(target));
            });
            return b;
        });
    }

    /**
     * Types {@code ;} at (line, col) exactly as production does: the real KEY_TYPED event runs the capture
     * filter (where the smart-semicolon hook lives) and then RichTextFX's own handler performs the
     * insertion, because the hook never consumes. Inserting the character by hand as well would double it.
     */
    private void typeSemicolon(EditorBuffer b, int line, int col) throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(line, col);
            area.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, ";", ";", null, false, false, false, false));
        });
    }

    private String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> ((CodeArea) FxTestSupport.field(b, "area")).getText());
    }

    @Test
    void aSameLineTargetMovesTheSemicolonPastTheRestOfTheExpression() throws Exception {
        // line 1 is "int n = compute(1, 2)"; caret at col 20 sits just before the ')'. The server answers
        // col 21 — one past it — in PRE-insert coordinates.
        String src = "class A {\nint n = compute(1, 2)\n}\n";
        var asked = new AtomicReference<int[]>();
        EditorBuffer b = buffer(src, new int[] {1, 21}, asked);

        typeSemicolon(b, 1, 20);

        assertEquals("class A {\nint n = compute(1, 2);\n}\n", text(b), "the ';' moved past the ')'");
        assertEquals(1, asked.get()[0], "asked about the typed line");
        assertEquals(20, asked.get()[1], "asked with the PRE-insert column");
    }

    @Test
    void aLaterLineTargetNeedsNoColumnShift() throws Exception {
        // A statement wrapped over two lines: typing ';' inside the call on line 1, the statement ends on
        // line 2 after the ')'. Columns on line 2 are untouched by the insertion on line 1.
        String src = "class A {\nint n = compute(1,\n2)\n}\n";
        var asked = new AtomicReference<int[]>();
        EditorBuffer b = buffer(src, new int[] {2, 2}, asked);

        typeSemicolon(b, 1, 18); // just after the ',' … inside the argument list

        assertEquals("class A {\nint n = compute(1,\n2);\n}\n", text(b), "the ';' landed at the statement end");
    }

    @Test
    void anAnswerAtTheTypedPositionLeavesItAlone() throws Exception {
        // The ordinary answer: the caret is already at the statement end, so nothing should move.
        String src = "class A {\nint n = 1\n}\n";
        var asked = new AtomicReference<int[]>();
        EditorBuffer b = buffer(src, new int[] {1, 9}, asked);

        typeSemicolon(b, 1, 9);

        assertEquals("class A {\nint n = 1;\n}\n", text(b));
    }

    @Test
    void aNullAnswerLeavesTheSemicolonWhereItWasTyped() throws Exception {
        String src = "class A {\nint n = compute(1, 2)\n}\n";
        var asked = new AtomicReference<int[]>();
        EditorBuffer b = buffer(src, null, asked);

        typeSemicolon(b, 1, 20);

        assertEquals("class A {\nint n = compute(1, 2;)\n}\n", text(b), "no answer → exactly the typed result");
    }

    @Test
    void theMoveIsOneUndoStep() throws Exception {
        String src = "class A {\nint n = compute(1, 2)\n}\n";
        var asked = new AtomicReference<int[]>();
        EditorBuffer b = buffer(src, new int[] {1, 21}, asked);

        FxTestSupport.runOnFx(() ->
                ((CodeArea) FxTestSupport.field(b, "area")).getUndoManager().forgetHistory());
        typeSemicolon(b, 1, 20);
        assertEquals("class A {\nint n = compute(1, 2);\n}\n", text(b));

        // One undo takes back the move — the correction is a single ranged replaceText, not a
        // delete-plus-insert that would need two.
        FxTestSupport.runOnFx(() -> ((CodeArea) FxTestSupport.field(b, "area")).undo());
        assertEquals("class A {\nint n = compute(1, 2;)\n}\n", text(b), "back to the as-typed text");
    }

    @Test
    void theGatesKeepItSilent() throws Exception {
        String src = "class A {\nint n = compute(1, 2)\n}\n";
        var asked = new AtomicReference<int[]>();
        EditorBuffer b = buffer(src, new int[] {1, 21}, asked);

        FxTestSupport.runOnFx(() -> b.setSmartSemicolonEnabled(false));
        typeSemicolon(b, 1, 20);
        assertNull(asked.get(), "disabled → the server is never asked");

        FxTestSupport.runOnFx(() -> {
            b.setSmartSemicolonEnabled(true);
            b.setLspActive(false);
        });
        typeSemicolon(b, 1, 20);
        assertNull(asked.get(), "no LSP → the server is never asked");
    }
}
