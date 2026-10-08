package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Commit window's list as the keyboard and the context menu use it: what each row's menu offers and hands
 * to the controller, the Emacs chords inside the tree, the recent-messages dropdown and the subject guide.
 */
@Tag("fx")
class GitPanelMenusAndKeysFxTest {

    /** Records every call as one line, in order. */
    private static final class Recorder implements GitPanel.Actions {
        final List<String> calls = new ArrayList<>();
        List<String> history = List.of();

        @Override
        public void open(String repoRelativePath) {
            calls.add("open " + repoRelativePath);
        }

        @Override
        public void stage(List<String> paths) {
            calls.add("stage " + paths);
        }

        @Override
        public void unstage(List<String> paths) {
            calls.add("unstage " + paths);
        }

        @Override
        public void discard(List<String> tracked, List<String> untracked) {
            calls.add("discard tracked=" + tracked + " untracked=" + untracked);
        }

        @Override
        public void stageAll() {
            calls.add("stageAll");
        }

        @Override
        public void unstageAll() {
            calls.add("unstageAll");
        }

        @Override
        public void commit(String message, Consumer<Boolean> onDone) {
            calls.add("commit " + message);
        }

        @Override
        public void push() {
            calls.add("push");
        }

        @Override
        public void refresh() {
            calls.add("refresh");
        }

        @Override
        public void review(boolean staged) {
            calls.add("review " + staged);
        }

        @Override
        public void diff(String repoRelativePath, boolean staged) {
            calls.add("diff " + repoRelativePath + " staged=" + staged);
        }

        @Override
        public void resolve(String repoRelativePath) {
            calls.add("resolve " + repoRelativePath);
        }

        @Override
        public List<String> messageHistory() {
            return history;
        }

        List<String> take() {
            List<String> seen = List.copyOf(calls);
            calls.clear();
            return seen;
        }
    }

    private Stage stage;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void closeStage() throws Exception {
        if (stage != null) {
            FxTestSupport.runOnFx(stage::close);
            stage = null;
        }
    }

    private static GitStatus status() {
        return new GitStatus(
                true,
                "main",
                "origin/main",
                0,
                0,
                List.of(
                        new FileEntry("staged.txt", 'M', '.', null),
                        new FileEntry("changed.txt", '.', 'M', null),
                        new FileEntry("src/other.txt", '.', 'M', null),
                        new FileEntry("new.txt", '?', '?', null),
                        new FileEntry("scratch.txt", '?', '?', null)));
    }

