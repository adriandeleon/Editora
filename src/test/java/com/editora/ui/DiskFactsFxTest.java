package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DiskFacts}: the FX thread answers from memory and never looks itself (PERF-01, Pauta 6.2.0); a look lands
 * later and tells the asker; a change on disk is seen once the kept answer is old; a failed look teaches nothing.
 */
@Tag("fx")
class DiskFactsFxTest {

    @TempDir
    Path dir;

    private final List<String> onFx = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() {
        BlockingSites.listenForTest(null);
    }

    private void listen() {
        BlockingSites.listenForTest((site, thread) -> {
            if (javafx.application.Platform.isFxApplicationThread()) {
                onFx.add(site);
            }
        });
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        com.editora.testkit.Await.holdsWithin(condition, Duration.ofSeconds(5));
        FxTestSupport.drainFx();
        assertTrue(condition.getAsBoolean(), "timed out");
    }

    @Test
    void anUnknownKeyAnswersNullOnFxAndTheLookLandsLater() throws Exception {
        listen();
        AtomicInteger learned = new AtomicInteger();
        DiskFacts<Path, Boolean> facts =
                FxTestSupport.callOnFx(() -> new DiskFacts<Path, Boolean>("test.isDirectory", Files::isDirectory)
                        .onLearned(learned::incrementAndGet));

        assertNull(FxTestSupport.callOnFx(() -> facts.get(dir)), "nothing known yet: no look on the FX thread");
        waitFor(() -> learned.get() == 1);
        assertEquals(Boolean.TRUE, FxTestSupport.callOnFx(() -> facts.get(dir)));
        assertTrue(onFx.isEmpty(), "never looked on the FX thread: " + onFx);
    }

    @Test
    void aChangeOnDiskIsSeenOnceTheAnswerIsOld() throws Exception {
        Path folder = dir.resolve("later");
        AtomicInteger learned = new AtomicInteger();
        AtomicLong now = new AtomicLong(System.nanoTime());
        DiskFacts<Path, Boolean> facts =
                FxTestSupport.callOnFx(() -> new DiskFacts<Path, Boolean>("test.isDirectory", Files::isDirectory)
                        .clockForTest(now::get)
                        .onLearned(learned::incrementAndGet));
        FxTestSupport.callOnFx(() -> facts.get(folder));
        waitFor(() -> learned.get() == 1);
        assertEquals(Boolean.FALSE, FxTestSupport.callOnFx(() -> facts.get(folder)));

        Files.createDirectory(folder);
        now.addAndGet(DiskFacts.FRESH_NANOS + 50_000_000L); // the answer is now older than it may be
        assertEquals(
                Boolean.FALSE, FxTestSupport.callOnFx(() -> facts.get(folder)), "the old answer, asked again behind");
        waitFor(() -> learned.get() == 2);
        assertEquals(Boolean.TRUE, FxTestSupport.callOnFx(() -> facts.get(folder)));
    }

    @Test
    void anAnswerEqualToWhatIsAssumedTellsNobody() throws Exception {
        AtomicInteger learned = new AtomicInteger();
        DiskFacts<Path, Boolean> facts =
                FxTestSupport.callOnFx(() -> new DiskFacts<Path, Boolean>("test.isDirectory", Files::isDirectory)
                        .assuming(Boolean.FALSE)
                        .onLearned(learned::incrementAndGet));
        FxTestSupport.callOnFx(() -> facts.get(dir.resolve("none")));
        waitFor(() -> {
            try {
                return FxTestSupport.callOnFx(() -> facts.knows(dir.resolve("none")));
            } catch (Exception e) {
                return false;
            }
        });
        FxTestSupport.drainFx();
        assertEquals(0, learned.get(), "a missing folder was assumed missing: no rebuild");
    }

    @Test
    void aFailedLookTeachesNothingAndIsAskedAgain() throws Exception {
        AtomicInteger looks = new AtomicInteger();
        DiskFacts<Path, Boolean> facts = FxTestSupport.callOnFx(() -> new DiskFacts<Path, Boolean>("test.fails", p -> {
            looks.incrementAndGet();
            throw new IllegalStateException("unreadable");
        }));
        FxTestSupport.callOnFx(() -> facts.get(dir));
        waitFor(() -> looks.get() == 1);
        FxTestSupport.drainFx();
        assertFalse(FxTestSupport.callOnFx(() -> facts.knows(dir)));
        FxTestSupport.callOnFx(() -> facts.get(dir));
        waitFor(() -> looks.get() == 2);
    }

    @Test
    void aLookRunInPlaceAnswersAtOnceWithoutCallingBack() throws Exception {
        com.editora.perf.OffFx.runnerForTest((name, task) -> task.run());
        try {
            AtomicInteger learned = new AtomicInteger();
            DiskFacts<Path, Boolean> facts =
                    FxTestSupport.callOnFx(() -> new DiskFacts<Path, Boolean>("test.isDirectory", Files::isDirectory)
                            .onLearned(learned::incrementAndGet));
            assertEquals(Boolean.TRUE, FxTestSupport.callOnFx(() -> facts.get(dir)));
            assertEquals(0, learned.get(), "the asker has the answer already");
        } finally {
            com.editora.perf.OffFx.runnerForTest(null);
        }
    }
}
