package com.editora.ui;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundTasksTest {

    @Test
    void aStartedTaskIsListedUntilItFinishes() {
        BackgroundTasks tasks = new BackgroundTasks();
        assertTrue(tasks.isIdle());

        BackgroundTasks.Handle h = tasks.start("Searching…");
        assertEquals(1, tasks.count());
        assertEquals("Searching…", tasks.current().label());

        h.done();
        assertTrue(tasks.isIdle());
        assertNull(tasks.current());
    }

    /** The indicator names the oldest task, so it doesn't flicker between concurrent ones. */
    @Test
    void theOldestRunningTaskIsTheCurrentOne() {
        BackgroundTasks tasks = new BackgroundTasks();
        BackgroundTasks.Handle first = tasks.start("Building…");
        tasks.start("Searching…");

        assertEquals("Building…", tasks.current().label());
        assertEquals(2, tasks.count());

        first.done();
        assertEquals("Searching…", tasks.current().label(), "the next oldest takes over");
    }

    /**
     * A service that both completes and errors, or retries its own cleanup, will close a handle twice. Since
     * ids are never reused, the second call must be a no-op rather than removing some other task.
     */
    @Test
    void closingAHandleTwiceDoesNotRemoveAnotherTask() {
        BackgroundTasks tasks = new BackgroundTasks();
        BackgroundTasks.Handle first = tasks.start("A");
        BackgroundTasks.Handle second = tasks.start("B");

        first.done();
        first.done();
        first.done();

        assertEquals(1, tasks.count(), "B is untouched");
        assertEquals("B", tasks.current().label());
        second.done();
        assertTrue(tasks.isIdle());
    }

    @Test
    void listenersFireOnEveryStartAndFinish() {
        BackgroundTasks tasks = new BackgroundTasks();
        AtomicInteger changes = new AtomicInteger();
        tasks.addListener(changes::incrementAndGet);

        BackgroundTasks.Handle h = tasks.start("A");
        assertEquals(1, changes.get());
        h.done();
        assertEquals(2, changes.get());
        h.done(); // already gone
        assertEquals(2, changes.get(), "a no-op close does not fire a spurious change");
    }

    @Test
    void cancelAllStopsOnlyWhatCanBeStopped() {
        BackgroundTasks tasks = new BackgroundTasks();
        AtomicInteger cancels = new AtomicInteger();
        tasks.start("cancellable", cancels::incrementAndGet);
        tasks.start("not cancellable");

        tasks.cancelAll();

        assertEquals(1, cancels.get(), "only the one that could be cancelled was");
        assertEquals(2, tasks.count(), "cancelling asks it to stop; the service removes it when it has");
        assertFalse(tasks.running().get(1).cancellable());
    }
}
