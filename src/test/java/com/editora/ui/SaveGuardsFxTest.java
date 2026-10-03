package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javafx.animation.AnimationTimer;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.stage.Window;

import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import com.editora.io.AtomicFileWrite;
import org.junit.jupiter.api.Assumptions;
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
 * The save path's refusals and bounds, each driven through the real coordinator: a buffer that is not the
 * document is never written, a write that cannot be committed ends as a visible failure, and neither the
 * auto-save nor the elevated save quietly diverges from what an ordinary save would have done.
 */
@Tag("fx")
class SaveGuardsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // --- a loading shell is not the document ---------------------------------------------------------

    @Test
    void savingATabThatIsStillLoadingDoesNotEmptyItsFile(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("slow.txt"), "the real content\n");
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer shell = FxTestSupport.callOnFx(() -> {
                // Exactly what openTextBufferAsync leaves on screen while the disk read is in flight.
                EditorBuffer buffer = new EditorBuffer();
                buffer.setPath(file);
                buffer.setHeavyFile(true);
                buffer.setLoading(true);
                workflows.loadingBuffers.add(buffer);
                addBuffer(fx, buffer);
                return buffer;
            });

            // The reflex: Ctrl/Cmd-S. No edit and no dirty flag are needed for it to fire.
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            FxTestSupport.runOnFx(() -> registry.run("file.save"));
            settle(async, workflows);
            assertEquals("the real content\n", Files.readString(file));
            assertEquals(tr("status.loadingNoSave", "slow.txt"), echo(fx));

            // Every other entry point funnels into the same guard.
            assertFalse(FxTestSupport.callOnFx(() -> workflows.saveSynchronously(shell)));
            assertFalse(FxTestSupport.callOnFx(() -> workflows.writeBuffer(shell, file)));
            assertFalse(FxTestSupport.callOnFx(() -> workflows.applySaveAsTarget(shell, dir.resolve("copy.txt"))));
            FxTestSupport.runOnFx(() -> workflows.autoSaveBuffer(shell));
            settle(async, workflows);

            assertEquals("the real content\n", Files.readString(file));
            assertFalse(Files.exists(dir.resolve("copy.txt")), "an empty shell must not be saved elsewhere either");
            assertEquals(file, FxTestSupport.callOnFx(shell::getPath), "and a refused Save As does not rename it");
            assertFalse(FxTestSupport.callOnFx(() -> workflows.hasPendingSave(shell)));
        }
    }

    @Test
    void aShellWhoseLoadFailedIsStillNotTheDocument(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("failed-load.txt"), "still here\n");
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer shell = FxTestSupport.callOnFx(() -> {
                EditorBuffer buffer = new EditorBuffer();
                buffer.setPath(file);
                buffer.setLoading(true); // no longer tracked as loading, but its text never arrived
                addBuffer(fx, buffer);
                return buffer;
            });

            assertFalse(FxTestSupport.callOnFx(() -> workflows.writeBuffer(shell, file)));
            settle(async, workflows);

            assertEquals("still here\n", Files.readString(file));
        }
    }

    @Test
    void aClosedBufferIsNeverWritten(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("closed.txt"), "on disk\n");
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer buffer = open(fx, file, "on disk\n");

            boolean written = FxTestSupport.callOnFx(() -> {
                buffer.replaceWholeDocument("a late write from a dead tab");
                buffer.dispose();
                return workflows.writeBuffer(buffer, file);
            });
            settle(async, workflows);

            assertFalse(written);
            assertEquals("on disk\n", Files.readString(file));
        }
    }

    // --- a write that cannot be committed ends, visibly ----------------------------------------------

    @Test
    void aWriteThatIsRefusedWithoutARaceFailsInsteadOfSpinning(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer buffer = untitled(fx, "draft\n");
            Path target = dir.resolve("never-written.txt");
            AtomicInteger attempts = new AtomicInteger();
            // The commit is refused every time and the target never appears: nothing a retry could change.
            workflows.setDocumentWriter((path, bytes, commit) -> {
                attempts.incrementAndGet();
                return false;
            });

            FxTestSupport.runOnFx(() -> assertTrue(workflows.applySaveAsTarget(buffer, target)));
            settle(async, workflows); // an unbounded loop never releases the single write worker

            assertEquals(SaveTarget.MAX_ATTEMPTS, attempts.get());
            assertEquals(tr("status.failedSave", tr("status.save.targetKeptChanging", "never-written.txt")), echo(fx));
            assertFalse(Files.exists(target));
            assertNull(FxTestSupport.callOnFx(buffer::getPath), "the failed Save As is rolled back");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertFalse(FxTestSupport.callOnFx(() -> workflows.hasPendingSave(buffer)));
        }
    }

    @Test
    void aTargetWhoseExistenceCannotBeDeterminedIsAFailedSave(@TempDir Path dir) throws Exception {
        // A folder that may not be searched: stat on anything inside fails with "permission denied", which is
        // neither "exists" nor "does not exist" — the same non-answer a dropped network mount gives.
        Path locked = Files.createDirectory(dir.resolve("locked"));
        Path target = locked.resolve("child.txt");
        try {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        } catch (UnsupportedOperationException notPosix) {
            Assumptions.abort("not a POSIX filesystem");
        }
        try (AsyncTestScope async = new AsyncTestScope()) {
            Assumptions.assumeTrue(
                    !Files.exists(target) && !Files.notExists(target),
                    "this user can see through the folder's permissions (running as root?)");
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer buffer = untitled(fx, "draft\n");
            AtomicInteger attempts = new AtomicInteger();
            workflows.setDocumentWriter((path, bytes, commit) -> {
                attempts.incrementAndGet();
                return AtomicFileWrite.writeIf(path, bytes, commit);
            });

            FxTestSupport.runOnFx(() -> workflows.applySaveAsTarget(buffer, target));
            settle(async, workflows); // the old loop retried a commit that could never pass, forever

            assertEquals(0, attempts.get(), "nothing is staged for a target that cannot be examined");
            assertEquals(tr("status.failedSave", tr("status.save.targetUnreachable", "child.txt")), echo(fx));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertNull(FxTestSupport.callOnFx(buffer::getPath));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    // --- auto-save and the elevated save follow the ordinary save's rules ----------------------------

    @Test
    void autoSaveBlockedByAnExternalChangeSaysSoAndAsks(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("shared.txt"), "A");
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer buffer = open(fx, file, "A");
            Files.writeString(file, "changed by another program\n");

            CountDownLatch keptMine = pressNextDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE);
            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("my edit");
                workflows.autoSaveBuffer(buffer);
            });
            settle(async, workflows);
            async.await(keptMine, "the external-change prompt for the blocked auto-save");
            async.awaitFx();

            assertEquals("changed by another program\n", Files.readString(file), "auto-save never overwrites it");
            assertEquals(tr("status.autoSaveConflict", "shared.txt"), echo(fx));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));

            // "Keep mine" re-baselines the buffer, so auto-save is working again rather than off for good.
            FxTestSupport.runOnFx(() -> workflows.autoSaveBuffer(buffer));
            settle(async, workflows);
            assertEquals("my edit", Files.readString(file));
        }
    }

    @Test
    void theElevatedSaveChecksForAnExternalChangeFirst(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(
                com.editora.process.ElevatedSave.supportedOnOs(System.getProperty("os.name")),
                "elevated save is not offered on this platform");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("hosts"), "A");
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer buffer = open(fx, file, "A");
            AtomicInteger elevatedWrites = new AtomicInteger();
            FxTestSupport.runOnFx(() -> {
                ConfigManager config = FxTestSupport.field(fx.controller, "config");
                config.getSettings().setAdminSave(true);
                workflows.adminToolAvailable = true;
                // Stands in for pkexec/osascript: the privileged copy, without the password prompt.
                workflows.elevatedWriter = (target, bytes) -> {
                    elevatedWrites.incrementAndGet();
                    Files.write(target, bytes);
                    return new FileWorkflowCoordinator.AdminResult(
                            0, "", Files.getLastModifiedTime(target).toMillis(), Files.size(target));
                };
            });

            // No external change: the elevated write goes ahead.
            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("first edit");
                workflows.saveAsAdmin(buffer);
            });
            awaitOnFx(async, "the elevated save to finish", () -> !workflows.hasPendingSave(buffer));
            assertEquals(1, elevatedWrites.get());
            assertEquals("first edit", Files.readString(file));

            // Another program rewrites the file; the next elevated save must ask, as an ordinary save does.
            Files.writeString(file, "changed by another program\n");
            CountDownLatch cancelled = pressNextDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE);
            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("second edit");
                workflows.saveAsAdmin(buffer);
            });
            async.await(cancelled, "the conflict prompt before the elevated write");
            awaitOnFx(async, "the cancelled elevated save to retire", () -> !workflows.hasPendingSave(buffer));

            assertEquals(1, elevatedWrites.get(), "cancelling the prompt must not run the privileged copy");
            assertEquals("changed by another program\n", Files.readString(file));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    // --- helpers -------------------------------------------------------------------------------------

    private static FileWorkflowCoordinator workflows(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "fileWorkflows");
    }

    private static void addBuffer(FxWindowFixture fx, EditorBuffer buffer) {
        FxTestSupport.call(
                fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
    }

    private static EditorBuffer open(FxWindowFixture fx, Path file, String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(content);
            buffer.setDiskSnapshot(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
            addBuffer(fx, buffer);
            return buffer;
        });
    }

    private static EditorBuffer untitled(FxWindowFixture fx, String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent(content);
            addBuffer(fx, buffer);
            return buffer;
        });
    }

    /** The write worker has drained and its completion has run on the FX thread. */
    private static void settle(AsyncTestScope async, FileWorkflowCoordinator workflows) throws Exception {
        ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");
        async.awaitWorker(worker);
        async.awaitFx();
    }

    private static String echo(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<Label>field(status, "echo").getText();
        });
    }

    /** Waits, pulse by pulse, for {@code condition} to hold on the FX thread. */
    static void awaitOnFx(AsyncTestScope async, String description, BooleanSupplier condition) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (condition.getAsBoolean()) {
                    held.countDown();
                    stop();
                }
            }
        };
        FxTestSupport.runOnFx(timer::start);
        async.onClose(() -> FxTestSupport.runOnFx(timer::stop));
        async.await(held, description);
        async.awaitFx();
    }

    static CountDownLatch pressNextDialog(AsyncTestScope async, ButtonBar.ButtonData buttonData) throws Exception {
        CountDownLatch pressed = new CountDownLatch(1);
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (window.getScene() == null || !(window.getScene().getRoot() instanceof DialogPane pane)) {
                        continue;
                    }
                    pane.getButtonTypes().stream()
                            .filter(type -> type.getButtonData() == buttonData)
                            .findFirst()
                            .ifPresent(type -> {
                                pressed.countDown();
                                ((Button) pane.lookupButton(type)).fire();
                            });
                }
                if (pressed.getCount() == 0) {
                    stop();
                }
            }
        };
        FxTestSupport.runOnFx(timer::start);
        async.onClose(() -> FxTestSupport.runOnFx(timer::stop));
        return pressed;
    }
}