    /** A panel showing {@link #status()}, in a window tall enough to draw every row. */
    private GitPanel shown(Recorder recorder) throws Exception {
        GitPanel panel = FxTestSupport.callOnFx(() -> new GitPanel(recorder));
        stage = FxTestSupport.callOnFx(() -> {
            panel.setStatus(status());
            Stage window = new Stage();
            window.setScene(new Scene(new StackPane(panel), 520, 900));
            window.show();
            window.getScene().getRoot().applyCss();
            window.getScene().getRoot().layout();
            return window;
        });
        return panel;
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> tree(GitPanel panel) {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
    }

    /** The row of the file {@code path} ({@code group} tells a staged row from an unstaged one). FX thread. */
    private static TreeItem<Object> fileRow(GitPanel panel, String group, String path) {
        for (TreeItem<Object> groupItem : tree(panel).getRoot().getChildren()) {
            if (!group.equals(String.valueOf(FxTestSupport.call(groupItem.getValue(), "group", new Class<?>[] {})))) {
                continue;
            }
            for (TreeItem<Object> item : groupItem.getChildren()) {
                FileEntry entry = (FileEntry) FxTestSupport.call(item.getValue(), "entry", new Class<?>[] {});
                if (entry.path().equals(path)) {
                    return item;
                }
            }
        }
        throw new AssertionError("no row for " + path + " in " + group);
    }

    private static TreeItem<Object> groupRow(GitPanel panel, String group) {
        for (TreeItem<Object> groupItem : tree(panel).getRoot().getChildren()) {
            if (group.equals(String.valueOf(FxTestSupport.call(groupItem.getValue(), "group", new Class<?>[] {})))) {
                return groupItem;
            }
        }
        throw new AssertionError("no group " + group);
    }

    /** Opens the menu the keyboard's menu key opens — for the focused row — and returns it ({@code null}: none). */
    private static ContextMenu menuKey(GitPanel panel, TreeItem<Object> row) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            TreeView<Object> tree = tree(panel);
            ContextMenu before = FxTestSupport.field(panel, "openMenu");
            if (before != null) {
                before.hide();
            }
            tree.getFocusModel().focus(tree.getRow(row));
            javafx.event.Event.fireEvent(
                    tree, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, 40, 40, true, null));
            ContextMenu menu = FxTestSupport.field(panel, "openMenu");
            return menu == before ? null : menu;
        });
    }

    private static void fire(ContextMenu menu, String label) throws Exception {
        FxTestSupport.runOnFx(() -> OverlayTestKit.item(menu.getItems(), label).fire());
    }

    private static void key(GitPanel panel, KeyCode code, boolean control) throws Exception {
        FxTestSupport.runOnFx(() -> javafx.event.Event.fireEvent(
                tree(panel), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false)));
    }

    private static String selectedPath(GitPanel panel) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            TreeItem<Object> item = tree(panel).getSelectionModel().getSelectedItem();
            if (item == null) {
                return null;
            }
            Object value = item.getValue();
            return value.getClass().getSimpleName().equals("FileRow")
                    ? ((FileEntry) FxTestSupport.call(value, "entry", new Class<?>[] {})).path()
                    : "group:" + FxTestSupport.call(value, "group", new Class<?>[] {});
        });
    }

    // --- context menus -----------------------------------------------------------------------------

    @Test
    void aFileRowsMenuOffersWhatAppliesToItsSideOfTheIndex() throws Exception {
        Recorder recorder = new Recorder();
        GitPanel panel = shown(recorder);

        ContextMenu changed = menuKey(panel, FxTestSupport.callOnFx(() -> fileRow(panel, "MODIFIED", "changed.txt")));
        assertEquals(
                List.of(
                        tr("gitpanel.menu.open"),
                        tr("gitpanel.menu.showDiff"),
                        tr("gitpanel.menu.stage"),
                        tr("gitpanel.menu.discard")),
                OverlayTestKit.labels(changed.getItems()));
        assertEquals("changed.txt", selectedPath(panel), "the row the menu is for becomes the selection");
        fire(changed, tr("gitpanel.menu.open"));
        fire(changed, tr("gitpanel.menu.showDiff"));
        fire(changed, tr("gitpanel.menu.stage"));
        fire(changed, tr("gitpanel.menu.discard"));
        assertEquals(
                List.of(
                        "open changed.txt",
                        "diff changed.txt staged=false",
                        "stage [changed.txt]",
                        "discard tracked=[changed.txt] untracked=[]"),
                recorder.take());

        ContextMenu staged = menuKey(panel, FxTestSupport.callOnFx(() -> fileRow(panel, "STAGED", "staged.txt")));
        assertEquals(
                List.of(tr("gitpanel.menu.open"), tr("gitpanel.menu.showDiff"), tr("gitpanel.menu.unstage")),
                OverlayTestKit.labels(staged.getItems()),
                "a staged row is unstaged, not discarded");
        fire(staged, tr("gitpanel.menu.showDiff"));
        fire(staged, tr("gitpanel.menu.unstage"));
        assertEquals(List.of("diff staged.txt staged=true", "unstage [staged.txt]"), recorder.take());

        ContextMenu untracked = menuKey(panel, FxTestSupport.callOnFx(() -> fileRow(panel, "UNTRACKED", "new.txt")));
        assertEquals(
                List.of(tr("gitpanel.menu.open"), tr("gitpanel.menu.stage"), tr("gitpanel.menu.deleteUntracked")),
                OverlayTestKit.labels(untracked.getItems()),
                "nothing to diff an untracked file against, and discarding it deletes it");
        fire(untracked, tr("gitpanel.menu.deleteUntracked"));
        assertEquals(List.of("discard tracked=[] untracked=[new.txt]"), recorder.take());
    }

    @Test
    void aGroupHeadersMenuActsOnTheWholeGroup() throws Exception {
        Recorder recorder = new Recorder();
        GitPanel panel = shown(recorder);

        ContextMenu staged = menuKey(panel, FxTestSupport.callOnFx(() -> groupRow(panel, "STAGED")));
        assertEquals(List.of(tr("gitpanel.menu.unstageAll")), OverlayTestKit.labels(staged.getItems()));
        fire(staged, tr("gitpanel.menu.unstageAll"));
        assertEquals(List.of("unstageAll"), recorder.take());

        ContextMenu changes = menuKey(panel, FxTestSupport.callOnFx(() -> groupRow(panel, "MODIFIED")));
        assertEquals(
                List.of(tr("gitpanel.menu.stageGroup"), tr("gitpanel.menu.discardGroup")),
                OverlayTestKit.labels(changes.getItems()));
        fire(changes, tr("gitpanel.menu.stageGroup"));
        fire(changes, tr("gitpanel.menu.discardGroup"));
        assertEquals(
                List.of(
                        "stage [changed.txt, src/other.txt]",
                        "discard tracked=[changed.txt, src/other.txt] untracked=[]"),
                recorder.take());

        ContextMenu untracked = menuKey(panel, FxTestSupport.callOnFx(() -> groupRow(panel, "UNTRACKED")));
        assertEquals(
                List.of(tr("gitpanel.menu.stageGroup"), tr("gitpanel.menu.deleteGroup")),
                OverlayTestKit.labels(untracked.getItems()));
        fire(untracked, tr("gitpanel.menu.deleteGroup"));
        assertEquals(List.of("discard tracked=[] untracked=[new.txt, scratch.txt]"), recorder.take());

        // A filter narrows what is listed, not what "the whole group" means.
        TextField filter = FxTestSupport.field(panel, "filterField");
        FxTestSupport.runOnFx(() -> filter.setText("other"));
        ContextMenu narrowed = menuKey(panel, FxTestSupport.callOnFx(() -> groupRow(panel, "MODIFIED")));
        fire(narrowed, tr("gitpanel.menu.stageGroup"));
        assertEquals(List.of("stage [changed.txt, src/other.txt]"), recorder.take());
    }

    @Test
    void theConflictsHeaderHasNoMenuOfItsOwn() throws Exception {
        Recorder recorder = new Recorder();
        GitPanel panel = shown(recorder);
        FxTestSupport.runOnFx(() -> panel.setStatus(
                new GitStatus(true, "main", "", 0, 0, List.of(new FileEntry("both.txt", 'U', 'U', null)))));

        assertNull(menuKey(panel, FxTestSupport.callOnFx(() -> groupRow(panel, "CONFLICTS"))));
        assertEquals(List.of(), recorder.take());
    }

    /** A right click inside a multi-selection acts on all of it; outside it, on the clicked row alone. */
    @Test
    void aRightClickInsideASelectionActsOnTheWholeSelection() throws Exception {
        Recorder recorder = new Recorder();
        GitPanel panel = shown(recorder);
        TreeView<Object> tree = tree(panel);
        FxTestSupport.runOnFx(() -> {
            tree.getSelectionModel().clearSelection();
            tree.getSelectionModel().select(fileRow(panel, "MODIFIED", "changed.txt"));
            tree.getSelectionModel().select(fileRow(panel, "MODIFIED", "src/other.txt"));
            tree.getSelectionModel().select(fileRow(panel, "UNTRACKED", "new.txt"));
            tree.getSelectionModel().select(fileRow(panel, "STAGED", "staged.txt"));
            tree.getSelectionModel().select(groupRow(panel, "UNTRACKED")); // a header in the range is harmless
            tree.getScene().getRoot().layout();
        });

        ContextMenu menu = rightClick(panel, "changed.txt");
        assertEquals(
                List.of(
                        tr("gitpanel.menu.stageMany", 3),
                        tr("gitpanel.menu.unstage"),
                        tr("gitpanel.menu.discardMany", 3)),
                OverlayTestKit.labels(menu.getItems()),
                "no Open or Show Diff for several rows; Stage and Unstage each for the rows they apply to");
        fire(menu, tr("gitpanel.menu.stageMany", 3));
        fire(menu, tr("gitpanel.menu.unstage"));
        fire(menu, tr("gitpanel.menu.discardMany", 3));
        assertEquals(
                List.of(
                        "stage [changed.txt, src/other.txt, new.txt]",
                        "unstage [staged.txt]",
                        "discard tracked=[changed.txt, src/other.txt] untracked=[new.txt]"),
                recorder.take());

        // Only untracked rows selected: the destructive entry says "delete", and counts them.
        FxTestSupport.runOnFx(() -> {
            menu.hide();
            tree.getSelectionModel().clearSelection();
            tree.getSelectionModel().select(fileRow(panel, "UNTRACKED", "new.txt"));
            tree.getSelectionModel().select(fileRow(panel, "UNTRACKED", "scratch.txt"));
            tree.getScene().getRoot().layout();
        });
        ContextMenu untracked = rightClick(panel, "scratch.txt");
        assertEquals(
                List.of(tr("gitpanel.menu.stageMany", 2), tr("gitpanel.menu.deleteUntrackedMany", 2)),
                OverlayTestKit.labels(untracked.getItems()));

        // A right click on a row outside the selection re-selects that row alone.
        FxTestSupport.runOnFx(untracked::hide);
        ContextMenu single = rightClick(panel, "staged.txt");
        assertEquals("staged.txt", selectedPath(panel));
        assertEquals(
                1,
                FxTestSupport.callOnFx(
                        () -> tree.getSelectionModel().getSelectedItems().size()));
        assertTrue(OverlayTestKit.labels(single.getItems()).contains(tr("gitpanel.menu.unstage")));
        FxTestSupport.runOnFx(single::hide);
    }

    /** Fires the mouse's context-menu request on the drawn cell of {@code path}. */
    private static ContextMenu rightClick(GitPanel panel, String path) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            for (Node node : tree(panel).lookupAll(".tree-cell")) {
                if (node instanceof TreeCell<?> cell
                        && cell.getItem() != null
                        && cell.getItem().getClass().getSimpleName().equals("FileRow")
                        && ((FileEntry) FxTestSupport.call(cell.getItem(), "entry", new Class<?>[] {}))
                                .path()
                                .equals(path)) {
                    javafx.geometry.Point2D screen = cell.localToScreen(4, 4);
                    javafx.geometry.Point2D scene = cell.localToScene(4, 4);
                    javafx.event.Event.fireEvent(
                            cell,
                            new ContextMenuEvent(
                                    ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                                    scene.getX(),
                                    scene.getY(),
                                    screen.getX(),
                                    screen.getY(),
                                    false,
                                    null));
                    return FxTestSupport.<ContextMenu>field(panel, "openMenu");
                }
            }
            throw new AssertionError("the row of " + path + " is not drawn");
        });
    }

    // --- keys --------------------------------------------------------------------------------------

    @Test
    void enterAndDoubleClickOpenTheSelectedFileAndAConflictOpensItsResolver() throws Exception {
        Recorder recorder = new Recorder();
        GitPanel panel = shown(recorder);
        TreeView<Object> tree = tree(panel);
        FxTestSupport.runOnFx(() ->
                tree.getSelectionModel().clearAndSelect(tree.getRow(fileRow(panel, "MODIFIED", "src/other.txt"))));

        key(panel, KeyCode.ENTER, false);
        key(panel, KeyCode.M, true); // C-m is Enter
        FxTestSupport.runOnFx(() -> javafx.event.Event.fireEvent(
                tree,
                new MouseEvent(
                        MouseEvent.MOUSE_CLICKED,
                        5,
                        5,
                        5,
                        5,
                        MouseButton.PRIMARY,
                        2,
                        false,
                        false,
                        false,
                        false,
                        true,
                        false,
                        false,
                        true,
                        false,
                        true,
                        null)));
        FxTestSupport.runOnFx(() -> javafx.event.Event.fireEvent(
                tree,
                new MouseEvent(
                        MouseEvent.MOUSE_CLICKED,
                        5,
                        5,
                        5,
                        5,
                        MouseButton.PRIMARY,
                        1,
                        false,
                        false,
                        false,
                        false,
                        true,
                        false,
                        false,
                        true,
                        false,
                        true,
                        null)));
        assertEquals(
                List.of("open src/other.txt", "open src/other.txt", "open src/other.txt"),
                recorder.take(),
                "a single click only selects");

        // On a group header there is no file to open.
        FxTestSupport.runOnFx(() -> tree.getSelectionModel().clearAndSelect(tree.getRow(groupRow(panel, "MODIFIED"))));
        key(panel, KeyCode.ENTER, false);
        assertEquals(List.of(), recorder.take());

        FxTestSupport.runOnFx(() -> {
            panel.setStatus(new GitStatus(true, "main", "", 0, 0, List.of(new FileEntry("both.txt", 'U', 'U', null))));
            tree.getSelectionModel().clearAndSelect(tree.getRow(fileRow(panel, "CONFLICTS", "both.txt")));
        });
        key(panel, KeyCode.ENTER, false);
        assertEquals(List.of("resolve both.txt"), recorder.take());
    }

    @Test
    void theEmacsChordsMoveExpandAndCollapseInsideTheTree() throws Exception {
        Recorder recorder = new Recorder();
        GitPanel panel = shown(recorder);
        TreeView<Object> tree = tree(panel);
        TreeItem<Object> changes = FxTestSupport.callOnFx(() -> groupRow(panel, "MODIFIED"));

        // With nothing selected, "back" starts from the last row.
        FxTestSupport.runOnFx(() -> tree.getSelectionModel().clearSelection());
        key(panel, KeyCode.B, true);
        assertEquals("scratch.txt", selectedPath(panel));

        // On a file, "back" steps out to its group; there it collapses the group; then it moves up a row.
        FxTestSupport.runOnFx(() ->
                tree.getSelectionModel().clearAndSelect(tree.getRow(fileRow(panel, "MODIFIED", "src/other.txt"))));
        key(panel, KeyCode.B, true);
        assertEquals("group:MODIFIED", selectedPath(panel));
        key(panel, KeyCode.B, true);
        assertFalse(FxTestSupport.callOnFx(changes::isExpanded), "the group folds");
        assertEquals("group:MODIFIED", selectedPath(panel));
        key(panel, KeyCode.B, true);
        assertEquals("staged.txt", selectedPath(panel), "a folded group has nothing to step out of: up one row");

        // "Forward" on the folded group opens it, then walks into it.
        FxTestSupport.runOnFx(() -> tree.getSelectionModel().clearAndSelect(tree.getRow(changes)));
        key(panel, KeyCode.F, true);
        assertTrue(FxTestSupport.callOnFx(changes::isExpanded));
        assertEquals("group:MODIFIED", selectedPath(panel));
        key(panel, KeyCode.F, true);
        assertEquals("changed.txt", selectedPath(panel));

        // A chord the tree has no meaning for is left alone, and so is a plain letter.
        key(panel, KeyCode.Z, true);
        key(panel, KeyCode.N, false);
        assertEquals("changed.txt", selectedPath(panel));
        assertEquals(List.of(), recorder.take(), "moving around runs no Git action");
    }

    @Test
    void spaceOnAGroupHeaderStagesOrUnstagesTheWholeGroup() throws Exception {
        Recorder recorder = new Recorder();
        GitPanel panel = shown(recorder);
        TreeView<Object> tree = tree(panel);

        FxTestSupport.runOnFx(() -> tree.getSelectionModel().clearAndSelect(tree.getRow(groupRow(panel, "UNTRACKED"))));
        key(panel, KeyCode.SPACE, false);
        assertEquals(List.of("stage [new.txt, scratch.txt]"), recorder.take());

        FxTestSupport.runOnFx(() -> tree.getSelectionModel().clearAndSelect(tree.getRow(groupRow(panel, "STAGED"))));
        key(panel, KeyCode.SPACE, false);
        assertEquals(List.of("unstageAll"), recorder.take(), "the staged header unstages everything in one step");

        // Staged and unstaged rows together: each goes the other way.
        FxTestSupport.runOnFx(() -> {
            tree.getSelectionModel().clearSelection();
            tree.getSelectionModel().select(fileRow(panel, "STAGED", "staged.txt"));
            tree.getSelectionModel().select(fileRow(panel, "MODIFIED", "changed.txt"));
        });
        key(panel, KeyCode.SPACE, false);
        assertEquals(List.of("stage [changed.txt]", "unstage [staged.txt]"), recorder.take());

        // Nothing selected: Space does nothing, and with Ctrl it is not this key at all.
        FxTestSupport.runOnFx(() -> tree.getSelectionModel().clearSelection());
        key(panel, KeyCode.SPACE, false);
        FxTestSupport.runOnFx(() -> tree.getSelectionModel().clearAndSelect(tree.getRow(groupRow(panel, "STAGED"))));
        key(panel, KeyCode.SPACE, true);
        assertEquals(List.of(), recorder.take());
        assertFalse(FxTestSupport.callOnFx(() -> {
            tree.getSelectionModel().clearSelection();
            return panel.toggleStagedOfSelection();
        }));
    }

    // --- the message box ---------------------------------------------------------------------------

    @Test
    void theRecentMessagesDropdownListsSubjectsAndPutsThePickedMessageBack() throws Exception {
        Recorder recorder = new Recorder();
        GitPanel panel = shown(recorder);
        MenuButton history = FxTestSupport.field(panel, "historyButton");
        TextArea message = FxTestSupport.field(panel, "message");

        FxTestSupport.runOnFx(history::show);
        List<MenuItem> none = FxTestSupport.callOnFx(() -> List.copyOf(history.getItems()));
        assertEquals(List.of(tr("gitpanel.historyEmpty")), OverlayTestKit.labels(none));
        assertTrue(none.get(0).isDisable(), "the placeholder cannot be picked");
        FxTestSupport.runOnFx(history::hide);

        String longSubject = "x".repeat(70);
        recorder.history = List.of("fix_a typo\n\nIn the README.", longSubject + "\n\nbody");
        FxTestSupport.runOnFx(history::show);
        List<MenuItem> items = FxTestSupport.callOnFx(() -> List.copyOf(history.getItems()));
        assertEquals(
                List.of("fix_a typo", "x".repeat(59) + "…"),
                OverlayTestKit.labels(items),
                "the subject line only, cut to fit");
        assertFalse(items.get(0).isMnemonicParsing(), "an underscore in a subject is text, not a mnemonic");

        FxTestSupport.runOnFx(() -> {
            items.get(0).fire();
            history.hide();
        });
        assertEquals("fix_a typo\n\nIn the README.", FxTestSupport.callOnFx(message::getText), "the whole message");
    }

    @Test
    void theSubjectGuideCountsTheSubjectAndFlagsLongLines() throws Exception {
        GitPanel panel = shown(new Recorder());
        TextArea message = FxTestSupport.field(panel, "message");
        Label guide = FxTestSupport.field(panel, "guideLabel");

        FxTestSupport.runOnFx(() -> message.setText("Short subject"));
        assertEquals(tr("gitpanel.guide.subject", 13), FxTestSupport.callOnFx(guide::getText));
        assertFalse(FxTestSupport.callOnFx(() -> guide.getStyleClass().contains("git-guide-long")));

        FxTestSupport.runOnFx(() -> message.setText("s".repeat(60)));
        assertTrue(FxTestSupport.callOnFx(() -> guide.getStyleClass().contains("git-guide-long")), "past 50");
        FxTestSupport.runOnFx(() -> message.setText("s".repeat(80)));
        assertTrue(FxTestSupport.callOnFx(() -> guide.getStyleClass().contains("git-guide-too-long")), "past 72");
        assertFalse(FxTestSupport.callOnFx(() -> guide.getStyleClass().contains("git-guide-long")));

        FxTestSupport.runOnFx(() -> message.setText("Short subject\n\n" + "b".repeat(100) + "\nfine\n"));
        assertEquals(tr("gitpanel.guide.subjectAndBody", 13, 1), FxTestSupport.callOnFx(guide::getText));
        assertTrue(
                FxTestSupport.callOnFx(() -> guide.getStyleClass().contains("git-guide-long")),
                "a fine subject over a long body line is still flagged");

        FxTestSupport.runOnFx(message::clear);
        assertEquals("", FxTestSupport.callOnFx(guide::getText));
    }

    @Test
    void theFilterHasAClearButtonOnlyWhileSomethingIsTyped() throws Exception {
        GitPanel panel = shown(new Recorder());
        TextField filter = FxTestSupport.field(panel, "filterField");
        Button clear = FxTestSupport.callOnFx(() -> OverlayTestKit.descendants(panel, Button.class).stream()
                .filter(button -> button.getStyleClass().contains("project-filter-clear"))
                .findFirst()
                .orElseThrow());
        assertFalse(FxTestSupport.callOnFx(clear::isVisible));

        FxTestSupport.runOnFx(() -> filter.setText("other"));
        assertTrue(FxTestSupport.callOnFx(clear::isVisible));
        assertEquals(
                1,
                FxTestSupport.callOnFx(
                        () -> groupRow(panel, "MODIFIED").getChildren().size()));

        FxTestSupport.runOnFx(clear::fire);
        assertEquals("", FxTestSupport.callOnFx(filter::getText));
        assertEquals(
                2,
                FxTestSupport.callOnFx(
                        () -> groupRow(panel, "MODIFIED").getChildren().size()));
        assertFalse(FxTestSupport.callOnFx(clear::isVisible));
    }
}
