package com.editora.editor;

import java.util.List;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import static com.editora.i18n.Messages.tr;

/**
 * The right-click menu of a buffer's rendered preview: Copy (rendered text), the link or code block under the
 * pointer, the exports that apply to the buffer's kind, Print. Built once per buffer; the actions are the
 * buffer's injected handlers, read when an item is chosen.
 */
final class PreviewMenu {

    private PreviewMenu() {}

    static ContextMenu build(EditorBuffer b) {
        // No "Select All": nothing in the preview can be selected, and the item only ever copied.
        MenuItem copy = new MenuItem(tr("previewmenu.copyAll"));
        copy.setGraphic(MenuIcons.copy());
        copy.setOnAction(ev -> b.copyPreviewToClipboard());
        ContextMenu menu = new ContextMenu(copy);
        if (b.isMarkdown()) {
            // What the pointer was on when the menu opened: shown only when it was on one.
            MenuItem copyLink = new MenuItem(tr("previewmenu.copyLink"));
            copyLink.setGraphic(MenuIcons.copy());
            copyLink.setOnAction(ev -> MarkdownRenderer.copyToClipboard(b.previewMenuLink));
            MenuItem copyCode = new MenuItem(tr("previewmenu.copyCode"));
            copyCode.setGraphic(MenuIcons.copy());
            copyCode.setOnAction(ev -> MarkdownRenderer.copyToClipboard(b.previewMenuCode));
            menu.getItems().addAll(0, List.of(copyLink, copyCode));
            menu.setOnShowing(ev -> {
                copyLink.setVisible(b.previewMenuLink != null);
                copyCode.setVisible(b.previewMenuCode != null);
            });
            // Copy puts rendered rich text on the clipboard; this one puts the HTML markup itself.
            MenuItem copyHtml = new MenuItem(tr("previewmenu.copyHtml"));
            copyHtml.setGraphic(MenuIcons.code());
            copyHtml.setOnAction(ev -> b.copyPreviewHtmlSource());
            menu.getItems().add(copyHtml);
        }
        menu.getItems().add(new SeparatorMenuItem());
        if (b.isMarkwhen()) {
            // Markwhen: JSON export + a timeline⇄calendar view switch (PDF/Word/Print don't apply).
            MenuItem json = new MenuItem(tr("command.markwhen.exportJson"));
            json.setGraphic(MenuIcons.download());
            json.setOnAction(ev -> b.previewExportJsonHandler.run());
            MenuItem viewToggle = new MenuItem();
            viewToggle.setGraphic(MenuIcons.table());
            viewToggle.setOnAction(ev -> b.toggleMarkwhenView());
            MenuItem pdf = new MenuItem(tr("previewmenu.exportPdf"));
            pdf.setGraphic(MenuIcons.download());
            pdf.setOnAction(ev -> b.previewExportPdfHandler.run());
            MenuItem print = new MenuItem(tr("previewmenu.print"));
            print.setGraphic(MenuIcons.print());
            print.setOnAction(ev -> b.previewPrintHandler.run());
            menu.getItems().addAll(json, viewToggle, new SeparatorMenuItem(), pdf, print);
            menu.setOnShowing(ev -> viewToggle.setText(
                    b.getMarkwhenView() == EditorBuffer.MarkwhenView.TIMELINE
                            ? tr("markwhen.switchToCalendar")
                            : tr("markwhen.switchToTimeline")));
        } else {
            MenuItem pdf = new MenuItem(tr("previewmenu.exportPdf"));
            pdf.setGraphic(MenuIcons.download());
            pdf.setOnAction(ev -> b.previewExportPdfHandler.run());
            menu.getItems().add(pdf);
            // Typst also exports to PNG / SVG natively (typst -f png/svg).
            if (b.isTypst()) {
                MenuItem png = new MenuItem(tr("command.typst.exportPng"));
                png.setGraphic(MenuIcons.download());
                png.setOnAction(ev -> b.previewExportPngHandler.run());
                MenuItem svg = new MenuItem(tr("command.typst.exportSvg"));
                svg.setGraphic(MenuIcons.download());
                svg.setOnAction(ev -> b.previewExportSvgHandler.run());
                menu.getItems().addAll(png, svg);
            }
            // Word / OpenDocument export — Markdown only (not standalone diagrams).
            if (b.isMarkdown()) {
                MenuItem docx = new MenuItem(tr("previewmenu.exportDocx"));
                docx.setGraphic(MenuIcons.download());
                docx.setOnAction(ev -> b.previewExportDocxHandler.run());
                MenuItem odt = new MenuItem(tr("previewmenu.exportOdt"));
                odt.setGraphic(MenuIcons.download());
                odt.setOnAction(ev -> b.previewExportOdtHandler.run());
                menu.getItems().addAll(docx, odt);
            }
            MenuItem print = new MenuItem(tr("previewmenu.print"));
            print.setGraphic(MenuIcons.print());
            print.setOnAction(ev -> b.previewPrintHandler.run());
            menu.getItems().addAll(new SeparatorMenuItem(), print);
        }
        menu.getStyleClass().add("editor-context-menu");
        return menu;
    }
}
