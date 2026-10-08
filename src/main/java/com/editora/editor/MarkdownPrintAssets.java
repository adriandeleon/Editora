package com.editora.editor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.eclipse.tm4e.core.grammar.IGrammar;

/**
 * Everything a printed Markdown document needs that the live preview fills in <em>later</em>: its images,
 * its Mermaid diagrams and the syntax colours of its code fences.
 *
 * <p>The preview renders a placeholder and swaps the real thing in when a background load finishes, which
 * is right for a pane that is laid out again on every pulse. Print is laid out exactly once: a page is
 * measured, packed and handed to the printer in the same pulse, so anything that arrives afterwards either
 * never prints (highlighting) or grows after the page was packed around its placeholder (an image measures
 * 0px until it loads) and runs off the sheet. So print resolves all of it up front, off the FX thread
 * ({@link #resolve}), and {@link MarkdownRenderer#renderForPrint} builds the final nodes synchronously.
 */
public final class MarkdownPrintAssets {

    /**
     * The longest the whole document's images may take to arrive. Local files load in milliseconds; this
     * bounds a remote host that accepts the connection and then never answers, which would otherwise hold
     * "Preparing…" for as long as it liked. An image that misses it prints as its alt text.
     */
    static final long IMAGE_TIMEOUT_MS = 15_000;

    private final Map<String, PreviewImageLoader.Loaded> images;
    private final Map<String, MermaidImages.Cached> diagrams;
    private final Map<String, List<MarkdownRenderer.Run>> code;

    private MarkdownPrintAssets(
            Map<String, PreviewImageLoader.Loaded> images,
            Map<String, MermaidImages.Cached> diagrams,
            Map<String, List<MarkdownRenderer.Run>> code) {
        this.images = images;
        this.diagrams = diagrams;
        this.code = code;
    }

    /**
     * Loads every image, renders every Mermaid fence (light theme) and tokenises every code fence of
     * {@code ast}. Blocks on file, network and process I/O, so it must run <b>off</b> the FX thread.
     */
    public static MarkdownPrintAssets resolve(org.commonmark.node.Node ast, Path baseDir) {
        return resolve(ast, baseDir, IMAGE_TIMEOUT_MS);
    }

    static MarkdownPrintAssets resolve(org.commonmark.node.Node ast, Path baseDir, long imageTimeoutMs) {
        Set<String> urls = new LinkedHashSet<>();
        Set<String> mermaid = new LinkedHashSet<>();
        Map<String, List<MarkdownRenderer.Run>> code = new HashMap<>();
        if (ast != null) {
            ast.accept(new AbstractVisitor() {
                @Override
                public void visit(Image image) {
                    String url = MarkdownRenderer.resolveUrl(image.getDestination(), baseDir);
                    if (MarkdownRenderer.ImagePolicy.DOCUMENT.allows(url)) {
                        urls.add(url);
                    }
                }

                @Override
                public void visit(FencedCodeBlock fence) {
                    String source = MarkdownRenderer.stripTrailingNewline(fence.getLiteral());
                    if (MarkdownRenderer.isMermaidInfo(fence.getInfo()) && MermaidImages.isEnabled()) {
                        mermaid.add(source);
                        return;
                    }
                    String key = codeKey(fence.getInfo(), source);
                    if (code.containsKey(key)) {
                        return;
                    }
                    IGrammar grammar = source.isEmpty() || source.length() > MarkdownRenderer.MAX_HIGHLIGHT_CHARS
                            ? null
                            : MarkdownRenderer.grammarForInfo(fence.getInfo());
                    List<MarkdownRenderer.Run> runs =
                            grammar == null ? null : MarkdownRenderer.tokenizeRuns(source, grammar);
                    if (runs != null) {
                        code.put(key, runs);
                    }
                }
            });
        }
        return new MarkdownPrintAssets(
                PreviewImageLoader.loadAll(urls, imageTimeoutMs), MermaidImages.renderAllLight(mermaid), code);
    }

    /** The loaded image at {@code url} (already resolved to an absolute URL), or null when it did not load. */
    PreviewImageLoader.Loaded image(String url) {
        return url == null ? null : images.get(url);
    }

    /** The finished render of the Mermaid {@code source}, or null when it was never asked for. */
    MermaidImages.Cached diagram(String source) {
        return diagrams.get(source);
    }

    /** The tokenised runs of a fence, or null when it has no bundled grammar (it then prints plain). */
    List<MarkdownRenderer.Run> runs(String info, String source) {
        return code.get(codeKey(info, source));
    }

    private static String codeKey(String info, String source) {
        return (info == null ? "" : info.strip()) + '\n' + source;
    }
}
