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
 * <p>Pure and unit-tested apart from math, which is rasterized by the file export's own
 * {@link MarkdownHtmlExport.MathImagesRenderer}, so equations survive the paste as inline PNG data URIs.
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

    /** Largest local picture inlined into the fragment; a bigger one is referenced by its absolute file URI. */
    static final int MAX_INLINE_IMAGE_BYTES = 4 * 1024 * 1024;

    /**
     * Renders {@code markdown} to the clipboard HTML fragment — a single styled {@code <div>}, with no
     * doctype/{@code <html>}/{@code <head>} (a fragment is what the {@code text/html} flavor expects).
     */
    public static String toHtml(String markdown, boolean mathEnabled) {
        return toHtml(markdown, mathEnabled, null);
    }

    /**
     * As {@link #toHtml(String, boolean)} for the document saved as {@code documentFile} (null: unsaved): a
     * picture it names by a relative path travels with the fragment (as a {@code data:} URI) instead of as a
     * path that means nothing wherever the fragment is pasted.
     */
    public static String toHtml(String markdown, boolean mathEnabled, java.nio.file.Path documentFile) {
        return toHtmlIn(
                markdown,
                mathEnabled,
                documentFile == null ? null : documentFile.toAbsolutePath().getParent());
    }

    /** {@link #toHtml(String, boolean, java.nio.file.Path)} for Markdown of a document in {@code baseDir}. */
    static String toHtmlIn(String markdown, boolean mathEnabled, java.nio.file.Path baseDir) {
        return "<div style=\"" + ROOT_STYLE + "\">\n" + render(markdown, mathEnabled, baseDir, true) + "</div>";
    }

    /**
     * The same document as plain HTML <em>markup</em>, for pasting into an HTML file: semantic tags only — no
     * inline styles, and the task-list classes and cell alignment the file export writes. Like the rich
     * fragment it carries no raw HTML from the document and no script-capable URL.
     */
    public static String toMarkup(String markdown, boolean mathEnabled) {
        return render(markdown, mathEnabled, null, false);
    }

    private static String render(String markdown, boolean mathEnabled, java.nio.file.Path baseDir, boolean styled) {
        // What lands on the clipboard is pasted into mail clients, chat and word processors, which render it
        // with their own privileges — and the Markdown may be someone else's file. So the fragment carries no
        // raw HTML from the document (it shows as text, like the preview shows it) and no script-capable URL
        // ([x](javascript:…) gets an empty href). Equations are math nodes of the parse, drawn as inline
        // data: PNGs by our own renderer — nothing the document wrote is let through as markup.
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(MarkdownRenderer.renderExtensions(mathEnabled))
                .attributeProviderFactory(
                        ctx -> styled ? new InlineStyles(baseDir) : new MarkdownHtmlExport.SemanticAttributes())
                .escapeHtml(true)
                .sanitizeUrls(true)
                .nodeRendererFactory(ctx -> new MarkdownHtmlExport.MathImagesRenderer(ctx.getWriter()))
                .build();
        return renderer.render(MarkdownRenderer.parseForRender(markdown, mathEnabled));
    }

    /**
     * The {@code src} a pasted picture can still be found by: a web or {@code data:} address as it is; a
     * local file's bytes as a {@code data:} URI (an SVG rasterized, as the word processors that receive the
     * paste cannot show one), or its absolute {@code file:} URI when it is too large to inline. Null: leave
     * the source as written (nothing to resolve it against, or no such file).
     */
    static String portableImageSrc(String src, java.nio.file.Path baseDir) {
        if (src == null || src.isBlank() || baseDir == null || src.matches("(?i)^[a-z][a-z0-9+.-]+:.*")) {
            return null;
        }
        try {
            String path = src.strip();
            int cut = path.indexOf('#') >= 0 ? path.indexOf('#') : path.indexOf('?');
            java.nio.file.Path file = baseDir.resolve(java.net.URLDecoder.decode(
                            (cut < 0 ? path : path.substring(0, cut)).replace("+", "%2B"),
                            java.nio.charset.StandardCharsets.UTF_8))
                    .normalize();
            if (!java.nio.file.Files.isRegularFile(file)) {
                return null;
            }
            if (java.nio.file.Files.size(file) > MAX_INLINE_IMAGE_BYTES) {
                return file.toUri().toString();
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(file);
            if (PreviewImageLoader.looksLikeSvg(bytes)) {
                byte[] png = PreviewImageLoader.svgToPng(bytes);
                if (png == null) {
                    return file.toUri().toString();
                }
                bytes = png;
            }
            return "data:" + mediaType(bytes) + ";base64,"
                    + java.util.Base64.getEncoder().encodeToString(bytes);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    private static String mediaType(byte[] b) {
        if (b.length > 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (b.length > 2 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return "image/gif";
        }
        if (b.length > 11 && b[0] == 'R' && b[1] == 'I' && b[8] == 'W' && b[9] == 'E') {
            return "image/webp";
        }
        return b.length > 1 && b[0] == 'B' && b[1] == 'M' ? "image/bmp" : "image/png";
    }

    /**
     * Stamps a {@code style} attribute per node. Merges with (rather than replaces) whatever an extension
     * already put there — the task-list and footnote extensions both contribute attributes — and leaves a
     * node alone when there is nothing worth styling.
     */
    private static final class InlineStyles implements AttributeProvider {

        private final java.nio.file.Path baseDir;

        InlineStyles(java.nio.file.Path baseDir) {
            this.baseDir = baseDir;
        }

        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            attributes.remove("rel", "nofollow"); // a side effect of sanitizeUrls; meaningless in a paste
            if (node instanceof Image image && "img".equals(tagName)) {
                String portable = portableImageSrc(image.getDestination(), baseDir);
                if (portable != null) {
                    attributes.put("src", portable);
                }
            }
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
