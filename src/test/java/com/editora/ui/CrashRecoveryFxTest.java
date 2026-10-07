package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import javafx.scene.control.Tab;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import com.editora.editor.EditorBuffer;
import com.editora.recovery.RecoveryCodec;
import com.editora.recovery.RecoveryRecord;
import com.editora.recovery.RecoveryService;
import com.editora.recovery.RecoveryStore;
import com.editora.vfs.EmbeddedSftpFixture;
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
 * L2 — unsaved edits survive the process dying. A real window against a real config directory: buffers are
 * made dirty, the process "dies" (its recovery locks are dropped and the window is torn down without the
 * close path, exactly what a kill leaves on disk), and a second launch on the same directory offers the text
 * back. Restoring must produce unsaved buffers and must not touch the user's files; a normal close must
 * leave nothing behind.
 */
@Tag("fx")
class CrashRecoveryFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // --- the end-to-end path ---

    @Test
    void unsavedEditsAreOfferedBackAfterAnAbnormalEndAndRestoreAsUnsavedBuffers(
            @TempDir Path configDir, @TempDir Path work) throws Exception {
        Path file = Files.writeString(work.resolve("doc.txt"), "on disk\r\nsecond line\r\n");
        byte[] diskBytes = Files.readAllBytes(file);
        Path changed = Files.writeString(work.resolve("changed.txt"), "before\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture first = launch(async, configDir);
            EditorBuffer doc = open(async, first, file);
            EditorBuffer other = open(async, first, changed);
            FxTestSupport.runOnFx(() -> {
                doc.getArea().insertText(0, "typed and never saved\n");
                other.getArea().appendText("edited\n");
            });
            EditorBuffer untitled = newUntitled(first, "a note that has no file yet");
            assertTrue(FxTestSupport.callOnFx(() -> doc.isDirty() && other.isDirty() && untitled.isDirty()));

            // The production timer takes the copies: nothing in this test asks for one.
            RecoveryService service = first.windowManager.recovery();
            assertTrue(waitFor(() -> service.store().ownRecordIds().size() == 3), "a copy of each unsaved buffer");
            assertTrue(service.awaitIdle(10_000));
            Path sessionDir = service.store().sessionDir();
            RecoveryRecord stored = records(sessionDir).stream()
                    .filter(r -> file.toString().equals(r.path()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("typed and never saved\non disk\nsecond line\n", stored.text());
            assertEquals("CRLF", stored.lineEnding(), "the line ending a save would have written");
            assertEquals(diskBytes.length, stored.baseSize(), "what the edits were based on");
            assertNotNull(stored.baseFingerprint());

            crash(first);
            assertEquals(3, records(sessionDir).size(), "an abnormal end leaves the copies on disk");
            // Another program rewrites one of the files while Editora is not running.
            Files.writeString(changed, "rewritten by something else\n");
            byte[] changedBytes = Files.readAllBytes(changed);

            FxWindowFixture second = launch(async, configDir);
            RecoveryCoordinator recovery = FxTestSupport.field(second.controller, "recovery");
            assertTrue(
                    waitFor(() -> recovery.offerForTest() != null
                            && recovery.offerForTest().isShowing()),
                    "the next launch offers the text back");
            RecoveryOfferWindow offer = FxTestSupport.callOnFx(recovery::offerForTest);
            List<RecoveryStore.Entry> rows =
                    FxTestSupport.callOnFx(() -> List.copyOf(offer.listForTest().getItems()));
            assertEquals(3, rows.size());
            RecoveryStore.Entry changedRow = row(rows, "changed.txt");
            assertEquals(RecoveryStore.DiskState.CHANGED, changedRow.disk());
            assertEquals(tr("recovery.item.diskChanged"), RecoveryOfferWindow.warning(changedRow));
            assertNull(RecoveryOfferWindow.warning(row(rows, "doc.txt")), "unchanged on disk: no warning");
            assertTrue(RecoveryOfferWindow.detail(row(rows, "untitled")).startsWith(tr("recovery.item.untitled")));
            assertTrue(FxTestSupport.callOnFx(() -> offer.restoreAllForTest().isDefaultButton()), "Enter restores");
            assertTrue(FxTestSupport.callOnFx(() -> offer.laterForTest().isCancelButton()), "Escape keeps everything");
            assertFalse(FxTestSupport.callOnFx(() -> offer.discardAllForTest().isDefaultButton()));
            assertEquals(0, bufferCount(second), "nothing is opened until the user says so");

            FxTestSupport.runOnFx(() -> offer.restoreAllForTest().fire());
            RecoveryService next = second.windowManager.recovery();
            assertTrue(waitFor(() -> next.unresolved().isEmpty() && bufferCount(second) == 3), "all three restored");

            EditorBuffer restored = bufferFor(second, file);
            assertEquals("typed and never saved\non disk\nsecond line\n", FxTestSupport.callOnFx(restored::getContent));
            assertTrue(FxTestSupport.callOnFx(restored::isDirty), "restored text is unsaved");
            assertEquals("CRLF", FxTestSupport.callOnFx(restored::getLineEnding));
            assertEquals(file, FxTestSupport.callOnFx(restored::getPath), "still bound to its file");
            assertEquals(
                    (long) diskBytes.length,
                    FxTestSupport.callOnFx(() -> restored.diskSnapshot().size()),
                    "with the disk state the edits were based on");
            assertTrue(
                    java.util.Arrays.equals(diskBytes, Files.readAllBytes(file)),
                    "restoring wrote nothing to the file");

            EditorBuffer restoredChanged = bufferFor(second, changed);
            assertEquals("before\nedited\n", FxTestSupport.callOnFx(restoredChanged::getContent));
            assertTrue(java.util.Arrays.equals(changedBytes, Files.readAllBytes(changed)), "nor to the changed one");
            assertTrue(
                    FxTestSupport.callOnFx(() -> restoredChanged.diskChangedFrom(
                            Files.getLastModifiedTime(changed).toMillis(), Files.size(changed))),
                    "the buffer still knows the file is not what it was edited against");

            EditorBuffer restoredUntitled = FxTestSupport.callOnFx(() -> buffers(second).stream()
                    .filter(b -> b.getPath() == null)
                    .findFirst()
                    .orElseThrow());
            assertEquals("a note that has no file yet", FxTestSupport.callOnFx(restoredUntitled::getContent));
            assertTrue(FxTestSupport.callOnFx(restoredUntitled::isDirty));

            // The copies now belong to the new session; the dead session's are gone only after that.
            assertTrue(waitFor(() -> !Files.exists(sessionDir)), "the old records were removed once replaced");
            assertEquals(3, next.store().ownRecordIds().size());
            assertFalse(FxTestSupport.callOnFx(offer::isShowing), "nothing left to decide");
        }
    }

    @Test
    void aSecondCrashBeforeTheUserDecidesLosesNothingAndDiscardIsExplicit(@TempDir Path configDir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture first = launch(async, configDir);
            newUntitled(first, "keep me");
            newUntitled(first, "throw me away");
            RecoveryService service = first.windowManager.recovery();
            assertTrue(waitFor(() -> service.store().ownRecordIds().size() == 2));
            Path sessionDir = service.store().sessionDir();
            crash(first);

            // Second launch: the offer is shown and left unanswered ("Decide Later"), then that launch dies too.
            FxWindowFixture second = launch(async, configDir);
            RecoveryCoordinator undecided = FxTestSupport.field(second.controller, "recovery");
            assertTrue(waitFor(() ->
                    undecided.offerForTest() != null && undecided.offerForTest().isShowing()));
            FxTestSupport.runOnFx(() -> undecided.offerForTest().laterForTest().fire());
            assertFalse(FxTestSupport.callOnFx(() -> undecided.offerForTest().isShowing()));
            assertEquals(
                    2,
                    FxTestSupport.callOnFx(
                            () -> second.windowManager.recovery().unresolved().size()));
            crash(second);
            assertEquals(2, records(sessionDir).size(), "closing the offer deleted nothing");

            // Third launch: discard one (only after confirming), restore the other.
            FxWindowFixture third = launch(async, configDir);
            RecoveryCoordinator recovery = FxTestSupport.field(third.controller, "recovery");
            assertTrue(waitFor(() ->
                    recovery.offerForTest() != null && recovery.offerForTest().isShowing()));
            RecoveryService next = third.windowManager.recovery();
            RecoveryStore.Entry doomed = FxTestSupport.callOnFx(() -> next.unresolved().stream()
                    .filter(e -> textOf(e).equals("throw me away"))
                    .findFirst()
                    .orElseThrow());
            int[] asked = {0};
            FxTestSupport.runOnFx(() -> {
                recovery.confirmDiscard = n -> {
                    asked[0]++;
                    return false; // the user backs out of the confirmation
                };
                recovery.discard(List.of(doomed));
            });
            assertEquals(1, asked[0]);
            assertTrue(Files.exists(doomed.file()), "declining the confirmation keeps the record");
            assertEquals(2, FxTestSupport.callOnFx(() -> next.unresolved().size()));

            FxTestSupport.runOnFx(() -> {
                recovery.confirmDiscard = n -> true;
                recovery.discard(List.of(doomed));
            });
            assertTrue(waitFor(() -> !Files.exists(doomed.file())), "deleted on the explicit choice");
            assertEquals(0, bufferCount(third));

            FxTestSupport.runOnFx(
                    () -> recovery.offerForTest().restoreAllForTest().fire());
            assertTrue(waitFor(() -> bufferCount(third) == 1));
            assertEquals(
                    "keep me",
                    FxTestSupport.callOnFx(() -> buffers(third).get(0).getContent()));
        }
    }

    // --- when a copy goes away ---

    @Test
    void savingRevertingAndClosingATabEachRemoveTheCopy(@TempDir Path configDir, @TempDir Path work) throws Exception {
        Path file = Files.writeString(work.resolve("saved.txt"), "one\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = launch(async, configDir);
            RecoveryService service = fx.windowManager.recovery();
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");

            EditorBuffer saved = open(async, fx, file);
            FxTestSupport.runOnFx(() -> saved.getArea().appendText("two\n"));
            EditorBuffer reverted = newUntitled(fx, "typed, then undone");
            EditorBuffer closed = newUntitled(fx, "discarded with its tab");
            assertTrue(waitFor(() -> service.store().ownRecordIds().size() == 3));

            assertTrue(FxTestSupport.callOnFx(() -> workflows.saveSynchronously(saved)));
            assertTrue(waitFor(() -> service.store().ownRecordIds().size() == 2), "saved: its copy is gone");
            assertEquals("one\ntwo\n", Files.readString(file));

            FxTestSupport.runOnFx(() -> reverted.getArea().replaceText(""));
            assertTrue(waitFor(() -> !reverted.isDirty()));
            assertTrue(waitFor(() -> service.store().ownRecordIds().size() == 1), "back to clean: its copy is gone");

            // What closing a tab does once the user has answered "discard".
            FxTestSupport.runOnFx(() -> area.remove(tabOf(fx, closed)));
            assertTrue(waitFor(() -> service.store().ownRecordIds().isEmpty()), "closed: its copy is gone");
        }
    }

    @Test
    void aNormalWindowCloseLeavesNoRecordBehind(@TempDir Path configDir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = launch(async, configDir);
            RecoveryService service = fx.windowManager.recovery();
            newUntitled(fx, "the user chose not to save this");
            assertTrue(waitFor(() -> service.store().ownRecordIds().size() == 1));
            Path recoveryDir = service.store().root();
            assertTrue(Files.isDirectory(recoveryDir));

            // The point both close paths reach once every unsaved buffer has been answered for (a window
            // close and an approved quit); the prompt itself is a modal dialog a headless test cannot answer.
            FxTestSupport.runOnFx(fx.controller::persistSessionForClose);
            assertTrue(waitFor(() -> service.store().ownRecordIds().isEmpty()));

            fx.keepConfigDir = true;
            fx.dispose(); // ends the process's recovery service the ordinary way
            assertFalse(Files.exists(recoveryDir), "no record, no session directory, no recovery directory");

            FxWindowFixture next = launch(async, configDir);
            RecoveryCoordinator recovery = FxTestSupport.field(next.controller, "recovery");
            assertTrue(next.windowManager.recovery().orphans().get().entries().isEmpty());
            async.awaitFx();
            assertNull(FxTestSupport.callOnFx(recovery::offerForTest), "nothing is offered after a normal close");
        }
    }

    /** The real close request, on a window whose buffers are all clean, also ends with nothing on disk. */
    @Test
    void theCloseRequestOfAWindowWithNothingUnsavedLeavesNothing(@TempDir Path configDir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = launch(async, configDir);
            RecoveryService service = fx.windowManager.recovery();
            EditorBuffer buffer = newUntitled(fx, "typed");
            assertTrue(waitFor(() -> service.store().ownRecordIds().size() == 1));
            FxTestSupport.runOnFx(() -> buffer.getArea().replaceText(""));
            assertTrue(waitFor(() -> !buffer.isDirty()));
            Stage stage = FxTestSupport.field(fx.controller, "stage");
            FxTestSupport.runOnFx(
                    () -> stage.getOnCloseRequest().handle(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)));
            assertTrue(service.awaitIdle(10_000));
            assertTrue(service.store().ownRecordIds().isEmpty());
        }
    }

    // --- every window, and only user documents ---

    @Test
    void everyWindowOfTheProcessIsCoveredAndOnlyTheFirstOffers(@TempDir Path configDir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture first = launch(async, configDir);
            MainController secondWindow = FxTestSupport.callOnFx(() -> first.windowManager.buildWindowForTest());
            newUntitled(first.controller, "from window one");
            newUntitled(secondWindow, "from window two");
            RecoveryService service = first.windowManager.recovery();
            assertTrue(waitFor(() -> service.store().ownRecordIds().size() == 2), "one copy per window's buffer");
            crash(first);

            FxWindowFixture next = launch(async, configDir);
            MainController nextSecond = FxTestSupport.callOnFx(() -> next.windowManager.buildWindowForTest());
            RecoveryCoordinator offering = FxTestSupport.field(next.controller, "recovery");
            RecoveryCoordinator silent = FxTestSupport.field(nextSecond, "recovery");
            assertTrue(waitFor(() ->
                    offering.offerForTest() != null && offering.offerForTest().isShowing()));
            assertEquals(
                    2,
                    FxTestSupport.callOnFx(() ->
                            offering.offerForTest().listForTest().getItems().size()));
            assertNull(FxTestSupport.callOnFx(silent::offerForTest), "one offer per launch, not one per window");
        }
    }

    @Test
    void cleanBuffersAndATurnedOffSettingLeaveNoCopy(@TempDir Path configDir, @TempDir Path work) throws Exception {
        Path file = Files.writeString(work.resolve("clean.txt"), "untouched\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = launch(async, configDir);
            RecoveryService service = fx.windowManager.recovery();
            RecoveryCoordinator recovery = FxTestSupport.field(fx.controller, "recovery");
            open(async, fx, file);
            FxTestSupport.runOnFx(() -> recovery.tick(System.nanoTime()));
            assertTrue(service.awaitIdle(10_000));
            assertFalse(Files.exists(service.store().root()), "a clean buffer creates nothing at all");

            EditorBuffer dirty = newUntitled(fx, "unsaved");
            assertTrue(waitFor(() -> service.store().ownRecordIds().size() == 1));

            FxTestSupport.runOnFx(
                    () -> FxTestSupport.<com.editora.command.CommandRegistry>field(fx.controller, "registry")
                            .run("view.toggleCrashRecovery"));
            assertFalse(fx.shared.getSettings().isCrashRecovery());
            assertTrue(waitFor(() -> service.store().ownRecordIds().isEmpty()), "turning it off removes the copies");
            FxTestSupport.runOnFx(() -> {
                dirty.getArea().appendText(" more");
                recovery.tick(System.nanoTime());
            });
            assertTrue(service.awaitIdle(10_000));
            assertTrue(service.store().ownRecordIds().isEmpty(), "and none is taken while it is off");

            FxTestSupport.runOnFx(
                    () -> FxTestSupport.<com.editora.command.CommandRegistry>field(fx.controller, "registry")
                            .run("view.toggleCrashRecovery"));
            assertTrue(waitFor(() -> service.store().ownRecordIds().size() == 1), "back on: copied at once");
        }
    }

    // --- remote files ---

    @Test
    void aRemoteBuffersTextIsKeptLocallyAndComesBackEvenWithoutTheConnection(
            @TempDir Path configDir, @TempDir Path server) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(server));
            Files.writeString(sftp.serverPath("remote.txt"), "on the server\n");
            byte[] serverBytes = Files.readAllBytes(sftp.serverPath("remote.txt"));
            FxWindowFixture first = launch(async, configDir);
            EditorBuffer remote = open(async, first, sftp.remotePath("remote.txt"));
            FxTestSupport.runOnFx(() -> remote.getArea().appendText("typed over ssh\n"));
            RecoveryService service = first.windowManager.recovery();
            assertTrue(waitFor(() -> service.store().ownRecordIds().size() == 1));
            assertTrue(service.awaitIdle(10_000));
            RecoveryRecord stored = records(service.store().sessionDir()).get(0);
            assertTrue(stored.path().startsWith("sftp://"), stored.path());
            assertEquals("on the server\ntyped over ssh\n", stored.text());
            crash(first);
            sftp.disconnect(); // the next launch has no connection to that host

            FxWindowFixture second = launch(async, configDir);
            RecoveryCoordinator recovery = FxTestSupport.field(second.controller, "recovery");
            assertTrue(waitFor(() ->
                    recovery.offerForTest() != null && recovery.offerForTest().isShowing()));
            RecoveryStore.Entry row = FxTestSupport.callOnFx(
                    () -> recovery.offerForTest().listForTest().getItems().get(0));
            assertEquals(RecoveryStore.DiskState.REMOTE, row.disk());
            FxTestSupport.runOnFx(
                    () -> recovery.offerForTest().restoreAllForTest().fire());
            assertTrue(waitFor(() -> bufferCount(second) == 1));
            EditorBuffer restored = FxTestSupport.callOnFx(() -> buffers(second).get(0));
            assertEquals("on the server\ntyped over ssh\n", FxTestSupport.callOnFx(restored::getContent));
            assertTrue(FxTestSupport.callOnFx(restored::isDirty));
            assertEquals("remote.txt", FxTestSupport.callOnFx(restored::getTitle));
            assertTrue(
                    java.util.Arrays.equals(serverBytes, Files.readAllBytes(sftp.serverPath("remote.txt"))),
                    "the server's file is untouched");
        }
    }

    // --- helpers ---

    private static FxWindowFixture launch(AsyncTestScope async, Path configDir) throws Exception {
        return async.own(FxWindowFixture.create(configDir, false, false, false, List.of(), true, c -> {}));
    }

    /**
     * The process dies: the operating system drops its locks and nothing gets to clean up. The window is then
     * torn down without the close path — no prompt, no session persist — only so the test leaks no threads.
     */
    private static void crash(FxWindowFixture fx) throws Exception {
        fx.windowManager.recovery().abandon();
        fx.keepConfigDir = true;
        fx.dispose();
    }

    private static EditorBuffer open(AsyncTestScope async, FxWindowFixture fx, Path file) throws Exception {
        FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
        CountDownLatch loaded = new CountDownLatch(1);
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            workflows.openPath(file);
            EditorBuffer opening = (EditorBuffer) area.selectedTab().getUserData();
            workflows.afterBufferLoad(opening, loaded::countDown);
            return opening;
        });
        async.await(loaded, "document load");
        async.awaitFx();
        return buffer;
    }

    private static EditorBuffer newUntitled(FxWindowFixture fx, String text) throws Exception {
        return newUntitled(fx.controller, text);
    }

    private static EditorBuffer newUntitled(MainController controller, String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            FxTestSupport.invoke(controller, "onNew");
            EditorArea area = FxTestSupport.field(controller, "editorArea");
            EditorBuffer buffer = (EditorBuffer) area.selectedTab().getUserData();
            buffer.getArea().replaceText(text);
            return buffer;
        });
    }

    private static List<EditorBuffer> buffers(FxWindowFixture fx) {
        EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
        return area.tabs().stream()
                .map(Tab::getUserData)
                .filter(EditorBuffer.class::isInstance)
                .map(EditorBuffer.class::cast)
                .toList();
    }

    private static int bufferCount(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> buffers(fx).size());
    }

    private static EditorBuffer bufferFor(FxWindowFixture fx, Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> buffers(fx).stream()
                .filter(b -> file.equals(b.getPath()))
                .findFirst()
                .orElseThrow());
    }

    private static Tab tabOf(FxWindowFixture fx, EditorBuffer buffer) {
        EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
        return area.tabs().stream()
                .filter(t -> t.getUserData() == buffer)
                .findFirst()
                .orElseThrow();
    }

    private static RecoveryStore.Entry row(List<RecoveryStore.Entry> rows, String title) {
        return rows.stream()
                .filter(e -> title.equals(e.record().title()))
                .findFirst()
                .orElseThrow();
    }

    private static String textOf(RecoveryStore.Entry entry) {
        try {
            return RecoveryCodec.decode(Files.readAllBytes(entry.file()), true).text();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static List<RecoveryRecord> records(Path sessionDir) throws Exception {
        try (Stream<Path> files = Files.list(sessionDir)) {
            return files.filter(f -> f.toString().endsWith(".rec"))
                    .sorted()
                    .map(f -> {
                        try {
                            return RecoveryCodec.decode(Files.readAllBytes(f), true);
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .toList();
        }
    }

    /** Polls a real condition on the FX thread: the copies are taken by a timer and written off-thread. */
    private static boolean waitFor(Callable<Boolean> onFx) throws Exception {
        long end = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < end) {
            if (Boolean.TRUE.equals(FxTestSupport.callOnFx(onFx))) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
