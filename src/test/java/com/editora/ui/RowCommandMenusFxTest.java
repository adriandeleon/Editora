package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.editor.MenuChords;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The right-click menus this change gave the views that had none, and the commands behind rows that had none: the
 * Git remotes and work-trees manager's list, the hex viewer, and a console's Copy and Select All, which
 * {@code edit.copy} and {@code edit.selectAll} now do while the keyboard is in the console (MENU-05, KBD-01).
 */
@Tag("fx")
class RowCommandMenusFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aConsolesCopyAndSelectAllAreWhatEditCopyAndSelectAllRunWhileItHasTheKeyboard() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea console = new CodeArea("line one\nline two");
            ConsoleContextMenu.install(console);
            ConsoleContextMenu.Actions actions = ConsoleContextMenu.actionsAt(console);
            assertNotNull(actions, "the console is marked as a read-only view");
            assertNull(ConsoleContextMenu.actionsAt(new CodeArea()), "an unmarked area is the editor's business");

            actions.copy().run(); // nothing selected: all of it, as the Copy row
            assertEquals("line one\nline two", Clipboard.getSystemClipboard().getString());
            actions.selectAll().run();
            assertEquals(console.getLength(), console.getSelection().getLength());
        });
    }

    @Test
    void theGitManagersListHasAMenuOfItsButtonsEachTiedToItsCommand() throws Exception {
        List<String> ran = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            StackPane pane = new StackPane();
            Stage stage = new Stage();
            stage.setScene(new Scene(pane, 800, 600));
            stage.show();
            try {
                OverlayHost host = new OverlayHost();
                host.install(pane);
                GitManagerOverlay<String> manager = new GitManagerOverlay<>(
                        "Remotes",
                        "none",
                        s -> s,
                        s -> "",
                        List.of(
                                new GitManagerOverlay.Action<>(
                                        "Add", false, false, "git.addRemote", s -> ran.add("add")),
                                new GitManagerOverlay.Action<>(
                                        "Remove", true, true, "git.removeRemote", s -> ran.add("remove " + s))));
                manager.show(host, List.of("origin", "upstream"));
                ListView<String> list = FxTestSupport.field(manager, "list");
                ContextMenu menu = list.getContextMenu();
                assertNotNull(menu, "the list has a right-click menu");
                assertEquals(
                        List.of("git.addRemote", "git.removeRemote"),
                        menu.getItems().stream().map(MenuChords::commandOf).toList());
                assertEquals("origin", manager.selectedWhileShowing());
                list.getSelectionModel().select("upstream");
                menu.getItems().get(1).fire();
                assertEquals(List.of("remove upstream"), ran);
                assertFalse(manager.isShowing(), "a row hides the card before its action, as its button does");
                assertNull(manager.selectedWhileShowing(), "a command never acts on a hidden card's selection");
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void theHexViewerHasACopyAndSelectAllMenu(@TempDir Path dir) throws Exception {
        Path file = Files.write(dir.resolve("data.bin"), new byte[] {1, 2, 3, 65, 66});
        HexViewerPane pane = FxTestSupport.callOnFx(() -> new HexViewerPane(file));
        pane.loadedForTest().get(10, java.util.concurrent.TimeUnit.SECONDS);
        FxTestSupport.runOnFx(() -> {
            CodeArea area = FxTestSupport.field(pane, "area");
            assertNotNull(area.getOnContextMenuRequested(), "the dump answers a right-click");
            assertNotNull(ConsoleContextMenu.actionsAt(area), "edit.copy and edit.selectAll reach the dump");
            pane.dispose();
        });
    }

    @Test
    void menuRowsFindsARowByItsCommandInASubmenuAndFiresItOnlyWhenEnabled() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> fired = new java.util.ArrayList<>();
            javafx.scene.control.MenuItem plain = MenuChords.tag(new javafx.scene.control.MenuItem("a"), "x.plain");
            plain.setOnAction(e -> fired.add("plain"));
            javafx.scene.control.MenuItem inner = MenuChords.tag(new javafx.scene.control.MenuItem("b"), "x.inner");
            inner.setOnAction(e -> fired.add("inner"));
            javafx.scene.control.MenuItem off = MenuChords.tag(new javafx.scene.control.MenuItem("c"), "x.off");
            off.setOnAction(e -> fired.add("off"));
            off.setDisable(true);
            javafx.scene.control.Menu sub = new javafx.scene.control.Menu("sub", null, inner, off);
            List<javafx.scene.control.MenuItem> rows =
                    List.of(plain, new javafx.scene.control.SeparatorMenuItem(), sub);

            assertEquals(inner, MenuRows.find(rows, "x.inner"), "found inside the submenu");
            assertNull(MenuRows.find(rows, "x.missing"));
            assertTrue(MenuRows.fire(rows, "x.plain"));
            assertTrue(MenuRows.fire(rows, "x.inner"));
            assertFalse(MenuRows.fire(rows, "x.off"), "a grayed-out row is not run");
            assertFalse(MenuRows.fire(rows, "x.missing"), "nor one the menu does not have");
            assertEquals(List.of("plain", "inner"), fired);
        });
    }

    @Test
    void theManagersMenuGraysOutTheRowsThatNeedARowWhileNoneIsSelected() throws Exception {
        FxTestSupport.runOnFx(() -> {
            StackPane pane = new StackPane();
            Stage stage = new Stage();
            stage.setScene(new Scene(pane, 800, 600));
            stage.show();
            try {
                OverlayHost host = new OverlayHost();
                host.install(pane);
                GitManagerOverlay<String> manager = new GitManagerOverlay<>(
                        "Worktrees",
                        "none",
                        s -> s,
                        s -> "",
                        List.of(
                                new GitManagerOverlay.Action<>("Add", false, false, "git.addWorktree", s -> {}),
                                new GitManagerOverlay.Action<>("Remove", true, true, "git.removeWorktree", s -> {})));
                manager.show(host, List.of("main"));
                ListView<String> list = FxTestSupport.field(manager, "list");
                ContextMenu menu = list.getContextMenu();
                menu.getOnShowing().handle(null);
                assertFalse(menu.getItems().get(1).isDisable(), "a row selected: Remove is offered");
                assertTrue(menu.getItems().get(1).getStyleClass().contains("danger"));
                list.getSelectionModel().clearSelection();
                menu.getOnShowing().handle(null);
                assertFalse(menu.getItems().get(0).isDisable(), "Add needs no row");
                assertTrue(menu.getItems().get(1).isDisable(), "Remove does");
                assertTrue(manager.isShowing());
                manager.hide();
                assertFalse(manager.isShowing(), "taken down, as a button does before its action");
                manager.hide(); // already down: nothing to do
                assertFalse(host.isShowing());
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void aConsolesRowsAreGrayedWhileItIsEmptyAndAMarkedViewIsOnlyTheOneWithTheKeyboard() throws Exception {
        Stage[] window = new Stage[1];
        FxTestSupport.runOnFx(() -> {
            CodeArea empty = new CodeArea();
            ConsoleContextMenu.install(empty);
            javafx.scene.control.TextArea log = new javafx.scene.control.TextArea("one\ntwo");
            ContextMenu logMenu = ConsoleContextMenu.install(log, () -> {}, "debugLog.find");
            javafx.scene.control.TextField elsewhere = new javafx.scene.control.TextField();
            Stage stage = new Stage();
            window[0] = stage;
            stage.setScene(new Scene(new javafx.scene.layout.VBox(empty, log, elsewhere), 400, 300));
            stage.show();
            {
                javafx.event.Event.fireEvent(
                        empty,
                        new javafx.scene.input.ContextMenuEvent(
                                javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 5, 5, false, null));
                ContextMenu shown = OverlayTestKit.openContextMenus().get(0);
                assertTrue(shown.getItems().stream().allMatch(javafx.scene.control.MenuItem::isDisable), "empty");
                shown.hide();

                logMenu.getOnShowing().handle(null);
                assertTrue(logMenu.getItems().stream()
                        .filter(i -> !(i instanceof javafx.scene.control.SeparatorMenuItem))
                        .noneMatch(javafx.scene.control.MenuItem::isDisable));
                log.selectRange(4, 7);
                logMenu.getItems().get(1).fire();
                assertEquals("two", Clipboard.getSystemClipboard().getString(), "Copy takes the selection");
                log.deselect();
                logMenu.getItems().get(1).fire();
                assertEquals("one\ntwo", Clipboard.getSystemClipboard().getString(), "and all of it with none");
                logMenu.getItems().get(0).fire();
                assertEquals(log.getLength(), log.getSelection().getLength(), "Select All");
                log.clear();
                logMenu.getOnShowing().handle(null);
                assertTrue(
                        logMenu.getItems().stream()
                                .filter(i -> !(i instanceof javafx.scene.control.SeparatorMenuItem))
                                .allMatch(javafx.scene.control.MenuItem::isDisable),
                        "an empty log offers nothing to copy, select or find");

                stage.requestFocus();
                elsewhere.requestFocus();
            }
        });
        try {
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertTrue(window[0].isFocused());
                assertFalse(
                        ConsoleContextMenu.runInFocusedView(true),
                        "the keyboard is in a plain field: Copy is the editor's business");
            });
        } finally {
            FxTestSupport.runOnFx(() -> window[0].close());
        }
    }
}
