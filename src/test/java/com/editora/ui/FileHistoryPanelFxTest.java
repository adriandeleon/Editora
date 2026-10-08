package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.config.HistoryRevision;
import com.editora.config.Settings;
import com.editora.diff.DiffEngine;
import com.editora.diff.DiffModels.DiffModel;
import com.editora.history.HistoryBlobStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Local History panel as a view: what it shows, keeps and asks, against a scripted controller. */
@Tag("fx")
class FileHistoryPanelFxTest {

    private static final Path ORDERS = Path.of("/p/src/Orders.java");
    private static final Path NOTES = Path.of("/p/docs/notes.md");
    private static final long DAY = 86_400_000L;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private Stage stage;
    private double sceneWidth = 900;

    @AfterEach
    void close() throws Exception {
        FxTestSupport.runOnFx(() -> {
            for (Window w : List.copyOf(Window.getWindows())) {
                if (w instanceof ContextMenu menu) {
                    menu.hide();
                }
            }
            if (stage != null) {
                stage.hide();
            }
        });
    }

    /** A controller that answers at once and records what it was asked. */
    private static final class Host implements FileHistoryPanel.Actions, FileHistoryPanel.DiffSupport {
        final Map<String, String> contentBySha = new HashMap<>();
        final Map<Path, String> current = new HashMap<>();
        final List<String> log = new ArrayList<>();
        int diffs;
        boolean tooLarge;
        boolean unsaved;
        boolean agree = true;
        Runnable onRefresh = () -> {};

        HistoryRevision revision(Path file, long when, String content, String reason, String label) {
            String sha = HistoryBlobStore.sha256(content);
            contentBySha.put(sha, content);
            return new HistoryRevision(file.toString(), when, content.length(), sha, reason, label);
        }

        @Override
        public void refresh() {
            log.add("refresh");
            onRefresh.run();
        }

        @Override
        public void restore(HistoryRevision revision) {
            log.add("restore");
        }

        @Override
        public void restoreToDisk(HistoryRevision revision) {
            log.add("restoreToDisk " + Path.of(revision.path()).getFileName());
        }

        @Override
        public void editLabel(HistoryRevision revision) {
            log.add("editLabel " + revision.timestamp());
        }

        @Override
        public boolean confirmRestoreOverUnsavedEdits(Path file) {
            log.add("ask");
            return agree;
        }

        @Override
        public void focusEditor() {
            log.add("focusEditor");
        }

        @Override
        public void fetchContent(HistoryRevision revision, Consumer<Optional<String>> onText) {
            onText.accept(Optional.ofNullable(contentBySha.get(revision.sha256())));
        }

        @Override
        public void computeDiff(String left, String right, DiffEngine.DiffOptions opts, Consumer<DiffModel> onResult) {
            diffs++;
            onResult.accept(tooLarge ? null : DiffEngine.compute(left, right, opts));
        }

        @Override
        public String currentText(Path target) {
            return current.getOrDefault(target, "");
        }

        @Override
        public void revert(HistoryRevision revision, Runnable done) {
            log.add("revert " + revision.timestamp());
            current.put(Path.of(revision.path()), contentBySha.get(revision.sha256()));
            done.run();
        }

        @Override
        public void applyToLocalIfUnchanged(Path target, String expectedText, String newText, Consumer<Boolean> done) {
            done.accept(false);
        }

        @Override
        public void undoLocal(Path target) {}

        @Override
        public void saveLocal(Path target) {}

        @Override
        public Settings settings() {
            return new Settings();
        }

        @Override
        public boolean hasUnsavedEdits(Path target) {
            return unsaved;
        }
    }

    private record Fixture(Host host, FileHistoryPanel panel, List<HistoryRevision> orders) {
        ListView<HistoryRevision> list() {
            return FxTestSupport.field(panel, "revisions");
        }

        TreeView<Object> tree() {
            return FxTestSupport.field(panel, "folderTree");
        }

        TextField filter() {
            return FxTestSupport.field(panel, "filter");
        }

        Button restore() {
            return FxTestSupport.field(panel, "restore");
        }

        ToggleButton ignoreWhitespace() {
            return FxTestSupport.field(panel, "ignoreWs");
        }

        DiffViewerPane pane() {
            return FxTestSupport.field(panel, "pane");
        }

        BorderPane right() {
            return FxTestSupport.field(panel, "rightPane");
        }

