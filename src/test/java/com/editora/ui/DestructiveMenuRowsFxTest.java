package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.command.CommandRegistry;
import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A destructive menu row (one that deletes, discards, reverts or resets) carries the {@code danger} style
 * class, which draws its text in the danger colour; the rows beside it do not. Checked on the menus a user
 * meets most: a Project row's, a Commit row's, a tab's, and the menu bar.
 */
@Tag("fx")
@org.junit.jupiter.api.extension.ExtendWith(DisposedPanels.class)
class DestructiveMenuRowsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static boolean danger(MenuItem item) {
        return item.getStyleClass().contains("danger");
    }

    private static MenuItem row(List<MenuItem> items, String key) {
        return items.stream()
                .filter(i -> tr(key).equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + key + " in "
                        + items.stream().map(MenuItem::getText).toList()));
    }

    private static Menu submenu(List<MenuItem> items, String key) {
        return (Menu) row(items, key);
    }

    /** An implementation of {@code type} whose methods do nothing and answer false, an empty list or null. */
    @SuppressWarnings("unchecked")
    private static <T> T inert(Class<T> type, Map<String, Object> answers) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(
                DestructiveMenuRowsFxTest.class.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                    if (answers.containsKey(method.getName())) {
                        return answers.get(method.getName());
                    }
                    Class<?> returns = method.getReturnType();
                    return returns == boolean.class ? Boolean.FALSE : returns == List.class ? List.of() : null;
                });
    }

    @Test
    void theProjectRowMenuMarksDeleteAndRevert(@TempDir Path folder) throws Exception {
        Path file = Files.writeString(folder.resolve("notes.txt"), "x");
        ContextMenu menu = FxTestSupport.callOnFx(() -> {
            ProjectPanel panel = DisposedPanels.track(new ProjectPanel(f -> {}, (a, b) -> {}, f -> {}, f -> false));
            panel.setFileActions(inert(
                    ProjectPanel.FileActions.class,
                    Map.of("gitScope", GitPathScope.ACTIVE, "localHistoryEnabled", true)));
            return (ContextMenu) FxTestSupport.call(
                    panel,
                    "contextMenuFor",
                    new Class<?>[] {TreeItem.class, boolean.class, boolean.class},
                    new TreeItem<>(file),
                    false,
                    false);
        });
        assertTrue(danger(row(menu.getItems(), "project.menu.delete")), "Delete… is destructive");
        assertFalse(danger(row(menu.getItems(), "project.menu.rename")), "Rename… is not");
        Menu git = submenu(menu.getItems(), "project.menu.git");
        assertTrue(danger(row(git.getItems(), "project.menu.git.revert")), "Revert… discards the file's changes");
        assertFalse(danger(row(git.getItems(), "project.menu.git.stage")));
        assertFalse(danger(row(git.getItems(), "project.menu.git.unstage")), "unstaging loses nothing");
    }

    @Test
    void theCommitRowMenuMarksDiscardAndAcceptingOneSide() throws Exception {
        GitPanel panel = FxTestSupport.callOnFx(() -> new GitPanel(inert(GitPanel.Actions.class, Map.of())));
        GitStatus status = new GitStatus(
                true,
                "main",
                "",
                0,
                0,
                List.of(new FileEntry("story.txt", 'U', 'U', null), new FileEntry("edited.txt", '.', 'M', null)));
        FxTestSupport.runOnFx(() -> {
            panel.setStatus(status);
            @SuppressWarnings("unchecked")
            TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
            ContextMenu conflict = rowMenu(panel, tree.getRoot().getChildren().get(0));
            assertTrue(danger(row(conflict.getItems(), "gitpanel.menu.acceptOurs")), "the other side is discarded");
            assertTrue(danger(row(conflict.getItems(), "gitpanel.menu.acceptTheirs")));
            assertFalse(danger(row(conflict.getItems(), "gitpanel.menu.markResolved")));

            ContextMenu changed = rowMenu(panel, tree.getRoot().getChildren().get(1));
            assertTrue(danger(row(changed.getItems(), "gitpanel.menu.discard")), "Discard changes is destructive");
            assertFalse(danger(row(changed.getItems(), "gitpanel.menu.stage")), "Stage is not");

            ContextMenu group = (ContextMenu)
                    FxTestSupport.call(panel, "buildGroupMenu", new Class<?>[] {groupType()}, group("MODIFIED"));
            assertTrue(danger(row(group.getItems(), "gitpanel.menu.discardGroup")), "Discard All Changes…");
            assertFalse(danger(row(group.getItems(), "gitpanel.menu.stageGroup")));
        });
    }

    /** {@code GitPanel.Group}, which is private to the panel. */
    private static Class<?> groupType() {
        try {
            return Class.forName("com.editora.ui.GitPanel$Group");
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object group(String name) {
        return Enum.valueOf((Class<? extends Enum>) groupType(), name);
    }

    /** The menu of the first file row of {@code group}, as a right-click on it builds it. */
    private static ContextMenu rowMenu(GitPanel panel, TreeItem<Object> group) {
        Object fileRow = group.getChildren().get(0).getValue();
        ContextMenu menu =
                (ContextMenu) FxTestSupport.call(panel, "buildMenu", new Class<?>[] {List.class}, List.of(fileRow));
        assertNotNull(menu);
        return menu;
    }

    @Test
    void theTabMenuMarksRevertAndNothingThatOnlyCloses(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("first.txt"), "one\n");
        FxWindowFixture fx = FxWindowFixture.create(
                Files.createDirectories(dir.resolve("config")),
                false,
                false,
                false,
                List.of(new MainController.OpenTarget(file, 0, 0)),
                c -> {});
        try {
            FxTestSupport.runOnFx(() -> {
                EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
                Tab tab = area.tabs().stream()
                        .filter(t -> t.getContextMenu() != null)
                        .findFirst()
                        .orElseThrow();
                ContextMenu menu = tab.getContextMenu();
                menu.getOnShowing().handle(null); // builds the lazy menu, as its first showing does
                Menu git = submenu(menu.getItems(), "project.menu.git");
                assertTrue(danger(row(git.getItems(), "project.menu.git.revert")), "Revert… discards changes");
                assertFalse(danger(row(git.getItems(), "project.menu.git.stage")));
                for (String closing : List.of("menu.close", "menu.closeOthers", "menu.closeAll")) {
                    assertFalse(danger(row(menu.getItems(), closing)), closing + " closes; it destroys nothing");
                }
                assertFalse(danger(row(menu.getItems(), "menu.rename")));
            });
        } finally {
            fx.dispose();
        }
    }

    @Test
    void theMenuBarMarksItsDestructiveCommandsWhereTheRowIsBuilt() throws Exception {
        MainMenuBar bar = FxTestSupport.callOnFx(() -> new MainMenuBar(
                new CommandRegistry(),
                Map::of,
                DestructiveMenuRowsFxTest::allFeaturesOn,
                Chrome.PaletteContext::all,
                id -> {}));
        Map<String, ?> rows = FxTestSupport.field(bar, "items");
        Set<String> destructive = MenuBarModel.destructiveCommandIds();
        assertTrue(destructive.containsAll(List.of("git.discardAll", "git.deleteBranch", "git.pushForce")));
        for (Map.Entry<String, ?> e : rows.entrySet()) {
            MenuItem item = (MenuItem) FxTestSupport.call(e.getValue(), "item", new Class<?>[0]);
            boolean expected = destructive.contains(e.getKey());
            assertTrue(danger(item) == expected, e.getKey() + (expected ? " is destructive" : " is not destructive"));
        }
        for (String id : List.of("git.commit", "buffer.close", "buffer.closeAll", "debug.stop", "edit.deleteLine")) {
            assertTrue(rows.containsKey(id), id + " is on the menu bar");
            assertFalse(MenuBarModel.isDestructive(id), id + " closes, stops or edits text: not destructive");
        }
    }

    private static Chrome.PaletteGates allFeaturesOn() {
        return new Chrome.PaletteGates(
                true, true, true, true, true, true, true, Set.of(), true, true, true, true, true, true, true, true,
                true, true, true, true, true, true, true, true, true, true, true, true, false);
    }
}
