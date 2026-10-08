package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.SymbolNode;
import com.editora.ui.StructurePanel.SortMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Structure tool window from the keyboard and the mouse — moving through the outline, expanding,
 * activating, leaving for the editor — and what its sort order, kind filter and rows show.
 */
@Tag("fx")
class StructurePanelKeysFxTest {

    private static final String SOURCE = "class Outer {\n" // 0
            + "  int alpha() { return 1; }\n" // 1
            + "  void beta(int x) {}\n" // 2
            + "  List<Map<String, int[]>> gamma() { return null; }\n" // 3
            + "}\n" // 4
            + "int zeta;\n" // 5
            + "fun apple(x: Int): String = \"a\"\n" // 6
            + "// Counts the cherries.\n" // 7
            + "fn cherry(a: i32) -> Result<i32, String> {\n" // 8
            + "}\n"; // 9

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static List<SymbolNode> symbols() {
        return List.of(
                new SymbolNode(
                        "Outer",
                        null,
                        "class",
                        0,
                        4,
                        List.of(
                                new SymbolNode("alpha", null, "method", 1, 1, List.of()),
                                new SymbolNode("beta(int)", null, "method", 2, 2, List.of()),
                                new SymbolNode("gamma", "(a) : List", "method", 3, 3, List.of()))),
                new SymbolNode("zeta", null, "field", 5, 5, List.of()),
                new SymbolNode("apple", "x: Int", "function", 6, 6, List.of()),
                new SymbolNode("cherry", null, "function", 8, 9, List.of()));
    }

    /** A Structure panel beside its editor in a shown stage, attached and holding the outline above. */
    private static final class Rig {
        final EditorBuffer buffer = new EditorBuffer();
        final StructurePanel panel = new StructurePanel();
        final TreeView<Object> tree = FxTestSupport.field(panel, "tree");
        final TextField filter = FxTestSupport.field(panel, "filterField");
        final Stage stage = new Stage();

        Rig() {
            buffer.setContent(SOURCE);
            stage.setScene(new Scene(new HBox(panel, buffer.node()), 900, 600));
            stage.show();
            panel.attach(buffer);
            panel.setLspSymbols(buffer, symbols());
        }

        KeyEvent press(KeyCode code, boolean control) {
            KeyEvent e = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false);
            FxTestSupport.invokeWith(panel, "onKey", KeyEvent.class, e);
            return e;
        }

