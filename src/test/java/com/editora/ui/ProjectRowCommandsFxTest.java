package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.editor.MenuChords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project tree's file menu and its commands ({@link ProjectRowCommands}): every row of the menu is tied to the
 * command that does the same to the selected row, and the command does it, or says why not, as the row would.
 */
@Tag("fx")
@org.junit.jupiter.api.extension.ExtendWith(DisposedPanels.class)
class ProjectRowCommandsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A panel over {@code dir} (holding {@code file}), whose Git and Local History actions are recorded. */
    private record Harness(ProjectPanel panel, TreeView<Path> tree, TreeItem<Path> dirItem, TreeItem<Path> fileItem) {}

    private static Harness harness(Path dir, Path file, Map<String, Path> calls) throws Exception {
        ProjectPanel.FileActions actions = (ProjectPanel.FileActions) java.lang.reflect.Proxy.newProxyInstance(
                ProjectRowCommandsFxTest.class.getClassLoader(),
                new Class<?>[] {ProjectPanel.FileActions.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "gitScope" -> GitPathScope.OTHER;
                    case "localHistoryEnabled" -> true;
                    default -> {
                        if (args != null && args.length == 1 && args[0] instanceof Path p) {
                            calls.put(method.getName(), p);
                        }
                        yield method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                    }
                });
        return FxTestSupport.callOnFx(() -> {
            ProjectPanel panel = DisposedPanels.track(new ProjectPanel(p -> {}, (a, b) -> {}, p -> {}, p -> false));
            panel.setFileActions(actions);
            TreeView<Path> tree = FxTestSupport.field(panel, "tree");
            TreeItem<Path> root = new TreeItem<>(dir);
            TreeItem<Path> fileItem = new TreeItem<>(file);
            root.getChildren().add(fileItem);
            root.setExpanded(true);
            tree.setRoot(root);
            return new Harness(panel, tree, root, fileItem);
        });
    }

    @Test
    void everyRowOfTheFileMenuShowsTheCommandThatDoesItToTheSelectedRow(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("notes.txt"), "x");
        Harness h = harness(dir, file, new ConcurrentHashMap<>());
        for (boolean isDir : new boolean[] {false, true}) {
            ContextMenu menu = FxTestSupport.callOnFx(() -> (ContextMenu) FxTestSupport.call(
                    h.panel(),
                    "contextMenuFor",
                    new Class<?>[] {TreeItem.class, boolean.class, boolean.class},
                    isDir ? h.dirItem() : h.fileItem(),
                    isDir,
                    false));
            List<String> untied = new ArrayList<>();
            collectUntied(menu.getItems(), untied);
            assertTrue(untied.isEmpty(), (isDir ? "folder" : "file") + " menu rows with no command: " + untied);
        }
    }

    /**
     * The rows that name a command: all but the file types of "New ▸", which Project: New File in Selected Folder…
     * reaches through its picker of the same types.
     */
    private static void collectUntied(List<MenuItem> items, List<String> untied) {
        for (MenuItem item : items) {
            if (item instanceof Menu sub) {
                if (!tr("newfile.menu.new").equals(sub.getText())) {
                    collectUntied(sub.getItems(), untied);
                } else {
                    for (MenuItem row : sub.getItems()) {
                        if (row instanceof Menu || row instanceof javafx.scene.control.SeparatorMenuItem) {
                            continue;
                        }
                        boolean fileType = MenuChords.commandOf(row) == null;
                        assertTrue(
                                fileType || MenuChords.commandOf(row).startsWith("project."),
                                "New ▸ row " + row.getText());
                    }
                }
            } else if (!(item instanceof javafx.scene.control.SeparatorMenuItem)
                    && MenuChords.commandOf(item) == null) {
                untied.add(item.getText());
            }
        }
    }

    @Test
    void aCommandActsOnTheSelectedRowAsItsMenuRowDoes(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("notes.txt"), "x");
        Map<String, Path> calls = new ConcurrentHashMap<>();
        Harness h = harness(dir, file, calls);
        FxTestSupport.runOnFx(() -> {
            h.tree().getSelectionModel().select(h.fileItem());
            assertNull(h.panel().runRowCommand(ProjectRowCommands.Row.GIT_STAGE));
            assertNull(h.panel().runRowCommand(ProjectRowCommands.Row.COMPARE_HEAD));
            assertNull(h.panel().runRowCommand(ProjectRowCommands.Row.GIT_HISTORY));
            h.tree().getSelectionModel().select(h.dirItem());
            assertNull(h.panel().runRowCommand(ProjectRowCommands.Row.LOCAL_HISTORY));
        });
        assertEquals(file, calls.get("gitStage"));
        assertEquals(file, calls.get("gitCompareWithHead"));
        assertEquals(file, calls.get("gitShowFileHistory"));
        assertEquals(dir, calls.get("showLocalHistory"));
    }

    @Test
    void aCommandSaysWhyWhenTheMenuWouldNotOfferItsRow(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("notes.txt"), "x");
        Map<String, Path> calls = new ConcurrentHashMap<>();
        Harness h = harness(dir, file, calls);
        FxTestSupport.runOnFx(() -> {
            h.tree().getSelectionModel().clearSelection();
            assertEquals(tr("status.project.selectRow"), h.panel().runRowCommand(ProjectRowCommands.Row.RENAME));
            h.tree().getSelectionModel().select(h.dirItem());
            String delete = DebugPanelText.bareTitle(tr("command.project.deleteSelected"));
            assertEquals(
                    tr("status.project.rowIsFolder", delete), h.panel().runRowCommand(ProjectRowCommands.Row.DELETE));
            assertEquals(
                    tr("status.project.rowIsFolder", DebugPanelText.bareTitle(tr("command.project.unstageSelected"))),
                    h.panel().runRowCommand(ProjectRowCommands.Row.GIT_UNSTAGE));
            h.tree().getSelectionModel().select(h.fileItem());
            assertEquals(
                    tr(
                            "status.project.rowIsFile",
                            DebugPanelText.bareTitle(tr("command.project.findInSelectedFolder"))),
                    h.panel().runRowCommand(ProjectRowCommands.Row.FIND_IN_FOLDER));
            assertEquals(tr("status.project.noMoveToUndo"), h.panel().runRowCommand(ProjectRowCommands.Row.UNDO_MOVE));
        });
        assertTrue(calls.isEmpty(), "nothing ran: " + calls);
    }
}
