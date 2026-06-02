package com.editora.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Bookmarks now live in their own global {@code bookmarks.json} (a {@link BookmarkStore}), not in
 * {@link WorkspaceState}. Verifies JSON round-trip plus the one-time migration that pulls bookmarks
 * out of the legacy {@code workspace-state.json} / {@code projects/*.json} session files.
 */
class BookmarkStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bookmarkStoreRoundTripsThroughJson() throws Exception {
        BookmarkStore store = new BookmarkStore();
        store.getBookmarks().put("/tmp/a.txt",
                List.of(new Bookmark(3, "todo", "int x = 1;"), new Bookmark(10, "", "return x;")));

        BookmarkStore back = mapper.readValue(mapper.writeValueAsString(store), BookmarkStore.class);

        Map<String, List<Bookmark>> marks = back.getBookmarks();
        assertEquals(1, marks.size());
        List<Bookmark> a = marks.get("/tmp/a.txt");
        assertEquals(new Bookmark(3, "todo", "int x = 1;"), a.get(0));
        assertEquals("return x;", a.get(1).lineText());
    }

    @Test
    void migratesBookmarksFromLegacySessionFilesAndStripsThem(@TempDir Path dir) throws Exception {
        // Legacy: bookmarks embedded in workspace-state.json and a project session file.
        Files.writeString(dir.resolve("workspace-state.json"), """
                {"activeFile":"","bookmarks":{"/tmp/a.txt":[{"line":3,"note":"todo","lineText":"x"}]}}""");
        Path projects = Files.createDirectories(dir.resolve("projects"));
        Files.writeString(projects.resolve("p1.json"), """
                {"bookmarks":{"/tmp/a.txt":[{"line":3,"note":"dup","lineText":"x"},
                {"line":9,"note":"","lineText":"y"}],"/tmp/b.txt":[{"line":1,"note":"","lineText":"z"}]}}""");

        ConfigManager config = new ConfigManager(dir);
        config.load();

        Map<String, List<Bookmark>> marks = config.getBookmarks();
        assertEquals(2, marks.size());
        // /tmp/a.txt: line 3 from workspace-state wins over the project dup; line 9 added.
        List<Bookmark> a = marks.get("/tmp/a.txt");
        assertEquals(2, a.size());
        assertEquals("todo", a.get(0).note());
        assertEquals(9, a.get(1).line());
        assertEquals(1, marks.get("/tmp/b.txt").get(0).line());

        // bookmarks.json was created; the legacy files no longer carry a bookmarks node.
        assertTrue(Files.exists(dir.resolve("bookmarks.json")));
        assertFalse(legacyHasBookmarks(dir.resolve("workspace-state.json")));
        assertFalse(legacyHasBookmarks(projects.resolve("p1.json")));
    }

    @Test
    void migrationIsOneTime(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("workspace-state.json"),
                "{\"bookmarks\":{\"/tmp/a.txt\":[{\"line\":3,\"note\":\"\",\"lineText\":\"x\"}]}}");

        new ConfigManager(dir).load(); // first run migrates + creates bookmarks.json

        // A second run must NOT re-read the (now stripped) legacy file: the bookmark count is stable
        // and reflects only bookmarks.json.
        ConfigManager second = new ConfigManager(dir);
        second.load();
        assertEquals(1, second.getBookmarks().size());

        // Writing new legacy bookmarks afterward is ignored (migration already done).
        Files.writeString(dir.resolve("workspace-state.json"),
                "{\"bookmarks\":{\"/tmp/zzz.txt\":[{\"line\":0,\"note\":\"\",\"lineText\":\"q\"}]}}");
        ConfigManager third = new ConfigManager(dir);
        third.load();
        assertFalse(third.getBookmarks().containsKey("/tmp/zzz.txt"));
    }

    @Test
    void noLegacyBookmarksStillCreatesStoreOnce(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        assertTrue(config.getBookmarks().isEmpty());
        assertTrue(Files.exists(dir.resolve("bookmarks.json")));
    }

    private boolean legacyHasBookmarks(Path file) throws Exception {
        JsonNode root = mapper.readTree(Files.readString(file));
        JsonNode bm = root.get("bookmarks");
        return bm != null && !bm.isNull() && !bm.isEmpty();
    }
}
