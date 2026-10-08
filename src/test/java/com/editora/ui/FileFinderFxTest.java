package com.editora.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keyboard file opener: what it lists for a typed path, what Tab completes, and what Enter does with a
 * folder, a file, a name that does not exist yet and nothing at all — in both the file and the folder picker.
 */
@Tag("fx")
class FileFinderFxTest {

    private static final String SEP = File.separator;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A finder over {@code root}, shown, with its first listing landed. */
    private static final class Shown implements AutoCloseable {
        final AsyncTestScope async = new AsyncTestScope();
        final List<Path> chosen = new ArrayList<>();
        final FileFinder finder;
        final TextField input;
        final ListView<Path> list;
        final ObservableList<Path> items;
        Stage stage;
        OverlayHost host;

        Shown(Path root, boolean pickDirectory, int expectedRows) throws Exception {
            finder = FxTestSupport.callOnFx(() -> pickDirectory
                    ? new FileFinder(() -> root, chosen::add, true, "Choose Folder")
                    : new FileFinder(() -> root, chosen::add));
            input = FxTestSupport.field(finder, "input");
            list = FxTestSupport.field(finder, "list");
            items = FxTestSupport.field(finder, "items");
            FxTestSupport.runOnFx(() -> {
                StackPane pane = new StackPane();
                stage = new Stage();
                stage.setScene(new Scene(pane, 900, 700));
                stage.show();
                host = new OverlayHost();
                host.install(pane);
                finder.setOverlayHost(host);
                finder.show(stage);
            });
            async.onClose(() -> FxTestSupport.runOnFx(stage::close));
            awaitRows(expectedRows);
        }

        void awaitRows(int rows) throws Exception {
            SaveGuardsFxTest.awaitOnFx(async, rows + " listed entries", () -> items.size() == rows);
        }

        /** Delivers a key press to the finder's handler on the FX thread; true when it was consumed. */
        boolean press(KeyCode code) throws Exception {
            return FxTestSupport.callOnFx(() -> {
                KeyEvent e = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
                FxTestSupport.invokeWith(finder, "onKey", KeyEvent.class, e);
                return e.isConsumed();
            });
        }

        void type(String text) throws Exception {
            FxTestSupport.runOnFx(() -> input.setText(text));
        }

        String text() throws Exception {
            return FxTestSupport.callOnFx(input::getText);
        }

        List<String> names() throws Exception {
            return FxTestSupport.callOnFx(
                    () -> items.stream().map(p -> p.getFileName().toString()).toList());
        }

        Path selected() throws Exception {
            return FxTestSupport.callOnFx(() -> list.getSelectionModel().getSelectedItem());
        }

        @Override
        public void close() throws Exception {
            async.close();
        }
    }

    private static Path tree(Path root) throws Exception {
        Files.createDirectory(root.resolve("docs"));
        Files.createDirectory(root.resolve("src"));
        Files.writeString(root.resolve("alpha.txt"), "");
        Files.writeString(root.resolve("Alpine.md"), "");
        Files.writeString(root.resolve("beta.txt"), "");
        Files.writeString(root.resolve(".hidden"), "");
        return root;
    }

    @Test
    void withoutAnOverlayHostShowAndHideDoNothing(@TempDir Path root) throws Exception {
        FxTestSupport.runOnFx(() -> {
            FileFinder finder = new FileFinder(() -> root, p -> {});
            finder.show(null);
            assertFalse(finder.isShown());
            finder.hide(); // nothing to hide, nothing to throw
            assertFalse(finder.isShown());
        });
    }

    @Test
    void showingPrefillsTheStartFolderAndListsFoldersFirstWithoutDotfiles(@TempDir Path root) throws Exception {
        tree(root);
        try (Shown s = new Shown(root, false, 5)) {
            assertTrue(FxTestSupport.callOnFx(s.finder::isShown));
            assertEquals(root + SEP, s.text(), "ready for a name to be typed straight away");
            assertEquals(List.of("docs", "src", "alpha.txt", "Alpine.md", "beta.txt"), s.names());
            assertEquals(root.resolve("docs"), s.selected(), "the first match is highlighted");
            String hint = FxTestSupport.<Label>field(s.finder, "hint").getText();
            assertTrue(hint.contains("tab") && hint.contains("↵"), "the legend names Tab and Enter: " + hint);
        }
    }

