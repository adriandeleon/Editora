package com.editora.ui;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The mark ring through the real {@link CommandRegistry} against a wired window. The pure
 * {@code MarkRingTest} covers the ring arithmetic; this covers the wiring the pure test cannot — that
 * {@code C-SPC} pushes the caret, {@code pop-mark} moves it back and cycles, the ring is <b>per buffer</b>,
 * and — the case that actually matters — a mark still points at its text after the document is edited.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarkRingFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
    }

    private EditorBuffer open(String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            return b;
        });
    }

    private void caret(EditorBuffer b, int pos) throws Exception {
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(pos));
    }

    private int caret(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getCaretPosition());
    }

    /** Drops a mark at the current caret (Emacs C-SPC). */
    private void mark(EditorBuffer b, int pos) throws Exception {
        caret(b, pos);
        run("edit.setMark");
    }

    // --- push / pop --------------------------------------------------------------------------------

    @Test
    void popReturnsToTheMarkedPosition() throws Exception {
        EditorBuffer b = open("0123456789abcdef");
        mark(b, 3); // set a mark at offset 3
        caret(b, 12); // wander off
        run("edit.popMark");
        assertEquals(3, caret(b), "pop-mark jumps back to where C-SPC was pressed");
    }

    @Test
    void repeatedPopsCycleThroughTheMarks() throws Exception {
        EditorBuffer b = open("0123456789abcdef");
        mark(b, 2);
        mark(b, 6);
        mark(b, 10);
        caret(b, 15);
        run("edit.popMark");
        assertEquals(10, caret(b), "newest mark first");
        run("edit.popMark");
        assertEquals(6, caret(b));
        run("edit.popMark");
        assertEquals(2, caret(b));
        run("edit.popMark");
        assertEquals(15, caret(b), "and back to where the cycle started");
    }

    @Test
    void popWithNoMarksReportsAndDoesNotMove() throws Exception {
        EditorBuffer b = open("hello world");
        caret(b, 4);
        run("edit.popMark");
        assertEquals(4, caret(b), "nothing on the ring — the caret stays put");
    }

    // --- per buffer -------------------------------------------------------------------------------

    @Test
    void theRingIsPerBuffer() throws Exception {
        EditorBuffer first = open("first buffer text");
        mark(first, 5);
        EditorBuffer second = open("second buffer text"); // now active; its ring is empty
        caret(second, 9);
        run("edit.popMark");
        assertEquals(9, caret(second), "the second buffer does not see the first buffer's mark");
        assertEquals(1, FxTestSupport.callOnFx(first::markRingSize), "and the first buffer still has its mark");
    }

    // --- the case that matters: marks track edits -------------------------------------------------

    @Test
    void aMarkFollowsItsTextWhenYouInsertBeforeIt() throws Exception {
        EditorBuffer b = open("0123456789");
        mark(b, 8); // mark the '8'
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "XXXXX")); // push everything right by 5
        caret(b, 0);
        run("edit.popMark");
        assertEquals(13, caret(b), "the mark shifted with the text, so it still lands on the '8'");
        assertEquals('8', FxTestSupport.callOnFx(() -> b.getArea().getText().charAt(caret(b))));
    }

    @Test
    void aMarkClampsWhenItsTextIsDeleted() throws Exception {
        EditorBuffer b = open("0123456789");
        mark(b, 7);
        FxTestSupport.runOnFx(() -> b.getArea().deleteText(5, 9)); // delete [5,9), which contained the mark
        caret(b, 0);
        run("edit.popMark");
        assertEquals(5, caret(b), "the mark collapsed to the edit site rather than dangling out of bounds");
    }
}
