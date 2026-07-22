package com.editora.ui;

import java.util.List;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Rectangle commands driven through the real {@link CommandRegistry} against a wired window — the
 * selection-to-rectangle resolution and the guards, which the pure {@code RectangleTest} cannot reach.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RectangleFxTest {

    private static final String GRID = "abcdef\nghijkl\nmnopqr";

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

    @BeforeEach
    void resetKilledRectangle() throws Exception {
        setKilled(List.of());
    }

    private void setKilled(List<String> lines) throws Exception {
        FxTestSupport.runOnFx(() -> {
            try {
                var f = MainController.class.getDeclaredField("killedRectangle");
                f.setAccessible(true);
                f.set(fx.controller, lines);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
    }

    /** Offset of {@code line}:{@code col}. */
    private static int at(String text, int line, int col) {
        int off = 0;
        for (int i = 0; i < line; i++) {
            off = text.indexOf('\n', off) + 1;
        }
        return off + col;
    }

    /** Opens {@code content} with a selection from one line:col to another — point and mark. */
    private EditorBuffer open(String content, int l1, int c1, int l2, int c2) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            // setContent is itself an undoable edit, and UndoMerge's boundary is an idle *pause* the
            // test never takes — without a clean baseline it merges with whatever the command does,
            // and a single undo would appear to revert the file to empty.
            b.getArea().getUndoManager().forgetHistory();
            b.getArea().selectRange(at(content, l1, c1), at(content, l2, c2));
            return b;
        });
    }

    private String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    @SuppressWarnings("unchecked")
    private List<String> killed() throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.field(fx.controller, "killedRectangle"));
    }

    // --- the core cycle --------------------------------------------------------------------------

    @Test
    void killRectangleRemovesTheColumnsAndRemembersThem() throws Exception {
        EditorBuffer b = open(GRID, 0, 2, 2, 4);
        run("edit.killRectangle");
        assertEquals("abef\nghkl\nmnqr", text(b));
        assertEquals(List.of("cd", "ij", "op"), killed());
    }

    @Test
    void killThenYankMovesTheRectangle() throws Exception {
        EditorBuffer b = open(GRID, 0, 2, 2, 4);
        run("edit.killRectangle");
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(at("abef\nghkl\nmnqr", 0, 4)));
        run("edit.yankRectangle");
        assertEquals("abefcd\nghklij\nmnqrop", text(b), "the rectangle keeps its shape across the move");
    }

    @Test
    void copyRectangleLeavesTheDocumentAlone() throws Exception {
        EditorBuffer b = open(GRID, 0, 2, 2, 4);
        run("edit.copyRectangle");
        assertEquals(GRID, text(b), "copy must not change the document");
        assertEquals(List.of("cd", "ij", "op"), killed());
    }

    @Test
    void deleteRectangleDoesNotDisturbTheRememberedRectangle() throws Exception {
        EditorBuffer b = open(GRID, 0, 2, 2, 4);
        run("edit.copyRectangle");
        FxTestSupport.runOnFx(() -> b.getArea().selectRange(at(GRID, 0, 0), at(GRID, 2, 1)));
        run("edit.deleteRectangle");
        assertEquals("bcdef\nhijkl\nnopqr", text(b));
        assertEquals(List.of("cd", "ij", "op"), killed(), "C-x r d discards rather than kills");
    }

    // --- the rest of the family -------------------------------------------------------------------

    @Test
    void clearRectangleBlanksTheColumns() throws Exception {
        EditorBuffer b = open(GRID, 0, 2, 2, 4);
        run("edit.clearRectangle");
        assertEquals("ab  ef\ngh  kl\nmn  qr", text(b));
    }

    @Test
    void openRectangleShiftsTheTextRight() throws Exception {
        EditorBuffer b = open(GRID, 0, 2, 2, 4);
        run("edit.openRectangle");
        assertEquals("ab  cdef\ngh  ijkl\nmn  opqr", text(b));
    }

    // --- guards ------------------------------------------------------------------------------------

    @Test
    void aRectangleCommandWithNoRegionLeavesTheDocumentAlone() throws Exception {
        EditorBuffer b = open(GRID, 1, 3, 1, 3); // point == mark: no region
        run("edit.killRectangle");
        assertEquals(GRID, text(b));
        assertEquals(List.of(), killed());
    }

    @Test
    void yankWithNothingRememberedLeavesTheDocumentAlone() throws Exception {
        EditorBuffer b = open(GRID, 0, 0, 0, 0);
        run("edit.yankRectangle");
        assertEquals(GRID, text(b));
    }

    @Test
    void aBackwardsSlantingSelectionStillFormsTheSameRectangle() throws Exception {
        // Point at line 0 col 4, mark at line 2 col 2 — the columns cross.
        EditorBuffer b = open(GRID, 0, 4, 2, 2);
        run("edit.killRectangle");
        assertEquals("abef\nghkl\nmnqr", text(b));
        assertEquals(List.of("cd", "ij", "op"), killed());
    }

    @Test
    void aRectangleEditIsASingleUndoStep() throws Exception {
        EditorBuffer b = open(GRID, 0, 2, 2, 4);
        run("edit.killRectangle");
        assertEquals("abef\nghkl\nmnqr", text(b));
        FxTestSupport.runOnFx(() -> b.getArea().undo());
        assertEquals(GRID, text(b), "one Ctrl-Z restores all three lines, not one line at a time");
    }
}
