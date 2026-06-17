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
 * embedded GitHub-style stylesheet ({@code markdown-export.css}).
 *
 * <p>When math is enabled, {@code $…$}/{@code $$…$$} spans are rasterized (JLaTeXMath → PNG data URIs)
 * in a source pre-pass so they survive into the static HTML; fenced code lines are skipped. Mermaid
 * blocks render as code blocks (live Mermaid in exported HTML is deferred). The non-math path is pure
 * and unit-tested.
 */
public final class MarkdownHtmlExport {

    private MarkdownHtmlExport() {}

    /** Renders {@code markdown} to a complete HTML document titled {@code title}. */
    public static String toHtml(String markdown, String title, boolean mathEnabled) {
        String src =
                mathEnabled ? substituteMath(markdown == null ? "" : markdown) : (markdown == null ? "" : markdown);
        org.commonmark.node.Node doc = MarkdownRenderer.parseToDocument(src);
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(MarkdownRenderer.EXTENSIONS)
                .extensions(java.util.List.of(HeadingAnchorExtension.create()))
                .build();
        String body = renderer.render(doc);
        return page(title, body);
    }

    private static String page(String title, String body) {
        return "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>"
                + escape(title)
                + "</title>\n<style>\n"
                + stylesheet()
                + "\n</style>\n</head>\n<body>\n<article class=\"markdown-body\">\n"
                + body
                + "</article>\n</body>\n</html>\n";
    }

    /** Replaces math spans with {@code <img>} data URIs, skipping fenced code blocks. */
    private static String substituteMath(String markdown) {
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
                appendLineWithMath(line, out);
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static void appendLineWithMath(String line, StringBuilder out) {
        for (MathSpans.Segment seg : MathSpans.segments(line)) {
            if (seg.span() == null) {
                out.append(seg.text());
            } else {
                out.append(mathImg(seg.span().latex(), seg.span().display()));
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
