package com.editora.perf;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gate a launch holds in front of its startup probes until the first content has painted. */
class AfterFirstPaintTest {

    @AfterEach
    void letGo() {
        AfterFirstPaint.release(); // the gate is process-wide: never leave it held for the next test
    }

    @Test
    void runsAtOnceWhenNotHeld() {
        List<String> ran = new ArrayList<>();
        assertFalse(AfterFirstPaint.held());
        AfterFirstPaint.run(() -> ran.add("now"));
        assertEquals(List.of("now"), ran, "outside a launch a task is not deferred at all");
    }

    @Test
    void holdsUntilReleasedThenRunsInOrder() {
        List<String> ran = new ArrayList<>();
        AfterFirstPaint.hold();
        AfterFirstPaint.hold(); // idempotent
        assertTrue(AfterFirstPaint.held());
        AfterFirstPaint.run(() -> ran.add("a"));
        AfterFirstPaint.run(() -> ran.add("b"));
        assertEquals(List.of(), ran, "queued, not run");
        AfterFirstPaint.release();
        assertFalse(AfterFirstPaint.held());
        assertEquals(List.of("a", "b"), ran, "released in the order queued");
        AfterFirstPaint.run(() -> ran.add("c"));
        assertEquals(List.of("a", "b", "c"), ran, "after the release a task runs at once again");
        AfterFirstPaint.release(); // a second release is a no-op
        assertEquals(List.of("a", "b", "c"), ran);
    }

    @Test
    void aFailingTaskDoesNotStopTheRest() {
        List<String> ran = new ArrayList<>();
        AfterFirstPaint.hold();
        AfterFirstPaint.run(() -> {
            throw new IllegalStateException("probe failed");
        });
        AfterFirstPaint.run(() -> ran.add("after"));
        AfterFirstPaint.release();
        assertEquals(List.of("after"), ran);
        assertFalse(AfterFirstPaint.held());
    }

    @Test
    void runOnceKeepsOneTaskPerKeyWhileHeld() {
        List<String> ran = new ArrayList<>();
        Object key = new Object();
        AfterFirstPaint.runOnce(key, () -> ran.add("now"));
        assertEquals(List.of("now"), ran, "not held: runs at once, every time");
        AfterFirstPaint.hold();
        AfterFirstPaint.runOnce(key, () -> ran.add("first"));
        AfterFirstPaint.runOnce(key, () -> ran.add("second"));
        AfterFirstPaint.runOnce(new Object(), () -> ran.add("other"));
        AfterFirstPaint.release();
        assertEquals(List.of("now", "first", "other"), ran, "repeated requests under one key collapse into the first");
        AfterFirstPaint.hold();
        AfterFirstPaint.runOnce(key, () -> ran.add("again"));
        AfterFirstPaint.release();
        assertEquals(List.of("now", "first", "other", "again"), ran, "a new hold starts with no keys");
    }
}
