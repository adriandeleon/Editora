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
}
