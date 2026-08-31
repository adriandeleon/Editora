package com.editora.ui;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.StackPane;

import com.editora.config.Bookmark;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        public void openAndJump(String projectKey, Path file, int line) {}

        @Override
        public void setNote(String projectKey, Path file, int line, String note) {}

        @Override
        public void delete(String projectKey, Path file, int line) {}

        @Override
        public void deleteAll(String projectKey, Path file) {}

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
        // The in-memory bucket is the General (no-project) scope; currentKey "" makes it the current group.
        return FxTestSupport.callOnFx(() ->
                new BookmarksPanel(() -> new BookmarksPanel.Scope(Map.of("", source), "", k -> "General"), NOOP));
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
        assertEquals(1, root.getChildren().size(), "one project group (General)");
        TreeItem<Object> general = root.getChildren().get(0);
        assertEquals(2, general.getChildren().size(), "two non-empty file groups under General");
        int totalMarks = FxTestSupport.callOnFx(() -> general.getChildren().stream()
                .mapToInt(f -> f.getChildren().size())
                .sum());
        assertEquals(3, totalMarks, "all bookmarks rendered under their file");
    }

    @Test
    void groupsEveryProjectByDefaultAndFoldsNonCurrentProjects() throws Exception {
        Map<String, Map<String, List<Bookmark>>> byProject = new LinkedHashMap<>();
        byProject.put("", Map.of("/g/gen.txt", List.of(new Bookmark(1, "g", "g"))));
        byProject.put("p1", Map.of("/p1/Foo.java", List.of(new Bookmark(2, "cur", "cur"))));
        byProject.put("p2", Map.of("/p2/Bar.py", List.of(new Bookmark(3, "other", "other"))));
        Function<String, String> nameFor = k -> switch (k) {
            case "" -> "General";
            case "p1" -> "MyProject";
            default -> "Other";
        };

        // In project p1 every bucket is present immediately, but only the current project is expanded.
        BookmarksPanel p = FxTestSupport.callOnFx(
                () -> new BookmarksPanel(() -> new BookmarksPanel.Scope(byProject, "p1", nameFor), NOOP));
        TreeItem<Object> root = FxTestSupport.callOnFx(() -> tree(p).getRoot());
        assertEquals(3, root.getChildren().size(), "all project groups are shown by default");
        assertFalse(root.getChildren().get(0).isExpanded(), "General is folded when it is not current");
        assertTrue(root.getChildren().get(1).isExpanded(), "the current project is expanded");
        assertFalse(root.getChildren().get(2).isExpanded(), "other projects are folded");
    }

    @Test
    void emptyCurrentProjectKeepsPlaceholderAlongsideOtherProjectGroups() throws Exception {
        Map<String, Map<String, List<Bookmark>>> byProject = new LinkedHashMap<>();
        byProject.put("p1", Map.of());
        byProject.put("p2", Map.of("/p2/Bar.py", List.of(new Bookmark(3, "other", "other"))));

        BookmarksPanel p = FxTestSupport.callOnFx(
                () -> new BookmarksPanel(() -> new BookmarksPanel.Scope(byProject, "p1", k -> k), NOOP));
        TreeItem<Object> root = FxTestSupport.callOnFx(() -> tree(p).getRoot());
        StackPane placeholder = FxTestSupport.field(p, "placeholderPane");

        assertEquals(1, root.getChildren().size(), "the other project's group remains visible");
        assertFalse(root.getChildren().get(0).isExpanded(), "the other project starts folded");
        assertTrue(FxTestSupport.callOnFx(placeholder::isVisible), "the current-project empty label remains visible");
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
        assertEquals(1, root.getChildren().size(), "one project group");
        assertEquals(1, root.getChildren().get(0).getChildren().size(), "only the Alpha file matches the filter");
    }
}