        Label header() {
            return FxTestSupport.field(panel, "headerInfo");
        }

        Label fileLabel() {
            return FxTestSupport.field(panel, "fileLabel");
        }

        /** The text of the placeholder on the right, or null while a diff is drawn there. */
        String rightPlaceholder() {
            return OverlayTestKit.descendants(right().getCenter(), Label.class).stream()
                    .filter(l -> l.getStyleClass().contains("tool-window-placeholder"))
                    .map(Label::getText)
                    .findFirst()
                    .orElse(null);
        }

        ListCell<HistoryRevision> cell(int row) {
            ListCell<HistoryRevision> cell = list().getCellFactory().call(list());
            cell.updateListView(list());
            cell.updateIndex(row);
            return cell;
        }

        void showOrders() {
            panel.setRevisions(orders, "Orders.java", ORDERS);
        }
    }

    /** Three revisions of Orders.java over two days; the editor holds the newest. Runs on the FX thread. */
    private Fixture fixture() {
        Host host = new Host();
        long now = System.currentTimeMillis();
        List<HistoryRevision> orders = List.of(
                host.revision(ORDERS, now - 1_000, "one\ntwo\nthree\n", HistoryRevision.REASON_SAVE, ""),
                host.revision(ORDERS, now - 2_000, "one\ntwo\n", HistoryRevision.REASON_LABEL, "before the fix"),
                host.revision(ORDERS, now - 3 * DAY, "one\n", HistoryRevision.REASON_EXTERNAL, ""));
        host.current.put(ORDERS, "one\ntwo\nthree\n");
        host.current.put(NOTES, "notes\n");
        FileHistoryPanel panel = new FileHistoryPanel(host);
        panel.setDiffSupport(host);
        stage = new Stage();
        stage.setScene(new Scene(panel, sceneWidth, 400));
        stage.show();
        Fixture f = new Fixture(host, panel, orders);
        f.showOrders();
        return f;
    }

