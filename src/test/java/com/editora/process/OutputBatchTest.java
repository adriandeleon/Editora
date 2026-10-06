package com.editora.process;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins {@link OutputBatch}: a flush deferred inside a drain runs once, when the drain's output is done. */
class OutputBatchTest {

    @Test
    void outsideADrainNothingIsDeferred() {
        List<String> ran = new ArrayList<>();
        assertFalse(OutputBatch.defer(() -> ran.add("flush")), "the caller applies its text itself");
        assertEquals(List.of(), ran);
    }

    @Test
    void aFlushDeferredManyTimesRunsOnceAtTheEndOfTheDrain() {
        List<String> ran = new ArrayList<>();
        Runnable first = () -> ran.add("first");
        Runnable second = () -> ran.add("second");
        OutputBatch.begin();
        for (int i = 0; i < 256; i++) {
            assertTrue(OutputBatch.defer(first));
            assertTrue(OutputBatch.defer(second));
        }
        assertEquals(List.of(), ran, "nothing runs while lines are still being delivered");
        OutputBatch.end();
        assertEquals(List.of("first", "second"), ran, "once each, in the order they first asked");

        assertFalse(OutputBatch.defer(first), "the drain is over");
        OutputBatch.begin();
        OutputBatch.end();
        assertEquals(List.of("first", "second"), ran, "a flush is not carried into the next drain");
    }

    @Test
    void aFailingFlushDoesNotStopTheOthersOrLeaveTheDrainOpen() {
        List<String> ran = new ArrayList<>();
        OutputBatch.begin();
        OutputBatch.defer(() -> {
            throw new IllegalStateException("a console failed");
        });
        OutputBatch.defer(() -> ran.add("after"));
        OutputBatch.end();
        assertEquals(List.of("after"), ran);
        assertFalse(OutputBatch.defer(() -> ran.add("late")));
    }

    @Test
    void aDrainNestedInAnotherFlushesAtItsOwnEndAndTheOuterOneStaysOpen() {
        List<String> ran = new ArrayList<>();
        Runnable flush = () -> ran.add("flush");
        OutputBatch.begin();
        OutputBatch.defer(flush);
        OutputBatch.begin();
        OutputBatch.end();
        assertEquals(List.of("flush"), ran);
        assertTrue(OutputBatch.defer(flush), "the outer drain is still delivering");
        OutputBatch.end();
        assertEquals(List.of("flush", "flush"), ran);
    }
}
