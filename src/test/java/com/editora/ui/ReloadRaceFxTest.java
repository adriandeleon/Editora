package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Text typed while a reload's disk read is in flight was never part of the decision to reload: it stays. */
@Tag("fx")
class ReloadRaceFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    @Test
    void theSilentReloadAfterAGitOperationKeepsWhatWasTypedMeanwhile() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("pulled.txt"), "before the pull\n");
            EditorBuffer buffer = load(fx, file);
            FileWorkflowCoordinator workflows = workflows(fx);
            rewrite(file, "pulled from upstream\n");
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            hold(workflows, reading, release);
            async.onClose(release::countDown);

            FxTestSupport.runOnFx(fx.controller::reloadAllFromDiskSilently);
            async.await(reading, "the reload to start reading the file");
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "typed meanwhile "));
            workflows.beforeReloadReadForTest = null;
            release.countDown();
            String skipped = tr("status.reloadSkippedEdited", "pulled.txt");
            SaveGuardsFxTest.awaitOnFx(
                    async,
                    "the reload to settle",
                    () -> skipped.equals(echo(fx)) || buffer.getContent().startsWith("pulled"));

            FxTestSupport.runOnFx(() -> {
                assertEquals("typed meanwhile before the pull\n", buffer.getContent());
                assertTrue(buffer.isDirty(), "the typed text is unsaved");
                assertTrue(buffer.getArea().isUndoAvailable(), "and its undo history is intact");
                assertTrue(
                        buffer.diskChangedFrom(lastModified(file), 21),
                        "the disk copy is still seen as changed, so a save asks before overwriting it");
            });
        }
    }

    @Test
    void aChosenReloadKeepsWhatWasTypedAfterTheChoice() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("chosen.txt"), "before\n");
            EditorBuffer buffer = load(fx, file);
            FileWorkflowCoordinator workflows = workflows(fx);
            rewrite(file, "changed by another program\n");
            CompletableFuture<Boolean> applied = new CompletableFuture<>();

            FxTestSupport.runOnFx(() -> {
                Tab tab = tabOf(fx, buffer);
                workflows.reloadFromDisk(tab, buffer, applied::complete);
                buffer.getArea().insertText(0, "typed after choosing Reload ");
            });

            assertFalse(async.await(applied), "the reload must report that it did not replace the text");
            FxTestSupport.runOnFx(() -> {
                assertEquals("typed after choosing Reload before\n", buffer.getContent());
                assertTrue(buffer.isDirty());
                assertTrue(buffer.getArea().isUndoAvailable());
            });
        }
    }

    @Test
    void anUntouchedBufferIsStillReloaded() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("quiet.txt"), "before\n");
            EditorBuffer buffer = load(fx, file);
            rewrite(file, "pulled from upstream\n");

            FxTestSupport.runOnFx(fx.controller::reloadAllFromDiskSilently);
            SaveGuardsFxTest.awaitOnFx(
                    async, "the silent reload", () -> "pulled from upstream\n".equals(buffer.getContent()));

            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    private static void hold(FileWorkflowCoordinator workflows, CountDownLatch reading, CountDownLatch release) {
        workflows.beforeReloadReadForTest = () -> {
            reading.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out holding the reload read");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private static void rewrite(Path file, String text) throws Exception {
        long before = Files.getLastModifiedTime(file).toMillis();
        Files.writeString(file, text);
        Files.setLastModifiedTime(file, FileTime.fromMillis(before + 60_000));
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String echo(FxWindowFixture fx) {
        StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
        return FxTestSupport.<javafx.scene.control.Label>field(status, "echo").getText();
    }

    private static Tab tabOf(FxWindowFixture fx, EditorBuffer buffer) {
        return (Tab) FxTestSupport.call(fx.controller, "tabForBuffer", new Class<?>[] {EditorBuffer.class}, buffer);
    }

    private static FileWorkflowCoordinator workflows(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "fileWorkflows");
    }

    private static EditorBuffer load(FxWindowFixture fx, Path file) throws Exception {
        FileWorkflowCoordinator workflows = workflows(fx);
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            workflows.loadInto(buffer, file);
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }
}
