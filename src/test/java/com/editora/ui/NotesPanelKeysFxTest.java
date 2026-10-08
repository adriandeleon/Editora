package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;

import com.editora.config.FileIdentity;
import com.editora.config.NoteScope;
import com.editora.config.NoteStatus;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Notes panel driven the way a user drives it: Enter / double-click / Delete on its tree, each row's
 * menu, what a row shows for a resolved, orphaned, empty or folder note, and the filter bar.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotesPanelKeysFxTest {

    private final Map<String, Map<String, List<PersonalNote>>> byProject = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();
    private NotesPanel panel;

    private final NotesPanel.Actions actions = new NotesPanel.Actions() {
        @Override
        public void openAndJump(String projectKey, String fileKey, PersonalNote note) {
            calls.add("open " + projectKey + " " + fileKey + " " + note.body());
        }

        @Override
        public void editBody(String projectKey, String fileKey, PersonalNote note) {
            calls.add("edit " + projectKey + " " + fileKey + " " + note.body());
        }

        @Override
        public void setStatus(String projectKey, String fileKey, PersonalNote note, NoteStatus status) {
            calls.add("status " + projectKey + " " + fileKey + " " + note.body() + " " + status);
        }

        @Override
        public void delete(String projectKey, String fileKey, PersonalNote note) {
            calls.add("delete " + projectKey + " " + fileKey + " " + note.body());
        }

        @Override
        public void deleteAll(String projectKey, String fileKey) {
            calls.add("deleteAll " + projectKey + " " + fileKey);
        }
    };

    private static PersonalNote note(NoteScope scope, int line, String body, NoteStatus status, String... tags) {
        FileIdentity id = new FileIdentity("/p1/A.java", "/p1/A.java", 1, 1, "");
        return PersonalNote.create(id, scope, new TextAnchor(line, 0, line, 1, "s", "", ""), body, List.of(tags))
                .withStatus(status);
    }

    @BeforeAll
    void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        calls.clear();
        byProject.clear();
        Map<String, List<PersonalNote>> current = new LinkedHashMap<>();
        current.put(
                "/p1/A.java",
                List.of(
                        note(NoteScope.LINE, 4, "active note\nmore", NoteStatus.ACTIVE, "perf"),
                        note(NoteScope.LINE, 9, "done note", NoteStatus.RESOLVED),
                        note(NoteScope.LINE, 2, "  ", NoteStatus.ORPHANED)));
        current.put("C:\\p1\\docs", List.of(note(NoteScope.FOLDER, 0, "folder note", NoteStatus.ACTIVE)));
        current.put("/p1/Empty.java", List.of());
        Map<String, List<PersonalNote>> general = new LinkedHashMap<>();
        general.put("/g/gen.txt", List.of(note(NoteScope.LINE, 0, "general note", NoteStatus.ACTIVE)));
        byProject.put("", general);
        byProject.put("p1", current);
        panel = FxTestSupport.callOnFx(() -> new NotesPanel(
                () -> new NotesPanel.Scope(byProject, "p1", k -> k.isEmpty() ? "General" : "Proj"), actions));
    }

    @SuppressWarnings("unchecked")
    private TreeView<Object> tree() {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
    }

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

    private void press(KeyCode code) throws Exception {
        FxTestSupport.runOnFx(() ->
                Event.fireEvent(tree(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)));
    }

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

    private static List<String> texts(TreeCell<Object> cell) {
        return ((HBox) cell.getGraphic())
                .getChildren().stream()
                        .filter(Text.class::isInstance)
                        .map(n -> ((Text) n).getText())
                        .toList();
    }

    @Test
    void enterOpensANoteAndTogglesAHeader() throws Exception {
        press(KeyCode.ENTER);
        assertEquals(List.of(), calls, "nothing selected");

        select("body=done note");
        press(KeyCode.ENTER);
        assertEquals(List.of("open p1 /p1/A.java done note"), calls);

        select("FileRow[projectKey=p1, fileKey=/p1/A.java");
        press(KeyCode.ENTER);
        assertFalse(FxTestSupport.callOnFx(() -> item("fileKey=/p1/A.java").isExpanded()), "Enter folds a file header");
        press(KeyCode.ENTER);
        assertTrue(FxTestSupport.callOnFx(() -> item("fileKey=/p1/A.java").isExpanded()));
        assertEquals(1, calls.size());
    }

    @Test
    void deleteRemovesTheSelectedNoteAndNothingElse() throws Exception {
        press(KeyCode.DELETE);
        select("FileRow[projectKey=p1, fileKey=/p1/A.java");
        press(KeyCode.DELETE);
        press(KeyCode.A);
        assertEquals(List.of(), calls, "Delete acts on a note row only");

        select("body=done note");
        press(KeyCode.DELETE);
        assertEquals(List.of("delete p1 /p1/A.java done note"), calls);
    }

    @Test
    void onlyAPrimaryDoubleClickOpensTheSelectedNote() throws Exception {
        select("body=folder note");
        FxTestSupport.runOnFx(() -> {
            Event.fireEvent(tree(), click(MouseButton.PRIMARY, 1));
            Event.fireEvent(tree(), click(MouseButton.SECONDARY, 2));
        });
        assertEquals(List.of(), calls);
        FxTestSupport.runOnFx(() -> Event.fireEvent(tree(), click(MouseButton.PRIMARY, 2)));
        assertEquals(List.of("open p1 C:\\p1\\docs folder note"), calls);
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

    @Test
    void aNoteRowShowsItsFirstLineItsStateAndItsLine() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TreeCell<Object> active = cellFor("body=active note");
            assertEquals(List.of("active note", tr("notes.line", 5)), texts(active));
            assertFalse(active.getStyleClass().contains("note-resolved"));

            TreeCell<Object> done = cellFor("body=done note");
            assertEquals(List.of("✓ done note", tr("notes.line", 10)), texts(done));
            assertTrue(done.getStyleClass().contains("note-resolved"));

            TreeCell<Object> orphan = cellFor("status=ORPHANED");
            assertEquals(List.of("⚠ " + tr("notes.empty"), tr("notes.line", 3)), texts(orphan));
            assertTrue(orphan.getStyleClass().contains("note-orphaned"));

            TreeCell<Object> folder = cellFor("body=folder note");
            assertEquals(List.of("folder note"), texts(folder), "a folder note has no line");

            // A reused cell drops what the last row gave it.
            done.updateIndex(tree().getRow(item("body=active note")));
            assertFalse(done.getStyleClass().contains("note-resolved"));
            done.updateIndex(-1);
            assertNull(done.getGraphic());
            assertNull(done.getContextMenu());
        });
    }

    @Test
    void headersNameTheirGroupAndAFileKeyIsShownByItsLastSegmentWhateverItsSeparator() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TreeCell<Object> general = cellFor("ProjectRow[key=,");
            assertEquals("General", general.getText());
            assertTrue(general.getStyleClass().contains("note-project-row"));
            assertFalse(general.getStyleClass().contains("note-project-current"));

            TreeCell<Object> current = cellFor("ProjectRow[key=p1,");
            assertEquals(tr("scope.currentSuffix", "Proj"), current.getText());
            assertTrue(current.getStyleClass().contains("note-project-current"));

            TreeCell<Object> file = cellFor("fileKey=/p1/A.java");
            assertEquals("A.java", file.getText());
            assertEquals("/p1/A.java", file.getTooltip().getText());
            assertTrue(file.getStyleClass().contains("notes-file-row"));

            assertEquals("docs", cellFor("fileKey=C:\\p1\\docs").getText());
        });
        assertNull(FxTestSupport.callOnFx(() -> item("Empty.java")), "a file with no notes has no row");
    }

    @Test
    void theRowMenusEditResolveReopenAndDelete() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ContextMenu active = cellFor("body=active note").getContextMenu();
            menuItem(active, tr("notes.editBody")).fire();
            menuItem(active, tr("notes.resolve")).fire();
            menuItem(active, tr("notes.delete")).fire();
            menuItem(cellFor("body=done note").getContextMenu(), tr("notes.reopen"))
                    .fire();
            menuItem(cellFor("fileKey=/p1/A.java").getContextMenu(), tr("notes.deleteAllInFile"))
                    .fire();
        });
        assertEquals(
                List.of(
                        "edit p1 /p1/A.java active note\nmore",
                        "status p1 /p1/A.java active note\nmore RESOLVED",
                        "delete p1 /p1/A.java active note\nmore",
                        "status p1 /p1/A.java done note ACTIVE",
                        "deleteAll p1 /p1/A.java"),
                calls);
    }

    @Test
    void theFilterMatchesBodyTagsAndPathAndTheClearButtonUndoesIt() throws Exception {
        TextField filter = FxTestSupport.field(panel, "filterField");
        HBox header = (HBox) FxTestSupport.callOnFx(() -> filter.getParent());
        Button clear = (Button) header.getChildren().get(1);
        assertFalse(FxTestSupport.callOnFx(clear::isVisible));

        FxTestSupport.runOnFx(() -> filter.setText("PERF"));
        assertNotNull(FxTestSupport.callOnFx(() -> item("body=active note")), "matched by its tag");
        assertNull(FxTestSupport.callOnFx(() -> item("body=done note")));
        assertTrue(FxTestSupport.callOnFx(clear::isVisible));

        FxTestSupport.runOnFx(() -> filter.setText("docs"));
        assertNotNull(FxTestSupport.callOnFx(() -> item("body=folder note")), "matched by its path");
        assertNull(FxTestSupport.callOnFx(() -> item("body=active note")));

        FxTestSupport.runOnFx(clear::fire);
        assertEquals("", FxTestSupport.callOnFx(filter::getText));
        assertNotNull(FxTestSupport.callOnFx(() -> item("body=done note")));
    }

    @Test
    void aScopeWithNoCurrentKeyTreatsGeneralAsCurrent() throws Exception {
        byProject.put("empty", null);
        NotesPanel general = FxTestSupport.callOnFx(
                () -> new NotesPanel(() -> new NotesPanel.Scope(byProject, null, k -> k), actions));
        assertEquals("", FxTestSupport.<String>field(general, "currentKey"));
        @SuppressWarnings("unchecked")
        TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(general, "tree");
        assertTrue(FxTestSupport.callOnFx(
                () -> tree.getRoot().getChildren().getFirst().isExpanded()));
        assertEquals(
                2, FxTestSupport.callOnFx(() -> tree.getRoot().getChildren().size()), "a null bucket is skipped");
    }
}
