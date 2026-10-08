package com.editora.ui;

import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keys a tool window's filter field hands to its results: Down enters the tree/list, C-n / C-p move the
 * selection while the focus stays in the field, Enter activates a row — and a bare {@code n}/{@code p} stays
 * a typed character.
 */
@Tag("fx")
class FilterFieldNavFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static KeyEvent press(TextField field, KeyCode code, boolean control) {
        KeyEvent e = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false);
        field.getOnKeyPressed().handle(e);
        return e;
    }

    /** A shown stage holding the field over its results, with the field focused. */
    private static Scene shown(TextField field, javafx.scene.Node results) {
        Stage stage = new Stage();
        Scene scene = new Scene(new VBox(field, results), 300, 300);
        stage.setScene(scene);
        stage.show();
        field.requestFocus();
        return scene;
    }

    private static TreeView<String> tree(int rows) {
        TreeItem<String> root = new TreeItem<>("root");
        for (int i = 0; i < rows; i++) {
            root.getChildren().add(new TreeItem<>("row" + i));
        }
        TreeView<String> tree = new TreeView<>(root);
        tree.setShowRoot(false);
        return tree;
    }

    private static ListView<String> list(int rows) {
        ListView<String> list = new ListView<>();
        for (int i = 0; i < rows; i++) {
            list.getItems().add("row" + i);
        }
        return list;
    }

    @Test
    void downMovesFocusIntoTheTreeSelectingTheFirstRowOnlyWhenNoneIs() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TextField field = new TextField();
            TreeView<String> tree = tree(3);
            FilterFieldNav.install(field, tree, () -> {});
            Scene scene = shown(field, tree);

            KeyEvent first = press(field, KeyCode.DOWN, false);
            assertEquals(0, tree.getSelectionModel().getSelectedIndex(), "an unselected tree starts at row 0");
            assertSame(tree, scene.getFocusOwner(), "Down hands the focus to the tree");
            assertTrue(first.isConsumed());

            tree.getSelectionModel().clearAndSelect(2);
            field.requestFocus();
            press(field, KeyCode.DOWN, false);
            assertEquals(2, tree.getSelectionModel().getSelectedIndex(), "an existing selection is kept");
            assertSame(tree, scene.getFocusOwner());
            ((Stage) scene.getWindow()).close();
        });
    }

    @Test
    void downOnAnEmptyTreeKeepsTheFocusInTheField() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TextField field = new TextField();
            TreeView<String> tree = tree(0);
            FilterFieldNav.install(field, tree, () -> {});
            Scene scene = shown(field, tree);

            KeyEvent e = press(field, KeyCode.DOWN, false);

            assertSame(field, scene.getFocusOwner(), "there is no row to move to");
            assertTrue(tree.getSelectionModel().isEmpty());
            assertTrue(e.isConsumed(), "Down is still the filter's key, not a caret move");
            ((Stage) scene.getWindow()).close();
        });
    }

    @Test
    void controlNAndPMoveTheTreeSelectionClampedAndABareLetterIsLeftToTheField() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TextField field = new TextField();
            TreeView<String> tree = tree(3);
            FilterFieldNav.install(field, tree, () -> {});
            Scene scene = shown(field, tree);

            assertFalse(press(field, KeyCode.N, false).isConsumed(), "a bare n is typed into the filter");
            assertFalse(press(field, KeyCode.P, false).isConsumed(), "a bare p is typed into the filter");
            assertTrue(tree.getSelectionModel().isEmpty());

            assertTrue(press(field, KeyCode.N, true).isConsumed());
            assertEquals(0, tree.getSelectionModel().getSelectedIndex(), "the first C-n lands on row 0");
            press(field, KeyCode.N, true);
            press(field, KeyCode.N, true);
            press(field, KeyCode.N, true);
            assertEquals(2, tree.getSelectionModel().getSelectedIndex(), "C-n stops at the last row");
            assertTrue(press(field, KeyCode.P, true).isConsumed());
            assertEquals(1, tree.getSelectionModel().getSelectedIndex());
            press(field, KeyCode.P, true);
            press(field, KeyCode.P, true);
            assertEquals(0, tree.getSelectionModel().getSelectedIndex(), "C-p stops at the first row");
            assertSame(field, scene.getFocusOwner(), "the focus never left the field");
            ((Stage) scene.getWindow()).close();
        });
    }

    @Test
    void controlNOnAMultiSelectionTreeMovesTheSelectionInsteadOfGrowingIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TextField field = new TextField();
            TreeView<String> tree = tree(3);
            tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            FilterFieldNav.install(field, tree, () -> {});

            press(field, KeyCode.N, true);
            press(field, KeyCode.N, true);

            assertEquals(java.util.List.of(1), tree.getSelectionModel().getSelectedIndices());
        });
    }

    @Test
    void controlNOnAnEmptyTreeOrListSelectsNothing() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TextField treeField = new TextField();
            TreeView<String> tree = tree(0);
            FilterFieldNav.install(treeField, tree, () -> {});
            assertTrue(press(treeField, KeyCode.N, true).isConsumed());
            assertTrue(tree.getSelectionModel().isEmpty());

            TextField listField = new TextField();
            ListView<String> list = list(0);
            FilterFieldNav.install(listField, list, () -> {});
            assertTrue(press(listField, KeyCode.P, true).isConsumed());
            assertTrue(list.getSelectionModel().isEmpty());
        });
    }

    @Test
    void enterSelectsTheFirstTreeRowWhenNoneIsAndRunsTheAction() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TextField field = new TextField();
            TreeView<String> tree = tree(2);
            AtomicInteger opened = new AtomicInteger(-2);
            FilterFieldNav.install(
                    field, tree, () -> opened.set(tree.getSelectionModel().getSelectedIndex()));

            assertTrue(press(field, KeyCode.ENTER, false).isConsumed());
            assertEquals(0, opened.get(), "the action saw the first row selected");

            tree.getSelectionModel().clearAndSelect(1);
            press(field, KeyCode.ENTER, false);
            assertEquals(1, opened.get(), "an existing selection is the one that opens");

            TreeView<String> empty = tree(0);
            TextField emptyField = new TextField();
            AtomicInteger ran = new AtomicInteger();
            FilterFieldNav.install(emptyField, empty, ran::incrementAndGet);
            press(emptyField, KeyCode.ENTER, false);
            assertEquals(1, ran.get(), "Enter still runs the action: the panel decides what no row means");
            assertTrue(empty.getSelectionModel().isEmpty());

            assertFalse(press(field, KeyCode.A, false).isConsumed(), "any other key is the field's own");
        });
    }

    @Test
    void downMovesFocusIntoTheListStartingAtTheFirstRow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TextField field = new TextField();
            ListView<String> list = list(3);
            FilterFieldNav.install(field, list, () -> {});
            Scene scene = shown(field, list);

            KeyEvent e = press(field, KeyCode.DOWN, false);
            assertEquals(0, list.getSelectionModel().getSelectedIndex());
            assertSame(list, scene.getFocusOwner());
            assertTrue(e.isConsumed());

            // From the last row there is nowhere further to go: the selection stays and the list takes focus.
            list.getSelectionModel().clearAndSelect(2);
            field.requestFocus();
            press(field, KeyCode.DOWN, false);
            assertEquals(2, list.getSelectionModel().getSelectedIndex());
            assertSame(list, scene.getFocusOwner());
            ((Stage) scene.getWindow()).close();
        });
    }

    @Test
    void downOnAnEmptyListKeepsTheFocusInTheField() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TextField field = new TextField();
            ListView<String> list = list(0);
            FilterFieldNav.install(field, list, () -> {});
            Scene scene = shown(field, list);

            assertTrue(press(field, KeyCode.DOWN, false).isConsumed());

            assertSame(field, scene.getFocusOwner());
            ((Stage) scene.getWindow()).close();
        });
    }

    @Test
    void controlNAndPMoveTheListSelectionClampedWithoutLeavingTheField() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TextField field = new TextField();
            ListView<String> list = list(3);
            FilterFieldNav.install(field, list, () -> {});
            Scene scene = shown(field, list);

            assertFalse(press(field, KeyCode.N, false).isConsumed());
            assertFalse(press(field, KeyCode.P, false).isConsumed());
            assertTrue(list.getSelectionModel().isEmpty());

            press(field, KeyCode.N, true);
            assertEquals(0, list.getSelectionModel().getSelectedIndex());
            for (int i = 0; i < 4; i++) {
                assertTrue(press(field, KeyCode.N, true).isConsumed());
            }
            assertEquals(2, list.getSelectionModel().getSelectedIndex(), "clamped at the last row");
            for (int i = 0; i < 4; i++) {
                assertTrue(press(field, KeyCode.P, true).isConsumed());
            }
            assertEquals(0, list.getSelectionModel().getSelectedIndex(), "clamped at the first row");
            assertSame(field, scene.getFocusOwner());
            ((Stage) scene.getWindow()).close();
        });
    }

    @Test
    void enterSelectsTheFirstListRowWhenNoneIsAndRunsTheAction() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TextField field = new TextField();
            ListView<String> list = list(2);
            AtomicInteger opened = new AtomicInteger(-2);
            FilterFieldNav.install(
                    field, list, () -> opened.set(list.getSelectionModel().getSelectedIndex()));

            assertTrue(press(field, KeyCode.ENTER, false).isConsumed());
            assertEquals(0, opened.get());

            list.getSelectionModel().clearAndSelect(1);
            press(field, KeyCode.ENTER, false);
            assertEquals(1, opened.get());

            ListView<String> empty = list(0);
            TextField emptyField = new TextField();
            AtomicInteger ran = new AtomicInteger();
            FilterFieldNav.install(emptyField, empty, ran::incrementAndGet);
            press(emptyField, KeyCode.ENTER, false);
            assertEquals(1, ran.get());
            assertTrue(empty.getSelectionModel().isEmpty());

            assertFalse(press(field, KeyCode.A, false).isConsumed());
        });
    }
}
