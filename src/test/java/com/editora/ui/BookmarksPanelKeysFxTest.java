package com.editora.ui;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;

import com.editora.config.Bookmark;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Bookmarks panel driven the way a user drives it: the keys of its tree, the rows' menus, reordering
 * (keyboard, menu and drop) and deletion with its confirmation. The model is an in-memory map the recording
 * {@link BookmarksPanel.Actions} mutates the way the window's coordinator would.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookmarksPanelKeysFxTest {

    private static final String A = "/p1/A.java";
    private static final String B = "/p1/B.java";

    private final Map<String, Map<String, List<Bookmark>>> byProject = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();
    private BookmarksPanel panel;

    private final BookmarksPanel.Actions actions = new BookmarksPanel.Actions() {
        @Override
        public void openAndJump(String projectKey, Path file, int line) {
            calls.add("open " + projectKey + " " + file.getFileName() + ":" + line);
        }

        @Override
        public void setNote(String projectKey, Path file, int line, String note) {
            calls.add("note " + projectKey + " " + file.getFileName() + ":" + line + " [" + note + "]");
        }

        @Override
        public void delete(String projectKey, Path file, int line) {
            calls.add("delete " + projectKey + " " + file.getFileName() + ":" + line);
        }

        @Override
        public void deleteAll(String projectKey, Path file) {
            calls.add("deleteAll " + projectKey + " " + file.getFileName());
        }

        @Override
        public void moveBookmark(Path file, int fromIndex, int toIndex) {
            calls.add("moveBookmark " + file.getFileName() + " " + fromIndex + "->" + toIndex);
            List<Bookmark> marks = byProject.get("p1").get(file.toString().replace('\\', '/'));
            marks.add(toIndex, marks.remove(fromIndex));
            panel.refresh();
        }

        @Override
        public void moveFile(int fromIndex, int toIndex) {
            calls.add("moveFile " + fromIndex + "->" + toIndex);
            List<Map.Entry<String, List<Bookmark>>> files =
                    new ArrayList<>(byProject.get("p1").entrySet());
            files.add(toIndex, files.remove(fromIndex));
            Map<String, List<Bookmark>> reordered = new LinkedHashMap<>();
            files.forEach(e -> reordered.put(e.getKey(), e.getValue()));
            byProject.put("p1", reordered);
            panel.refresh();
        }
    };

    @BeforeAll
    void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        calls.clear();
        byProject.clear();
        Map<String, List<Bookmark>> general = new LinkedHashMap<>();
        general.put("/g/gen.txt", new ArrayList<>(List.of(new Bookmark(0, "general", ""))));
        Map<String, List<Bookmark>> current = new LinkedHashMap<>();
        current.put(
                A,
                new ArrayList<>(
                        List.of(new Bookmark(1, "one", ""), new Bookmark(2, "two", ""), new Bookmark(3, "three", ""))));
        current.put(B, new ArrayList<>(List.of(new Bookmark(9, "nine", ""))));
        byProject.put("", general);
        byProject.put("p1", current);
        panel = FxTestSupport.callOnFx(() -> new BookmarksPanel(
                () -> new BookmarksPanel.Scope(byProject, "p1", k -> k.isEmpty() ? "General" : "Proj"), actions));
    }

    // --- plumbing ---------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private TreeView<Object> tree() {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
    }

    /** The tree item whose row mentions {@code needle} (rows are records, so their text names their fields). */
    private TreeItem<Object> item(String needle) {
        return find(tree().getRoot(), needle);
    }

    private static TreeItem<Object> find(TreeItem<Object> at, String needle) {
        if (at.getValue() != null && at.getValue().toString().contains(needle)) {
            return at;
        }
        for (TreeItem<Object> child : at.getChildren()) {
            TreeItem<Object> hit = find(child, needle);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private void select(String needle) throws Exception {
        FxTestSupport.runOnFx(() -> tree().getSelectionModel().select(item(needle)));
    }

    private String selected() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            TreeItem<Object> sel = tree().getSelectionModel().getSelectedItem();
            return sel == null ? null : sel.getValue().toString();
        });
    }

    private static KeyEvent key(KeyCode code, boolean ctrl, boolean alt) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, alt, false);
    }

    /** Presses a key on the tree; returns whether the panel claimed it. */
    private boolean press(KeyCode code, boolean ctrl, boolean alt) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            boolean[] reachedTree = {false};
            javafx.event.EventHandler<KeyEvent> probe = e -> reachedTree[0] = true;
            tree().addEventFilter(KeyEvent.KEY_PRESSED, probe);
            try {
                Event.fireEvent(tree(), key(code, ctrl, alt));
            } finally {
                tree().removeEventFilter(KeyEvent.KEY_PRESSED, probe);
            }
            return !reachedTree[0]; // the panel's filter consumed it before the tree saw it
        });
    }

    private boolean press(KeyCode code) throws Exception {
        return press(code, false, false);
    }

    /** A cell showing the row of {@code needle}, wired to the tree as a laid-out cell is. */
    private TreeCell<Object> cellFor(String needle) {
        TreeView<Object> tree = tree();
        TreeCell<Object> cell = tree.getCellFactory().call(tree);
        cell.updateTreeView(tree);
        cell.updateIndex(tree.getRow(item(needle)));
        return cell;
    }

    private static MenuItem menuItem(ContextMenu menu, String text) {
        return menu.getItems().stream()
                .filter(i -> text.equals(i.getText()))
                .findFirst()
                .orElseThrow();
    }

    private void setDragged(TreeItem<Object> item) throws Exception {
        Field f = BookmarksPanel.class.getDeclaredField("draggedItem");
        f.setAccessible(true);
        f.set(panel, item);
    }

    private static DragEvent drag(javafx.event.EventType<DragEvent> type, double y) {
        return new DragEvent(type, null, 0, y, 0, y, TransferMode.MOVE, null, null, null);
    }

    // --- walking the tree -------------------------------------------------------------------------------

    @Test
    void arrowsAndEmacsKeysWalkTheVisibleRowsAndWrapAround() throws Exception {
        assertNull(selected());
        assertTrue(press(KeyCode.DOWN));
        assertTrue(selected().startsWith("ProjectRow[key=,"), "Down with nothing selected lands on the first row");

        assertTrue(press(KeyCode.UP));
        assertTrue(selected().contains("note=nine"), "Up from the first row wraps to the last");

        assertTrue(press(KeyCode.N, true, false));
        assertTrue(selected().startsWith("ProjectRow[key=,"), "C-n from the last row wraps to the first");
        assertTrue(press(KeyCode.N, true, false));
        assertTrue(selected().startsWith("ProjectRow[key=p1,"));
        assertTrue(press(KeyCode.P, true, false));
        assertTrue(selected().startsWith("ProjectRow[key=,"));
    }

    @Test
    void upWithNothingSelectedStartsFromTheLastRow() throws Exception {
        assertTrue(press(KeyCode.UP));
        assertTrue(selected().contains("note=nine"));
    }

    @Test
    void walkingAnEmptyTreeSelectsNothing() throws Exception {
        byProject.clear();
        FxTestSupport.runOnFx(panel::refresh);
        assertTrue(press(KeyCode.DOWN));
        assertNull(selected());
    }

    @Test
    void aPlainLetterIsNotThePanelsKey() throws Exception {
        assertFalse(press(KeyCode.N), "without Control the letter goes on to the tree");
        assertFalse(press(KeyCode.Q, true, false), "a Control chord the panel does not know is left alone");
        assertNull(selected());
    }

    @Test
    void forwardExpandsAFoldedGroupThenDescendsAndBackwardFoldsThenAscends() throws Exception {
        select("ProjectRow[key=,");
        assertFalse(FxTestSupport.callOnFx(() -> item("ProjectRow[key=,").isExpanded()), "General starts folded");

        assertTrue(press(KeyCode.F, true, false));
        assertTrue(FxTestSupport.callOnFx(() -> item("ProjectRow[key=,").isExpanded()), "C-f unfolds it");
        assertTrue(selected().startsWith("ProjectRow[key=,"), "and stays on the header");

        assertTrue(press(KeyCode.F, true, false));
        assertTrue(selected().contains("gen.txt"), "C-f on an open group steps into it");

        select("note=general");
        assertTrue(press(KeyCode.B, true, false));
        assertTrue(selected().startsWith("FileRow["), "C-b on a leaf goes to its file header");

        assertTrue(press(KeyCode.B, true, false));
        assertFalse(FxTestSupport.callOnFx(() -> item("gen.txt").isExpanded()), "C-b on an open header folds it");
        assertTrue(press(KeyCode.B, true, false));
        assertTrue(selected().startsWith("ProjectRow[key=,"), "C-b on a folded header goes to its parent");

        assertTrue(press(KeyCode.B, true, false));
        assertFalse(FxTestSupport.callOnFx(() -> item("ProjectRow[key=,").isExpanded()));
        assertTrue(press(KeyCode.B, true, false));
        assertTrue(selected().contains("note=nine"), "a folded top-level group has no parent row: C-b steps back");
    }

    @Test
    void backwardWithNothingSelectedStepsToTheLastRow() throws Exception {
        assertTrue(press(KeyCode.B, true, false));
        assertTrue(selected().contains("note=nine"));
    }

    // --- opening ----------------------------------------------------------------------------------------

    @Test
    void enterOpensABookmarkAndTogglesAHeader() throws Exception {
        assertTrue(press(KeyCode.ENTER));
        assertEquals(List.of(), calls, "Enter with nothing selected does nothing");

        select("note=two");
        assertTrue(press(KeyCode.ENTER));
        assertEquals(List.of("open p1 A.java:2"), calls);

        select("B.java");
        assertTrue(press(KeyCode.M, true, false));
        assertFalse(FxTestSupport.callOnFx(() -> item("B.java").isExpanded()), "C-m on a file header folds it");
        assertTrue(press(KeyCode.ENTER));
        assertTrue(FxTestSupport.callOnFx(() -> item("B.java").isExpanded()), "and Enter unfolds it again");
        assertEquals(List.of("open p1 A.java:2"), calls, "a header opens nothing");
    }

    @Test
    void onlyAPrimaryDoubleClickOpensTheSelectedBookmark() throws Exception {
        select("note=nine");
        FxTestSupport.runOnFx(() -> {
            Event.fireEvent(tree(), click(MouseButton.PRIMARY, 1));
            Event.fireEvent(tree(), click(MouseButton.SECONDARY, 2));
        });
        assertEquals(List.of(), calls);
        FxTestSupport.runOnFx(() -> Event.fireEvent(tree(), click(MouseButton.PRIMARY, 2)));
        assertEquals(List.of("open p1 B.java:9"), calls);
    }

    private static MouseEvent click(MouseButton button, int count) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                0,
                0,
                0,
                0,
                button,
                count,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    // --- reordering -------------------------------------------------------------------------------------

    @Test
    void altArrowsMoveABookmarkWithinItsFileAndKeepItSelected() throws Exception {
        select("note=one");
        assertTrue(press(KeyCode.DOWN, false, true));
        assertEquals(List.of("moveBookmark A.java 0->1"), calls);
        assertTrue(selected().contains("note=one"), "the moved bookmark is still the selected row");
        assertEquals(
                List.of("two", "one", "three"),
                byProject.get("p1").get(A).stream().map(Bookmark::note).toList());

        assertTrue(press(KeyCode.UP, false, true));
        assertTrue(press(KeyCode.UP, false, true)); // already first: nothing to do
        assertEquals(List.of("moveBookmark A.java 0->1", "moveBookmark A.java 1->0"), calls);

        select("note=three");
        assertTrue(press(KeyCode.DOWN, false, true)); // already last
        assertEquals(2, calls.size());
    }

    @Test
    void altArrowsMoveAFileGroupAmongItsSiblings() throws Exception {
        select("FileRow[projectKey=p1, file=" + Path.of(A));
        assertTrue(press(KeyCode.DOWN, false, true));
        assertEquals(List.of("moveFile 0->1"), calls);
        assertEquals(List.of(B, A), List.copyOf(byProject.get("p1").keySet()));
        assertTrue(selected().contains("A.java") && selected().startsWith("FileRow["), "the header stays selected");

        assertTrue(press(KeyCode.DOWN, false, true)); // already last
        assertEquals(1, calls.size());
    }

    @Test
    void reorderingIsRefusedOutsideTheCurrentProjectWhileFilteringAndOnAProjectHeader() throws Exception {
        FxTestSupport.runOnFx(() -> item("ProjectRow[key=,").setExpanded(true));
        select("note=general");
        press(KeyCode.DOWN, false, true);
        assertEquals(List.of(), calls, "General is not this window's project");

        press(KeyCode.DOWN, false, true); // nothing is selected after the refused move? still General's row
        select("ProjectRow[key=p1,");
        press(KeyCode.UP, false, true);
        assertEquals(List.of(), calls, "a project header has no siblings to move among");

        TextField filter = FxTestSupport.field(panel, "filterField");
        FxTestSupport.runOnFx(() -> filter.setText("o"));
        select("note=one");
        press(KeyCode.DOWN, false, true);
        assertEquals(List.of(), calls, "a filtered tree's rows do not map to stored positions");

        FxTestSupport.runOnFx(() -> {
            filter.setText("");
            tree().getSelectionModel().clearSelection();
        });
        press(KeyCode.DOWN, false, true);
        assertEquals(List.of(), calls, "nothing selected");
    }

    @Test
    void theRowMenuMovesItsOwnRowAndIsDisabledOutsideTheCurrentProject() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ContextMenu menu = cellFor("note=two").getContextMenu();
            assertFalse(menuItem(menu, tr("bookmarks.moveUp")).isDisable());
            menuItem(menu, tr("bookmarks.moveUp")).fire();
        });
        assertEquals(List.of("moveBookmark A.java 1->0"), calls);
        FxTestSupport.runOnFx(() -> menuItem(cellFor("B.java").getContextMenu(), tr("bookmarks.moveDown"))
                .fire());
        assertEquals(1, calls.size(), "B.java is the last file: Move Down has nowhere to go");
        FxTestSupport.runOnFx(() -> menuItem(cellFor("B.java").getContextMenu(), tr("bookmarks.moveUp"))
                .fire());
        assertEquals("moveFile 1->0", calls.get(1));

        FxTestSupport.runOnFx(() -> {
            item("ProjectRow[key=,").setExpanded(true);
            ContextMenu menu = cellFor("note=general").getContextMenu();
            assertTrue(menuItem(menu, tr("bookmarks.moveUp")).isDisable());
            assertTrue(menuItem(menu, tr("bookmarks.moveDown")).isDisable());
        });
    }

    @Test
    void droppingARowOnASiblingMovesItBeforeOrAfterTheTarget() throws Exception {
        FxTestSupport.runOnFx(() -> {
            try {
                setDragged(item("note=one"));
                TreeCell<Object> target = cellFor("note=three");
                target.resize(100, 20);
                Event.fireEvent(target, drag(DragEvent.DRAG_OVER, 15));
                assertTrue(target.getStyleClass().contains("bookmark-drop-below"), "lower half: drop after");
                assertFalse(target.getStyleClass().contains("bookmark-drop-above"));
                Event.fireEvent(target, drag(DragEvent.DRAG_OVER, 3));
                assertTrue(target.getStyleClass().contains("bookmark-drop-above"), "upper half: drop before");
                Event.fireEvent(target, drag(DragEvent.DRAG_EXITED, 3));
                assertFalse(target.getStyleClass().contains("bookmark-drop-above"));

                Event.fireEvent(target, drag(DragEvent.DRAG_DROPPED, 15));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals(List.of("moveBookmark A.java 0->2"), calls);
        assertEquals(
                List.of("two", "three", "one"),
                byProject.get("p1").get(A).stream().map(Bookmark::note).toList());

        FxTestSupport.runOnFx(() -> {
            try {
                setDragged(item("B.java"));
                TreeCell<Object> target = cellFor("FileRow[projectKey=p1, file=" + Path.of(A));
                target.resize(100, 20);
                Event.fireEvent(target, drag(DragEvent.DRAG_DROPPED, 2));
                Event.fireEvent(target, drag(DragEvent.DRAG_DONE, 2));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals("moveFile 1->0", calls.get(1));
        assertNull(FxTestSupport.field(panel, "draggedItem"), "the drag is over");
    }

    @Test
    void aDropIsRefusedAcrossFilesAcrossKindsAndOntoItself() throws Exception {
        FxTestSupport.runOnFx(() -> {
            try {
                item("ProjectRow[key=,").setExpanded(true);
                String[][] pairs = {
                    {"note=one", "note=nine"}, // another file's bookmark
                    {"note=one", "B.java"}, // a bookmark onto a file header
                    {"B.java", "note=one"}, // a file header onto a bookmark
                    {"note=one", "note=one"}, // itself
                    {"note=general", "note=one"}, // from another project's group
                    {"note=one", "note=general"}, // into another project's group
                };
                for (String[] pair : pairs) {
                    setDragged(item(pair[0]));
                    TreeCell<Object> target = cellFor(pair[1]);
                    target.resize(100, 20);
                    Event.fireEvent(target, drag(DragEvent.DRAG_OVER, 15));
                    assertFalse(
                            target.getStyleClass().contains("bookmark-drop-below"),
                            pair[0] + " over " + pair[1] + " shows no insertion line");
                    Event.fireEvent(target, drag(DragEvent.DRAG_DROPPED, 15));
                }
                setDragged(null);
                Event.fireEvent(cellFor("note=one"), drag(DragEvent.DRAG_DROPPED, 15));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals(List.of(), calls);
    }

    @Test
    void theDraggedRowIsDimmed() throws Exception {
        FxTestSupport.runOnFx(() -> {
            try {
                setDragged(item("note=two"));
                assertTrue(cellFor("note=two").getStyleClass().contains("bookmark-dragging"));
                assertFalse(cellFor("note=one").getStyleClass().contains("bookmark-dragging"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    // --- deleting and editing ---------------------------------------------------------------------------

    @Test
    void deleteRemovesTheSelectedBookmarkButOnlyFromTheTree() throws Exception {
        assertFalse(press(KeyCode.DELETE), "nothing selected: the key is not the panel's");
        select("note=two");
        assertTrue(press(KeyCode.DELETE));
        assertEquals(List.of("delete p1 A.java:2"), calls);

        TextField filter = FxTestSupport.field(panel, "filterField");
        FxTestSupport.runOnFx(() -> Event.fireEvent(filter, key(KeyCode.DELETE, false, false)));
        assertEquals(1, calls.size(), "Delete in the filter field edits text");

        select("ProjectRow[key=p1,");
        assertTrue(press(KeyCode.DELETE));
        assertEquals(1, calls.size(), "a project group is not deletable");
    }

    @Test
    void deletingAFilesBookmarksAsksFirst() throws Exception {
        String question = tr("bookmarks.deleteFileBody", "A.java");
        select("FileRow[projectKey=p1, file=" + Path.of(A));

        assertTrue(FxDialogs.duringContent(
                        () -> Event.fireEvent(tree(), key(KeyCode.DELETE, false, false)),
                        question,
                        ButtonBar.ButtonData.CANCEL_CLOSE)
                != null);
        assertEquals(List.of(), calls, "Cancel keeps them");

        FxDialogs.duringContent(() -> Event.fireEvent(tree(), key(KeyCode.DELETE, false, false)), question, null);
        assertEquals(List.of(), calls, "closing the question keeps them");

        FxDialogs.duringContent(
                () -> menuItem(
                                cellFor("FileRow[projectKey=p1, file=" + Path.of(A))
                                        .getContextMenu(),
                                tr("bookmarks.deleteAllInFile"))
                        .fire(),
                question,
                ButtonBar.ButtonData.OK_DONE);
        assertEquals(List.of("deleteAll p1 A.java"), calls);
    }

    @Test
    void theBookmarkMenuEditsTheNoteThroughThePromptAndDeletes() throws Exception {
        FxTestSupport.runOnFx(() -> menuItem(cellFor("note=two").getContextMenu(), tr("bookmarks.editNoteItem"))
                .fire());
        assertEquals(List.of(), calls, "without a prompt there is nothing to edit with");

        List<String> shown = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            panel.setPrompt((title, label, initial, onAccept) -> {
                shown.add(title + "|" + label + "|" + initial);
                onAccept.accept("  rewritten  ");
            });
            ContextMenu menu = cellFor("note=two").getContextMenu();
            menuItem(menu, tr("bookmarks.editNoteItem")).fire();
            menuItem(menu, tr("bookmarks.deleteItem")).fire();
        });
        assertEquals(
                List.of(tr("dialog.bookmarkNote.title") + "|" + tr("dialog.bookmarkNote.content") + "|two"), shown);
        assertEquals(List.of("note p1 A.java:2 [rewritten]", "delete p1 A.java:2"), calls);
    }

    // --- rendering --------------------------------------------------------------------------------------

    @Test
    void headersNameTheirGroupAndMarkTheCurrentProject() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TreeCell<Object> general = cellFor("ProjectRow[key=,");
            assertEquals("General", general.getText());
            assertTrue(general.getStyleClass().contains("bookmark-project-row"));
            assertFalse(general.getStyleClass().contains("bookmark-project-current"));
            assertNull(general.getContextMenu());

            TreeCell<Object> current = cellFor("ProjectRow[key=p1,");
            assertEquals(tr("scope.currentSuffix", "Proj"), current.getText());
            assertTrue(current.getStyleClass().contains("bookmark-project-current"));

            TreeCell<Object> file = cellFor("B.java");
            assertEquals("B.java", file.getText());
            assertTrue(file.getStyleClass().contains("bookmark-file-row"));
            assertEquals(Path.of(B).toString(), file.getTooltip().getText());

            // A reused cell drops everything the last row gave it.
            file.updateIndex(-1);
            assertNull(file.getText());
            assertNull(file.getGraphic());
            assertNull(file.getContextMenu());
            assertNull(file.getTooltip());
            assertFalse(file.getStyleClass().contains("bookmark-file-row"));
        });
    }

    @Test
    void aBookmarkIsLabelledByMnemonicThenNoteThenLineTextThenLineNumber() throws Exception {
        byProject
                .get("p1")
                .put(
                        A,
                        new ArrayList<>(List.of(
                                new Bookmark(4, "", "int x = 1;", "q"),
                                new Bookmark(5, "", ""),
                                new Bookmark(6, "noted", "ignored", ""))));
        FxTestSupport.runOnFx(panel::refresh);
        List<String> labels = FxTestSupport.callOnFx(() -> {
            List<String> out = new ArrayList<>();
            for (String needle : List.of("line=4,", "line=5,", "line=6,")) {
                HBox row = (HBox) cellFor(needle).getGraphic();
                out.add(row.getChildren().stream()
                        .filter(Text.class::isInstance)
                        .map(n -> ((Text) n).getText())
                        .findFirst()
                        .orElseThrow());
            }
            return out;
        });
        assertEquals(List.of("[Q] int x = 1;", "line 6", "noted"), labels);

        // The filter matches the label a row shows, not only its file name.
        TextField filter = FxTestSupport.field(panel, "filterField");
        FxTestSupport.runOnFx(() -> filter.setText("INT X"));
        assertEquals(
                1, FxTestSupport.callOnFx(() -> item("A.java").getChildren().size()));
        assertNull(FxTestSupport.callOnFx(() -> item("B.java")), "a file with no matching row is left out");
    }

    // --- the filter bar ---------------------------------------------------------------------------------

    @Test
    void theClearButtonEmptiesTheFilterAndShowsOnlyWhileThereIsText() throws Exception {
        TextField filter = FxTestSupport.field(panel, "filterField");
        HBox header = FxTestSupport.field(panel, "header");
        Button clear = (Button) header.getChildren().get(1);
        assertFalse(FxTestSupport.callOnFx(clear::isVisible));
        FxTestSupport.runOnFx(() -> filter.setText("nine"));
        assertTrue(FxTestSupport.callOnFx(clear::isVisible));
        assertNull(FxTestSupport.callOnFx(() -> item("A.java")));

        FxTestSupport.runOnFx(clear::fire);
        assertEquals("", FxTestSupport.callOnFx(filter::getText));
        assertTrue(FxTestSupport.callOnFx(() -> item("A.java")) != null, "every file is back");
    }

    @Test
    void aScopeWithNoCurrentKeyTreatsGeneralAsCurrent() throws Exception {
        BookmarksPanel general = FxTestSupport.callOnFx(
                () -> new BookmarksPanel(() -> new BookmarksPanel.Scope(byProject, null, k -> k), actions));
        assertEquals("", FxTestSupport.<String>field(general, "currentKey"));
        @SuppressWarnings("unchecked")
        TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(general, "tree");
        assertTrue(FxTestSupport.callOnFx(
                () -> tree.getRoot().getChildren().getFirst().isExpanded()));
    }
}
