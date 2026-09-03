package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.event.ActionEvent;

import com.editora.config.Bookmark;
import com.editora.config.PersonalNote;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for #551: a line-shifting edit above a bookmark (holding Enter, pasting/cutting lines) used to do a
 * synchronous atomic {@code bookmarks.json} write + a full Bookmarks-tree rebuild on the FX thread, once per
 * newline. The per-edit persist is now coalesced: {@code schedulePersistBookmarks} does not write synchronously —
 * the write lands once when the debounce fires. (Bookmarks is the exemplar; notes and breakpoints share the
 * identical mechanism.)
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarkerPersistDebounceFxTest {

    @TempDir
    Path root;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void schedulingABookmarkPersistIsDebouncedNotSynchronous() throws Exception {
        int[] saves = {0};
        int[] counts = FxTestSupport.callOnFx(() -> {
            BookmarkCoordinator.Ops ops = new BookmarkCoordinator.Ops() {
                final Map<String, List<Bookmark>> map = new HashMap<>();

                @Override
                public void openPath(Path file) {}

                @Override
                public void navigateToLine(int line) {}

                @Override
                public void openInProjectWindow(String projectKey, Path file, int line) {}

                @Override
                public EditorBuffer bufferForPath(Path file) {
                    return null;
                }

                @Override
                public void promptText(String title, String label, String initial, Consumer<String> onAccept) {}

                @Override
                public Map<String, List<Bookmark>> bookmarks() {
                    return map;
                }

                @Override
                public Map<String, Map<String, List<Bookmark>>> allBookmarks() {
                    return Map.of("", map);
                }

                @Override
                public String currentProjectKey() {
                    return "";
                }

                @Override
                public String projectName(String key) {
                    return key.isEmpty() ? "General" : key;
                }

                @Override
                public void saveBookmarks() {
                    saves[0]++;
                }
            };
            BookmarkCoordinator coord = new BookmarkCoordinator(new CoordinatorHostStub(), ops);

            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(Path.of("/tmp/marker-persist-test.txt"));
            buffer.getBookmarkManager().add(0, "");

            coord.schedulePersistBookmarks(buffer);
            int immediate = saves[0]; // must still be 0 — the write is deferred, not run on the edit event

            // Fire the coalescing debounce (as the ~300 ms PauseTransition would).
            PauseTransition pt = FxTestSupport.field(coord, "persistDebounce");
            pt.getOnFinished().handle(new ActionEvent());
            int afterFlush = saves[0]; // the write lands exactly once

            return new int[] {immediate, afterFlush};
        });

        assertEquals(0, counts[0], "scheduling a persist must not write synchronously on the FX hot path");
        assertEquals(1, counts[1], "the coalesced persist writes once when the debounce fires");
    }

    @Test
    void projectFileActionsPersistFirstLineMarkersWithoutOpeningTheFile() throws Exception {
        Path file = Files.writeString(root.resolve("marked.txt"), "first line\nsecond line\n")
                .toAbsolutePath()
                .normalize();
        Map<String, List<Bookmark>> bookmarks = new HashMap<>();
        Map<String, List<PersonalNote>> notes = new HashMap<>();
        int[] saves = {0, 0};
        int[] refreshes = {0, 0};
        Settings settings = new Settings();
        settings.setNotesSupport(true);

        FxTestSupport.runOnFx(() -> {
            BookmarkCoordinator bookmarksCoordinator =
                    new BookmarkCoordinator(new CoordinatorHostStub(), bookmarkOps(bookmarks, saves));
            bookmarksCoordinator.setOnChanged(() -> refreshes[0]++);
            bookmarksCoordinator.addBookmark(file);

            NotesCoordinator notesCoordinator = new NotesCoordinator(
                    new CoordinatorHostStub() {
                        @Override
                        public Settings settings() {
                            return settings;
                        }
                    },
                    noteOps(notes, saves));
            notesCoordinator.setOnChanged(() -> refreshes[1]++);
            FxTestSupport.call(
                    notesCoordinator,
                    "addPersonalNote",
                    new Class<?>[] {Path.class, String.class},
                    file,
                    "Remember this file");

            assertTrue(bookmarksCoordinator.hasBookmarks(file));
            assertTrue(notesCoordinator.hasPersonalNotes(file));
        });

        Bookmark bookmark = bookmarks.values().iterator().next().getFirst();
        PersonalNote note = notes.values().iterator().next().getFirst();
        assertEquals(0, bookmark.line());
        assertEquals(0, note.anchor().line());
        assertEquals("Remember this file", note.body());
        assertEquals(1, saves[0]);
        assertEquals(1, saves[1]);
        assertEquals(1, refreshes[0]);
        assertEquals(1, refreshes[1]);
    }

    private static BookmarkCoordinator.Ops bookmarkOps(Map<String, List<Bookmark>> map, int[] saves) {
        return new BookmarkCoordinator.Ops() {
            @Override
            public void openPath(Path file) {}

            @Override
            public void navigateToLine(int line) {}

            @Override
            public void openInProjectWindow(String projectKey, Path file, int line) {}

            @Override
            public EditorBuffer bufferForPath(Path file) {
                return null;
            }

            @Override
            public void promptText(String title, String label, String initial, Consumer<String> onAccept) {}

            @Override
            public Map<String, List<Bookmark>> bookmarks() {
                return map;
            }

            @Override
            public Map<String, Map<String, List<Bookmark>>> allBookmarks() {
                return Map.of("", map);
            }

            @Override
            public String currentProjectKey() {
                return "";
            }

            @Override
            public String projectName(String key) {
                return key;
            }

            @Override
            public void saveBookmarks() {
                saves[0]++;
            }
        };
    }

    private static NotesCoordinator.Ops noteOps(Map<String, List<PersonalNote>> map, int[] saves) {
        return new NotesCoordinator.Ops() {
            @Override
            public void openPath(Path file) {}

            @Override
            public void navigateToLine(int line) {}

            @Override
            public void openInProjectWindow(String projectKey, Path file, int line) {}

            @Override
            public String noteKey(EditorBuffer buffer) {
                return buffer.getPath().toString();
            }

            @Override
            public EditorBuffer bufferForKey(String fileKey) {
                return null;
            }

            @Override
            public EditorBuffer bufferForPath(Path file) {
                return null;
            }

            @Override
            public void installEmacsKeys(javafx.scene.control.TextInputControl control) {}

            @Override
            public void setToolWindowAvailable(boolean available) {}

            @Override
            public Map<String, List<PersonalNote>> notes() {
                return map;
            }

            @Override
            public Map<String, Map<String, List<PersonalNote>>> allNotes() {
                return Map.of("", map);
            }

            @Override
            public String currentProjectKey() {
                return "";
            }

            @Override
            public String projectName(String key) {
                return key;
            }

            @Override
            public void saveNotes() {
                saves[1]++;
            }
        };
    }
}
