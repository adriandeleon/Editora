package com.editora.config;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.editora.history.HistoryBlobStore;
import com.editora.history.HistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Local History index journal (F6): a publication of a big index appends what changed and syncs it instead
 * of rewriting the whole index, and a restart after a crash gets every reported-durable revision back.
 */
class HistoryJournalTest {

    @TempDir
    Path dir;

    private final List<SharedConfig> open = new ArrayList<>();

    @AfterEach
    void close() {
        open.forEach(SharedConfig::shutdown);
    }

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static HistoryRevision row(String path, long ts, String sha) {
        return new HistoryRevision(path, ts, 1, sha, HistoryRevision.REASON_SAVE);
    }

    private static String fakeSha(int i) {
        return String.format("%064x", i);
    }

    // --- the change between two snapshots, and applying it -------------------------------------------------------

    @Test
    void aChangeAppliedToTheOldIndexGivesTheNewOne() {
        HistoryStore store = new HistoryStore();
        HistoryRevision a1 = row("/w/a", 10, fakeSha(1));
        HistoryRevision b1 = row("/w/b", 10, fakeSha(2));
        HistoryRevision b2 = row("/w/b", 20, fakeSha(3));
        store.bucket("").put("/w/a", List.of(a1));
        store.bucket("").put("/w/b", List.of(b2, b1));
        store.bucket("p").put("/p/c", List.of(row("/p/c", 5, fakeSha(4))));
        HistoryStore.Snapshot before = store.snapshot();

        HistoryRevision a2 = row("/w/a", 30, fakeSha(5));
        store.bucket("").put("/w/a", List.of(a2, a1)); // a save
        store.bucket("").put("/w/b", List.of(b2.withLabel("named"), b1)); // a label: one row for another
        store.bucket("").put("/w/new", List.of(row("/w/new", 40, fakeSha(6))));
        HistoryStore.Snapshot after = store.snapshot();

        List<HistoryJournal.Change> changes = HistoryJournal.diff(before, after);
        assertNotNull(changes);
        assertEquals(3, changes.size(), "the untouched project is not looked at: " + changes);

        HistoryStore replayed = copyOf(before);
        changes.forEach(change -> HistoryJournal.apply(replayed, change));
        assertEquals(after.byProject(), replayed.snapshot().byProject());

        // Applied again — a journal left behind by a crash after the index was written whole — nothing changes.
        changes.forEach(change -> HistoryJournal.apply(replayed, change));
        assertEquals(after.byProject(), replayed.snapshot().byProject());
    }

    @Test
    void removalsAndADroppedProjectAreChangesTooButLimitsAreNot() {
        HistoryStore store = new HistoryStore();
        HistoryRevision old = row("/w/a", 10, fakeSha(1));
        HistoryRevision coalesced = row("/w/a", 20, fakeSha(2));
        store.bucket("").put("/w/a", List.of(coalesced, old));
        store.bucket("gone").put("/g/x", List.of(row("/g/x", 1, fakeSha(3))));
        HistoryStore.Snapshot before = store.snapshot();
        assertEquals(List.of(), HistoryJournal.diff(before, store.snapshot()), "unchanged: nothing to append");

        store.bucket("").put("/w/a", List.of(old)); // an auto-save replaced by the next one
        store.getByProject().remove("gone");
        HistoryStore.Snapshot after = store.snapshot();
        List<HistoryJournal.Change> changes = HistoryJournal.diff(before, after);
        assertNotNull(changes);
        HistoryStore replayed = copyOf(before);
        changes.forEach(change -> HistoryJournal.apply(replayed, change));
        assertEquals(List.of(old), replayed.getByProject().get("").get("/w/a"));
        assertTrue(replayed.getByProject().getOrDefault("gone", Map.of()).isEmpty(), "a dropped project's rows go");

        store.setAcknowledgedLimits(new HistoryStore.Limits(1, 2, 3));
        assertNull(HistoryJournal.diff(after, store.snapshot()), "not about rows: the index is written whole");
    }

    @Test
    void anotherWritersRowsAreLeftAlone() {
        HistoryStore mine = new HistoryStore();
        HistoryRevision own = row("/w/a", 10, fakeSha(1));
        mine.bucket("").put("/w/a", List.of(own));
        HistoryStore.Snapshot before = mine.snapshot();
        HistoryRevision newer = row("/w/a", 20, fakeSha(2));
        mine.bucket("").put("/w/a", List.of(newer, own));
        List<HistoryJournal.Change> changes = HistoryJournal.diff(before, mine.snapshot());
        assertNotNull(changes);

        HistoryStore onDisk = copyOf(before);
        HistoryRevision theirs = row("/w/a", 15, fakeSha(3));
        onDisk.bucket("").put("/w/a", List.of(theirs, own));
        changes.forEach(change -> HistoryJournal.apply(onDisk, change));
        assertEquals(List.of(newer, theirs, own), onDisk.getByProject().get("").get("/w/a"));
    }

    // --- the file ---------------------------------------------------------------------------------------------

