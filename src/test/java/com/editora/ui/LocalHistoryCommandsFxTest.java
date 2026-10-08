package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.config.HistoryRevision;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local File History as its commands drive it in a real window: labelled snapshots, the revision list and its
 * filter, relabelling, the cross-file picker, a folder's history with a deleted file in it, restoring to disk,
 * and forgetting a file's history.
 */
@Tag("fx")
class LocalHistoryCommandsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Window(GitFeatureFx w, HistoryCoordinator history, HistoryCoordinator.Ops ops) {}

    private static Window window(AsyncTestScope async) throws Exception {
        FxWindowFixture fx = async.own(FxWindowFixture.create());
        GitFeatureFx w = GitFeatureFx.attach(fx);
        HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
        return new Window(w, history, FxTestSupport.field(history, "ops"));
    }

    private static EditorBuffer open(AsyncTestScope async, Window win, Path file) throws Exception {
        FxTestSupport.runOnFx(() -> win.w().fx.controller.openAndNavigate(file, 0));
        OverlayTestKit.await(
                async,
                "the tab of " + file.getFileName(),
                () -> win.w().active() != null && file.equals(win.w().active().getPath()));
        return FxTestSupport.callOnFx(win.w()::active);
    }

    /** The recorded revisions of {@code file}, newest first. FX thread. */
    private static List<HistoryRevision> revisions(Window win, Path file) {
        List<HistoryRevision> out = new ArrayList<>();
        for (List<HistoryRevision> list : win.ops().historyMap().values()) {
            for (HistoryRevision revision : list) {
                if (revision.path().equals(file.toString())) {
                    out.add(revision);
                }
            }
        }
        return out;
    }

    private static void awaitRevisions(AsyncTestScope async, Window win, Path file, int count) throws Exception {
        OverlayTestKit.await(
                async,
                count + " revisions of " + file.getFileName(),
                () -> revisions(win, file).size() == count);
    }

    private static void putLabel(AsyncTestScope async, Window win, String typed) throws Exception {
        FxTestSupport.runOnFx(win.history()::putLabel);
        assertEquals(
                tr("history.label.title"),
                FxTestSupport.callOnFx(() -> OverlayTestKit.formTitle(win.w().scene())));
        FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), typed));
        async.awaitFx();
    }

    @Test
    void aLabelledSnapshotKeepsTheTextAsItWasWhenTheLabelWasAskedFor(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path file = Files.writeString(dir.resolve("notes.txt"), "one\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            EditorBuffer buffer = open(async, win, file);

            putLabel(async, win, "  first draft  ");
            awaitRevisions(async, win, file, 1);
            HistoryRevision first =
                    FxTestSupport.callOnFx(() -> revisions(win, file).get(0));
            assertEquals("first draft", first.label(), "the typed name is trimmed");
            assertEquals(HistoryRevision.REASON_LABEL, first.reason());
            assertEquals(tr("status.history.labeled", "first draft"), win.w().status());

            // A blank name records nothing.
            win.w().clearStatus();
            putLabel(async, win, "   ");
            assertEquals("", win.w().status());

            // Unsaved edits are part of a labelled snapshot: it marks the buffer, not the file on disk.
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("two\n"));
            putLabel(async, win, "second draft");
            awaitRevisions(async, win, file, 2);
            assertEquals("one\n", Files.readString(file), "nothing was saved");
            HistoryRevision second = FxTestSupport.callOnFx(() -> revisions(win, file).stream()
                    .filter(revision -> "second draft".equals(revision.label()))
                    .findFirst()
                    .orElseThrow());
            assertFalse(second.sha256().equals(first.sha256()), "a different text, a different snapshot");
        }
    }

    @Test
    void theHistoryWindowListsFiltersAndRelabelsTheActiveFilesRevisions(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path file = Files.writeString(dir.resolve("notes.txt"), "one\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            EditorBuffer buffer = open(async, win, file);
            putLabel(async, win, "alpha");
            awaitRevisions(async, win, file, 1);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("two\n"));
            putLabel(async, win, "beta");
            awaitRevisions(async, win, file, 2);

            FxTestSupport.runOnFx(win.history()::showActive);
            FileHistoryPanel panel = FxTestSupport.callOnFx(win.history()::panel);
            ListView<HistoryRevision> list = FxTestSupport.field(panel, "revisions");
            assertEquals(2, FxTestSupport.callOnFx(() -> list.getItems().size()));
            Label fileLabel = FxTestSupport.field(panel, "fileLabel");
            assertEquals(tr("history.forFile", "notes.txt"), FxTestSupport.callOnFx(fileLabel::getText));

            // The filter matches a label; clearing it brings every revision back.
            TextField filter = FxTestSupport.field(panel, "filter");
            FxTestSupport.runOnFx(() -> filter.setText("BETA"));
            assertEquals(
                    List.of("beta"),
                    FxTestSupport.callOnFx(() ->
                            list.getItems().stream().map(HistoryRevision::label).toList()));
            FxTestSupport.runOnFx(() -> filter.setText("no revision is called this"));
            assertEquals(List.of(), FxTestSupport.callOnFx(() -> List.copyOf(list.getItems())));
            FxTestSupport.runOnFx(() -> filter.setText(""));
            assertEquals(2, FxTestSupport.callOnFx(() -> list.getItems().size()));

            // The rows as the list's own cells draw them: each shows its label, and the newest is marked.
            FxTestSupport.runOnFx(() -> {
                for (int row = 0; row < list.getItems().size(); row++) {
                    javafx.scene.control.ListCell<HistoryRevision> cell =
                            list.getCellFactory().call(list);
                    cell.updateListView(list);
                    cell.updateIndex(row);
                    HistoryRevision revision = cell.getItem();
                    assertEquals(
                            List.of(revision.label()),
                            OverlayTestKit.descendants(cell.getGraphic(), Label.class).stream()
                                    .filter(label -> label.getStyleClass().contains("history-label"))
                                    .map(Label::getText)
                                    .toList());
                    assertEquals(
                            "beta".equals(revision.label()),
                            cell.getAccessibleText().startsWith(tr("history.current") + ", "),
                            "only the newest revision is the current one: " + cell.getAccessibleText());
                    assertNull(cell.getText(), "the row is drawn by its graphic");
                    assertTrue(cell.getAccessibleText().contains(tr("history.reason.label")), cell.getAccessibleText());
                    assertEquals(
                            List.of(tr("history.menu.restore"), tr("history.menu.editLabel")),
                            OverlayTestKit.labels(cell.getContextMenu().getItems()));
                    cell.updateIndex(-1); // an emptied cell shows nothing and offers nothing
                    assertNull(cell.getGraphic());
                    assertNull(cell.getAccessibleText());
                    assertNull(cell.getContextMenu());
                }
            });

            // Relabel: the prompt starts from the current label.
            HistoryRevision alpha = FxTestSupport.callOnFx(() -> revisions(win, file).stream()
                    .filter(revision -> "alpha".equals(revision.label()))
                    .findFirst()
                    .orElseThrow());
            FxTestSupport.runOnFx(() -> win.history().editLabel(alpha));
            assertEquals(
                    "alpha",
                    FxTestSupport.callOnFx(() ->
                            OverlayTestKit.formFields(win.w().scene()).get(0).getText()));
            win.w().clearStatus();
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene())); // accepted unchanged
            assertEquals("", win.w().status(), "an unchanged label is not a change");

            FxTestSupport.runOnFx(() -> win.history().editLabel(alpha));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), " release 1 "));
            assertEquals(tr("status.history.labeled", "release 1"), win.w().status());
            HistoryRevision renamed = FxTestSupport.callOnFx(() -> revisions(win, file).stream()
                    .filter(revision -> "release 1".equals(revision.label()))
                    .findFirst()
                    .orElseThrow());
            assertEquals(alpha.sha256(), renamed.sha256(), "the same snapshot, renamed in place");
            assertEquals(2, FxTestSupport.callOnFx(() -> revisions(win, file).size()));

            // The old record is gone from the index: relabelling it again finds nothing and changes nothing.
            win.w().clearStatus();
            FxTestSupport.runOnFx(() -> win.history().editLabel(alpha));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "ghost"));
            assertEquals("", win.w().status());

            // An empty answer clears the label.
            FxTestSupport.runOnFx(() -> win.history().editLabel(renamed));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "  "));
            assertEquals(tr("status.history.labelCleared"), win.w().status());
            assertEquals(
                    List.of("", "beta"),
                    FxTestSupport.callOnFx(() -> revisions(win, file).stream()
                            .map(revision -> revision.label() == null ? "" : revision.label())
                            .sorted()
                            .toList()));
            FxTestSupport.runOnFx(() -> win.history().editLabel(null)); // nothing selected: nothing asked
            assertNull(FxTestSupport.callOnFx(() -> OverlayTestKit.form(win.w().scene())));
        }
    }

    @Test
    void recentChangesListsRevisionsAcrossFilesAndOpensThePickedOne(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path first = Files.writeString(dir.resolve("first.txt"), "first\n");
        Path second = Files.writeString(dir.resolve("second.txt"), "second\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            FxTestSupport.runOnFx(() -> {
                win.history().record(first, "first\n", HistoryRevision.REASON_SAVE);
                win.history().record(second, "second\n", HistoryRevision.REASON_AUTOSAVE);
            });
            awaitRevisions(async, win, first, 1);
            awaitRevisions(async, win, second, 1);

            FxTestSupport.runOnFx(win.history()::showRecentChanges);
            List<?> offered = FxTestSupport.callOnFx(
                    () -> OverlayTestKit.pickerItems(win.w().scene()));
            assertEquals(
                    List.of(first.toString(), second.toString()),
                    offered.stream()
                            .map(revision -> ((HistoryRevision) revision).path())
                            .sorted()
                            .toList());
            assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pick(win.w().scene(), "second.txt")));
            OverlayTestKit.await(
                    async,
                    "second.txt to open",
                    () -> win.w().active() != null
                            && second.equals(win.w().active().getPath()));
            FileHistoryPanel panel = FxTestSupport.callOnFx(win.history()::panel);
            Label fileLabel = FxTestSupport.field(panel, "fileLabel");
            OverlayTestKit.await(
                    async,
                    "its history to be shown",
                    () -> tr("history.forFile", "second.txt").equals(fileLabel.getText()));

            assertEquals(
                    tr("history.reason.autosave"),
                    HistoryCoordinator.historyReasonLabel(HistoryRevision.REASON_AUTOSAVE));
            assertEquals(
                    tr("history.reason.delete"), HistoryCoordinator.historyReasonLabel(HistoryRevision.REASON_DELETE));
            assertEquals(
                    tr("history.reason.label"), HistoryCoordinator.historyReasonLabel(HistoryRevision.REASON_LABEL));
            assertEquals(tr("history.reason.save"), HistoryCoordinator.historyReasonLabel(null));
        }
    }

    @Test
    void aFoldersHistoryIncludesItsDeletedFilesAndRestoresThemToDisk(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path folder = Files.createDirectories(dir.resolve("project/src"));
        Path kept = Files.writeString(folder.resolve("kept.txt"), "kept, as recorded\n");
        Path gone = folder.resolve("gone.txt");
        Path empty = Files.createDirectories(dir.resolve("project/empty"));
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            FxTestSupport.runOnFx(() -> {
                win.history().record(kept, "kept, as recorded\n", HistoryRevision.REASON_SAVE);
                win.history().record(gone, "the only copy left\n", HistoryRevision.REASON_DELETE);
            });
            awaitRevisions(async, win, kept, 1);
            awaitRevisions(async, win, gone, 1);

            win.w().clearStatus();
            FxTestSupport.runOnFx(() -> win.history().showForPath(empty));
            assertEquals(tr("status.history.folderEmpty", "empty"), win.w().status());
            FxTestSupport.runOnFx(() -> win.history().showForPath(null));
            assertEquals(tr("status.history.noFile"), win.w().status());

            FxTestSupport.runOnFx(() -> win.history().showForPath(folder));
            FileHistoryPanel panel = FxTestSupport.callOnFx(win.history()::panel);
            Label fileLabel = FxTestSupport.field(panel, "fileLabel");
            assertEquals(tr("history.forFolder", "src"), FxTestSupport.callOnFx(fileLabel::getText));
            TreeView<Object> tree = FxTestSupport.field(panel, "folderTree");
            List<FileHistoryPanel.FileGroup> groups = FxTestSupport.callOnFx(() -> tree.getRoot().getChildren().stream()
                    .map(item -> (FileHistoryPanel.FileGroup) item.getValue())
                    .sorted(java.util.Comparator.comparing(FileHistoryPanel.FileGroup::display))
                    .toList());
            assertEquals(
                    List.of("gone.txt", "kept.txt"),
                    groups.stream().map(FileHistoryPanel.FileGroup::display).toList());
            assertTrue(groups.get(0).deleted(), "a file that is no longer on disk is marked as deleted");
            assertFalse(groups.get(1).deleted());
            ListView<HistoryRevision> list = FxTestSupport.field(panel, "revisions");
            assertFalse(FxTestSupport.callOnFx(list::isVisible), "the single-file list gives way to the tree");

            // The rows as the tree's own cells draw them: the deleted file says so, a revision offers Restore.
            List<TreeCell<Object>> cells = FxTestSupport.callOnFx(() -> {
                List<TreeCell<Object>> out = new ArrayList<>();
                for (TreeItem<Object> group : tree.getRoot().getChildren()) {
                    group.setExpanded(true);
                }
                for (TreeItem<Object> group : tree.getRoot().getChildren()) {
                    List<TreeItem<Object>> rows = new ArrayList<>(List.of(group));
                    rows.addAll(group.getChildren());
                    for (TreeItem<Object> row : rows) {
                        TreeCell<Object> cell = tree.getCellFactory().call(tree);
                        cell.updateTreeView(tree);
                        cell.updateIndex(tree.getRow(row));
                        out.add(cell);
                    }
                }
                return out;
            });
            assertEquals(4, cells.size());
            assertTrue(
                    cells.stream()
                            .anyMatch(cell -> ("gone.txt, " + tr("history.deleted")).equals(cell.getAccessibleText())),
                    cells.stream().map(TreeCell::getAccessibleText).toList().toString());
            assertTrue(cells.stream().anyMatch(cell -> "kept.txt".equals(cell.getAccessibleText())));
            assertTrue(
                    cells.stream()
                            .filter(cell -> cell.getItem() instanceof FileHistoryPanel.FileGroup)
                            .allMatch(cell -> cell.getContextMenu() == null),
                    "a file row has nothing to restore by itself");
            TreeCell<Object> goneRevision = cells.stream()
                    .filter(cell -> cell.getItem() instanceof HistoryRevision revision
                            && revision.path().equals(gone.toString()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(
                    goneRevision.getAccessibleText().contains(tr("history.reason.delete")),
                    goneRevision.getAccessibleText());
            assertTrue(
                    goneRevision.getTooltip().getText().endsWith("19 B"),
                    goneRevision.getTooltip().getText());

            // Restore the deleted file from its row's menu: nothing to overwrite, so nothing is asked.
            FxTestSupport.runOnFx(
                    () -> OverlayTestKit.item(goneRevision.getContextMenu().getItems(), tr("history.menu.restore"))
                            .fire());
            OverlayTestKit.await(async, "the deleted file to come back", () -> Files.exists(gone));
            assertEquals("the only copy left\n", Files.readString(gone));

            // Restoring over a file that exists asks first; declined, the file keeps what it has.
            Files.writeString(kept, "edited since\n");
            HistoryRevision keptRevision =
                    FxTestSupport.callOnFx(() -> revisions(win, kept).get(0));
            AtomicReference<OverlayTestKit.Shown> question = new AtomicReference<>();
            CountDownLatch declined =
                    OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE, question);
            CompletableFuture<HistoryCoordinator.RestoreResult> cancelled =
                    FxTestSupport.callOnFx(() -> win.history().restoreRevisionToDisk(keptRevision));
            async.await(declined, "the overwrite question");
            assertEquals(HistoryCoordinator.RestoreResult.CANCELLED, async.await(cancelled));
            assertEquals(
                    tr("history.restoreOverwrite", "kept.txt"), question.get().content());
            assertEquals("edited since\n", Files.readString(kept));

            CountDownLatch agreed = OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.OK_DONE, null);
            CompletableFuture<HistoryCoordinator.RestoreResult> restored =
                    FxTestSupport.callOnFx(() -> win.history().restoreRevisionToDisk(keptRevision));
            async.await(agreed, "the overwrite question, accepted");
            assertEquals(HistoryCoordinator.RestoreResult.RESTORED, async.await(restored));
            assertEquals("kept, as recorded\n", Files.readString(kept));

            // A request that names no file is refused without touching anything.
            HistoryRevision nowhere = new HistoryRevision("", 1L, 1L, "sha", HistoryRevision.REASON_SAVE);
            assertEquals(
                    HistoryCoordinator.RestoreResult.INVALID_REQUEST,
                    async.await(FxTestSupport.callOnFx(() -> win.history().restoreRevisionToDisk(nowhere))));
            assertEquals(
                    HistoryCoordinator.RestoreResult.INVALID_REQUEST,
                    async.await(FxTestSupport.callOnFx(() -> win.history().restoreRevisionToDisk(null))));
        }
    }

    @Test
    void showingAFilesHistoryFromTheProjectTreeOpensTheFileFirst(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path file = Files.writeString(dir.resolve("tree.txt"), "from the tree\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            FxTestSupport.runOnFx(() -> win.history().record(file, "from the tree\n", HistoryRevision.REASON_SAVE));
            awaitRevisions(async, win, file, 1);

            FxTestSupport.runOnFx(() -> win.history().showForPath(file));
            OverlayTestKit.await(
                    async,
                    "the file to open",
                    () -> win.w().active() != null
                            && file.equals(win.w().active().getPath()));
            FileHistoryPanel panel = FxTestSupport.callOnFx(win.history()::panel);
            ListView<HistoryRevision> list = FxTestSupport.field(panel, "revisions");
            OverlayTestKit.await(
                    async, "its revision in the list", () -> list.getItems().size() == 1);
            assertTrue(FxTestSupport.callOnFx(list::isVisible));
        }
    }

    @Test
    void withHistorySwitchedOffItsCommandsSaySoButWhatWasStoredCanStillBeDeleted(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path file = Files.writeString(dir.resolve("secret.txt"), "token=hunter2\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            open(async, win, file);
            putLabel(async, win, "with the secret");
            awaitRevisions(async, win, file, 1);
            putLabel(async, win, "again");
            awaitRevisions(async, win, file, 2);
            HistoryRevision stored =
                    FxTestSupport.callOnFx(() -> revisions(win, file).get(0));

            FxTestSupport.runOnFx(() -> win.w().fx.shared.getSettings().setLocalHistory(false));
            for (Runnable command : List.<Runnable>of(
                    win.history()::showActive,
                    win.history()::putLabel,
                    win.history()::showRecentChanges,
                    () -> win.history().showForPath(file))) {
                win.w().clearStatus();
                FxTestSupport.runOnFx(command);
                assertEquals(tr("status.history.disabled"), win.w().status());
            }
            FxTestSupport.runOnFx(() -> win.history().editLabel(stored));
            assertNull(FxTestSupport.callOnFx(() -> OverlayTestKit.form(win.w().scene())), "nothing is asked");
            assertNull(
                    FxTestSupport.callOnFx(() -> OverlayTestKit.picker(win.w().scene())));

            // Forgetting still works: turning the feature off must not strand what it stored.
            AtomicReference<OverlayTestKit.Shown> question = new AtomicReference<>();
            CountDownLatch declined =
                    OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE, question);
            FxTestSupport.runOnFx(win.history()::purgeActiveFile);
            async.await(declined, "the purge question");
            assertEquals(
                    tr("dialog.history.purgeFile.confirm", 2, "secret.txt"),
                    question.get().content());
            assertEquals(2, FxTestSupport.callOnFx(() -> revisions(win, file).size()), "declined: kept");

            CountDownLatch agreed = OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.OK_DONE, null);
            FxTestSupport.runOnFx(win.history()::purgeActiveFile);
            async.await(agreed, "the purge question, accepted");
            async.awaitFx();
            assertEquals(tr("status.history.purged", 2), win.w().status());
            assertEquals(List.of(), FxTestSupport.callOnFx(() -> revisions(win, file)));

            FxTestSupport.runOnFx(win.history()::purgeActiveFile);
            assertEquals(tr("status.history.nothingToPurge"), win.w().status());
            FxTestSupport.runOnFx(win.history()::purgeProject);
            assertEquals(tr("status.history.nothingToPurge"), win.w().status());
        }
    }

    @Test
    void sizesAndReasonsAreSpelledForTheReader() {
        assertEquals("0 B", HistoryRowText.sizeText(0, java.util.Locale.ROOT));
        assertEquals("1023 B", HistoryRowText.sizeText(1023, java.util.Locale.ROOT));
        assertEquals("1.0 KB", HistoryRowText.sizeText(1024, java.util.Locale.ROOT));
        assertEquals("1.5 KB", HistoryRowText.sizeText(1536, java.util.Locale.ROOT));
        assertEquals("1.0 MB", HistoryRowText.sizeText(1024 * 1024, java.util.Locale.ROOT));
        assertEquals("2.5 MB", HistoryRowText.sizeText((long) (2.5 * 1024 * 1024), java.util.Locale.ROOT));
        assertEquals("2,5 MB", HistoryRowText.sizeText((long) (2.5 * 1024 * 1024), java.util.Locale.GERMANY));

        assertEquals(tr("history.reason.autosave"), FileHistoryPanel.reasonLabel(HistoryRevision.REASON_AUTOSAVE));
        assertEquals(tr("history.reason.external"), FileHistoryPanel.reasonLabel(HistoryRevision.REASON_EXTERNAL));
        assertEquals(tr("history.reason.label"), FileHistoryPanel.reasonLabel(HistoryRevision.REASON_LABEL));
        assertEquals(tr("history.reason.delete"), FileHistoryPanel.reasonLabel(HistoryRevision.REASON_DELETE));
        assertEquals(tr("history.reason.save"), FileHistoryPanel.reasonLabel(HistoryRevision.REASON_SAVE));
        assertEquals(tr("history.reason.save"), FileHistoryPanel.reasonLabel(null));
        assertEquals(tr("history.reason.save"), FileHistoryPanel.reasonLabel("something newer"));
    }
}
