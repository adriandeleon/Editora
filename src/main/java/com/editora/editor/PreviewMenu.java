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

    /**
     * Copies the preview to the clipboard: the selection when the rendered Markdown has one, else all of it —
     * rendered plain text for Markdown, the source for a diagram.
     *
     * <p>Markdown additionally carries a {@code text/html} flavor ({@link MarkdownClipboardHtml}), so the same
     * copy pastes as <em>formatted</em> text into Word / Teams / Outlook / Gmail while a plain-text target
     * still gets the markup-stripped text. Both flavors always go on together: the consumer picks.
     */
    static void copy(EditorBuffer b) {
        if (b.markdownPreview != null && b.isMarkdown() && b.markdownPreview.copySelection()) {
            return;
        }
        copyAll(b);
    }

    static void copyAll(EditorBuffer b) {
        String text = b.isMarkdown() ? MarkdownRenderer.plainText(b.getContent()) : b.getContent();
        javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
        cc.putString(text == null ? "" : text);
        if (b.isMarkdown()) {
            cc.putHtml(MarkdownClipboardHtml.toHtml(b.getContent(), MathImages.isEnabled(), b.getPath()));
        }
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
    }

    /**
     * Copies the preview's HTML <em>markup</em> as plain text (for pasting into an HTML file), as opposed to
     * {@link #copy}, which puts rendered rich text on the clipboard. Markdown only.
     */
    static boolean copyHtmlSource(EditorBuffer b) {
        if (!b.isMarkdown()) {
            return false;
        }
        javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
        cc.putString(MarkdownClipboardHtml.toMarkup(b.getContent(), MathImages.isEnabled()));
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
        return true;
    }

    /**
     * The editor's Copy or Select All command, when the rendered Markdown is where the user is — only the
     * preview is showing, or its pane has the focus: Select All selects the preview's text, Copy copies its
     * selection. False (nothing done) anywhere else, and for Copy when nothing is selected there.
     */
    static boolean editCommand(EditorBuffer b, javafx.scene.control.ScrollPane pane, boolean selectAll) {
        MarkdownPreview preview = b.markdownPreview;
        if (preview == null || !b.isMarkdown() || pane == null || !preview.isShowing()) {
            return false;
        }
        if (!b.isPreviewOnly() && !pane.isFocused()) {
            return false;
        }
        return selectAll ? preview.selection().selectAll() : preview.copySelection();
    }

    static ContextMenu build(EditorBuffer b) {
        MenuItem copyAll = new MenuItem(tr("previewmenu.copyAll"));
        copyAll.setGraphic(MenuIcons.copy());
        copyAll.setOnAction(ev -> copyAll(b));
        ContextMenu menu = new ContextMenu(copyAll);
        if (b.isMarkdown()) {
            // The selection: Copy is offered while there is one; Select All makes one.
            MenuItem copy = new MenuItem(tr("previewmenu.copy"));
            copy.setGraphic(MenuIcons.copy());
            copy.setOnAction(ev -> b.markdownPreview.copySelection());
            MenuItem selectAll = new MenuItem(tr("previewmenu.selectAll"));
            selectAll.setOnAction(ev -> b.markdownPreview.selection().selectAll());
            MenuItem toSource = new MenuItem(tr("previewmenu.goToSource"));
            toSource.setOnAction(ev -> b.markdownPreview.jumpToSourceAt(b.previewMenuHit, b.previewMenuY));
            menu.getItems().add(0, copy);
            menu.getItems().addAll(selectAll, toSource);
            menu.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWING, ev -> {
                MarkdownPreview preview = b.markdownPreview;
                copy.setVisible(preview != null && preview.selection().isActive());
                selectAll.setDisable(preview == null);
                toSource.setVisible(preview != null && preview.sourceLineAt(b.previewMenuHit, b.previewMenuY) >= 0);
            });
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
