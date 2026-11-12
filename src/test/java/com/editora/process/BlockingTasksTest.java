package com.editora.process;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Blocking one-off work gets a named virtual thread of its own, never a thread of the common pool. */
class BlockingTasksTest {

    @Test
    void eachTaskRunsOnANamedVirtualThread() throws Exception {
        Thread ran = CompletableFuture.supplyAsync(Thread::currentThread, BlockingTasks.named("blocking-test"))
                .get(10, TimeUnit.SECONDS);
        assertTrue(ran.isVirtual());
        assertEquals("blocking-test", ran.getName());
    }
}
