package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A buffer's server root is looked for off the FX thread (PERF-01, Pauta 6.2.0): the FX thread answers "not known
 * yet" for a key nobody looked at, and the asker hears when it lands; a kept answer is given at once.
 */
@Tag("fx")
class LspRootMemoFxTest {

    private static final Path FILE = Path.of("/p/src/A.java");
    private static final LspRootMemo.Key KEY = new LspRootMemo.Key(null, FILE, "java", null);

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void stopListening() {
        BlockingSites.listenForTest(null);
    }

    @Test
    void aColdKeyAnswersNullOnFxAndTheLookRunsOffItThenTheAnswerIsKept() throws Exception {
        List<Thread> lookedOn = new CopyOnWriteArrayList<>();
        CountDownLatch learned = new CountDownLatch(1);
        List<Path> heard = new CopyOnWriteArrayList<>();
        LspRootMemo memo = new LspRootMemo(
                (key, head) -> {
                    lookedOn.add(Thread.currentThread());
                    return Path.of("/p");
                },
                file -> {
                    heard.add(file);
                    learned.countDown();
                });
        AtomicReference<Path> first = new AtomicReference<>(Path.of("unset"));
        FxTestSupport.runOnFx(() -> first.set(memo.root(KEY, "")));
        assertNull(first.get(), "not known yet on the FX thread");
        assertTrue(learned.await(5, SECONDS), "the asker hears when it lands");
        assertEquals(List.of(FILE), heard);
        assertEquals(1, lookedOn.size());
        assertFalse(lookedOn.get(0).getName().contains("JavaFX"), "looked off the FX thread");
        AtomicReference<Path> second = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> second.set(memo.root(KEY, "")));
        assertEquals(Path.of("/p"), second.get(), "kept: answered at once");
        assertEquals(1, lookedOn.size(), "no second look for a fresh answer");
    }

    @Test
    void twoAsksBeforeTheAnswerLookOnce() throws Exception {
        AtomicInteger looks = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch learned = new CountDownLatch(1);
        LspRootMemo memo = new LspRootMemo(
                (key, head) -> {
                    looks.incrementAndGet();
                    try {
                        release.await(5, SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null; // no marker anywhere
                },
                file -> learned.countDown());
        FxTestSupport.runOnFx(() -> {
            memo.root(KEY, "");
            memo.root(KEY, "");
        });
        release.countDown();
        assertTrue(learned.await(5, SECONDS));
        FxTestSupport.drainFx();
        assertEquals(1, looks.get());
        AtomicReference<Boolean> knows = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> knows.set(memo.knows(KEY)));
        assertTrue(knows.get(), "\"no root\" is an answer too, kept");
    }

    @Test
    void aFailedFirstLookAnswersNoRootSoTheAskerGoesOn() throws Exception {
        CountDownLatch learned = new CountDownLatch(1);
        LspRootMemo memo = new LspRootMemo(
                (key, head) -> {
                    throw new IllegalStateException("unreadable");
                },
                file -> learned.countDown());
        FxTestSupport.runOnFx(() -> memo.root(KEY, ""));
        assertTrue(learned.await(5, SECONDS), "the asker is not left waiting");
        AtomicReference<Path> again = new AtomicReference<>(Path.of("unset"));
        FxTestSupport.runOnFx(() -> again.set(memo.root(KEY, "")));
        assertNull(again.get());
    }

    @Test
    void offTheFxThreadItLooksInPlace() {
        LspRootMemo memo = new LspRootMemo((key, head) -> Path.of("/q"), file -> {});
        assertEquals(Path.of("/q"), memo.root(KEY, ""));
        assertFalse(Platform.isFxApplicationThread());
    }
}
