package com.editora.perf;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ProbeCache}: a probe's answer kept, looked at again only when asked to, and named as it looks. */
class ProbeCacheTest {

    private final Map<String, Integer> disk = new ConcurrentHashMap<>();
    private final AtomicInteger probes = new AtomicInteger();
    private final List<String> heard = new CopyOnWriteArrayList<>();

    private ProbeCache<String, Integer> cache() {
        return new ProbeCache<>("test-probe", key -> {
            probes.incrementAndGet();
            Integer value = disk.get(key);
            if (value != null && value < 0) {
                throw new IllegalStateException("unreadable");
            }
            return value == null ? 0 : value;
        });
    }

    @AfterEach
    void stopListening() {
        BlockingSites.listenForTest(null);
    }

    @Test
    void anAnswerIsLookedUpOnceAndKept() {
        ProbeCache<String, Integer> cache = cache();
        Thread self = Thread.currentThread();
        BlockingSites.listenForTest((site, thread) -> {
            if (thread == self) {
                heard.add(site + "@" + thread.getName());
            }
        });
        disk.put("a", 1);
        assertFalse(cache.knows("a"));
        assertEquals(1, cache.get("a"));
        disk.put("a", 2);
        assertEquals(1, cache.get("a"), "kept, not looked up again");
        assertEquals(1, probes.get());
        assertTrue(cache.knows("a"));
        assertEquals(List.of("test-probe@" + Thread.currentThread().getName()), heard, "the one look is named");

        assertEquals(2, cache.prepare("a"), "prepare always looks");
        cache.forget();
        assertFalse(cache.knows("a"));
        disk.put("a", 3);
        assertEquals(3, cache.get("a"), "forgotten: looked up again");
    }

    @Test
    void aProbeMayNotAnswerNull() {
        ProbeCache<String, Integer> nulls = new ProbeCache<>("nulls", key -> null);
        assertThrows(NullPointerException.class, () -> nulls.get("x"));
    }

    @Test
    void refreshKeepsNewAnswersAndSaysSoOnlyWhenOneChanged() throws Exception {
        ProbeCache<String, Integer> cache = cache();
        cache.refresh(() -> {
            throw new AssertionError("nothing kept: nothing to look at");
        });
        disk.put("a", 1);
        disk.put("b", 5);
        cache.get("a");
        cache.get("b");

        CountDownLatch unchanged = new CountDownLatch(1);
        cache.refresh(unchanged::countDown);
        assertFalse(unchanged.await(300, TimeUnit.MILLISECONDS), "the same answers: no change told");

        disk.put("a", 4);
        disk.put("b", -1); // this one cannot be looked at now: what was known stays
        CountDownLatch changed = new CountDownLatch(1);
        String[] thread = new String[1];
        cache.refresh(() -> {
            thread[0] = Thread.currentThread().getName();
            changed.countDown();
        });
        assertTrue(changed.await(10, TimeUnit.SECONDS));
        assertEquals("test-probe-refresh", thread[0], "on a thread of its own");
        assertEquals(4, cache.get("a"));
        assertEquals(5, cache.get("b"));
    }

    @Test
    void nobodyListeningCostsNothing() {
        BlockingSites.enter("nobody hears this");
        Thread self = Thread.currentThread();
        BlockingSites.listenForTest((site, t) -> {
            if (t == self) {
                heard.add(site);
            }
        });
        BlockingSites.enter("heard");
        BlockingSites.listenForTest(null);
        BlockingSites.enter("not heard");
        assertEquals(List.of("heard"), heard);
    }

    @Test
    void reprobeKeptLooksAtEveryKeptKeyAgainWithoutEmptyingTheCache() {
        ProbeCache<String, Integer> cache = cache();
        disk.put("a", 1);
        disk.put("b", 2);
        assertEquals(1, cache.get("a"));
        assertEquals(2, cache.get("b"));
        disk.put("a", 10); // an install changed what is on disk
        disk.put("b", -1); // and this one cannot be read now
        cache.reprobeKept();
        assertTrue(cache.knows("a"), "kept: the next ask answers from memory");
        assertEquals(10, cache.get("a"), "with the new answer");
        assertFalse(cache.knows("b"), "an unreadable one is dropped, asked again where it is next asked");
    }
}
