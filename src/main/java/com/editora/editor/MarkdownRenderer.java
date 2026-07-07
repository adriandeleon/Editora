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
        VBox content = new VBox();
        content.getStyleClass().add("markdown-preview");
        // Cap the readable column width so long lines don't stretch across a wide window (GitHub-style).
        content.setMaxWidth(MAX_CONTENT_WIDTH);
        if (ast != null) {
            appendBlocks(ast, content, new RenderContext(baseDir, onLinkClick));
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
    private record RenderContext(Path baseDir, java.util.function.Consumer<String> onLinkClick) {}

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
                    StackPane wrap = new StackPane(MathImages.blockNode(disp, DISPLAY_MATH_SIZE));
                    wrap.getStyleClass().add("md-math-block-wrap");
                    return wrap;
                }
            }
            // A paragraph that is just an image renders as a block image (not squeezed into a TextFlow).
            if (p.getFirstChild() instanceof org.commonmark.node.Image img && img.getNext() == null) {
                return imageNode(img, ctx.baseDir());
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
                // Show at natural size, but never wider than the reading column.
                return MermaidImages.node(stripTrailingNewline(f.getLiteral()), lw -> Math.min(lw, MAX_CONTENT_WIDTH));
            }
            return highlightedCodeBlock(f.getLiteral(), f.getInfo());
        }
        if (node instanceof IndentedCodeBlock i) {
            return codeBlock(i.getLiteral()); // indented blocks carry no language → plain
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
            return codeBlock(hb.getLiteral()); // other raw HTML shown as text (no interpretation)
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
            clamped[i] = Math.min(Math.max(colWeights.get(i), MIN_COL_WEIGHT), MAX_COL_WEIGHT);
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

    private static Node codeBlock(String literal) {
        Label label = new Label(stripTrailingNewline(literal));
        label.getStyleClass().add("md-code-block");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    /** Above this many characters a fenced block renders as plain text (avoids tokenizing a huge block). */
    private static final int MAX_HIGHLIGHT_CHARS = 50_000;

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
    static IGrammar grammarForInfo(String info) {
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
    private static boolean isMermaidInfo(String info) {
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
                appendTextWithMath(t.getLiteral(), flow, styles);
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
            flow.getChildren().add(imageNode(img, ctx.baseDir()));
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
    private static void appendTextWithMath(String literal, TextFlow flow, List<String> styles) {
        for (MathSpans.Segment seg : MathSpans.segments(literal)) {
            if (seg.span() == null) {
                if (!seg.text().isEmpty()) {
                    flow.getChildren().add(styledText(seg.text(), styles));
                }
            } else {
                flow.getChildren().add(MathImages.inlineNode(seg.span().latex(), INLINE_MATH_SIZE));
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

    private static Node imageNode(org.commonmark.node.Image img, Path baseDir) {
        String alt = imageAlt(img);
        String url = resolveUrl(img.getDestination(), baseDir);
        if (url == null) {
            return inlineCode(alt.isBlank() ? "[image]" : "[image: " + alt + "]");
        }
        ImageView view = new ImageView();
        view.getStyleClass().add("md-image");
        view.setPreserveRatio(true);
        // Loads off the FX thread and rasterizes SVG (e.g. badges) that JavaFX's own decoder can't read;
        // sizes the view to the image's logical width, capped to the pane.
        PreviewImageLoader.loadInto(view, url, MAX_IMAGE_WIDTH);
        String tip = img.getTitle() != null && !img.getTitle().isBlank() ? img.getTitle() : alt;
        if (tip != null && !tip.isBlank()) {
            Tooltip.install(view, new Tooltip(tip));
        }
        return view;
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

    private static String resolveUrl(String dest, Path baseDir) {
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

    private static String stripTrailingNewline(String s) {
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
