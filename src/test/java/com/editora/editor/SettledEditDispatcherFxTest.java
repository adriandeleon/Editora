package com.editora.editor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class SettledEditDispatcherFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void runsEnabledTasksAtSharedMilestonesInRegistrationOrder() throws Exception {
        AtomicReference<SettledEditDispatcher> dispatcher = new AtomicReference<>();
        List<String> calls = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(3);

        runOnFx(() -> {
            SettledEditDispatcher d = new SettledEditDispatcher();
            dispatcher.set(d);
            d.at(Duration.ofMillis(20), () -> true, () -> {
                calls.add("first");
                done.countDown();
            });
            d.at(Duration.ofMillis(20), () -> false, () -> calls.add("disabled"));
            d.at(Duration.ofMillis(20), () -> true, () -> {
                calls.add("second");
                done.countDown();
            });
            d.at(Duration.ofMillis(45), () -> true, () -> {
                calls.add("third");
                done.countDown();
            });

            assertEquals(4, d.taskCount());
            assertEquals(2, d.enabledMilestoneCount());
            d.changed();
        });

        try {
            if (!done.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("settled edit milestones timed out");
            }
            assertEquals(List.of("first", "second", "third"), calls);
        } finally {
            runOnFx(dispatcher.get()::dispose);
        }
    }

    @Test
    void disposeCancelsAnArmedMilestone() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        runOnFx(() -> {
            SettledEditDispatcher dispatcher = new SettledEditDispatcher();
            dispatcher.at(Duration.ofMillis(25), () -> true, fired::countDown);
            dispatcher.changed();
            dispatcher.dispose();
        });

        assertFalse(fired.await(150, TimeUnit.MILLISECONDS));
    }

    @Test
    void editorAndSplitViewKeepOneSharedSettledEditSubscription() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                assertEquals(7, buffer.settledEditTaskCount());
                assertTrue(buffer.settledEditSubscriptionActive());

                buffer.setSplit(EditorBuffer.Split.SIDE_BY_SIDE);
                assertEquals(7, buffer.settledEditTaskCount());
                assertTrue(buffer.settledEditSubscriptionActive());
            } finally {
                buffer.dispose();
            }
            assertFalse(buffer.settledEditSubscriptionActive());
        });
    }

    @Test
    void htmlPreviewListenerStillRunsAtItsSettledMilestone() throws Exception {
        AtomicReference<EditorBuffer> buffer = new AtomicReference<>();
        CountDownLatch fired = new CountDownLatch(1);
        runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            buffer.set(b);
            b.setPath(Path.of("index.html"));
            b.setHtmlPreviewDirtyListener(fired::countDown);
            b.getArea().replaceText("<p>edited</p>");
        });

        try {
            assertTrue(fired.await(5, TimeUnit.SECONDS));
        } finally {
            runOnFx(buffer.get()::dispose);
        }
    }

    private static void runOnFx(Runnable task) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX task timed out");
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }
}
