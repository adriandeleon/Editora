package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;

import com.editora.todo.TodoComment;
import com.editora.todo.TodoGrouping;
import com.editora.todo.TodoMatch;
import com.editora.todo.TodoPatterns;
import com.editora.todo.TodoService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TODO panel driven as a user drives it: the keys that walk and open its rows (and that leave the
 * filter field alone while it is being typed in), the row menu's edits, grouping, the filter, and what a
 * structured and an unstructured match look like.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TodoPanelKeysFxTest {

    private static final Path A = Path.of("/proj/src/Alpha.java");
    private static final Path B = Path.of("/proj/src/Beta.py");

    private final List<String> calls = new ArrayList<>();
    private Stage stage;
    private StackPane root;
    private TodoPanel panel;

    private final TodoPanel.Actions actions = new TodoPanel.Actions() {
        @Override
        public void openMatch(Path file, int line, int col) {
            calls.add("open " + file.getFileName() + ":" + line + ":" + col);
        }

        @Override
        public void refresh() {
            calls.add("refresh");
        }

        @Override
        public void setPriority(Path file, TodoMatch match, String priority) {
            calls.add("priority " + file.getFileName() + ":" + match.line() + " " + priority);
        }

        @Override
        public void setKeyword(Path file, TodoMatch match, String keyword) {
            calls.add("keyword " + file.getFileName() + ":" + match.line() + " " + keyword);
        }

        @Override
        public void editDescription(Path file, TodoMatch match) {
            calls.add("edit " + file.getFileName() + ":" + match.line());
        }
    };

    /** A match on {@code line} whose text is {@code text}; its keyword is the first upper-case word. */
    private static TodoMatch structured(int line, String text) {
        int start = 0;
        while (!Character.isUpperCase(text.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < text.length() && Character.isUpperCase(text.charAt(end))) {
            end++;
        }
        String keyword = text.substring(start, end);
        return new TodoMatch(start, end, line, start, text, keyword, "#ffcc00", TodoComment.parse(text, start, end));
    }

    private static TodoService.Outcome outcome(boolean truncated) {
        return new TodoService.Outcome(
                List.of(
                        new TodoService.FileTodos(
                                A,
                                List.of(
                                        structured(3, "// TODO [ui] (high) fix the layout"),
                                        structured(9, "// DONE ship it"),
                                        new TodoMatch(0, 4, 12, 2, "  NOTE plain match  ", "NOTE", null))),
                        new TodoService.FileTodos(B, List.of(structured(1, "# FIXME (low) slow path")))),
                4,
                2,
                truncated);
    }

    @BeforeAll
    void boot() throws Exception {
        FxTestSupport.bootToolkit();
        FxTestSupport.runOnFx(() -> {
            root = new StackPane();
            stage = new Stage();
            stage.setScene(new Scene(root, 600, 500));
            stage.show();
        });
    }

    @AfterAll
    void close() throws Exception {
        FxTestSupport.runOnFx(stage::close);
    }

    @BeforeEach
    void create() throws Exception {
        calls.clear();
        FxTestSupport.runOnFx(() -> {
            panel = new TodoPanel(actions);
            root.getChildren().setAll(panel);
            root.applyCss();
            root.layout();
            panel.setResults(outcome(false));
        });
    }

    @SuppressWarnings("unchecked")
    private TreeView<Object> tree() {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
    }

    private static void press(javafx.scene.Node target, KeyCode code, boolean ctrl) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, false, false));
    }

    private TreeCell<Object> cell(int row) {
        TreeView<Object> tree = tree();
        TreeCell<Object> cell = tree.getCellFactory().call(tree);
        cell.updateTreeView(tree);
        cell.updateIndex(row);
        return cell;
    }

    private static List<String> runs(TreeCell<Object> cell) {
        return ((TextFlow) cell.getGraphic())
                .getChildren().stream().map(n -> ((Text) n).getText()).toList();
    }

    private List<String> headers() {
        return tree().getRoot().getChildren().stream()
                .map(i -> cell(tree().getRow(i)).getText())
                .toList();
    }

    private static MenuItem item(List<MenuItem> items, String text) {
        return items.stream().filter(i -> text.equals(i.getText())).findFirst().orElseThrow();
    }

    // Rows grouped by file: 0 Alpha.java, 1 TODO, 2 DONE, 3 NOTE, 4 Beta.py, 5 FIXME.

    @Test
    void theTreeIsWalkedAndOpenedFromTheKeyboard() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TreeView<Object> tree = tree();
            press(tree, KeyCode.ENTER, false);
            assertEquals(List.of(), calls, "nothing selected");

            press(tree, KeyCode.DOWN, false);
            assertEquals(1, tree.getSelectionModel().getSelectedIndex());
            press(tree, KeyCode.ENTER, false);
            press(tree, KeyCode.N, true);
            press(tree, KeyCode.M, true);
            press(tree, KeyCode.N, false); // plain n / p move too, while the filter is not being typed in
            press(tree, KeyCode.P, false);
            press(tree, KeyCode.P, true);
            press(tree, KeyCode.UP, false);
            assertEquals(0, tree.getSelectionModel().getSelectedIndex());
            press(tree, KeyCode.UP, false); // stops at the top
            assertEquals(0, tree.getSelectionModel().getSelectedIndex());
            assertEquals(List.of("open Alpha.java:3:3", "open Alpha.java:9:3"), calls);

            press(tree, KeyCode.ENTER, false);
            assertFalse(tree.getRoot().getChildren().getFirst().isExpanded(), "Enter folds a header");
            for (int i = 0; i < 9; i++) {
                press(tree, KeyCode.DOWN, false);
            }
            assertEquals(2, tree.getSelectionModel().getSelectedIndex(), "Down stops at the last row");
            press(tree, KeyCode.Q, true); // an unknown chord, and an unknown letter, are left alone
            press(tree, KeyCode.Q, false);
            assertEquals(2, tree.getSelectionModel().getSelectedIndex());

            Event.fireEvent(tree, click(MouseButton.PRIMARY, 1));
            Event.fireEvent(tree, click(MouseButton.SECONDARY, 2));
            assertEquals(2, calls.size());
            Event.fireEvent(tree, click(MouseButton.PRIMARY, 2));
            assertEquals("open Beta.py:1:2", calls.getLast());
        });
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
                false,
                false,
                false,
                null);
    }

    @Test
    void plainLettersTypeIntoTheFilterInsteadOfMovingTheSelection() throws Exception {
        TextField filter = FxTestSupport.field(panel, "filterField");
        FxTestSupport.runOnFx(() -> {
            filter.requestFocus();
            assertTrue(filter.isFocused(), "the filter has the focus");
            tree().getSelectionModel().select(1);
            press(filter, KeyCode.N, false);
            press(filter, KeyCode.P, false);
            assertEquals(1, tree().getSelectionModel().getSelectedIndex(), "n and p are letters here");
            press(filter, KeyCode.N, true);
            assertEquals(2, tree().getSelectionModel().getSelectedIndex(), "C-n still moves");
        });
    }

    @Test
    void theFilterMatchesKeywordTextAndPathAndAnEmptyTreeIsNotWalked() throws Exception {
        TextField filter = FxTestSupport.field(panel, "filterField");
        FxTestSupport.runOnFx(() -> {
            filter.setText("fixme");
            assertEquals(List.of("Beta.py  (1)"), headers(), "by keyword");
            filter.setText("LAYOUT");
            assertEquals(List.of("Alpha.java  (1)"), headers(), "by line text");
            filter.setText("beta");
            assertEquals(List.of("Beta.py  (1)"), headers(), "by path");
            filter.setText("no such thing");
            assertEquals(List.of(), headers());
            press(tree(), KeyCode.DOWN, false);
            assertEquals(-1, tree().getSelectionModel().getSelectedIndex());
            filter.setText("");
            assertEquals(2, headers().size());
        });
    }

    @Test
    void groupingByPriorityTagOrKeywordRelabelsTheHeadersAndIsReportedOnce() throws Exception {
        List<TodoGrouping.GroupBy> persisted = new ArrayList<>();
        ComboBox<TodoGrouping.GroupBy> groupBy = FxTestSupport.field(panel, "groupBy");
        FxTestSupport.runOnFx(() -> {
            panel.setGroupBy(TodoGrouping.GroupBy.TAG); // restoring a saved value: nobody to tell yet
            panel.setGroupByChangeHandler(persisted::add);
            assertTrue(headers().contains("ui  (1)"), headers().toString());
            assertTrue(headers().contains(tr("todo.noTag") + "  (3)"), headers().toString());

            panel.setGroupBy(TodoGrouping.GroupBy.TAG); // already that
            panel.setGroupBy(null);
            assertEquals(List.of(), persisted);

            groupBy.setValue(TodoGrouping.GroupBy.PRIORITY);
            assertTrue(
                    headers().contains(tr("todo.noPriority") + "  (2)"),
                    headers().toString());
            assertEquals(3, headers().size(), "high, low and none");
            groupBy.setValue(TodoGrouping.GroupBy.KEYWORD);
            assertEquals(4, headers().size(), "TODO, DONE, NOTE, FIXME");
            assertEquals(List.of(TodoGrouping.GroupBy.PRIORITY, TodoGrouping.GroupBy.KEYWORD), persisted);

            assertEquals(tr("todo.groupBy.priority"), groupBy.getConverter().toString(TodoGrouping.GroupBy.PRIORITY));
            assertEquals("", groupBy.getConverter().toString(null));
            assertNull(groupBy.getConverter().fromString("anything"), "the selector is not editable");
        });
    }

    @Test
    void aStructuredRowShowsItsPartsAndAPlainRowItsLine() throws Exception {
        FxTestSupport.runOnFx(() -> {
            assertEquals(
                    Arrays.asList("TODO", " [ui]", " (high)", " fix the layout", "  — Alpha.java:3"), runs(cell(1)));
            assertEquals(Arrays.asList("DONE", " ship it", "  — Alpha.java:9"), runs(cell(2)));
            assertEquals(Arrays.asList("NOTE plain match", "  — Alpha.java:12"), runs(cell(3)));
            assertNull(cell(3).getContextMenu(), "an unstructured match has nothing to edit");

            TreeCell<Object> header = cell(0);
            assertEquals("Alpha.java  (3)", header.getText());
            assertTrue(header.getStyleClass().contains("todo-file-row"));
            assertNull(header.getContextMenu());
            header.updateIndex(1);
            assertFalse(header.getStyleClass().contains("todo-file-row"), "a reused cell drops the header style");
            header.updateIndex(-1);
            assertNull(header.getGraphic());
            assertNull(header.getText());
        });
    }

    @Test
    void theRowMenuMarksDoneSetsThePriorityAndEditsAndADoneRowAsksWhichKeywordToReopenAs() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ContextMenu open = cell(1).getContextMenu();
            item(open.getItems(), tr("todo.menu.markDone")).fire();
            Menu priority = (Menu) item(open.getItems(), tr("todo.menu.priority"));
            item(priority.getItems(), tr("todo.priority.critical")).fire();
            item(priority.getItems(), tr("todo.priority.none")).fire();
            item(open.getItems(), tr("todo.menu.editDescription")).fire();
            assertEquals(
                    List.of(
                            "keyword Alpha.java:3 " + TodoPatterns.DONE_KEYWORD,
                            "priority Alpha.java:3 critical",
                            "priority Alpha.java:3 null",
                            "edit Alpha.java:3"),
                    calls);

            calls.clear();
            panel.setKeywords(Arrays.asList("FIXME", TodoPatterns.DONE_KEYWORD, " ", null, "HACK"));
            ContextMenu done = cell(2).getContextMenu();
            Menu reopen = (Menu) item(done.getItems(), tr("todo.menu.reopen"));
            assertEquals(
                    List.of("FIXME", "HACK"),
                    reopen.getItems().stream().map(MenuItem::getText).toList(),
                    "the configured keywords, without DONE itself");
            reopen.getItems().get(1).fire();
            assertEquals(List.of("keyword Alpha.java:9 HACK"), calls);

            panel.setKeywords(List.of(TodoPatterns.DONE_KEYWORD));
            Menu fallback = (Menu) item(cell(2).getContextMenu().getItems(), tr("todo.menu.reopen"));
            assertEquals(
                    List.of("TODO"),
                    fallback.getItems().stream().map(MenuItem::getText).toList());
        });
    }

    @Test
    void theSummaryScopeAndRefreshButtonReflectTheScan() throws Exception {
        Label summary = FxTestSupport.field(panel, "summary");
        Label scope = FxTestSupport.field(panel, "scopeLabel");
        FxTestSupport.runOnFx(() -> {
            assertEquals(tr("todo.summary", 4, 2), summary.getText());
            panel.setResults(outcome(true));
            assertEquals(tr("todo.summaryTruncated", 4, 2), summary.getText());
            panel.setResults(new TodoService.Outcome(List.of(), 0, 0, false));
            assertEquals(tr("todo.none"), summary.getText());

            panel.setScope("proj", "/home/me/proj");
            assertEquals("proj", scope.getText());
            assertEquals("/home/me/proj", scope.getTooltip().getText());
            panel.setScope(null, " ");
            assertEquals("", scope.getText());
            assertNull(scope.getTooltip());

            ((Button) panel.lookup(".todo-refresh")).fire();
            assertEquals(List.of("refresh"), calls);

            panel.focusFirstItem(); // an empty tree: refreshes, selects nothing
            assertTrue(tree().getSelectionModel().isEmpty());
            panel.setResults(outcome(false));
            panel.focusFirstItem();
            assertEquals(0, tree().getSelectionModel().getSelectedIndex());
            assertEquals(3, calls.size());
        });
    }

    @Test
    void theActiveFilesGroupLeadsWithoutARescan() throws Exception {
        FxTestSupport.runOnFx(() -> {
            assertEquals("Alpha.java  (3)", headers().getFirst());
            panel.setActiveFile(B);
            assertEquals("Beta.py  (1)", headers().getFirst());
            TreeItem<Object> before = tree().getRoot();
            panel.setActiveFile(B); // the same file: the tree is left as it is
            assertTrue(before == tree().getRoot());
        });
    }
}
