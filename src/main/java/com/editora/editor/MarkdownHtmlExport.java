package com.editora.editor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import com.editora.editor.highlight.TextMateHighlighter;
import com.editora.markdown.MarkdownMath;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jspecify.annotations.Nullable;

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
        return toHtml(markdown, title, mathEnabled, Options.PLAIN);
    }

    /**
     * What a page is exported with beyond its text.
     *
     * @param highlightCode colour fenced code by its language, as inline styles (the page allows no script)
     * @param embedImages write each image the document shows into the page as a {@code data:} URI, so the
     *     file can be sent on its own
     * @param baseDir the document's folder, for its relative image paths; null for an unsaved document
     * @param mmdc the Mermaid CLI command, or null: a {@code mermaid} fence is then left as its source
     * @param painter turns highlighted code into HTML; injected because the painter lives with the other
     *     light-palette exports ({@code pdf/CodeHtml}), which this package must not reference. Null: no colour
     */
    public record Options(
            boolean highlightCode,
            boolean embedImages,
            java.nio.file.@Nullable Path baseDir,
            @Nullable List<String> mmdc,
            @Nullable CodePainter painter) {
        public static final Options PLAIN = new Options(false, false, null, null, null);
    }

    /** Highlighted code as a self-contained {@code <pre>} fragment (text, its style spans, tab size). */
    @FunctionalInterface
    public interface CodePainter {
        String toHtml(
                String text,
                org.fxmisc.richtext.model.@Nullable StyleSpans<java.util.Collection<String>> spans,
                int tabSize);

        /** Extra page CSS the fragments need (token classes for the light and the dark scheme); "" for none. */
        default String css() {
            return "";
        }
    }

    /** A painter from its two halves: the fragment and the stylesheet that colours its classes. */
    public static CodePainter painter(CodePainter fragment, java.util.function.Supplier<String> css) {
        return new CodePainter() {
            @Override
            public String toHtml(
                    String text,
                    org.fxmisc.richtext.model.@Nullable StyleSpans<java.util.Collection<String>> spans,
                    int tabSize) {
                return fragment.toHtml(text, spans, tabSize);
            }

            @Override
            public String css() {
                return css.get();
            }
        };
    }

    /**
     * Whether exporting {@code markdown} with {@code options} may read image files or run the Mermaid CLI —
     * the two things that should not happen on the FX thread. A cheap look at the source, erring on "yes".
     */
    public static boolean readsFilesOrRunsTools(String markdown, Options options) {
        if (markdown == null || options == null) {
            return false;
        }
        boolean images = options.embedImages() && (markdown.contains("![") || markdown.contains("<img"));
        boolean diagrams = options.mmdc() != null
                && !options.mmdc().isEmpty()
                && markdown.toLowerCase(java.util.Locale.ROOT).contains("mermaid");
        return images || diagrams;
    }

    /**
     * For {@link com.editora.markdown.MarkdownExportTree#lower(org.commonmark.node.Node,
     * java.util.function.UnaryOperator)}: the path a link written in a document of {@code folder} carries
     * for a wiki target — relative, forward slashes, spaces escaped — or null when it does not resolve
     * ({@link WikiLinkTargets}, the resolver every surface asks).
     */
    public static java.util.function.UnaryOperator<@Nullable String> wikiPaths(java.nio.file.@Nullable Path folder) {
        return target -> {
            if (target == null) {
                return null; // never asked for: the operator's type also lets a null in
            }
            java.nio.file.Path file = WikiLinkTargets.resolve(folder, target);

            if (file == null || folder == null) {
                return null;
            }
            try {
                return folder.toAbsolutePath()
                        .normalize()
                        .relativize(file.toAbsolutePath().normalize())
                        .toString()
                        .replace('\\', '/')
                        .replace(" ", "%20");
            } catch (IllegalArgumentException e) {
                return null;
            }
        };
    }

    private static void lowerWikiLinksOnly(org.commonmark.node.Node doc, java.nio.file.@Nullable Path folder) {
        com.editora.markdown.MarkdownExportTree.wikiLinksToLinks(doc, wikiPaths(folder));
    }

    /** Images bigger than this are linked, not embedded: a page is not the place for a 50 MB scan. */
    static final int MAX_EMBED_BYTES = 8 * 1024 * 1024;

    /** A fenced block longer than this is written plain; colouring it would cost more than it shows. */
    static final int MAX_HIGHLIGHT_CHARS = 200_000;

    /**
     * As {@link #toHtml(String, String, boolean)} with {@code options}. Reads image files and may run the
     * Mermaid CLI: call it off the FX thread.
     */
    public static String toHtml(String markdown, String title, boolean mathEnabled, Options options) {
        org.commonmark.node.Node doc = MarkdownRenderer.parseForRender(markdown, mathEnabled);
        Options o = options == null ? Options.PLAIN : options;
        // Wiki links leave the page as ordinary links to the files they resolve to (not the raw note name).
        lowerWikiLinksOnly(doc, o.baseDir());
        if (o.embedImages()) {
            embedImages(doc, o.baseDir());
        }
        // Raw HTML is kept — an author who writes <details> or <kbd> in a document expects it in the export —
        // but link and image destinations are sanitized: [x](javascript:…) / vbscript: / file: become an empty
        // href instead of a live script URL in a page the reader will open in a browser.
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(MarkdownRenderer.renderExtensions(mathEnabled))
                .extensions(List.of(HeadingAnchorExtension.create()))
                .sanitizeUrls(true)
                .urlSanitizer(new EmbeddedImageUrls())
                .attributeProviderFactory(ctx -> new SemanticAttributes())
                .nodeRendererFactory(ctx -> new MathImagesRenderer(ctx.getWriter()))
                .nodeRendererFactory(ctx -> new CodeBlocks(ctx.getWriter(), o))
                .build();
        String body = renderer.render(doc);
        // The token classes' rules go in only when a block was actually coloured.
        String extraCss = o.painter() != null && body.contains("class=\"md-highlight\"")
                ? o.painter().css()
                : "";
        return page(MarkdownDocInfo.title(doc, title), MarkdownDocInfo.language(doc, null), body, extraCss);
    }

    /** Rewrites every image the document can load into a {@code data:} URI; the others keep their address. */
    private static void embedImages(org.commonmark.node.Node node, java.nio.file.@Nullable Path baseDir) {
        for (org.commonmark.node.Node c = node.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof org.commonmark.node.Image image) {
                String dest = image.getDestination();
                if (dest != null && !dest.startsWith("data:")) {
                    byte[] bytes = PreviewImageLoader.fetchForExport(dest, baseDir);
                    if (bytes != null && bytes.length > 0 && bytes.length <= MAX_EMBED_BYTES) {
                        image.setDestination("data:" + mediaType(bytes, dest) + ";base64,"
                                + Base64.getEncoder().encodeToString(bytes));
                    }
                }
            } else {
                embedImages(c, baseDir);
            }
        }
    }

    /** The image's media type, from its first bytes (and, for the text format SVG, from what it holds). */
    static String mediaType(byte[] b, String name) {
        if (b.length > 3 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length > 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8) {
            return "image/jpeg";
        }
        if (b.length > 3 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return "image/gif";
        }
        if (b.length > 11 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[8] == 'W' && b[9] == 'E') {
            return "image/webp";
        }
        if (b.length > 1 && b[0] == 'B' && b[1] == 'M') {
            return "image/bmp";
        }
        String head = new String(b, 0, Math.min(b.length, 512), StandardCharsets.UTF_8);
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return head.contains("<svg") || lower.endsWith(".svg") ? "image/svg+xml" : "image/png";
    }

    /**
     * The sanitizer of the exported page: commonmark's own, except for {@code data:} URIs, which it lets
     * through whole — here a link never keeps one, and an image only a {@code data:image/…} one (what this
     * class writes when it embeds a picture; an {@code <img>} runs no script, whatever it is given).
     */
    static final class EmbeddedImageUrls implements org.commonmark.renderer.html.UrlSanitizer {
        private final org.commonmark.renderer.html.UrlSanitizer standard =
                new org.commonmark.renderer.html.DefaultUrlSanitizer();

        @Override
        public String sanitizeLinkUrl(String url) {
            return isData(url) ? "" : standard.sanitizeLinkUrl(url); // a data: page is not a link to follow
        }

        private static boolean isData(String url) {
            return url != null && url.stripLeading().regionMatches(true, 0, "data:", 0, 5);
        }

        @Override
        public String sanitizeImageUrl(String url) {
            if (isData(url)) {
                return url.stripLeading().regionMatches(true, 0, "data:image/", 0, 11) ? url : "";
            }
            return standard.sanitizeImageUrl(url);
        }
    }

    /**
     * Fenced code in the exported page: a {@code mermaid} block as its diagram when the CLI is there, any
     * other block coloured by its language when asked to, and otherwise exactly what commonmark writes.
     */
    static final class CodeBlocks implements org.commonmark.renderer.NodeRenderer {
        private final org.commonmark.renderer.html.HtmlWriter html;
        private final Options options;

        CodeBlocks(org.commonmark.renderer.html.HtmlWriter html, Options options) {
            this.html = html;
            this.options = options;
        }

        @Override
        public java.util.Set<Class<? extends org.commonmark.node.Node>> getNodeTypes() {
            return java.util.Set.of(org.commonmark.node.FencedCodeBlock.class);
        }

        @Override
        public void render(org.commonmark.node.Node node) {
            org.commonmark.node.FencedCodeBlock block = (org.commonmark.node.FencedCodeBlock) node;
            String info = block.getInfo() == null ? "" : block.getInfo().strip();
            String code = block.getLiteral() == null ? "" : block.getLiteral();
            html.line();
            String drawn = null;
            if (MarkdownRenderer.isMermaidInfo(info)) {
                drawn = mermaid(code);
            } else if (options.highlightCode() && options.painter() != null) {
                drawn = highlighted(code, info, options.painter());
            }
            if (drawn != null) {
                html.raw(drawn);
            } else {
                String language = info.isEmpty() ? "" : info.split("\\s+", 2)[0];
                html.tag("pre");
                html.tag(
                        "code",
                        language.isEmpty() ? java.util.Map.of() : java.util.Map.of("class", "language-" + language));
                html.text(code);
                html.tag("/code");
                html.tag("/pre");
            }
            html.line();
        }

        private @Nullable String mermaid(String source) {
            if (options.mmdc() == null || options.mmdc().isEmpty()) {
                return null;
            }
            try {
                com.editora.mermaid.Mermaid.Render r =
                        com.editora.mermaid.Mermaid.renderPng(options.mmdc(), source, false);
                if (!r.ok()) {
                    return null;
                }
                return "<p><img class=\"md-mermaid\" alt=\"" + escape(firstLine(source))
                        + "\" src=\"data:image/png;base64,"
                        + Base64.getEncoder().encodeToString(r.image()) + "\"></p>";
            } catch (RuntimeException e) {
                return null; // the diagram is a nicety: its source is still exported
            }
        }

        private static String firstLine(String source) {
            String s = source.strip();
            int nl = s.indexOf('\n');
            return nl < 0 ? s : s.substring(0, nl);
        }

        private static @Nullable String highlighted(String code, String info, CodePainter painter) {
            if (info.isEmpty() || code.isEmpty() || code.length() > MAX_HIGHLIGHT_CHARS) {
                return null;
            }
            try {
                org.eclipse.tm4e.core.grammar.IGrammar grammar = MarkdownRenderer.grammarForInfo(info);
                if (grammar == null) {
                    return null;
                }
                String body = code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
                String html = painter.toHtml(body, TextMateHighlighter.compute(body, grammar), 4);
                return html.startsWith("<pre class=")
                        ? html
                        : html.replaceFirst("^<pre ", "<pre class=\"md-highlight\" ");
            } catch (RuntimeException | LinkageError e) {
                return null; // colour is a nicety: the block is still exported, plain
            }
        }
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

    private static String page(@Nullable String title, @Nullable String lang, String body, String extraCss) {
        return "<!DOCTYPE html>\n<html" + (lang == null ? "" : " lang=\"" + escape(lang) + "\"")
                + ">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"" + CONTENT_SECURITY_POLICY + "\">\n"
                + "<meta name=\"color-scheme\" content=\"light dark\">\n"
                + "<meta name=\"generator\" content=\"Editora\">\n<title>"
                + escape(title)
                + "</title>\n<style>\n"
                + stylesheet()
                + (extraCss.isEmpty() ? "" : "\n" + extraCss)
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

    private static String escape(@Nullable String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
