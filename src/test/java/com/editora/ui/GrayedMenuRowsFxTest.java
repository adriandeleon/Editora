package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.event.Event;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import com.editora.dap.DapModels;
import com.editora.editor.EditorBuffer;
import com.editora.editor.MenuChords;
import com.editora.editor.RowReasons;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The menus this change gave reasons to: each grayed row says the sentence for why it is grayed, in each case, and a
 * row that can run says nothing (KBD-04, Pauta 6.0.1). Built as their code builds them, without opening a window
 * where the menu does not need one.
 */
@Tag("fx")
class GrayedMenuRowsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** {@code item}'s reason, or null when it can run; a grayed row with no reason fails. */
    private static String why(MenuItem item) {
        String reason = RowReasons.reasonOf(item);
        assertEquals(reason != null, item.isDisable(), item.getText() + ": grayed exactly when it says why");
        return reason;
    }

    private static MenuItem titled(List<MenuItem> items, String key) {
        String title = tr(key);
        for (MenuItem item : items) {
            if (title.equals(item.getText())
                    || item.getText() != null && item.getText().startsWith(title)) {
                return item;
            }
            if (item instanceof Menu menu) {
                for (MenuItem inner : menu.getItems()) {
                    if (title.equals(inner.getText())) {
                        return inner;
                    }
                }
            }
        }
        throw new AssertionError("no row " + title + " in "
                + items.stream().map(MenuItem::getText).toList());
    }

    /** {@code MarkdownMenus}' rows for {@code b} with an image chooser: {@code insertImage}. */
    @SuppressWarnings("unchecked")
    private static List<MenuItem> markdownRows(EditorBuffer b, Runnable insertImage) {
        try {
            java.lang.reflect.Method items = Class.forName("com.editora.editor.markdown.MarkdownMenus")
                    .getDeclaredMethod("items", EditorBuffer.class, Runnable.class);
            items.setAccessible(true);
            return (List<MenuItem>) items.invoke(null, b, insertImage);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code editor.markdown.MarkdownMenus} is package-private: its rows for {@code b}. */
    @SuppressWarnings("unchecked")
    private static List<MenuItem> markdownRows(EditorBuffer b) {
        try {
            java.lang.reflect.Method items = Class.forName("com.editora.editor.markdown.MarkdownMenus")
                    .getDeclaredMethod("items", EditorBuffer.class);
            items.setAccessible(true);
            return (List<MenuItem>) items.invoke(null, b);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The words a Markdown row shows for command title {@code key}: the title without its category. */
    private static String markdownLabel(String key) {
        try {
            java.lang.reflect.Method label =
                    Class.forName("com.editora.editor.markdown.MarkdownMenus").getDeclaredMethod("label", String.class);
            label.setAccessible(true);
            return (String) label.invoke(null, tr(key));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MenuItem markdownRow(List<MenuItem> rows, String key) {
        String label = markdownLabel(key);
        return rows.stream()
                .filter(item -> label.equals(item.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + label));
    }

    @SuppressWarnings("unchecked")
    private static List<MenuItem> editorRows(EditorBuffer b) {
        return (List<MenuItem>)
                FxTestSupport.call(FxTestSupport.field(b, "editorMenu"), "standardMenuItems", new Class<?>[0]);
    }

    private static void clipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        if (text != null) {
            content.putString(text);
        }
        Clipboard.getSystemClipboard().setContent(content);
    }

    @Test
    void theEditorsClipboardAndHistoryRowsSayWhatIsMissing() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            clipboard(null);
            List<MenuItem> rows = editorRows(b);
            assertEquals(tr("palette.disabled.needsSelection"), why(titled(rows, "editmenu.cut")));
            assertEquals(tr("palette.disabled.needsSelection"), why(titled(rows, "editmenu.copy")));
            assertEquals(tr("row.unavailable.clipboardEmpty"), why(titled(rows, "editmenu.paste")));
            assertEquals(tr("row.unavailable.nothingToUndo"), why(titled(rows, "editmenu.undo")));
            assertEquals(tr("row.unavailable.nothingToRedo"), why(titled(rows, "editmenu.redo")));
            assertNull(why(titled(rows, "editmenu.selectAll")));

            CodeArea area = b.getFocusedArea();
            area.replaceText("some text");
            area.selectAll();
            clipboard("x");
            rows = editorRows(b);
            for (String key : List.of("editmenu.cut", "editmenu.copy", "editmenu.paste", "editmenu.undo")) {
                assertNull(why(titled(rows, key)), key + " can run");
            }

            b.setReadOnly(true);
            rows = editorRows(b);
            assertEquals(tr("row.unavailable.readOnly"), why(titled(rows, "editmenu.cut")));
            assertEquals(tr("row.unavailable.readOnly"), why(titled(rows, "editmenu.paste")));
            assertNull(why(titled(rows, "editmenu.copy")), "copying from a read-only file is fine");
        });
    }

    @Test
    void theAiRowsSayWhetherTextIsSelectedAndTheFileWritable() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            Object menu = FxTestSupport.field(b, "editorMenu");
            Menu ai = (Menu) FxTestSupport.call(menu, "aiActionsMenu", new Class<?>[0]);
            assertEquals(
                    tr("palette.disabled.needsSelection"), why(ai.getItems().get(0)));
            assertEquals(
                    tr("palette.disabled.needsSelection"), why(ai.getItems().get(1)));
            b.getFocusedArea().replaceText("text");
            b.getFocusedArea().selectAll();
            b.setReadOnly(true);
            ai = (Menu) FxTestSupport.call(menu, "aiActionsMenu", new Class<?>[0]);
            assertNull(why(ai.getItems().get(0)));
            assertEquals(tr("row.unavailable.readOnly"), why(ai.getItems().get(1)), "a rewrite edits the file");
        });
    }

    @Test
    void theMarkdownRowsSayWhereTheCaretIsNot() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(Path.of("notes.md"));
            b.getFocusedArea().replaceText("plain\n\n| a | b |\n|---|---|\n| 1 | 2 |\n");
            b.getFocusedArea().moveTo(0);
            List<MenuItem> rows = markdownRows(b);
            assertEquals(tr("row.unavailable.notOnLink"), why(markdownRow(rows, "command.markdown.removeLink")));
            assertEquals(
                    tr("row.unavailable.notOnWikiLink"), why(markdownRow(rows, "command.markdown.convertWikiLink")));
            assertEquals(tr("row.unavailable.noImageChooser"), why(markdownRow(rows, "command.markdown.insertImage")));
            assertNull(why(markdownRow(rows, "command.markdown.bold")));
            assertNull(
                    why(markdownRow(markdownRows(b, () -> {}), "command.markdown.insertImage")),
                    "a window that chooses images inserts one");
            Menu table = (Menu) rows.stream()
                    .filter(item -> item instanceof Menu)
                    .findFirst()
                    .orElseThrow();
            List<MenuItem> tableRows = table.getItems().stream()
                    .filter(item -> !(item instanceof SeparatorMenuItem))
                    .toList();
            assertNull(why(tableRows.get(0)), "Insert Table goes anywhere");
            for (MenuItem row : tableRows.subList(1, tableRows.size())) {
                if (!row.getText().equals(markdownLabel("command.markdown.tableFromCsv"))) {
                    assertEquals(tr("row.unavailable.notInTable"), why(row), row.getText());
                }
            }
            b.getFocusedArea().moveTo(b.getFocusedArea().getText().indexOf("| 1") + 2);
            rows = markdownRows(b);
            table = (Menu) rows.stream()
                    .filter(item -> item instanceof Menu)
                    .findFirst()
                    .orElseThrow();
            for (MenuItem row : table.getItems()) {
                if (!(row instanceof SeparatorMenuItem)) {
                    assertNull(why(row), row.getText() + " in a table");
                }
            }

            // On a link its rows can run; on a wiki link, its conversion too.
            b.getFocusedArea().replaceText("see [site](http://example.com) and [[Page]]\n");
            b.getFocusedArea().moveTo(6);
            rows = markdownRows(b);
            assertNull(why(markdownRow(rows, "command.markdown.removeLink")), "on a link");
            assertNull(why(markdownRow(rows, "command.markdown.openLink")), "on a link");
            b.getFocusedArea().moveTo(b.getFocusedArea().getText().indexOf("Page") + 1);
            rows = markdownRows(b);
            assertNull(why(markdownRow(rows, "command.markdown.convertWikiLink")), "on a wiki link");
        });
    }

    @Test
    void aConsolesRowsSayItIsEmptyUntilItHasText() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TextField log = new TextField();
            ContextMenu logMenu = ConsoleContextMenu.install(log, () -> {}, "debugLog.find");
            logMenu.getOnShowing().handle(new WindowEvent(logMenu, WindowEvent.WINDOW_SHOWING));
            for (MenuItem row : logMenu.getItems()) {
                if (!(row instanceof SeparatorMenuItem)) {
                    assertEquals(tr("row.unavailable.emptyView"), why(row), row.getText());
                }
            }
            log.setText("a line");
            logMenu.getOnShowing().handle(new WindowEvent(logMenu, WindowEvent.WINDOW_SHOWING));
            for (MenuItem row : logMenu.getItems()) {
                if (!(row instanceof SeparatorMenuItem)) {
                    assertNull(why(row), row.getText());
                }
            }
        });
    }

    @Test
    void aListsRowsSayNothingIsSelectedOrWhatTheirButtonLacks() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ListView<String> list = new ListView<>();
            list.getItems().addAll("one", "two");
            Button up = new Button("▲");
            Button down = new Button("▼");
            Button remove = new Button("Remove");
            Button bundled = ListRowCommands.because(new Button("Delete"), () -> "row.unavailable.bundledSnippet");
            MenuItem upRow = ListRowCommands.commandButtonRow(list, ListRowCommands.MOVE_UP, up, null);
            MenuItem downRow = ListRowCommands.commandButtonRow(list, ListRowCommands.MOVE_DOWN, down, null);
            MenuItem removeRow = ListRowCommands.buttonRow(list, ListRowCommands.REMOVE, remove, null);
            MenuItem bundledRow = ListRowCommands.buttonRow(list, ListRowCommands.REMOVE, bundled, null);
            MenuItem ruled = ListRowCommands.enabledWhen(
                    new MenuItem("Run"), () -> false, () -> null); // a rule that says nothing of its own
            MenuItem task = ListRowCommands.enabledWhen(new MenuItem("Task"), () -> false, "row.unavailable.notATask");
            ContextMenu menu = ListRowCommands.install(list, upRow, downRow, removeRow, bundledRow, ruled, task);

            menu.getOnShowing().handle(new WindowEvent(menu, WindowEvent.WINDOW_SHOWING));
            for (MenuItem row : menu.getItems()) {
                assertEquals(tr("row.unavailable.noSelection"), why(row), row.getText());
            }

            list.getSelectionModel().select(0);
            up.setDisable(true);
            down.setDisable(false);
            remove.setDisable(true);
            bundled.setDisable(true);
            menu.getOnShowing().handle(new WindowEvent(menu, WindowEvent.WINDOW_SHOWING));
            assertEquals(tr("row.unavailable.firstRow"), why(upRow));
            assertNull(why(downRow));
            assertEquals(tr("row.unavailable.noSelection"), why(removeRow), "a button with nothing to say");
            assertEquals(tr("row.unavailable.bundledSnippet"), why(bundledRow), "what its button says");
            assertEquals(tr("row.unavailable.notForThisRow"), why(ruled));
            assertEquals(tr("row.unavailable.notATask"), why(task));

            down.setDisable(true);
            menu.getOnShowing().handle(new WindowEvent(menu, WindowEvent.WINDOW_SHOWING));
            assertEquals(tr("row.unavailable.lastRow"), why(downRow));

            // A control whose rows need something else than a selection says what.
            ListView<String> table = new ListView<>();
            MenuItem copy = new MenuItem("Copy");
            ContextMenu tableMenu =
                    RowMenus.install(table, () -> !table.getItems().isEmpty(), "row.unavailable.emptyTable", copy);
            tableMenu.getOnShowing().handle(new WindowEvent(tableMenu, WindowEvent.WINDOW_SHOWING));
            assertEquals(tr("row.unavailable.emptyTable"), why(copy));
            table.getItems().add("x");
            tableMenu.getOnShowing().handle(new WindowEvent(tableMenu, WindowEvent.WINDOW_SHOWING));
            assertNull(why(copy));
        });
    }

    @Test
    void aMenuRowsCommandSaysWhatItsGrayedRowSays() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MenuItem up = MenuChords.tag(new MenuItem("Up"), "bookmarks.moveSelectedUp");
            MenuItem gone = MenuChords.tag(new MenuItem("Gone"), "bookmarks.deleteSelected");
            RowReasons.because(up, "row.unavailable.bookmarksFiltered");
            RowReasons.placeholder(gone);
            List<MenuItem> rows = List.of(up, new Menu("More", null, gone));
            assertEquals(tr("row.unavailable.bookmarksFiltered"), MenuRows.reason(rows, "bookmarks.moveSelectedUp"));
            assertNull(MenuRows.reason(rows, "bookmarks.deleteSelected"), "a placeholder says nothing");
            assertNull(MenuRows.reason(rows, "bookmarks.editSelectedNote"), "nor a row that is not there");
            RowReasons.because(up, null);
            assertNull(MenuRows.reason(rows, "bookmarks.moveSelectedUp"), "nor one that can run");
        });
    }

    @Test
    void aMapSubmenuSaysItsRowsReasonWhenTheyAllShareOne() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MenuItem a = RowReasons.because(new MenuItem("a"), "row.unavailable.languageCannotAnswer");
            MenuItem b = RowReasons.because(new MenuItem("b"), "row.unavailable.languageCannotAnswer");
            MenuItem c = RowReasons.because(new MenuItem("c"), "row.unavailable.declarationGone");
            MenuItem d = new MenuItem("d");
            assertEquals(
                    tr("row.unavailable.languageCannotAnswer"),
                    why(ProjectMapPreview.submenu("x", List.of(a, new SeparatorMenuItem(), b))));
            assertEquals(tr("row.unavailable.noRowFits"), why(ProjectMapPreview.submenu("x", List.of(a, c))));
            assertNull(why(ProjectMapPreview.submenu("x", List.of(a, d))), "one row can run: the submenu opens");
        });
    }

    @Test
    void restartFrameSaysWhyItCannotRestartTheFrame() {
        var top = new DapModels.StackFrameInfo(1, "top", null, 3, 0);
        var bottom = new DapModels.StackFrameInfo(2, "main", null, 9, 0);
        List<DapModels.StackFrameInfo> frames = List.of(top, bottom);
        assertEquals("palette.disabled.needsSuspended", DebugPanelText.restartFrameReason(false, true, top, frames));
        assertEquals("row.unavailable.cannotRestartFrame", DebugPanelText.restartFrameReason(true, false, top, frames));
        assertEquals("row.unavailable.bottomFrame", DebugPanelText.restartFrameReason(true, true, bottom, frames));
        assertEquals("row.unavailable.bottomFrame", DebugPanelText.restartFrameReason(true, true, null, frames));
        assertNull(DebugPanelText.restartFrameReason(true, true, top, frames));
        assertEquals("row.unavailable.bottomFrame", DebugPanelText.restartFrameReason(true, true, top, null));
    }

    /** The menu JavaFX itself gives a text field, opened by a right-click on {@code field}; null when none shows. */
    private static ContextMenu stockMenu(TextInputControl field) {
        Event.fireEvent(field, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 5, 5, false, null));
        for (Window w : Window.getWindows()) {
            if (w instanceof ContextMenu menu && menu.getOwnerNode() == field) {
                return menu;
            }
        }
        return null;
    }

    @Test
    void aTextFieldsOwnMenuSaysWhyJavaFxGraysItsRows() throws Exception {
        Stage[] stage = new Stage[1];
        TextField field = new TextField();
        PasswordField password = new PasswordField();
        try {
            FxTestSupport.runOnFx(() -> {
                stage[0] = new Stage();
                stage[0].setScene(new Scene(new StackPane(new javafx.scene.layout.VBox(field, password)), 300, 200));
                stage[0].show();
                clipboard(null);
            });
            Map<Integer, String> expected = Map.of(
                    0, tr("row.unavailable.nothingToUndo"),
                    1, tr("row.unavailable.nothingToRedo"),
                    2, tr("palette.disabled.needsSelection"),
                    3, tr("palette.disabled.needsSelection"),
                    4, tr("row.unavailable.clipboardEmpty"),
                    5, tr("palette.disabled.needsSelection"));
            List<String> seen = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                ContextMenu menu = stockMenu(field);
                assertNotNull(menu, "a text field opens JavaFX's own menu");
                for (int i = 0; i < menu.getItems().size(); i++) {
                    seen.add(RowReasons.reasonOf(menu.getItems().get(i)));
                }
                menu.hide();
            });
            for (Map.Entry<Integer, String> e : expected.entrySet()) {
                assertEquals(e.getValue(), seen.get(e.getKey()), "row " + e.getKey());
            }
            assertNull(seen.get(7), "Select All can run");

            // Text selected and on the clipboard: what JavaFX enables says nothing.
            FxTestSupport.runOnFx(() -> {
                field.setText("abc");
                field.selectAll();
                clipboard("x");
                ContextMenu menu = stockMenu(field);
                assertNull(RowReasons.reasonOf(menu.getItems().get(3)), "Copy");
                assertNull(RowReasons.reasonOf(menu.getItems().get(4)), "Paste");
                assertFalse(menu.getItems().get(3).isDisable());
                menu.hide();

                password.setText("secret");
                password.selectAll();
                ContextMenu secret = stockMenu(password);
                assertEquals(
                        tr("row.unavailable.password"),
                        RowReasons.reasonOf(secret.getItems().get(3)));
                assertTrue(secret.getItems().get(3).isDisable(), "JavaFX's own gray is kept");
                secret.hide();

                field.setEditable(false);
                field.deselect();
                ContextMenu readOnly = stockMenu(field);
                assertEquals(3, readOnly.getItems().size(), "Copy, a separator, Select All");
                assertEquals(
                        tr("palette.disabled.needsSelection"),
                        RowReasons.reasonOf(readOnly.getItems().get(0)));
                readOnly.hide();
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                if (stage[0] != null) {
                    stage[0].close();
                }
            });
        }
    }

    @Test
    void aMenuShownFromAnOwnerSaysWhyAsItShows() throws Exception {
        Stage[] stage = new Stage[1];
        try {
            FxTestSupport.runOnFx(() -> {
                stage[0] = new Stage();
                Button owner = new Button("x");
                stage[0].setScene(new Scene(new StackPane(owner), 200, 200));
                stage[0].show();
                MenuItem row = RowReasons.because(new MenuItem("Row"), "row.unavailable.noSelection");
                ContextMenu menu = new ContextMenu(row);
                menu.show(owner, Side.BOTTOM, 0, 0);
                assertNotNull(RowReasons.tooltipOf(row), "its first showing already says why");
                menu.hide();
                RowReasons.because(row, "row.unavailable.historyEmpty");
                menu.show(owner, Side.BOTTOM, 0, 0);
                assertEquals(
                        tr("row.unavailable.historyEmpty"),
                        RowReasons.tooltipOf(row).getText());
                menu.hide();
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
