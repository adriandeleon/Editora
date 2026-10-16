package com.editora.ui;

import java.util.List;
import java.util.Objects;

import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The Bookmarks and Personal Notes trees render the same row kinds (project header, current project header, file
 * header) in the same colours. Notes once lost its header accent because its own {@code .tree-cell} rule came later in
 * app.css and won the tie.
 */
@Tag("fx")
class BookmarksNotesColorsFxTest {

    /** Row kinds as {bookmark classes, notes classes}. */
    private static final List<String[][]> ROWS = List.of(
            new String[][] {{"bookmark-project-row"}, {"note-project-row"}},
            new String[][] {
                {"bookmark-project-row", "bookmark-project-current"}, {"note-project-row", "note-project-current"}
            },
            new String[][] {{"bookmark-file-row"}, {"notes-file-row"}});

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void bothTreesColourEveryRowKindAlike() throws Exception {
        FxTestSupport.runOnFx(() -> {
            for (String[][] row : ROWS) {
                TreeCell<String> bookmark = styledCell("bookmarks-panel", "bookmarks-tree", row[0]);
                TreeCell<String> note = styledCell("notes-panel", "notes-tree", row[1]);
                String kind = String.join(" ", row[0]);
                assertEquals(bookmark.getTextFill(), note.getTextFill(), "text fill of " + kind);
                assertEquals(weight(bookmark.getFont()), weight(note.getFont()), "font weight of " + kind);
            }
            // The headers really are accented, not just equally plain.
            TreeCell<String> plain = styledCell("notes-panel", "notes-tree");
            TreeCell<String> header = styledCell("notes-panel", "notes-tree", "note-project-row");
            assertNotEquals(plain.getTextFill(), header.getTextFill(), "a project header is accented");
        });
    }

    private static String weight(Font font) {
        return font.getStyle();
    }

    @SuppressWarnings("unchecked")
    private static TreeCell<String> styledCell(String panelClass, String treeClass, String... cellClasses) {
        TreeView<String> tree = new TreeView<>(new TreeItem<>("row"));
        tree.getStyleClass().add(treeClass);
        tree.setCellFactory(t -> {
            TreeCell<String> cell = new TreeCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty ? null : item);
                }
            };
            cell.getStyleClass().addAll(cellClasses);
            return cell;
        });
        StackPane panel = new StackPane(tree);
        panel.getStyleClass().add(panelClass);
        Scene scene = new Scene(panel, 300, 200);
        scene.getStylesheets()
                .add(Objects.requireNonNull(BookmarksNotesColorsFxTest.class.getResource("/com/editora/styles/app.css"))
                        .toExternalForm());
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        tree.applyCss();
        tree.layout();
        TreeCell<String> cell = (TreeCell<String>) tree.lookupAll(".tree-cell").stream()
                .filter(n -> n instanceof TreeCell<?> c && "row".equals(c.getItem()))
                .findFirst()
                .orElseThrow();
        stage.hide();
        return cell;
    }
}
