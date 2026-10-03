package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.Node;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.config.Bookmark;
import com.editora.config.FileIdentity;
import com.editora.config.NoteScope;
import com.editora.config.NoteStatus;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delete in the Bookmarks and Notes trees removes the selected entry — the row menu's Delete, which used to
 * be reachable only with the mouse. In the filter field the key keeps deleting text.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TreePanelDeleteKeyFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static void delete(Node target) {
        target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DELETE, false, false, false, false));
    }

    /** Depth-first: the first leaf row (a bookmark / a note). */
    private static <T> TreeItem<T> firstLeaf(TreeItem<T> item) {
        if (item.getChildren().isEmpty()) {
            return item;
        }
        return firstLeaf(item.getChildren().get(0));
    }

    private static <T> void expandAll(TreeItem<T> item) {
        item.setExpanded(true);
        item.getChildren().forEach(TreePanelDeleteKeyFxTest::expandAll);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteRemovesTheSelectedBookmark() throws Exception {
        List<String> deleted = new ArrayList<>();
        Map<String, List<Bookmark>> source = new LinkedHashMap<>();
        source.put("/proj/Alpha.java", List.of(new Bookmark(7, "first", "line 7")));
        BookmarksPanel.Actions actions = new BookmarksPanel.Actions() {
            @Override
            public void openAndJump(String projectKey, Path file, int line) {}

            @Override
            public void setNote(String projectKey, Path file, int line, String note) {}

            @Override
            public void delete(String projectKey, Path file, int line) {
                deleted.add(file.getFileName() + ":" + line);
            }

            @Override
            public void deleteAll(String projectKey, Path file) {}

            @Override
            public void moveBookmark(Path file, int fromIndex, int toIndex) {}

            @Override
            public void moveFile(int fromIndex, int toIndex) {}
        };
        FxTestSupport.runOnFx(() -> {
            BookmarksPanel panel =
                    new BookmarksPanel(() -> new BookmarksPanel.Scope(Map.of("", source), "", k -> "General"), actions);
            panel.refresh();
            TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
            TextField filter = FxTestSupport.field(panel, "filterField");
            expandAll(tree.getRoot());
            tree.getSelectionModel().select(firstLeaf(tree.getRoot()));

            delete(filter);
            assertTrue(deleted.isEmpty(), "Delete in the filter field edits text, it removes no bookmark");
            delete(tree);
            assertEquals(List.of("Alpha.java:7"), deleted, "Delete in the tree removes the selected bookmark");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteRemovesTheSelectedNote() throws Exception {
        List<String> deleted = new ArrayList<>();
        PersonalNote note = PersonalNote.create(
                new FileIdentity("/x", "/x", 1, 1, ""),
                NoteScope.LINE,
                new TextAnchor(0, 0, 0, 1, "sel", "pre", "suf"),
                "remember this",
                List.of());
        Map<String, List<PersonalNote>> source = new LinkedHashMap<>();
        source.put("/proj/Alpha.java", List.of(note));
        NotesPanel.Actions actions = new NotesPanel.Actions() {
            @Override
            public void openAndJump(String projectKey, String fileKey, PersonalNote n) {}

            @Override
            public void editBody(String projectKey, String fileKey, PersonalNote n) {}

            @Override
            public void setStatus(String projectKey, String fileKey, PersonalNote n, NoteStatus status) {}

            @Override
            public void delete(String projectKey, String fileKey, PersonalNote n) {
                deleted.add(fileKey + ":" + n.body());
            }

            @Override
            public void deleteAll(String projectKey, String fileKey) {
                deleted.add("ALL:" + fileKey);
            }
        };
        FxTestSupport.runOnFx(() -> {
            NotesPanel panel =
                    new NotesPanel(() -> new NotesPanel.Scope(Map.of("", source), "", k -> "General"), actions);
            panel.refresh();
            TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
            expandAll(tree.getRoot());
            TreeItem<Object> leaf = firstLeaf(tree.getRoot());

            tree.getSelectionModel().select(leaf.getParent()); // the file row: Delete is not a bulk delete
            delete(tree);
            assertTrue(deleted.isEmpty(), "Delete on a file row does not delete every note in the file");

            tree.getSelectionModel().select(leaf);
            delete(tree);
            assertEquals(List.of("/proj/Alpha.java:remember this"), deleted);
        });
    }
}