    @Test
    void aLineCutShortIsIgnoredAndCutOffBeforeTheNextAppend() throws Exception {
        Path index = Files.createDirectories(dir.resolve("history")).resolve("index.json");
        HistoryJournal journal = new HistoryJournal(index, JSON);
        HistoryRevision kept = row("/w/a", 10, fakeSha(1));
        String good = JSON.writer()
                .without(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(new HistoryJournal.Batch(
                        HistoryJournal.FORMAT,
                        HistoryStore.SCHEMA_VERSION,
                        List.of(new HistoryJournal.Change("", "/w/a", List.of(kept), List.of()))));
        Files.writeString(journal.file(), good + "\n" + good.substring(0, good.length() / 2));

        HistoryStore store = new HistoryStore();
        HistoryJournal.Replayed replayed = journal.replay(store);
        assertEquals(1, replayed.applied());
        assertFalse(replayed.frozen(), "a torn last line was never reported durable");
        assertEquals(List.of(kept), store.getByProject().get("").get("/w/a"));

        HistoryStore.Snapshot loaded = store.snapshot();
        journal.loaded(loaded, HistoryJournal.MIN_INDEX_BYTES, replayed);
        HistoryRevision next = row("/w/a", 20, fakeSha(2));
        store.bucket("").put("/w/a", List.of(next, kept));
        assertEquals(HistoryJournal.Outcome.APPENDED, journal.publish(store.snapshot(), 1, true, false));

        List<String> lines = Files.readAllLines(journal.file(), StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "the torn tail was replaced by the new line: " + lines);
        assertEquals(good, lines.get(0));
        HistoryStore again = new HistoryStore();
        assertEquals(2, journal.replay(again).applied());
        assertEquals(List.of(next, kept), again.getByProject().get("").get("/w/a"));
    }

    @Test
    void aLineThisBuildCannotReadFreezesTheJournalAndKeepsItsBodies() throws Exception {
        Path index = Files.createDirectories(dir.resolve("history")).resolve("index.json");
        HistoryJournal journal = new HistoryJournal(index, JSON);
        String sha = fakeSha(7);
        Files.writeString(
                journal.file(), "{\"format\":99,\"schemaVersion\":4,\"changes\":[{\"sha256\":\"" + sha + "\"}]}\n");
        HistoryJournal.Replayed replayed = journal.replay(new HistoryStore());
        assertTrue(replayed.frozen());
        assertEquals(java.util.Set.of(sha), replayed.keptHashes());

        HistoryStore store = new HistoryStore();
        journal.loaded(store.snapshot(), HistoryJournal.MIN_INDEX_BYTES, replayed);
        store.bucket("").put("/w/a", List.of(row("/w/a", 1, fakeSha(1))));
        assertEquals(HistoryJournal.Outcome.WHOLE, journal.publish(store.snapshot(), 1, true, false));
        journal.compacted(store.snapshot(), 1, HistoryJournal.MIN_INDEX_BYTES, true);
        assertTrue(Files.exists(journal.file()), "never deleted by a build that cannot read it");
    }

    @Test
    void aSmallIndexIsAlwaysWrittenWhole() throws Exception {
        HistoryJournal journal = new HistoryJournal(dir.resolve("index.json"), JSON);
        HistoryStore store = new HistoryStore();
        journal.loaded(store.snapshot(), HistoryJournal.MIN_INDEX_BYTES - 1, HistoryJournal.Replayed.NONE);
        store.bucket("").put("/w/a", List.of(row("/w/a", 1, fakeSha(1))));
        assertEquals(HistoryJournal.Outcome.WHOLE, journal.publish(store.snapshot(), 1, true, false));
        assertFalse(Files.exists(journal.file()));
    }

    // --- through SharedConfig: durability and recovery ------------------------------------------------------------

    private SharedConfig open(Path configDir, boolean claim) {
        SharedConfig config = new SharedConfig(configDir, false);
        config.load();
        if (claim) {
            assertTrue(config.claimInstance());
        }
        open.add(config);
        return config;
    }

    /** A history big enough to be journalled: many files, each with one row (bodies not needed for these). */
    private static void fill(SharedConfig config) {
        Map<String, List<HistoryRevision>> bucket = config.historyBucket("");
        for (int i = 0; i < 2_000; i++) {
            String path = "/work/project/src/main/java/com/example/File" + i + ".java";
            bucket.put(path, List.of(row(path, 1_000L + i, fakeSha(i + 1))));
        }
    }

    private static HistoryRevision record(SharedConfig config, Path configDir, String path, String content, long ts) {
        String sha = new HistoryBlobStore(configDir.resolve("history/blobs")).put(content);
        HistoryRevision revision = new HistoryRevision(path, ts, content.length(), sha, HistoryRevision.REASON_SAVE);
        Map<String, List<HistoryRevision>> bucket = config.historyBucket("");
        List<HistoryRevision> rows = new ArrayList<>(List.of(revision));
        rows.addAll(bucket.getOrDefault(path, List.of()));
        bucket.put(path, rows);
        return revision;
    }

    private static boolean publish(SharedConfig config) throws Exception {
        CompletableFuture<Boolean> durable = new CompletableFuture<>();
        config.saveHistory(durable::complete);
        assertTrue(config.flushWrites());
        Field field = HistoryService.class.getDeclaredField("exec");
        field.setAccessible(true);
        ((ExecutorService) field.get(config.historyService())).submit(() -> {}).get(30, TimeUnit.SECONDS);
        return durable.get(30, TimeUnit.SECONDS);
    }

    private Path journalFile(Path configDir) {
        return configDir.resolve("history").resolve(HistoryJournal.FILE_NAME);
    }

    private static String index(Path configDir) throws Exception {
        return Files.readString(configDir.resolve("history/index.json"));
    }

    /** What a crash leaves: the config folder as it is on disk now, with nothing more written to it. */
    private Path crashCopy() throws Exception {
        Path copy = Files.createDirectories(dir.resolveSibling(dir.getFileName() + "-crashed"));
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path from : files.toList()) {
                Path to = copy.resolve(dir.relativize(from).toString());
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else if (!from.getFileName().toString().endsWith(".lock")) {
                    Files.copy(from, to);
                }
            }
        }
        return copy;
    }

    @Test
    void aSaveIsJournalledAndARestartAfterACrashGetsItBack() throws Exception {
        SharedConfig config = open(dir, true);
        fill(config);
        assertTrue(publish(config), "the first publication of a session is written whole");
        assertFalse(Files.exists(journalFile(dir)));
        long indexSize = Files.size(dir.resolve("history/index.json"));
        assertTrue(indexSize >= HistoryJournal.MIN_INDEX_BYTES, "big enough to journal: " + indexSize);

        String path = "/work/project/README.md";
        HistoryRevision saved = record(config, dir, path, "the text that was saved\n", 50_000);
        // The auto-save before it is replaced, as the panel's fold does: a removal goes to the journal too.
        String coalesced = "/work/project/src/main/java/com/example/File7.java";
        config.historyBucket("").remove(coalesced);
        assertTrue(publish(config), "reported durable once the journal line is synced");

        assertTrue(Files.exists(journalFile(dir)), "journalled, not written whole");
        assertFalse(index(dir).contains(saved.sha256()), "index.json was not rewritten");
        assertEquals(indexSize, Files.size(dir.resolve("history/index.json")));

        // The process dies here. The next start reads index.json and the journal.
        Path crashed = crashCopy();
        SharedConfig restarted = open(crashed, true);
        assertEquals(List.of(saved), restarted.historyBucket("").get(path), "the saved revision is back");
        assertNull(restarted.historyBucket("").get(coalesced), "and the removal holds");
        assertEquals(1_999 + 1, restarted.historyBucket("").size());

        // Its first publication writes the index whole, folds the journal in, and collection keeps the body.
        assertTrue(publish(restarted));
        assertFalse(Files.exists(journalFile(crashed)), "folded into index.json and deleted");
        assertTrue(index(crashed).contains(saved.sha256()));
        assertEquals(
                "the text that was saved\n",
                new HistoryBlobStore(crashed.resolve("history/blobs")).get(saved.sha256()),
                "the body of a journalled revision survives the collection after the restart");
    }

    @Test
    void anotherProcessOnTheFolderMeansWholeWrites() throws Exception {
        SharedConfig config = open(dir, true);
        fill(config);
        assertTrue(publish(config));
        try (var second = InstanceLockTestHooks.otherProcessIsSecondary(dir)) {
            HistoryRevision saved = record(config, dir, "/work/project/a.txt", "a\n", 60_000);
            assertTrue(publish(config));
            assertFalse(Files.exists(journalFile(dir)), "two writers never share the journal");
            assertTrue(index(dir).contains(saved.sha256()));
        }
    }

    @Test
    void quittingFoldsTheJournalIntoTheIndex() throws Exception {
        SharedConfig config = open(dir, true);
        fill(config);
        assertTrue(publish(config));
        HistoryRevision saved = record(config, dir, "/work/project/b.txt", "b\n", 70_000);
        assertTrue(publish(config));
        assertTrue(Files.exists(journalFile(dir)));

        open.remove(config);
        assertTrue(config.shutdown());
        assertFalse(Files.exists(journalFile(dir)), "an older build reads only index.json");
        assertTrue(index(dir).contains(saved.sha256()));
    }

    @Test
    void aConfigThatWasNeverClaimedWritesWholeAsBefore() throws Exception {
        SharedConfig config = open(dir, false);
        fill(config);
        assertTrue(publish(config));
        HistoryRevision saved = record(config, dir, "/work/project/c.txt", "c\n", 80_000);
        assertTrue(publish(config));
        assertFalse(Files.exists(journalFile(dir)));
        assertTrue(index(dir).contains(saved.sha256()));
    }

    private static HistoryStore copyOf(HistoryStore.Snapshot snapshot) {
        HistoryStore store = new HistoryStore();
        Map<String, Map<String, List<HistoryRevision>>> projects = new LinkedHashMap<>();
        snapshot.byProject().forEach((project, files) -> projects.put(project, new LinkedHashMap<>(files)));
        store.setByProject(projects);
        return store;
    }
}
