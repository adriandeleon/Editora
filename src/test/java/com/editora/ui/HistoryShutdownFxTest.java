package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.config.HistoryRevision;
import com.editora.config.SharedConfig;
import com.editora.history.HistoryBlobStore;
import com.editora.history.HistoryRetention.RetentionPolicy;
import com.editora.history.HistoryService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2: a revision recorded just before the application stops — the save made from the quit prompt, a label, a
 * pre-delete copy — was still on the history worker, or on its way back to the FX thread, when
 * {@code App.stop()} shut the configuration down. The worker was stopped with {@code shutdownNow} and the
 * index writer had been closed first, so the body was written (or not even that) and the index never listed
 * it.
 */
@Tag("fx")
class HistoryShutdownFxTest {

    private static final RetentionPolicy POLICY = new RetentionPolicy(50, 0, 0);

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Submits ten records the way the coordinator does: fold the revision into the index, publish it. */
    private static List<String> submitTen(SharedConfig shared, AtomicInteger delivered) {
        HistoryService service = shared.historyService();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String file = "/w/file" + i + ".txt";
            String content = "saved just before quitting " + i + "\n".repeat(50_000);
            hashes.add(HistoryBlobStore.sha256(content));
            service.snapshotWithOutcome(
                    Path.of(file),
                    content,
                    HistoryRevision.REASON_SAVE,
                    "",
                    false,
                    List.of(),
                    POLICY,
                    1000 + i,
                    outcome -> {
                        delivered.incrementAndGet();
                        if (outcome.revision() != null) {
                            shared.historyBucket("").put(file, List.of(outcome.revision()));
                            shared.saveHistory();
                        }
                    });
        }
        return hashes;
    }

    private static void assertAllRecorded(Path dir, List<String> hashes, AtomicInteger delivered) throws Exception {
        assertEquals(10, delivered.get(), "every record was handed back to its caller");
        String index = Files.readString(dir.resolve("history").resolve("index.json"));
        HistoryBlobStore blobs = new HistoryBlobStore(dir.resolve("history").resolve("blobs"));
        for (String hash : hashes) {
            assertTrue(index.contains(hash), "the index lists " + hash);
            assertTrue(blobs.get(hash) != null, "and its body is stored");
        }
    }

    @Test
    void recordsInFlightWhenTheApplicationStopsStillReachTheIndex(@TempDir Path dir) throws Exception {
        SharedConfig shared = new SharedConfig(dir, false);
        shared.load();
        AtomicInteger delivered = new AtomicInteger();
        List<List<String>> hashes = new ArrayList<>();
        boolean[] durable = new boolean[1];
        FxTestSupport.runOnFx(() -> {
            hashes.add(submitTen(shared, delivered));
            durable[0] = shared.shutdown(); // what App.stop() does, on the FX thread, right after the last save
        });
        assertTrue(durable[0]);
        assertAllRecorded(dir, hashes.get(0), delivered);
    }

    @Test
    void aShutdownFromAnotherThreadWaitsForTheFxThreadToTakeTheResults(@TempDir Path dir) throws Exception {
        SharedConfig shared = new SharedConfig(dir, false);
        shared.load();
        AtomicInteger delivered = new AtomicInteger();
        List<List<String>> hashes = new ArrayList<>();
        FxTestSupport.runOnFx(() -> hashes.add(submitTen(shared, delivered)));
        assertTrue(shared.shutdown());
        assertAllRecorded(dir, hashes.get(0), delivered);
    }

    @Test
    void aRecordSubmittedAfterShutdownIsReportedAsNotRecorded(@TempDir Path dir) throws Exception {
        SharedConfig shared = new SharedConfig(dir, false);
        shared.load();
        shared.shutdown();
        AtomicInteger failed = new AtomicInteger();
        FxTestSupport.runOnFx(() -> shared.historyService()
                .snapshotWithOutcome(
                        Path.of("/w/late.txt"),
                        "late",
                        HistoryRevision.REASON_SAVE,
                        "",
                        false,
                        List.of(),
                        POLICY,
                        1,
                        outcome -> {
                            if (!outcome.successful() && outcome.revision() == null) {
                                failed.incrementAndGet();
                            }
                        }));
        FxTestSupport.runOnFx(() -> {});
        assertEquals(1, failed.get());
    }
}
