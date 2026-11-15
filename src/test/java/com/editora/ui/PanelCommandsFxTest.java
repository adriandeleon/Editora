package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.http.HttpExchange;
import com.editora.http.HttpFixtures;
import com.editora.markdown.MarkdownLint;
import com.editora.text.LspDiagnostic;
import com.editora.text.LspDiagnostic.Severity;
import com.editora.todo.TodoComment;
import com.editora.todo.TodoMatch;
import com.editora.todo.TodoService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The panels' commands of {@link PanelCommands} on the panels themselves, found as the palette finds them (the
 * keyboard in the panel, or the panel showing): each does what its row or button does to the selected or focused
 * thing, and with nothing there, or a row its menu would not offer, the status line says so (KBD-01, MENU-05).
 */
@Tag("fx")
class PanelCommandsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static String notOffered(String id) {
        return tr("status.rows.notOffered", DebugPanelText.bareTitle(tr("command." + id)));
    }

    /** Runs {@code id} on what {@code shows} offers, with the keyboard nowhere in it; the status lines go to {@code status}. */
    private static boolean run(String id, Node shows, List<String> status) {
        return FocusCommands.run(id, status::add, List.of(), List.of(shows), rows -> status.add("pick " + rows.size()));
    }

    private static Stage stage(Parent root) {
        Stage stage = new Stage();
        stage.setScene(new Scene(root, 900, 650));
        stage.show();
        root.applyCss();
        root.layout();
        return stage;
    }

    @Test
    void theCsvGridsCommandsSortRevealAndTurnTheHeaderRow() throws Exception {
        List<String> jumps = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            CsvGridPanel panel = new CsvGridPanel((line, field) -> jumps.add(line + "," + field));
            Stage stage = stage(new StackPane(panel));
            try {
                List<List<String>> rows = new ArrayList<>();
                rows.add(new ArrayList<>(List.of("name", "qty")));
                rows.add(new ArrayList<>(List.of("pear", "10")));
                rows.add(new ArrayList<>(List.of("apple", "9")));
                panel.setData(rows);
                @SuppressWarnings("unchecked")
                TableView<Object> table = (TableView<Object>) FxTestSupport.<TableView<?>>field(panel, "table");
                table.getFocusModel().focus(1, table.getColumns().get(1)); // the second row, the name column
                List<String> status = new ArrayList<>();
                assertTrue(run("csv.sortAscending", panel, status));
                assertEquals(List.of("apple", "pear"), firstColumn(panel));
                table.getFocusModel().focus(0, table.getColumns().get(1));
                assertTrue(run("csv.sortDescending", panel, status));
                assertEquals(List.of("pear", "apple"), firstColumn(panel));
                assertTrue(run("csv.clearSort", panel, status));
                assertEquals(List.of("pear", "apple"), firstColumn(panel), "the file's order again");
                assertTrue(run("csv.revealFocusedCell", panel, status));
                assertEquals(1, jumps.size(), "the focused cell's place in the file");
                assertTrue(panel.isHeaderRow());
                assertTrue(run("csv.toggleHeaderRow", panel, status));
                assertFalse(panel.isHeaderRow(), "the toggle turned over, as a click on it does");
                assertTrue(status.isEmpty(), status.toString());
            } finally {
                stage.close();
            }
        });
    }

    private static List<String> firstColumn(CsvGridPanel panel) {
        return panel.shown().rows().stream().map(List::getFirst).toList();
    }

    @Test
    void theProblemsWindowsCommandsActOnTheSelectedDiagnostic() throws Exception {
        List<String> opened = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            ProblemsPanel panel = new ProblemsPanel(new ProblemsPanel.Actions() {
                @Override
                public void open(Path file, int line, int col) {}

                @Override
                public void openUrl(String url) {
                    opened.add(url);
                }
            });
            Stage stage = stage(new StackPane(panel));
            try {
                LspDiagnostic withCode = new LspDiagnostic(
                        1, 0, 1, 5, Severity.ERROR, "boom", " E42 ", "javac", "https://example.invalid/E42", false);
                panel.setProblems(Map.of(Path.of("/proj/Main.java"), List.of(withCode)));
                List<String> status = new ArrayList<>();
                assertFalse(run("problems.copySelectedCode", panel, status), "nothing selected: no menu");
                @SuppressWarnings("unchecked")
                TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
                tree.getSelectionModel().select(leaf(tree.getRoot()));
                assertTrue(run("problems.copySelectedCode", panel, status));
                assertEquals("E42", Clipboard.getSystemClipboard().getString());
                assertTrue(run("problems.openSelectedRuleDocs", panel, status));
                assertEquals(List.of("https://example.invalid/E42"), opened);
                assertEquals(List.of(notOffered("problems.copySelectedCode")), status);
            } finally {
                stage.close();
            }
        });
    }

    private static TreeItem<Object> leaf(TreeItem<Object> item) {
        TreeItem<Object> at = item;
        while (!at.getChildren().isEmpty()) {
            at.setExpanded(true);
            at = at.getChildren().getFirst();
        }
        return at;
    }

    @Test
    void theTodoMenusCommandsActOnTheSelectedMatchAndAPriorityIsPicked() throws Exception {
        List<String> done = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            TodoPanel panel = new TodoPanel(new TodoPanel.Actions() {
                @Override
                public void openMatch(Path file, int line, int col) {
                    done.add("open " + line);
                }

                @Override
                public void refresh() {}

                @Override
                public void setPriority(Path file, TodoMatch match, String priority) {
                    done.add("priority " + priority);
                }

                @Override
                public void setKeyword(Path file, TodoMatch match, String keyword) {
                    done.add("keyword " + keyword);
                }

                @Override
                public void editDescription(Path file, TodoMatch match) {
                    done.add("edit");
                }
            });
            Stage stage = stage(new StackPane(panel));
            try {
                String text = "// TODO tidy this";
                TodoMatch match = new TodoMatch(3, 7, 4, 3, text, "TODO", "#ffcc00", TodoComment.parse(text, 3, 7));
                panel.setResults(new TodoService.Outcome(
                        List.of(new TodoService.FileTodos(Path.of("/proj/A.java"), List.of(match))), 1, 1, false));
                List<String> status = new ArrayList<>();
                assertFalse(run("todo.markSelectedDone", panel, status), "nothing selected");
                @SuppressWarnings("unchecked")
                TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
                tree.getSelectionModel().select(leaf(tree.getRoot()));
                assertTrue(run("todo.markSelectedDone", panel, status));
                assertTrue(run("todo.editSelectedDescription", panel, status));
                assertTrue(run("todo.setSelectedPriority", panel, status));
                assertFalse(run("todo.reopenSelected", panel, status), "a TODO that is not done has no Reopen");
                assertEquals(List.of("keyword DONE", "edit"), done);
                assertEquals(
                        List.of(
                                notOffered("todo.markSelectedDone"),
                                "pick " + (TodoComment.PRIORITY_ORDER.size() + 1),
                                notOffered("todo.reopenSelected")),
                        status,
                        "the priorities and None are offered to choose from");
                assertTrue(ListRowCommands.actionAt(tree, "list.open").getAsBoolean(), "Enter's list.open");
                tree.getSelectionModel().clearSelection();
                assertFalse(ListRowCommands.actionAt(tree, "list.open").getAsBoolean(), "no row: nothing to open");
                tree.getSelectionModel().select(tree.getRoot().getChildren().getFirst()); // the file's header row
                assertFalse(run("todo.markSelectedDone", panel, status), "a header row has no match menu");
                assertFalse(ListRowCommands.actionAt(tree, "list.open").getAsBoolean());
                status.removeLast();
                String doneText = "// DONE tidy that";
                TodoMatch doneMatch =
                        new TodoMatch(3, 7, 9, 3, doneText, "DONE", "#00ff00", TodoComment.parse(doneText, 3, 7));
                panel.setResults(new TodoService.Outcome(
                        List.of(new TodoService.FileTodos(Path.of("/proj/B.java"), List.of(doneMatch))), 1, 1, false));
                tree.getSelectionModel().select(leaf(tree.getRoot()));
                assertTrue(run("todo.reopenSelected", panel, status), "a done item's Reopen keywords");
                assertTrue(done.contains("open 4"), done.toString());
                assertTrue(done.getLast().startsWith("keyword "), "reopened with the keyword chosen");
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void theMergeViewsAcceptCommandsActOnTheConflictTheKeyboardIsIn() throws Exception {
        var merge = com.editora.diff.ThreeWayMerge.merge("a\nold\nz\n", "a\nours\nz\n", "a\ntheirs\nz\n");
        FxTestSupport.runOnFx(() -> {
            MergeViewerPane pane = new MergeViewerPane("merge", merge.file(), "Monospaced", 13, "\n", true, s -> {});
            Stage stage = stage(new StackPane(pane.node()));
            try {
                List<String> status = new ArrayList<>();
                assertFalse(run("merge.acceptTheirs", pane.node(), status), "the keyboard is in no conflict");
                Button theirs = button((Parent) pane.node(), tr("merge.acceptTheirs"));
                theirs.requestFocus();
                assertTrue(FocusCommands.run(
                        "merge.acceptTheirs", status::add, List.of(stage), List.of(pane.node()), r -> {}));
                TextArea result = FxTestSupport.field(pane, "resultArea");
                assertEquals("a\ntheirs\nz\n", result.getText());
                Button base = button((Parent) pane.node(), tr("merge.acceptBase"));
                base.requestFocus();
                assertTrue(FocusCommands.run(
                        "merge.acceptBase", status::add, List.of(stage), List.of(pane.node()), r -> {}));
                assertEquals("a\nold\nz\n", result.getText(), "the common ancestor's lines");
                assertEquals(List.of(notOffered("merge.acceptTheirs")), status);
            } finally {
                stage.close();
            }
        });
    }

    private static Button button(Parent root, String text) {
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button b && text.equals(b.getText())) {
                return b;
            }
        }
        throw new AssertionError("no button " + text);
    }

    @Test
    void theFindBarsOptionsAreTheirCommandsWhereTheyShow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CheckBox matchCase = new CheckBox();
            CheckBox regex = new CheckBox();
            CheckBox word = new CheckBox();
            StackPane bar = new StackPane();
            PanelCommands.bindFindOptions(bar, matchCase, regex, word);
            List<String> status = new ArrayList<>();
            assertTrue(run("find.toggleCase", bar, status));
            assertTrue(run("find.toggleRegex", bar, status));
            assertTrue(run("find.toggleWholeWord", bar, status));
            assertTrue(matchCase.isSelected() && regex.isSelected() && word.isSelected());
            assertTrue(run("find.toggleCase", bar, status));
            assertFalse(matchCase.isSelected(), "a second time turns it off");
            bar.setVisible(false);
            assertFalse(run("find.toggleCase", bar, status), "a hidden bar offers nothing");
            assertEquals(List.of(notOffered("find.toggleCase")), status);
        });
    }

    @Test
    void theDebugWindowsRowsAreTheVariablesViewsCommandsWhereTheKeyboardIs() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MenuItem copy = FocusCommands.row(new MenuItem("Copy Value"), "debug.copySelectedValue");
            List<String> fired = new ArrayList<>();
            copy.setOnAction(e -> fired.add("copy"));
            javafx.scene.control.ListView<String> view = new javafx.scene.control.ListView<>();
            FocusCommands.menu(view, () -> List.of(copy));
            Stage stage = stage(new StackPane(view));
            try {
                view.requestFocus();
                stage.requestFocus();
                List<String> status = new ArrayList<>();
                boolean ran = FocusCommands.runWhereFocused("debug.copySelectedValue", status::add, List.of(stage));
                // A headless stage may not report focus; where it does, the row is the command's.
                assertEquals(stage.isFocused(), ran);
                assertEquals(stage.isFocused() ? List.of("copy") : List.of(), fired);
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void theDiffsOptionButtonsAndApplyArrowsAreCommands() throws Exception {
        var model = com.editora.diff.DiffEngine.compute(
                "a\nb\nc\n", "a\nB\nc\n", com.editora.diff.DiffEngine.DiffOptions.DEFAULT);
        List<String> applied = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            DiffViewerPane pane = new DiffViewerPane(
                    "diff",
                    "left",
                    "right",
                    "x.txt",
                    "x.txt",
                    "a\nb\nc\n",
                    "a\nB\nc\n",
                    model,
                    "Monospaced",
                    13,
                    true,
                    "x.txt");
            Stage stage = stage(new StackPane(pane.node()));
            try {
                List<String> status = new ArrayList<>();
                boolean wrap = FxTestSupport.<Boolean>field(pane, "wrapLines");
                assertTrue(run("diff.toggleWrapLines", pane.node(), status));
                assertEquals(!wrap, (boolean) FxTestSupport.<Boolean>field(pane, "wrapLines"));
                boolean context = FxTestSupport.<Boolean>field(pane, "collapseContext");
                assertTrue(run("diff.toggleCollapseContext", pane.node(), status));
                assertEquals(!context, (boolean) FxTestSupport.<Boolean>field(pane, "collapseContext"));
                Button whitespace = FxTestSupport.field(pane, "whitespaceButton");
                String before = whitespace.getText();
                assertTrue(run("diff.cycleWhitespace", pane.node(), status));
                assertFalse(before.equals(whitespace.getText()), "the next whitespace rule");
                assertTrue(run("diff.toggleWordHighlight", pane.node(), status));
                // Nothing editable: no change or line can be applied, and the result has no editor.
                assertFalse(run("diff.applyChange", pane.node(), status));
                assertFalse(run("diff.applyLine", pane.node(), status));
                assertFalse(run("diff.applyResult", pane.node(), status));
                pane.setEditable(
                        DiffViewerPane.EditableSide.LEFT,
                        (java.util.function.Consumer<String>) applied::add,
                        () -> {},
                        () -> {});
                assertTrue(run("diff.applyChange", pane.node(), status));
                assertEquals("a\nB\nc\n", applied.getLast(), "the change copied into the local file");
                assertFalse(run("diff.applyLine", pane.node(), status), "the caret is in neither side");
                org.fxmisc.richtext.CodeArea left = FxTestSupport.field(pane, "leftArea");
                left.requestFocus();
                left.moveTo(1, 0);
                boolean focused = left.isFocused(); // a headless stage need not take the keyboard
                assertEquals(
                        focused, FocusCommands.run("diff.applyLine", status::add, List.of(stage), List.of(), r -> {}));
                assertEquals(focused ? 2 : 1, applied.size(), "the caret's line copied into the local file");
                pane.setOnResultEdited(text -> {});
                javafx.scene.control.ToggleButton edit = FxTestSupport.field(pane, "editResultButton");
                edit.fire(); // the result editor opens
                boolean edited = FxTestSupport.<Boolean>field(pane, "resultEditing");
                if (edited) {
                    assertFalse(run("diff.applyChange", pane.node(), status), "not while the result is edited");
                    Button reset = FxTestSupport.field(pane, "resetResultButton");
                    Button apply = FxTestSupport.field(pane, "applyResultButton");
                    // Each runs as its button would: a disabled one (nothing to reset yet) is refused.
                    boolean resets = !reset.isDisabled();
                    boolean applies = !apply.isDisabled();
                    int said = status.size();
                    assertEquals(resets, run("diff.resetResult", pane.node(), status));
                    assertEquals(applies, run("diff.applyResult", pane.node(), status));
                    assertEquals((resets ? 0 : 1) + (applies ? 0 : 1), status.size() - said, "a refusal is said");
                    status.subList(said, status.size()).clear();
                }
                List<String> expected = new ArrayList<>(List.of(
                        notOffered("diff.applyChange"), notOffered("diff.applyLine"),
                        notOffered("diff.applyResult"), notOffered("diff.applyLine")));
                if (!focused) {
                    expected.add(notOffered("diff.applyLine"));
                }
                if (edited) {
                    expected.add(notOffered("diff.applyChange"));
                }
                assertEquals(expected, status);
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void theMarkdownLintRowsAreCommandsOnTheSelectedIssue() throws Exception {
        List<String> calls = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            MarkdownLintPanel panel = new MarkdownLintPanel(new MarkdownLintPanel.Actions() {
                @Override
                public void open(Path f, int line, int col) {
                    calls.add("open " + line);
                }

                @Override
                public void refresh() {}
            });
            Stage stage = stage(new StackPane(panel));
            try {
                MarkdownLint.Diagnostic d = new MarkdownLint.Diagnostic(3, 1, 4, "error", "MD001", "heading level");
                panel.setResults(Path.of("/proj/README.md"), List.of(d));
                javafx.scene.control.ListView<MarkdownLint.Diagnostic> list = FxTestSupport.field(panel, "list");
                list.getSelectionModel().clearSelection();
                List<String> status = new ArrayList<>();
                assertFalse(run("markdownLint.copySelected", panel, status), "no row selected: no menu");
                list.getSelectionModel().select(d);
                assertTrue(run("markdownLint.copySelected", panel, status));
                assertEquals(
                        MarkdownLintPanel.rowText(d),
                        Clipboard.getSystemClipboard().getString());
                assertFalse(run("markdownLint.fixSelected", panel, status), "no fixer given: the row is not offered");
                assertTrue(ListRowCommands.actionAt(list, ListRowCommands.OPEN).getAsBoolean());
                assertEquals(List.of("open 3"), calls);
                assertEquals(
                        List.of(notOffered("markdownLint.copySelected"), notOffered("markdownLint.fixSelected")),
                        status);
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void theDebugSessionStripIsPickedFromAndOneSessionOffersNone() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> shown = new ArrayList<>();
            DebugSessionsView view = new DebugSessionsView(new DebugSessionsView.Actions() {
                @Override
                public void show(DebugSession session) {
                    shown.add("show");
                }

                @Override
                public void close(DebugSession session) {}

                @Override
                public void stopAll() {}
            });
            List<String> status = new ArrayList<>();
            view.setTabs(List.of(new DebugSessionsView.Tab(null, "one", "running", true, true)));
            assertFalse(run("debug.switchSession", view, status), "one session: no strip, nothing to switch to");
            view.setTabs(List.of(
                    new DebugSessionsView.Tab(null, "one", "running", true, true),
                    new DebugSessionsView.Tab(null, "two", "paused", true, false)));
            assertTrue(run("debug.switchSession", view, status));
            assertEquals(List.of(notOffered("debug.switchSession"), "pick 2"), status);
        });
    }

    @Test
    void theSmallerPanelsTogglesAreTheirCommands() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> status = new ArrayList<>();
            FindReplaceBar bar = new FindReplaceBar(() -> null, status::add);
            bar.setVisible(true);
            CheckBox preserveOff = FxTestSupport.field(bar, "preserveCase");
            assertFalse(run("find.togglePreserveCase", bar, status), "no query: the option is off-limits");
            assertFalse(preserveOff.isSelected());
            status.clear();
            FxTestSupport.<javafx.scene.control.TextField>field(bar, "findField")
                    .setText("x");
            CheckBox preserve = FxTestSupport.field(bar, "preserveCase");
            assertTrue(run("find.togglePreserveCase", bar, status));
            assertTrue(run("find.toggleInSelection", bar, status));
            assertTrue(preserve.isSelected(), "Preserve Case turned on");

            @SuppressWarnings("unchecked")
            GitHubPanel github = new GitHubPanel((GitHubPanel.Actions) java.lang.reflect.Proxy.newProxyInstance(
                    GitHubPanel.Actions.class.getClassLoader(),
                    new Class<?>[] {GitHubPanel.Actions.class},
                    (proxy, method, args) -> null));
            javafx.scene.control.ToggleButton mine = FxTestSupport.field(github, "mineToggle");
            assertTrue(run("github.toggleMine", github, status));
            assertTrue(mine.isSelected());
            assertFalse(run("github.loadMore", github, status), "no more to load");

            HttpClientPanel http = new HttpClientPanel(null, e -> {}, e -> {}, "monospace", 12);
            javafx.scene.control.ToggleButton wrap = FxTestSupport.field(http, "wrapToggle");
            boolean wrapped = wrap.isSelected();
            assertTrue(run("http.toggleResponseWrap", http, status));
            assertEquals(!wrapped, wrap.isSelected());
            assertFalse(run("http.runShownAgain", http, status), "no past response shown");
            assertFalse(run("http.saveResponse", http, status), "nothing to save");
            MenuItem clear = FxTestSupport.field(http, "clearItem");
            boolean clears = !clear.isDisable();
            assertEquals(clears, run("http.clearHistory", http, status), "as its row would");
            if (!clears) {
                status.removeLast();
            }
            // With a response kept: its history can be shown and cleared.
            HttpExchange exchange = HttpFixtures.exchange(
                    "get",
                    "GET",
                    "http://x.test/get",
                    List.of(),
                    "",
                    HttpFixtures.text(200, List.of(), "{}", "application/json", 3, 2, null, List.of()));
            http.showEntries(
                    List.of(new HttpHistoryEntry(exchange, null, 0, System.currentTimeMillis(), false, false, false)));
            assertTrue(run("http.showHistory", http, status), "the past responses, in the drop-down itself");
            FxTestSupport.<javafx.scene.control.ComboBox<?>>field(http, "historyCombo")
                    .hide();
            assertFalse(clear.isDisable(), "a kept response can be cleared");
            assertTrue(run("http.clearHistory", http, status));

            HierarchyPanel hierarchy =
                    new HierarchyPanel((HierarchyPanel.Loader) java.lang.reflect.Proxy.newProxyInstance(
                            HierarchyPanel.Loader.class.getClassLoader(),
                            new Class<?>[] {HierarchyPanel.Loader.class},
                            (proxy, method, args) -> null));
            javafx.scene.control.ToggleButton primary = FxTestSupport.field(hierarchy, "primaryToggle");
            assertTrue(run("hierarchy.toggleDirection", hierarchy, status));
            assertFalse(primary.isSelected(), "the other direction");
            assertTrue(run("hierarchy.toggleDirection", hierarchy, status));
            assertTrue(primary.isSelected(), "and back");
            assertEquals(
                    List.of(
                            tr("find.noSelection"),
                            notOffered("github.loadMore"),
                            notOffered("http.runShownAgain"),
                            notOffered("http.saveResponse")),
                    status);
        });
    }

    @Test
    void aTerminalsRowsAreTaggedForTheWindowAndBuiltAsTheKeyboardWouldHaveThem() throws Exception {
        FxTestSupport.runOnFx(() -> {
            com.editora.terminal.TerminalView view = new com.editora.terminal.TerminalView(13);
            view.setRowTagger((item, row) -> com.editora.editor.MenuChords.tag(item, "terminal." + row));
            List<MenuItem> rows = view.commandMenu();
            MenuItem selectAll = FocusCommands.rowIn(rows, "terminal.selectAll", false) == null
                    ? null
                    : rows.stream()
                            .filter(r -> "terminal.selectAll".equals(FocusCommands.commandOf(r)))
                            .findFirst()
                            .orElse(null);
            assertTrue(selectAll != null, "Select All is the terminal.selectAll command's row");
            assertTrue(FocusCommands.rowIn(rows, "terminal.copy", false).action() == null, "nothing selected");
            assertTrue(
                    FocusCommands.rowIn(rows, "terminal.openLink", false).action() == null,
                    "no selection on a link: the link rows are hidden");
        });
    }

    @Test
    void theStructureWindowsRowsActOnTheSelectedSymbol() throws Exception {
        int[] marks = {-1, -1};
        FxTestSupport.runOnFx(() -> {
            StructurePanel panel = new StructurePanel();
            Stage stage = stage(new StackPane(panel));
            try {
                List<String> status = new ArrayList<>();
                assertFalse(run("structure.bookmarkSelected", panel, status), "no buffer, nothing selected");
                com.editora.editor.EditorBuffer buffer = new com.editora.editor.EditorBuffer();
                buffer.setPath(Path.of("Example.java"));
                buffer.setContent("class Example {\n  void target() {}\n}\n");
                panel.setMarkerActions(new StructurePanel.MarkerActions() {
                    @Override
                    public void addBookmark(com.editora.editor.EditorBuffer b, int line) {
                        marks[0] = line;
                    }

                    @Override
                    public void addPersonalNote(com.editora.editor.EditorBuffer b, int line) {
                        marks[1] = line;
                    }

                    @Override
                    public boolean personalNotesEnabled() {
                        return true;
                    }
                });
                panel.attach(buffer);
                panel.setLspSymbols(
                        buffer, List.of(new com.editora.lsp.SymbolNode("target", "()", "method", 1, 2, List.of())));
                @SuppressWarnings("unchecked")
                TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
                tree.getSelectionModel().clearSelection();
                assertFalse(run("structure.noteSelected", panel, status), "no symbol selected");
                tree.getSelectionModel().select(tree.getRoot().getChildren().getFirst());
                assertTrue(run("structure.bookmarkSelected", panel, status));
                assertTrue(run("structure.noteSelected", panel, status));
                assertEquals(1, marks[0], "the symbol's line, as its row");
                assertEquals(1, marks[1]);
                assertEquals(
                        List.of(notOffered("structure.bookmarkSelected"), notOffered("structure.noteSelected")),
                        status);
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void aValuePopupOffersTheRowsOfWhatItShows() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugValuePopup popup = new DebugValuePopup(new DebugValuePopup.Source() {
                @Override
                public void evaluate(
                        String expression, java.util.function.Consumer<com.editora.dap.DapModels.EvalResult> cb) {}

                @Override
                public void children(
                        int ref,
                        String filter,
                        int start,
                        int count,
                        java.util.function.Consumer<List<com.editora.dap.DapModels.VariableInfo>> cb) {}

                @Override
                public void addWatch(String expression) {}
            });
            StackPane root = FxTestSupport.field(popup, "root");
            // A plain value: the one line's rows, Copy Value and Pin to Line (no expression yet: Pin is off).
            assertTrue(FocusCommands.findBelow(root, "debug.copySelectedValue").action() != null);
            assertTrue(FocusCommands.findBelow(root, "debug.pinShownValue").action() == null);
            root.getChildren().clear(); // nothing shown: no rows
            assertTrue(FocusCommands.findBelow(root, "debug.pinShownValue") == null);
        });
    }

    @Test
    void theAgentsSendIsACommandWhileNoTurnRuns() throws Exception {
        FxTestSupport.runOnFx(() -> {
            AgentPanel agent = new AgentPanel(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, path -> {});
            List<String> status = new ArrayList<>();
            assertTrue(run("agent.send", agent, status), "Send, as its button does (an empty prompt sends nothing)");
            agent.setBusy(true);
            assertFalse(run("agent.send", agent, status), "while a turn runs the button is Stop: agent.stop's");
            assertEquals(List.of(notOffered("agent.send")), status);
        });
    }

    @Test
    void aPanelsViewDropDownIsOpenedByItsCommand() throws Exception {
        FxTestSupport.runOnFx(() -> {
            StructurePanel structure = new StructurePanel();
            Stage stage = stage(new StackPane(structure));
            try {
                List<String> status = new ArrayList<>();
                javafx.scene.control.ComboBox<?> sort = FxTestSupport.field(structure, "sortCombo");
                assertTrue(run("structure.sortBy", structure, status));
                assertTrue(sort.isShowing(), "the sort choices, in the drop-down itself");
                sort.hide();
                HttpClientPanel http = new HttpClientPanel(null, e -> {}, e -> {}, "monospace", 12);
                assertFalse(run("http.showHistory", http, status), "no past responses: nothing to show");
                assertEquals(List.of(notOffered("http.showHistory")), status);
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void anEditableCsvGridsColumnIsRenamedFromTheKeyboard() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CsvGridPanel panel = new CsvGridPanel((line, field) -> {});
            Stage stage = stage(new StackPane(panel));
            try {
                List<List<String>> rows = new ArrayList<>();
                rows.add(new ArrayList<>(List.of("name", "qty")));
                rows.add(new ArrayList<>(List.of("pear", "10")));
                panel.setData(rows);
                @SuppressWarnings("unchecked")
                TableView<Object> table = (TableView<Object>) FxTestSupport.<TableView<?>>field(panel, "table");
                table.getFocusModel().focus(0, table.getColumns().get(1));
                List<String> status = new ArrayList<>();
                assertFalse(run("csv.renameColumn", panel, status), "a read-only grid is not renamed");
                panel.setEditable(true, null);
                table.getFocusModel().focus(0, table.getColumns().get(1));
                table.getFocusModel().focus(0, table.getColumns().get(0)); // the "#" gutter has no header to rename
                assertFalse(run("csv.renameColumn", panel, status));
                status.removeLast(); // said, as the first refusal was
                table.getFocusModel().focus(0, table.getColumns().get(1));
                assertTrue(run("csv.renameColumn", panel, status));
                assertTrue(
                        table.getColumns().get(1).getGraphic() instanceof javafx.scene.control.TextField,
                        "the header became its rename field, as a double-click makes it");
                assertEquals(List.of(notOffered("csv.renameColumn")), status);
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void theTerminalsFindBarAnswersTheConsoleFindBarsCommands() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TerminalFindBar bar = new TerminalFindBar();
            List<String> closed = new ArrayList<>();
            bar.setOnClose(() -> closed.add("closed"));
            List<String> status = new ArrayList<>();
            assertFalse(run("find.toggleCase", bar, status), "a hidden bar offers nothing");
            bar.setVisible(true);
            CheckBox matchCase = FxTestSupport.field(bar, "matchCase");
            assertTrue(run("find.toggleCase", bar, status));
            assertTrue(matchCase.isSelected());
            assertTrue(run("edit.cancel", bar, status), "what closes the editor's find bar closes it");
            assertFalse(bar.isVisible());
            assertEquals(List.of(notOffered("find.toggleCase")), status);
        });
    }

    @Test
    void theDebugWindowsThreadListIsOpenedOnlyWithThreadsInIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = new DebugPanel((DebugPanel.Actions) java.lang.reflect.Proxy.newProxyInstance(
                    DebugPanel.Actions.class.getClassLoader(),
                    new Class<?>[] {DebugPanel.Actions.class},
                    (proxy, method, args) -> {
                        Class<?> type = method.getReturnType();
                        return type == boolean.class ? false : type == int.class ? 0 : null;
                    }));
            Stage stage = stage(new StackPane(panel));
            try {
                List<String> status = new ArrayList<>();
                assertFalse(run("debug.chooseThread", panel, status), "no session: no threads to choose from");
                javafx.scene.control.ComboBox<com.editora.dap.DapModels.ThreadInfo> threads =
                        FxTestSupport.field(panel, "threads");
                threads.getItems().add(new com.editora.dap.DapModels.ThreadInfo(1, "main"));
                assertTrue(run("debug.chooseThread", panel, status), "the session's threads, in the drop-down itself");
                threads.hide();
                assertEquals(List.of(notOffered("debug.chooseThread")), status);
            } finally {
                stage.close();
            }
        });
    }
}
