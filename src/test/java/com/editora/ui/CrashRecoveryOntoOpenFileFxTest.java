package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import javafx.animation.AnimationTimer;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Tab;
import javafx.stage.Window;

import com.editora.editor.EditorBuffer;
import com.editora.recovery.RecoveryService;
import com.editora.recovery.RecoveryStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recovered text for a file that is already open again when the user asks for it back: it goes on top of a
 * clean buffer as one edit, beside a buffer that has unsaved changes of its own, and nowhere new when the
 * buffer already holds exactly that text. And the Discard question, answered both ways through its dialog.
 */
@Tag("fx")
class CrashRecoveryOntoOpenFileFxTest {

    private static final String DISK = "on disk\n";
    private static final String TYPED = "typed and never saved\non disk\n";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A second launch after a crash that left an unsaved edit of {@code file} behind, offer on screen. */
    private static final class AfterCrash {
        final AsyncTestScope async;
        final FxWindowFixture window;
        final RecoveryCoordinator recovery;
        final RecoveryService service;
        final Path file;

        AfterCrash(AsyncTestScope async, Path configDir, Path file) throws Exception {
            this.async = async;
            this.file = file;
            FxWindowFixture first = launch(async, configDir);
            EditorBuffer doc = open(async, first, file);
            FxTestSupport.runOnFx(() -> doc.getArea().insertText(0, "typed and never saved\n"));
            RecoveryService firstService = first.windowManager.recovery();
            SaveGuardsFxTest.awaitOnFx(
                    async,
                    "a recovery copy of the unsaved buffer",
                    () -> firstService.store().ownRecordIds().size() == 1);
            assertTrue(firstService.awaitIdle(10_000));
            // The process dies: nothing gets to clean up. The window is torn down only to leak no threads.
            firstService.abandon();
            first.keepConfigDir = true;
            first.dispose();

            window = launch(async, configDir);
            recovery = FxTestSupport.field(window.controller, "recovery");
            service = window.windowManager.recovery();
            SaveGuardsFxTest.awaitOnFx(
                    async,
                    "the offer to restore",
                    () -> recovery.offerForTest() != null
                            && recovery.offerForTest().isShowing());
        }

        List<RecoveryStore.Entry> rows() throws Exception {
            return FxTestSupport.callOnFx(
                    () -> List.copyOf(recovery.offerForTest().listForTest().getItems()));
        }

        List<EditorBuffer> buffers() {
            EditorArea area = FxTestSupport.field(window.controller, "editorArea");
            return area.tabs().stream()
                    .map(Tab::getUserData)
                    .filter(EditorBuffer.class::isInstance)
                    .map(EditorBuffer.class::cast)
                    .toList();
        }

        void restoreAll() throws Exception {
            FxTestSupport.runOnFx(
                    () -> recovery.offerForTest().restoreAllForTest().fire());
            SaveGuardsFxTest.awaitOnFx(
                    async,
                    "the record to be resolved",
                    () -> service.unresolved().isEmpty());
        }

        String status() throws Exception {
            return SaveDecisionsFxTest.lastMessage(window);
        }
    }

