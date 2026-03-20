package org.adriandeleon.editora.session;
import org.adriandeleon.editora.persistence.EditoraPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
class BookmarkManagerTest {
    @TempDir
    Path tempDir;
    private String previousHomeOverride;
    @BeforeEach
    void setHomeOverride() {
        previousHomeOverride = System.getProperty(EditoraPersistence.HOME_OVERRIDE_PROPERTY);
        System.setProperty(EditoraPersistence.HOME_OVERRIDE_PROPERTY, tempDir.resolve("home").toString());
    }
    @AfterEach
    void restoreHomeOverride() {
        if (previousHomeOverride == null) {
            System.clearProperty(EditoraPersistence.HOME_OVERRIDE_PROPERTY);
        } else {
            System.setProperty(EditoraPersistence.HOME_OVERRIDE_PROPERTY, previousHomeOverride);
        }
    }
    @Test
    void saveAndLoadRoundTripsBookmarkLines() {
        Path readme = tempDir.resolve("workspace/README.md").toAbsolutePath().normalize();
        Path mainJava = tempDir.resolve("workspace/src/Main.java").toAbsolutePath().normalize();
        Map<Path, List<Integer>> bookmarks = new LinkedHashMap<>();
        bookmarks.put(readme, List.of(0, 8, 0));
        bookmarks.put(mainJava, List.of(2, 12));
        BookmarkManager.saveBookmarks(bookmarks);
        Map<Path, List<Integer>> loaded = BookmarkManager.loadBookmarks();
        assertEquals(List.of(0, 8), loaded.get(readme));
        assertEquals(List.of(2, 12), loaded.get(mainJava));
        assertEquals(2, loaded.size());
        assertTrue(Files.isRegularFile(BookmarkManager.bookmarksFile()));
    }
    @Test
    void loadFallsBackToEmptyOnMalformedJson() throws IOException {
        Files.createDirectories(BookmarkManager.bookmarksFile().getParent());
        Files.writeString(BookmarkManager.bookmarksFile(), "{ not-json }");
        assertTrue(BookmarkManager.loadBookmarks().isEmpty());
    }
}