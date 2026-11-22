package com.editora.ui;

import java.util.List;

import javafx.scene.Scene;
import javafx.scene.layout.VBox;

import com.editora.config.Breakpoint;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A deleted line's breakpoint comes back when Undo (or a paste right after the cut) puts the line back — the very
 * breakpoint that was deleted, not another one with the same text, and not long afterwards through an unrelated
 * paste.
 */
@org.junit.jupiter.api.extension.ExtendWith(OffFxInPlace.class)
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BreakpointReviveFxTest {

    private static final String DOC = "zero\nfoo()\ntwo\nfoo()\nfour\n";

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer buffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(DOC);
            new Scene(new VBox(b.getNode()), 800, 600);
            b.getArea().getUndoManager().forgetHistory();
            return b;
        });
    }

    private static List<Integer> lines(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> List.copyOf(b.getBreakpointManager().lines()));
    }

    private static Breakpoint bp(EditorBuffer b, int line) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getBreakpointManager().get(line));
    }

    private static void deleteLine(EditorBuffer b, int line) {
        var area = b.getArea();
        area.deleteText(area.getAbsolutePosition(line, 0), area.getAbsolutePosition(line + 1, 0));
        area.getUndoManager().preventMerge();
    }

    @Test
    void deletingOneOfTwoSameTextLinesDropsThatOneAndUndoBringsItBack() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> {
            b.toggleBreakpoint(1);
            b.toggleBreakpoint(3);
            b.getBreakpointManager().setId(3, "keep");
            b.getBreakpointManager().setHitCondition(3, "5");
            deleteLine(b, 1);
        });
        assertEquals(List.of(2), lines(b));
        assertEquals("keep", bp(b, 2).id());
        FxTestSupport.runOnFx(() -> b.getArea().undo());
        assertEquals(List.of(1, 3), lines(b));
        assertEquals("", bp(b, 1).id(), "the deleted breakpoint had no id");
        assertEquals("", bp(b, 1).hitCondition(), "the deleted breakpoint had no hit condition");
        assertEquals("keep", bp(b, 3).id());
        assertEquals("5", bp(b, 3).hitCondition());
    }

    @Test
    void aRevivedBreakpointNeverTakesAnIdAnotherBreakpointHas() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> {
            b.toggleBreakpoint(1);
            b.getBreakpointManager().setId(1, "dup");
            var area = b.getArea();
            int at = area.getAbsolutePosition(1, 0);
            area.deleteText(at, area.getAbsolutePosition(2, 0)); // cut line 1
            b.toggleBreakpoint(2);
            b.getBreakpointManager().setId(2, "dup"); // another breakpoint took the id meanwhile
            area.insertText(area.getAbsolutePosition(0, 0), "foo()\n"); // paste it back
        });
        assertEquals(List.of(0, 3), lines(b));
        assertEquals("", bp(b, 0).id());
        assertEquals("dup", bp(b, 3).id());
    }

    @Test
    void aPasteRightAfterTheCutMovesTheBreakpoint() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> {
            b.toggleBreakpoint(1);
            b.getBreakpointManager().setCondition(1, "x > 1");
            deleteLine(b, 1);
            var area = b.getArea();
            area.insertText(area.getLength(), "foo()\n");
        });
        assertEquals(List.of(4), lines(b));
        assertEquals("x > 1", bp(b, 4).condition());
    }

    @Test
    void aLaterUnrelatedPasteDoesNotReviveAnOldBreakpoint() throws Exception {
        EditorBuffer b = buffer();
        long[] now = {0};
        com.editora.editor.debug.BreakpointManager.clockForTest(() -> now[0]);
        try {
            FxTestSupport.runOnFx(() -> {
                b.toggleBreakpoint(1);
                deleteLine(b, 1);
                var area = b.getArea();
                area.insertText(area.getAbsolutePosition(0, 4), "!"); // other typing in between
                area.getUndoManager().preventMerge();
                now[0] += java.util.concurrent.TimeUnit.MINUTES.toNanos(3); // much later
                area.insertText(area.getLength(), "foo()\n");
            });
            assertEquals(List.of(), lines(b));
        } finally {
            com.editora.editor.debug.BreakpointManager.clockForTest(null);
        }
    }

    /** A cut, some typing, then the paste: the breakpoint moves with its line (LineMarkManagersFxTest's case). */
    @Test
    void aPasteSoonAfterTheCutStillMovesTheBreakpointAfterTyping() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> {
            b.toggleBreakpoint(1);
            deleteLine(b, 1);
            var area = b.getArea();
            area.insertText(area.getAbsolutePosition(0, 4), "!");
            area.getUndoManager().preventMerge();
            area.insertText(area.getLength(), "foo()\n");
        });
        assertEquals(1, lines(b).size(), "pasted back soon after: the breakpoint is on it again");
    }

    @Test
    void undoStillBringsItBackAfterOtherEdits() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> {
            b.toggleBreakpoint(1);
            b.getBreakpointManager().setCondition(1, "x > 1");
            deleteLine(b, 1);
            var area = b.getArea();
            area.insertText(area.getAbsolutePosition(0, 4), "!");
            area.getUndoManager().preventMerge();
            area.undo();
            area.undo();
        });
        assertEquals(List.of(1), lines(b));
        assertEquals("x > 1", bp(b, 1).condition());
    }
}
