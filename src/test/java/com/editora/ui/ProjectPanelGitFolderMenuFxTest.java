package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Project-tree folder Git comparisons are present and route the selected directory unchanged. */
@Tag("fx")
class ProjectPanelGitFolderMenuFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void folderOffersHeadBranchTagAndRevisionComparisons(@TempDir Path folder) throws Exception {
        Map<String, Path> calls = new ConcurrentHashMap<>();
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel created = new ProjectPanel(f -> {}, (a, b) -> {}, f -> {}, f -> false);
            created.setFileActions(new ProjectPanel.FileActions() {
                @Override
                public boolean localHistoryEnabled() {
                    return true;
                }

                @Override
                public void showLocalHistory(Path file) {}

                @Override
                public boolean gitAvailable() {
                    return true;
                }

                @Override
                public void gitShowFileHistory(Path file) {}

                @Override
                public void gitCompareWithHead(Path file) {
                    calls.put("head", file);
                }

                @Override
                public void gitCompareWithBranch(Path file) {
                    calls.put("branch", file);
                }

                @Override
                public void gitCompareWithTag(Path file) {
                    calls.put("tag", file);
                }

                @Override
                public void gitCompareWithRevision(Path file) {
                    calls.put("revision", file);
                }

                @Override
                public void gitAnnotate(Path file) {}

                @Override
                public void gitStage(Path file) {}

                @Override
                public void gitUnstage(Path file) {}

                @Override
                public void gitRevert(Path file) {}

                @Override
                public void gitAddToGitignore(Path file) {}
            });
            return created;
        });

        ContextMenu context = FxTestSupport.callOnFx(() -> (ContextMenu) FxTestSupport.call(
                panel,
                "contextMenuFor",
                new Class<?>[] {TreeItem.class, boolean.class, boolean.class},
                new TreeItem<>(folder),
                true,
                false));
        Menu git = (Menu) context.getItems().stream()
                .filter(item -> item instanceof Menu && tr("project.menu.git").equals(item.getText()))
                .findFirst()
                .orElseThrow();

        fire(git, "project.menu.git.compareHead");
        fire(git, "project.menu.git.compareBranch");
        fire(git, "project.menu.git.compareTag");
        fire(git, "project.menu.git.compareRevision");

        assertEquals(Map.of("head", folder, "branch", folder, "tag", folder, "revision", folder), calls);
    }

    @Test
    void folderOffersBookmarkAndPersonalNoteActions(@TempDir Path folder) throws Exception {
        AtomicReference<Path> bookmarked = new AtomicReference<>();
        AtomicReference<Path> noted = new AtomicReference<>();
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel created = new ProjectPanel(f -> {}, (a, b) -> {}, f -> {}, f -> false);
            created.setMarkerActions(new ProjectPanel.MarkerActions() {
                @Override
                public boolean personalNotesEnabled() {
                    return true;
                }

                @Override
                public boolean hasBookmarks(Path path) {
                    return false;
                }

                @Override
                public boolean hasPersonalNotes(Path path) {
                    return false;
                }

                @Override
                public void addBookmark(Path path) {
                    bookmarked.set(path);
                }

                @Override
                public void addPersonalNote(Path path) {
                    noted.set(path);
                }
            });
            return created;
        });
        ContextMenu context = FxTestSupport.callOnFx(() -> (ContextMenu) FxTestSupport.call(
                panel,
                "contextMenuFor",
                new Class<?>[] {TreeItem.class, boolean.class, boolean.class},
                new TreeItem<>(folder),
                true,
                false));

        FxTestSupport.runOnFx(() -> {
            context.getItems().stream()
                    .filter(item -> tr("project.menu.addFolderBookmark").equals(item.getText()))
                    .findFirst()
                    .orElseThrow()
                    .fire();
            context.getItems().stream()
                    .filter(item -> tr("project.menu.addFolderPersonalNote").equals(item.getText()))
                    .findFirst()
                    .orElseThrow()
                    .fire();
        });
        assertSame(folder, bookmarked.get());
        assertSame(folder, noted.get());
    }

    @Test
    void folderPersonalNotesAppearInTreeTooltip(@TempDir Path root) throws Exception {
        Path folder = Files.createDirectory(root.resolve("docs"));
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel created = new ProjectPanel(f -> {}, (a, b) -> {}, f -> {}, f -> false);
            created.setMarkerActions(new ProjectPanel.MarkerActions() {
                @Override
                public boolean personalNotesEnabled() {
                    return true;
                }

                @Override
                public boolean hasBookmarks(Path path) {
                    return false;
                }

                @Override
                public boolean hasPersonalNotes(Path path) {
                    return folder.equals(path);
                }

                @Override
                public String personalNotesTooltip(Path path) {
                    return "Keep generated docs here";
                }

                @Override
                public void addBookmark(Path path) {}

                @Override
                public void addPersonalNote(Path path) {}
            });
            created.setRoot(root);
            return created;
        });

        Tooltip tooltip = FxTestSupport.callOnFx(() -> {
            @SuppressWarnings("unchecked")
            TreeView<Path> tree = FxTestSupport.field(panel, "tree");
            TreeItem<Path> folderItem = tree.getRoot().getChildren().stream()
                    .filter(item -> folder.equals(item.getValue()))
                    .findFirst()
                    .orElseThrow();
            TreeCell<Path> cell = tree.getCellFactory().call(tree);
            FxTestSupport.call(cell, "updateTreeItem", new Class<?>[] {TreeItem.class}, folderItem);
            FxTestSupport.call(cell, "updateItem", new Class<?>[] {Path.class, boolean.class}, folder, false);
            return cell.getTooltip();
        });

        assertEquals("Keep generated docs here", tooltip.getText());
        assertTrue(tooltip.getStyleClass().contains("personal-note-tooltip"));
        assertTrue(tooltip.getStyle().contains("#fff9c4"), "folder notes must use the editor preview's yellow");
    }

    @Test
    void fileStateMarkersFollowTheFileName(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("README.md"), "# Test");
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel created = new ProjectPanel(f -> {}, (a, b) -> {}, f -> {}, f -> false, file::equals);
            created.setMarkerActions(new ProjectPanel.MarkerActions() {
                @Override
                public boolean personalNotesEnabled() {
                    return true;
                }

                @Override
                public boolean hasBookmarks(Path path) {
                    return file.equals(path);
                }

                @Override
                public boolean hasPersonalNotes(Path path) {
                    return file.equals(path);
                }

                @Override
                public void addBookmark(Path path) {}

                @Override
                public void addPersonalNote(Path path) {}
            });
            created.setRoot(root);
            created.setActiveFile(file);
            return created;
        });

        FxTestSupport.runOnFx(() -> {
            @SuppressWarnings("unchecked")
            TreeView<Path> tree = FxTestSupport.field(panel, "tree");
            TreeCell<Path> cell = tree.getCellFactory().call(tree);
            FxTestSupport.call(cell, "updateItem", new Class<?>[] {Path.class, boolean.class}, file, false);

            HBox row = (HBox) cell.getGraphic();
            assertEquals("README.md", ((Label) row.getChildren().get(1)).getText());
            assertEquals("◉", ((Label) row.getChildren().get(2)).getText());
            assertTrue(row.getChildren()
                            .get(3)
                            .lookupAll(".project-bookmark-indicator")
                            .size()
                    == 1);
            assertTrue(row.getChildren()
                            .get(4)
                            .lookupAll(".project-note-indicator")
                            .size()
                    == 1);
            assertEquals("README.md", cell.getAccessibleText());
        });
    }

    @Test
    void revealFolderForcesTreeModeClearsFilterAndSelectsFolder(@TempDir Path root) throws Exception {
        Path parent = Files.createDirectory(root.resolve("src"));
        Path folder = Files.createDirectory(parent.resolve("main"));
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel created = new ProjectPanel(f -> {}, (a, b) -> {}, f -> {}, f -> false);
            created.setRoot(root);
            return created;
        });

        FxTestSupport.runOnFx(() -> {
            ToggleButton map = FxTestSupport.field(panel, "mapModeButton");
            TextField filter = FxTestSupport.field(panel, "filterField");
            map.setSelected(true);
            filter.setText("something else");
            panel.revealPathInTree(folder);
        });

        ToggleButton treeMode = FxTestSupport.field(panel, "treeMode");
        TextField filter = FxTestSupport.field(panel, "filterField");
        @SuppressWarnings("unchecked")
        TreeView<Path> tree = FxTestSupport.field(panel, "tree");
        assertEquals(true, FxTestSupport.callOnFx(treeMode::isSelected));
        assertEquals("", FxTestSupport.callOnFx(filter::getText));
        assertEquals(
                folder,
                FxTestSupport.callOnFx(
                        () -> tree.getSelectionModel().getSelectedItem().getValue()));
    }

    private static void fire(Menu menu, String labelKey) throws Exception {
        MenuItem item = menu.getItems().stream()
                .filter(candidate -> tr(labelKey).equals(candidate.getText()))
                .findFirst()
                .orElseThrow();
        FxTestSupport.runOnFx(item::fire);
    }
}
