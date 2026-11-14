package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.config.Settings;
import com.editora.dap.DapModels;
import com.editora.dap.TypeRenderer;
import com.editora.editor.MenuChords;
import com.editora.git.GitChanges;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lists that had no right-click menu have one (MENU-05, Pauta 6.0.1): the debugger's memory and disassembly
 * views, a Settings page's list, a Project Map card's lists; each row is a command's, does what the command does to
 * the selected row of the list the keyboard is in, and is disabled while it cannot act.
 */
@Tag("fx")
class ListRowMenusFxTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void reset() throws Exception {
        FxTestSupport.runOnFx(() -> MenuChords.setChords(Map::of));
    }

    /** The control's menu, with its rows enabled or disabled as a showing would leave them. */
    private static ContextMenu menuOf(Control control) {
        ContextMenu menu = control.getContextMenu();
        assertNotNull(menu, "the control has a right-click menu");
        menu.getOnShowing().handle(null);
        return menu;
    }

    private static MenuItem row(ContextMenu menu, String commandId) {
        return menu.getItems().stream()
                .filter(i -> commandId.equals(MenuChords.commandOf(i))
                        || (ListRowCommands.COPY.equals(commandId) && "edit.copy".equals(MenuChords.commandOf(i))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + commandId + " in "
                        + menu.getItems().stream().map(MenuItem::getText).toList()));
    }

    @Test
    void theMemoryViewCopiesItsSelectedRow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugMemoryView view = new DebugMemoryView();
            view.show(new DapModels.MemoryBlock("0x10", "Hello, world!...".getBytes(), 0));
            ListView<String> rows = view.rowList();
            rows.getSelectionModel().clearSelection();
            MenuItem copy = row(menuOf(rows), ListRowCommands.COPY);
            assertTrue(copy.isDisable(), "nothing selected: nothing to copy");
            assertEquals(DebugPanelText.bareTitle(tr("command.list.copy")), copy.getText());

            rows.getSelectionModel().select(0);
            copy = row(menuOf(rows), ListRowCommands.COPY);
            assertFalse(copy.isDisable());
            copy.fire();
            assertEquals(
                    rows.getItems().getFirst(), Clipboard.getSystemClipboard().getString());
        });
    }

    @Test
    void theDisassemblyViewOpensAnInstructionsSourceAndCopiesIt() throws Exception {
        List<String> opened = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            DebugDisassemblyView view = new DebugDisassemblyView();
            view.setOnOpenSource((file, line) -> opened.add(file.getFileName() + ":" + line));
            DapModels.Instruction withSource =
                    new DapModels.Instruction("0x10", "", "mov eax, 1", "main", Path.of("/p/main.c"), 11);
            DapModels.Instruction withoutSource = new DapModels.Instruction("0x14", "", "ret", "", null, -1);
            view.show(List.of(withSource, withoutSource), "0x14");
            ListView<DapModels.Instruction> list = view.list();

            ContextMenu menu = menuOf(list); // the current instruction, 0x14, is selected: it has no source
            assertTrue(row(menu, ListRowCommands.OPEN).isDisable(), "no source line to open");
            assertFalse(row(menu, ListRowCommands.COPY).isDisable());

            list.getSelectionModel().select(0);
            menu = menuOf(list);
            assertFalse(row(menu, ListRowCommands.OPEN).isDisable());
            row(menu, ListRowCommands.OPEN).fire();
            assertEquals(List.of("main.c:11"), opened, "what a double-click does");
            row(menu, ListRowCommands.COPY).fire();
            assertEquals(
                    "0x10  mov eax, 1  main", Clipboard.getSystemClipboard().getString());
        });
    }

    @Test
    void aSettingsListsMenuDoesWhatItsButtonsDo() throws Exception {
        List<List<TypeRenderer>> saved = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            TypeRenderer one = new TypeRenderer();
            one.setType("java.util.List");
            TypeRenderersEditor editor = new TypeRenderersEditor(new TypeRenderersEditor.Hooks(
                    () -> List.of(one), saved::add, (owner, initial, isNew) -> Optional.empty(), () -> null));
            ListView<TypeRenderer> list = editor.list();
            list.getSelectionModel().clearSelection();
            ContextMenu menu = menuOf(list);
            MenuItem remove = row(menu, ListRowCommands.REMOVE);
            assertTrue(remove.isDisable(), "the Remove button is greyed: so is the row");
            assertEquals(tr("typerenderer.remove"), remove.getText(), "the button's own words");
            assertTrue(remove.getStyleClass().contains("danger"), "a row that deletes reads as one (MENU-12)");
            assertEquals(
                    tr("typerenderer.edit"), row(menu, ListRowCommands.OPEN).getText());

            list.getSelectionModel().select(0);
            remove = row(menuOf(list), ListRowCommands.REMOVE);
            assertFalse(remove.isDisable());
            remove.fire();
            assertTrue(list.getItems().isEmpty(), "the row did what the Remove button does");
            assertEquals(1, saved.size(), "and stored it as the button does");
        });
    }

    @Test
    void aTickListsRowTurnsTheSelectedRowOnAndOff() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Settings settings = new Settings();
            SpellFileTypesEditor editor = new SpellFileTypesEditor(() -> settings, () -> {});
            @SuppressWarnings("unchecked")
            ListView<String> list = (ListView<String>) FxTestSupport.<ListView<?>>field(editor, "list");
            String language = list.getItems().getFirst();
            boolean was = editor.isTicked(language);
            list.getSelectionModel().select(language);
            row(menuOf(list), ListRowCommands.TOGGLE).fire();
            assertEquals(!was, editor.isTicked(language));
        });
    }

    /** The command does what the row does, to the list the keyboard is in, and says so when there is none. */
    @Test
    void theCommandsActOnTheListTheKeyboardIsIn() throws Exception {
        Stage[] stage = new Stage[1];
        List<String> said = new CopyOnWriteArrayList<>();
        DebugMemoryView view = FxTestSupport.callOnFx(() -> {
            DebugMemoryView made = new DebugMemoryView();
            made.show(new DapModels.MemoryBlock("0x20", "abcdefghijklmnop".getBytes(), 0));
            stage[0] = new Stage();
            stage[0].setScene(new Scene(new StackPane(made), 500, 300));
            stage[0].show();
            return made;
        });
        try {
            FxTestSupport.runOnFx(() -> {
                ListView<String> rows = view.rowList();
                rows.requestFocus();
                rows.getSelectionModel().clearSelection();
                assertNotNull(ListRowCommands.actionFor(ListRowCommands.COPY), "the list the keyboard is in");
                assertNull(ListRowCommands.actionFor(ListRowCommands.REMOVE), "it has no Remove");

                ListRowCommands.run(ListRowCommands.COPY, said::add);
                assertEquals(List.of(tr("status.list.selectRow")), said, "a list, but no row selected");

                rows.getSelectionModel().select(0);
                Clipboard.getSystemClipboard().clear();
                ListRowCommands.run(ListRowCommands.COPY, said::add);
                assertEquals(
                        rows.getItems().getFirst(),
                        Clipboard.getSystemClipboard().getString());

                ListRowCommands.run(ListRowCommands.REMOVE, said::add);
                assertEquals(tr("status.list.noTarget"), said.getLast(), "no list here takes Remove");
            });
        } finally {
            FxTestSupport.runOnFx(() -> stage[0].close());
        }
    }

    /** A Project Map card's lists: Open and Copy, and the bar's buttons under the card commands that run them. */
    @Test
    void aChangesCardsFileRowOpensComparesAndCopies() throws Exception {
        Path root = Files.createDirectories(temp.resolve("repo")).toRealPath();
        GitChanges.FileDiff text = GitChanges.untracked("t.txt", "a\nb\n", false);
        List<String> done = new CopyOnWriteArrayList<>();
        Stage[] stage = new Stage[1];
        try {
            FxTestSupport.runOnFx(() -> {
                ProjectMapChangesCard card = new ProjectMapChangesCard();
                stage[0] = new Stage(); // shown, so the lists sit inside the card (a SplitPane's items join it then)
                stage[0].setScene(new Scene(new StackPane(card), 700, 700));
                stage[0].show();
                card.setVisible(true);
                card.setOnOpenLine((file, line) -> done.add("card " + file.getFileName() + ":" + line));
                card.setOnOpenInEditor((file, line) -> done.add("editor " + file.getFileName() + ":" + line));
                card.setOnCompare(file -> done.add("compare " + file.getFileName()));
                card.show(root, null);
                card.setChanges(new GitChanges.Result(List.of(text), false, ""));
                @SuppressWarnings("unchecked")
                ListView<GitChanges.FileDiff> files =
                        (ListView<GitChanges.FileDiff>) FxTestSupport.<ListView<?>>field(card, "files");
                files.getSelectionModel().select(0);
                ContextMenu menu = menuOf(files);
                row(menu, ListRowCommands.OPEN).fire();
                row(menu, "projectMap.changesOpenInEditor").fire();
                row(menu, "projectMap.changesCompare").fire();
                assertEquals(List.of("card t.txt:0", "editor t.txt:0", "compare t.txt"), done);
                assertEquals(
                        tr("project.map.changes.openInEditor"),
                        row(menu, "projectMap.changesOpenInEditor").getText());
                row(menu, ListRowCommands.COPY).fire();
                assertEquals("t.txt", Clipboard.getSystemClipboard().getString());
                assertSame(
                        MapButtonCommands.actionAt(files, "projectMap.changesCompare"),
                        MapButtonCommands.actionAt(card, "projectMap.changesCompare"),
                        "the card command finds the action from the list up");
                assertNotNull(MapButtonCommands.actionAt(files, "projectMap.changesCompare"));
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                if (stage[0] != null) {
                    stage[0].close();
                }
            });
        }
    }
}
