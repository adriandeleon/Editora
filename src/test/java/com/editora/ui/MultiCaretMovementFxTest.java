package com.editora.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the Emacs movement chords fan out to every caret when extras exist (#635). Movement chords are
 * resolved by the scene-level {@code KeyDispatcher} on the primary caret; the {@code nav.*} commands now
 * branch to the fork's multi-caret movement, so running one through the real {@link CommandRegistry} must
 * move <em>all</em> carets. Extra-caret positions are read via reflection into the vendored fork (the only
 * way to observe them) — {@code MultiCaretManager.extras[].caret.getPosition()}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiCaretMovementFxTest {

    // line 0 "aaaa" [0..4], line 1 "bbbb" [5..9], line 2 "cccc" [10..14]
    private static final String SRC = "aaaa\nbbbb\ncccc\n";

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

    /** Two carets, one on line 0 and one on line 1, both at column 0. */
    private EditorBuffer twoCaretBuffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(SRC);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.setMultiCaretEnabled(true);
            CodeArea area = FxTestSupport.field(b, "area");
            area.requestFocus();
            area.moveTo(0);
            // addCaretBelow is geometry-based (no layout headless); add the extra by absolute offset instead.
            Object controller = FxTestSupport.field(b, "multiCaret");
            Object manager = FxTestSupport.call(controller, "getManager", new Class[] {});
            FxTestSupport.call(manager, "addCaretAt", new Class[] {int.class}, 5); // line 1, column 0
            return b;
        });
    }

    /**
     * Forces a layout pass and reports whether the area actually has one.
     *
     * <p>Vertical caret movement is <b>geometry-based</b> — it has to know which offset sits on the next line
     * at the same visual column — so it silently does nothing until the virtual flow has laid out. Under the
     * headless platform that is not guaranteed to have happened by the time a test runs, which made
     * {@link #lineDownMovesEveryCaret} fail intermittently (#773): the carets stayed at their starting
     * offsets, indistinguishable from a broken implementation.
     *
     * <p>Height, not an exception, is the readiness test — a not-yet-laid-out area no-ops rather than
     * throwing, the same property documented for {@code scrollRestoredCaretIntoView}.
     */
    private boolean layOut(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            if (area.getScene() != null) {
                area.getScene().getRoot().applyCss();
                area.getScene().getRoot().layout();
            }
            area.requestLayout();
            area.layout();
            return area.getHeight() > 0;
        });
    }

    /** Sorted absolute offsets of every caret (primary + extras), read out of the fork. */
    private List<Integer> carets(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            Object controller = FxTestSupport.field(b, "multiCaret"); // MultiCaretController
            Object manager = FxTestSupport.call(controller, "getManager", new Class[] {});
            List<?> extras = FxTestSupport.field(manager, "extras");
            List<Integer> out = new ArrayList<>();
            out.add(area.getCaretPosition());
            for (Object cws : extras) {
                Object caret = FxTestSupport.field(cws, "caret"); // CaretNode
                out.add((Integer) FxTestSupport.call(caret, "getPosition", new Class[] {}));
            }
            Collections.sort(out);
            return out;
        });
    }

    @Test
    void charForwardMovesEveryCaret() throws Exception {
        EditorBuffer b = twoCaretBuffer();
        assertEquals(List.of(0, 5), carets(b), "carets start at both line beginnings");

        run("nav.charForward");
        assertEquals(List.of(1, 6), carets(b), "both carets step right one char");
        run("nav.charForward");
        assertEquals(List.of(2, 7), carets(b), "and again");

        closeActiveTab(b);
    }

    @Test
    void wordAndLineBoundaryMoveEveryCaret() throws Exception {
        EditorBuffer b = twoCaretBuffer();

        run("nav.wordForward");
        assertEquals(List.of(4, 9), carets(b), "both carets jump to the end of their word");

        run("nav.lineStart");
        assertEquals(List.of(0, 5), carets(b), "both carets return to line start");

        run("nav.lineEnd");
        assertEquals(List.of(4, 9), carets(b), "both carets go to line end");

        closeActiveTab(b);
    }

    @Test
    void lineDownMovesEveryCaret() throws Exception {
        EditorBuffer b = twoCaretBuffer(); // carets on lines 0 and 1

        // Unlike the other tests here, this one moves *vertically*, which needs real geometry. Assert the
        // area laid out rather than assume it: without this the failure looks like "the carets did not move",
        // which reads as a product bug rather than a test that ran too early (#773).
        org.junit.jupiter.api.Assumptions.assumeTrue(
                layOut(b), "the editor never laid out, so vertical movement cannot be exercised");

        run("nav.lineDown");
        assertEquals(List.of(5, 10), carets(b), "both carets move down a line, keeping column 0");

        closeActiveTab(b);
    }

    private void closeActiveTab(EditorBuffer b) throws Exception {
        FxTestSupport.runOnFx(() -> {
            b.collapseCarets();
            b.markClean();
            javafx.scene.control.TabPane tabPane = FxTestSupport.field(fx.controller, "tabPane");
            javafx.scene.control.Tab sel = tabPane.getSelectionModel().getSelectedItem();
            if (sel != null) {
                FxTestSupport.call(fx.controller, "closeTab", new Class[] {javafx.scene.control.Tab.class}, sel);
            }
        });
    }
}
