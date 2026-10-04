package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.editora.config.HistoryRevision;
import com.editora.config.SharedConfig;
import com.editora.config.migration.ConfigLoadProblem;
import com.editora.history.HistoryBlobStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Local History index that does not load as written must not cost the user the revision bodies.
 *
 * <p>The index used to be treated as empty, and the blob GC that follows the first index save of the session
 * then deleted every stored body — so the backup the status bar pointed to referenced nothing. Collection is
 * now refused for that session and for as long as the backup stays on disk.
 */
@Tag("fx")
class HistoryIndexLossFxTest {

    private static final String OLD = "the only copy of a file the user deleted last week";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit(); // history callbacks are delivered on the FX thread
    }

    /** As in {@code SecondInstanceFxTest}: returns once the GC queued by this index save has run or been refused. */
    private static void saveHistoryAndAwaitCollection(SharedConfig shared) throws Exception {
        CountDownLatch durable = new CountDownLatch(1);
        shared.saveHistory(written -> durable.countDown());
        assertTrue(durable.await(20, TimeUnit.SECONDS), "the history index should have been written");
        CountDownLatch behindTheCollection = new CountDownLatch(1);
        shared.historyService().content(null, text -> behindTheCollection.countDown());
        assertTrue(behindTheCollection.await(20, TimeUnit.SECONDS));
    }

    /** One session that records a revision of {@code text} for {@code path} and publishes the index. */
    private static String recordOneRevision(SharedConfig shared, String path, String text) throws Exception {
        String sha = new HistoryBlobStore(shared.getHistoryBlobsDir()).put(text);
        shared.historyBucket("")
                .put(
                        path,
                        new ArrayList<>(List.of(
                                new HistoryRevision(path, 1L, text.length(), sha, HistoryRevision.REASON_SAVE))));
        saveHistoryAndAwaitCollection(shared);
        return sha;
    }

    private static String firstSession(Path dir) throws Exception {
        SharedConfig shared = new SharedConfig(dir, false);
        try {
            shared.load();
            return recordOneRevision(shared, "/tmp/a.txt", OLD);
        } finally {
            shared.shutdown();
        }
    }

    private static void damage(Path index, String how) throws Exception {
        String written = Files.readString(index);
        assertTrue(written.contains("\"schemaVersion\""), written);
        switch (how) {
            case "newer" ->
                Files.writeString(
                        index, written.replaceFirst("\"schemaVersion\"\\s*:\\s*\\d+", "\"schemaVersion\":99"));
            case "torn" -> Files.writeString(index, written.substring(0, written.length() / 2));
            case "badValue" ->
                Files.writeString(
                        index, written.replaceFirst("\"timestamp\"\\s*:\\s*1", "\"timestamp\":\"yesterday\""));
            case "empty" -> Files.writeString(index, "");
            case "missing" -> Files.delete(index);
            default -> throw new IllegalArgumentException(how);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"newer", "torn", "badValue", "empty", "missing"})
    void anIndexThatDidNotLoadIsNeverCollectedAgainst(String how, @TempDir Path dir) throws Exception {
        String old = firstSession(dir);
        HistoryBlobStore blobs = new HistoryBlobStore(dir.resolve("history").resolve("blobs"));
        damage(dir.resolve("history").resolve("index.json"), how);

        SharedConfig second = new SharedConfig(dir, false);
        try {
            second.load();
            assertTrue(second.historyByProject().isEmpty(), "precondition: the old revisions did not load");
            List<ConfigLoadProblem> problems = second.takeLoadProblems();
            if (!how.equals("missing")) {
                assertEquals(1, problems.size(), "the user is told, including for a zero-length index");
                assertEquals(second.getHistoryFile(), problems.get(0).file());
                assertNotNull(problems.get(0).backup());
            }
            recordOneRevision(second, "/tmp/b.txt", "something saved in the second session");
            assertEquals(OLD, blobs.get(old), "the first save of the session must not delete the old bodies");
        } finally {
            second.shutdown();
        }
        if (how.equals("missing")) {
            return; // nothing was kept beside the index, so there is nothing to protect the bodies with later
        }

        // The index written by the second session loads cleanly. The backup still references the old bodies.
        SharedConfig third = new SharedConfig(dir, false);
        try {
            third.load();
            assertTrue(third.takeLoadProblems().isEmpty());
            saveHistoryAndAwaitCollection(third);
            assertEquals(OLD, blobs.get(old), "protected for as long as the backup is on disk");
        } finally {
            third.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"intact", "backupRemoved"})
    void collectionStillRunsAgainstAnIndexThatLoaded(String how, @TempDir Path dir) throws Exception {
        firstSession(dir);
        HistoryBlobStore blobs = new HistoryBlobStore(dir.resolve("history").resolve("blobs"));
        Path index = dir.resolve("history").resolve("index.json");
        if (how.equals("backupRemoved")) {
            Path backup = index.resolveSibling("index.json.corrupt.bak");
            Files.writeString(backup, "{");
            SharedConfig guarded = new SharedConfig(dir, false);
            try {
                guarded.load();
                String orphan = blobs.put("unreferenced, but kept while the backup exists");
                saveHistoryAndAwaitCollection(guarded);
                assertNotNull(blobs.get(orphan));
            } finally {
                guarded.shutdown();
            }
            Files.delete(backup);
        }
        SharedConfig shared = new SharedConfig(dir, false);
        try {
            shared.load();
            String orphan = blobs.put("nobody references this");
            saveHistoryAndAwaitCollection(shared);
            assertNull(blobs.get(orphan), "the guard must not disable collection for a healthy index");
            assertFalse(shared.historyByProject().isEmpty());
        } finally {
            shared.shutdown();
        }
    }
}
