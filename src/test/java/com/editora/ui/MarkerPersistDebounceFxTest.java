package com.editora.ui;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.event.ActionEvent;

import com.editora.config.Bookmark;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
