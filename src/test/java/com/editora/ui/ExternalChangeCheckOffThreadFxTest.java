package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Platform;
import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tab-switch / focus-gain check of the active file used to stat every ancestor directory (for
 * {@code .editorconfig}) and then the file itself on the FX thread. On a cold network or FUSE mount that is
 * the UI frozen for as long as the mount takes; the disk is now asked on a worker.
 */
@Tag("fx")
class ExternalChangeCheckOffThreadFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /**
     * Opens {@code file} in a selected tab and waits for the check that selecting it starts. That check asks
     * the disk on a worker like any other; left running, it would see whatever the test writes next — an
     * {@code .editorconfig}, say — and apply it to the still-open tab before the check under test has begun.
     */
    private static EditorBuffer load(AsyncTestScope async, FxWindowFixture fx, Path file) throws Exception {
        FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer loaded = new EditorBuffer();
            loaded.setPath(file);
            workflows.loadInto(loaded, file);
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, loaded, true);
            return loaded;
        });
        java.util.Set<EditorBuffer> pending = FxTestSupport.field(workflows, "verifyingExternalChange");
        SaveGuardsFxTest.awaitOnFx(async, "the tab-switch check to settle", pending::isEmpty);
        return buffer;
    }

    @Test
    void theDiskIsAskedOffTheFxThreadAndTheAnswerStillApplies(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("a.txt"), "text\n");
            EditorBuffer buffer = load(async, fx, file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            assertNull(
                    FxTestSupport.callOnFx(() -> buffer.getEditorConfigProps().maxLineLength()));
            // A pull brings an .editorconfig the open file knows nothing about yet.
            Files.writeString(dir.resolve(".editorconfig"), "root = true\n[*]\nmax_line_length = 97\n");

            CountDownLatch asking = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean askedOnFx = new AtomicBoolean();
            workflows.beforeExternalStatForTest = () -> {
                askedOnFx.set(Platform.isFxApplicationThread());
                asking.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            async.onClose(release::countDown);

            FxTestSupport.runOnFx(workflows::checkExternalChanges);
            async.await(asking, "the disk to be asked");
            assertFalse(askedOnFx.get(), "the stat must not run on the FX thread");
            // While the mount is slow the FX thread keeps serving events, and nothing was applied early.
            CountDownLatch heartbeat = new CountDownLatch(1);
            Platform.runLater(heartbeat::countDown);
            async.await(heartbeat, "the FX thread to stay responsive");
            assertNull(
                    FxTestSupport.callOnFx(() -> buffer.getEditorConfigProps().maxLineLength()));
            // A second request for the same buffer does not queue a second round of stats.
            FxTestSupport.runOnFx(workflows::checkExternalChanges);

            workflows.beforeExternalStatForTest = null;
            release.countDown();
            SaveGuardsFxTest.awaitOnFx(
                    async,
                    "the rules to reach the buffer",
                    () -> Integer.valueOf(97)
                            .equals(buffer.getEditorConfigProps().maxLineLength()));
            assertEquals("text\n", FxTestSupport.callOnFx(buffer::getContent));
        }
    }

    @Test
    void anAnswerForATabClosedMeanwhileIsDropped(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("b.txt"), "text\n");
            EditorBuffer buffer = load(async, fx, file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            Files.writeString(dir.resolve(".editorconfig"), "root = true\n[*]\nmax_line_length = 97\n");
            Files.writeString(file, "changed by someone else\n");

            CountDownLatch asking = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            workflows.beforeExternalStatForTest = () -> {
                asking.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            async.onClose(release::countDown);
            FxTestSupport.runOnFx(workflows::checkExternalChanges);
            async.await(asking, "the disk to be asked");
            FxTestSupport.runOnFx(() -> {
                Tab tab = (Tab)
                        FxTestSupport.call(fx.controller, "tabForBuffer", new Class<?>[] {EditorBuffer.class}, buffer);
                javafx.scene.control.TabPane pane = FxTestSupport.field(fx.controller, "tabPane");
                pane.getTabs().remove(tab);
            });
            workflows.beforeExternalStatForTest = null;
            release.countDown();
            async.awaitFx();
            SaveGuardsFxTest.awaitOnFx(async, "the check to settle", () -> {
                java.util.Set<EditorBuffer> pending = FxTestSupport.field(workflows, "verifyingExternalChange");
                return pending.isEmpty();
            });

            assertNull(buffer.getEditorConfigProps().maxLineLength(), "nothing is applied to a closed tab");
            assertTrue(
                    javafx.stage.Window.getWindows().stream()
                            .noneMatch(w -> w.getScene() != null
                                    && w.getScene().getRoot() instanceof javafx.scene.control.DialogPane),
                    "and nobody is asked about it");
        }
    }
}
