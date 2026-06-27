package com.editora.ui;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.config.Bookmark;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Headless-FX coverage of {@link BookmarksPanel#refresh}: grouping the active bucket (file → bookmarks)
 * into the tree, skipping empty files, and the name/note filter. Uses a no-op {@link BookmarksPanel.Actions}
 * and an in-memory source map.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookmarksPanelFxTest {

    private static final BookmarksPanel.Actions NOOP = new BookmarksPanel.Actions() {
        @Override
        public void openAndJump(Path file, int line) {}

        @Override
        public void setNote(Path file, int line, String note) {}

        @Override
        public void delete(Path file, int line) {}

        @Override
        public void deleteAll(Path file) {}

        @Override
        public void moveBookmark(Path file, int fromIndex, int toIndex) {}

        @Override
        public void moveFile(int fromIndex, int toIndex) {}
    };

    private final Map<String, List<Bookmark>> source = new LinkedHashMap<>();

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private BookmarksPanel panel() throws Exception {
        return FxTestSupport.callOnFx(() -> new BookmarksPanel(() -> source, NOOP));
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> tree(BookmarksPanel p) {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(p, "tree");
    }

    @Test
    void groupsFilesAndBookmarksSkippingEmptyFiles() throws Exception {
        source.clear();
        source.put("/proj/Alpha.java", List.of(new Bookmark(1, "first", "line 1"), new Bookmark(5, "", "line 5")));
        source.put("/proj/Beta.java", List.of(new Bookmark(2, "note", "line 2")));
        source.put("/proj/Empty.java", List.of()); // an empty file is skipped

        BookmarksPanel p = panel();
        FxTestSupport.runOnFx(p::refresh);

        TreeItem<Object> root = FxTestSupport.callOnFx(() -> tree(p).getRoot());
        assertEquals(2, root.getChildren().size(), "two non-empty file groups");
        int totalMarks = FxTestSupport.callOnFx(() -> root.getChildren().stream()
                .mapToInt(f -> f.getChildren().size())
                .sum());
        assertEquals(3, totalMarks, "all bookmarks rendered under their file");
    }

    @Test
    void filterNarrowsToMatchingFiles() throws Exception {
        source.clear();
        source.put("/proj/Alpha.java", List.of(new Bookmark(1, "x", "a")));
        source.put("/proj/Beta.java", List.of(new Bookmark(1, "y", "b")));

        BookmarksPanel p = panel();
        TextField filter = FxTestSupport.field(p, "filterField");
        FxTestSupport.runOnFx(() -> {
            filter.setText("alpha");
            p.refresh();
        });
        TreeItem<Object> root = FxTestSupport.callOnFx(() -> tree(p).getRoot());
        assertEquals(1, root.getChildren().size(), "only the Alpha file matches the filter");
    }
}
