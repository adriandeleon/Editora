package com.editora.config;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.editora.history.HistoryBlobStore;
import com.editora.history.HistoryRetention.RetentionPolicy;
import com.editora.history.HistoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Local History index as {@link SharedConfig} publishes it, and what blob collection may delete behind a
 * publication. Two configs on one directory stand in for two Editora processes, as in {@link TwoWritersTest};
 * neither claims the instance lock, which is the state of the primary once the other process has exited.
 */
class HistoryPublicationTest {

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
        open.add(config);
        return config;
    }

    private HistoryBlobStore blobs() {
        return new HistoryBlobStore(dir.resolve("history/blobs"));
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

    /** Publishes, waits for the write, and for whatever the history worker was given because of it. */
    private static void publish(SharedConfig config) throws Exception {
        config.saveHistory();
        settle(config);
    }

    private static void settle(SharedConfig config) throws Exception {
        assertTrue(config.flushWrites());
        Field field = HistoryService.class.getDeclaredField("exec");
        field.setAccessible(true);
        ((ExecutorService) field.get(config.historyService())).submit(() -> {}).get(30, TimeUnit.SECONDS);
    }

    /** The index file without its whitespace (it is written indented). */
    private String index() throws Exception {
        return Files.readString(dir.resolve("history/index.json")).replaceAll("\\s", "");
    }

    // --- A1 ------------------------------------------------------------------------------------------------

    @Test
    void aSupersededPublicationDoesNotCollectTheOtherProcessesBodies() throws Exception {
        SharedConfig primary = open();
        record(primary, "/w/a.txt", "saved in the primary", 1000);
        publish(primary);

        SharedConfig secondary = open();
        HistoryRevision theirs = record(secondary, "/w/b.txt", "saved in the secondary", 2000);
        publish(secondary);
        secondary.shutdown(); // the other editor is closed: the primary may collect again

        String orphan = blobs().put("a body no index refers to");
        primary.historyService().requestGc(); // "ten minutes later"
        // Save All of three files: three publications back to back, the middle one superseded unwritten.
        record(primary, "/w/x.txt", "x", 3000);
        primary.saveHistory();
        record(primary, "/w/y.txt", "y", 3001);
        primary.saveHistory();
        record(primary, "/w/z.txt", "z", 3002);
        primary.saveHistory();
        settle(primary);
        settle(primary);

        assertTrue(index().contains(theirs.sha256()), "the other process's revision is still listed");
        assertEquals("saved in the secondary", blobs().get(theirs.sha256()), "so its body must still be there");
        assertNull(blobs().get(orphan), "and the collection did run, behind the publication that was written");
    }

    @Test
    void anUnchangedPublicationCollectsOnlyWhenNobodyElseWroteTheIndex() throws Exception {
        SharedConfig primary = open();
        record(primary, "/w/a.txt", "saved in the primary", 1000);
        publish(primary);

        // Nobody else: an unchanged publication may collect (the first of a later session does exactly this).
        String orphan = blobs().put("unreferenced");
        primary.historyService().requestGc();
        publish(primary);
        assertNull(blobs().get(orphan));

        SharedConfig secondary = open();
        HistoryRevision theirs = record(secondary, "/w/b.txt", "saved in the secondary", 2000);
        publish(secondary);
        secondary.shutdown();

        // The primary has nothing to write, so it does not read the file — and must not collect on what it
        // knew before the other process wrote.
        primary.historyService().requestGc();
        publish(primary);
        assertEquals("saved in the secondary", blobs().get(theirs.sha256()));

        // Its next real save merges, learns of the other rows, and the pending collection runs then.
        String later = blobs().put("unreferenced, later");
        record(primary, "/w/a.txt", "saved again", 3000);
        publish(primary);
        assertNull(blobs().get(later));
        assertEquals("saved in the secondary", blobs().get(theirs.sha256()));
    }

    // --- A9 ------------------------------------------------------------------------------------------------

    @Test
    void aPurgeRemovesTheBodyEvenInASessionThatMergedWithAnotherWriter() throws Exception {
        SharedConfig primary = open();
        HistoryRevision secret = record(primary, "/w/secret.env", "API_KEY=hunter2", 1000);
        publish(primary);

        SharedConfig secondary = open();
        HistoryRevision theirs = record(secondary, "/w/other.txt", "theirs", 2000);
        // The same text as a file of the primary: one body, two owners.
        HistoryRevision shared = record(secondary, "/w/copy.env", "shared text", 2001);
        publish(secondary);
        secondary.shutdown();

        record(primary, "/w/mine.env", "shared text", 3000); // this save merges with what the other left
        publish(primary);

        primary.historyBucket("").remove("/w/secret.env");
        primary.historyBucket("").remove("/w/mine.env");
        primary.historyService().requestGc();
        publish(primary);

        assertFalse(index().contains(secret.sha256()));
        assertNull(blobs().get(secret.sha256()), "purged: the content has left the disk");
        assertEquals("theirs", blobs().get(theirs.sha256()));
        assertEquals("shared text", blobs().get(shared.sha256()), "the other process's row still needs it");
    }

    @Test
    void foreignHashesAreTheOnesOnlyTheOtherWritersRowsReferTo() throws Exception {
        HistoryStore mine = new HistoryStore();
        mine.bucket("").put("/a", List.of(new HistoryRevision("/a", 1, 1, "own", "SAVE")));
        mine.bucket("").put("/b", List.of(new HistoryRevision("/b", 2, 1, "both", "SAVE")));
        var merged = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                        {"byProject":{"":{
                          "/a":[{"sha256":"own"}],
                          "/b":[{"sha256":"both"}],
                          "/c":[{"sha256":"both"},{"sha256":"theirs"},{"sha256":""}]}}}""");
        assertEquals(java.util.Set.of("both", "theirs"), SharedConfig.foreignHashes(merged, mine.snapshot()));
    }

    // --- A4 ------------------------------------------------------------------------------------------------

    @Test
    void aListMergedFromTwoWritersIsNewestFirstOnceLoaded() throws Exception {
        SharedConfig primary = open();
        record(primary, "/w/a.txt", "v1", 1000);
        publish(primary);
        SharedConfig secondary = open();
        record(secondary, "/w/a.txt", "v2", 2000);
        record(secondary, "/w/a.txt", "v3", 3000);
        publish(secondary);
        secondary.shutdown();
        record(primary, "/w/a.txt", "v4", 4000);
        publish(primary);
        primary.shutdown();

        SharedConfig next = open();
        List<HistoryRevision> merged = next.historyBucket("").get("/w/a.txt");
        assertEquals(
                List.of(4000L, 3000L, 2000L, 1000L),
                merged.stream().map(HistoryRevision::timestamp).toList());
        assertEquals(
                List.of(4000L, 3000L),
                com.editora.history.HistoryRetention.prune(merged, 2, 0, 5000).stream()
                        .map(HistoryRevision::timestamp)
                        .toList(),
                "the cap keeps the newest two");
        // Re-ordering is a change the load made: the next save writes it.
        publish(next);
        assertTrue(index().indexOf("\"timestamp\":4000") < index().indexOf("\"timestamp\":1000"), index());
    }

    // --- A8 ------------------------------------------------------------------------------------------------

    @Test
    void nullsInTheIndexAreDroppedInsteadOfStoppingTheLoadOrEverySave() throws Exception {
        Files.createDirectories(dir.resolve("history"));
        Files.writeString(dir.resolve("history/index.json"), """
                {"schemaVersion":4,"byProject":{
                  "":{"/w/a.txt":[null,{"path":"/w/a.txt","timestamp":5,"sizeBytes":1,"sha256":"aa","reason":"SAVE"}],
                      "/w/gone.txt":null},
                  "dead":null}}""");
        SharedConfig config = open();
        assertEquals(
                List.of("aa"),
                config.historyBucket("").get("/w/a.txt").stream()
                        .map(HistoryRevision::sha256)
                        .toList());
        assertFalse(config.historyBucket("").containsKey("/w/gone.txt"));
        assertFalse(config.historyByProject().containsKey("dead"));
        record(config, "/w/b.txt", "b", 6);
        publish(config);
        assertTrue(index().contains("/w/b.txt"));
        assertFalse(index().contains("null"), index());
    }

    // --- A3 ------------------------------------------------------------------------------------------------

    private Path damagedOnce(String damagedIndex) throws Exception {
        SharedConfig first = open();
        record(first, "/w/old.txt", "the only copy of a deleted file", 1000);
        publish(first);
        first.shutdown();
        Path index = dir.resolve("history/index.json");
        Files.writeString(
                index,
                damagedIndex == null
                        ? Files.readString(index).replaceFirst("\"timestamp\"\\s*:\\s*1000", "\"timestamp\":\"then\"")
                        : damagedIndex);
        SharedConfig second = open(); // reports the damage and keeps the bytes beside the index
        assertFalse(second.mayCollectHistoryBlobs(), "an empty index beside stored bodies is never collected against");
        record(second, "/w/new.txt", "saved after the damage", 2000);
        publish(second);
        second.shutdown();
        return index.resolveSibling("index.json.corrupt.bak");
    }

    @Test
    void aBackupOfAWholeIndexProtectsItsBodiesWithoutStoppingCollection() throws Exception {
        Path backup = damagedOnce(null);
        assertTrue(Files.exists(backup));
        String old = HistoryBlobStore.sha256("the only copy of a deleted file");

        SharedConfig later = open();
        assertTrue(later.takeLoadProblems().isEmpty());
        assertTrue(later.mayCollectHistoryBlobs(), "one damaged write must not switch collection off for good");
        HistoryRevision secret = record(later, "/w/secret.env", "API_KEY=hunter2", 3000);
        publish(later);
        later.historyBucket("").remove("/w/secret.env");
        later.historyService().requestGc();
        publish(later);

        assertNull(blobs().get(secret.sha256()), "a purge removes the content again");
        assertEquals("the only copy of a deleted file", blobs().get(old), "what the backup lists is kept");
    }

    @Test
    void aTornBackupKeepsEveryBodyForAGracePeriodThenOnlyWhatItStillLists() throws Exception {
        SharedConfig first = open();
        record(first, "/w/kept.txt", "listed before the tear", 1000);
        record(first, "/w/lost.txt", "listed after the tear", 1001);
        publish(first);
        first.shutdown();
        String whole = Files.readString(dir.resolve("history/index.json"));
        String lostHash = HistoryBlobStore.sha256("listed after the tear");
        String keptHash = HistoryBlobStore.sha256("listed before the tear");
        assertTrue(whole.indexOf(keptHash) < whole.indexOf(lostHash));
        Path backup = damagedOnce(whole.substring(0, whole.indexOf(lostHash) - 12));

        SharedConfig within = open();
        assertFalse(
                within.mayCollectHistoryBlobs(), "a torn backup cannot say what it listed: keep everything for now");
        within.shutdown();

        Files.setLastModifiedTime(
                backup,
                FileTime.fromMillis(
                        System.currentTimeMillis() - HistoryIndexGuard.INCOMPLETE_BACKUP_GRACE_MILLIS - 60_000));
        SharedConfig after = open();
        assertTrue(after.mayCollectHistoryBlobs(), "the grace period is over");
        record(after, "/w/new.txt", "another save", 3000);
        after.historyService().requestGc();
        publish(after);
        assertEquals("listed before the tear", blobs().get(keptHash), "legible in the backup: protected");
        assertNull(blobs().get(lostHash), "in no index and no backup any more");
    }

    @Test
    void aBackupThatAppearsDuringTheSessionIsNoticedByTheNextPublication() throws Exception {
        SharedConfig config = open();
        HistoryRevision old = record(config, "/w/a.txt", "recorded before the damage", 1000);
        publish(config);
        assertTrue(config.mayCollectHistoryBlobs());

        // Something else leaves the index unreadable; this process's next save cannot merge with it and
        // keeps the bytes beside the index.
        Files.writeString(
                dir.resolve("history/index.json"), "{\"byProject\":{\"\":{\"/w/z.txt\":[{\"sha256\":\"zz\"},");
        record(config, "/w/b.txt", "the next save", 2000);
        config.historyService().requestGc();
        publish(config);

        assertTrue(Files.exists(dir.resolve("history/index.json.corrupt.bak")));
        assertFalse(config.mayCollectHistoryBlobs(), "a torn copy: everything is kept for its grace period");
        assertEquals("recorded before the damage", blobs().get(old.sha256()));
        assertTrue(index().contains(old.sha256()), "and the index is this process's again");
    }

    @Test
    void backupHashesReadsWhatIsLegibleAndSaysWhenABackupIsNotWhole() throws Exception {
        Path index = Files.createDirectories(dir.resolve("history")).resolve("index.json");
        assertEquals(
                new HistoryIndexGuard.BackupHashes(java.util.Set.of(), Long.MIN_VALUE),
                HistoryIndexGuard.backupHashes(index));
        assertTrue(HistoryIndexGuard.backupHashes(index).allowsCollection(0));

        Files.writeString(
                index.resolveSibling("index.json.v9.bak"), "{\"byProject\":{\"\":{\"/a\":[{\"sha256\" : \"h1\"}]}}}");
        HistoryIndexGuard.BackupHashes whole = HistoryIndexGuard.backupHashes(index);
        assertEquals(java.util.Set.of("h1"), whole.hashes());
        assertEquals(Long.MIN_VALUE, whole.incompleteSince());

        Path torn = Files.writeString(
                index.resolveSibling("index.json.corrupt.bak"),
                "{\"byProject\":{\"\":{\"/b\":[{\"sha256\":\"h2\"},{\"sha2");
        Files.setLastModifiedTime(torn, FileTime.fromMillis(5_000));
        Path empty = Files.writeString(index.resolveSibling("index.json.corrupt.bak.2"), "");
        Files.setLastModifiedTime(empty, FileTime.fromMillis(9_000));
        HistoryIndexGuard.BackupHashes both = HistoryIndexGuard.backupHashes(index);
        assertEquals(java.util.Set.of("h1", "h2"), both.hashes());
        assertEquals(9_000, both.incompleteSince(), "the newest backup that is not a whole index");
        assertFalse(both.allowsCollection(9_000 + HistoryIndexGuard.INCOMPLETE_BACKUP_GRACE_MILLIS));
        assertTrue(both.allowsCollection(9_001 + HistoryIndexGuard.INCOMPLETE_BACKUP_GRACE_MILLIS));
        assertEquals(
                java.util.Set.of("index.json.corrupt.bak", "index.json.corrupt.bak.2", "index.json.v9.bak"),
                HistoryIndexGuard.backupNames(index));

        Files.delete(empty);
        Files.createDirectory(empty); // a backup that cannot be read at all
        assertNull(HistoryIndexGuard.backupHashes(index), "cannot tell: nothing may be collected");
        assertEquals(
                java.util.Set.of(),
                HistoryIndexGuard.backupNames(dir.resolve("nowhere").resolve("index.json")));
    }

    // --- A13 -----------------------------------------------------------------------------------------------

    @Test
    void theLimitsInForceAreKeptWithTheIndexSoARestartIsNotAWayAroundTheConfirmation() throws Exception {
        RetentionPolicy loose = new RetentionPolicy(50, 0, 0);
        RetentionPolicy strict = new RetentionPolicy(5, 0, 0);
        SharedConfig first = open();
        assertEquals(loose, first.historyService().effectivePolicy(loose), "nothing on record: adopted");
        assertFalse(Files.exists(dir.resolve("history/index.json")), "no history yet: no index is created for it");
        record(first, "/w/a.txt", "a", 1);
        publish(first);
        assertTrue(index().contains("\"acknowledgedLimits\":{\"maxPerFile\":50"), index());
        first.shutdown();

        // A stricter value arrives unconfirmed (a settings sync, an edited settings.json) and the editor restarts.
        SharedConfig second = open();
        assertEquals(loose, second.historyService().effectivePolicy(strict), "still waiting for the user");
        assertTrue(second.historyService().awaitsConfirmation(strict));
        assertFalse(second.historyService().awaitsConfirmation(loose));
        second.historyService().acknowledge(strict); // the user confirmed what it deletes
        settle(second);
        assertTrue(index().contains("\"acknowledgedLimits\":{\"maxPerFile\":5,"), index());
        second.shutdown();

        SharedConfig third = open();
        assertEquals(strict, third.historyService().effectivePolicy(strict));
        assertFalse(third.historyService().awaitsConfirmation(strict));
        // Loosening needs nobody's say-so and is recorded as well.
        assertEquals(loose, third.historyService().effectivePolicy(loose));
        settle(third);
        assertTrue(index().contains("\"acknowledgedLimits\":{\"maxPerFile\":50"), index());
    }

    @Test
    void aSchemaThreeIndexLoadsWithNoLimitsOnRecord() throws Exception {
        Files.createDirectories(dir.resolve("history"));
        Files.writeString(
                dir.resolve("history/index.json"),
                "{\"schemaVersion\":3,\"byProject\":{\"\":{\"/w/a.txt\":[{\"path\":\"/w/a.txt\",\"timestamp\":5,"
                        + "\"sizeBytes\":1,\"sha256\":\"aa\",\"reason\":\"SAVE\",\"label\":\"\"}]}}}");
        SharedConfig config = open();
        assertTrue(config.takeLoadProblems().isEmpty());
        assertNotNull(config.historyBucket("").get("/w/a.txt"));
        RetentionPolicy configured = new RetentionPolicy(7, 0, 0);
        assertFalse(config.historyService().awaitsConfirmation(configured));
        assertEquals(configured, config.historyService().effectivePolicy(configured), "adopted, as before v4");
        settle(config);
        assertTrue(index().contains("\"schemaVersion\":4"), index());
        assertTrue(index().contains("\"acknowledgedLimits\":{\"maxPerFile\":7"), index());
    }
}
