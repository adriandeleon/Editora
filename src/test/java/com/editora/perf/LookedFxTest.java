package com.editora.perf;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Looked}: a look made off the FX thread is kept, and the FX thread answers from it without looking (PERF-01,
 * Pauta 6.2.0); a key never looked at answers its cold answer (or null) there and is looked at behind.
 */
@Tag("fx")
class LookedFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void tearDown() {
        BlockingSites.listenForTest(null);
        OffFx.runnerForTest(null);
    }

    /** Runs the work {@link OffFx} is handed in place: called on the FX thread, its answer lands before it returns. */
    private static void offFxInPlace() {
        OffFx.runnerForTest((name, task) -> task.run());
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Exception> failed = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                out.set(work.call());
            } catch (Exception e) {
                failed.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (failed.get() != null) {
            throw failed.get();
        }
        return out.get();
    }

    @Test
    void theFxThreadAnswersFromWhatAPlanLookedAt() throws Exception {
        AtomicInteger looks = new AtomicInteger();
        AtomicReference<String> disk = new AtomicReference<>("first");
        Looked<String, String> looked = new Looked<>("guard-look", key -> {
            looks.incrementAndGet();
            return disk.get() == null ? null : key + ":" + disk.get();
        });
        List<String> onFx = new CopyOnWriteArrayList<>();
        BlockingSites.listenForTest((site, thread) -> {
            if (Platform.isFxApplicationThread()) {
                onFx.add(site);
            }
        });

        assertEquals("a:first", looked.get("a"), "a plan's look, off the FX thread");
        assertEquals("a:first", onFx(() -> looked.get("a")), "the FX thread answers from it");
        assertEquals(List.of(), onFx, "without looking");
        assertEquals(1, looks.get());

        disk.set("second");
        assertEquals("a:second", looked.get("a"), "the next plan's look refreshes it");
        assertEquals("a:second", onFx(() -> looked.get("a")));

        disk.set(null);
        int before = looks.get();
        assertNull(onFx(() -> looked.get("b")), "a key nobody looked at answers unknown on the FX thread");
        waitUntil(() -> looked.knows("b"));
        assertEquals(before + 1, looks.get(), "and is looked at behind, off the FX thread");
        assertNull(onFx(() -> looked.get("b")), "a null answer is kept too");
        assertEquals(List.of(), onFx, "no memo looks on the FX thread (PERF-01)");
    }

    @Test
    void aFullMemoKeepsTheKeysJustLookedAt() throws Exception {
        Looked<Integer, Integer> memo = new Looked<>("full-look", k -> k);
        for (int i = 0; i < 300; i++) {
            memo.get(i); // off the FX thread: looked at now, all of them fresh
        }
        for (int i = 0; i < 300; i++) {
            assertTrue(memo.knows(i), "a key a command just had looked at is still there: " + i);
        }
        assertTrue(memo.size() >= 300, "fresh keys are not dropped at the bound");
        for (int i = 300; i < 2000; i++) {
            memo.get(i);
        }
        assertTrue(memo.size() < 2000, "but the memo stays bounded: " + memo.size());
        assertTrue(memo.knows(1999), "the newest are kept");
    }

    @Test
    void aColdKeyOnTheFxThreadAnswersTheColdAnswerAndIsLookedAtBehind() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger looks = new AtomicInteger();
        List<String> learned = new CopyOnWriteArrayList<>();
        Looked<String, Boolean> looked = new Looked<String, Boolean>(
                        "cold-look",
                        key -> {
                            looks.incrementAndGet();
                            try {
                                release.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return Boolean.FALSE;
                        },
                        Boolean.TRUE)
                .onLearned(learned::add);
        List<String> onFx = new CopyOnWriteArrayList<>();
        BlockingSites.listenForTest((site, thread) -> {
            if (Platform.isFxApplicationThread()) {
                onFx.add(site);
            }
        });

        assertEquals(Boolean.TRUE, onFx(() -> looked.get("a")), "unknown: the cold answer, nothing hidden");
        assertEquals(Boolean.TRUE, onFx(() -> looked.get("a")), "a second ask while the look runs");
        release.countDown();
        waitUntil(() -> learned.contains("a"));
        assertEquals(Boolean.FALSE, onFx(() -> looked.get("a")), "the look's answer once it lands");
        assertEquals(1, looks.get(), "one look for both asks");
        assertEquals(List.of(), onFx, "nothing looked at on the FX thread");
    }

    @Test
    void whenKnownWaitsOffTheFxThreadAndAnswersOnIt() throws Exception {
        Looked<String, String> looked = new Looked<>("known-look", key -> key + "!", "");
        List<String> onFx = new CopyOnWriteArrayList<>();
        BlockingSites.listenForTest((site, thread) -> {
            if (Platform.isFxApplicationThread()) {
                onFx.add(site);
            }
        });
        AtomicReference<String> answer = new AtomicReference<>();
        AtomicReference<Boolean> answeredOnFx = new AtomicReference<>();
        onFx(() -> {
            looked.whenKnown("b", v -> {
                answer.set(v);
                answeredOnFx.set(Platform.isFxApplicationThread());
            });
            return null;
        });
        waitUntil(() -> answer.get() != null);
        assertEquals("b!", answer.get());
        assertTrue(answeredOnFx.get(), "handed back on the FX thread");
        assertTrue(looked.knows("b"), "and kept");
        assertEquals(List.of(), onFx);

        looked.warm("c"); // off the FX thread: looked at now
        assertTrue(looked.knows("c"));
    }

    @Test
    void aProbeCacheWithAColdAnswerNeverProbesOnTheFxThread() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        List<String> learned = new CopyOnWriteArrayList<>();
        ProbeCache<String, Boolean> cache = new ProbeCache<String, Boolean>(
                        "cold-probe",
                        key -> {
                            probes.incrementAndGet();
                            return Boolean.FALSE;
                        },
                        Boolean.TRUE)
                .onLearned(learned::add);
        List<String> onFx = new CopyOnWriteArrayList<>();
        BlockingSites.listenForTest((site, thread) -> {
            if (Platform.isFxApplicationThread()) {
                onFx.add(site);
            }
        });
        Boolean first = onFx(() -> cache.get("k"));
        waitUntil(() -> learned.contains("k"));
        assertEquals(Boolean.FALSE, onFx(() -> cache.get("k")), "the probe's answer once it lands");
        assertTrue(first, "the cold answer meanwhile");
        assertEquals(1, probes.get());
        assertEquals(List.of(), onFx);
    }

    @Test
    void allKnownChecksWithoutLookingAndALookOffTheFxThreadFillsIt() throws Exception {
        AtomicInteger looks = new AtomicInteger();
        Looked<String, String> memo = new Looked<>("test-all-known", k -> {
            looks.incrementAndGet();
            return k + "!";
        });
        ProbeCache<String, Boolean> cache = new ProbeCache<>("test-all-known-cache", k -> {
            looks.incrementAndGet();
            return Boolean.TRUE;
        });
        List<String> heard = new CopyOnWriteArrayList<>();
        BlockingSites.listenForTest((site, thread) -> heard.add(site));

        // Nothing kept: on the FX thread the probe says so, and nothing is looked at.
        assertEquals(Boolean.FALSE, onFx(() -> Looked.allKnown(() -> memo.get("a"))));
        assertEquals(Boolean.FALSE, onFx(() -> Looked.allKnown(() -> cache.get("a"))));
        assertEquals(0, looks.get(), "a probe looks at nothing");
        assertEquals(List.of(), heard);

        // The same asks off the FX thread look for real and keep the answers; the probe then passes.
        memo.get("a");
        cache.get("a");
        assertEquals(2, looks.get());
        assertEquals(
                Boolean.TRUE,
                onFx(() -> Looked.allKnown(() -> {
                    memo.get("a");
                    cache.get("a");
                })));
        assertEquals("a!", onFx(() -> memo.get("a")), "and the FX thread answers from memory");
        // A probe that finds one gap among several stops there and still says so.
        assertEquals(
                Boolean.FALSE,
                onFx(() -> Looked.allKnown(() -> {
                    memo.get("a");
                    memo.get("b");
                })));
        assertEquals(2, looks.get());
    }

    @Test
    void whenKnownOffTheFxThreadOrWithAKeptKeyAnswersInPlace() throws Exception {
        AtomicInteger looks = new AtomicInteger();
        Looked<String, String> looked = new Looked<>("in-place-look", key -> {
            looks.incrementAndGet();
            return key + "!";
        });
        Thread self = Thread.currentThread();
        AtomicReference<String> answer = new AtomicReference<>();
        AtomicReference<Thread> answeredOn = new AtomicReference<>();
        looked.whenKnown("a", v -> {
            answer.set(v);
            answeredOn.set(Thread.currentThread());
        });
        assertEquals("a!", answer.get(), "off the FX thread: looked at and answered before the call returns");
        assertEquals(self, answeredOn.get(), "on the asker's own thread");
        assertTrue(looked.knows("a"));

        // A kept key on the FX thread: answered at once, from memory, with no look behind.
        AtomicReference<String> onFxAnswer = new AtomicReference<>();
        String atReturn = onFx(() -> {
            looked.whenKnown("a", onFxAnswer::set);
            return onFxAnswer.get();
        });
        assertEquals("a!", atReturn, "answered before whenKnown returned on the FX thread");
        assertEquals(1, looks.get(), "from what was kept");
    }

    @Test
    void whenKnownHandsTheColdAnswerOnWhenTheLookFails() throws Exception {
        offFxInPlace();
        AtomicInteger looks = new AtomicInteger();
        Looked<String, String> looked = new Looked<>(
                "failing-known-look",
                key -> {
                    looks.incrementAndGet();
                    throw new IllegalStateException("unreadable");
                },
                "cold");
        AtomicReference<String> answer = new AtomicReference<>();
        onFx(() -> {
            looked.whenKnown("x", answer::set);
            return null;
        });
        assertEquals("cold", answer.get(), "a failed look answers what the code did when the fact was unknown");
        assertEquals(1, looks.get());
        assertFalse(looked.knows("x"), "nothing kept for a look that failed");
    }

    @Test
    void warmLooksBehindOnTheFxThreadAndNotAtAllForAKeptKey() throws Exception {
        AtomicInteger looks = new AtomicInteger();
        List<String> onFx = new CopyOnWriteArrayList<>();
        Looked<String, String> looked = new Looked<>("warm-look", key -> {
            looks.incrementAndGet();
            return key.toUpperCase(java.util.Locale.ROOT);
        });
        BlockingSites.listenForTest((site, thread) -> {
            if (Platform.isFxApplicationThread()) {
                onFx.add(site);
            }
        });
        onFx(() -> {
            looked.warm("w");
            return null;
        });
        waitUntil(() -> looked.knows("w"));
        assertEquals(List.of(), onFx, "warmed on the FX thread: looked at behind it");
        assertEquals("W", onFx(() -> looked.get("w")), "and the FX thread then answers from memory");
        assertEquals(1, looks.get());

        looked.warm("w");
        onFx(() -> {
            looked.warm("w");
            return null;
        });
        assertEquals(1, looks.get(), "a kept key is not looked at again, on either thread");
    }

    @Test
    void anAllKnownInsideAnotherAnswersForItsOwnAsksAndHandsTheOuterBack() {
        AtomicInteger looks = new AtomicInteger();
        Looked<String, String> memo = new Looked<>("nested-all-known", k -> {
            looks.incrementAndGet();
            return k;
        });
        memo.get("kept"); // off the FX thread: looked at now
        boolean[] inner = new boolean[1];
        boolean outer = Looked.allKnown(() -> {
            inner[0] = Looked.allKnown(() -> memo.get("gap"));
            memo.get("kept");
        });
        assertFalse(inner[0], "the inner check found its gap");
        assertTrue(outer, "the outer one's own asks were all kept");

        boolean outerWithGap = Looked.allKnown(() -> {
            Looked.allKnown(() -> memo.get("kept"));
            memo.get("other-gap"); // still checked, not looked at: the outer probe is back in place
        });
        assertFalse(outerWithGap);
        assertEquals(1, looks.get(), "no check looked at anything");
        assertNull(Looked.PROBING.get(), "and nothing is left set on this thread");
        assertEquals("other-gap", memo.get("other-gap"), "so an ask afterwards looks for real");
    }

    @Test
    void aFailedLookBehindIsNotHeardAndIsTriedAgainOnTheNextAsk() throws Exception {
        offFxInPlace();
        AtomicInteger looks = new AtomicInteger();
        List<String> learned = new CopyOnWriteArrayList<>();
        Looked<String, String> looked = new Looked<String, String>(
                        "failing-look",
                        key -> {
                            looks.incrementAndGet();
                            throw new IllegalStateException("unreadable");
                        },
                        "cold")
                .onLearned(learned::add);
        assertEquals("cold", onFx(() -> looked.get("k")), "the cold answer: the look behind failed");
        assertEquals(1, looks.get());
        assertEquals(List.of(), learned, "a look that failed teaches the listener nothing");
        assertFalse(looked.knows("k"));
        assertEquals("cold", onFx(() -> looked.get("k")));
        assertEquals(2, looks.get(), "the failed look let go of the key, so the next ask looks again");
    }

    @Test
    void aColdProbeCacheWithoutAListenerKeepsWhatItsProbeBehindFound() throws Exception {
        offFxInPlace();
        AtomicInteger probes = new AtomicInteger();
        ProbeCache<String, Boolean> cache = new ProbeCache<>(
                "quiet-probe",
                key -> {
                    probes.incrementAndGet();
                    return Boolean.FALSE;
                },
                Boolean.TRUE);
        assertEquals(Boolean.FALSE, onFx(() -> cache.get("k")), "probed in place: its answer, not the cold one");
        assertTrue(cache.knows("k"));
        assertEquals(Boolean.FALSE, onFx(() -> cache.get("k")));
        assertEquals(1, probes.get(), "kept: probed once");
    }

    @Test
    void aFailedProbeBehindIsNotHeardAndIsTriedAgainOnTheNextAsk() throws Exception {
        offFxInPlace();
        AtomicInteger probes = new AtomicInteger();
        List<String> learned = new CopyOnWriteArrayList<>();
        ProbeCache<String, Boolean> cache = new ProbeCache<String, Boolean>(
                        "failing-probe",
                        key -> {
                            probes.incrementAndGet();
                            throw new IllegalStateException("unreadable");
                        },
                        Boolean.TRUE)
                .onLearned(learned::add);
        assertEquals(Boolean.TRUE, onFx(() -> cache.get("k")), "the cold answer: the probe behind failed");
        assertEquals(List.of(), learned, "a probe that failed teaches the listener nothing");
        assertFalse(cache.knows("k"));
        assertEquals(Boolean.TRUE, onFx(() -> cache.get("k")));
        assertEquals(2, probes.get(), "the failed probe let go of the key, so the next ask probes again");
    }

    private static void waitUntil(java.util.function.BooleanSupplier done) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!done.getAsBoolean()) {
            assertTrue(System.nanoTime() < until, "timed out");
            onFx(() -> null); // let the FX thread run what landed
            Thread.onSpinWait();
        }
    }
}
