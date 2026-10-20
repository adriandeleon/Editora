package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.SymbolNode;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Structure panel's narrowing segments (Widen | Defun | Fold): a segment narrows the editor to the selected
 * symbol and turns a mode on under which every picked symbol re-narrows; Widen ends it; the segments follow the
 * buffer whatever narrowed or widened it; and rows keep the file's line numbers while narrowed.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructurePanelNarrowFxTest {

    private static final String DOC = "class A {\n  void foo() {\n    x();\n  }\n  void bar() {\n    y();\n  }\n}\n";

    /** Narrowing actions that really narrow the buffer (to the symbol's line and the next) and log what they did. */
    private static final class Actions implements StructurePanel.NarrowActions {
        final List<String> log = new ArrayList<>();

        @Override
        public boolean narrowToDefun(EditorBuffer buffer, int line) {
            log.add("defun " + line);
            return narrowLines(buffer, line);
        }

        @Override
        public boolean narrowToFoldRegion(EditorBuffer buffer, int line) {
            log.add("fold " + line);
            return narrowLines(buffer, line);
        }

        @Override
        public void widen(EditorBuffer buffer) {
            log.add("widen");
            buffer.widen();
        }

        private static boolean narrowLines(EditorBuffer buffer, int line) {
            CodeArea area = buffer.getArea();
            int last = Math.min(line + 1, area.getParagraphs().size() - 1);
            return buffer.narrowTo(
                    area.getAbsolutePosition(line, 0), area.getAbsolutePosition(last, area.getParagraphLength(last)));
        }
    }

    private record Rig(StructurePanel panel, EditorBuffer buffer, Actions actions) {}

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A shown panel attached to a Java buffer with an LSP outline (A → foo, bar) and wired like MainController does. */
    private static Rig rig() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StructurePanel p = new StructurePanel();
            new Scene(new StackPane(p), 300, 400);
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent(DOC);
            Actions actions = new Actions();
            p.setNarrowActions(actions);
            p.attach(b);
            b.setOnNarrowChanged(() -> p.narrowChanged(b));
            p.setLspSymbols(
                    b,
                    List.of(new SymbolNode(
                            "A",
                            "",
                            "class",
                            0,
                            7,
                            List.of(
                                    new SymbolNode("foo", "()", "method", 1, 3, List.of()),
                                    new SymbolNode("bar", "()", "method", 4, 6, List.of())))));
            return new Rig(p, b, actions);
        });
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> tree(StructurePanel p) {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(p, "tree");
    }

    private static ToggleButton segment(StructurePanel p, String name) {
        return FxTestSupport.field(p, name);
    }

    private static ToggleGroup group(StructurePanel p) {
        return FxTestSupport.field(p, "narrowGroup");
    }

    /** Selects the row of the method named {@code name} (a child of the class row). */
    private static void pick(StructurePanel p, String name) throws Exception {
        FxTestSupport.runOnFx(() -> {
            TreeItem<Object> cls = tree(p).getRoot().getChildren().getFirst();
            for (TreeItem<Object> m : cls.getChildren()) {
                if (m.getValue().toString().contains(name)
                        || renderedText(tree(p), m).startsWith(name)) {
                    tree(p).getSelectionModel().select(m);
                    return;
                }
            }
            throw new AssertionError("no row " + name);
        });
    }

    private static String renderedText(TreeView<Object> tree, TreeItem<Object> item) {
        Object value = item.getValue();
        @SuppressWarnings("unchecked")
        TreeCell<Object> cell = (TreeCell<Object>) tree.getCellFactory().call(tree);
        FxTestSupport.call(cell, "updateItem", new Class<?>[] {value.getClass(), boolean.class}, value, false);
        HBox graphic = (HBox) cell.getGraphic();
        return graphic.getChildren().stream()
                .filter(Text.class::isInstance)
                .map(n -> ((Text) n).getText())
                .reduce("", String::concat);
    }

    @Test
    void theSegmentsStartOnWidenAndAreDisabledWithoutActions() throws Exception {
        StructurePanel bare = FxTestSupport.callOnFx(StructurePanel::new);
        assertTrue(FxTestSupport.callOnFx(() -> segment(bare, "narrowDefun").isDisabled()), "nothing to narrow with");
        Rig r = rig();
        assertFalse(
                FxTestSupport.callOnFx(() -> segment(r.panel(), "narrowDefun").isDisabled()));
        assertSame(
                segment(r.panel(), "narrowWiden"),
                FxTestSupport.callOnFx(() -> group(r.panel()).getSelectedToggle()));
        assertEquals(StructurePanel.NarrowMode.OFF, r.panel().narrowMode());
    }

    @Test
    void defunSegmentNarrowsToTheSelectedSymbolAndPickingAnotherReNarrows() throws Exception {
        Rig r = rig();
        pick(r.panel(), "bar");
        assertTrue(r.actions().log.isEmpty(), "with the mode off a picked symbol only scrolls");

        FxTestSupport.runOnFx(() -> segment(r.panel(), "narrowDefun").fire());
        assertEquals(List.of("defun 4"), r.actions().log, "the segment narrows to the selected row's line");
        assertTrue(FxTestSupport.callOnFx(r.buffer()::isNarrowed));
        assertEquals(StructurePanel.NarrowMode.DEFUN, r.panel().narrowMode());
        assertSame(
                segment(r.panel(), "narrowDefun"),
                FxTestSupport.callOnFx(() -> group(r.panel()).getSelectedToggle()));

        // The region's rows: the outline is region-relative, with bar at area line 0 — a fresh outline arrives.
        FxTestSupport.runOnFx(() ->
                r.panel().setLspSymbols(r.buffer(), List.of(new SymbolNode("bar", "()", "method", 0, 1, List.of()))));
        String row = FxTestSupport.callOnFx(() -> renderedText(
                tree(r.panel()), tree(r.panel()).getRoot().getChildren().getFirst()));
        assertTrue(row.endsWith("line 5"), "a row shows the file's line while narrowed: " + row);

        FxTestSupport.runOnFx(() -> {
            tree(r.panel()).getSelectionModel().clearSelection(); // the rebuild selected row 0 silently
            tree(r.panel())
                    .getSelectionModel()
                    .select(tree(r.panel()).getRoot().getChildren().getFirst());
        });
        assertEquals(List.of("defun 4", "defun 0"), r.actions().log, "picking a symbol in the mode re-narrows to it");
    }

    @Test
    void foldSegmentUsesTheFoldAction() throws Exception {
        Rig r = rig();
        pick(r.panel(), "foo");
        FxTestSupport.runOnFx(() -> segment(r.panel(), "narrowFold").fire());
        assertEquals(List.of("fold 1"), r.actions().log);
        assertEquals(StructurePanel.NarrowMode.FOLD, r.panel().narrowMode());
        assertSame(
                segment(r.panel(), "narrowFold"),
                FxTestSupport.callOnFx(() -> group(r.panel()).getSelectedToggle()));
    }

    @Test
    void widenSegmentWidensAndEndsTheMode() throws Exception {
        Rig r = rig();
        pick(r.panel(), "foo");
        FxTestSupport.runOnFx(() -> segment(r.panel(), "narrowDefun").fire());
        FxTestSupport.runOnFx(() -> segment(r.panel(), "narrowWiden").fire());
        assertEquals(List.of("defun 1", "widen"), r.actions().log);
        assertFalse(FxTestSupport.callOnFx(r.buffer()::isNarrowed));
        assertEquals(StructurePanel.NarrowMode.OFF, r.panel().narrowMode());
        assertSame(
                segment(r.panel(), "narrowWiden"),
                FxTestSupport.callOnFx(() -> group(r.panel()).getSelectedToggle()));
    }

    @Test
    void aBufferNarrowedByACommandLightsNoSegmentAndWideningByAnyMeansResetsThem() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> r.buffer().narrowTo(10, 30)); // C-x n n, not the panel
        assertNull(
                FxTestSupport.callOnFx(() -> group(r.panel()).getSelectedToggle()),
                "narrowed, but by no mode of the panel");
        assertEquals(StructurePanel.NarrowMode.OFF, r.panel().narrowMode());

        FxTestSupport.runOnFx(() -> segment(r.panel(), "narrowDefun").fire());
        assertEquals(StructurePanel.NarrowMode.DEFUN, r.panel().narrowMode(), "a segment takes over");
        FxTestSupport.runOnFx(r.buffer()::widen); // the status bar pill, C-x n w, a whole-document write…
        assertEquals(StructurePanel.NarrowMode.OFF, r.panel().narrowMode(), "widening ends the mode");
        assertSame(
                segment(r.panel(), "narrowWiden"),
                FxTestSupport.callOnFx(() -> group(r.panel()).getSelectedToggle()));
    }

    @Test
    void theModeBelongsToItsBufferAcrossTabSwitches() throws Exception {
        Rig r = rig();
        pick(r.panel(), "foo");
        FxTestSupport.runOnFx(() -> segment(r.panel(), "narrowFold").fire());
        EditorBuffer other = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("plain\ntext\n");
            return b;
        });
        FxTestSupport.runOnFx(() -> r.panel().attach(other));
        assertEquals(StructurePanel.NarrowMode.OFF, r.panel().narrowMode(), "the other buffer is whole");
        assertSame(
                segment(r.panel(), "narrowWiden"),
                FxTestSupport.callOnFx(() -> group(r.panel()).getSelectedToggle()));
        FxTestSupport.runOnFx(() -> r.panel().attach(r.buffer()));
        assertEquals(
                StructurePanel.NarrowMode.FOLD,
                r.panel().narrowMode(),
                "back on the narrowed buffer, its mode is back");
        assertSame(
                segment(r.panel(), "narrowFold"),
                FxTestSupport.callOnFx(() -> group(r.panel()).getSelectedToggle()));
    }
}