    @Test
    void aStartFolderAlreadyEndingInASeparatorIsNotGivenASecondOne(@TempDir Path root) throws Exception {
        Path fsRoot = root.getRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                fsRoot != null && fsRoot.toString().endsWith(SEP));
        FxTestSupport.runOnFx(() -> {
            FileFinder finder = new FileFinder(() -> fsRoot, p -> {});
            finder.directoryReader = (dir, directoriesOnly) -> DirectoryListing.UNREADABLE; // never list the real root
            StackPane pane = new StackPane();
            Stage stage = new Stage();
            stage.setScene(new Scene(pane, 400, 300));
            OverlayHost host = new OverlayHost();
            host.install(pane);
            finder.setOverlayHost(host);
            finder.show(stage);
            assertEquals(
                    fsRoot.toString(),
                    FxTestSupport.<TextField>field(finder, "input").getText());
            finder.hide();
        });
    }

    @Test
    void typingFiltersByPrefixWithoutCaseAndALeadingDotRevealsDotfiles(@TempDir Path root) throws Exception {
        tree(root);
        try (Shown s = new Shown(root, false, 5)) {
            s.type(root + SEP + "AL");
            assertEquals(List.of("alpha.txt", "Alpine.md"), s.names());

            s.type(root + SEP + ".");
            assertEquals(List.of(".hidden"), s.names());

            s.type(root + SEP + "zzz");
            assertEquals(List.of(), s.names());
            assertNull(s.selected());

            // A bare name (no folder part) still filters the folder being listed.
            s.type("be");
            assertEquals(List.of("beta.txt"), s.names());
        }
    }

    @Test
    void tabCompletesTheCommonPrefixThenDescendsIntoALoneFolder(@TempDir Path root) throws Exception {
        tree(root);
        Files.writeString(root.resolve("docs").resolve("guide.md"), "");
        try (Shown s = new Shown(root, false, 5)) {
            s.type(root + SEP + "a");
            assertTrue(s.press(KeyCode.TAB));
            assertEquals(root + SEP + "alp", s.text(), "alpha.txt and Alpine.md share 'alp' whatever the case");

            assertTrue(s.press(KeyCode.TAB));
            assertEquals(root + SEP + "alp", s.text(), "nothing more in common, and neither is a lone folder");

            s.type(root + SEP + "be");
            s.press(KeyCode.TAB);
            assertEquals(root + SEP + "beta.txt", s.text(), "a lone file completes to its whole name");
            s.press(KeyCode.TAB);
            assertEquals(root + SEP + "beta.txt", s.text(), "a file is not descended into");

            s.type(root + SEP + "docs");
            s.press(KeyCode.TAB);
            assertEquals(root.resolve("docs") + SEP, s.text(), "a lone, fully typed folder is entered");
            s.awaitRows(1);
            assertEquals(List.of("guide.md"), s.names());

            s.type(root + SEP + "zzz");
            assertTrue(s.press(KeyCode.TAB), "Tab never leaves the field");
            assertEquals(root + SEP + "zzz", s.text(), "nothing to complete");
        }
    }

    @Test
    void enterDescendsIntoAFolderAndOpensAFile(@TempDir Path root) throws Exception {
        tree(root);
        Path guide = Files.writeString(root.resolve("docs").resolve("guide.md"), "");
        try (Shown s = new Shown(root, false, 5)) {
            assertTrue(s.press(KeyCode.ENTER));
            assertEquals(root.resolve("docs") + SEP, s.text(), "Enter on a folder goes into it");
            assertTrue(s.chosen.isEmpty());
            assertTrue(FxTestSupport.callOnFx(s.finder::isShown));
            s.awaitRows(1);

            s.press(KeyCode.ENTER);
            assertEquals(List.of(guide), s.chosen);
            assertFalse(FxTestSupport.callOnFx(s.finder::isShown), "the finder closes once a file is chosen");
        }
    }

    @Test
    void enterOnANameThatDoesNotExistOpensItInTheListedFolder(@TempDir Path root) throws Exception {
        tree(root);
        try (Shown s = new Shown(root, false, 5)) {
            s.type(root + SEP + "new-notes.txt");
            s.press(KeyCode.ENTER);
            assertEquals(List.of(root.resolve("new-notes.txt")), s.chosen);
        }
        try (Shown s = new Shown(root, false, 5)) {
            s.type("bare-name.txt");
            s.press(KeyCode.ENTER);
            assertEquals(
                    List.of(root.resolve("bare-name.txt")),
                    s.chosen,
                    "a bare name belongs to the listed folder, not the working directory");
        }
    }

    @Test
    void enterWithNothingTypedAndNothingListedChoosesNothing(@TempDir Path root) throws Exception {
        try (Shown s = new Shown(root, false, 0)) {
            s.type("");
            assertTrue(s.press(KeyCode.ENTER));
            assertTrue(s.chosen.isEmpty());
            assertTrue(FxTestSupport.callOnFx(s.finder::isShown), "the finder stays open");
        }
    }

    @Test
    void arrowsMoveTheHighlightEscapeClosesAndOtherKeysAreLeftToTheField(@TempDir Path root) throws Exception {
        tree(root);
        try (Shown s = new Shown(root, false, 5)) {
            assertTrue(s.press(KeyCode.DOWN));
            assertEquals(root.resolve("src"), s.selected());
            assertTrue(s.press(KeyCode.UP));
            assertEquals(root.resolve("docs"), s.selected());
            assertTrue(s.press(KeyCode.UP));
            assertEquals(root.resolve("beta.txt"), s.selected(), "Up from the first row wraps to the last");

            assertFalse(s.press(KeyCode.A), "a letter is typed, not handled");
            assertFalse(s.press(KeyCode.LEFT), "caret keys stay with the field");

            assertTrue(s.press(KeyCode.ESCAPE));
            assertFalse(FxTestSupport.callOnFx(s.finder::isShown));
            assertTrue(s.chosen.isEmpty());
        }
    }

    @Test
    void theFolderPickerListsOnlyFoldersTabDescendsAndEnterChooses(@TempDir Path root) throws Exception {
        tree(root);
        Files.createDirectory(root.resolve("docs").resolve("api"));
        Files.writeString(root.resolve("docs").resolve("guide.md"), "");
        try (Shown s = new Shown(root, true, 2)) {
            assertEquals(List.of("docs", "src"), s.names(), "files are left out of a folder picker");

            assertTrue(s.press(KeyCode.TAB));
            assertEquals(root.resolve("docs") + SEP, s.text(), "Tab goes into the highlighted folder");
            assertTrue(s.chosen.isEmpty());
            s.awaitRows(1);
            assertEquals(List.of("api"), s.names());

            s.press(KeyCode.ENTER);
            assertEquals(List.of(root.resolve("docs").resolve("api")), s.chosen, "Enter chooses the highlighted one");
            assertFalse(FxTestSupport.callOnFx(s.finder::isShown));
        }
    }

    @Test
    void theFolderPickerRefusesAPathThatIsNotAFolderAndTabWithNoMatchDoesNothing(@TempDir Path root) throws Exception {
        tree(root);
        try (Shown s = new Shown(root, true, 2)) {
            s.type(root + SEP + "alpha.txt");
            assertEquals(List.of(), s.names());

            assertTrue(s.press(KeyCode.TAB));
            assertEquals(root + SEP + "alpha.txt", s.text());
            assertTrue(s.press(KeyCode.ENTER));

            assertTrue(s.chosen.isEmpty(), "a file is not a folder to choose");
            assertTrue(FxTestSupport.callOnFx(s.finder::isShown), "the picker stays open for another try");
        }
    }

    @Test
    void aRowShowsAFolderWithATrailingSeparatorAndAFileByItsName(@TempDir Path root) throws Exception {
        tree(root);
        try (Shown s = new Shown(root, false, 5)) {
            FxTestSupport.runOnFx(() -> {
                ListCell<Path> cell = s.list.getCellFactory().call(s.list);
                cell.updateListView(s.list);

                updateItem(cell, root.resolve("docs"), false);
                HBox folderRow = (HBox) cell.getGraphic();
                assertEquals("docs" + SEP, ((Label) folderRow.getChildren().get(1)).getText());
                Object folderIcon = folderRow.getChildren().get(0);

                updateItem(cell, root.resolve("alpha.txt"), false);
                HBox fileRow = (HBox) cell.getGraphic();
                assertEquals("alpha.txt", ((Label) fileRow.getChildren().get(1)).getText());
                assertEquals(2, fileRow.getChildren().size(), "an icon and the name, nothing left of the folder row");
                assertTrue(fileRow.getChildren().get(0) != folderIcon, "the recycled row swapped its icon");

                updateItem(cell, null, true);
                assertNull(cell.getGraphic(), "an emptied row shows nothing");
                updateItem(cell, null, false);
                assertNull(cell.getGraphic());
            });
        }
    }

    @Test
    void aPrimaryClickOnARowChoosesItAndOtherClicksDoNot(@TempDir Path root) throws Exception {
        tree(root);
        try (Shown s = new Shown(root, false, 5)) {
            FxTestSupport.runOnFx(() -> {
                ListCell<Path> cell = s.list.getCellFactory().call(s.list);
                cell.updateListView(s.list);

                updateItem(cell, null, true);
                cell.getOnMouseClicked().handle(click(MouseButton.PRIMARY));
                assertTrue(s.chosen.isEmpty(), "an empty row is not a file");

                updateItem(cell, root.resolve("beta.txt"), false);
                cell.getOnMouseClicked().handle(click(MouseButton.SECONDARY));
                assertTrue(s.chosen.isEmpty(), "a right click chooses nothing");

                cell.getOnMouseClicked().handle(click(MouseButton.PRIMARY));
                assertEquals(List.of(root.resolve("beta.txt")), s.chosen);
                assertFalse(s.finder.isShown());
            });
        }
    }

    private static void updateItem(ListCell<Path> cell, Path item, boolean empty) {
        FxTestSupport.call(cell, "updateItem", new Class<?>[] {Path.class, boolean.class}, item, empty);
    }

    private static MouseEvent click(MouseButton button) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                0,
                0,
                0,
                0,
                button,
                1,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                true,
                null);
    }
}
