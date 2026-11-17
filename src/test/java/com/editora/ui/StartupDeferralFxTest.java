package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer;
import com.editora.perf.AfterFirstPaint;
import com.editora.testkit.Await;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two startup rules of a window: the focus it gains by being shown repeats none of the regain work that
 * {@code init} and the restore just did, and the probes a launch holds back run once the first content has
 * painted.
 */
@Tag("fx")
class StartupDeferralFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        AfterFirstPaint.release(); // never leave the process-wide gate held for the next test
        if (fx != null) {
            fx.dispose();
            fx = null;
        }
    }

    private static Path fileIn(Path dir) throws Exception {
        return Files.writeString(dir.resolve("notes.txt"), "one\ntwo\n");
    }

    /** Polls until the CLI target is loaded into an editable buffer (the load lands off the FX thread). */
    private static void awaitLoaded(MainController controller, Path file) throws Exception {
        Await.until(
                () -> onFx(() -> {
                    EditorBuffer b = (EditorBuffer) FxTestSupport.call(controller, "activeBuffer", new Class<?>[] {});
                    return b != null && file.equals(b.getPath()) && b.isEditable();
                }),
                "the target file did not load",
                Duration.ofSeconds(10));
    }

    @Test
    void theFocusGainedByBeingShownIsNotARegain() throws Exception {
        Path dir = Files.createTempDirectory("editora-startup-focus");
        Path file = fileIn(dir);
        fx = FxWindowFixture.create(
                dir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        MainController controller = fx.controller;
        awaitLoaded(controller, file);

        // The external-change check is the first thing a real regain does; it stats the file off-thread
        // through this hook, which is therefore the observable.
        CountDownLatch statted = new CountDownLatch(1);
        FileWorkflowCoordinator workflows = FxTestSupport.field(controller, "fileWorkflows");
        workflows.beforeExternalStatForTest = statted::countDown;

        // As right after show(): the startup focus has not arrived, and no tab switch computed the repo state.
        FxTestSupport.runOnFx(() -> {
            controller.startupFocus.resetForTest();
            controller.onWindowFocusGained();
        });
        assertFalse(statted.await(300, TimeUnit.MILLISECONDS), "the show-time focus checks nothing on disk");
        assertFalse(controller.startupFocus.startupFocusPending(), "but it is consumed");
        assertTrue(
                controller.startupFocus.isRepositoryStateComputed(),
                "and it computed the repository state the startup path had not");

        FxTestSupport.runOnFx(controller::onWindowFocusGained); // a real regain
        assertTrue(statted.await(10, TimeUnit.SECONDS), "the next focus gain re-checks the file on disk");
    }

    @Test
    void heldProbesRunOnceTheFirstContentHasPainted() throws Exception {
        Path dir = Files.createTempDirectory("editora-startup-deferral");
        Path file = fileIn(dir);
        AtomicBoolean probeRan = new AtomicBoolean();
        AtomicBoolean probeOnFx = new AtomicBoolean();
        AfterFirstPaint.hold(); // as App.start does before its first window
        AfterFirstPaint.run(() -> {
            probeOnFx.set(Platform.isFxApplicationThread());
            probeRan.set(true);
        });
        fx = FxWindowFixture.create(
                dir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        assertTrue(AfterFirstPaint.held(), "building the window releases nothing by itself");
        assertFalse(probeRan.get());
        awaitLoaded(fx.controller, file);

        FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        // the frame after the content landed lets the probes go
        Await.holdsWithin(() -> !AfterFirstPaint.held(), Duration.ofSeconds(10));
        assertFalse(AfterFirstPaint.held(), "released after the first content painted");
        assertTrue(workflows.perfPaintTimerStarted, "the content-loaded path armed the first-paint timer");
        assertTrue(probeRan.get(), "the held probe ran");
        assertTrue(probeOnFx.get(), "on the FX thread, where its starters belong");
    }

    /** {@code probe}'s answer, read on the FX thread, for a condition {@link Await} checks. */
    private static <T> T onFx(java.util.concurrent.Callable<T> probe) {
        try {
            return FxTestSupport.callOnFx(probe);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
