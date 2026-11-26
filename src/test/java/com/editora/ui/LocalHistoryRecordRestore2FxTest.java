package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javafx.scene.control.Tab;

import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;
import com.editora.editops.LineEndings;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The record/restore half of the second Local History fix pass (findings R1–R17, F3, U4), in real windows: what
 * is recorded before a file changes, under which reason, and where a restore lands.
 */
@Tag("fx")
@org.junit.jupiter.api.extension.ExtendWith(OffFxInPlace.class)
class LocalHistoryRecordRestore2FxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Window(GitFeatureFx w, MainController controller, HistoryCoordinator history) {}

    private static Window window(FxWindowFixture fx) {
        return new Window(
                GitFeatureFx.attach(fx), fx.controller, FxTestSupport.field(fx.controller, "historyCoordinator"));
    }

    private static Window window(AsyncTestScope async) throws Exception {
        return window(async.own(FxWindowFixture.create()));
    }

    private static Window secondWindow(Window first) throws Exception {
        MainController second = FxTestSupport.callOnFx(() -> {
            first.w().fx.windowManager.newWindow();
            List<?> holders = FxTestSupport.field(first.w().fx.windowManager, "windows");
            return (MainController)
                    FxTestSupport.call(holders.get(holders.size() - 1), "controller", new Class<?>[] {});
        });
        return new Window(first.w(), second, FxTestSupport.field(second, "historyCoordinator"));
    }

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

    private static void record(AsyncTestScope async, Window win, Path file, String content, String reason, int total)
            throws Exception {
        FxTestSupport.runOnFx(() -> win.history().record(file, content, reason));
        awaitRevisions(async, win, file, total);
    }

    /**
     * Whether a file or a folder can still be made in {@code dir}: root on Linux, or on Windows a session with the
     * backup and restore privileges (an administrator's SSH logon), passes over the lock. Files.isWritable does not
     * look at a deny ACL.
     */
    private static boolean canAddTo(Path dir) {
        for (boolean folder : new boolean[] {false, true}) {
            try {
                Files.delete(
                        folder ? Files.createTempDirectory(dir, "probe") : Files.createTempFile(dir, "probe", ".tmp"));
                return true;
            } catch (java.io.IOException refused) {
                // locked for this kind
            }
        }
        return false;
    }

    /** Makes every write to the revision store fail, until the returned action is run (see the first suite). */
    private static Runnable breakStore(AsyncTestScope async, Window win) throws Exception {
        com.editora.history.HistoryService service = win.w().fx.shared.historyService();
        win.w().fx.shared.flushWrites();
        OverlayTestKit.await(
                async,
                "the session's first history collection",
                () -> FxTestSupport.<Boolean>field(service, "gcHasRun"));
        async.awaitWorker(FxTestSupport.field(service, "exec"));
        Path blobs = win.w().fx.shared.getHistoryBlobsDir();
        Files.createDirectories(blobs);
        List<Path> dirs;
        try (var walk = Files.walk(blobs)) {
            dirs = walk.filter(Files::isDirectory).toList();
        }
        Runnable unlock = com.editora.testkit.UnwritableDirs.lock(dirs);
        Assumptions.assumeFalse(canAddTo(blobs), "running with rights that ignore permissions");
        return unlock;
    }

    // --- R1 ---------------------------------------------------------------------------------------------

    @Test
    void theNoUndoCopyIsOnDiskBeforeTheEditIsAllowedAndRefusedWhenItCannotBe(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path warm = Files.writeString(dir.resolve("warm.txt"), "w\n");
        Path file = Files.writeString(dir.resolve("big.txt"), "precious\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            EditorBuffer buffer = open(async, win, file);
            assertEquals(
                    NoUndoGuard.Copy.STORED,
                    FxTestSupport.callOnFx(() -> win.history().recordSafetyCopy(buffer, "before")));
            List<HistoryRevision> now = FxTestSupport.callOnFx(() -> revisions(win, file));
            assertEquals(1, now.size(), "listed when the answer is given, not later");
            assertEquals("before", now.get(0).label());
            assertEquals("precious\n", body(win, now.get(0)), "and its body is on disk");

            record(async, win, warm, "w\n", HistoryRevision.REASON_SAVE, 1);
            Runnable repair = breakStore(async, win);
            try {
                // A text whose shard folder does not exist yet: a write into an existing shard repairs the store.
                Path blobs = win.w().fx.shared.getHistoryBlobsDir();
                String text = null;
                for (int i = 0; text == null; i++) {
                    String candidate = "unsaved " + i + "\n";
                    if (!Files.exists(
                            blobs.resolve(HistoryBlobStore.sha256(candidate).substring(0, 2)))) {
                        text = candidate;
                    }
                }
                String unsaved = text;
                FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument(unsaved));
                assertEquals(
                        NoUndoGuard.Copy.FAILED,
                        FxTestSupport.callOnFx(() -> win.history().recordSafetyCopy(buffer, "again")));
                settle(async, win);
                assertEquals(1, revisions(win, file).size());
                assertFalse(NoUndoGuard.verdict(NoUndoGuard.Copy.FAILED, "op").allowed(), "the edit is refused");
                assertFalse(
                        tr(NoUndoGuard.messageKey(NoUndoGuard.Copy.FAILED), "op")
                                .equals(tr("status.history.recordFailed", "big.txt")),
                        "its own words, not those of a save that went through");
            } finally {
                repair.run();
            }
        }
    }

    // --- R2 ---------------------------------------------------------------------------------------------

    @Test
    void aPlainAscFileIsRecordedAndAnEncryptedOneIsNot(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path plain = Files.writeString(dir.resolve("notes.asc"), "= AsciiDoc title\n");
        Path message = Files.writeString(
                dir.resolve("secret.asc"), "-----BEGIN PGP MESSAGE-----\n\nhQEMA...\n-----END PGP MESSAGE-----\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            EditorBuffer buffer = open(async, win, plain);
            assertEquals(
                    NoUndoGuard.Copy.STORED,
                    FxTestSupport.callOnFx(() -> win.history().recordSafetyCopy(buffer, "copy")));
            record(async, win, plain, "= AsciiDoc title\nmore\n", HistoryRevision.REASON_SAVE, 2);

            CompletableFuture<HistoryCoordinator.DeleteCapture> captured = new CompletableFuture<>();
            FxTestSupport.runOnFx(() -> win.history().captureBeforeDeleteDurably(message, captured::complete));
            assertTrue(async.await(captured).durable(), "an encrypted file may be deleted: it has no history");
            settle(async, win);
            assertEquals(List.of(), revisions(win, message), "its ciphertext is not kept either");
        }
    }

    // --- R4 ---------------------------------------------------------------------------------------------

    @Test
    void aBufferHoldingPartOfItsFileIsNoRevisionOfIt(@TempDir Path temp) throws Exception {
        Path file = Files.writeString(temp.toRealPath().resolve("huge.log"), "first part\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            EditorBuffer buffer = open(async, win, file);
            FxTestSupport.runOnFx(() -> buffer.setTruncatedLoad(true));
            FxTestSupport.runOnFx(() -> win.history().record(buffer, HistoryRevision.REASON_EXTERNAL));
            assertEquals(
                    NoUndoGuard.Copy.FAILED,
                    FxTestSupport.callOnFx(() -> win.history().recordSafetyCopy(buffer, "copy")));
            FxTestSupport.runOnFx(win.history()::putLabel);
            settle(async, win);
            assertEquals(List.of(), revisions(win, file));
            assertEquals(
                    tr("status.history.partialFile", buffer.getTitle()), win.w().status());
        }
    }

    // --- R5 ---------------------------------------------------------------------------------------------

    @Test
    void whatASaveReplacesIsKeptWhateverItsSize(@TempDir Path temp) throws Exception {
        Path file = temp.toRealPath().resolve("large.txt");
        byte[] replaced = ("x".repeat(99) + "\n").repeat(60_000).getBytes(StandardCharsets.UTF_8); // 6 MB
        assertTrue(replaced.length > HistoryCoordinator.MAX_CAPTURE_BYTES);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            win.history().saveReplaced(file, replaced, "new\n".getBytes(StandardCharsets.UTF_8)); // the save worker
            awaitRevisions(async, win, file, 1);
            HistoryRevision baseline = revisions(win, file).get(0);
            assertEquals(HistoryCoordinator.REASON_BASELINE, baseline.reason());
            assertEquals(replaced.length, baseline.sizeBytes());
        }
    }

    // --- R7 (B4) ----------------------------------------------------------------------------------------

    @Test
    void aRestoreOfAFileOpenInAnotherWindowGoesIntoThatBuffer(@TempDir Path temp) throws Exception {
        Path file = Files.writeString(temp.toRealPath().resolve("elsewhere.txt"), "on disk\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Window first = window(fx);
            Window second = secondWindow(first);
            EditorBuffer there = open(async, second, file);
            FxTestSupport.runOnFx(() -> there.replaceWholeDocument("unsaved there\n"));
            record(async, first, file, "as recorded\n", HistoryRevision.REASON_SAVE, 1);
            HistoryRevision recorded = revisions(first, file).get(0);
            List<Path> asked = new ArrayList<>();
            FxTestSupport.runOnFx(() -> first.history().confirmUnsavedRestore = path -> asked.add(path));

            assertEquals(
                    HistoryCoordinator.RestoreResult.RESTORED,
                    async.await(FxTestSupport.callOnFx(() -> first.history().restoreRevisionToDisk(recorded))));
            assertEquals(List.of(file), asked, "its unsaved edits were asked about");
            assertEquals("as recorded\n", FxTestSupport.callOnFx(there::getContent));
            assertTrue(FxTestSupport.callOnFx(() -> there.getArea().isUndoAvailable()), "one undo away");
            assertEquals("on disk\n", Files.readString(file), "the disk under it is not written");
        }
    }

    // --- R8, R9 (B10) -----------------------------------------------------------------------------------

    @Test
    void deletingTheProjectsHistoryClearsItsFilesInEveryBucket(@TempDir Path temp) throws Exception {
        Path file = Files.writeString(temp.toRealPath().resolve("p.txt"), "text\n");
        String key = PathKeys.normalizedKey(file);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            record(async, win, file, "here\n", HistoryRevision.REASON_SAVE, 1);
            HistoryRevision elsewhere = new HistoryRevision(
                    key, System.currentTimeMillis() - 60_000, 5, "sha-elsewhere", HistoryRevision.REASON_SAVE);
            FxTestSupport.runOnFx(
                    () -> win.w().fx.shared.historyBucket("another-project").put(key, List.of(elsewhere)));
            List<String> asked = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                win.history().confirmPurgeAnswer = question -> asked.add(question);
                win.history().purgeProject();
            });
            assertEquals(List.of(tr("dialog.history.purgeProject.confirm", 2, 1)), asked, "counted the same way");
            assertEquals(List.of(), FxTestSupport.callOnFx(() -> revisions(win, file)));
        }
    }

    @Test
    void aSharedSnapshotCanBeRemovedFromEveryFile(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path original = dir.resolve("original.txt");
        Path copy = dir.resolve("copy.txt");
        Path other = dir.resolve("other.txt");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            record(async, win, original, "secret\n", HistoryRevision.REASON_SAVE, 1);
            record(async, win, copy, "secret\n", HistoryRevision.REASON_SAVE, 1);
            record(async, win, copy, "secret removed\n", HistoryRevision.REASON_SAVE, 2);
            record(async, win, other, "unrelated\n", HistoryRevision.REASON_SAVE, 1);
            List<String> asked = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                win.history().sharedPurgeAnswer = question -> {
                    asked.add(question);
                    return 1;
                };
                win.history().purgeFile(PathKeys.normalizedKey(original));
            });
            assertEquals(1, asked.size());
            assertEquals(List.of(), FxTestSupport.callOnFx(() -> revisions(win, original)));
            List<HistoryRevision> kept = FxTestSupport.callOnFx(() -> revisions(win, copy));
            assertEquals(1, kept.size(), "the shared snapshot left the copy's history too");
            assertEquals("secret removed\n", body(win, kept.get(0)));
            assertEquals(1, FxTestSupport.callOnFx(() -> revisions(win, other)).size(), "nothing else");
        }
    }

    // --- R13, F3 ----------------------------------------------------------------------------------------

    @Test
    void whatADiskRestoreReplacesIsRecordedAsSuchAlsoForUtf16WithoutAMark(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Files.writeString(dir.resolve(".editorconfig"), "root = true\n\n[*.txt]\ncharset = utf-16le\n");
        Path file = dir.resolve("wide.txt");
        Files.write(file, "now\n".getBytes(StandardCharsets.UTF_16LE));
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            record(async, win, file, "then\n", HistoryRevision.REASON_SAVE, 1);
            HistoryRevision then = revisions(win, file).get(0);
            Confirm.answerWith(question -> true);
            try {
                assertEquals(
                        HistoryCoordinator.RestoreResult.RESTORED,
                        async.await(FxTestSupport.callOnFx(() -> win.history().restoreRevisionToDisk(then))));
            } finally {
                Confirm.answerWith(null);
            }
            settle(async, win);
            HistoryRevision before = revisions(win, file).stream()
                    .filter(r -> HistoryRevision.REASON_RESTORE.equals(r.reason()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("now\n", body(win, before), "a BOM-less UTF-16 file is text, kept before the restore");
            assertEquals(
                    java.util.Arrays.toString("then\n".getBytes(StandardCharsets.UTF_16LE)),
                    java.util.Arrays.toString(Files.readAllBytes(file)),
                    "and the restored text is written as the file was: UTF-16, no mark");
        }
    }

    @Test
    void eachCopyIsFiledUnderWhatMadeIt(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path linked = Files.writeString(dir.resolve("linked.md"), "[a](old.md)\n");
        Path redirected = Files.writeString(dir.resolve("out.json"), "{}\n");
        Path reverted = Files.writeString(dir.resolve("reverted.txt"), "saved\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            CompletableFuture<Boolean> link = new CompletableFuture<>();
            FxTestSupport.runOnFx(() -> win.history()
                    .recordBeforeReplace(linked, "[a](old.md)\n", HistoryRevision.REASON_LINK_UPDATE, link::complete));
            assertTrue(async.await(link));
            assertEquals(
                    HistoryRevision.REASON_LINK_UPDATE,
                    revisions(win, linked).get(0).reason());

            CompletableFuture<HistoryCoordinator.OverwriteCapture> http = new CompletableFuture<>();
            FxTestSupport.runOnFx(() -> win.history()
                    .captureBeforeOverwriteDurably(redirected, HistoryRevision.REASON_HTTP, http::complete));
            assertTrue(async.await(http).recorded());
            assertEquals(
                    HistoryRevision.REASON_HTTP,
                    revisions(win, redirected).get(0).reason());

            EditorBuffer buffer = open(async, win, reverted);
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("never saved\n"));
            FileWorkflowCoordinator workflows = FxTestSupport.field(win.controller(), "fileWorkflows");
            FxTestSupport.runOnFx(() -> {
                workflows.confirmRevert = b -> true;
                workflows.revert();
            });
            OverlayTestKit.await(async, "the revert", () -> !buffer.isDirty());
            awaitRevisions(async, win, reverted, 1);
            HistoryRevision before = revisions(win, reverted).get(0);
            assertEquals(HistoryRevision.REASON_REVERT, before.reason());
            assertEquals("never saved\n", body(win, before));
        }
    }

    // --- R14 --------------------------------------------------------------------------------------------

    @Test
    void aSavesRowSaysHowTheFileWasWritten(@TempDir Path temp) throws Exception {
        Path file = temp.toRealPath().resolve("crlf.txt");
        Files.write(file, "one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8));
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            EditorBuffer buffer = open(async, win, file);
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("one\ntwo\nthree\n"));
            FileWorkflowCoordinator workflows = FxTestSupport.field(win.controller(), "fileWorkflows");
            assertTrue(FxTestSupport.callOnFx(() -> workflows.save(buffer)));
            OverlayTestKit.await(async, "the save", () -> !buffer.isDirty());
            OverlayTestKit.await(
                    async,
                    "its row",
                    () -> revisions(win, file).stream().anyMatch(r -> HistoryRevision.REASON_SAVE.equals(r.reason())));
            HistoryRevision saved = revisions(win, file).stream()
                    .filter(r -> HistoryRevision.REASON_SAVE.equals(r.reason()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(saved.hasEncoding());
            assertEquals(LineEndings.CRLF, saved.lineEnding());
            assertFalse(saved.bom());
        }
    }

    // --- R15 --------------------------------------------------------------------------------------------

    @Test
    void saveAsCarriesTheHistoryFromEveryBucket(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path origin = Files.writeString(dir.resolve("origin.txt"), "origin\n");
        Path target = dir.resolve("target.txt");
        String key = PathKeys.normalizedKey(origin);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            HistoryRevision elsewhere = new HistoryRevision(
                    key, System.currentTimeMillis() - 60_000, 5, "sha-elsewhere", HistoryRevision.REASON_SAVE);
            FxTestSupport.runOnFx(
                    () -> win.w().fx.shared.historyBucket("another-project").put(key, List.of(elsewhere)));
            EditorBuffer buffer = open(async, win, origin);
            FxTestSupport.runOnFx(() -> {
                buffer.setPath(target);
                win.history().bufferPathChanged(buffer, origin);
                win.history().record(target, "copied\n", HistoryRevision.REASON_SAVE);
            });
            awaitRevisions(async, win, target, 2);
            assertTrue(revisions(win, target).stream().anyMatch(r -> "sha-elsewhere".equals(r.sha256())));
            FxTestSupport.runOnFx(() -> buffer.setPath(origin));
        }
    }

    // --- U4 ---------------------------------------------------------------------------------------------

    @Test
    void theWindowComesBackWithTheNextFile(@TempDir Path temp) throws Exception {
        Path file = Files.writeString(temp.toRealPath().resolve("back.txt"), "one\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            open(async, win, file);
            ToolWindowManager toolWindows = FxTestSupport.field(win.controller(), "toolWindows");
            ToolWindow tw = FxTestSupport.field(win.controller(), "fileHistoryToolWindow");
            FxTestSupport.runOnFx(win.history()::showActive);
            assertTrue(FxTestSupport.callOnFx(() -> toolWindows.isOpen(tw)));

            FxTestSupport.runOnFx(win.controller()::onNew); // an untitled tab: no local file
            OverlayTestKit.await(async, "the untitled tab", () -> {
                EditorBuffer active = active(win);
                return active != null && active.getPath() == null;
            });
            async.awaitFx();
            assertFalse(FxTestSupport.callOnFx(() -> toolWindows.isOpen(tw)));

            FxTestSupport.runOnFx(() -> {
                Tab tab = win.controller().tabForPath(file);
                EditorArea area = FxTestSupport.field(win.controller(), "editorArea");
                area.select(tab);
            });
            OverlayTestKit.await(
                    async, "the file again", () -> file.equals(active(win).getPath()));
            async.awaitFx();
            assertTrue(FxTestSupport.callOnFx(() -> toolWindows.isOpen(tw)), "back with the file");

            // Closed by the user, it stays closed.
            FxTestSupport.runOnFx(() -> toolWindows.close(tw));
            FxTestSupport.runOnFx(win.controller()::onNew);
            async.awaitFx();
            FxTestSupport.runOnFx(() -> {
                EditorArea area = FxTestSupport.field(win.controller(), "editorArea");
                area.select(win.controller().tabForPath(file));
            });
            async.awaitFx();
            assertFalse(FxTestSupport.callOnFx(() -> toolWindows.isOpen(tw)));
        }
    }

    // --- U3 ---------------------------------------------------------------------------------------------

    @Test
    void recentChangesOnADeletedFileSelectsThatRevisionInItsFolder(@TempDir Path temp) throws Exception {
        Path folder = Files.createDirectory(temp.toRealPath().resolve("pkg"));
        Path gone = folder.resolve("gone.txt");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            record(async, win, gone, "first\n", HistoryRevision.REASON_SAVE, 1);
            record(async, win, gone, "last\n", HistoryRevision.REASON_DELETE, 2);
            HistoryRevision picked = revisions(win, gone).get(1); // not the newest: the row that was picked
            FileHistoryPanel panel = FxTestSupport.callOnFx(win.history()::panel);
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.invokeWith(win.history(), "openRecentChange", HistoryRevision.class, picked));
            OverlayTestKit.await(async, "the folder's listing", () -> folder.equals(panel.folderShown()));
            javafx.scene.control.TreeView<Object> tree = FxTestSupport.field(panel, "folderTree");
            OverlayTestKit.await(async, "the picked row selected", () -> {
                var selected = tree.getSelectionModel().getSelectedItem();
                return selected != null
                        && selected.getValue() instanceof HistoryRevision r
                        && r.timestamp() == picked.timestamp()
                        && r.sha256().equals(picked.sha256());
            });
        }
    }
}
