package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.ui.ProjectPanel.RowKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project tree's row actions from the keyboard: F2 renames and Delete deletes the selected row — in the
 * tree only. In the filter field those keys keep their text-editing meaning.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectPanelRowKeysFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void rowKeyDecision() {
        assertEquals(RowKey.RENAME, ProjectPanel.rowKey(KeyCode.F2, true, true));
        assertEquals(RowKey.DELETE, ProjectPanel.rowKey(KeyCode.DELETE, true, true));
        assertEquals(RowKey.NONE, ProjectPanel.rowKey(KeyCode.F2, false, true), "not from the filter field");
        assertEquals(RowKey.NONE, ProjectPanel.rowKey(KeyCode.DELETE, false, true), "Delete edits the filter text");
        assertEquals(RowKey.NONE, ProjectPanel.rowKey(KeyCode.F2, true, false), "nothing selected");
        assertEquals(RowKey.NONE, ProjectPanel.rowKey(KeyCode.ENTER, true, true));
        assertEquals(RowKey.NONE, ProjectPanel.rowKey(KeyCode.BACK_SPACE, true, true));
    }

    /** A panel whose tree holds {@code dir} with one file selected, and a prompt that records what it was asked. */
    private record Harness(ProjectPanel panel, TreeView<Path> tree, TextField filter, List<String> prompts) {}

    private static Harness harness() throws Exception {
        List<String> prompts = new ArrayList<>();
        return FxTestSupport.callOnFx(() -> {
            ProjectPanel panel = new ProjectPanel(p -> {}, (a, b) -> {}, p -> {}, p -> false);
            panel.setPrompt((title, label, initial, onAccept) -> prompts.add(initial));
            TreeView<Path> tree = FxTestSupport.field(panel, "tree");
            Path dir = Path.of("project").toAbsolutePath();
            TreeItem<Path> root = new TreeItem<>(dir);
            TreeItem<Path> file = new TreeItem<>(dir.resolve("notes.txt"));
            root.getChildren().add(file);
            root.setExpanded(true);
            tree.setRoot(root);
            tree.getSelectionModel().select(file);
            return new Harness(panel, tree, FxTestSupport.field(panel, "filterField"), prompts);
        });
    }

    /** Delivers a key press to the panel's key handler as if {@code target} had the focus. */
    private static KeyEvent deliver(ProjectPanel panel, javafx.scene.Node target, KeyCode code) {
        KeyEvent e =
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false).copyFor(target, target);
        FxTestSupport.invokeWith(panel, "onKey", KeyEvent.class, e);
        return e;
    }

    @Test
    void f2InTheTreeOpensTheRenamePromptForTheSelectedRow() throws Exception {
        Harness h = harness();
        FxTestSupport.runOnFx(() -> {
            KeyEvent e = deliver(h.panel(), h.tree(), KeyCode.F2);
            assertEquals(List.of("notes.txt"), h.prompts(), "the rename prompt opened, pre-filled with the name");
            assertTrue(e.isConsumed());
        });
    }

    @Test
    void theTreeClaimsItsRowKeysFromTheGlobalDispatcher() throws Exception {
        // F2 is lsp.rename in the VS Code/Sublime/IntelliJ keymaps; without the claim the dispatcher would
        // run that and the tree would never see the key. The claim is on the tree, not the whole panel, so
        // the filter field is unaffected.
        Harness h = harness();
        FxTestSupport.runOnFx(() -> {
            assertEquals(
                    java.util.Set.of("f2", "delete"),
                    h.tree().getProperties().get(com.editora.command.KeyDispatcher.CLAIMED_KEYS));
            assertTrue(!h.panel().getProperties().containsKey(com.editora.command.KeyDispatcher.CLAIMED_KEYS));
            assertTrue(!h.filter().getProperties().containsKey(com.editora.command.KeyDispatcher.CLAIMED_KEYS));
        });
    }

    @Test
    void f2AndDeleteInTheFilterFieldAreNotRowActions() throws Exception {
        Harness h = harness();
        FxTestSupport.runOnFx(() -> {
            KeyEvent f2 = deliver(h.panel(), h.filter(), KeyCode.F2);
            KeyEvent del = deliver(h.panel(), h.filter(), KeyCode.DELETE);
            assertTrue(h.prompts().isEmpty(), "no rename from the filter field");
            assertTrue(!f2.isConsumed() && !del.isConsumed(), "the keys are left to the field");
        });
    }

    @Test
    void deleteOnAFolderRowIsANoOp() throws Exception {
        // Delete goes through the menu's confirmed files-only flow; a folder (here: a path that is not a
        // regular file) is skipped without a dialog, exactly as the menu item does.
        Harness h = harness();
        FxTestSupport.runOnFx(() -> {
            KeyEvent e = deliver(h.panel(), h.tree(), KeyCode.DELETE);
            assertTrue(e.isConsumed(), "Delete in the tree is the tree's key");
            assertTrue(h.prompts().isEmpty());
        });
    }
}
