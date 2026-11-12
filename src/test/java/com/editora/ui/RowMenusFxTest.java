package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.editor.MenuChords;
import com.editora.lsp.LspManager;
import com.editora.search.FileResult;
import com.editora.search.LineMatch;
import com.editora.search.SearchQuery;
import com.editora.search.SearchService;
import com.editora.ui.ReferencesPanel.Reference;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lists, trees and read-only logs that had no right-click menu have one, and every row of it is a command's:
 * titled as the command, showing its chord, doing what the command does to the selected row, and disabled
 * while no row is selected.
 */
@Tag("fx")
class RowMenusFxTest {

    private static final Path FILE = Path.of("/project/src/Alpha.java");

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void reset() {
        MenuChords.setChords(Map::of);
    }

    private static String title(String commandId) {
        return DebugPanelText.bareTitle(tr("command." + commandId));
    }

    private static MenuItem row(ContextMenu menu, String commandId) {
        return menu.getItems().stream()
                .filter(i -> title(commandId).equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + commandId + " in "
                        + menu.getItems().stream().map(MenuItem::getText).toList()));
    }

    private static @Nullable String chord(MenuItem item) {
        KeyCombination shown = item.getAccelerator();
        return shown == null ? null : shown.getDisplayText();
    }

    /** The control's menu, with its rows enabled or disabled as a showing would leave them. */
    private static ContextMenu menuOf(javafx.scene.control.Control control) {
        ContextMenu menu = control.getContextMenu();
        assertNotNull(menu, "the control has a right-click menu");
        menu.getOnShowing().handle(null);
        return menu;
    }

    private static String clipboard() {
        return Clipboard.getSystemClipboard().getString();
    }

    @Test
    void aReferenceRowOpensAndCopiesAndShowsItsCommandsChords() throws Exception {
        List<String> opened = new ArrayList<>();
        Map<String, String> chords = new HashMap<>();
        chords.put("references.openSelected", "C-c o");
        chords.put("edit.copy", "M-w");
        FxTestSupport.runOnFx(() -> {
            MenuChords.setChords(() -> chords);
            ReferencesPanel panel =
                    new ReferencesPanel((file, line, col) -> opened.add(file.getFileName() + ":" + line + ":" + col));
            @SuppressWarnings("unchecked")
            TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");

            tree.getSelectionModel().clearSelection();
            ContextMenu menu = menuOf(tree);
            assertTrue(row(menu, "references.openSelected").isDisable(), "nothing selected: nothing to open");
            assertFalse(panel.openSelected());
            assertFalse(panel.copySelected());

            panel.setReferences(List.of(new Reference(FILE, 4, 2, "  foo();  ")));
            TreeItem<Object> heading = tree.getRoot().getChildren().get(0);
            tree.getSelectionModel().select(heading.getChildren().get(0));
            menu = menuOf(tree);
            MenuItem open = row(menu, "references.openSelected");
            MenuItem copy = row(menu, "references.copySelected");
            assertFalse(open.isDisable());
            assertEquals("C-c o", chord(open), "the row shows its own command's chord");
            assertEquals("M-w", chord(copy), "Copy shows the keymap's Copy chord, which copies here");

            open.fire();
            assertEquals(List.of("Alpha.java:4:2"), opened);
            copy.fire();
            assertEquals("foo();", clipboard(), "the reference's line, trimmed");

            tree.getSelectionModel().select(heading);
            assertTrue(panel.copySelected());
            assertEquals(FILE.toString(), clipboard(), "a file heading copies its path");
        });
    }

    @Test
    void theKeymapsCopyChordCopiesTheSelectedRow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            KeymapManager before = TextInputKeymap.sharedKeymap();
            KeymapManager emacs = new KeymapManager();
            emacs.loadNamed("emacs");
            TextInputKeymap.setShared(emacs);
            try {
                ReferencesPanel panel = new ReferencesPanel((file, line, col) -> {});
                panel.setReferences(List.of(new Reference(FILE, 4, 2, "bar();")));
                @SuppressWarnings("unchecked")
                TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
                tree.getSelectionModel()
                        .select(tree.getRoot()
                                .getChildren()
                                .get(0)
                                .getChildren()
                                .get(0));
                // M-w is Copy in the Emacs keymap.
                tree.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.W, false, false, true, false));
                assertEquals("bar();", clipboard());
            } finally {
                if (before != null) {
                    TextInputKeymap.setShared(before);
                }
            }
        });
    }

    @Test
    void aHierarchyRowOpensItsDeclarationAndCopiesItsName() throws Exception {
        List<String> opened = new ArrayList<>();
        HierarchyPanel.Loader loader = new HierarchyPanel.Loader() {
            @Override
            public void children(
                    LspManager.HierarchyNode node, boolean primary, Consumer<List<LspManager.HierarchyNode>> cb) {
                cb.accept(List.of());
            }

            @Override
            public void open(Path file, int line, int col) {
                opened.add(file.getFileName() + ":" + line + ":" + col);
            }
        };
        FxTestSupport.runOnFx(() -> {
            HierarchyPanel panel = new HierarchyPanel(loader);
            @SuppressWarnings("unchecked")
            TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
            assertTrue(row(menuOf(tree), "hierarchy.openSelected").isDisable(), "an empty tree has no row");
            assertFalse(panel.openSelected());

            panel.setRoots(
                    HierarchyPanel.Mode.CALLS,
                    List.of(new LspManager.HierarchyNode("run()", "Alpha", "method", FILE, 7, 3, "raw")));
            ContextMenu menu = menuOf(tree); // setRoots selects the anchor
            assertFalse(row(menu, "hierarchy.openSelected").isDisable());
            row(menu, "hierarchy.openSelected").fire();
            assertEquals(List.of("Alpha.java:7:3"), opened);
            row(menu, "hierarchy.copySelected").fire();
            assertEquals("run()", clipboard());
        });
    }

    @Test
    void aFindInFilesRowOpensTheMatchAndCopiesItsLine() throws Exception {
        List<String> calls = new ArrayList<>();
        SearchPanel.Actions actions = new SearchPanel.Actions() {
            @Override
            public void search(SearchQuery query, String includeGlobs, String excludeGlobs) {}

            @Override
            public void openMatch(Path file, int line, int col, boolean focusEditor) {
                calls.add((focusEditor ? "open " : "preview ") + file.getFileName() + ":" + line + ":" + col);
            }

            @Override
            public void replaceAll(
                    SearchQuery query,
                    String includeGlobs,
                    String excludeGlobs,
                    String replacement,
                    List<Path> files) {}

            @Override
            public void recordSearch(String query) {}
        };
        FxTestSupport.runOnFx(() -> {
            SearchPanel panel = new SearchPanel(actions);
            @SuppressWarnings("unchecked")
            TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
            tree.getSelectionModel().clearSelection();
            assertFalse(panel.openSelectedMatch(), "no results: nothing to open");

            panel.setResults(new SearchService.Outcome(
                    List.of(new FileResult(FILE, List.of(new LineMatch(3, 4, 3, "    foo();  ")))), 1, 1, false));
            tree.getSelectionModel()
                    .select(tree.getRoot().getChildren().get(0).getChildren().get(0));
            calls.clear();
            ContextMenu menu = menuOf(tree);
            row(menu, "searchResults.openSelected").fire();
            assertEquals(List.of("open Alpha.java:3:4"), calls, "opens and moves the keyboard to the editor");
            row(menu, "searchResults.copySelected").fire();
            assertEquals("foo();", clipboard());
        });
    }

    @Test
    void theUndoHistoryMessageLogAndConsolesHaveTheirMenus() throws Exception {
        FxTestSupport.runOnFx(() -> {
            UndoHistoryPanel undo = new UndoHistoryPanel();
            ListView<?> states = FxTestSupport.field(undo, "list");
            assertTrue(row(menuOf(states), "undoHistory.restoreSelected").isDisable(), "no state to restore");
            assertFalse(undo.restoreSelected());

            MessageLogPopup log = new MessageLogPopup();
            ListView<?> messages = FxTestSupport.field(log, "list");
            assertTrue(row(menuOf(messages), "messageLog.copySelected").isDisable(), "no message selected");
            assertFalse(log.copySelection(), "a closed log copies nothing");

            ExternalToolPanel tools = new ExternalToolPanel();
            CodeArea console = FxTestSupport.field(tools, "output");
            assertNotNull(console.getOnContextMenuRequested(), "the External Tools console has Select All / Copy");
        });
    }

    @Test
    void theApplicationLogsMenuIsSelectAllCopyAndFindInPlaceOfTheStockOne() throws Exception {
        DebugLogWindow window = FxTestSupport.callOnFx(DebugLogWindow::new);
        FxTestSupport.runOnFx(() -> {
            window.show(null);
            Stage stage = FxTestSupport.field(window, "stage");
            try {
                TextArea area = FxTestSupport.field(window, "area");
                area.setText("one warn\ntwo warn\n");
                ContextMenu menu = menuOf(area);
                List<String> rows = menu.getItems().stream()
                        .filter(i -> !(i instanceof SeparatorMenuItem))
                        .map(MenuItem::getText)
                        .toList();
                assertEquals(List.of(tr("editmenu.selectAll"), tr("editmenu.copy"), tr("find.prompt")), rows);
                assertEquals("find.show", MenuChords.commandOf(menu.getItems().get(3)));

                area.selectRange(4, 8);
                menu.getItems().get(1).fire();
                assertEquals("warn", clipboard(), "Copy takes the selection");
                area.deselect();
                menu.getItems().get(1).fire();
                assertEquals("one warn\ntwo warn\n", clipboard(), "and the whole log when nothing is selected");

                ConsoleFindBar bar = (ConsoleFindBar) stage.getScene().getRoot().lookup(".console-find-bar");
                assertFalse(bar.isShowing());
                menu.getItems().get(3).fire();
                assertTrue(bar.isShowing(), "Find opens the window's find bar");
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void everyRowIsACommandThatSaysSoWhenNothingIsSelected(@TempDir Path dir) throws Exception {
        FxWindowFixture fx = FxWindowFixture.create(dir, c -> {});
        try {
            FxTestSupport.runOnFx(() -> {
                CommandRegistry registry = fx.controller.registry;
                for (String id : List.of(
                        "references.openSelected",
                        "references.copySelected",
                        "hierarchy.openSelected",
                        "hierarchy.copySelected",
                        "searchResults.openSelected",
                        "searchResults.copySelected",
                        "undoHistory.restoreSelected",
                        "messageLog.copySelected")) {
                    assertTrue(registry.get(id).isPresent(), id + " is registered");
                }
                assertTrue(registry.run("hierarchy.openSelected"));
                StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
                MessageLog log = FxTestSupport.field(bar, "messageLog");
                assertEquals(
                        tr("status.rows.selectFirst", tr("toolwindow.hierarchy")),
                        log.entries().get(0).text());
            });
        } finally {
            fx.close();
        }
    }
}
