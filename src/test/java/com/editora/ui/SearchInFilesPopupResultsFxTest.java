package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
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
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Find-in-Files popup shown in a real overlay: what it searches with (options, globs, the folder field),
 * what it refuses to search (a broken pattern, a folder that is not there), how results are listed, and
 * what opening a file header or a match asks of the window.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchInFilesPopupResultsFxTest {

    @TempDir
    Path dir;

    private final List<String> calls = new ArrayList<>();
    private SearchService.Outcome answer;
    private Path defaultRoot;
    private Stage stage;
    private OverlayHost overlay;
    private SearchInFilesPopup popup;

    private final SearchInFilesPopup.Ops ops = new SearchInFilesPopup.Ops() {
        @Override
        public Path defaultRoot() {
            return defaultRoot;
        }

        @Override
        public void search(
                SearchQuery query,
                Path root,
                String includeGlobs,
                String excludeGlobs,
                Consumer<SearchService.Outcome> onResult) {
            calls.add("search " + query + " root=" + root + " in[" + includeGlobs + "] ex[" + excludeGlobs + "]");
            onResult.accept(answer);
        }

        @Override
        public void openMatch(Path file, int line, int col) {
            calls.add("open " + file.getFileName() + ":" + line + ":" + col);
        }

        @Override
        public void recordSearch(String query) {
            calls.add("record " + query);
        }
    };

    @BeforeAll
    void boot() throws Exception {
        FxTestSupport.bootToolkit();
        FxTestSupport.runOnFx(() -> {
            StackPane root = new StackPane();
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

    @BeforeEach
    void create() throws Exception {
        calls.clear();
        defaultRoot = dir;
        answer = new SearchService.Outcome(
                List.of(
                        new FileResult(
                                dir.resolve("src").resolve("Alpha.java"),
                                List.of(new LineMatch(3, 5, 3, "    foo();"), new LineMatch(9, 1, 3, "foo"))),
                        new FileResult(
                                Path.of("/elsewhere/Beta.java").toAbsolutePath(),
                                List.of(new LineMatch(1, 1, 3, "foo")))),
                3,
                2,
                false);
        FxTestSupport.runOnFx(() -> {
            if (overlay.isShowing()) {
                overlay.hide();
            }
            popup = new SearchInFilesPopup(overlay, ops);
        });
    }

    private TextField field(String name) {
        return FxTestSupport.field(popup, name);
    }

    private Label status() {
        return FxTestSupport.field(popup, "status");
    }

    @SuppressWarnings("unchecked")
    private ListView<Object> list() {
        return (ListView<Object>) FxTestSupport.<ListView<?>>field(popup, "list");
    }

    private ListCell<Object> cell(int index) {
        ListCell<Object> cell = list().getCellFactory().call(list());
        cell.updateListView(list());
        cell.updateIndex(index);
        return cell;
    }

    private static List<String> labels(ListCell<Object> cell) {
        return cell.getGraphic().lookupAll(".label").stream()
                .map(n -> ((Label) n).getText())
                .toList();
    }

    private static void press(javafx.scene.Node target, KeyCode code) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    @Test
    void showingSeedsTheFolderAndTheSelectionAndSearchesAtOnce() throws Exception {
        FxTestSupport.runOnFx(() -> popup.show("foo"));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            assertTrue(popup.isShown());
            assertEquals(dir.toString(), field("rootField").getText());
            assertEquals("foo", field("query").getText());
            assertEquals(
                    List.of("search " + new SearchQuery("foo", false, false, false) + " root=" + dir + " in[] ex[]"),
                    calls);
            assertEquals(tr("search.summary", 3, 2), status().getText());
            assertEquals(5, list().getItems().size(), "two file headers and three matches");
            assertEquals(1, list().getSelectionModel().getSelectedIndex(), "the first match, not its file header");

            assertEquals(
                    List.of(Path.of("src", "Alpha.java").toString(), "(2)"), labels(cell(0)), "relative to the folder");
            assertEquals(List.of("3", "foo();"), labels(cell(1)));
            assertEquals(
                    Path.of("/elsewhere/Beta.java").toAbsolutePath().toString(),
                    labels(cell(3)).getFirst());
            ListCell<Object> reused = cell(1);
            reused.updateIndex(-1);
            assertNull(reused.getGraphic());

            overlay.hide();
            assertFalse(popup.isShown());
        });

        FxTestSupport.runOnFx(() -> {
            defaultRoot = null;
            popup.show(null); // reopened with nothing selected: the last query is kept
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            assertEquals("foo", field("query").getText());
            assertEquals("", field("rootField").getText(), "no project and no file: the open buffers only");
            assertTrue(calls.getLast().contains("root=null"), calls.toString());
        });
    }

    @Test
    void theOptionsAndGlobsAreSentWithTheQuery() throws Exception {
        FxTestSupport.runOnFx(() -> {
            popup.show("");
            field("query").setText("fo+");
            FxTestSupport.<CheckBox>field(popup, "caseSensitive").setSelected(true);
            FxTestSupport.<CheckBox>field(popup, "regex").setSelected(true);
            FxTestSupport.<CheckBox>field(popup, "wholeWord").setSelected(true);
            field("include").setText("*.java");
            field("exclude").setText("build/");
            calls.clear();
            FxTestSupport.invoke(popup, "runSearch");
            assertEquals(
                    List.of("search " + new SearchQuery("fo+", true, true, true) + " root=" + dir
                            + " in[*.java] ex[build/]"),
                    calls);
        });
    }

    @Test
    void aBlankQueryABrokenPatternOrAMissingFolderSearchNothing() throws Exception {
        FxTestSupport.runOnFx(() -> {
            popup.show("foo");
            FxTestSupport.invoke(popup, "runSearch");
            assertEquals(5, list().getItems().size());
            calls.clear();

            field("query").setText("  ");
            FxTestSupport.invoke(popup, "runSearch");
            assertTrue(list().getItems().isEmpty());
            assertEquals("", status().getText());

            field("query").setText("(");
            FxTestSupport.invoke(popup, "runSearch");
            assertEquals(tr("search.summary", 3, 2), status().getText(), "as plain text, '(' is searchable");
            FxTestSupport.<CheckBox>field(popup, "regex").setSelected(true);
            calls.clear();
            FxTestSupport.invoke(popup, "runSearch");
            assertTrue(list().getItems().isEmpty(), "a pattern that does not compile shows no stale results");
            assertFalse(status().getText().isBlank(), "and says what is wrong with it");
            assertEquals(List.of(), calls);

            FxTestSupport.<CheckBox>field(popup, "regex").setSelected(false);
            field("query").setText("foo");
            field("rootField").setText(dir.resolve("no-such-folder").toString());
            calls.clear(); // un-ticking the option searched again, in the folder that does exist
            FxTestSupport.invoke(popup, "runSearch");
            assertEquals(tr("search.rootNotFound"), status().getText());
            assertTrue(list().getItems().isEmpty());
            assertEquals(List.of(), calls);

            field("rootField").setText("\u0000bad");
            FxTestSupport.invoke(popup, "runSearch");
            assertEquals(tr("search.rootNotFound"), status().getText(), "a path that cannot be a path at all");

            field("rootField").setText("~");
            FxTestSupport.invoke(popup, "runSearch");
            assertEquals(
                    Path.of(System.getProperty("user.home"))
                            .toAbsolutePath()
                            .normalize()
                            .toString(),
                    calls.getLast().replaceAll(".*root=(.*) in\\[.*", "$1"),
                    "a tilde is the home folder");
        });
    }

    @Test
    void noMatchesAndATruncatedSearchAreSaidSo() throws Exception {
        FxTestSupport.runOnFx(() -> {
            popup.show("foo");
            answer = new SearchService.Outcome(List.of(), 0, 0, false);
            FxTestSupport.invoke(popup, "runSearch");
            assertEquals(tr("search.none"), status().getText());
            assertTrue(list().getItems().isEmpty());
            FxTestSupport.invoke(popup, "openSelected");
            assertFalse(calls.stream().anyMatch(c -> c.startsWith("open")), "nothing selected, nothing opened");
            assertTrue(popup.isShown(), "and the popup stays");

            answer = new SearchService.Outcome(
                    List.of(new FileResult(dir.resolve("A.java"), List.of(new LineMatch(1, 1, 3, "foo")))),
                    1000,
                    40,
                    true);
            FxTestSupport.invoke(popup, "runSearch");
            assertEquals(tr("search.summaryTruncated", 1000, 40), status().getText());
        });
    }

    @Test
    void enterOpensTheSelectedMatchRecordsTheQueryAndClosesAndArrowsWalkTheResults() throws Exception {
        FxTestSupport.runOnFx(() -> popup.show("foo"));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            calls.clear();
            TextField query = field("query");
            press(query, KeyCode.DOWN);
            assertEquals(2, list().getSelectionModel().getSelectedIndex());
            press(query, KeyCode.A); // an ordinary key is the field's
            assertEquals(2, list().getSelectionModel().getSelectedIndex());
            press(query, KeyCode.ENTER);
            assertEquals(List.of("record foo", "open Alpha.java:9:1"), calls);
            assertFalse(popup.isShown());
        });
    }

    @Test
    void aFileHeaderOpensTheFileAtItsStartAndAClickOpensTheClickedRow() throws Exception {
        FxTestSupport.runOnFx(() -> popup.show("foo"));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            calls.clear();
            list().getSelectionModel().select(0);
            FxTestSupport.invoke(popup, "openSelected");
            assertEquals(List.of("record foo", "open Alpha.java:1:0"), calls);
        });

        FxTestSupport.runOnFx(() -> popup.show(null));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            calls.clear();
            ListCell<Object> row = cell(4);
            Event.fireEvent(row, click(MouseButton.SECONDARY));
            assertEquals(List.of(), calls, "only the primary button opens");
            Event.fireEvent(row, click(MouseButton.PRIMARY));
            assertEquals(List.of("record foo", "open Beta.java:1:1"), calls);

            ListCell<Object> empty = cell(-1);
            Event.fireEvent(empty, click(MouseButton.PRIMARY));
            assertEquals(2, calls.size(), "a click below the last row opens nothing");

            popup.setBackendActive(true);
            Label badge = FxTestSupport.field(popup, "backendBadge");
            assertTrue(badge.isVisible() && badge.isManaged());
            popup.setBackendActive(false);
            assertFalse(badge.isVisible() || badge.isManaged());
        });
    }

    private static MouseEvent click(MouseButton button) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                0,
                0,
                0,
                0,
                button,
                1,
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
}
