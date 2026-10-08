package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.collections.FXCollections;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.search.FileResult;
import com.editora.search.LineMatch;
import com.editora.search.SearchQuery;
import com.editora.search.SearchService;
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
 * The Find-in-Files panel driven as a user drives it: running a search and a Replace All from its fields,
 * walking the results (a selection previews, Enter and a double click activate), folding file groups, and
 * what the summary, the scope line and a result row show.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchPanelKeysFxTest {

    private static final Path A = Path.of("/proj/Alpha.java");
    private static final Path B = Path.of("/proj/Beta.java");

    /** What this test's panel asked of the window. A list per panel: a panel that left the scene commits
     *  its query field when it loses the focus, and that late search must not land in the next test's record. */
    private List<String> calls = new ArrayList<>();

    private Stage stage;
    private StackPane root;
    private SearchPanel panel;

    private static SearchPanel.Actions recording(List<String> calls) {
        return new SearchPanel.Actions() {
            @Override
            public void search(SearchQuery query, String includeGlobs, String excludeGlobs) {
                calls.add("search " + query + " in[" + includeGlobs + "] ex[" + excludeGlobs + "]");
            }

            @Override
            public void openMatch(Path file, int line, int col, boolean focusEditor) {
                calls.add((focusEditor ? "activate " : "preview ") + file.getFileName() + ":" + line + ":" + col);
            }

            @Override
            public void replaceAll(
                    SearchQuery query, String includeGlobs, String excludeGlobs, String replacement, List<Path> files) {
                calls.add("replace " + query.text() + " -> " + replacement + " in "
                        + files.stream().map(f -> f.getFileName().toString()).toList());
            }

            @Override
            public void recordSearch(String query) {
                calls.add("record " + query);
            }
        };
    }

    private static SearchService.Outcome outcome(boolean truncated) {
        return new SearchService.Outcome(
                List.of(
                        new FileResult(
                                A,
                                List.of(
                                        new LineMatch(3, 4, 3, "    foo();  "),
                                        new LineMatch(8, 0, 3, "x".repeat(250)))),
                        new FileResult(B, List.of(new LineMatch(1, 2, 3, "a foo")))),
                3,
                2,
                truncated);
    }

    @BeforeAll
    void boot() throws Exception {
        FxTestSupport.bootToolkit();
        FxTestSupport.runOnFx(() -> {
            root = new StackPane();
            stage = new Stage();
            stage.setScene(new Scene(root, 700, 500));
            stage.show();
        });
    }

    @AfterAll
    void close() throws Exception {
        FxTestSupport.runOnFx(stage::close);
    }

    @BeforeEach
    void create() throws Exception {
        FxTestSupport.runOnFx(() -> {
            calls = new ArrayList<>();
            panel = new SearchPanel(recording(calls));
            root.getChildren().setAll(panel);
            root.applyCss();
            root.layout();
        });
    }

    @SuppressWarnings("unchecked")
    private TreeView<Object> tree() {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
    }

    private ComboBox<String> query() {
        return FxTestSupport.field(panel, "queryCombo");
    }

    private Button button(String label) {
        return (Button) panel.lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && label.equals(b.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static void press(javafx.scene.Node target, KeyCode code, boolean ctrl) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, false, false));
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

    private TreeCell<Object> cell(int row) {
        TreeView<Object> tree = tree();
        TreeCell<Object> cell = tree.getCellFactory().call(tree);
        cell.updateTreeView(tree);
        cell.updateIndex(row);
        return cell;
    }

    @Test
    void searchingRecordsTheQueryAndPassesTheOptionsAndGlobs() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Button search = button(tr("search.run"));
            CheckBox caseBox = FxTestSupport.field(panel, "caseBox");
            CheckBox regexBox = FxTestSupport.field(panel, "regexBox");
            assertTrue(search.isDisabled(), "nothing to search for yet");
            assertTrue(caseBox.isDisabled());
            panel.refresh();
            panel.setQuery(null);
            panel.setQuery("");
            assertEquals(List.of(), calls, "an empty query runs nothing");

            query().getEditor().setText("foo");
            assertFalse(search.isDisabled());
            caseBox.setSelected(true);
            regexBox.setSelected(true);
            FxTestSupport.<TextField>field(panel, "includeField").setText("*.java");
            FxTestSupport.<TextField>field(panel, "excludeField").setText("build/");
            search.fire();
            assertEquals(
                    List.of(
                            "record foo",
                            "search " + new SearchQuery("foo", true, true, false) + " in[*.java] ex[build/]"),
                    calls);

            calls.clear();
            panel.setQuery("bar"); // from the editor's selection
            assertEquals("bar", query().getEditor().getText());
            assertEquals("record bar", calls.getFirst());
            panel.refresh();
            assertEquals(4, calls.size(), "refresh re-runs the current query");
        });
    }

    @Test
    void replaceAllActsOnTheShownFilesAndOnlyWhenThereIsAQueryAndResults() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Button replace = button(tr("search.replaceAll"));
            TextField replacement = FxTestSupport.field(panel, "replaceField");
            assertTrue(replace.isDisabled(), "no replacement typed");
            replacement.setText("bar");
            assertFalse(replace.isDisabled());
            replace.fire();
            assertEquals(List.of(), calls, "no query");

            query().getEditor().setText("foo");
            replace.fire();
            assertEquals(List.of(), calls, "no results to replace in");

            panel.setResults(outcome(false));
            replace.fire();
            assertEquals(List.of("replace foo -> bar in [Alpha.java, Beta.java]"), calls);
        });
    }

    @Test
    void aSelectionPreviewsEnterAndDoubleClickActivateAndHeadersFold() throws Exception {
        // Rows: 0 Alpha.java, 1 line 3, 2 line 8, 3 Beta.java, 4 line 1.
        FxTestSupport.runOnFx(() -> {
            TreeView<Object> tree = tree();
            press(tree, KeyCode.DOWN, false); // no results yet
            press(tree, KeyCode.ENTER, false);
            assertEquals(List.of(), calls);

            query().getEditor().setText("foo");
            panel.setResults(outcome(false));
            assertEquals(List.of(), calls, "showing results opens nothing by itself");

            press(tree, KeyCode.DOWN, false);
            assertEquals(List.of("preview Alpha.java:3:4"), calls, "moving onto a match previews it");
            press(tree, KeyCode.ENTER, false);
            assertEquals("activate Alpha.java:3:4", calls.getLast());
            press(tree, KeyCode.N, true);
            press(tree, KeyCode.M, true);
            assertEquals(List.of("preview Alpha.java:8:0", "activate Alpha.java:8:0"), calls.subList(2, 4));
            press(tree, KeyCode.P, true);
            press(tree, KeyCode.UP, false);
            assertEquals(0, tree.getSelectionModel().getSelectedIndex());
            press(tree, KeyCode.UP, false); // stops at the top
            assertEquals(0, tree.getSelectionModel().getSelectedIndex());

            int before = calls.size();
            press(tree, KeyCode.B, true);
            assertFalse(tree.getRoot().getChildren().getFirst().isExpanded(), "C-b folds the file group");
            press(tree, KeyCode.F, true);
            assertTrue(tree.getRoot().getChildren().getFirst().isExpanded(), "C-f unfolds it");
            press(tree, KeyCode.ENTER, false);
            assertFalse(tree.getRoot().getChildren().getFirst().isExpanded(), "Enter on a header toggles it");
            assertEquals(before, calls.size(), "a header opens nothing");

            tree.getSelectionModel().select(1); // Beta.java's header, now that Alpha is folded
            for (int i = 0; i < 6; i++) {
                press(tree, KeyCode.DOWN, false);
            }
            assertEquals(2, tree.getSelectionModel().getSelectedIndex(), "Down stops at the last row");
            press(tree, KeyCode.F, true); // a leaf has nothing to unfold
            press(tree, KeyCode.Q, true);
            press(tree, KeyCode.Q, false);

            calls.clear();
            Event.fireEvent(tree, click(MouseButton.PRIMARY, 1));
            Event.fireEvent(tree, click(MouseButton.SECONDARY, 2));
            assertEquals(List.of(), calls);
            Event.fireEvent(tree, click(MouseButton.PRIMARY, 2));
            assertEquals(List.of("activate Beta.java:1:2"), calls);
        });
    }

    @Test
    void keysTypedInAFieldAreNotResultNavigation() throws Exception {
        FxTestSupport.runOnFx(() -> {
            query().getEditor().setText("foo");
            panel.setResults(outcome(false));
            TextField replacement = FxTestSupport.field(panel, "replaceField");
            press(replacement, KeyCode.DOWN, false);
            press(replacement, KeyCode.ENTER, false);
            press(replacement, KeyCode.N, true);
            assertEquals(-1, tree().getSelectionModel().getSelectedIndex());
            assertEquals(List.of(), calls);
        });
    }

    @Test
    void rowsShowTheFileWithItsCountAndEachMatchWithItsLineCutWhenLong() throws Exception {
        FxTestSupport.runOnFx(() -> {
            query().getEditor().setText("foo");
            panel.setResults(outcome(false));
            TreeCell<Object> file = cell(0);
            assertEquals("Alpha.java  (2)", file.getText());
            assertTrue(file.getStyleClass().contains("search-file-row"));
            assertEquals("3:  foo();", cell(1).getText(), "the line is shown without its indentation");
            assertEquals("8:  " + "x".repeat(200) + "…", cell(2).getText());

            file.updateIndex(1);
            assertFalse(file.getStyleClass().contains("search-file-row"), "a reused cell drops the header style");
            file.updateIndex(-1);
            assertNull(file.getText());
        });
    }

    @Test
    void theSummaryScopeBadgeAndHistoryReflectWhatTheWindowTellsThePanel() throws Exception {
        Label summary = FxTestSupport.field(panel, "summary");
        Label scope = FxTestSupport.field(panel, "scopeLabel");
        Label badge = FxTestSupport.field(panel, "backendBadge");
        FxTestSupport.runOnFx(() -> {
            query().getEditor().setText("foo");
            panel.setResults(outcome(false));
            assertEquals(tr("search.summary", 3, 2), summary.getText());
            panel.setResults(outcome(true));
            assertEquals(tr("search.summaryTruncated", 3, 2), summary.getText());
            panel.setResults(new SearchService.Outcome(List.of(), 0, 0, false));
            assertEquals(tr("search.none"), summary.getText());

            panel.setResults(outcome(false));
            panel.showError("Bad regex: unclosed group");
            assertEquals("Bad regex: unclosed group", summary.getText());
            assertTrue(tree().getRoot().getChildren().isEmpty(), "an invalid query shows no stale results");

            panel.setResults(outcome(false));
            tree().getSelectionModel().select(1);
            calls.clear();
            query().getEditor().setText("");
            assertTrue(tree().getRoot().getChildren().isEmpty(), "clearing the query clears the results");
            assertEquals("", summary.getText());
            assertEquals(List.of(), calls, "and the selection going away previews nothing");

            panel.setScope("~/proj", "/home/me/proj");
            assertEquals("~/proj", scope.getText());
            assertEquals("/home/me/proj", scope.getTooltip().getText());
            panel.setScope(null, null);
            assertEquals("", scope.getText());
            assertNull(scope.getTooltip());

            panel.setBackendActive(true);
            assertTrue(badge.isVisible() && badge.isManaged());
            panel.setBackendActive(false);
            assertFalse(badge.isVisible() || badge.isManaged());

            panel.setHistory(FXCollections.observableArrayList("newest", "older"));
            assertEquals(List.of("newest", "older"), query().getItems());

            panel.focusResults();
            panel.focusFirstItem();
        });
    }
}
