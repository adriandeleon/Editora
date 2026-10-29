package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;

import com.editora.editor.EditorBuffer;
import com.editora.editor.fold.NarrowLines;
import com.editora.lsp.SymbolNode;
import com.editora.text.FoldRegions.Region;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Structure panel's narrow-to-fold-region toggle: a first click narrows the editor to the picked symbol's
 * block and turns fold mode on, under which a click on another symbol re-narrows to it; a second click widens and
 * ends the mode. The outline stays the whole file's while narrowed (document lines, rows outside the region
 * muted); a mere selection never narrows; the toggle follows the buffer whatever narrowed or widened it.
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

    private static ToggleButton toggle(StructurePanel p) {
        return FxTestSupport.field(p, "narrowFold");
    }

    private static boolean lit(StructurePanel p) throws Exception {
        return FxTestSupport.callOnFx(() -> toggle(p).isSelected());
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

    /** Activates the selected row (Enter, a double-click): in fold mode, narrows to it. */
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

    private static void click(StructurePanel p) throws Exception {
        FxTestSupport.runOnFx(() -> toggle(p).fire());
        settle();
    }

    @Test
    void theToggleStartsOffAndIsDisabledWithoutActions() throws Exception {
        StructurePanel bare = FxTestSupport.callOnFx(StructurePanel::new);
        assertTrue(FxTestSupport.callOnFx(() -> toggle(bare).isDisabled()), "nothing to narrow with");
        Rig r = rig();
        assertFalse(FxTestSupport.callOnFx(() -> toggle(r.panel()).isDisabled()));
        assertFalse(lit(r.panel()));
        assertFalse(r.panel().foldMode());
    }

    @Test
    void aClickNarrowsToThePickedRowsBlockAndTheOutlineStaysTheWholeFiles() throws Exception {
        Rig r = rig();
        pick(r.panel(), "bar");
        assertTrue(r.actions().log.isEmpty(), "with the mode off a picked symbol only scrolls");

        click(r.panel());
        assertEquals(List.of("fold 4 4-6"), r.actions().log, "the LSP row's range is its block");
        assertTrue(FxTestSupport.callOnFx(r.buffer()::isNarrowed));
        assertEquals(4, FxTestSupport.callOnFx(r.buffer()::narrowFirstLine));
        assertEquals(
                3,
                FxTestSupport.callOnFx(
                        () -> r.buffer().getArea().getParagraphs().size()));
        assertTrue(r.panel().foldMode());
        assertTrue(lit(r.panel()));

        // The whole file's outline, in document lines, with the picked row still selected.
        List<String> rows = FxTestSupport.callOnFx(() -> rows(r.panel()));
        assertEquals(List.of("A@0", "foo@1", "bar@4"), rows, "the outline is not the region's");
        assertEquals("bar", selectedLabel(r.panel()), "the highlight stays on the picked row");
        String row = FxTestSupport.callOnFx(
                () -> renderedText(tree(r.panel()), find(tree(r.panel()).getRoot(), "bar")));
        assertTrue(row.endsWith("line 5"), "a row shows the file's line while narrowed: " + row);
    }

    @Test
    void aSecondClickWidensAndEndsTheMode() throws Exception {
        Rig r = rig();
        pick(r.panel(), "foo");
        click(r.panel());
        click(r.panel());
        assertEquals(List.of("fold 1 1-3", "widen"), r.actions().log);
        assertFalse(FxTestSupport.callOnFx(r.buffer()::isNarrowed));
        assertFalse(r.panel().foldMode());
        assertFalse(lit(r.panel()));
        click(r.panel());
        assertEquals(List.of("fold 1 1-3", "widen", "fold 1 1-3"), r.actions().log, "a third click is on again");
        assertTrue(lit(r.panel()));
    }

    @Test
    void aSelectionNeverNarrowsButAnActivationDoes() throws Exception {
        Rig r = rig();
        pick(r.panel(), "bar");
        click(r.panel());
        assertEquals(List.of("fold 4 4-6"), r.actions().log);

        pick(r.panel(), "foo"); // the arrow keys, the filter, a right-click: a selection
        settle();
        assertEquals(List.of("fold 4 4-6"), r.actions().log, "selecting a row out of the region narrows nothing");
        assertEquals(4, FxTestSupport.callOnFx(r.buffer()::narrowFirstLine));

        activate(r.panel()); // Enter / a click on the row
        assertEquals(
                List.of("fold 4 4-6", "fold 1 1-3"), r.actions().log, "activating a row in the mode re-narrows to it");
        assertEquals(1, FxTestSupport.callOnFx(r.buffer()::narrowFirstLine));
        assertEquals(
                0, FxTestSupport.callOnFx(() -> r.buffer().getArea().getCurrentParagraph()), "the caret lands on it");
        assertTrue(r.panel().foldMode());
    }

    @Test
    void typingInTheFilterDoesNotNarrow() throws Exception {
        Rig r = rig();
        pick(r.panel(), "bar");
        click(r.panel());
        TextField filter = FxTestSupport.field(r.panel(), "filterField");
        for (String typed : List.of("f", "fo", "foo")) {
            FxTestSupport.runOnFx(() -> filter.setText(typed));
            settle();
        }
        assertEquals(List.of("fold 4 4-6"), r.actions().log, "three keystrokes, no narrowing");
        assertEquals(4, FxTestSupport.callOnFx(r.buffer()::narrowFirstLine));
    }

    @Test
    void theCaretLineIsTheTargetWhenNoRowWasPicked() throws Exception {
        Rig r = rig(); // the rebuild left row 0 (A) selected; the user picked nothing
        FxTestSupport.runOnFx(() -> r.buffer().getArea().moveTo(5, 2));
        click(r.panel());
        assertEquals(List.of("fold 5"), r.actions().log, "the caret's line, not the row a rebuild selected");
    }

    @Test
    void aFailedNarrowingLeavesTheToggleOff() throws Exception {
        Rig r = rig();
        r.actions().foldFails = true;
        pick(r.panel(), "bar");
        click(r.panel());
        assertEquals(List.of("fold 4 4-6"), r.actions().log);
        assertFalse(r.panel().foldMode());
        assertFalse(lit(r.panel()), "nothing was narrowed, so the toggle is not on");
    }

    @Test
    void aBufferNarrowedByACommandLeavesTheToggleUnlitAndWideningByAnyMeansResetsIt() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> r.buffer().narrowTo(10, 30)); // C-x n n, not the panel
        settle();
        assertFalse(lit(r.panel()), "narrowed, but not by the panel");
        assertFalse(r.panel().foldMode());
        assertTrue(
                FxTestSupport.callOnFx(() -> toggle(r.panel()).getPseudoClassStates().stream()
                        .anyMatch(c -> c.getPseudoClassName().equals("narrowed-elsewhere"))),
                "the toggle says the buffer is narrowed elsewhere");
        assertEquals(
                List.of("A@0", "foo@1", "bar@4"),
                FxTestSupport.callOnFx(() -> rows(r.panel())),
                "the outline stays the whole file's under a chord's narrowing too");

        pick(r.panel(), "bar");
        click(r.panel());
        assertTrue(r.panel().foldMode(), "the toggle takes over");
        assertEquals(4, FxTestSupport.callOnFx(r.buffer()::narrowFirstLine), "measured on the whole document");
        FxTestSupport.runOnFx(r.buffer()::widen); // the status bar pill, C-x n w, a whole-document write…
        settle();
        assertFalse(r.panel().foldMode(), "widening ends the mode");
        assertFalse(lit(r.panel()));
    }

    @Test
    void theModeBelongsToItsBufferAcrossTabSwitches() throws Exception {
        Rig r = rig();
        pick(r.panel(), "foo");
        click(r.panel());
        EditorBuffer other = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("plain\ntext\n");
            return b;
        });
        FxTestSupport.runOnFx(() -> r.panel().attach(other));
        assertFalse(r.panel().foldMode(), "the other buffer is whole");
        assertFalse(lit(r.panel()));
        FxTestSupport.runOnFx(() -> r.panel().attach(r.buffer()));
        settle();
        assertTrue(r.panel().foldMode(), "back on the narrowed buffer, its mode is back");
        assertTrue(lit(r.panel()));
        assertEquals(
                List.of("A@0", "foo@1", "bar@4"),
                FxTestSupport.callOnFx(() -> rows(r.panel())),
                "the whole file's outline, recomputed for the narrowed buffer");
    }

    @Test
    void simpleModeHidesTheToggleAndTheCommandActsLikeIt() throws Exception {
        Rig r = rig();
        ToggleButton toggle = toggle(r.panel());
        assertTrue(FxTestSupport.callOnFx(toggle::isVisible));
        FxTestSupport.runOnFx(() -> r.panel().setSimpleMode(() -> true));
        assertFalse(FxTestSupport.callOnFx(toggle::isVisible));
        assertFalse(FxTestSupport.callOnFx(toggle::isManaged));
        FxTestSupport.runOnFx(() -> r.panel().setSimpleMode(() -> false));

        pick(r.panel(), "bar");
        FxTestSupport.runOnFx(r.panel()::toggleFoldCommand);
        settle();
        assertEquals(List.of("fold 4 4-6"), r.actions().log);
        assertTrue(r.panel().foldMode());
        FxTestSupport.runOnFx(r.panel()::toggleFoldCommand);
        settle();
        assertFalse(r.panel().foldMode());
        assertFalse(FxTestSupport.callOnFx(r.buffer()::isNarrowed));
    }

    @Test
    void reNarrowingKeepsTheClickedRowSelected() throws Exception {
        Rig r = rig();
        pick(r.panel(), "bar");
        click(r.panel());
        int builds = FxTestSupport.<Integer>field(r.panel(), "treeBuilds");
        List<String> seen = new ArrayList<>();
        FxTestSupport.runOnFx(() -> tree(r.panel())
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((o, a, b) -> seen.add(b == null ? "none" : labelOf(b))));
        pick(r.panel(), "foo");
        activate(r.panel());
        assertEquals(1, FxTestSupport.callOnFx(r.buffer()::narrowFirstLine));
        assertEquals("foo", selectedLabel(r.panel()), "selections seen: " + seen);
        assertEquals(List.of("foo"), seen, "the selection never left the clicked row");
        assertEquals(builds, (int) FxTestSupport.<Integer>field(r.panel(), "treeBuilds"), "no rebuild");
    }
}
