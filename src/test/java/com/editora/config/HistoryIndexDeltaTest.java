package com.editora.config;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import com.editora.history.HistoryBlobStore;
import com.editora.history.HistoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Local History index written as {@code index.json} plus a delta ({@link HistoryIndexFiles}, perf finding
 * F6): crash recovery, and two processes on one config folder. Two configs on one directory stand in for two
 * Editora processes, as in {@link TwoWritersTest}; a "crash" is a config that is simply never used again (no
 * quit, so the delta is not folded in). Each config uses the delta whatever the index's size.
 *
 * <p>The scenarios named "journal n" are the four defects found in the first attempt at F6 (an append-only
 * journal, reverted in #1757).
 */
class HistoryIndexDeltaTest {

    @TempDir
    Path dir;

    private final List<SharedConfig> open = new ArrayList<>();

    @AfterEach
    void close() {
        open.forEach(SharedConfig::shutdown);
    }

    private SharedConfig open() {
        SharedConfig config = new SharedConfig(dir, false);
        config.load();
        config.historyIndexFiles().deltaMinIndexBytes(0);
        open.add(config);
        return config;
    }

    private HistoryBlobStore blobs() {
        return new HistoryBlobStore(dir.resolve("history/blobs"));
    }

    private Path index() {
        return dir.resolve("history/index.json");
    }

    private Path delta() {
        return HistoryIndexFiles.deltaFile(index());
    }

    private HistoryRevision record(SharedConfig config, String path, String content, long timestamp) {
        HistoryRevision revision = new HistoryRevision(
                path, timestamp, content.length(), blobs().put(content), HistoryRevision.REASON_SAVE);
        config.historyBucket("").merge(path, List.of(revision), (present, added) -> {
            List<HistoryRevision> out = new ArrayList<>(added);
            out.addAll(present);
            return out;
        });
        return revision;
    }

    /** A purge (or a retention limit) dropping one row: what the history list does to the index. */
    private static void drop(SharedConfig config, HistoryRevision revision) {
        List<HistoryRevision> rows = new ArrayList<>(config.historyBucket("").get(revision.path()));
        assertTrue(rows.remove(revision));
        if (rows.isEmpty()) {
            config.historyBucket("").remove(revision.path());
        } else {
            config.historyBucket("").put(revision.path(), rows);
        }
    }

    private static List<HistoryRevision> rows(SharedConfig config, String path) {
        return config.historyBucket("").getOrDefault(path, List.of());
    }

    private static void publish(SharedConfig config) throws Exception {
        config.saveHistory();
        settle(config);
    }

    /** Waits for the writer, for the history worker, and for the FX-thread work (adopted rows) they queued. */
    private static void settle(SharedConfig config) throws Exception {
        config.flushWrites();
        Field field = HistoryService.class.getDeclaredField("exec");
        field.setAccessible(true);
        ((ExecutorService) field.get(config.historyService())).submit(() -> {}).get(30, TimeUnit.SECONDS);
    }

    /** A collection ran a moment ago: the next publications write only the delta (until one is asked for). */
    private static void noCollectionDue(SharedConfig config) throws Exception {
        HistoryService service = config.historyService();
        Field clock = HistoryService.class.getDeclaredField("nanoClock");
        clock.setAccessible(true);
        long now = ((LongSupplier) clock.get(service)).getAsLong();
        set(service, "gcHasRun", true);
        set(service, "lastGcNanos", now);
        set(service, "gcRequested", false);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** A collection is due: the next publication writes the index whole, then collects. */
    private static void compact(SharedConfig config) throws Exception {
        config.historyService().requestGc();
        publish(config);
    }

    // --- crash recovery ------------------------------------------------------------------------------------

    @Test
    void aSaveWritesOnlyTheDeltaAndARestartAfterACrashHasTheRevision() throws Exception {
        SharedConfig first = open();
        HistoryRevision one = record(first, "/w/a.txt", "one", 1000);
        publish(first); // the first publication of a session writes the index whole (a collection is due)
        byte[] indexBefore = Files.readAllBytes(index());
        HistoryRevision two = record(first, "/w/a.txt", "two", 2000);
        publish(first);

        assertArrayEquals(indexBefore, Files.readAllBytes(index()), "the save did not rewrite the index");
        assertTrue(Files.readString(delta()).contains(two.sha256()), "it wrote the delta");

        // Crash: `first` never quits. The next start lists both revisions, and its collection keeps both bodies.
        SharedConfig next = open();
        assertEquals(List.of(two, one), rows(next, "/w/a.txt"));
        compact(next);
        assertEquals("two", blobs().get(two.sha256()));
        assertEquals("one", blobs().get(one.sha256()));
        assertTrue(Files.readString(index()).contains(two.sha256()), "folded into the index");
    }

    @Test
    void aCrashBetweenTheTwoWritesOfACompactionLosesAndRepeatsNothing() throws Exception {
        SharedConfig first = open();
        HistoryRevision one = record(first, "/w/a.txt", "one", 1000);
        publish(first);
        HistoryRevision two = record(first, "/w/a.txt", "two", 2000);
        HistoryRevision three = record(first, "/w/b.txt", "three", 3000);
        drop(first, one);
        publish(first);
        byte[] staleDelta = Files.readAllBytes(delta());
        compact(first);
        // The index was written whole; the crash came before the delta was emptied.
        Files.write(delta(), staleDelta);

        SharedConfig next = open();
        assertEquals(List.of(two), rows(next, "/w/a.txt"), "no row twice, and the dropped one stays dropped");
        assertEquals(List.of(three), rows(next, "/w/b.txt"));
        compact(next);
        assertEquals("two", blobs().get(two.sha256()));
        assertEquals("three", blobs().get(three.sha256()));
    }

    // --- journal 1: rows only the delta lists are in every writer's ancestor --------------------------------

    /** A writes a row only its delta lists; B, started after, drops it; A goes on saving, then compacts. */
    private void anotherProcessDropsARowOnlyTheDeltaLists(boolean otherCompacts) throws Exception {
        SharedConfig a = open();
        HistoryRevision kept = record(a, "/w/a.txt", "kept", 1000);
        publish(a);
        HistoryRevision onlyInDelta = record(a, "/w/a.txt", "only in the delta", 2000);
        publish(a);

        SharedConfig b = open();
        assertEquals(List.of(onlyInDelta, kept), rows(b, "/w/a.txt"));
        if (!otherCompacts) {
            noCollectionDue(b);
        }
        drop(b, onlyInDelta);
        publish(b);
        b.shutdown();
        open.remove(b);

        HistoryRevision later = record(a, "/w/c.txt", "later", 3000);
        publish(a); // a delta write: A still lists the dropped row in memory
        compact(a);

        SharedConfig next = open();
        assertEquals(List.of(kept), rows(next, "/w/a.txt"), "the other process's purge is not undone");
        assertEquals(List.of(later), rows(next, "/w/c.txt"));
    }

    @Test
    void journal1_aRowOnlyTheDeltaListedStaysDroppedWhenTheOtherProcessWroteADelta() throws Exception {
        anotherProcessDropsARowOnlyTheDeltaLists(false);
    }

    @Test
    void journal1_aRowOnlyTheDeltaListedStaysDroppedWhenTheOtherProcessCompacted() throws Exception {
        anotherProcessDropsARowOnlyTheDeltaLists(true);
    }

    @Test
    void journal1_aRowTheDeltaRemovedIsNotAddedBackByAProcessThatStillListsIt() throws Exception {
        SharedConfig a = open();
        HistoryRevision purged = record(a, "/w/a.txt", "purged", 1000);
        HistoryRevision kept = record(a, "/w/b.txt", "kept", 1000);
        publish(a); // both in index.json

        SharedConfig b = open(); // lists `purged`
        noCollectionDue(b);

        drop(a, purged);
        publish(a); // the delta removes it

        HistoryRevision theirs = record(b, "/w/c.txt", "theirs", 2000);
        publish(b); // merges deltas
        compact(b); // and then whole indexes
        b.shutdown();
        open.remove(b);

        SharedConfig next = open();
        assertEquals(List.of(), rows(next, "/w/a.txt"), "the purge stands");
        assertEquals(List.of(kept), rows(next, "/w/b.txt"));
        assertEquals(List.of(theirs), rows(next, "/w/c.txt"));
    }

    @Test
    void journal1_aProcessWhoseIndexWasCompactedByAnotherRecordsItsPurgeAgainstTheNewIndex() throws Exception {
        SharedConfig a = open();
        HistoryRevision kept = record(a, "/w/a.txt", "kept", 1000);
        publish(a);
        HistoryRevision folded = record(a, "/w/a.txt", "folded by the other", 2000);
        publish(a); // only the delta lists it

        SharedConfig b = open();
        HistoryRevision theirs = record(b, "/w/b.txt", "theirs", 3000);
        compact(b); // folds `folded` into a new index.json
        b.shutdown();
        open.remove(b);

        drop(a, folded);
        publish(a); // index.json is not the one A knows: merged whole, written as a delta on the new index
        settle(a);
        assertEquals(List.of(theirs), rows(a, "/w/b.txt"), "the other process's row is taken in");

        SharedConfig next = open();
        assertEquals(List.of(kept), rows(next, "/w/a.txt"), "the purge is not undone by the compaction");
        assertEquals(List.of(theirs), rows(next, "/w/b.txt"));
    }

    @Test
    void twoProcessesWritingDeltasKeepEachOthersRows() throws Exception {
        SharedConfig a = open();
        record(a, "/w/a.txt", "base", 1000);
        publish(a);
        SharedConfig b = open();
        noCollectionDue(b);

        HistoryRevision fromA = record(a, "/w/x.txt", "from a", 2000);
        publish(a);
        HistoryRevision fromB = record(b, "/w/x.txt", "from b", 2001);
        publish(b);
        settle(b);
        assertEquals(List.of(fromB, fromA), rows(b, "/w/x.txt"), "B took in A's row");

        SharedConfig next = open();
        assertEquals(List.of(fromB, fromA), rows(next, "/w/x.txt"));
        // A collection in A (alone again) keeps B's body although A never listed it before the merge.
        b.shutdown();
        open.remove(b);
        compact(a);
        assertEquals("from b", blobs().get(fromB.sha256()));
    }

    // --- journal 2: a delta that cannot be read now -----------------------------------------------------------

    @Test
    void journal2_aDeltaThatCannotBeReadAtLoadIsNeitherCollectedAgainstNorReplaced() throws Exception {
        SharedConfig a = open();
        record(a, "/w/a.txt", "base", 1000);
        publish(a);
        HistoryRevision onlyInDelta = record(a, "/w/a.txt", "only in the delta", 2000);
        publish(a);
        byte[] deltaBefore = Files.readAllBytes(delta());

        assumeTrue(Files.getFileStore(delta()).supportsFileAttributeView("posix"));
        Files.setPosixFilePermissions(delta(), PosixFilePermissions.fromString("---------"));
        assumeTrue(!Files.isReadable(delta()), "running as a user who can read anything");
        SharedConfig b;
        try {
            b = open();
            assertFalse(b.canCollectHistoryBlobs(), "what the delta lists is unknown: nothing is collected");
            HistoryRevision meanwhile = record(b, "/w/b.txt", "meanwhile", 3000);
            b.historyService().requestGc();
            b.saveHistory();
            b.flushWrites(); // fails: the delta cannot be read, so it is not replaced
            assertEquals("only in the delta", blobs().get(onlyInDelta.sha256()));
            assertEquals("meanwhile", blobs().get(meanwhile.sha256()));
        } finally {
            Files.setPosixFilePermissions(delta(), PosixFilePermissions.fromString("rw-------"));
        }
        assertArrayEquals(deltaBefore, Files.readAllBytes(delta()), "the delta was left as it was");

        // Readable again: the next publication reads it, takes its rows in, and may collect.
        compact(b);
        settle(b);
        assertTrue(b.canCollectHistoryBlobs());
        assertTrue(rows(b, "/w/a.txt").contains(onlyInDelta), "its rows are listed again");
        assertEquals("only in the delta", blobs().get(onlyInDelta.sha256()), "and its body was kept");
        SharedConfig next = open();
        assertTrue(rows(next, "/w/a.txt").contains(onlyInDelta));
        assertNotNull(rows(next, "/w/b.txt"));
    }

    // --- journal 3: a damaged delta is set aside once, not replayed ------------------------------------------

    @Test
    void journal3_aDamagedDeltaIsKeptAsACopyThatProtectsItsBodiesAndIsNotReplayed() throws Exception {
        SharedConfig a = open();
        HistoryRevision base = record(a, "/w/a.txt", "base", 1000);
        publish(a);
        HistoryRevision onlyInDelta = record(a, "/w/a.txt", "only in the delta", 2000);
        publish(a);
        String text = Files.readString(delta());
        Files.writeString(delta(), text.substring(0, text.length() / 2)); // damaged by hand or by a sync tool

        SharedConfig b = open();
        assertEquals(List.of(base), rows(b, "/w/a.txt"), "a delta that cannot be read lists nothing");
        Path copy = delta().resolveSibling(delta().getFileName() + ".corrupt.bak");
        assertTrue(Files.exists(copy), "its bytes were kept");
        compact(b);
        assertEquals("only in the delta", blobs().get(onlyInDelta.sha256()), "the copy keeps its bodies");
        HistoryDelta now =
                HistoryDelta.fromBytes(Files.readAllBytes(delta()), new com.fasterxml.jackson.databind.ObjectMapper());
        assertTrue(now.isEmpty(), "the delta was replaced");

        drop(b, base);
        publish(b);
        b.shutdown();
        open.remove(b);
        SharedConfig next = open();
        assertEquals(List.of(), rows(next, "/w/a.txt"), "nothing comes back from the damaged delta");
    }

    @Test
    void journal3_aPurgeInTheDeltaStaysThroughRestarts() throws Exception {
        SharedConfig a = open();
        HistoryRevision base = record(a, "/w/a.txt", "base", 1000);
        publish(a);
        HistoryRevision purged = record(a, "/w/a.txt", "purged", 2000);
        publish(a);
        drop(a, purged);
        drop(a, base);
        publish(a);

        for (int restart = 0; restart < 3; restart++) {
            SharedConfig next = open();
            noCollectionDue(next);
            assertEquals(List.of(), rows(next, "/w/a.txt"), "restart " + restart);
            record(next, "/w/n" + restart + ".txt", "n" + restart, 3000 + restart);
            publish(next);
        }
    }

    // --- journal 4: the quit folds the delta in, whatever is queued ------------------------------------------

    @Test
    void journal4_theQuitFoldsTheDeltaInEvenWithASaveStillQueued() throws Exception {
        SharedConfig a = open();
        record(a, "/w/a.txt", "base", 1000);
        publish(a);
        HistoryRevision delta = record(a, "/w/a.txt", "in the delta", 2000);
        publish(a);
        HistoryRevision queued = record(a, "/w/b.txt", "queued at quit", 3000);
        a.saveHistory(); // not waited for
        a.shutdown();
        open.remove(a);

        String index = Files.readString(index());
        assertTrue(index.contains(delta.sha256()) && index.contains(queued.sha256()), "an older build sees both");
        HistoryDelta left =
                HistoryDelta.fromBytes(Files.readAllBytes(delta()), new com.fasterxml.jackson.databind.ObjectMapper());
        assertTrue(left.isEmpty(), "the delta was folded in");
    }
}
