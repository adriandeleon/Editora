package com.editora.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Bookmarks live in their own {@code bookmarks.json} (a {@link BookmarkStore}), bucketed per project so
 * switching projects shows only that project's bookmarks. Verifies JSON round-trip, the per-project
 * bucket selection driven by the active session file, and the one-time migration that pulls bookmarks
 * out of the legacy {@code workspace-state.json} / {@code projects/*.json} into the right bucket.
 */
class BookmarkStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bookmarkStoreRoundTripsThroughJson() throws Exception {
        BookmarkStore store = new BookmarkStore();
        store.bucket("").put("/tmp/a.txt", List.of(new Bookmark(3, "todo", "x")));
        store.bucket("proj-1").put("/tmp/b.txt", List.of(new Bookmark(7, "", "y")));

        BookmarkStore back = mapper.readValue(mapper.writeValueAsString(store), BookmarkStore.class);

        assertEquals(new Bookmark(3, "todo", "x"), back.bucket("").get("/tmp/a.txt").get(0));
        assertEquals(7, back.bucket("proj-1").get("/tmp/b.txt").get(0).line());
        assertTrue(back.bucket("missing").isEmpty());
    }

    @Test
    void readsTheLegacyFlatFormatIntoTheNoProjectBucket() throws Exception {
        // The brief pre-release bookmarks.json format: a single flat "bookmarks" map, no projects.
        String legacy = "{\"bookmarks\":{\"/tmp/a.txt\":[{\"line\":3,\"note\":\"todo\",\"lineText\":\"x\"}]}}";
        BookmarkStore back = mapper.readValue(legacy, BookmarkStore.class);
        assertEquals(new Bookmark(3, "todo", "x"), back.bucket("").get("/tmp/a.txt").get(0));
    }

    @Test
    void getBookmarksFollowsTheActiveSession(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        config.load(); // default (no project) session

        config.getBookmarks().put("/tmp/global.txt", List.of(new Bookmark(0, "", "g")));
        config.saveBookmarks();

        // Switch to a project session: its bucket is independent (empty), so the global bookmark is hidden.
        config.setWorkspaceStateFile(dir.resolve("projects").resolve("p-1.json"));
        assertTrue(config.getBookmarks().isEmpty());
        config.getBookmarks().put("/tmp/proj.txt", List.of(new Bookmark(4, "", "p")));
        config.saveBookmarks();

        // Back to the global session: only the global bookmark is visible again.
        config.useDefaultWorkspaceStateFile();
        assertEquals(1, config.getBookmarks().size());
        assertTrue(config.getBookmarks().containsKey("/tmp/global.txt"));
        assertFalse(config.getBookmarks().containsKey("/tmp/proj.txt"));
    }

    @Test
    void migratesLegacyBookmarksIntoPerProjectBuckets(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("workspace-state.json"), """
                {"activeFile":"","bookmarks":{"/tmp/a.txt":[{"line":3,"note":"todo","lineText":"x"}]}}""");
        Path projects = Files.createDirectories(dir.resolve("projects"));
        Files.writeString(projects.resolve("editora-1.json"), """
                {"bookmarks":{"/tmp/b.txt":[{"line":9,"note":"","lineText":"y"}]}}""");

        ConfigManager config = new ConfigManager(dir);
        config.load();

        // Default session sees only the no-project bucket.
        assertEquals(1, config.getBookmarks().size());
        assertTrue(config.getBookmarks().containsKey("/tmp/a.txt"));

        // The project's bookmarks landed in the editora-1 bucket (visible when that session is active).
        config.setWorkspaceStateFile(projects.resolve("editora-1.json"));
        assertTrue(config.getBookmarks().containsKey("/tmp/b.txt"));

        assertTrue(Files.exists(dir.resolve("bookmarks.json")));
        assertFalse(legacyHasBookmarks(dir.resolve("workspace-state.json")));
        assertFalse(legacyHasBookmarks(projects.resolve("editora-1.json")));
    }

    @Test
    void migrationIsOneTime(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("workspace-state.json"),
                "{\"bookmarks\":{\"/tmp/a.txt\":[{\"line\":3,\"note\":\"\",\"lineText\":\"x\"}]}}");
        new ConfigManager(dir).load(); // migrates + creates bookmarks.json

        // New legacy bookmarks written afterward are ignored (migration already done).
        Files.writeString(dir.resolve("workspace-state.json"),
                "{\"bookmarks\":{\"/tmp/zzz.txt\":[{\"line\":0,\"note\":\"\",\"lineText\":\"q\"}]}}");
        ConfigManager second = new ConfigManager(dir);
        second.load();
        assertTrue(second.getBookmarks().containsKey("/tmp/a.txt"));
        assertFalse(second.getBookmarks().containsKey("/tmp/zzz.txt"));
    }

    @Test
    void noLegacyBookmarksStillCreatesStoreOnce(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        assertTrue(config.getBookmarks().isEmpty());
        assertTrue(Files.exists(dir.resolve("bookmarks.json")));
    }

    @Test
    void deleteBookmarksForProjectDropsThatBucketOnly(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        config.getBookmarks().put("/tmp/g.txt", List.of(new Bookmark(0, "", "g"))); // "" bucket
        config.setWorkspaceStateFile(dir.resolve("projects").resolve("proj-1.json"));
        config.getBookmarks().put("/tmp/p.txt", List.of(new Bookmark(0, "", "p"))); // "proj-1" bucket
        config.saveBookmarks();

        config.deleteBookmarksForProject("proj-1");

        assertTrue(config.getBookmarks().isEmpty()); // active = proj-1, now gone
        config.useDefaultWorkspaceStateFile();
        assertTrue(config.getBookmarks().containsKey("/tmp/g.txt")); // the global bucket is untouched
    }

    // --- mergePreservingOrder (keeps the user's custom within-file order across edits) ---

    @Test
    void mergeKeepsCustomOrderAndAppendsNewBookmarks() {
        List<Bookmark> previous = List.of(new Bookmark(42, "fixme", "b"), new Bookmark(10, "todo", "a"));
        // snapshot is line-ordered and has a new bookmark at line 20:
        List<Bookmark> snapshot = List.of(
                new Bookmark(10, "todo", "a"), new Bookmark(20, "", "c"), new Bookmark(42, "fixme", "b"));

        List<Bookmark> merged = BookmarkStore.mergePreservingOrder(previous, snapshot);

        assertEquals(List.of(42, 10, 20), merged.stream().map(Bookmark::line).toList());
    }

    @Test
    void mergeFollowsLineShiftsViaLineText() {
        // Custom order [fixme(42), todo(10)]; after inserting a line above, both shifted down by one.
        List<Bookmark> previous = List.of(new Bookmark(42, "fixme", "b"), new Bookmark(10, "todo", "a"));
        List<Bookmark> shifted = List.of(new Bookmark(11, "todo", "a"), new Bookmark(43, "fixme", "b"));

        List<Bookmark> merged = BookmarkStore.mergePreservingOrder(previous, shifted);

        // The "fixme" bookmark (now line 43) stays first; "todo" (now 11) second — order preserved.
        assertEquals(List.of("fixme", "todo"), merged.stream().map(Bookmark::note).toList());
        assertEquals(List.of(43, 11), merged.stream().map(Bookmark::line).toList());
    }

    @Test
    void mergeWithNoPreviousOrderReturnsSnapshotOrder() {
        List<Bookmark> snapshot = List.of(new Bookmark(1, "", "a"), new Bookmark(2, "", "b"));
        assertEquals(snapshot, BookmarkStore.mergePreservingOrder(null, snapshot));
        assertEquals(snapshot, BookmarkStore.mergePreservingOrder(List.of(), snapshot));
    }

    private boolean legacyHasBookmarks(Path file) throws Exception {
        JsonNode root = mapper.readTree(Files.readString(file));
        JsonNode bm = root.get("bookmarks");
        return bm != null && !bm.isNull() && !bm.isEmpty();
    }
}
