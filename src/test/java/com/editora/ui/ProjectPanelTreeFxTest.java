package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project tree driven from the keyboard and the mouse: moving through the rows, opening, expanding and
 * collapsing, renaming, creating a folder, filtering by name and revealing a path.
 */
@Tag("fx")
class ProjectPanelTreeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void arrowsAndTheEmacsChordsMoveThroughTheRowsWrappingAtTheEnds(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                assertEquals(
                        List.of(dir.getFileName().toString(), "docs", "src", "alpha.txt", "beta.txt"),
                        r.rows(),
                        "folders first, dotfiles hidden");

                assertTrue(r.press(r.tree, KeyCode.UP, false).isConsumed());
                assertEquals(r.beta, r.selected(), "Up with nothing selected starts at the last row");
                assertTrue(r.press(r.tree, KeyCode.DOWN, false).isConsumed());
                assertEquals(r.root, r.selected(), "Down from the last row wraps to the first");
                assertTrue(r.press(r.tree, KeyCode.N, true).isConsumed());
                assertEquals(r.docs, r.selected());
                assertTrue(r.press(r.tree, KeyCode.P, true).isConsumed());
                assertEquals(r.root, r.selected());

                r.tree.getSelectionModel().clearSelection();
                r.press(r.tree, KeyCode.DOWN, false);
                assertEquals(r.root, r.selected(), "Down with nothing selected starts at the first row");

                assertFalse(r.press(r.tree, KeyCode.N, false).isConsumed(), "a bare letter is not navigation");
                assertFalse(r.press(r.tree, KeyCode.X, true).isConsumed(), "nor is a chord the tree does not bind");
                assertEquals(r.root, r.selected());

                // Arrow navigation replaces a multi-selection rather than growing it.
                r.tree.getSelectionModel().selectIndices(1, 2, 3);
                r.press(r.tree, KeyCode.DOWN, false);
                assertEquals(1, r.tree.getSelectionModel().getSelectedItems().size());
            });
        }
    }

    @Test
    void controlFAndBExpandDescendCollapseAndAscend(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                r.select(r.docs);
                assertTrue(r.press(r.tree, KeyCode.F, true).isConsumed());
                assertTrue(r.item(r.docs).isExpanded(), "C-f opens a closed folder");
            });
            r.awaitChildren(r.docs, 1);
            FxTestSupport.runOnFx(() -> {
                r.press(r.tree, KeyCode.F, true);
                assertEquals(r.guide, r.selected(), "C-f on an open folder steps into it");

                assertTrue(r.press(r.tree, KeyCode.B, true).isConsumed());
                assertEquals(r.docs, r.selected(), "C-b on a file goes up to its folder");
                r.press(r.tree, KeyCode.B, true);
                assertFalse(r.item(r.docs).isExpanded(), "C-b on an open folder closes it");
                r.press(r.tree, KeyCode.B, true);
                assertEquals(r.root, r.selected(), "C-b on a closed top-level folder moves up a row");

                r.tree.getSelectionModel().clearSelection();
                r.press(r.tree, KeyCode.B, true);
                assertEquals(r.beta, r.selected(), "C-b with nothing selected starts from the bottom");
                r.tree.getSelectionModel().clearSelection();
                r.press(r.tree, KeyCode.F, true);
                assertEquals(r.root, r.selected(), "C-f with nothing selected starts from the top");
            });
        }
    }

    @Test
    void enterAndControlMOpenAFileAndToggleAFolder(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                assertTrue(r.press(r.tree, KeyCode.ENTER, false).isConsumed());
                assertTrue(r.opened.isEmpty(), "nothing selected, nothing opened");

                r.select(r.alpha);
                r.press(r.tree, KeyCode.ENTER, false);
                assertEquals(List.of(r.alpha), r.opened);
                assertTrue(r.press(r.tree, KeyCode.M, true).isConsumed());
                assertEquals(List.of(r.alpha, r.alpha), r.opened);

                r.select(r.src);
                r.press(r.tree, KeyCode.ENTER, false);
                assertTrue(r.item(r.src).isExpanded(), "Enter opens a folder row instead of an editor tab");
                r.press(r.tree, KeyCode.ENTER, false);
                assertFalse(r.item(r.src).isExpanded());
                assertEquals(2, r.opened.size());
            });
        }
    }

    @Test
    void aDoubleClickOpensTheSelectedFileAndOtherClicksDoNot(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                r.select(r.beta);
                r.tree.getOnMouseClicked().handle(click(MouseButton.PRIMARY, 1));
                r.tree.getOnMouseClicked().handle(click(MouseButton.SECONDARY, 2));
                assertTrue(r.opened.isEmpty());

                r.tree.getOnMouseClicked().handle(click(MouseButton.PRIMARY, 2));
                assertEquals(List.of(r.beta), r.opened);
            });
        }
    }

    @Test
    void inMapModeTheTreeKeysAreLeftAloneAndTheFilterFieldDrivesTheMap(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                // In Tree mode the filter's Map handler stays out of the way: FilterFieldNav moves the tree.
                javafx.event.Event.fireEvent(r.filter, key(KeyCode.N, true));
                assertEquals(r.root, r.selected(), "C-n from the filter moved the tree selection");

                r.panel.toggleMapView();
                assertTrue(r.panel.isMapMode());
                r.select(r.alpha);
                assertFalse(r.press(r.tree, KeyCode.ENTER, false).isConsumed(), "the Map owns its keys");
                assertFalse(r.press(r.tree, KeyCode.F2, false).isConsumed());
                assertTrue(r.opened.isEmpty() && r.prompts.isEmpty());
            });
            ProjectMapView map = FxTestSupport.field(r.panel, "mapView");
            javafx.scene.layout.Region surface = FxTestSupport.field(map, "surface");
            r.await("the map to list the project", () -> mapContains(surface, r.beta));
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(surface, "select", new Class<?>[] {Path.class}, r.alpha);
                javafx.event.Event.fireEvent(r.filter, key(KeyCode.N, true));
                assertEquals(r.beta, mapSelection(surface), "C-n in the filter field moves the Map's selection");
                javafx.event.Event.fireEvent(r.filter, key(KeyCode.P, true));
                assertEquals(r.alpha, mapSelection(surface), "and C-p moves it back");
                javafx.event.Event.fireEvent(r.filter, key(KeyCode.N, false));
                javafx.event.Event.fireEvent(r.filter, key(KeyCode.P, false));
                assertEquals(r.alpha, mapSelection(surface), "bare letters are typed, not navigation");
                r.select(r.beta); // the hidden tree must not be what Enter opens

                javafx.event.Event.fireEvent(r.filter, key(KeyCode.ENTER, false));
                assertEquals(List.of(r.alpha), r.opened, "Enter in the filter opens the Map's selection");

                javafx.event.Event.fireEvent(r.filter, key(KeyCode.DOWN, false));
                assertTrue(
                        isWithin(r.stage.getScene().getFocusOwner(), map),
                        "Down hands the keyboard to the Map, not to the hidden tree");

                r.panel.toggleMapView();
                assertFalse(r.panel.isMapMode());
                assertEquals(r.tree, r.stage.getScene().getFocusOwner(), "toggling back focuses the tree");
            });
        }
    }

    @Test
    void theSelectedViewModeCannotBeSwitchedOffByClickingItAgain(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                ToggleButton treeMode = FxTestSupport.field(r.panel, "treeMode");
                treeMode.setSelected(false);
                assertTrue(treeMode.isSelected(), "one of Tree / Map is always chosen");
                assertFalse(r.panel.isMapMode());
            });
        }
    }

    @Test
    void f2RenamesTheSelectedRowOnDiskAndTellsTheWindow(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            Path target = dir.resolve("renamed.txt");
            FxTestSupport.runOnFx(() -> {
                r.select(r.alpha);
                assertTrue(r.press(r.tree, KeyCode.F2, false).isConsumed());
                assertEquals(List.of(tr("project.renameTitle") + "|alpha.txt"), r.prompts);

                r.promptAccept.accept("   ");
                r.promptAccept.accept("alpha.txt");
                r.promptAccept.accept("beta.txt");
                assertTrue(r.renamed.isEmpty(), "a blank name, the same name and a taken name rename nothing");
                assertTrue(Files.exists(r.alpha));
                assertEquals("beta\n", read(r.beta), "the file already called beta.txt is untouched");

                r.promptAccept.accept("  renamed.txt ");
                assertEquals(List.of(List.of(r.alpha, target)), r.renamed);
                assertEquals("alpha\n", read(target));
                assertFalse(Files.exists(r.alpha));
            });
            r.await("the renamed row", () -> r.item(target) != null && r.item(r.alpha) == null);
        }
    }

    @Test
    void theProjectRootIsNeverRenamedAndWithoutAPromptF2DoesNothing(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                r.select(r.root);
                assertTrue(r.press(r.tree, KeyCode.F2, false).isConsumed());
                assertTrue(r.prompts.isEmpty(), "renaming the root would move the project away from itself");
                assertNull(RigMenus.entryOrNull(r.menu(r.item(r.root), true, true), "project.menu.rename"));

                r.panel.setPrompt(null);
                r.select(r.alpha);
                assertTrue(r.press(r.tree, KeyCode.F2, false).isConsumed());
                assertTrue(r.prompts.isEmpty());
                ContextMenu menu = r.menu(r.item(r.docs), true, false);
                ProjectPanelRig.entry(menu.getItems(), "project.menu.newFolder").fire();
                assertTrue(r.prompts.isEmpty(), "no prompt to ask for the folder's name");
            });
        }
    }

    @Test
    void aRenameThatFailsOnDiskSaysWhyAndReportsNoRename(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            r.answerDialogs(ButtonBar.ButtonData.OK_DONE);
            FxTestSupport.runOnFx(() -> {
                r.select(r.alpha);
                r.press(r.tree, KeyCode.F2, false);
            });
            Files.delete(r.alpha); // gone behind the prompt's back

            FxTestSupport.runOnFx(() -> r.promptAccept.accept("renamed.txt"));

            assertEquals(1, r.dialogs.size());
            assertTrue(
                    r.dialogs
                            .get(0)
                            .startsWith(
                                    tr("project.renameError", "alpha.txt", "").trim()),
                    "the error names the file: " + r.dialogs.get(0));
            assertTrue(r.renamed.isEmpty());
            assertFalse(Files.exists(dir.resolve("renamed.txt")));
        }
    }

    @Test
    void newFolderCreatesItUnderTheClickedFolderAndRevealsIt(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                TreeItem<Path> docs = r.item(r.docs);
                ProjectPanelRig.entry(r.menu(docs, true, false).getItems(), "project.menu.newFolder")
                        .fire();
                assertEquals(List.of(tr("project.newFolderTitle") + "|"), r.prompts);

                r.promptAccept.accept("  ");
                assertFalse(docs.isExpanded(), "a blank name creates nothing");

                r.promptAccept.accept(" api ");
                assertTrue(Files.isDirectory(r.docs.resolve("api")));
                assertTrue(docs.isExpanded(), "the parent is opened so the new folder is on show");
            });
            r.awaitChildren(r.docs, 2);
            assertEquals(
                    List.of("api", "guide.md"),
                    FxTestSupport.callOnFx(() -> r.item(r.docs).getChildren().stream()
                            .map(i -> i.getValue().getFileName().toString())
                            .toList()));
        }
    }

    @Test
    void newFolderRefusesATakenNameAndReportsAFailureToCreate(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            r.answerDialogs(ButtonBar.ButtonData.OK_DONE);
            FxTestSupport.runOnFx(() -> {
                ProjectPanelRig.entry(r.menu(r.item(r.root), true, true).getItems(), "project.menu.newFolder")
                        .fire();
                r.promptAccept.accept("docs");
            });
            assertEquals(List.of(tr("project.newFolderExists", "docs")), List.copyOf(r.dialogs));

            // A folder cannot be created underneath a regular file.
            String under = "alpha.txt" + java.io.File.separator + "inside";
            FxTestSupport.runOnFx(() -> r.promptAccept.accept(under));
            assertEquals(2, r.dialogs.size());
            assertTrue(
                    r.dialogs
                            .get(1)
                            .startsWith(tr("project.newFolderError", under, "").trim()),
                    "the error names the folder: " + r.dialogs.get(1));
            assertTrue(Files.isRegularFile(r.alpha));
        }
    }

    @Test
    void typingInTheFilterListsMatchesByRelativePathAndClearingItRestoresTheTree(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> r.filter.setText("helper"));
            r.await("the search result", () -> r.item(r.helper) != null);
            FxTestSupport.runOnFx(() -> {
                assertEquals(List.of(dir.getFileName().toString(), "Helper.java"), r.rows(), "a flat list of matches");
                assertEquals(
                        List.of(dir.relativize(r.helper).toString()),
                        ProjectPanelRig.labels(r.cell(r.helper)),
                        "a match is shown by its path from the project root");
                assertEquals(
                        List.of(dir.getFileName().toString()),
                        ProjectPanelRig.labels(r.cell(r.root)),
                        "the root row keeps its own name");
            });

            FxTestSupport.runOnFx(() -> r.filter.setText("no-such-file-anywhere"));
            r.await("the empty result", () -> r.rows().size() == 1 && r.item(r.helper) == null);

            FxTestSupport.runOnFx(() -> r.filter.clear());
            r.awaitChildren(r.root, 4);
            assertEquals(
                    List.of(dir.getFileName().toString(), "docs", "src", "alpha.txt", "beta.txt"),
                    FxTestSupport.callOnFx(r::rows));
        }
    }

    @Test
    void theFilterSkipsGitignoredFilesUntilToldNotTo(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            Files.writeString(dir.resolve(".gitignore"), "build/\n");
            Path ignored = Files.writeString(
                    Files.createDirectory(dir.resolve("build")).resolve("helper.o"), "");

            FxTestSupport.runOnFx(() -> r.filter.setText("helper"));
            r.await("the search result", () -> r.item(r.helper) != null);
            assertNull(FxTestSupport.callOnFx(() -> r.item(ignored)), "an ignored build output is not a match");

            FxTestSupport.runOnFx(() -> {
                r.panel.setRespectGitignore(true); // no change: nothing is searched again
                r.panel.setRespectGitignore(false);
            });
            r.await("the ignored match", () -> r.item(ignored) != null && r.item(r.helper) != null);
        }
    }

    @Test
    void revealPathOpensTheFoldersDownToAFileAndSelectsIt(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                r.panel.revealPath(null);
                r.panel.revealPath(dir.resolveSibling("somewhere-else.txt"));
                assertNull(r.selected(), "a path outside the project selects nothing");

                r.panel.revealPath(r.helper);
            });
            r.await("the revealed file", () -> r.helper.equals(r.selected()));
            FxTestSupport.runOnFx(() -> {
                assertTrue(r.item(r.src).isExpanded() && r.item(r.util).isExpanded());

                r.panel.revealPath(r.docs.resolve("missing.md"));
            });
            r.awaitChildren(r.docs, 1);
            FxTestSupport.runOnFx(() -> {
                assertEquals(r.helper, r.selected(), "a file that is not there leaves the selection alone");
                assertNull(FxTestSupport.field(r.panel, "pendingTreeReveal"), "and the reveal is not left pending");
            });
        }
    }

    @Test
    void revealPathInTreeDropsTheFilterFirst(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> r.filter.setText("helper"));
            r.await("the search result", () -> r.item(r.helper) != null);

            FxTestSupport.runOnFx(() -> r.panel.revealPathInTree(r.guide));

            r.await("the revealed file", () -> r.guide.equals(r.selected()));
            FxTestSupport.runOnFx(() -> {
                assertEquals("", r.filter.getText());
                assertEquals(
                        List.of(dir.getFileName().toString(), "docs", "guide.md", "src", "alpha.txt", "beta.txt"),
                        r.rows(),
                        "the whole tree is back, opened down to the file");
            });
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static javafx.scene.input.KeyEvent key(KeyCode code, boolean control) {
        return new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false);
    }

    private static boolean isWithin(javafx.scene.Node node, javafx.scene.Node ancestor) {
        for (javafx.scene.Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** The path of the Map surface's selected entry, or null while it has none. */
    @SuppressWarnings("unchecked")
    private static Path mapSelection(javafx.scene.layout.Region surface) {
        return ((java.util.Optional<ProjectMapModel.Entry>)
                        FxTestSupport.call(surface, "selectedEntry", new Class<?>[0]))
                .map(ProjectMapModel.Entry::path)
                .orElse(null);
    }

    private static boolean mapContains(javafx.scene.layout.Region surface, Path path) {
        return (boolean) FxTestSupport.call(surface, "contains", new Class<?>[] {Path.class}, path);
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
                true,
                false,
                true,
                null);
    }

    /** Menu lookups that may legitimately find nothing. */
    private static final class RigMenus {
        static javafx.scene.control.MenuItem entryOrNull(ContextMenu menu, String key) {
            return ProjectPanelRig.entry(menu.getItems(), key);
        }
    }
}