    private static void press(Node target, KeyCode code, boolean shift) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
    }

    /** C2: with no revision shown the toolbar is dead, and a toggle cannot conjure the last file's diff. */
    @Test
    void aDiffNeverOutlivesTheRevisionItBelongsTo() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Fixture f = fixture();
            assertTrue(f.right().getTop().isDisabled(), "nothing selected yet: nothing to act on");
            f.list().getSelectionModel().select(2);
            assertNotNull(f.pane());
            assertFalse(f.right().getTop().isDisabled());

            f.panel().setRevisions(List.of(), "notes.md", NOTES); // the other tab
            assertNull(f.pane());
            assertTrue(f.right().getTop().isDisabled());
            int before = f.host().diffs;
            f.ignoreWhitespace().fire(); // disabled for the mouse; a stray action must still do nothing
            assertEquals(before, f.host().diffs, "no revision: nothing to diff");
            assertNull(f.pane());
            assertEquals(tr("history.window.selectPrompt"), f.rightPlaceholder());
            assertEquals("", f.header().getText());
        });
    }

    /** C3 / C4: the diff's current side and the "Current" row follow the editor's text. */
    @Test
    void theDiffAndTheCurrentRowFollowTheEditor() throws Exception {
        Fixture f = FxTestSupport.callOnFx(this::fixture);
        FxTestSupport.runOnFx(() -> {
            assertEquals(tr("history.current"), tag(f.cell(0)));
            assertNull(tag(f.cell(1)));
            f.list().getSelectionModel().select(0);
            assertEquals(0, changes(f.pane()));

            f.host().current.put(ORDERS, "one\ntwo\nthree\ntyped\n"); // typing in the editor
            f.panel().editorTextChanged();
        });
        awaitOnFx(() -> changes(f.pane()) == 1, "the diff to follow the typing");
        FxTestSupport.runOnFx(() -> {
            assertEquals(tr("history.latest"), tag(f.cell(0)), "the newest row no longer equals the editor");
            assertFalse(f.cell(0).getAccessibleText().startsWith(tr("history.current")));

            f.host().current.put(ORDERS, "one\ntwo\n"); // an undo back to the labelled revision's text
            f.panel().editorTextChanged();
        });
        awaitOnFx(() -> tr("history.current").equals(tag(f.cell(1))), "the Current tag to move");
        FxTestSupport.runOnFx(() -> assertEquals(tr("history.latest"), tag(f.cell(0))));
    }

    /** C4: the header names the revision, not a "before". */
    @Test
    void theHeaderSaysWhatTheSelectedVersionIs() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Fixture f = fixture();
            f.list().getSelectionModel().select(1);
            assertTrue(
                    f.header().getText().startsWith("before the fix"),
                    f.header().getText());
            f.list().getSelectionModel().select(2);
            assertTrue(
                    f.header().getText().startsWith(tr("history.reason.external")),
                    f.header().getText());
            assertEquals(f.header().getText(), f.header().getTooltip().getText(), "a narrow header is still readable");
            assertEquals(tr("history.forFile", "Orders.java"), f.fileLabel().getText());
            assertEquals(ORDERS.toString(), f.fileLabel().getTooltip().getText());
        });
    }

    /** C5 / C6: the filter matches the words in the rows, and takes a hidden revision's diff with it. */
    @Test
    void theFilterMatchesShownWordsAndClearsAHiddenDiff() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Fixture f = fixture();
            f.list().getSelectionModel().select(2);
            assertNotNull(f.pane());

            f.filter().setText(tr("history.reason.external"));
            assertEquals(List.of(f.orders().get(2)), List.copyOf(f.list().getItems()));
            assertSame(f.orders().get(2), f.list().getSelectionModel().getSelectedItem(), "still listed: still shown");
            assertNotNull(f.pane());

            f.filter().setText(tr("history.reason.save"));
            assertEquals(List.of(f.orders().get(0)), List.copyOf(f.list().getItems()));
            assertNull(f.pane(), "the shown revision is filtered out: its diff goes too");
            assertTrue(f.right().getTop().isDisabled(), "and Restore with it");

            f.filter().setText("no revision says this");
            assertTrue(f.list().getItems().isEmpty());
            Label placeholder = (Label) f.list().getPlaceholder();
            assertEquals(tr("history.noMatch"), placeholder.getText(), "the file has history; the filter hides it");
            f.filter().setText("");
            assertEquals(tr("history.noRevisions"), placeholder.getText());
            assertEquals(3, f.list().getItems().size());

            f.panel().setRevisions(List.of(), "notes.md", NOTES);
            assertEquals(tr("history.noRevisions"), placeholder.getText());
        });
    }

    /** C1: the row menu opens from the keyboard, F2 edits the label, Enter goes into the diff. */
    @Test
    void rowActionsAreReachableFromTheKeyboard() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Fixture f = fixture();
            f.list().requestFocus();
            f.list().getSelectionModel().select(1);
            f.list().applyCss();
            f.list().layout();
            Event.fireEvent(
                    f.list(), new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 5, 5, true, null));
            ContextMenu menu = Window.getWindows().stream()
                    .filter(w -> w instanceof ContextMenu && w.isShowing())
                    .map(w -> (ContextMenu) w)
                    .findFirst()
                    .orElse(null);
            assertNotNull(menu, "the Menu key opens the selected row's menu");
            assertEquals(
                    List.of(tr("history.menu.restore"), tr("history.menu.editLabel")),
                    OverlayTestKit.labels(menu.getItems()));
            menu.hide();

            press(f.list(), KeyCode.F2, false);
            assertEquals(
                    "editLabel " + f.orders().get(1).timestamp(), f.host().log.getLast());

            layout();
            press(f.list(), KeyCode.ENTER, false);
            Node owner = stage.getScene().getFocusOwner();
            assertTrue(owner != null && owner.getStyleClass().contains("diff-area"), "Enter: into the diff — " + owner);
        });
    }

    /** C12: Tab leaves a read-only diff area, and Escape hands the keyboard back to the editor. */
    @Test
    void tabLeavesTheDiffAndEscapeLeavesThePanel() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Fixture f = fixture();
            f.list().getSelectionModel().select(2);
            layout();
            Node area = f.pane().node().lookup(".diff-area");
            area.requestFocus();
            press(area, KeyCode.TAB, false);
            assertSame(f.filter(), stage.getScene().getFocusOwner(), "Tab: on to the top of the panel");
            area.requestFocus();
            press(area, KeyCode.TAB, true);
            assertSame(f.restore(), stage.getScene().getFocusOwner(), "Shift+Tab: back to Restore");

            f.filter().requestFocus();
            f.filter().setText("x");
            press(f.filter(), KeyCode.ESCAPE, false);
            assertEquals("", f.filter().getText(), "Escape first clears a typed filter");
            assertFalse(f.host().log.contains("focusEditor"));
            press(f.filter(), KeyCode.ESCAPE, false);
            assertEquals("focusEditor", f.host().log.getLast());

            press(f.list(), KeyCode.F5, false);
            assertEquals("refresh", f.host().log.getLast());
        });
    }

    /** C10: one Restore, which asks only when it would replace text that was never saved. */
    @Test
    void restoreAsksOnlyOverUnsavedEdits() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Fixture f = fixture();
            assertEquals(tr("history.window.revert"), f.restore().getText());
            f.list().getSelectionModel().select(2);
            f.restore().fire();
            assertEquals(List.of("revert " + f.orders().get(2).timestamp()), f.host().log, "a saved file: no question");
            assertEquals("one\n", f.host().current.get(ORDERS));
            assertEquals(0, changes(f.pane()), "and the diff is re-read once the restore reports back");

            f.host().log.clear();
            f.host().unsaved = true;
            f.host().agree = false;
            f.list().getSelectionModel().select(1);
            f.restore().fire();
            assertEquals(List.of("ask"), f.host().log, "declined: nothing is replaced");

            // The row's menu takes the same path as the button.
            f.host().log.clear();
            f.host().agree = true;
            OverlayTestKit.item(f.cell(0).getContextMenu().getItems(), tr("history.menu.restore"))
                    .fire();
            assertEquals(List.of("ask", "revert " + f.orders().get(0).timestamp()), f.host().log);
            assertSame(f.orders().get(0), f.list().getSelectionModel().getSelectedItem(), "and shows what it restored");
        });
    }

    /** C15 / C16: a compact row — label first, no "Label" reason, a day caption — that a screen reader can read. */
    @Test
    void rowsAreCompactAndReadable() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Fixture f = fixture();
            ListCell<HistoryRevision> newest = f.cell(0);
            ListCell<HistoryRevision> labelled = f.cell(1);
            ListCell<HistoryRevision> old = f.cell(2);
            assertNull(newest.getText());
            assertEquals(List.of(tr("history.day.today")), texts(newest, "history-day"));
            assertEquals(List.of(), texts(labelled, "history-day"), "same day: no second caption");
            assertEquals(1, texts(old, "history-day").size(), "an older day starts with its date");
            assertFalse(texts(old, "history-day").contains(tr("history.day.today")));

            assertEquals(List.of("before the fix"), texts(labelled, "history-label"));
            assertEquals(List.of(), texts(labelled, "history-row-reason"), "the label already says it is a label");
            assertEquals(List.of(tr("history.reason.save")), texts(newest, "history-row-reason"));

            assertTrue(labelled.getAccessibleText().startsWith("before the fix, "), labelled.getAccessibleText());
            assertTrue(newest.getAccessibleText().startsWith(tr("history.current") + ", "));
            assertTrue(
                    newest.getTooltip().getText().endsWith("14 B"),
                    newest.getTooltip().getText());
            layout();
            List<javafx.scene.control.IndexedCell<?>> drawn = new java.util.ArrayList<>();
            for (Node n : f.list().lookupAll(".list-cell")) {
                if (n instanceof ListCell<?> c && !c.isEmpty()) {
                    drawn.add(c);
                }
            }
            drawn.sort(java.util.Comparator.comparingInt(javafx.scene.control.IndexedCell::getIndex));
            assertEquals(3, drawn.size());
            assertTrue(
                    drawn.get(1).getHeight() < 30,
                    "a text-height row, not the theme's 3em: " + drawn.get(1).getHeight());
            assertTrue(drawn.get(0).getHeight() > drawn.get(1).getHeight(), "the day caption makes its row taller");
            assertEquals(tr("history.forFile", "Orders.java"), f.list().getAccessibleText());
            assertEquals(tr("history.filterPrompt"), f.filter().getAccessibleText());

            ListCell<HistoryRevision> cell = f.cell(0);
            cell.updateIndex(-1);
            assertNull(cell.getGraphic());
            assertNull(cell.getTooltip());
            assertNull(cell.getAccessibleText());
        });
    }

    /** C17 / B6: the folder view shows a revision's diff, restores from the keyboard, and can be left. */
    @Test
    void folderHistoryShowsRestoresAndStepsBack() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Fixture f = fixture();
            Path gone = Path.of("/p/docs/gone.md");
            HistoryRevision notes = f.host().revision(NOTES, 5_000, "old notes\n", HistoryRevision.REASON_SAVE, "");
            HistoryRevision deleted = f.host().revision(gone, 6_000, "was here\n", HistoryRevision.REASON_DELETE, "");
            f.list().getSelectionModel().select(2);
            f.panel()
                    .setFolderHistory(
                            Path.of("/p/docs"),
                            List.of(
                                    new FileHistoryPanel.FileGroup(NOTES.toString(), "notes.md", false, List.of(notes)),
                                    new FileHistoryPanel.FileGroup(
                                            gone.toString(), "gone.md", true, List.of(deleted))));
            assertEquals(Path.of("/p/docs"), f.panel().folderShown());
            assertEquals(tr("history.forFolder", "docs"), f.fileLabel().getText());
            assertNull(f.pane(), "the file view's diff does not carry over");
            assertTrue(f.right().getTop().isDisabled());
            assertFalse(f.filter().isVisible());

            TreeItem<Object> notesRow =
                    f.tree().getRoot().getChildren().get(0).getChildren().get(0);
            TreeItem<Object> goneFile = f.tree().getRoot().getChildren().get(1);
            f.tree().getSelectionModel().select(notesRow);
            assertNotNull(f.pane(), "a revision row shows what it holds");
            assertEquals(1, changes(f.pane()));
            assertEquals(DiffViewerPane.EditableSide.NONE, f.pane().editableSide(), "no per-hunk apply here");
            f.restore().fire();
            assertEquals("restoreToDisk notes.md", f.host().log.getLast());

            f.tree().getSelectionModel().select(goneFile);
            assertNull(f.pane(), "a file row is not one revision");
            f.tree().getSelectionModel().select(goneFile.getChildren().get(0));
            assertEquals(1, changes(f.pane()), "a deleted file compares with nothing");
            press(f.tree(), KeyCode.ENTER, false);
            assertEquals("restoreToDisk gone.md", f.host().log.getLast());

            TreeCell<Object> fileCell = f.tree().getCellFactory().call(f.tree());
            fileCell.updateTreeView(f.tree());
            fileCell.updateIndex(f.tree().getRow(goneFile));
            Label name = OverlayTestKit.descendants(fileCell.getGraphic(), Label.class)
                    .get(0);
            assertEquals("gone.md", name.getText());
            assertTrue(name.getStyleClass().contains("history-deleted-file"), "the name itself is struck through");
            assertEquals("gone.md, " + tr("history.deleted"), fileCell.getAccessibleText());
            fileCell.updateIndex(-1);
            assertNull(fileCell.getGraphic());

            // The name-only overload lists a folder without remembering which.
            f.panel().setFolderHistory("docs", List.of());
            assertNull(f.panel().folderShown());
            f.panel().setFolderHistory(Path.of("/p/docs"), List.of());

            f.host().onRefresh = f::showOrders;
            Button back = FxTestSupport.field(f.panel(), "backToFile");
            assertTrue(back.isVisible());
            back.fire();
            assertNull(f.panel().folderShown());
            assertEquals(tr("history.forFile", "Orders.java"), f.fileLabel().getText());
            assertFalse(back.isVisible());
            assertTrue(f.filter().isVisible());
        });
    }

    /** C19 / C7: a file's selected revision is kept for the way back, and one can be asked for ahead of its list. */
    @Test
    void selectionIsRememberedPerFileAndCanBeAskedForAhead() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Fixture f = fixture();
            f.list().getSelectionModel().select(2);
            f.filter().setText(tr("history.reason.external"));
            f.panel().setRevisions(List.of(), "notes.md", NOTES);
            assertEquals("", f.filter().getText(), "a filter typed for one file is not applied to the next");
            assertNull(f.pane());

            f.showOrders();
            assertSame(f.orders().get(2), f.list().getSelectionModel().getSelectedItem());
            assertNotNull(f.pane(), "back on the tab, back on its diff");

            // Recent Changes: the revision is asked for while another file is listed, then its list arrives.
            f.panel().setRevisions(List.of(), "notes.md", NOTES);
            f.panel().selectRevision(f.orders().get(1));
            assertNull(f.list().getSelectionModel().getSelectedItem());
            f.showOrders();
            assertSame(f.orders().get(1), f.list().getSelectionModel().getSelectedItem());
            assertTrue(f.header().getText().startsWith("before the fix"));

            // Asked for while a filter hides it: the filter gives way.
            f.filter().setText(tr("history.reason.save"));
            f.panel().selectRevision(f.orders().get(2));
            assertEquals("", f.filter().getText());
            assertSame(f.orders().get(2), f.list().getSelectionModel().getSelectedItem());
            f.panel().selectRevision(null);
            assertSame(f.orders().get(2), f.list().getSelectionModel().getSelectedItem());

            // A reload of the same file keeps the shown revision and its pane (each save reloads the list).
            DiffViewerPane pane = f.pane();
            f.showOrders();
            assertSame(pane, f.pane());
            // … and a revision that is gone from the reloaded list takes its diff with it.
            f.panel().setRevisions(f.orders().subList(0, 2), "Orders.java", ORDERS);
            assertNull(f.pane());
        });
    }

    /** C20: a revision that cannot be diffed takes the previous revision's diff off the screen. */
    @Test
    void tooLargeToDiffReplacesTheDiffOnScreen() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Fixture f = fixture();
            f.list().getSelectionModel().select(2);
            assertNotNull(f.pane());
            f.host().tooLarge = true;
            f.list().getSelectionModel().select(1);
            assertNull(f.pane());
            assertEquals(tr("history.window.tooLarge"), f.rightPlaceholder());
            assertTrue(f.ignoreWhitespace().isDisabled(), "nothing to re-diff");
            assertFalse(f.restore().isDisabled(), "the content is there: it can still be restored");
            f.host().tooLarge = false;
            f.list().getSelectionModel().select(2);
            assertNotNull(f.pane());
            assertFalse(f.ignoreWhitespace().isDisabled());
        });
    }

    /** C9: however narrow the diff pane, Restore keeps its whole word; the header and the toggles give way. */
    @Test
    void restoreStaysReadableWhenThePaneIsNarrow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Fixture wide = fixture();
            wide.list().getSelectionModel().select(2);
            layout();
            assertEquals(wide.restore().prefWidth(-1), wide.restore().getWidth(), 1.5);
            assertEquals(
                    wide.ignoreWhitespace().prefWidth(-1),
                    wide.ignoreWhitespace().getWidth(),
                    1.5,
                    "room for all");
            stage.hide();

            sceneWidth = 430;
            Fixture f = fixture();
            f.list().getSelectionModel().select(2);
            layout();
            layout(); // the toggles' minimum follows the width the first pass found
            assertEquals(f.restore().prefWidth(-1), f.restore().getWidth(), 1.5, "Restore is never cut short");
            javafx.geometry.Bounds button = f.restore().localToScene(f.restore().getBoundsInLocal());
            assertTrue(button.getMaxX() <= stage.getScene().getWidth() + 0.5, "and stays inside the window");
            assertTrue(
                    f.ignoreWhitespace().getWidth() < f.ignoreWhitespace().prefWidth(-1) - 2, "the toggles give way");
        });
    }

    /** Opening the tool window lands on the newest row; with no file there is nothing to land on. */
    @Test
    void openingFocusesTheFirstRow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Fixture f = fixture();
            f.panel().focusFirstItem();
            assertEquals(0, f.list().getSelectionModel().getSelectedIndex());
            assertSame(f.list(), stage.getScene().getFocusOwner());
            f.panel().setRevisions(List.of(), null, null);
            assertEquals(tr("history.noFile"), f.fileLabel().getText());
            assertNull(f.fileLabel().getTooltip());
            f.panel().editorTextChanged(); // no file: nothing to follow
            f.panel().setFolderHistory(Path.of("/p/docs"), List.of());
            f.panel().focusFirstItem();
            assertSame(f.tree(), stage.getScene().getFocusOwner());
        });
    }

    private void layout() {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
    }

    private static String tag(ListCell<HistoryRevision> cell) {
        List<String> tags = texts(cell, "history-row-tag");
        return tags.isEmpty() ? null : tags.get(0);
    }

    private static List<String> texts(ListCell<HistoryRevision> cell, String styleClass) {
        return OverlayTestKit.descendants(cell.getGraphic(), Label.class).stream()
                .filter(l -> l.getStyleClass().contains(styleClass))
                .map(Label::getText)
                .toList();
    }

    private static int changes(DiffViewerPane pane) {
        return FxTestSupport.<DiffModel>field(pane, "model").changeBlockStarts().size();
    }

    private static void awaitOnFx(java.util.concurrent.Callable<Boolean> condition, String what) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!FxTestSupport.callOnFx(condition)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }
}
