package com.editora.history;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The blob collection that follows every history save is throttled; a purge is never made to wait. */
class HistoryGcThrottleTest {

    private static void drain(HistoryService service) throws Exception {
        Field field = HistoryService.class.getDeclaredField("exec");
        field.setAccessible(true);
        ((ExecutorService) field.get(service)).submit(() -> {}).get(30, TimeUnit.SECONDS);
    }

    @Test
    void collectionAfterEverySaveIsThrottledAndAPurgeForcesOne(@TempDir Path dir) throws Exception {
        HistoryBlobStore store = new HistoryBlobStore(dir);
        AtomicLong clock = new AtomicLong(42);
        HistoryService service = new HistoryService(store, clock::get);
        try {
            String keep = store.put("still referenced");
            String first = store.put("dropped before the first collection");
            service.gcIfDue(Set.of(keep));
            drain(service);
            assertNull(store.get(first), "the first collection of a session runs");
            assertNotNull(store.get(keep));

            // The next saves land inside the interval: the store is not walked again for each of them.
            String second = store.put("dropped a moment later");
            clock.addAndGet(HistoryService.GC_MIN_INTERVAL_NANOS - 1);
            service.gcIfDue(Set.of(keep));
            drain(service);
            assertNotNull(store.get(second), "a collection inside the interval is skipped, deleting nothing");

            // Once the interval has passed, the next save collects with the live set of that moment.
            clock.addAndGet(1);
            service.gcIfDue(Set.of(keep));
            drain(service);
            assertNull(store.get(second));

            // A purge must take the content off disk now.
            String purged = store.put("a secret the user asked to forget");
            service.gcIfDue(Set.of(keep));
            drain(service);
            assertNotNull(store.get(purged), "still throttled");
            service.requestGc();
            service.gcIfDue(Set.of(keep));
            drain(service);
            assertNull(store.get(purged), "a requested collection ignores the throttle");
            assertNotNull(store.get(keep));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void theIndexIsSweptOncePerStartAndAgainWhenTheLimitsChange(@TempDir Path dir) {
        HistoryService service = new HistoryService(new HistoryBlobStore(dir));
        try {
            var policy = new HistoryRetention.RetentionPolicy(50, 30L * 86_400_000L, 50L * 1024 * 1024);
            assertTrue(service.claimSweep(policy), "the first window to ask sweeps");
            assertFalse(service.claimSweep(policy), "every later window and settings apply does not");
            assertFalse(
                    service.claimSweep(new HistoryRetention.RetentionPolicy(50, 30L * 86_400_000L, 50L * 1024 * 1024)));
            assertTrue(
                    service.claimSweep(new HistoryRetention.RetentionPolicy(50, 7L * 86_400_000L, 50L * 1024 * 1024)),
                    "a changed limit is applied to the whole index");
            assertFalse(service.claimSweep(null));
        } finally {
            service.shutdown();
        }
    }
}
