package com.editora.config;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.history.HistoryBlobStore;
import com.editora.history.HistoryMoves;
import com.editora.history.HistoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Local History engine fixes of the second review: superseded publications, two writers, the purge hold,
 * lost indexes, clocks set ahead. Two configs on one directory stand in for two Editora processes.
 */
class HistoryEngineFixesTest {

    @TempDir
    Path dir;

    private final List<SharedConfig> open = new ArrayList<>();

    @AfterEach
    void close() {
        open.forEach(SharedConfig::shutdown);
    }

    private SharedConfig open() {
        SharedConfig config = new SharedConfig(dir, false);
        // The other writer's rows are taken in where the publication hands them: right here, not on an FX thread
        // another test in this JVM may have started, which settle() does not wait for.
        config.setFxThreadForTest(Runnable::run);
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

    private static void settle(SharedConfig config) throws Exception {
        assertTrue(config.flushWrites());
        Field field = HistoryService.class.getDeclaredField("exec");
        field.setAccessible(true);
        ((ExecutorService) field.get(config.historyService())).submit(() -> {}).get(30, TimeUnit.SECONDS);
    }

    private static void publish(SharedConfig config) throws Exception {
        config.saveHistory();
        settle(config);
    }

    private Path index() {
        return dir.resolve("history/index.json");
    }

    // --- E1 ---------------------------------------------------------------------------------------------------

    @Test
    void aSupersededPublicationIsAnsweredByTheWriteThatReplacedIt() throws Exception {
        SharedConfig config = open();
        Field field = SharedConfig.class.getDeclaredField("writer");
        field.setAccessible(true);
        ConfigWriter writer = (ConfigWriter) field.get(config);
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        writer.enqueue(dir.resolve("other.json"), () -> {
            busy.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{}".getBytes();
        });
        assertTrue(busy.await(10, TimeUnit.SECONDS));
        AtomicReference<Boolean> first = new AtomicReference<>();
        AtomicReference<Boolean> second = new AtomicReference<>();
        record(config, "/w/a.txt", "a copy taken before an agent write", 1000);
        config.saveHistory(first::set);
        record(config, "/w/b.txt", "an autosave in between", 2000);
        config.saveHistory(second::set);
        release.countDown();
        settle(config);

        assertEquals(Boolean.TRUE, second.get());
        assertEquals(Boolean.TRUE, first.get(), "its row is on disk, written by the publication that replaced it");
        assertTrue(Files.readString(index()).contains("/w/a.txt"));
    }

    @Test
    void aSupersededAnswerWaitsForTheNextFinishedPublicationOrTakesOneThatFinishedAlready() {
        HistoryPublicationAnswers answers = new HistoryPublicationAnswers();
        AtomicReference<Boolean> one = new AtomicReference<>();
        assertEquals(null, answers.superseded(1, one::set));
        assertTrue(answers.finished(1, true).isEmpty(), "only publications taken before it are answered by it");
        List<java.util.function.Consumer<Boolean>> due = answers.finished(2, false);
        assertEquals(1, due.size());
        due.forEach(answer -> answer.accept(false));
        assertEquals(Boolean.FALSE, one.get(), "the replacing write failed");
        assertEquals(Boolean.FALSE, answers.superseded(1, b -> {}), "told late: a later publication already finished");
        answers.finished(3, true);
        assertEquals(Boolean.TRUE, answers.superseded(2, b -> {}));
    }

    // --- E3, A7 ----------------------------------------------------------------------------------------------

    @Test
    void aRenameHereKeepsTheRevisionsAnotherProcessRecordedForTheOldName() throws Exception {
        SharedConfig primary = open();
        record(primary, "/w/f.txt", "one", 1000);
        publish(primary);
        SharedConfig secondary = open();
        HistoryRevision theirs = record(secondary, "/w/f.txt", "two, from the other editor", 2000);
        publish(secondary);
        secondary.shutdown();
        open.remove(secondary);

        HistoryMoves.rename(primary.historyByProject(), "/w/f.txt", "/w/g.txt", "/");
        primary.historyService().requestGc();
        publish(primary);

        assertTrue(Files.readString(index()).contains(theirs.sha256()), "the other process's revision is listed");
        assertEquals("two, from the other editor", blobs().get(theirs.sha256()), "and its body is kept");
    }

    @Test
    void theOtherWritersRowsAreTakenInSoTheNextSaveIsAPlainWrite() throws Exception {
        SharedConfig primary = open();
        record(primary, "/w/a.txt", "mine", 1000);
        publish(primary);
        SharedConfig secondary = open();
        HistoryRevision theirs = record(secondary, "/w/b.txt", "theirs", 2000);
        publish(secondary);
        secondary.shutdown();
        open.remove(secondary);

        record(primary, "/w/a.txt", "mine again", 3000); // merges with what the other process wrote
        publish(primary);
        assertEquals(List.of(theirs), primary.historyBucket("").get("/w/b.txt"), "listed here now");

        record(primary, "/w/c.txt", "and a later save", 4000);
        publish(primary);
        Field field = SharedConfig.class.getDeclaredField("historyFiles");
        field.setAccessible(true);
        HistoryIndexFiles files = (HistoryIndexFiles) field.get(primary);
        Field pure = HistoryIndexFiles.class.getDeclaredField("pure");
        pure.setAccessible(true);
        assertTrue((Boolean) pure.get(files), "the file holds this process's index and nothing else again");
    }

    // --- E4 --------------------------------------------------------------------------------------------------

    @Test
    void thePurgeHoldNamesWhatKeepsTheBodies() throws Exception {
        SharedConfig config = open();
        HistoryRevision listed = record(config, "/w/a.txt", "secret", 1000);
        publish(config);
        assertEquals(HistoryPurgeHold.NONE, config.historyPurgeHold(List.of(listed.sha256())));
        config.shutdown();
        open.remove(config);

        // A whole copy of the index kept beside it lists the body.
        Files.copy(index(), dir.resolve("history/index.json.corrupt.bak"));
        SharedConfig withBackup = open();
        assertEquals(HistoryPurgeHold.BACKUP, withBackup.historyPurgeHold(List.of(listed.sha256())));
        assertEquals(HistoryPurgeHold.NONE, withBackup.historyPurgeHold(List.of("not-in-the-backup")));
        withBackup.shutdown();
        open.remove(withBackup);
        Files.delete(dir.resolve("history/index.json.corrupt.bak"));

        // Another writer's rows on disk list it.
        SharedConfig mine = open();
        mine.historyBucket("").clear();
        publish(mine);
        SharedConfig other = open();
        HistoryRevision theirs = record(other, "/w/b.txt", "theirs", 2000);
        publish(other);
        other.shutdown();
        open.remove(other);
        record(mine, "/w/c.txt", "merges", 3000);
        mine.saveHistory();
        mine.flushWrites();
        assertEquals(HistoryPurgeHold.OTHER_PROCESS, mine.historyPurgeHold(List.of(theirs.sha256())));
    }

    @Test
    void anIndexThatListsNothingBesideBodiesIsUntrustedForTheGracePeriod() throws Exception {
        String body = blobs().put("a body from before");
        Files.createDirectories(index().getParent());
        Files.writeString(index(), "{\"schemaVersion\":4,\"byProject\":{}}");
        SharedConfig config = open();
        assertFalse(config.mayCollectHistoryBlobs());
        assertEquals(HistoryPurgeHold.INDEX_UNTRUSTED, config.historyPurgeHold(List.of("x")));
        record(config, "/w/a.txt", "a new revision", 1000);
        config.historyService().requestGc();
        publish(config);
        assertNotNull(blobs().get(body), "a new revision does not make the old bodies collectable at once");
    }

    // --- A3 --------------------------------------------------------------------------------------------------

    @Test
    void aZeroLengthIndexKeepsTheBodiesForTheGracePeriod() throws Exception {
        String body = blobs().put("a body whose row was lost");
        Files.createDirectories(index().getParent());
        Files.writeString(index(), "");
        SharedConfig config = open();
        assertTrue(Files.exists(dir.resolve("history/index.json.lost.bak")));
        assertFalse(config.mayCollectHistoryBlobs());
        record(config, "/w/a.txt", "next", 1000);
        config.historyService().requestGc();
        publish(config);
        assertNotNull(blobs().get(body), "kept for the grace period");
    }

    @Test
    void aMissingIndexGetsTheSameGraceAsATornOne() throws Exception {
        String body = blobs().put("a body whose index was deleted");
        SharedConfig first = open();
        assertTrue(Files.exists(dir.resolve("history/index.json.lost.bak")));
        record(first, "/w/a.txt", "a new revision", 1000);
        publish(first);
        first.shutdown();
        open.remove(first);

        SharedConfig next = open(); // the index lists a revision now: before, this session collected everything
        assertFalse(next.mayCollectHistoryBlobs());
        record(next, "/w/a.txt", "another", 2000);
        next.historyService().requestGc();
        publish(next);
        assertNotNull(blobs().get(body));
        next.shutdown();
        open.remove(next);

        Files.setLastModifiedTime(
                dir.resolve("history/index.json.lost.bak"),
                FileTime.fromMillis(
                        System.currentTimeMillis() - HistoryIndexGuard.INCOMPLETE_BACKUP_GRACE_MILLIS - 60_000));
        SharedConfig after = open();
        assertTrue(after.mayCollectHistoryBlobs(), "the grace period is over");
        assertFalse(Files.exists(dir.resolve("history/index.json.lost.bak")), "and the marker is gone");
    }

    @Test
    void aZeroLengthBackupLeftByAnOlderBuildDoesNotStopCollection() throws Exception {
        SharedConfig first = open();
        record(first, "/w/a.txt", "kept", 1000);
        publish(first);
        first.shutdown();
        open.remove(first);
        Files.writeString(dir.resolve("history/index.json.corrupt.bak"), "");
        SharedConfig next = open();
        assertTrue(next.mayCollectHistoryBlobs());
    }

    // --- E-L3 ------------------------------------------------------------------------------------------------

    @Test
    void aRevisionStampedByAClockSetAheadIsBroughtBackToNowOnLoad() throws Exception {
        long now = System.currentTimeMillis();
        SharedConfig first = open();
        record(first, "/w/a.txt", "saved while the clock said next year", now + 365L * 86_400_000L);
        record(first, "/w/a.txt", "saved yesterday", now - 86_400_000L);
        publish(first);
        first.shutdown();
        open.remove(first);

        SharedConfig next = open();
        List<HistoryRevision> list = next.historyBucket("").get("/w/a.txt");
        assertNotNull(list);
        assertTrue(list.get(0).timestamp() <= System.currentTimeMillis(), "no revision is in the future");
        assertTrue(list.get(0).timestamp() >= now);
        assertEquals(
                HistoryBlobStore.sha256("saved while the clock said next year"),
                list.get(0).sha256());
    }

    // --- contract 3 ------------------------------------------------------------------------------------------

    @Test
    void anIndexWithTheNewReasonsLoads() throws Exception {
        SharedConfig first = open();
        String[] reasons = {
            HistoryRevision.REASON_AGENT,
            HistoryRevision.REASON_HTTP,
            HistoryRevision.REASON_RESTORE,
            HistoryRevision.REASON_REVERT,
            HistoryRevision.REASON_LINK_UPDATE
        };
        List<HistoryRevision> rows = new ArrayList<>();
        for (int i = 0; i < reasons.length; i++) {
            rows.add(new HistoryRevision("/w/a.txt", 5000 - i, 1, blobs().put("r" + i), reasons[i]));
        }
        first.historyBucket("").put("/w/a.txt", rows);
        publish(first);
        first.shutdown();
        open.remove(first);

        SharedConfig next = open();
        assertEquals(rows, next.historyBucket("").get("/w/a.txt"));
        assertTrue(next.takeLoadProblems().isEmpty());
    }
}
