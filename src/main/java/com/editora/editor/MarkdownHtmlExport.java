package com.editora.editor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Exports a Markdown document to a standalone HTML string: commonmark-java's {@link HtmlRenderer} over
 * the same extensions the preview uses (tables, strikethrough, task lists, autolinks, front matter,
 * footnotes, ins) plus heading anchors (for in-document links), wrapped in a self-contained page with an
 * embedded GitHub-style stylesheet ({@code markdown-export.css}, light and dark) and a Content-Security-Policy
 * that keeps the document's raw HTML from running script.
 *
 * <p>When math is enabled, {@code $…$}/{@code $$…$$} spans are rasterized (JLaTeXMath → PNG data URIs)
 * in a source pre-pass so they survive into the static HTML; fenced code lines are skipped. Mermaid
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
        String src =
                mathEnabled ? substituteMath(markdown == null ? "" : markdown) : (markdown == null ? "" : markdown);
        org.commonmark.node.Node doc = MarkdownRenderer.parseToDocument(src);
        // Raw HTML is kept — an author who writes <details> or <kbd> in a document expects it in the export —
        // but link and image destinations are sanitized: [x](javascript:…) / vbscript: / file: become an empty
        // href instead of a live script URL in a page the reader will open in a browser.
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(MarkdownRenderer.EXTENSIONS)
                .extensions(java.util.List.of(HeadingAnchorExtension.create()))
                .sanitizeUrls(true)
                .attributeProviderFactory(ctx -> new SemanticAttributes())
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
     * Replaces math spans with {@code <img>} data URIs, skipping fenced code blocks. Package-visible so the
     * clipboard fragment ({@link MarkdownClipboardHtml}) rasterizes equations the same way this export does.
     */
    static String substituteMath(String markdown) {
        return substituteMath(markdown, null);
    }

    /** As {@link #substituteMath(String)}, also recording every {@code <img>} tag it generated in
     *  {@code generated} (when non-null) — so a renderer that escapes raw HTML can let exactly these through. */
    static String substituteMath(String markdown, java.util.Collection<String> generated) {
        String[] lines = markdown.split("\n", -1);
        StringBuilder out = new StringBuilder();
        String fence = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String tok = fenceToken(line);
            boolean fenceLine = false;
            if (fence != null) {
                if (tok != null && tok.charAt(0) == fence.charAt(0) && tok.length() >= fence.length()) {
                    fence = null;
                }
                fenceLine = true;
            } else if (tok != null) {
                fence = tok;
                fenceLine = true;
            }
            if (fenceLine) {
                out.append(line);
            } else {
                appendLineWithMath(line, out, generated);
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static void appendLineWithMath(String line, StringBuilder out, java.util.Collection<String> generated) {
        for (MathSpans.Segment seg : MathSpans.segments(line)) {
            if (seg.span() == null) {
                out.append(seg.text());
            } else {
                String img = mathImg(seg.span().latex(), seg.span().display());
                out.append(img);
                if (generated != null) {
                    generated.add(img);
                }
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

    private static String fenceToken(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        if (indent > 3 || indent >= line.length()) {
            return null;
        }
        char c = line.charAt(indent);
        if (c != '`' && c != '~') {
            return null;
        }
        int n = 0;
        int p = indent;
        while (p < line.length() && line.charAt(p) == c) {
            p++;
            n++;
        }
        return n >= 3 ? line.substring(indent, indent + n) : null;
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
