package com.editora.editor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.editora.markdown.MarkdownMath;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Exports a Markdown document to a standalone HTML string: commonmark-java's {@link HtmlRenderer} over
 * the same extensions the preview uses (tables, strikethrough, task lists, autolinks, front matter,
 * footnotes, ins) plus heading anchors (for in-document links), wrapped in a self-contained page with an
 * embedded GitHub-style stylesheet ({@code markdown-export.css}, light and dark) and a Content-Security-Policy
 * that keeps the document's raw HTML from running script.
 *
 * <p>When math is enabled the parse carries math nodes ({@link MarkdownMath}, as in the preview) and each is
 * rasterized (JLaTeXMath → PNG data URI) so it survives into the static HTML. Mermaid
 * blocks render as code blocks (live Mermaid in exported HTML is deferred). The non-math path is pure
 * and unit-tested.
 */
public final class MarkdownHtmlExport {

    private MarkdownHtmlExport() {}

    /**
     * The exported page forbids script: the document's raw HTML is kept (an author's {@code <details>} or
     * {@code <kbd>} belongs in the export), and with it would come a {@code <script>} or an {@code onerror=}
     * from a README someone else wrote. The policy stops both from running in the reader's browser while
     * every other tag still renders.
     */
    static final String CONTENT_SECURITY_POLICY = "script-src 'none'; object-src 'none'; base-uri 'none'";

    /**
     * Renders {@code markdown} to a complete HTML document. Its title is the document's own — the front
     * matter's {@code title:}, else its first level-1 heading — and {@code title} (the file name) only when
     * it states none; its language is the front matter's {@code lang:}, and is left unstated otherwise
     * rather than claimed to be English.
     */
    public static String toHtml(String markdown, String title, boolean mathEnabled) {
        org.commonmark.node.Node doc = MarkdownRenderer.parseForRender(markdown, mathEnabled);
        // Raw HTML is kept — an author who writes <details> or <kbd> in a document expects it in the export —
        // but link and image destinations are sanitized: [x](javascript:…) / vbscript: / file: become an empty
        // href instead of a live script URL in a page the reader will open in a browser.
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(MarkdownRenderer.renderExtensions(mathEnabled))
                .extensions(java.util.List.of(HeadingAnchorExtension.create()))
                .sanitizeUrls(true)
                .attributeProviderFactory(ctx -> new SemanticAttributes())
                .nodeRendererFactory(ctx -> new MathImagesRenderer(ctx.getWriter()))
                .build();
        String body = renderer.render(doc);
        return page(MarkdownDocInfo.title(doc, title), MarkdownDocInfo.language(doc, null), body);
    }

    /**
     * The attributes HTML needs and commonmark does not write: the classes that let a stylesheet drop the
     * bullet in front of a task-list checkbox ({@code contains-task-list} / {@code task-list-item}, GitHub's
     * names), and a table cell's alignment as CSS instead of the obsolete {@code align} attribute.
     */
    static final class SemanticAttributes implements org.commonmark.renderer.html.AttributeProvider {
        @Override
        public void setAttributes(
                org.commonmark.node.Node node, String tagName, java.util.Map<String, String> attributes) {
            // sanitizeUrls also stamps rel="nofollow" on every link; an author's own document has no use
            // for that, so drop it again.
            attributes.remove("rel", "nofollow");
            String align = "td".equals(tagName) || "th".equals(tagName) ? attributes.remove("align") : null;
            if (align != null) {
                attributes.merge("style", "text-align:" + align, (old, add) -> old + ";" + add);
            }
            if (node instanceof org.commonmark.node.ListItem item && isTask(item)) {
                attributes.merge("class", "task-list-item", (old, add) -> old + " " + add);
            } else if (node instanceof org.commonmark.node.ListBlock) {
                for (org.commonmark.node.Node c = node.getFirstChild(); c != null; c = c.getNext()) {
                    if (c instanceof org.commonmark.node.ListItem item && isTask(item)) {
                        attributes.merge("class", "contains-task-list", (old, add) -> old + " " + add);
                        break;
                    }
                }
            }
        }

        private static boolean isTask(org.commonmark.node.ListItem item) {
            return item.getFirstChild() instanceof org.commonmark.ext.task.list.items.TaskListItemMarker;
        }
    }

    private static String page(String title, String lang, String body) {
        return "<!DOCTYPE html>\n<html" + (lang == null ? "" : " lang=\"" + escape(lang) + "\"")
                + ">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"" + CONTENT_SECURITY_POLICY + "\">\n"
                + "<meta name=\"color-scheme\" content=\"light dark\">\n"
                + "<meta name=\"generator\" content=\"Editora\">\n<title>"
                + escape(title)
                + "</title>\n<style>\n"
                + stylesheet()
                + "\n</style>\n</head>\n<body>\n<article class=\"markdown-body\">\n"
                + body
                + "</article>\n</body>\n</html>\n";
    }

    /**
     * Writes the parse's math nodes ({@link MarkdownMath}) as rasterized {@code <img>} data URIs, so an
     * equation survives into static HTML: a formula on its own (see {@link MarkdownDocInfo#blockFormula}) as
     * a centred block image, one in a line of text inline. The parser decided what is math — never code, and
     * a {@code $$} block over several lines is one formula — so this only draws it. Shared with the
     * clipboard fragment ({@link MarkdownClipboardHtml}).
     */
    static final class MathImagesRenderer implements org.commonmark.renderer.NodeRenderer {
        private final org.commonmark.renderer.html.HtmlWriter html;

        MathImagesRenderer(org.commonmark.renderer.html.HtmlWriter html) {
            this.html = html;
        }

        @Override
        public java.util.Set<Class<? extends org.commonmark.node.Node>> getNodeTypes() {
            return java.util.Set.of(MarkdownMath.Inline.class, MarkdownMath.Block.class);
        }

        @Override
        public void render(org.commonmark.node.Node node) {
            if (node instanceof MarkdownMath.Block block) {
                html.line();
                if (block.isClosed()) {
                    html.raw(mathImg(block.getLatex(), true));
                } else {
                    html.tag("p"); // never closed: not a formula yet — what was typed, as the preview shows it
                    html.text(MarkdownMath.source(block));
                    html.tag("/p");
                }
                html.line();
            } else if (node instanceof MarkdownMath.Inline inline) {
                // Alone in its paragraph a display formula is the block image; in a line it sits in the line.
                boolean block = MarkdownDocInfo.blockFormula(inline.getParent()) != null;
                html.raw(mathImg(inline.getLatex(), block));
            }
        }
    }

    private static String mathImg(String latex, boolean display) {
        byte[] png = MathImages.renderPng(latex, display, display ? 30f : 18f, false);
        if (png == null) {
            return "<span class=\"md-math-error\">" + escape((display ? "$$" : "$") + latex + (display ? "$$" : "$"))
                    + "</span>";
        }
        String data = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        String cls = display ? "md-math md-math-block" : "md-math md-math-inline";
        return "<img class=\"" + cls + "\" alt=\"" + escape(latex) + "\" src=\"" + data + "\">";
    }

    private static String stylesheet() {
        try (InputStream in = MarkdownHtmlExport.class.getResourceAsStream("/com/editora/styles/markdown-export.css")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // fall through to a minimal inline default
        }
        return "body{font-family:sans-serif;max-width:820px;margin:2rem auto;padding:0 1rem;}";
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
