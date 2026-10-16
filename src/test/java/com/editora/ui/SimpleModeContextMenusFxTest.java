package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.input.ContextMenuEvent;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simple UI mode trims the editor's and the Project tree's right-click menus as it trims the menu bar (the tab
 * menu has its own test in {@link TabContextMenuFxTest}), and turning the mode off brings them back in full.
 */
@Tag("fx")
class SimpleModeContextMenusFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void editorMenuKeepsTheEditingActionsOnly(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("notes.txt"), "Hello world\n");
        FxWindowFixture fx = open(dir, file);
        try {
            FxTestSupport.runOnFx(() -> {
                MainController c = fx.controller;
                EditorBuffer buffer = (EditorBuffer) FxTestSupport.call(c, "activeBuffer", new Class<?>[] {});
                List<String> full = editorMenu(buffer);
                assertTrue(full.contains(tr("editmenu.addBookmark")), full.toString());

                c.chrome.toggleSimpleMode();
                assertEquals(
                        List.of(
                                tr("editmenu.cut"),
                                tr("editmenu.copy"),
                                tr("editmenu.paste"),
                                "-",
                                tr("editmenu.undo"),
                                tr("editmenu.redo"),
                                "-",
                                tr("editmenu.selectAll"),
                                "-",
                                tr("menu.print"),
                                tr("menu.exportPdf")),
                        editorMenu(buffer),
                        "Print / Export to PDF stay, as on the Simple File menu");

                c.chrome.toggleSimpleMode();
                assertEquals(full, editorMenu(buffer), "turning Simple mode off restores the menu");
            });
        } finally {
            fx.dispose();
        }
    }

    @Test
    void projectTreeMenuKeepsFileManagementOnly(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("notes.txt"), "one\n");
        FxWindowFixture fx = open(dir, file);
        try {
            FxTestSupport.runOnFx(() -> {
                MainController c = fx.controller;
                ProjectPanel panel = c.projectPanel();
                List<String> fullFile = treeMenu(panel, file, false);
                for (String key : List.of(
                        "project.menu.addBookmark",
                        "project.menu.openTerminal",
                        "project.menu.localHistory",
                        "project.menu.git")) {
                    assertTrue(fullFile.stream().anyMatch(e -> e.startsWith(tr(key))), key + " in " + fullFile);
                }
                List<String> fullDir = treeMenu(panel, dir, true);

                c.chrome.toggleSimpleMode();
                assertEquals(
                        List.of(
                                tr("project.menu.rename"),
                                tr("project.menu.delete"),
                                "-",
                                tr("project.menu.revealInFileManager")),
                        treeMenu(panel, file, false));
                String newFile = ProjectPanel.labelFor(com.editora.template.NewFileCatalog.PLAIN);
                List<String> simpleDir = treeMenu(panel, dir, true);
                assertEquals(
                        List.of(
                                tr("newfile.menu.new") + "[" + newFile + ", " + tr("project.menu.newFolder") + "]",
                                tr("project.menu.rename"),
                                "-",
                                tr("project.menu.revealInFileManager")),
                        simpleDir,
                        "New offers a file or a folder only");

                c.chrome.toggleSimpleMode();
                assertEquals(fullFile, treeMenu(panel, file, false), "turning Simple mode off restores the menu");
                assertEquals(fullDir, treeMenu(panel, dir, true));
            });
        } finally {
            fx.dispose();
        }
    }

    private static FxWindowFixture open(Path dir, Path file) throws Exception {
        return FxWindowFixture.create(
                Files.createDirectories(dir.resolve("config")),
                false,
                false,
                false,
                List.of(new MainController.OpenTarget(file, 0, 0)),
                c -> {});
    }

    /** Right-clicks the start of the buffer's first line and returns the menu as drawn. FX thread. */
    private static List<String> editorMenu(EditorBuffer buffer) {
        CodeArea area = buffer.getArea();
        area.getOnContextMenuRequested()
                .handle(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 2, 2, 2, 2, false, null));
        ContextMenu menu = FxTestSupport.field(buffer, "contextMenu");
        menu.hide();
        return entries(menu.getItems());
    }

    /** The Project tree's menu for {@code path}, as drawn, submenus' entries in brackets. FX thread. */
    private static List<String> treeMenu(ProjectPanel panel, Path path, boolean directory) {
        ContextMenu menu = (ContextMenu) FxTestSupport.call(
                panel,
                "contextMenuFor",
                new Class<?>[] {TreeItem.class, boolean.class, boolean.class},
                new TreeItem<>(path),
                directory,
                false);
        return entries(menu.getItems());
    }

    /** Visible items by label, a visible separator as {@code "-"}, a submenu as {@code label[entries]}. */
    private static List<String> entries(List<MenuItem> items) {
        return items.stream()
                .filter(MenuItem::isVisible)
                .map(item -> item instanceof SeparatorMenuItem
                        ? "-"
                        : item instanceof Menu sub ? sub.getText() + entries(sub.getItems()) : item.getText())
                .toList();
    }
}
