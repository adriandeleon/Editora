package com.editora.markdown;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.commonmark.Extension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.commonmark.renderer.text.TextContentRenderer;

/**
 * GitHub alerts: a block quote whose first line is {@code [!NOTE]}, {@code [!TIP]}, {@code [!IMPORTANT]},
 * {@code [!WARNING]} or {@code [!CAUTION]} becomes an {@link Alert} node of that {@link Kind}, holding the
 * rest of the quote.
 *
 * <pre>
 * &gt; [!WARNING]
 * &gt; Back up first.
 * </pre>
 *
 * <p>As on GitHub the marker must be the whole first line of the quote (any case), and the quote must say
 * something after it; anything else stays the block quote it is. Done on the parsed tree (a post-processor),
 * so the quote's own parsing — lazy lines, nested blocks — is CommonMark's. Pure.
 *
 * <p>The extension also renders the node for {@code HtmlRenderer} (GitHub's markup:
 * {@code <div class="markdown-alert markdown-alert-warning">} with a {@code markdown-alert-title} paragraph)
 * and for {@code TextContentRenderer} (the title, then the content).
 */
public final class MarkdownAlerts {

    private MarkdownAlerts() {}

    /** The five alert types, in GitHub's order. */
    public enum Kind {
        NOTE,
        TIP,
        IMPORTANT,
        WARNING,
        CAUTION;

        /** The English title GitHub shows ("Note", "Warning"); a UI localises by {@link #name()} instead. */
        public String title() {
            return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
        }

        /** The lower-case name, as in the CSS class {@code markdown-alert-<slug>}. */
        public String slug() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** An alert: its children are the blocks of the quote after the marker line. */
    public static final class Alert extends CustomBlock {
        private final Kind kind;

        public Alert(Kind kind) {
            this.kind = kind;
        }

        public Kind getKind() {
            return kind;
        }
    }

    public static Extension extension() {
        return new AlertsExtension();
    }

    /** The kind a quote's first line names, or null when the line is not exactly an alert marker. Pure. */
    public static Kind marker(String firstLine) {
        if (firstLine == null) {
            return null;
        }
        String s = firstLine.strip();
        if (s.length() < 5 || !s.startsWith("[!") || !s.endsWith("]")) {
            return null;
        }
        String name = s.substring(2, s.length() - 1).toUpperCase(Locale.ROOT);
        for (Kind kind : Kind.values()) {
            if (kind.name().equals(name)) {
                return kind;
            }
        }
        return null;
    }

    /** Replaces every qualifying block quote under {@code document} by an {@link Alert}. */
    static Node process(Node document) {
        List<BlockQuote> quotes = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(BlockQuote quote) {
                quotes.add(quote);
                visitChildren(quote);
            }
        });
        for (BlockQuote quote : quotes) {
            convert(quote);
        }
        return document;
    }

    private static void convert(BlockQuote quote) {
        if (!(quote.getFirstChild() instanceof Paragraph first) || !(first.getFirstChild() instanceof Text text)) {
            return;
        }
        Kind kind = marker(text.getLiteral());
        Node after = text.getNext();
        boolean lineEnds = after == null || after instanceof SoftLineBreak || after instanceof HardLineBreak;
        boolean hasContent = (after != null && after.getNext() != null) || first.getNext() != null;
        if (kind == null || !lineEnds || !hasContent) {
            return;
        }
        text.unlink();
        if (after != null) {
            after.unlink();
        }
        if (first.getFirstChild() == null) {
            first.unlink(); // the marker was the whole paragraph
        }
        Alert alert = new Alert(kind);
        alert.setSourceSpans(quote.getSourceSpans());
        for (Node child = quote.getFirstChild(); child != null; ) {
            Node next = child.getNext();
            alert.appendChild(child);
            child = next;
        }
        quote.insertAfter(alert);
        quote.unlink();
    }

    private static final class AlertsExtension
            implements Parser.ParserExtension,
                    HtmlRenderer.HtmlRendererExtension,
                    TextContentRenderer.TextContentRendererExtension {
        @Override
        public void extend(Parser.Builder builder) {
            builder.postProcessor(MarkdownAlerts::process);
        }

        @Override
        public void extend(HtmlRenderer.Builder builder) {
            builder.nodeRendererFactory(context -> new NodeRenderer() {
                @Override
                public Set<Class<? extends Node>> getNodeTypes() {
                    return Set.of(Alert.class);
                }

                @Override
                public void render(Node node) {
                    Alert alert = (Alert) node;
                    HtmlWriter html = context.getWriter();
                    Map<String, String> attrs = new LinkedHashMap<>();
                    attrs.put(
                            "class",
                            "markdown-alert markdown-alert-" + alert.getKind().slug());
                    html.line();
                    html.tag("div", context.extendAttributes(node, "div", attrs));
                    html.line();
                    html.tag("p", Map.of("class", "markdown-alert-title"));
                    html.text(alert.getKind().title());
                    html.tag("/p");
                    html.line();
                    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                        context.render(child);
                    }
                    html.tag("/div");
                    html.line();
                }
            });
        }

        @Override
        public void extend(TextContentRenderer.Builder builder) {
            builder.nodeRendererFactory(context -> new NodeRenderer() {
                @Override
                public Set<Class<? extends Node>> getNodeTypes() {
                    return Set.of(Alert.class);
                }

                @Override
                public void render(Node node) {
                    context.getWriter().write(((Alert) node).getKind().title());
                    context.getWriter().colon();
                    context.getWriter().block();
                    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                        context.render(child);
                    }
                }
            });
        }
    }
}
