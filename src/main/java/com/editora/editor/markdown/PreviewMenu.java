package com.editora.editor.markdown;

import java.util.List;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;

import com.editora.editor.EditorBuffer;
import com.editora.editor.MenuIcons;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The right-click menu of a buffer's rendered preview: Copy (rendered text), the link or code block under the
 * pointer, the exports that apply to the buffer's kind, Print. One per buffer; it owns the export and print actions
 * the window injects ({@link #setExportPdf} and the rest) and what the pointer was on when it was asked for, and
 * builds the menu itself on first use.
 *
 * <p>In Simple UI mode the menu keeps the copy actions, Go to Source, Enlarge, Export to PDF and Print — as the
 * Simple File menu keeps Print and Export to PDF — and hides Copy HTML, the other export formats (Word,
 * OpenDocument, PNG, SVG, Markwhen JSON). Checked on every showing, as the menu outlives a toggle.
 */
public final class PreviewMenu {

    private static final Runnable NONE = () -> {};

    private final EditorBuffer b;
    private final Supplier<@Nullable MarkdownPreview> preview;

    private Runnable exportPdf = NONE;
    private Runnable exportPng = NONE;
    private Runnable exportSvg = NONE;
    private Runnable print = NONE;
    private Runnable exportDocx = NONE;
    private Runnable exportOdt = NONE;
    private Runnable exportJson = NONE;

    /** The link / code block / node under the pointer (and its scene y) when the menu was asked for. */
    private @Nullable String link;

    private @Nullable String code;
    private @Nullable Node hit;
    private double hitY;
    private @Nullable ContextMenu menu;

    /**
     * @param buffer  the buffer whose preview this is
     * @param preview its Markdown preview, null until one is built
     */
    public PreviewMenu(EditorBuffer buffer, Supplier<@Nullable MarkdownPreview> preview) {
        this.b = buffer;
        this.preview = preview;
    }

    private static Runnable orNone(@Nullable Runnable action) {
        return action == null ? NONE : action;
    }

    /** Export to PDF (the window's command); null: nothing. Also the tree previews' menu item. */
    public void setExportPdf(@Nullable Runnable action) {
        exportPdf = orNone(action);
    }

    /** Typst's Export to PNG. */
    public void setExportPng(@Nullable Runnable action) {
        exportPng = orNone(action);
    }

    /** Typst's Export to SVG. */
    public void setExportSvg(@Nullable Runnable action) {
        exportSvg = orNone(action);
    }

    /** Print. Also the tree previews' menu item. */
    public void setPrint(@Nullable Runnable action) {
        print = orNone(action);
    }

    /** Markdown's Export to Word (.docx). */
    public void setExportDocx(@Nullable Runnable action) {
        exportDocx = orNone(action);
    }

    /** Markdown's Export to OpenDocument (.odt). */
    public void setExportOdt(@Nullable Runnable action) {
        exportOdt = orNone(action);
    }

    /** Markwhen's Export to JSON. */
    public void setExportJson(@Nullable Runnable action) {
        exportJson = orNone(action);
    }

    /** Runs the injected Export to PDF. */
    public void exportPdf() {
        exportPdf.run();
    }

    /** Runs the injected Print. */
    public void print() {
        print.run();
    }

    /**
     * Shows the menu for a right-click on {@code target} (the node the pointer was on, null for none) at scene y
     * {@code sceneY}, at screen {@code (screenX, screenY)} over {@code owner}.
     */
    public void show(Node owner, @Nullable Node target, double sceneY, double screenX, double screenY) {
        link = MarkdownRenderer.property(target, MarkdownRenderer.LINK_KEY);
        code = MarkdownRenderer.property(target, MarkdownRenderer.CODE_KEY);
        hit = target;
        hitY = sceneY;
        if (menu == null) {
            menu = build();
        }
        menu.show(owner, screenX, screenY);
    }

    /**
     * Copies the preview to the clipboard: the selection when the rendered Markdown has one, else all of it —
     * rendered plain text for Markdown, the source for a diagram.
     *
     * <p>Markdown additionally carries a {@code text/html} flavor ({@link MarkdownClipboardHtml}), so the same
     * copy pastes as <em>formatted</em> text into Word / Teams / Outlook / Gmail while a plain-text target
     * still gets the markup-stripped text. Both flavors always go on together: the consumer picks.
     */
    public void copy() {
        MarkdownPreview p = preview.get();
        if (p != null && b.isMarkdown() && p.copySelection()) {
            return;
        }
        copyAll();
    }

    private void copyAll() {
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
    public boolean copyHtmlSource() {
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
     * preview is showing, or its {@code pane} has the focus: Select All selects the preview's text, Copy copies its
     * selection. False (nothing done) anywhere else, and for Copy when nothing is selected there.
     */
    public boolean editCommand(@Nullable ScrollPane pane, boolean selectAll) {
        MarkdownPreview p = preview.get();
        if (p == null || !b.isMarkdown() || pane == null || !p.isShowing()) {
            return false;
        }
        if (!b.isPreviewOnly() && !pane.isFocused()) {
            return false;
        }
        return selectAll ? p.selection().selectAll() : p.copySelection();
    }

    /** Runs {@code action} on the Markdown preview; nothing when there is none yet. */
    private void withPreview(java.util.function.Consumer<MarkdownPreview> action) {
        MarkdownPreview p = preview.get();
        if (p != null) {
            action.accept(p);
        }
    }

    private ContextMenu build() {
        MenuItem copyAll = new MenuItem(tr("previewmenu.copyAll"));
        copyAll.setGraphic(MenuIcons.copy());
        copyAll.setOnAction(ev -> copyAll());
        ContextMenu m = new ContextMenu(copyAll);
        List<MenuItem> fullOnly = new java.util.ArrayList<>(); // hidden in Simple UI mode
        if (b.isMarkdown()) {
            // The selection: Copy is offered while there is one; Select All makes one.
            MenuItem copy = new MenuItem(tr("previewmenu.copy"));
            copy.setGraphic(MenuIcons.copy());
            copy.setOnAction(ev -> withPreview(MarkdownPreview::copySelection));
            MenuItem selectAll = new MenuItem(tr("previewmenu.selectAll"));
            selectAll.setOnAction(ev -> withPreview(p -> p.selection().selectAll()));
            MenuItem toSource = new MenuItem(tr("previewmenu.goToSource"));
            toSource.setOnAction(ev -> withPreview(p -> p.jumpToSourceAt(hit, hitY)));
            MenuItem enlarge = new MenuItem(tr("previewmenu.enlarge"));
            enlarge.setOnAction(ev -> withPreview(p -> p.enlarge(MarkdownPreview.enlargeableAt(hit))));
            m.getItems().add(0, copy);
            m.getItems().addAll(selectAll, toSource, enlarge);
            m.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWING, ev -> {
                MarkdownPreview p = preview.get();
                copy.setVisible(p != null && p.selection().isActive());
                selectAll.setDisable(p == null);
                Node picture = MarkdownPreview.enlargeableAt(hit);
                enlarge.setVisible(p != null && picture != null && PreviewLightbox.imageOf(picture) != null);
                toSource.setVisible(p != null && p.sourceLineAt(hit, hitY) >= 0);
            });
            // What the pointer was on when the menu opened: shown only when it was on one.
            MenuItem copyLink = new MenuItem(tr("previewmenu.copyLink"));
            copyLink.setGraphic(MenuIcons.copy());
            copyLink.setOnAction(ev -> MarkdownRenderer.copyToClipboard(link));
            MenuItem copyCode = new MenuItem(tr("previewmenu.copyCode"));
            copyCode.setGraphic(MenuIcons.copy());
            copyCode.setOnAction(ev -> MarkdownRenderer.copyToClipboard(code));
            m.getItems().addAll(0, List.of(copyLink, copyCode));
            m.setOnShowing(ev -> {
                copyLink.setVisible(link != null);
                copyCode.setVisible(code != null);
            });
            // Copy puts rendered rich text on the clipboard; this one puts the HTML markup itself.
            MenuItem copyHtml = new MenuItem(tr("previewmenu.copyHtml"));
            copyHtml.setGraphic(MenuIcons.code());
            copyHtml.setOnAction(ev -> copyHtmlSource());
            m.getItems().add(copyHtml);
            fullOnly.add(copyHtml);
        }
        m.getItems().add(new SeparatorMenuItem());
        if (b.isMarkwhen()) {
            // Markwhen: JSON export + a timeline⇄calendar view switch (PDF/Word/Print don't apply).
            MenuItem json = new MenuItem(tr("command.markwhen.exportJson"));
            json.setGraphic(MenuIcons.download());
            json.setOnAction(ev -> exportJson.run());
            MenuItem viewToggle = new MenuItem();
            viewToggle.setGraphic(MenuIcons.table());
            viewToggle.setOnAction(ev -> b.toggleMarkwhenView());
            MenuItem pdf = new MenuItem(tr("previewmenu.exportPdf"));
            pdf.setGraphic(MenuIcons.download());
            pdf.setOnAction(ev -> exportPdf.run());
            MenuItem printItem = new MenuItem(tr("previewmenu.print"));
            printItem.setGraphic(MenuIcons.print());
            printItem.setOnAction(ev -> print.run());
            m.getItems().addAll(json, viewToggle, new SeparatorMenuItem(), pdf, printItem);
            fullOnly.add(json);
            m.setOnShowing(ev -> viewToggle.setText(
                    b.getMarkwhenView() == EditorBuffer.MarkwhenView.TIMELINE
                            ? tr("markwhen.switchToCalendar")
                            : tr("markwhen.switchToTimeline")));
        } else {
            MenuItem pdf = new MenuItem(tr("previewmenu.exportPdf"));
            pdf.setGraphic(MenuIcons.download());
            pdf.setOnAction(ev -> exportPdf.run());
            m.getItems().add(pdf);
            // Typst also exports to PNG / SVG natively (typst -f png/svg).
            if (b.isTypst()) {
                MenuItem png = new MenuItem(tr("command.typst.exportPng"));
                png.setGraphic(MenuIcons.download());
                png.setOnAction(ev -> exportPng.run());
                MenuItem svg = new MenuItem(tr("command.typst.exportSvg"));
                svg.setGraphic(MenuIcons.download());
                svg.setOnAction(ev -> exportSvg.run());
                m.getItems().addAll(png, svg);
                fullOnly.addAll(List.of(png, svg));
            }
            // Word / OpenDocument export — Markdown only (not standalone diagrams).
            if (b.isMarkdown()) {
                MenuItem docx = new MenuItem(tr("previewmenu.exportDocx"));
                docx.setGraphic(MenuIcons.download());
                docx.setOnAction(ev -> exportDocx.run());
                MenuItem odt = new MenuItem(tr("previewmenu.exportOdt"));
                odt.setGraphic(MenuIcons.download());
                odt.setOnAction(ev -> exportOdt.run());
                m.getItems().addAll(docx, odt);
                fullOnly.addAll(List.of(docx, odt));
            }
            MenuItem printItem = new MenuItem(tr("previewmenu.print"));
            printItem.setGraphic(MenuIcons.print());
            printItem.setOnAction(ev -> print.run());
            m.getItems().addAll(new SeparatorMenuItem(), printItem);
        }
        // An event handler, not setOnShowing: the branches above each install their own onShowing.
        m.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWING, ev -> {
            boolean full = !b.isSimpleMenu();
            fullOnly.forEach(item -> item.setVisible(full));
        });
        m.getStyleClass().add("editor-context-menu");
        return m;
    }
}