        /** The labels of the rows on show, top to bottom. */
        List<String> rows() {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < tree.getExpandedItemCount(); i++) {
                out.add(label(tree.getTreeItem(i)));
            }
            return out;
        }

        String selected() {
            TreeItem<Object> item = tree.getSelectionModel().getSelectedItem();
            return item == null ? null : label(item);
        }

        TreeItem<Object> item(String label) {
            for (int i = 0; i < tree.getExpandedItemCount(); i++) {
                if (label.equals(label(tree.getTreeItem(i)))) {
                    return tree.getTreeItem(i);
                }
            }
            throw new AssertionError("no row " + label + " in " + rows());
        }

        void select(String label) {
            tree.getSelectionModel().select(item(label));
        }

        int caretLine() {
            return buffer.getArea().getCurrentParagraph();
        }

        Node focus() {
            return stage.getScene().getFocusOwner();
        }

        TreeCell<Object> cell(String label) {
            TreeCell<Object> cell = tree.getCellFactory().call(tree);
            cell.updateTreeView(tree);
            cell.updateIndex(tree.getRow(item(label)));
            return cell;
        }

        /** The text a row draws, each run between bars. */
        String rendered(String label) {
            StringBuilder sb = new StringBuilder();
            for (Node n : ((HBox) cell(label).getGraphic()).getChildren()) {
                if (n instanceof Text t) {
                    sb.append('|').append(t.getText());
                }
            }
            return sb.append('|').toString();
        }

        void dispose() {
            panel.attach(null);
            stage.close();
            buffer.dispose();
        }
    }

    private static String label(TreeItem<Object> item) {
        return (String) FxTestSupport.call(item.getValue(), "label", new Class<?>[0]);
    }

    @Test
    void arrowsAndTheEmacsChordsMoveThroughTheOutlineAndTheEditorFollows() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            assertEquals(List.of("Outer", "alpha", "beta(int)", "gamma", "zeta", "apple", "cherry"), r.rows());
            r.filter.requestFocus();

            assertTrue(r.press(KeyCode.DOWN, false).isConsumed());
            assertEquals("alpha", r.selected());
            assertEquals(1, r.caretLine(), "the editor shows the symbol under the highlight");
            assertSame(r.filter, r.focus(), "without taking the keyboard away from the panel");

            assertTrue(r.press(KeyCode.N, true).isConsumed());
            assertEquals("beta(int)", r.selected());
            assertTrue(r.press(KeyCode.P, true).isConsumed());
            assertTrue(r.press(KeyCode.UP, false).isConsumed());
            assertEquals("Outer", r.selected());
            r.press(KeyCode.UP, false);
            assertEquals("cherry", r.selected(), "Up from the first row wraps to the last");
            assertEquals(8, r.caretLine());
            r.press(KeyCode.DOWN, false);
            assertEquals("Outer", r.selected(), "and Down from the last wraps to the first");

            r.tree.getSelectionModel().clearSelection();
            r.press(KeyCode.UP, false);
            assertEquals("cherry", r.selected(), "Up with nothing selected starts at the bottom");
            r.tree.getSelectionModel().clearSelection();
            r.press(KeyCode.DOWN, false);
            assertEquals("Outer", r.selected());

            assertFalse(r.press(KeyCode.N, false).isConsumed(), "a bare letter is typed into the filter");
            assertFalse(r.press(KeyCode.X, true).isConsumed(), "an unbound chord is left alone");
            assertEquals("Outer", r.selected());
            r.dispose();
        });
    }

    @Test
    void controlFAndBExpandDescendCollapseAndAscend() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.select("alpha");
            assertTrue(r.press(KeyCode.B, true).isConsumed());
            assertEquals("Outer", r.selected(), "C-b on a member goes up to its class");
            r.press(KeyCode.B, true);
            assertEquals(List.of("Outer", "zeta", "apple", "cherry"), r.rows(), "C-b on an open class closes it");
            r.press(KeyCode.B, true);
            assertEquals("cherry", r.selected(), "C-b on a closed top-level row moves up a row");

            r.select("Outer");
            assertTrue(r.press(KeyCode.F, true).isConsumed());
            assertEquals(7, r.rows().size(), "C-f opens a closed class");
            r.press(KeyCode.F, true);
            assertEquals("alpha", r.selected(), "C-f on an open class steps into it");
            r.press(KeyCode.F, true);
            assertEquals("beta(int)", r.selected(), "C-f on a leaf moves down");

            r.tree.getSelectionModel().clearSelection();
            r.press(KeyCode.B, true);
            assertEquals("cherry", r.selected(), "C-b with nothing selected starts from the bottom");
            r.dispose();
        });
    }

    @Test
    void enterOpensAClosedRowFirstThenGoesToTheSymbolAndFocusesTheEditor() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.filter.requestFocus();
            r.select("Outer");
            r.item("Outer").setExpanded(false);

            assertTrue(r.press(KeyCode.ENTER, false).isConsumed());
            assertTrue(r.item("Outer").isExpanded(), "Enter on a closed row shows its members");
            assertSame(r.filter, r.focus(), "and stays in the panel");

            r.select("gamma");
            assertTrue(r.press(KeyCode.M, true).isConsumed());
            assertEquals(3, r.caretLine());
            assertSame(r.buffer.getArea(), r.focus(), "C-m (Enter) on a symbol moves into the editor");
            r.dispose();
        });
    }

    @Test
    void escapeGoesBackToTheEditorAndControlGClearsTheFilterFirst() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.panel.focusFirstItem();
            assertSame(r.filter, r.focus(), "the panel is entered at its filter field");
            r.filter.setText("zeta");
            assertEquals(List.of("zeta"), r.rows());

            assertTrue(r.press(KeyCode.G, true).isConsumed());
            assertEquals("", r.filter.getText(), "the first C-g drops the filter");
            assertSame(r.filter, r.focus());
            assertEquals(7, r.rows().size());

            r.press(KeyCode.G, true);
            assertSame(r.buffer.getArea(), r.focus(), "the second leaves for the editor");

            r.panel.focusContent();
            assertSame(r.filter, r.focus());
            assertTrue(r.press(KeyCode.ESCAPE, false).isConsumed());
            assertSame(r.buffer.getArea(), r.focus(), "Esc leaves at once");
            r.dispose();
        });
    }

    @Test
    void theKeysDoNothingHarmfulWithNoFileOpen() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.panel.attach(null);
            assertEquals(List.of(tr("structure.noFileOpen")), r.rows());
            r.filter.requestFocus();

            assertTrue(r.press(KeyCode.ESCAPE, false).isConsumed());
            assertSame(r.filter, r.focus(), "there is no editor to go to");
            assertTrue(r.press(KeyCode.ENTER, false).isConsumed());
            r.press(KeyCode.DOWN, false);
            assertEquals(tr("structure.noFileOpen"), r.selected(), "the placeholder can be highlighted, no more");
            assertTrue(r.panel.outline().isEmpty(), "and is not a symbol to jump to");
            r.stage.close();
            r.buffer.dispose();
        });
    }

    @Test
    void aDoubleClickGoesToTheSymbolAndFocusesTheEditor() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.filter.requestFocus();
            r.select("zeta");
            r.tree.getOnMouseClicked().handle(click(MouseButton.PRIMARY, 1));
            r.tree.getOnMouseClicked().handle(click(MouseButton.SECONDARY, 2));
            assertSame(r.filter, r.focus(), "a single click only moves the highlight");
            assertEquals(5, r.caretLine());

            r.tree.getOnMouseClicked().handle(click(MouseButton.PRIMARY, 2));
            assertSame(r.buffer.getArea(), r.focus());
            r.dispose();
        });
    }

    @Test
    void theOutlineCanBeSortedByNameOrKind() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            ComboBox<SortMode> sort = FxTestSupport.field(r.panel, "sortCombo");
            assertEquals(tr("structure.sort.position"), sort.getConverter().toString(SortMode.POSITION));
            assertEquals(tr("structure.sort.position"), sort.getConverter().toString(null));
            assertEquals(tr("structure.sort.name"), sort.getConverter().toString(SortMode.NAME));
            assertEquals(tr("structure.sort.kind"), sort.getConverter().toString(SortMode.KIND));
            assertEquals(SortMode.POSITION, sort.getConverter().fromString("anything typed"));

            sort.setValue(SortMode.NAME);
            assertEquals(
                    List.of("apple", "cherry", "Outer", "alpha", "beta(int)", "gamma", "zeta"),
                    r.rows(),
                    "by name, whatever the case, members within their class");

            sort.setValue(SortMode.KIND);
            assertEquals(List.of("Outer", "alpha", "beta(int)", "gamma", "zeta", "apple", "cherry"), r.rows());

            r.select("zeta");
            sort.setValue(null);
            assertEquals(List.of("Outer", "alpha", "beta(int)", "gamma", "zeta", "apple", "cherry"), r.rows());
            assertEquals("zeta", r.selected(), "re-sorting keeps the highlighted symbol");
            r.dispose();
        });
    }

    @Test
    void theKindFilterHidesAndRestoresWholeKinds() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            MenuButton kinds = FxTestSupport.field(r.panel, "kindFilter");
            assertEquals(
                    List.of("Class", "Field", "Function", "Method"),
                    kinds.getItems().stream().map(MenuItem::getText).toList());
            assertFalse(kinds.isDisable());
            CheckMenuItem methods = (CheckMenuItem) kinds.getItems().get(3);
            CheckMenuItem classes = (CheckMenuItem) kinds.getItems().get(0);

            methods.setSelected(false);
            assertEquals(List.of("Outer", "zeta", "apple", "cherry"), r.rows());
            classes.setSelected(false);
            assertEquals(List.of("zeta", "apple", "cherry"), r.rows(), "a class with nothing left to show goes too");
            methods.setSelected(true);
            assertEquals(
                    List.of("Outer", "alpha", "beta(int)", "gamma", "zeta", "apple", "cherry"),
                    r.rows(),
                    "a hidden class is kept as the parent of the members still shown");

            r.filter.setText("no such symbol");
            assertEquals(List.of(tr("structure.noMatches")), r.rows());
            Button clear = (Button) r.panel.lookup(".project-filter-clear");
            clear.fire();
            assertEquals("", r.filter.getText());
            assertSame(r.filter, r.focus(), "clearing the filter puts the caret back in it");

            // A new outline without classes forgets the toggle for them.
            r.panel.setLspSymbols(r.buffer, List.of(new SymbolNode("only", null, "variable", 5, 5, List.of())));
            assertEquals(
                    List.of("Variable"),
                    kinds.getItems().stream().map(MenuItem::getText).toList());
            r.panel.setLspSymbols(r.buffer, List.of());
            assertTrue(kinds.isDisable(), "nothing to filter in an empty outline");
            assertEquals(List.of(tr("structure.noStructure")), r.rows());
            r.dispose();
        });
    }

    @Test
    void aRowShowsItsSignatureItsReturnTypeAndItsLine() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            assertEquals("|Outer|  line 1|", r.rendered("Outer"), "a class has no signature");
            assertEquals("|alpha|()| : |int|  line 2|", r.rendered("alpha"), "a prefix return type");
            assertEquals(
                    "|beta|(int)| : |void|  line 3|",
                    r.rendered("beta(int)"),
                    "a label that carries its own parameter list gets no second one");
            assertEquals(
                    "|gamma|(a) : List| : |List<Map<String, int[]>>|  line 4|",
                    r.rendered("gamma"),
                    "the server's detail is shown as given; the generic return type is read whole");
            assertEquals("|zeta|  line 6|", r.rendered("zeta"));
            assertEquals("|apple|(x: Int)| : |String|  line 7|", r.rendered("apple"), "a detail gains its parentheses");
            assertEquals(
                    "|cherry|()| : |Result<i32, String>|  line 9|",
                    r.rendered("cherry"),
                    "a suffix return type after ->");

            HBox graphic = (HBox) r.cell("alpha").getGraphic();
            Text name = (Text) graphic.getChildren().get(1);
            assertTrue(name.getStyleClass().contains("function"), "an unstyled name falls back to its kind's colour");
            Text returnType = (Text) graphic.getChildren().get(4);
            assertTrue(returnType.getStyleClass().containsAll(List.of("structure-return-type", "type")));

            TreeCell<Object> documented = r.cell("cherry");
            assertEquals("Counts the cherries.", documented.getTooltip().getText());
            assertNull(r.cell("zeta").getTooltip(), "no comment above, no tooltip");

            documented.updateIndex(-1);
            assertNull(documented.getGraphic());
            assertNull(documented.getTooltip());
            assertNull(documented.getOnContextMenuRequested());

            r.filter.setText("no such symbol");
            TreeCell<Object> placeholder = r.cell(tr("structure.noMatches"));
            assertEquals(tr("structure.noMatches"), placeholder.getText());
            assertNull(placeholder.getGraphic());
            assertNull(placeholder.getContextMenu());
            r.dispose();
        });
    }

    @Test
    void aRightClickHighlightsItsRowAndOffersMarkersOnlyForASavedFile() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            List<String> calls = new ArrayList<>();
            boolean[] notesEnabled = {false};
            r.panel.setMarkerActions(new StructurePanel.MarkerActions() {
                @Override
                public void addBookmark(EditorBuffer buffer, int line) {
                    calls.add("bookmark " + line);
                }

                @Override
                public void addPersonalNote(EditorBuffer buffer, int line) {
                    calls.add("note " + line);
                }

                @Override
                public boolean personalNotesEnabled() {
                    return notesEnabled[0];
                }
            });
            assertNull(r.cell("zeta").getContextMenu(), "an unsaved buffer has no path to mark");

            r.buffer.setPath(Path.of("Outer.java").toAbsolutePath());
            TreeCell<Object> cell = r.cell("zeta");
            r.select("Outer");
            cell.getOnContextMenuRequested()
                    .handle(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, 0, 0, false, null));
            assertEquals("zeta", r.selected(), "the menu acts on the row under the pointer");

            ContextMenu menu = cell.getContextMenu();
            MenuItem note = menu.getItems().get(1);
            assertTrue(note.isDisable(), "Personal Notes are switched off");
            notesEnabled[0] = true;
            menu.getOnShowing().handle(new WindowEvent(menu, WindowEvent.WINDOW_SHOWING));
            assertFalse(note.isDisable(), "the menu re-reads the setting each time it opens");
            menu.getItems().get(0).fire();
            note.fire();
            assertEquals(List.of("bookmark 5", "note 5"), calls);

            r.panel.setMarkerActions(null);
            assertNull(r.cell("zeta").getContextMenu());
            r.dispose();
        });
    }

    @Test
    void aStaleServerOutlineIsAskedForAgainWhenTheOutlineIsNextRead() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            List<EditorBuffer> requested = new ArrayList<>();
            r.panel.setLspSymbolsRequester(requested::add);
            EditorBuffer other = new EditorBuffer();

            r.panel.lspSymbolsStale(other);
            r.panel.outline();
            assertTrue(requested.isEmpty(), "another buffer's outline going stale is not this panel's concern");

            r.panel.lspSymbolsStale(r.buffer);
            r.panel.outline();
            r.panel.outline();
            assertEquals(List.of(r.buffer), requested, "asked for once, not on every read");
            other.dispose();
            r.dispose();
        });
    }

    @ParameterizedTest
    @CsvSource({
        "class,type",
        "typeparameter,type",
        "constructor,function",
        "operator,function",
        "property,property",
        "variable,variable",
        "enummember,constant",
        "string,constant",
        "section,heading",
        "tag,tag",
    })
    void aKindWithoutAStyledDeclarationFallsBackToASyntaxClass(String kind, String style) {
        assertEquals(style, StructurePanel.syntaxStyleForKind(kind));
    }

    @Test
    void anUnknownOrMissingKindHasNoFallbackClass() {
        assertNull(StructurePanel.syntaxStyleForKind("file"));
        assertNull(StructurePanel.syntaxStyleForKind(null));
    }

    private static MouseEvent click(MouseButton button, int count) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                0,
                0,
                0,
                0,
                button,
                count,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                true,
                null);
    }
}