    private static FxWindowFixture launch(AsyncTestScope async, Path configDir) throws Exception {
        return async.own(FxWindowFixture.create(configDir, false, false, false, List.of(), true, c -> {}));
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

    @Test
    void recoveredTextGoesOnTopOfACleanOpenBufferAsOneUndoableEdit(@TempDir Path configDir, @TempDir Path work)
            throws Exception {
        Path file = Files.writeString(work.resolve("doc.txt"), DISK);
        try (AsyncTestScope async = new AsyncTestScope()) {
            AfterCrash c = new AfterCrash(async, configDir, file);
            EditorBuffer reopened = open(async, c.window, file);
            assertEquals(DISK, FxTestSupport.callOnFx(reopened::getContent));

            c.restoreAll();

            assertEquals(TYPED, FxTestSupport.callOnFx(reopened::getContent));
            assertTrue(FxTestSupport.callOnFx(reopened::isDirty), "recovered text is unsaved");
            assertEquals(1, FxTestSupport.callOnFx(() -> c.buffers().size()), "no second tab for the same file");
            assertEquals(tr("status.recovery.restored", "doc.txt"), c.status());
            assertEquals(DISK, Files.readString(file), "nothing is written to the user's file");

            FxTestSupport.runOnFx(() -> reopened.getArea().undo());
            assertEquals(DISK, FxTestSupport.callOnFx(reopened::getContent), "one undo takes the whole restore back");
        }
    }

    @Test
    void recoveredTextNeverOverwritesUnsavedChangesItOpensBesideThem(@TempDir Path configDir, @TempDir Path work)
            throws Exception {
        Path file = Files.writeString(work.resolve("doc.txt"), DISK);
        try (AsyncTestScope async = new AsyncTestScope()) {
            AfterCrash c = new AfterCrash(async, configDir, file);
            EditorBuffer reopened = open(async, c.window, file);
            FxTestSupport.runOnFx(() -> reopened.getArea().appendText("today's edits\n"));

            c.restoreAll();

            assertEquals(DISK + "today's edits\n", FxTestSupport.callOnFx(reopened::getContent));
            List<EditorBuffer> buffers = FxTestSupport.callOnFx(c::buffers);
            assertEquals(2, buffers.size(), "the recovered text has its own tab");
            EditorBuffer beside =
                    buffers.stream().filter(b -> b != reopened).findFirst().orElseThrow();
            assertEquals(TYPED, FxTestSupport.callOnFx(beside::getContent));
            assertEquals(null, FxTestSupport.callOnFx(beside::getPath), "untitled: it is not the file's buffer");
            assertEquals(tr("status.recovery.restoredBeside", "doc.txt"), c.status());
        }
    }

    @Test
    void aBufferThatAlreadyHoldsTheRecoveredTextIsLeftAsItIs(@TempDir Path configDir, @TempDir Path work)
            throws Exception {
        Path file = Files.writeString(work.resolve("doc.txt"), DISK);
        try (AsyncTestScope async = new AsyncTestScope()) {
            AfterCrash c = new AfterCrash(async, configDir, file);
            EditorBuffer reopened = open(async, c.window, file);
            FxTestSupport.runOnFx(() -> reopened.getArea().insertText(0, "typed and never saved\n"));

            c.restoreAll();

            assertEquals(TYPED, FxTestSupport.callOnFx(reopened::getContent));
            assertEquals(1, FxTestSupport.callOnFx(() -> c.buffers().size()), "nothing new was opened");
            assertEquals(tr("status.recovery.restored", "doc.txt"), c.status());
        }
    }

    @Test
    void discardAsksFirstAndOnlyDiscardDeletesTheRecord(@TempDir Path configDir, @TempDir Path work) throws Exception {
        Path file = Files.writeString(work.resolve("doc.txt"), DISK);
        try (AsyncTestScope async = new AsyncTestScope()) {
            AfterCrash c = new AfterCrash(async, configDir, file);
            List<RecoveryStore.Entry> rows = c.rows();
            assertEquals(1, rows.size());
            Path record = rows.get(0).file();
            String question = tr("recovery.discard.confirm", 1);

            CountDownLatch kept = answer(async, question, tr("dialog.cancel"));
            FxTestSupport.runOnFx(() -> c.recovery.discard(rows));
            async.await(kept, "the discard question");
            assertTrue(Files.exists(record), "Cancel keeps the record");
            assertEquals(1, FxTestSupport.callOnFx(() -> c.service.unresolved().size()));

            FxTestSupport.runOnFx(() -> c.recovery.discard(List.of())); // nothing chosen: nothing asked

            CountDownLatch discarded = answer(async, question, tr("recovery.discard.button"));
            FxTestSupport.runOnFx(() -> c.recovery.discard(rows));
            async.await(discarded, "the discard question");
            SaveGuardsFxTest.awaitOnFx(async, "the record to be deleted", () -> !Files.exists(record));
            assertEquals(tr("status.recovery.discarded", 1), c.status());
            assertTrue(FxTestSupport.callOnFx(() -> c.service.unresolved().isEmpty()));
            assertFalse(Files.exists(record));
            assertEquals(DISK, Files.readString(file));
        }
    }

    /** Presses {@code button} on the next dialog whose message is {@code content}. */
    private static CountDownLatch answer(AsyncTestScope async, String content, String button) throws Exception {
        CountDownLatch pressed = new CountDownLatch(1);
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (pressed.getCount() == 0
                            || window.getScene() == null
                            || !(window.getScene().getRoot() instanceof DialogPane pane)
                            || !content.equals(pane.getContentText())) {
                        continue;
                    }
                    pane.getButtonTypes().stream()
                            .filter(type -> button.equals(type.getText()))
                            .findFirst()
                            .ifPresent(type -> {
                                pressed.countDown();
                                stop();
                                ((Button) pane.lookupButton(type)).fire();
                            });
                }
            }
        };
        FxTestSupport.runOnFx(timer::start);
        async.onClose(() -> FxTestSupport.runOnFx(timer::stop));
        return pressed;
    }
}
