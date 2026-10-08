package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;
import com.editora.history.HistoryBlobStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Local History records and what a restore does, in a real window: which revisions a save leaves, what
 * a failure says, where a restore lands when the file is open, and what the panel shows afterwards — in this
 * window and in another.
 */
@Tag("fx")
class LocalHistoryRecordRestoreFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Window(GitFeatureFx w, MainController controller, HistoryCoordinator history) {}

    private static Window window(AsyncTestScope async) throws Exception {
        return window(async.own(FxWindowFixture.create()));
    }

    private static Window window(FxWindowFixture fx) {
        return new Window(
                GitFeatureFx.attach(fx), fx.controller, FxTestSupport.field(fx.controller, "historyCoordinator"));
    }

    /** The active editor buffer of {@code win}'s window, or null. FX thread. */
    private static EditorBuffer active(Window win) {
        return (EditorBuffer) FxTestSupport.call(win.controller(), "activeBuffer", new Class<?>[] {});
    }

    private static EditorBuffer open(AsyncTestScope async, Window win, Path file) throws Exception {
        FxTestSupport.runOnFx(() -> win.controller().openAndNavigate(file, 0));
        OverlayTestKit.await(async, "the loaded tab of " + file.getFileName(), () -> {
            EditorBuffer active = active(win);
            return active != null && file.equals(active.getPath()) && !active.isLoading();
        });
        return FxTestSupport.callOnFx(() -> active(win));
    }

    /** Every revision of {@code file}, newest first, from every project's bucket. FX thread. */
    private static List<HistoryRevision> revisions(Window win, Path file) {
        List<HistoryRevision> out = new ArrayList<>();
        for (var bucket : win.w().fx.shared.historyByProject().values()) {
            out.addAll(bucket.getOrDefault(PathKeys.normalizedKey(file), List.of()));
        }
        out.sort(java.util.Comparator.comparingLong(HistoryRevision::timestamp).reversed());
        return out;
    }

    private static void awaitRevisions(AsyncTestScope async, Window win, Path file, int count) throws Exception {
        OverlayTestKit.await(
                async,
                count + " revisions of " + file.getFileName(),
                () -> revisions(win, file).size() == count);
    }

    private static String body(Window win, HistoryRevision revision) {
        HistoryBlobStore blobs = FxTestSupport.field(win.w().fx.shared.historyService(), "blobs");
        return blobs.get(revision.sha256());
    }

    /** Records {@code content} as a revision of {@code file} and waits until it is listed. */
    private static void record(AsyncTestScope async, Window win, Path file, String content, String reason, int total)
            throws Exception {
        FxTestSupport.runOnFx(() -> win.history().record(file, content, reason));
        awaitRevisions(async, win, file, total);
    }

    private static void save(AsyncTestScope async, Window win, EditorBuffer buffer) throws Exception {
        FileWorkflowCoordinator workflows = FxTestSupport.field(win.controller(), "fileWorkflows");
        assertTrue(FxTestSupport.callOnFx(() -> workflows.save(buffer)));
        OverlayTestKit.await(async, "the save to be acknowledged", () -> !buffer.isDirty());
        settle(async, win);
    }

    /** Lets a record travel FX → history worker → FX → index writer. */
    private static void settle(AsyncTestScope async, Window win) throws Exception {
        java.util.concurrent.ExecutorService worker =
                FxTestSupport.field(win.w().fx.shared.historyService(), "exec");
        for (int round = 0; round < 3; round++) {
            async.awaitFx();
            async.awaitWorker(worker);
        }
        async.awaitFx();
        win.w().fx.shared.flushWrites();
        async.awaitFx();
    }

    private static List<HistoryRevision> listed(Window win) {
        ListView<HistoryRevision> list = FxTestSupport.field(win.history().panel(), "revisions");
        return List.copyOf(list.getItems());
    }

    /** Makes every write to the revision store fail, until the returned action is run. */
    private static Runnable breakStore(Window win) throws Exception {
        Path blobs = win.w().fx.shared.getHistoryBlobsDir();
        Files.createDirectories(blobs);
        List<Path> dirs;
        try (var walk = Files.walk(blobs)) {
            dirs = walk.filter(Files::isDirectory).toList();
        }
        for (Path dir : dirs) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        }
        Assumptions.assumeFalse(Files.isWritable(blobs), "running with rights that ignore permissions");
        return () -> {
            for (Path dir : dirs) {
                try {
                    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        };
    }

    // --- B1 ---------------------------------------------------------------------------------------------

    @Test
    void aLargeFileSavedOftenDoesNotCostTheOtherFilesTheirHistory(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path small = dir.resolve("small.txt");
        Path big = dir.resolve("big.log");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            CountDownLatch limited = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> win.history().changeLimits(50, 30, 1, null, done -> limited.countDown()));
            async.await(limited, "the 1 MB project limit");
            for (int i = 1; i <= 6; i++) {
                record(async, win, small, "small " + i + "\n", HistoryRevision.REASON_SAVE, i);
            }
            String filler = "0123456789abcdef".repeat(25_000); // 400 KB
            for (int i = 1; i <= 6; i++) {
                String content = i + filler;
                FxTestSupport.runOnFx(() -> win.history().record(big, content, HistoryRevision.REASON_SAVE));
                settle(async, win);
            }

            assertEquals(
                    6,
                    FxTestSupport.callOnFx(() -> revisions(win, small).size()),
                    "the small file keeps every revision: the large one gives up its own");
            int kept = FxTestSupport.callOnFx(() -> revisions(win, big).size());
            assertTrue(kept >= 1 && kept < 6, "the large file is held to the limit, newest kept: " + kept);
            assertEquals(
                    6 + filler,
                    FxTestSupport.callOnFx(() -> body(win, revisions(win, big).get(0))));
        }
    }

    // --- B11, B13, C8 -----------------------------------------------------------------------------------

    @Test
    void savingAFileThatWasNotChangedLeavesOneRevisionNotTwo(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path file = Files.writeString(dir.resolve("same.txt"), "alpha\nbeta\n");
        Path empty = Files.writeString(dir.resolve("empty.txt"), "");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            EditorBuffer buffer = open(async, win, file);
            save(async, win, buffer);

            List<HistoryRevision> recorded = FxTestSupport.callOnFx(() -> revisions(win, file));
            assertEquals(1, recorded.size(), "the text the save replaced and the text it wrote are one text");
            assertEquals("alpha\nbeta\n", body(win, recorded.get(0)));

            // An edited first save: what it replaced is named for what it is, not as an "external change".
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("gamma\n"));
            save(async, win, buffer);
            assertEquals(
                    List.of(HistoryRevision.REASON_SAVE, HistoryCoordinator.REASON_BASELINE),
                    FxTestSupport.callOnFx(() -> revisions(win, file).stream()
                            .map(HistoryRevision::reason)
                            .toList()));

            // A new, empty file has no text for a baseline to hold.
            EditorBuffer blank = open(async, win, empty);
            FxTestSupport.runOnFx(() -> blank.replaceWholeDocument("first words\n"));
            save(async, win, blank);
            assertEquals(
                    List.of(HistoryRevision.REASON_SAVE),
                    FxTestSupport.callOnFx(() -> revisions(win, empty).stream()
                            .map(HistoryRevision::reason)
                            .toList()));
        }
    }

    @Test
    void autoSavesCloseTogetherAreOneRevisionAndAManualSaveIsNeverReplaced(@TempDir Path temp) throws Exception {
        Path file = temp.toRealPath().resolve("typing.txt");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            record(async, win, file, "saved by hand\n", HistoryRevision.REASON_SAVE, 1);
            record(async, win, file, "typing 1\n", HistoryRevision.REASON_AUTOSAVE, 2);
            for (int i = 2; i <= 5; i++) {
                String content = "typing " + i + "\n";
                FxTestSupport.runOnFx(() -> win.history().record(file, content, HistoryRevision.REASON_AUTOSAVE));
                settle(async, win);
            }

            List<HistoryRevision> recorded = FxTestSupport.callOnFx(() -> revisions(win, file));
            assertEquals(
                    List.of(HistoryRevision.REASON_AUTOSAVE, HistoryRevision.REASON_SAVE),
                    recorded.stream().map(HistoryRevision::reason).toList(),
                    "five auto-saves in one sitting are the latest of them, after the manual save");
            assertEquals("typing 5\n", body(win, recorded.get(0)));
            assertEquals("saved by hand\n", body(win, recorded.get(1)));
        }
    }

    // --- B12 --------------------------------------------------------------------------------------------

    @Test
    void theTextReplaceInFilesFoundIsRecordedInTheEditorsFormUnderItsOwnName(@TempDir Path temp) throws Exception {
        Path file = temp.toRealPath().resolve("win.txt");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            CountDownLatch durable = new CountDownLatch(1);
            FxTestSupport.runOnFx(() ->
                    win.history().recordBeforeReplace(file, "﻿alpha foo\r\nbeta foo\r\n", ok -> durable.countDown()));
            async.await(durable, "the pre-replace revision");

            HistoryRevision before =
                    FxTestSupport.callOnFx(() -> revisions(win, file).get(0));
            assertEquals(HistoryCoordinator.REASON_BEFORE_REPLACE, before.reason());
            assertEquals("alpha foo\nbeta foo\n", body(win, before), "line feeds only, no byte-order mark");
            assertEquals(tr("history.reason.beforeReplace"), FileHistoryPanel.reasonLabel(before.reason()));
            assertEquals(
                    tr("history.reason.beforeReplace"),
                    FileHistoryPanel.reasonLabel("replace-in-files"),
                    "a row stored before the reason had a name of its own reads the same");
            assertEquals(
                    tr("history.reason.baseline"), FileHistoryPanel.reasonLabel(HistoryCoordinator.REASON_BASELINE));

            // The same text saved afterwards is the same content: not a second revision.
            FxTestSupport.runOnFx(
                    () -> win.history().record(file, "alpha foo\nbeta foo\n", HistoryRevision.REASON_SAVE));
            settle(async, win);
            assertEquals(1, FxTestSupport.callOnFx(() -> revisions(win, file).size()));
        }
    }

    // --- B2, B15, B16 -----------------------------------------------------------------------------------

    @Test
    void aRevisionThatCannotBeStoredIsSaidOnceAndALabelDoesNotClaimSuccess(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path file = Files.writeString(dir.resolve("notes.txt"), "one\n");
        Path closed = Files.writeString(dir.resolve("closed.txt"), "edited outside\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            open(async, win, file);
            record(async, win, closed, "as recorded\n", HistoryRevision.REASON_SAVE, 1);
            HistoryRevision recorded =
                    FxTestSupport.callOnFx(() -> revisions(win, closed).get(0));
            Runnable repair = breakStore(win);
            try {
                FxTestSupport.runOnFx(() -> win.history().record(file, "never stored\n", HistoryRevision.REASON_SAVE));
                settle(async, win);
                assertEquals(
                        tr("status.history.recordFailed", "notes.txt"), win.w().status());
                assertEquals(List.of(), FxTestSupport.callOnFx(() -> revisions(win, file)));

                win.w().clearStatus();
                FxTestSupport.runOnFx(() -> win.history().record(file, "nor this\n", HistoryRevision.REASON_AUTOSAVE));
                settle(async, win);
                assertEquals("", win.w().status(), "said once: an auto-save every second would repeat it for ever");

                // Put Label reports what happened to the revision, not that it was asked for.
                FxTestSupport.runOnFx(win.history()::putLabel);
                FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "milestone"));
                settle(async, win);
                assertEquals(
                        tr("status.history.labelFailed", "milestone"), win.w().status());

                // A restore that cannot first keep the file as it is does not replace it.
                CountDownLatch agreed = OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.OK_DONE, null);
                CompletableFuture<HistoryCoordinator.RestoreResult> restore =
                        FxTestSupport.callOnFx(() -> win.history().restoreRevisionToDisk(recorded));
                async.await(agreed, "the overwrite question");
                assertEquals(HistoryCoordinator.RestoreResult.NOT_PRESERVED, async.await(restore));
                assertEquals("edited outside\n", Files.readString(closed));
                assertEquals(
                        tr("status.history.restoreNotPreserved", "closed.txt"),
                        win.w().status());
            } finally {
                repair.run();
            }
        }
    }

    // --- B4 ---------------------------------------------------------------------------------------------

    @Test
    void restoringAFileThatIsOpenGoesIntoItsBufferNotUnderneathIt(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path file = Files.writeString(dir.resolve("open.txt"), "on disk\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            EditorBuffer buffer = open(async, win, file);
            record(async, win, file, "as recorded\n", HistoryRevision.REASON_SAVE, 1);
            HistoryRevision recorded =
                    FxTestSupport.callOnFx(() -> revisions(win, file).get(0));

            // Clean buffer: nothing is asked, the text arrives in the editor, the disk is not written.
            assertEquals(
                    HistoryCoordinator.RestoreResult.RESTORED,
                    async.await(FxTestSupport.callOnFx(() -> win.history().restoreRevisionToDisk(recorded))));
            assertEquals("as recorded\n", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "an edit like any other: saved when the user saves");
            assertTrue(FxTestSupport.callOnFx(() -> buffer.getArea().isUndoAvailable()));
            assertEquals("on disk\n", Files.readString(file));

            // Unsaved edits: asked first; declined, they stay.
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("unsaved work\n"));
            List<Path> asked = new ArrayList<>();
            FxTestSupport.runOnFx(() -> win.history().confirmUnsavedRestore = path -> {
                asked.add(path);
                return false;
            });
            assertEquals(
                    HistoryCoordinator.RestoreResult.CANCELLED,
                    async.await(FxTestSupport.callOnFx(() -> win.history().restoreRevisionToDisk(recorded))));
            assertEquals(List.of(file), asked);
            assertEquals("unsaved work\n", FxTestSupport.callOnFx(buffer::getContent));
            assertEquals("on disk\n", Files.readString(file));

            FxTestSupport.runOnFx(() -> win.history().confirmUnsavedRestore = path -> true);
            assertEquals(
                    HistoryCoordinator.RestoreResult.RESTORED,
                    async.await(FxTestSupport.callOnFx(() -> win.history().restoreRevisionToDisk(recorded))));
            assertEquals("as recorded\n", FxTestSupport.callOnFx(buffer::getContent));
            assertEquals(tr("status.history.restored", "open.txt"), win.w().status());
        }
    }

    // --- B6, B10, C7 ------------------------------------------------------------------------------------

    @Test
    void theFolderListingStaysWhileItsFilesAreRestoredAndADeletedFileCanBeForgotten(@TempDir Path temp)
            throws Exception {
        Path dir = temp.toRealPath();
        Path folder = Files.createDirectory(dir.resolve("pkg"));
        Path one = folder.resolve("one.txt");
        Path two = folder.resolve("two.txt");
        Path other = Files.writeString(dir.resolve("other.txt"), "elsewhere\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            record(async, win, one, "one\n", HistoryRevision.REASON_DELETE, 1);
            record(async, win, two, "two\n", HistoryRevision.REASON_DELETE, 1);
            FileHistoryPanel panel = FxTestSupport.callOnFx(win.history()::panel);

            // Recent Changes on a file that is gone: its folder's listing, where it can be restored from.
            HistoryRevision goneOne =
                    FxTestSupport.callOnFx(() -> revisions(win, one).get(0));
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.invokeWith(win.history(), "openRecentChange", HistoryRevision.class, goneOne));
            assertEquals(folder, FxTestSupport.callOnFx(panel::folderShown));
            assertNull(FxTestSupport.callOnFx(() -> active(win)), "nothing was opened in its place");

            // Restore the first: it opens, and the listing is still there for the second.
            assertEquals(
                    HistoryCoordinator.RestoreResult.RESTORED,
                    async.await(FxTestSupport.callOnFx(() -> win.history().restoreRevisionToDisk(goneOne))));
            OverlayTestKit.await(async, "the restored file's tab", () -> {
                EditorBuffer active = active(win);
                return active != null && one.equals(active.getPath()) && !active.isLoading();
            });
            assertEquals(folder, FxTestSupport.callOnFx(panel::folderShown), "still the folder, not the opened file");
            TreeView<Object> tree = FxTestSupport.field(panel, "folderTree");
            assertEquals(
                    List.of("one.txt=false", "two.txt=true"),
                    FxTestSupport.callOnFx(() -> tree.getRoot().getChildren().stream()
                            .map(item -> (FileHistoryPanel.FileGroup) item.getValue())
                            .map(group -> group.display() + "=" + group.deleted())
                            .sorted()
                            .toList()),
                    "and it knows the first one is back");

            // A save recorded for the open file, and another tab, leave it up as well.
            FxTestSupport.runOnFx(() -> win.history().record(one, "one, edited\n", HistoryRevision.REASON_SAVE));
            settle(async, win);
            open(async, win, other);
            assertEquals(folder, FxTestSupport.callOnFx(panel::folderShown));

            // The deleted file's row offers to delete its history; the question is asked, then it is gone.
            List<String> questions = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                win.history().confirmPurgeAnswer = question -> {
                    questions.add(question);
                    return true;
                };
                TreeItem<Object> row = tree.getRoot().getChildren().stream()
                        .filter(item -> ((FileHistoryPanel.FileGroup) item.getValue())
                                .display()
                                .equals("two.txt"))
                        .findFirst()
                        .orElseThrow();
                TreeCell<Object> cell = tree.getCellFactory().call(tree);
                cell.updateTreeView(tree);
                cell.updateIndex(tree.getRow(row));
                OverlayTestKit.item(cell.getContextMenu().getItems(), tr("history.menu.purge"))
                        .fire();
            });
            assertEquals(List.of(tr("dialog.history.purgeFile.confirm", 1, "two.txt")), questions);
            assertEquals(List.of(), FxTestSupport.callOnFx(() -> revisions(win, two)));
            assertEquals(tr("status.history.purged", 1), win.w().status());

            // The back button's way out: the active file's history again.
            FxTestSupport.runOnFx(panel::showFileView);
            assertNull(FxTestSupport.callOnFx(panel::folderShown));
        }
    }

    @Test
    void recentChangesOpensTheFileAndSelectsTheRevisionThatWasPicked(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path file = Files.writeString(dir.resolve("picked.txt"), "now\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            record(async, win, file, "first\n", HistoryRevision.REASON_SAVE, 1);
            record(async, win, file, "second\n", HistoryRevision.REASON_SAVE, 2);
            HistoryRevision older =
                    FxTestSupport.callOnFx(() -> revisions(win, file).get(1));

            FxTestSupport.runOnFx(win.history()::showRecentChanges);
            assertEquals(
                    2,
                    FxTestSupport.callOnFx(
                            () -> OverlayTestKit.pickerItems(win.w().scene()).size()));
            FxTestSupport.runOnFx(() -> OverlayTestKit.cancelPicker(win.w().scene()));

            FxTestSupport.runOnFx(
                    () -> FxTestSupport.invokeWith(win.history(), "openRecentChange", HistoryRevision.class, older));
            ListView<HistoryRevision> list = FxTestSupport.field(win.history().panel(), "revisions");
            OverlayTestKit.await(
                    async,
                    "the picked revision to be selected",
                    () -> older.equals(list.getSelectionModel().getSelectedItem()));
        }
    }

    @Test
    void purgingAFileSaysWhenOtherFilesKeepTheSameSnapshotsAndWhenTheContentStaysOnDisk(@TempDir Path temp)
            throws Exception {
        Path dir = temp.toRealPath();
        Path origin = dir.resolve("secret.env");
        Path copy = dir.resolve("copy.env");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            record(async, win, origin, "TOKEN=hunter2\n", HistoryRevision.REASON_SAVE, 1);
            record(async, win, copy, "TOKEN=hunter2\n", HistoryRevision.REASON_SAVE, 1);
            List<String> questions = new ArrayList<>();
            FxTestSupport.runOnFx(() -> win.history().confirmPurgeAnswer = question -> {
                questions.add(question);
                return true;
            });
            // Another process holds the configuration: bodies cannot be collected now.
            java.lang.reflect.Field intact = win.w().fx.shared.getClass().getDeclaredField("historyIndexIntact");
            intact.setAccessible(true);
            intact.setBoolean(win.w().fx.shared, false);

            FxTestSupport.runOnFx(() -> win.history().purgeFile(PathKeys.normalizedKey(origin)));

            assertEquals(1, questions.size());
            assertTrue(
                    questions.get(0).startsWith(tr("dialog.history.purgeFile.confirm", 1, "secret.env")),
                    questions.get(0));
            assertTrue(questions.get(0).endsWith(tr("dialog.history.purgeFile.shared", 1)), questions.get(0));
            assertEquals(tr("status.history.purgedPending", 1), win.w().status());
            assertEquals(List.of(), FxTestSupport.callOnFx(() -> revisions(win, origin)));

            intact.setBoolean(win.w().fx.shared, true);
            FxTestSupport.runOnFx(() -> win.history().purgeFile(PathKeys.normalizedKey(copy)));
            assertEquals(
                    tr("dialog.history.purgeFile.confirm", 1, "copy.env"), questions.get(1), "nothing shares it now");
            assertEquals(tr("status.history.purged", 1), win.w().status());
        }
    }

    // --- B7, B14 ----------------------------------------------------------------------------------------

    @Test
    void aFailedRestoreSaysWhatFailedAndARestoreKeepsTheReplacedTextEvenWithHistoryOff(@TempDir Path temp)
            throws Exception {
        Path dir = temp.toRealPath();
        Path locked = Files.createDirectory(dir.resolve("locked"));
        Path gone = locked.resolve("gone.txt");
        Path file = Files.writeString(dir.resolve("kept.txt"), "edited outside\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            record(async, win, gone, "bye\n", HistoryRevision.REASON_DELETE, 1);
            record(async, win, file, "as recorded\n", HistoryRevision.REASON_SAVE, 1);
            HistoryRevision deleted =
                    FxTestSupport.callOnFx(() -> revisions(win, gone).get(0));
            HistoryRevision recorded =
                    FxTestSupport.callOnFx(() -> revisions(win, file).get(0));

            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                Assumptions.assumeFalse(Files.isWritable(locked), "running with rights that ignore permissions");
                assertEquals(
                        HistoryCoordinator.RestoreResult.WRITE_FAILED,
                        async.await(FxTestSupport.callOnFx(() -> win.history().restoreRevisionToDisk(deleted))));
                assertEquals(
                        tr("status.history.restoreWriteFailed", "gone.txt"),
                        win.w().status());
            } finally {
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
            }

            // Switched off, the restore still keeps what it replaces: it is this feature's own overwrite.
            FxTestSupport.runOnFx(() -> win.w().fx.shared.getSettings().setLocalHistory(false));
            CountDownLatch agreed = OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.OK_DONE, null);
            CompletableFuture<HistoryCoordinator.RestoreResult> restore =
                    FxTestSupport.callOnFx(() -> win.history().restoreRevisionToDisk(recorded));
            async.await(agreed, "the overwrite question");
            assertEquals(HistoryCoordinator.RestoreResult.RESTORED, async.await(restore));
            assertEquals("as recorded\n", Files.readString(file));
            assertTrue(
                    FxTestSupport.callOnFx(() -> revisions(win, file).stream()
                            .anyMatch(revision -> "edited outside\n".equals(body(win, revision)))),
                    "the text the restore replaced is a revision");
        }
    }

    @Test
    void inSimpleModeTheStatusNamesSimpleModeNotTheSetting(@TempDir Path temp) throws Exception {
        Path file = Files.writeString(temp.toRealPath().resolve("a.txt"), "a\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async.own(FxWindowFixture.create(false, false, true, c -> {})));
            for (Runnable command : List.<Runnable>of(
                    win.history()::putLabel,
                    win.history()::showRecentChanges,
                    () -> win.history().showForPath(file))) {
                win.w().clearStatus();
                FxTestSupport.runOnFx(command);
                assertEquals(tr("status.history.disabledSimple"), win.w().status());
            }
        }
    }

    // --- B8, B9 -----------------------------------------------------------------------------------------

    @Test
    void anotherWindowsPanelFollowsARevisionRecordedHere(@TempDir Path temp) throws Exception {
        Path file = Files.writeString(temp.toRealPath().resolve("shared.txt"), "text\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Window first = window(fx);
            MainController secondController = FxTestSupport.callOnFx(() -> {
                fx.windowManager.newWindow();
                List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
                return (MainController)
                        FxTestSupport.call(holders.get(holders.size() - 1), "controller", new Class<?>[] {});
            });
            Window second = new Window(
                    first.w(), secondController, FxTestSupport.field(secondController, "historyCoordinator"));
            open(async, first, file);
            open(async, second, file);
            assertEquals(0, FxTestSupport.callOnFx(() -> listed(second).size()));

            FxTestSupport.runOnFx(
                    () -> first.history().record(file, "saved in the first\n", HistoryRevision.REASON_SAVE));

            OverlayTestKit.await(
                    async, "the second window's list", () -> listed(second).size() == 1);
            assertEquals(1, FxTestSupport.callOnFx(() -> listed(first).size()));
        }
    }

    @Test
    void aFilesHistoryIsShownWhicheverProjectsWindowRecordedIt(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path file = Files.writeString(dir.resolve("both.txt"), "text\n");
        String key = PathKeys.normalizedKey(file);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            HistoryRevision elsewhere =
                    new HistoryRevision(key, 1_000L, 5, "sha-elsewhere", HistoryRevision.REASON_SAVE);
            FxTestSupport.runOnFx(
                    () -> win.w().fx.shared.historyBucket("another-project").put(key, List.of(elsewhere)));
            open(async, win, file);
            record(async, win, file, "here\n", HistoryRevision.REASON_SAVE, 2);

            List<HistoryRevision> shown = FxTestSupport.callOnFx(() -> listed(win));
            assertEquals(2, shown.size(), "the row another project's window recorded is listed too");
            assertEquals(elsewhere, shown.get(1), "newest first");

            // The folder listing and Recent Changes read the same merged history.
            FxTestSupport.runOnFx(() -> win.history().showForPath(dir));
            TreeView<Object> tree = FxTestSupport.field(win.history().panel(), "folderTree");
            assertEquals(
                    2,
                    FxTestSupport.callOnFx(() ->
                            tree.getRoot().getChildren().get(0).getChildren().size()));
            FxTestSupport.runOnFx(win.history()::showRecentChanges);
            assertEquals(
                    2,
                    FxTestSupport.callOnFx(
                            () -> OverlayTestKit.pickerItems(win.w().scene()).size()));
            FxTestSupport.runOnFx(() -> OverlayTestKit.cancelPicker(win.w().scene()));

            // A label set from the panel lands on the row where it is stored.
            FxTestSupport.runOnFx(() -> win.history().editLabel(elsewhere));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "from the other window"));
            assertEquals(
                    "from the other window",
                    FxTestSupport.callOnFx(() -> win.w()
                            .fx
                            .shared
                            .historyBucket("another-project")
                            .get(key)
                            .get(0)
                            .label()));
        }
    }

    // --- A2 ---------------------------------------------------------------------------------------------

    @Test
    void aRecordThatReportsBackAfterItsWindowClosedStillReachesTheIndex(@TempDir Path temp) throws Exception {
        Path file = Files.writeString(temp.toRealPath().resolve("closing.txt"), "text\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            open(async, win, file);
            win.w().clearStatus();
            FxTestSupport.runOnFx(() -> {
                win.history().record(file, "saved as the window closed\n", HistoryRevision.REASON_SAVE);
                win.history().shutdown(); // disposeWindow(), with the record still on the worker
            });
            awaitRevisions(async, win, file, 1);
            settle(async, win);

            assertEquals(
                    0, FxTestSupport.callOnFx(() -> listed(win).size()), "the closed window's panel is left alone");
            assertEquals("", win.w().status());
            assertNotNull(
                    Files.readString(win.w().fx.configDir.resolve("history").resolve("index.json")));
            assertTrue(Files.readString(win.w().fx.configDir.resolve("history").resolve("index.json"))
                    .contains(HistoryBlobStore.sha256("saved as the window closed\n")));
        }
    }

    // --- A13 --------------------------------------------------------------------------------------------

    @Test
    void aStricterLimitThatArrivedUnconfirmedIsAskedAboutOnceAndAppliedOnlyWhenAgreed(@TempDir Path temp)
            throws Exception {
        Path file = temp.toRealPath().resolve("many.txt");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            for (int i = 1; i <= 4; i++) {
                record(async, win, file, "v" + i + "\n", HistoryRevision.REASON_SAVE, i);
            }
            AtomicInteger asked = new AtomicInteger();
            AtomicReference<com.editora.history.HistoryRetention.Impact> impact = new AtomicReference<>();
            boolean[] answer = {false};
            FxTestSupport.runOnFx(() -> {
                win.history().confirmPendingLimits = what -> {
                    asked.incrementAndGet();
                    impact.set(what);
                    return answer[0];
                };
                // As a settings sync or an edited settings.json leaves it: written, never confirmed.
                win.w().fx.shared.getSettings().setHistoryMaxPerFile(2);
                win.history().applySupport();
            });
            OverlayTestKit.await(async, "the question", () -> asked.get() == 1);
            settle(async, win);
            assertEquals(2, impact.get().revisions());
            assertEquals(1, impact.get().files());
            assertEquals(tr("status.history.limitsPending"), win.w().status());
            assertEquals(4, FxTestSupport.callOnFx(() -> revisions(win, file).size()), "declined: nothing deleted");

            FxTestSupport.runOnFx(win.history()::applySupport);
            settle(async, win);
            assertEquals(1, asked.get(), "the same limits are not asked about again");

            answer[0] = true;
            FxTestSupport.runOnFx(() -> {
                win.w().fx.shared.getSettings().setHistoryMaxPerFile(3);
                win.history().applySupport();
            });
            OverlayTestKit.await(async, "the question about the new limit", () -> asked.get() == 2);
            OverlayTestKit.await(async, "the sweep", () -> revisions(win, file).size() == 3);
        }
    }

    @Test
    void theEditorsFormOfATextAndTheCatalogEntriesOfEveryRestoreOutcome() {
        assertEquals("a\nb\n", HistoryCoordinator.editorForm("a\r\nb\r"));
        assertEquals("café\n", HistoryCoordinator.editorForm("\uFEFFcafé\n"));
        assertEquals("", HistoryCoordinator.editorForm(""));
        assertNull(HistoryCoordinator.editorForm(null));
        for (HistoryCoordinator.RestoreResult result : HistoryCoordinator.RestoreResult.values()) {
            String key = HistoryCoordinator.restoreMessageKey(result);
            if (key != null) {
                assertFalse(tr(key, "x").equals(key), key + " is in the catalog"); // a missing key shows as itself
            }
        }
    }
}
