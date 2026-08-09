package com.editora.editor;

import java.util.Map;

import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.ThematicBreak;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Renders a Markdown document to the HTML <em>fragment</em> put on the clipboard's {@code text/html} flavor,
 * so a preview copy pastes as formatted text into Word / LibreOffice / Outlook / Teams / Gmail.
 *
 * <p>This is deliberately <em>not</em> {@link MarkdownHtmlExport}, whose standalone page carries the
 * {@code markdown-export.css} stylesheet: that sheet is written in CSS custom properties ({@code :root},
 * {@code var(--fg)}), and no rich-text paste consumer resolves those — every color and border would come out
 * empty. Rich-paste importers also vary in whether they honor a pasted {@code <style>} block at all (Word and
 * LibreOffice do; Gmail and Teams strip it), so the styling is stamped as <b>inline {@code style} attributes
 * per element</b> via a commonmark {@link AttributeProvider}. That is the one form every consumer applies,
 * and it is done at render time rather than by rewriting the HTML string afterwards.
 *
 * <p>Only what HTML semantics can't carry on its own is styled — a consumer that ignored every attribute here
 * would still get headings, bold, lists, tables and links right, because {@code <h1>}/{@code <strong>}/
 * {@code <ul>}/{@code <table>} already mean those things. The styles cover the rest: code backgrounds, table
 * borders, the blockquote rule, and heading/spacing rhythm. Colors are literal, light-theme values (pasted
 * content nearly always lands on a white page), matching the export sheet's GitHub-ish palette.
 *
 * <p>Pure and unit-tested apart from the math pre-pass, which rasterizes {@code $…$} spans through
 * {@link MarkdownHtmlExport#substituteMath} exactly as the file export does, so equations survive the paste
 * as inline PNG data URIs.
 */
public final class MarkdownClipboardHtml {

    private static final String FG = "#1f2328";
    private static final String FG_MUTED = "#59636e";
    private static final String BORDER = "#d1d9e0";
    private static final String BG_SUBTLE = "#f6f8fa";
    private static final String ACCENT = "#0969da";
    private static final String MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";

    /** Wraps the whole fragment; a paste target inherits the font/color from here. */
    private static final String ROOT_STYLE = "font-family:-apple-system,'Segoe UI',Inter,Helvetica,Arial,"
            + "sans-serif;font-size:16px;line-height:1.6;color:" + FG + ";";

    private MarkdownClipboardHtml() {}

    /**
     * Renders {@code markdown} to the clipboard HTML fragment — a single styled {@code <div>}, with no
     * doctype/{@code <html>}/{@code <head>} (a fragment is what the {@code text/html} flavor expects).
     */
    public static String toHtml(String markdown, boolean mathEnabled) {
        String src = markdown == null ? "" : markdown;
        if (mathEnabled) {
            src = MarkdownHtmlExport.substituteMath(src);
        }
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(MarkdownRenderer.EXTENSIONS)
                .attributeProviderFactory(ctx -> new InlineStyles())
                .build();
        String body = renderer.render(MarkdownRenderer.parseToDocument(src));
        return "<div style=\"" + ROOT_STYLE + "\">\n" + body + "</div>";
    }

    /**
     * Stamps a {@code style} attribute per node. Merges with (rather than replaces) whatever an extension
     * already put there — the task-list and footnote extensions both contribute attributes — and leaves a
     * node alone when there is nothing worth styling.
     */
    private static final class InlineStyles implements AttributeProvider {

        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            String style = styleFor(node, tagName);
            if (style == null) {
                return;
            }
            String existing = attributes.get("style");
            attributes.put("style", existing == null || existing.isBlank() ? style : existing + ";" + style);
        }

        private String styleFor(Node node, String tagName) {
            // Keyed off the emitted tag, not the node type, for two reasons: the GFM tables extension's node
            // types live in a separate package, and commonmark calls the provider TWICE for a code block —
            // once for the <pre> and once for the <code> inside it, both with the same FencedCodeBlock node.
            // Matching on the node there would stamp the block's background and padding on both, painting
            // them twice.
            switch (tagName) {
                case "table":
                    return "border-collapse:collapse;margin:0 0 16px;";
                case "th":
                    return "border:1px solid " + BORDER + ";padding:6px 13px;background:" + BG_SUBTLE
                            + ";font-weight:600;";
                case "td":
                    return "border:1px solid " + BORDER + ";padding:6px 13px;";
                case "pre":
                    return "margin:0 0 16px;padding:12px 16px;background:" + BG_SUBTLE + ";border-radius:6px;"
                            + "font-family:" + MONO + ";font-size:85%;line-height:1.45;overflow-x:auto;";
                case "code":
                    // Inline code is a pill; the <code> nested in a <pre> inherits the block's own styling.
                    return node instanceof Code
                            ? "padding:0.2em 0.4em;background:" + BG_SUBTLE + ";border-radius:6px;font-family:" + MONO
                                    + ";font-size:85%;"
                            : null;
                default:
                    break;
            }
            if (node instanceof Heading h) {
                return headingStyle(h.getLevel());
            }
            if (node instanceof Paragraph) {
                return "margin:0 0 16px;";
            }
            if (node instanceof BulletList || node instanceof OrderedList) {
                return "margin:0 0 16px;padding-left:2em;";
            }
            if (node instanceof ListItem) {
                return "margin:0 0 4px;";
            }
            if (node instanceof BlockQuote) {
                return "margin:0 0 16px;padding:0 1em;color:" + FG_MUTED + ";border-left:4px solid " + BORDER + ";";
            }
            if (node instanceof Link) {
                return "color:" + ACCENT + ";";
            }
            if (node instanceof Image) {
                return "max-width:100%;";
            }
            if (node instanceof ThematicBreak) {
                return "height:1px;margin:24px 0;background:" + BORDER + ";border:0;";
            }
            return null;
        }

        /** GitHub's heading scale: h1/h2 carry the underline rule, h4+ stop shrinking below body size. */
        private String headingStyle(int level) {
            String size =
                    switch (level) {
                        case 1 -> "2em";
                        case 2 -> "1.5em";
                        case 3 -> "1.25em";
                        case 4 -> "1em";
                        case 5 -> "0.875em";
                        default -> "0.85em";
                    };
            String base = "margin:24px 0 16px;font-weight:600;line-height:1.25;font-size:" + size + ";";
            if (level <= 2) {
                base += "padding-bottom:0.3em;border-bottom:1px solid " + BORDER + ";";
            }
            if (level >= 6) {
                base += "color:" + FG_MUTED + ";";
            }
            return base;
        }
    }
}
