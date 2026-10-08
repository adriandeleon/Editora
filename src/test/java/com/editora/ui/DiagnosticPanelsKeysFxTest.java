package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.editor.LspDiagnostic;
import com.editora.editor.LspDiagnostic.Severity;
import com.editora.markdown.MarkdownLint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Problems panel, the Markdown Lint panel and the message-log popup driven as a user drives them: the
 * keys that walk and open their rows, the double click, what a row shows, and the popup's filter, copy and
 * clear.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiagnosticPanelsKeysFxTest {

    private Stage stage;
    private StackPane root;
    private OverlayHost overlay;

    @BeforeAll
    void boot() throws Exception {
        FxTestSupport.bootToolkit();
        FxTestSupport.runOnFx(() -> {
            root = new StackPane();
            stage = new Stage();
            stage.setScene(new Scene(root, 900, 600));
            stage.show();
            overlay = new OverlayHost();
            overlay.install(root);
        });
    }

    @AfterAll
    void close() throws Exception {
        FxTestSupport.runOnFx(stage::close);
    }

    private static KeyEvent key(KeyCode code, boolean ctrl, boolean alt, boolean shortcut) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl || shortcut, alt, false);
    }

    private static void press(javafx.scene.Node target, KeyCode code) {
        Event.fireEvent(target, key(code, false, false, false));
    }

    private static void ctrl(javafx.scene.Node target, KeyCode code) {
        Event.fireEvent(target, key(code, true, false, false));
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

    // --- Problems ---------------------------------------------------------------------------------------

    private static LspDiagnostic diag(int line, int col, Severity severity, String message) {
        return new LspDiagnostic(line, col, line, col + 3, severity, message, "E", "test");
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> tree(ProblemsPanel p) {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(p, "tree");
    }

    private static TreeCell<Object> cell(TreeView<Object> tree, int row) {
        TreeCell<Object> cell = tree.getCellFactory().call(tree);
        cell.updateTreeView(tree);
        cell.updateIndex(row);
        return cell;
    }

    @Test
    void theProblemsTreeIsWalkedOpenedAndFoldedFromTheKeyboard() throws Exception {
        List<String> opened = new ArrayList<>();
        List<Boolean> scopes = new ArrayList<>();
        Path java = Path.of("/proj/Main.java");
        Path ts = Path.of("/proj/app.ts");
        FxTestSupport.runOnFx(() -> {
            ProblemsPanel p = new ProblemsPanel(new ProblemsPanel.Actions() {
                @Override
                public void open(Path file, int line, int col) {
                    opened.add(file.getFileName() + ":" + line + ":" + col);
                }

                @Override
                public void setProjectWide(boolean projectWide) {
                    scopes.add(projectWide);
                }
            });
            root.getChildren().addFirst(p);
            TreeView<Object> tree = tree(p);

            press(tree, KeyCode.DOWN); // an empty tree: nothing to select
            press(tree, KeyCode.ENTER);
            assertEquals(-1, tree.getSelectionModel().getSelectedIndex());

            Map<Path, List<LspDiagnostic>> byFile = new LinkedHashMap<>();
            byFile.put(java, List.of(diag(7, 4, Severity.ERROR, "second"), diag(7, 1, Severity.WARNING, "first")));
            byFile.put(ts, List.of(diag(0, 0, Severity.INFO, "note")));
            p.setProblems(byFile);
            // Rows: 0 Java, 1 Main.java, 2 first, 3 second, 4 TypeScript, 5 app.ts, 6 note.
            assertEquals(7, tree.getExpandedItemCount());

            press(tree, KeyCode.DOWN);
            assertEquals(1, tree.getSelectionModel().getSelectedIndex(), "from no selection, Down lands on row 1");
            ctrl(tree, KeyCode.N);
            assertEquals(2, tree.getSelectionModel().getSelectedIndex());
            press(tree, KeyCode.ENTER);
            ctrl(tree, KeyCode.N);
            ctrl(tree, KeyCode.M);
            assertEquals(List.of("Main.java:7:1", "Main.java:7:4"), opened, "same line: ordered by column");

            ctrl(tree, KeyCode.P);
            press(tree, KeyCode.UP);
            press(tree, KeyCode.UP);
            press(tree, KeyCode.UP); // stops at the first row, does not wrap
            assertEquals(0, tree.getSelectionModel().getSelectedIndex());
            press(tree, KeyCode.ENTER);
            assertFalse(tree.getRoot().getChildren().getFirst().isExpanded(), "Enter on a header folds it");
            assertEquals(4, tree.getExpandedItemCount());
            for (int i = 0; i < 10; i++) {
                press(tree, KeyCode.DOWN);
            }
            assertEquals(3, tree.getSelectionModel().getSelectedIndex(), "Down stops at the last row");

            press(tree, KeyCode.A); // not a panel key
            ctrl(tree, KeyCode.Q);
            assertEquals(2, opened.size());

            Event.fireEvent(tree, click(MouseButton.PRIMARY, 1));
            Event.fireEvent(tree, click(MouseButton.SECONDARY, 2));
            assertEquals(2, opened.size());
            Event.fireEvent(tree, click(MouseButton.PRIMARY, 2));
            assertEquals("app.ts:0:0", opened.getLast(), "a primary double click opens the selected problem");

            ComboBox<String> scope = FxTestSupport.field(p, "scope");
            root.applyCss(); // gives the selector its skin, as the first frame does
            root.layout();
            scope.setValue(tr("problems.scope.project")); // what picking the second entry does
            assertEquals(List.of(true), scopes, "choosing a scope asks the window for it");
            p.setProjectWide(false);
            assertEquals(List.of(true), scopes, "the window setting the selector is not the user choosing");
            assertEquals(0, scope.getSelectionModel().getSelectedIndex());
            root.getChildren().remove(p);
        });
    }

    @Test
    void aProblemRowShowsSeverityLineAndAOneLineMessageAndHeadersCountTheirProblems() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ProblemsPanel p = new ProblemsPanel((file, line, col) -> {});
            root.getChildren().addFirst(p);
            Map<Path, List<LspDiagnostic>> byFile = new LinkedHashMap<>();
            byFile.put(
                    Path.of("/proj/view.tsx"),
                    List.of(
                            diag(0, 0, Severity.ERROR, " broken\nhere "),
                            diag(1, 0, Severity.WARNING, "w".repeat(250)),
                            diag(2, 0, Severity.HINT, "hint")));
            byFile.put(Path.of("/proj/a.jsx"), List.of(diag(0, 0, Severity.INFO, "i")));
            byFile.put(Path.of("/proj/b.js"), List.of(diag(0, 0, Severity.INFO, "i")));
            byFile.put(Path.of("/proj/c.ts"), List.of(diag(0, 0, Severity.INFO, "i")));
            byFile.put(Path.of("/proj/notes.unknownext"), List.of(diag(0, 0, Severity.INFO, "i")));
            p.setActiveFile(Path.of("/proj/view.tsx"));
            p.setProblems(byFile);
            TreeView<Object> tree = tree(p);

            List<String> languages = tree.getRoot().getChildren().stream()
                    .map(i -> cell(tree, tree.getRow(i)).getText())
                    .toList();
            assertEquals("TypeScript (TSX)  (3)", languages.getFirst(), "the active file's language leads");
            assertTrue(languages.contains("JavaScript (JSX)  (1)"), languages.toString());
            assertTrue(languages.contains("JavaScript  (1)"), languages.toString());
            assertTrue(languages.contains("TypeScript  (1)"), languages.toString());

            TreeCell<Object> lang = cell(tree, 0);
            assertTrue(lang.getStyleClass().contains("problem-lang-row"));
            TreeCell<Object> file = cell(tree, 1);
            assertEquals("view.tsx  (3)", file.getText());
            assertTrue(file.getStyleClass().contains("search-file-row"));

            TreeCell<Object> error = cell(tree, 2);
            assertEquals("✖  1:  broken here", error.getText());
            assertTrue(error.getStyleClass().contains("problem-error"));
            TreeCell<Object> warning = cell(tree, 3);
            assertEquals("⚠  2:  " + "w".repeat(200) + "…", warning.getText(), "a long message is cut");
            assertTrue(warning.getStyleClass().contains("problem-warning"));
            TreeCell<Object> hint = cell(tree, 4);
            assertEquals("ℹ  3:  hint", hint.getText());
            assertTrue(hint.getStyleClass().contains("problem-info"));

            hint.updateIndex(2); // a reused cell takes the new row's severity only
            assertTrue(hint.getStyleClass().contains("problem-error"));
            assertFalse(hint.getStyleClass().contains("problem-info"));
            hint.updateIndex(-1);
            assertNull(hint.getText());

            Label summary = FxTestSupport.field(p, "summary");
            assertEquals(tr("problems.summary", 7, 5), summary.getText());
            p.setProblems(null);
            assertEquals(tr("problems.none"), summary.getText());

            p.focusFirstItem();
            assertTrue(tree.getSelectionModel().isEmpty(), "nothing to select in an empty tree");
            p.setProblems(byFile);
            p.focusFirstItem();
            assertEquals(0, tree.getSelectionModel().getSelectedIndex());
            root.getChildren().remove(p);
        });
    }

    // --- Markdown lint ----------------------------------------------------------------------------------

    @Test
    void theLintListIsWalkedAndOpenedFromTheKeyboardAndShowsEachIssue() throws Exception {
        List<String> calls = new ArrayList<>();
        Path file = Path.of("/proj/README.md");
        FxTestSupport.runOnFx(() -> {
            MarkdownLintPanel p = new MarkdownLintPanel(new MarkdownLintPanel.Actions() {
                @Override
                public void open(Path f, int line, int col) {
                    calls.add("open " + f.getFileName() + ":" + line + ":" + col);
                }

                @Override
                public void refresh() {
                    calls.add("refresh");
                }
            });
            root.getChildren().addFirst(p);
            ListView<MarkdownLint.Diagnostic> list = FxTestSupport.field(p, "list");
            Label summary = FxTestSupport.field(p, "summary");

            p.setResults(file, null);
            assertEquals(tr("markdownLint.none"), summary.getText());
            press(list, KeyCode.DOWN);
            press(list, KeyCode.ENTER);
            p.focusFirstItem();
            assertEquals(List.of("refresh"), calls, "focusing the panel re-lints; an empty list opens nothing");
            assertTrue(list.getSelectionModel().isEmpty());

            p.setResults(
                    file,
                    List.of(
                            new MarkdownLint.Diagnostic(3, 1, 4, "error", "MD001", "heading level"),
                            new MarkdownLint.Diagnostic(9, 5, 1, "warning", "MD009", "trailing spaces"),
                            new MarkdownLint.Diagnostic(12, 2, 1, "warning", "MD012", "blank lines")));
            assertEquals(tr("markdownLint.summary", 3), summary.getText());

            calls.clear();
            press(list, KeyCode.DOWN);
            assertEquals(1, list.getSelectionModel().getSelectedIndex(), "from no selection, Down lands on row 1");
            press(list, KeyCode.ENTER);
            ctrl(list, KeyCode.N);
            ctrl(list, KeyCode.N); // already last
            ctrl(list, KeyCode.M);
            ctrl(list, KeyCode.P);
            press(list, KeyCode.UP);
            press(list, KeyCode.UP); // already first
            Event.fireEvent(list, click(MouseButton.PRIMARY, 1));
            Event.fireEvent(list, click(MouseButton.PRIMARY, 2));
            press(list, KeyCode.A);
            ctrl(list, KeyCode.Q);
            assertEquals(List.of("open README.md:9:5", "open README.md:12:2", "open README.md:3:1"), calls);

            ListCell<MarkdownLint.Diagnostic> cell = list.getCellFactory().call(list);
            cell.updateListView(list);
            cell.updateIndex(0);
            assertEquals(tr("markdownLint.row", 3, 1) + "  MD001  heading level", cell.getText());
            assertTrue(cell.getGraphic().getStyleClass().contains("lint-error"));
            cell.updateIndex(1);
            assertTrue(cell.getGraphic().getStyleClass().contains("lint-warning"));
            cell.updateIndex(-1);
            assertNull(cell.getText());
            assertNull(cell.getGraphic());

            p.focusFirstItem();
            assertEquals(0, list.getSelectionModel().getSelectedIndex());

            // With no file to open them in, the rows are inert.
            p.setResults(null, List.of(new MarkdownLint.Diagnostic(1, 1, 1, "error", "MD001", "x")));
            calls.clear();
            list.getSelectionModel().select(0);
            press(list, KeyCode.ENTER);
            assertEquals(List.of(), calls);

            ((Button) p.lookup(".button")).fire();
            assertEquals(List.of("refresh"), calls);
            root.getChildren().remove(p);
        });
    }

    // --- the message log --------------------------------------------------------------------------------

    @Test
    void theMessageLogFiltersCopiesAndClears() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MessageLogPopup popup = new MessageLogPopup();
            MessageLog log = new MessageLog();
            log.add("Saved notes.md", 1_000);
            log.add("Search failed: bad pattern", MessageLog.Severity.ERROR, 2_000);
            log.add("Disk is nearly full", MessageLog.Severity.WARN, 3_000);

            popup.show(stage, null, log);
            assertFalse(popup.isShown(), "without an overlay to show it in, nothing opens");

            popup.setOverlayHost(overlay);
            popup.toggle(stage, null, log);
            assertTrue(popup.isShown());
            ListView<MessageLog.Entry> list = FxTestSupport.field(popup, "list");
            javafx.scene.control.TextField filter = FxTestSupport.field(popup, "filter");
            javafx.scene.Node card = FxTestSupport.field(popup, "root");
            assertEquals(
                    List.of("Disk is nearly full", "Search failed: bad pattern", "Saved notes.md"),
                    list.getItems().stream().map(MessageLog.Entry::text).toList(),
                    "newest first");
            assertEquals(0, list.getSelectionModel().getSelectedIndex(), "the newest is selected");

            // Rows are styled by severity.
            ListCell<MessageLog.Entry> cell = list.getCellFactory().call(list);
            cell.updateListView(list);
            cell.updateIndex(0);
            Label message = (Label) cell.getGraphic().lookup(".message-log-text");
            assertTrue(message.getStyleClass().contains("message-log-warn"));
            cell.updateIndex(1);
            assertTrue(message.getStyleClass().contains("message-log-error"));
            assertFalse(message.getStyleClass().contains("message-log-warn"));
            cell.updateIndex(2);
            assertFalse(message.getStyleClass().contains("message-log-error"));
            cell.updateIndex(-1);
            assertNull(cell.getGraphic());

            // The platform copy shortcut copies the selection.
            Event.fireEvent(list, key(KeyCode.C, false, false, true));
            assertEquals("Disk is nearly full", Clipboard.getSystemClipboard().getString());

            filter.setText("  SEARCH ");
            assertEquals(
                    List.of("Search failed: bad pattern"),
                    list.getItems().stream().map(MessageLog.Entry::text).toList());

            // Copy with nothing selected copies every row shown.
            list.getSelectionModel().clearSelection();
            Button copy = (Button) card.lookupAll(".button").stream()
                    .filter(n -> n instanceof Button b && tr("messagelog.copy").equals(b.getText()))
                    .findFirst()
                    .orElseThrow();
            copy.fire();
            assertEquals(
                    "Search failed: bad pattern", Clipboard.getSystemClipboard().getString());

            filter.setText("nothing matches this");
            assertTrue(list.getItems().isEmpty());
            copy.fire();
            assertEquals(
                    "Search failed: bad pattern",
                    Clipboard.getSystemClipboard().getString(),
                    "nothing to copy: unchanged");

            filter.setText("");
            assertEquals(3, list.getItems().size());

            Button clear = (Button) card.lookupAll(".button").stream()
                    .filter(n -> n instanceof Button b && tr("messagelog.clear").equals(b.getText()))
                    .findFirst()
                    .orElseThrow();
            clear.fire();
            assertTrue(list.getItems().isEmpty());
            assertTrue(log.isEmpty(), "Clear empties the session's log, not only the view");

            Event.fireEvent(list, key(KeyCode.A, false, false, false)); // not a popup key: stays open
            assertTrue(popup.isShown());
            Event.fireEvent(list, key(KeyCode.G, false, true, false)); // M-g closes it
            assertFalse(popup.isShown());

            popup.toggle(stage, null, log);
            assertFalse(popup.isShown(), "a toggle right after it was closed is the same click: it stays closed");
        });
    }

    @Test
    void escapeAndTheKeyboardQuitChordCloseTheMessageLog() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MessageLogPopup popup = new MessageLogPopup();
            popup.setOverlayHost(overlay);
            MessageLog log = new MessageLog();
            ListView<MessageLog.Entry> list = FxTestSupport.field(popup, "list");

            popup.show(stage, null, log);
            assertTrue(popup.isShown());
            assertTrue(list.getSelectionModel().isEmpty(), "an empty log has nothing to select");
            Event.fireEvent(list, key(KeyCode.ESCAPE, false, false, false));
            assertFalse(popup.isShown());

            popup.show(stage, null, null); // a window with no log yet
            assertTrue(popup.isShown());
            assertTrue(list.getItems().isEmpty());
            Event.fireEvent(list, key(KeyCode.G, true, false, false));
            assertFalse(popup.isShown());

            popup.show(stage, null, log);
            popup.toggle(stage, null, log);
            assertFalse(popup.isShown(), "toggling an open popup closes it");
        });
    }
}
