package com.editora.editor;

import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.scene.Node;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import com.editora.markdown.MarkdownTable;

import static com.editora.i18n.Messages.tr;

/**
 * The "Markdown" submenu of the editor's right-click menu: inline markers, link, lists, heading level, the
 * Table submenu and the table of contents — the same actions the palette commands run.
 *
 * <p>Items are labelled with the command's title without its group prefix: under "Markdown ▸ Table" an
 * item reads "Add Row", not "Table: Add Row". Table items that need the caret in a table are disabled
 * when it is not, instead of doing nothing when chosen.
 */
final class MarkdownMenus {

    private MarkdownMenus() {}

    /**
     * A palette title without its group: {@code "Markdown: Bold"} → {@code "Bold"}, {@code "Tableau :
     * Insérer…"} → {@code "Insérer…"}. A title with no group is returned as it is. Pure.
     */
    static String label(String commandTitle) {
        int colon = commandTitle.indexOf(':');
        if (colon <= 0 || colon > 24 || colon + 1 >= commandTitle.length()) {
            return commandTitle;
        }
        String rest = commandTitle.substring(colon + 1).strip();
        if (rest.isEmpty()) {
            return commandTitle;
        }
        return rest.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + rest.substring(1);
    }

    static List<MenuItem> items(EditorBuffer b) {
        MarkdownEditing md = b.markdown();
        return List.of(
                item("command.markdown.bold", MenuIcons.bold(), () -> md.formatInline("**")),
                item("command.markdown.italic", MenuIcons.italic(), () -> md.formatInline("*")),
                item("command.markdown.strikethrough", MenuIcons.strikethrough(), () -> md.formatInline("~~")),
                item("command.markdown.code", MenuIcons.code(), () -> md.formatInline("`")),
                item("command.markdown.link", MenuIcons.link(), md::formatLinkFromClipboard),
                enabledWhen(
                        item("command.markdown.openLink", null, b::openLinkUnderCaret),
                        () -> b.linkUnderCaret() != null),
                new SeparatorMenuItem(),
                item("command.markdown.bulletList", MenuIcons.bulletList(), md::formatBulletList),
                item("command.markdown.taskList", MenuIcons.taskList(), md::formatTaskList),
                item("command.markdown.headingPromote", null, () -> md.formatHeading(-1)),
                item("command.markdown.headingDemote", null, () -> md.formatHeading(1)),
                new SeparatorMenuItem(),
                tableMenu(md),
                item("command.markdown.toc", MenuIcons.bulletList(), md::insertOrUpdateToc));
    }

    /** The "Table" submenu: insert, reformat, rows and columns, column alignment, CSV in and out. */
    private static Menu tableMenu(MarkdownEditing md) {
        boolean inTable = md.caretInTable();
        BooleanSupplier onTable = () -> inTable;
        Menu menu = new Menu(tr("menu.markdown.table"));
        menu.setGraphic(MenuIcons.table());
        menu.getItems()
                .addAll(
                        item("command.markdown.insertTable", MenuIcons.table(), md::insertTableInteractive),
                        enabledWhen(item("command.markdown.reflowTable", null, md::reflowTable), onTable),
                        new SeparatorMenuItem(),
                        enabledWhen(item("command.markdown.tableAddRow", MenuIcons.add(), md::tableAddRow), onTable),
                        enabledWhen(
                                item("command.markdown.tableDeleteRow", MenuIcons.remove(), md::tableDeleteRow),
                                onTable),
                        enabledWhen(
                                item("command.markdown.tableAddColumn", MenuIcons.add(), md::tableAddColumn), onTable),
                        enabledWhen(
                                item("command.markdown.tableDeleteColumn", MenuIcons.remove(), md::tableDeleteColumn),
                                onTable),
                        new SeparatorMenuItem(),
                        enabledWhen(
                                item(
                                        "command.markdown.tableAlignLeft",
                                        MenuIcons.alignLeft(),
                                        () -> md.tableSetAlignment(MarkdownTable.Align.LEFT)),
                                onTable),
                        enabledWhen(
                                item(
                                        "command.markdown.tableAlignCenter",
                                        MenuIcons.alignCenter(),
                                        () -> md.tableSetAlignment(MarkdownTable.Align.CENTER)),
                                onTable),
                        enabledWhen(
                                item(
                                        "command.markdown.tableAlignRight",
                                        MenuIcons.alignRight(),
                                        () -> md.tableSetAlignment(MarkdownTable.Align.RIGHT)),
                                onTable),
                        new SeparatorMenuItem(),
                        item("command.markdown.tableFromCsv", MenuIcons.paste(), md::tableFromCsv),
                        enabledWhen(item("command.markdown.tableToCsv", MenuIcons.copy(), md::tableToCsv), onTable),
                        new SeparatorMenuItem(),
                        enabledWhen(
                                item(
                                        "command.markdown.tableExportCsv",
                                        MenuIcons.download(),
                                        () -> md.exportTableFile("csv")),
                                onTable),
                        enabledWhen(
                                item(
                                        "command.markdown.tableExportExcel",
                                        MenuIcons.download(),
                                        () -> md.exportTableFile("xlsx")),
                                onTable),
                        enabledWhen(
                                item(
                                        "command.markdown.tableExportOds",
                                        MenuIcons.download(),
                                        () -> md.exportTableFile("ods")),
                                onTable));
        return menu;
    }

    private static MenuItem item(String commandKey, Node icon, Runnable action) {
        MenuItem mi = new MenuItem(label(tr(commandKey)));
        if (icon != null) {
            mi.setGraphic(icon);
        }
        mi.setOnAction(e -> action.run());
        return mi;
    }

    private static MenuItem enabledWhen(MenuItem item, BooleanSupplier enabled) {
        item.setDisable(!enabled.getAsBoolean());
        return item;
    }
}
