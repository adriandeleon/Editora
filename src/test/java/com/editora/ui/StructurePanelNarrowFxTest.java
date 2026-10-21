package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;

import com.editora.editor.EditorBuffer;
import com.editora.editor.FoldRegions.Region;
import com.editora.editor.NarrowLines;
import com.editora.lsp.SymbolNode;
import org.jspecify.annotations.Nullable;
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
 * The Structure panel's narrowing segments (Widen | Defun | Fold): a segment narrows the editor to the picked
 * symbol and turns a mode on under which a click on another symbol re-narrows to it; the outline stays the whole
 * file's while narrowed (document lines, rows outside the region muted); a mere selection never narrows; Widen
 * ends the mode; the segments follow the buffer whatever narrowed or widened it.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructurePanelNarrowFxTest {

    private static final String DOC = "class A {\n  void foo() {\n    x();\n  }\n  void bar() {\n    y();\n  }\n}\n";

    /** Narrowing actions that really narrow the buffer (to the row's line and the next) and log what they did. */
    private static final class Actions implements StructurePanel.NarrowActions {
        final List<String> log = new ArrayList<>();
        boolean foldFails;

        @Override
        public boolean narrowToDefun(EditorBuffer buffer, int line) {
            log.add("defun " + line);
            return narrowLines(buffer, line, line + 1);
        }

        @Override
        public boolean narrowToFoldRegion(EditorBuffer buffer, int line, @Nullable Region block) {
            log.add("fold " + line + (block == null ? "" : " " + block.startLine() + "-" + block.endLine()));
            if (foldFails) {
                return false;
            }
            return block != null
                    ? narrowLines(buffer, block.startLine(), block.endLine())
                    : narrowLines(buffer, line, line + 1);
        }

        @Override
        public void widen(EditorBuffer buffer) {
            log.add("widen");
            buffer.widen();
        }

        /** Narrows to document lines {@code first..last} (inclusive), whatever the buffer holds now. */
        private static boolean narrowLines(EditorBuffer buffer, int first, int last) {
            String text = buffer.getContent();
            int start = NarrowLines.offsetOfLine(text, first);
            int lastStart = NarrowLines.offsetOfLine(text, last);
            int nl = text.indexOf('\n', lastStart);
            int end = nl < 0 ? text.length() : nl;
            int base = buffer.narrowStart();
            return buffer.narrowTo(start - base, end - base);
        }
    }

    private record Rig(StructurePanel panel, EditorBuffer buffer, Actions actions) {}

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A shown panel attached to a Java buffer with an LSP outline (A → foo, bar) and wired like MainController does. */
    private static Rig rig() throws Exception {
        return rig("java", DOC);
    }

    private static Rig rig(String language, String doc) throws Exception {
        Rig r = FxTestSupport.callOnFx(() -> {
            StructurePanel p = new StructurePanel();
            new Scene(new StackPane(p), 300, 400);
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride(language);
            b.setContent(doc);
            Actions actions = new Actions();
            p.setNarrowActions(actions);
            p.attach(b);
            b.setOnNarrowChanged(() -> p.narrowChanged(b));
            if ("java".equals(language)) {
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
            }
            return new Rig(p, b, actions);
        });
        settle();
        return r;
    }

    /** Lets the deferred work land: the fold scan, and the whole-file outline a narrowed buffer computes off-thread. */
    private static void settle() throws Exception {
        for (int i = 0; i < 4; i++) {
            Thread.sleep(120);
            FxTestSupport.drainFx();
        }
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

    private static @Nullable TreeItem<Object> find(TreeItem<Object> from, String name) {
        for (TreeItem<Object> item : from.getChildren()) {
            if (labelOf(item).startsWith(name)) {
                return item;
            }
            TreeItem<Object> deeper = find(item, name);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    private static String labelOf(TreeItem<Object> item) {
        return String.valueOf(FxTestSupport.call(item.getValue(), "label", new Class<?>[0]));
    }

    private static int lineOf(TreeItem<Object> item) {
        return (Integer) FxTestSupport.call(item.getValue(), "line", new Class<?>[0]);
    }

    /** Selects the row named {@code name}, as the arrow keys or a click would. */
    private static void pick(StructurePanel p, String name) throws Exception {
        FxTestSupport.runOnFx(() -> {
            TreeItem<Object> item = find(tree(p).getRoot(), name);
            if (item == null) {
                throw new AssertionError("no row " + name + " among " + rows(p));
            }
            tree(p).getSelectionModel().select(item);
        });
    }

    /** Activates the selected row (Enter, a double-click): in a mode, narrows to it. */
    private static void activate(StructurePanel p) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(p, "activateSelected", new Class<?>[0]));
        settle();
    }

    private static List<String> rows(StructurePanel p) {
        List<String> out = new ArrayList<>();
        collect(tree(p).getRoot(), out);
        return out;
    }

    private static void collect(TreeItem<Object> from, List<String> out) {
        for (TreeItem<Object> item : from.getChildren()) {
            out.add(labelOf(item) + "@" + lineOf(item));
            collect(item, out);
        }
    }

    private static String selectedLabel(StructurePanel p) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            TreeItem<Object> sel = tree(p).getSelectionModel().getSelectedItem();
            return sel == null ? "none" : labelOf(sel);
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

    private static void fire(StructurePanel p, String segment) throws Exception {
        FxTestSupport.runOnFx(() -> segment(p, segment).fire());
        settle();
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
    void defunSegmentNarrowsToThePickedRowAndTheOutlineStaysTheWholeFiles() throws Exception {
        Rig r = rig();
        pick(r.panel(), "bar");
        assertTrue(r.actions().log.isEmpty(), "with the mode off a picked symbol only scrolls");

        fire(r.panel(), "narrowDefun");
        assertEquals(List.of("defun 4"), r.actions().log, "the segment narrows to the picked row's document line");
        assertTrue(FxTestSupport.callOnFx(r.buffer()::isNarrowed));
        assertEquals(4, FxTestSupport.callOnFx(r.buffer()::narrowFirstLine));
        assertEquals(StructurePanel.NarrowMode.DEFUN, r.panel().narrowMode());
        assertSame(
                segment(r.panel(), "narrowDefun"),
                FxTestSupport.callOnFx(() -> group(r.panel()).getSelectedToggle()));

        // The whole file's outline, in document lines, with the picked row still selected.
        List<String> rows = FxTestSupport.callOnFx(() -> rows(r.panel()));
        assertEquals(List.of("A@0", "foo@1", "bar@4"), rows, "the outline is not the region's");
        assertEquals("bar", selectedLabel(r.panel()), "the highlight stays on the picked row");
        String row = FxTestSupport.callOnFx(
                () -> renderedText(tree(r.panel()), find(tree(r.panel()).getRoot(), "bar")));
        assertTrue(row.endsWith("line 5"), "a row shows the file's line while narrowed: " + row);
    }

    @Test
    void aSelectionNeverNarrowsButAnActivationDoes() throws Exception {
        Rig r = rig();
        pick(r.panel(), "bar");
        fire(r.panel(), "narrowDefun");
        assertEquals(List.of("defun 4"), r.actions().log);

        pick(r.panel(), "foo"); // the arrow keys, the filter, a right-click: a selection
        settle();
        assertEquals(List.of("defun 4"), r.actions().log, "selecting a row out of the region narrows nothing");
        assertEquals(4, FxTestSupport.callOnFx(r.buffer()::narrowFirstLine));

        activate(r.panel()); // Enter / a click on the row
        assertEquals(List.of("defun 4", "defun 1"), r.actions().log, "activating a row in the mode re-narrows to it");
        assertEquals(1, FxTestSupport.callOnFx(r.buffer()::narrowFirstLine));
        assertEquals(
                0, FxTestSupport.callOnFx(() -> r.buffer().getArea().getCurrentParagraph()), "the caret lands on it");
        assertEquals(StructurePanel.NarrowMode.DEFUN, r.panel().narrowMode());
    }

    @Test
    void typingInTheFilterDoesNotNarrow() throws Exception {
        Rig r = rig();
        pick(r.panel(), "bar");
        fire(r.panel(), "narrowDefun");
        TextField filter = FxTestSupport.field(r.panel(), "filterField");
        for (String typed : List.of("f", "fo", "foo")) {
            FxTestSupport.runOnFx(() -> filter.setText(typed));
            settle();
        }
        assertEquals(List.of("defun 4"), r.actions().log, "three keystrokes, no narrowing");
        assertEquals(4, FxTestSupport.callOnFx(r.buffer()::narrowFirstLine));
    }

    @Test
    void foldSegmentUsesTheFoldActionWithTheRowsOwnBlock() throws Exception {
        Rig r = rig();
        pick(r.panel(), "foo");
        fire(r.panel(), "narrowFold");
        assertEquals(List.of("fold 1 1-3"), r.actions().log, "the LSP row's range is its block");
        assertEquals(StructurePanel.NarrowMode.FOLD, r.panel().narrowMode());
        assertEquals(1, FxTestSupport.callOnFx(r.buffer()::narrowFirstLine));
        assertEquals(
                3,
                FxTestSupport.callOnFx(
                        () -> r.buffer().getArea().getParagraphs().size()));
        assertSame(
                segment(r.panel(), "narrowFold"),
                FxTestSupport.callOnFx(() -> group(r.panel()).getSelectedToggle()));
    }

    @Test
    void theCaretLineIsTheTargetWhenNoRowWasPicked() throws Exception {
        Rig r = rig(); // the rebuild left row 0 (A) selected; the user picked nothing
        FxTestSupport.runOnFx(() -> r.buffer().getArea().moveTo(5, 2));
        fire(r.panel(), "narrowDefun");
        assertEquals(List.of("defun 5"), r.actions().log, "the caret's line, not the row a rebuild selected");
    }

    @Test
    void reClickingTheLitSegmentNarrowsNothingAgain() throws Exception {
        Rig r = rig();
        pick(r.panel(), "bar");
        fire(r.panel(), "narrowDefun");
        fire(r.panel(), "narrowDefun");
        assertEquals(List.of("defun 4"), r.actions().log, "a re-click is not a second text swap");
        assertSame(
                segment(r.panel(), "narrowDefun"),
                FxTestSupport.callOnFx(() -> group(r.panel()).getSelectedToggle()),
                "the ToggleGroup's deselect was undone");
    }

    @Test
    void aFailedNarrowingKeepsTheModeTheBufferHad() throws Exception {
        Rig r = rig();
        pick(r.panel(), "bar");
        fire(r.panel(), "narrowDefun");
        r.actions().foldFails = true;
        fire(r.panel(), "narrowFold");
        assertEquals(
                StructurePanel.NarrowMode.DEFUN,
                r.panel().narrowMode(),
                "Defun stays: the buffer is still narrowed by it");
        assertSame(
                segment(r.panel(), "narrowDefun"),
                FxTestSupport.callOnFx(() -> group(r.panel()).getSelectedToggle()));
    }

    @Test
    void widenSegmentWidensAndEndsTheMode() throws Exception {
        Rig r = rig();
        pick(r.panel(), "foo");
        fire(r.panel(), "narrowDefun");
        fire(r.panel(), "narrowWiden");
        assertEquals(List.of("defun 1", "widen"), r.actions().log);
        assertFalse(FxTestSupport.callOnFx(r.buffer()::isNarrowed));
        assertEquals(StructurePanel.NarrowMode.OFF, r.panel().narrowMode());
        assertSame(
                segment(r.panel(), "narrowWiden"),
                FxTestSupport.callOnFx(() -> group(r.panel()).getSelectedToggle()));
        fire(r.panel(), "narrowWiden");
        assertEquals(
                List.of("defun 1", "widen", "widen"), r.actions().log, "Widen on a whole file is the action's no-op");
    }

    @Test
    void aBufferNarrowedByACommandLightsNoSegmentAndWideningByAnyMeansResetsThem() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> r.buffer().narrowTo(10, 30)); // C-x n n, not the panel
        settle();
        assertNull(
                FxTestSupport.callOnFx(() -> group(r.panel()).getSelectedToggle()),
                "narrowed, but by no mode of the panel");
        assertEquals(StructurePanel.NarrowMode.OFF, r.panel().narrowMode());
        HBox toggle = FxTestSupport.field(r.panel(), "narrowToggle");
        assertTrue(
                FxTestSupport.callOnFx(() -> toggle.getPseudoClassStates().stream()
                        .anyMatch(c -> c.getPseudoClassName().equals("narrowed-elsewhere"))),
                "the group says the buffer is narrowed elsewhere");
        assertEquals(
                List.of("A@0", "foo@1", "bar@4"),
                FxTestSupport.callOnFx(() -> rows(r.panel())),
                "the outline stays the whole file's under a chord's narrowing too");

        pick(r.panel(), "bar");
        fire(r.panel(), "narrowDefun");
        assertEquals(StructurePanel.NarrowMode.DEFUN, r.panel().narrowMode(), "a segment takes over");
        assertEquals(4, FxTestSupport.callOnFx(r.buffer()::narrowFirstLine), "measured on the whole document");
        FxTestSupport.runOnFx(r.buffer()::widen); // the status bar pill, C-x n w, a whole-document write…
        settle();
        assertEquals(StructurePanel.NarrowMode.OFF, r.panel().narrowMode(), "widening ends the mode");
        assertSame(
                segment(r.panel(), "narrowWiden"),
                FxTestSupport.callOnFx(() -> group(r.panel()).getSelectedToggle()));
    }

    @Test
    void theModeBelongsToItsBufferAcrossTabSwitches() throws Exception {
        Rig r = rig();
        pick(r.panel(), "foo");
        fire(r.panel(), "narrowFold");
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
        settle();
        assertEquals(
                StructurePanel.NarrowMode.FOLD,
                r.panel().narrowMode(),
                "back on the narrowed buffer, its mode is back");
        assertSame(
                segment(r.panel(), "narrowFold"),
                FxTestSupport.callOnFx(() -> group(r.panel()).getSelectedToggle()));
        assertEquals(
                List.of("A@0", "foo@1", "bar@4"),
                FxTestSupport.callOnFx(() -> rows(r.panel())),
                "the whole file's outline, recomputed for the narrowed buffer");
    }

    @Test
    void defunIsNotOfferedWhereThereAreNoDefinitions() throws Exception {
        Rig md = rig("markdown", "# Title\n\nText.\n\n## Section\n\nMore.\n");
        assertTrue(
                FxTestSupport.callOnFx(() -> segment(md.panel(), "narrowDefun").isDisabled()), "Markdown: no defun");
        assertFalse(
                FxTestSupport.callOnFx(() -> segment(md.panel(), "narrowFold").isDisabled()), "a section folds");
        Rig java = rig();
        assertFalse(FxTestSupport.callOnFx(
                () -> segment(java.panel(), "narrowDefun").isDisabled()));
    }

    @Test
    void simpleModeHidesTheSegmentsAndTheCommandsActLikeTheSegments() throws Exception {
        Rig r = rig();
        HBox toggle = FxTestSupport.field(r.panel(), "narrowToggle");
        assertTrue(FxTestSupport.callOnFx(toggle::isVisible));
        FxTestSupport.runOnFx(() -> r.panel().setSimpleMode(() -> true));
        assertFalse(FxTestSupport.callOnFx(toggle::isVisible));
        assertFalse(FxTestSupport.callOnFx(toggle::isManaged));
        FxTestSupport.runOnFx(() -> r.panel().setSimpleMode(() -> false));

        pick(r.panel(), "bar");
        FxTestSupport.runOnFx(r.panel()::narrowToFoldCommand);
        settle();
        assertEquals(List.of("fold 4 4-6"), r.actions().log);
        assertEquals(StructurePanel.NarrowMode.FOLD, r.panel().narrowMode());
        FxTestSupport.runOnFx(r.panel()::widenCommand);
        settle();
        assertEquals(StructurePanel.NarrowMode.OFF, r.panel().narrowMode());
        assertFalse(FxTestSupport.callOnFx(r.buffer()::isNarrowed));
    }
}
