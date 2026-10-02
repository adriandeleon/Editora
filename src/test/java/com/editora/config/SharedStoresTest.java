package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recent-files, search and agent-session histories are single instances in {@link SharedConfig}, shared
 * by every window. Each window used to load its own copy and rewrite the whole file from it, so the last
 * window to write discarded what the others had added.
 */
class SharedStoresTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<String> stored(Path file, String field) throws IOException {
        List<String> out = new ArrayList<>();
        for (JsonNode n : JSON.readTree(file.toFile()).get(field)) {
            out.add(n.isTextual() ? n.asText() : n.get("sessionId").asText());
        }
        return out;
    }

    /** Two windows over one shared config: a project window and the global one. */
    private static ConfigManager[] twoWindows(Path dir) {
        ConfigManager first = new ConfigManager(dir);
        first.load();
        ConfigManager second =
                new ConfigManager(first.shared(), dir.resolve("projects").resolve("p.json"));
        assertNotSame(first, second);
        return new ConfigManager[] {first, second};
    }

    @Test
    void everyWindowAddsToTheSameRecentFilesList(@TempDir Path dir) throws IOException {
        ConfigManager[] windows = twoWindows(dir);
        assertSame(windows[0].shared().recentFiles(), windows[1].shared().recentFiles());

        windows[0].shared().recentFiles().add(dir.resolve("from-window-a.txt"));
        windows[1].shared().recentFiles().add(dir.resolve("from-window-b.txt"));
        windows[0].shared().recentFiles().add(dir.resolve("again-from-a.txt"));
        assertTrue(windows[0].shared().flushWrites());

        assertEquals(
                List.of(
                        dir.resolve("again-from-a.txt").toString(),
                        dir.resolve("from-window-b.txt").toString(),
                        dir.resolve("from-window-a.txt").toString()),
                stored(dir.resolve("recent-files.json"), "files"),
                "no window's entry is lost to another window's save");
    }

    @Test
    void everyWindowAddsToTheSameSearchAndAgentHistories(@TempDir Path dir) throws IOException {
        ConfigManager[] windows = twoWindows(dir);
        windows[0].shared().searchHistory().add("alpha");
        windows[1].shared().searchHistory().add("beta");
        windows[0].shared().agentSessions().remember("s-1", "/a", "first", 10, "claude");
        windows[1].shared().agentSessions().remember("s-2", "/b", "second", 20, "codex");
        assertTrue(windows[0].shared().flushWrites());

        assertEquals(List.of("beta", "alpha"), stored(dir.resolve("search-history.json"), "queries"));
        assertEquals(List.of("s-2", "s-1"), stored(dir.resolve("agent-sessions.json"), "sessions"));
    }

    @Test
    void historyWritesGoThroughTheSharedWriterNotTheCallingThread(@TempDir Path dir) throws Exception {
        SharedConfig shared = new SharedConfig(dir, false);
        // Hold the writer thread, as a slow disk would: add() must return without the file existing yet.
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        shared.writer().afterBatchClaimedForTest = () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            shared.searchHistory().add("queued");
            assertEquals(List.of("queued"), shared.searchHistory().getList());
            assertTrue(Files.notExists(dir.resolve("search-history.json")), "written off the calling thread");
        } finally {
            release.countDown();
        }
        shared.writer().afterBatchClaimedForTest = null;
        assertTrue(shared.flushWrites());
        assertEquals(List.of("queued"), stored(dir.resolve("search-history.json"), "queries"));
    }

    /**
     * A remote entry resolves to a path only while its SFTP connection is open. It used to be dropped at load
     * and then purged from the file by the next save, so remote recents never survived a restart.
     */
    @Test
    void aRemoteRecentFileWhoseConnectionIsClosedStaysInTheFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("recent-files.json");
        String remote = "sftp://user@example.org:22/srv/app/config.yml";
        String local = dir.resolve("local.txt").toString();
        Files.writeString(
                file, "{\"schemaVersion\":1,\"files\":[\"" + remote + "\",\"" + local.replace("\\", "\\\\") + "\"]}");

        RecentFiles recents = new RecentFiles(dir);
        assertEquals(List.of(Path.of(local)), recents.getList(), "only the resolvable entry is listed");

        recents.add(dir.resolve("newer.txt"));

        assertEquals(
                List.of(dir.resolve("newer.txt").toString(), remote, local),
                stored(file, "files"),
                "the remote entry keeps its place for when its connection is open again");
        recents.remove(Path.of(local));
        assertEquals(List.of(dir.resolve("newer.txt").toString(), remote), stored(file, "files"));
    }

    @Test
    void aRecentFilesChangeIsOneListEvent(@TempDir Path dir) {
        RecentFiles recents = new RecentFiles(dir);
        recents.add(dir.resolve("a.txt"));
        recents.add(dir.resolve("b.txt"));
        int[] events = {0};
        recents.getList().addListener((javafx.collections.ListChangeListener<Path>) c -> events[0]++);
        recents.add(dir.resolve("a.txt")); // a move to the top: every window rebuilds its menu once, not twice
        assertEquals(1, events[0]);
        assertEquals(List.of(dir.resolve("a.txt"), dir.resolve("b.txt")), recents.getList());
    }
}
