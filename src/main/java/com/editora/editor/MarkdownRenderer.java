package com.editora.editor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
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

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

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

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    TaskListItemsExtension.create(),
                    AutolinkExtension.create()))
            .build();

    private MarkdownRenderer() {
    }

    /** Parses Markdown to a CommonMark AST. Pure CPU — safe to call off the FX thread. */
    public static org.commonmark.node.Node parseToDocument(String markdown) {
        return PARSER.parse(markdown == null ? "" : markdown);
    }

    /** Builds a JavaFX node tree from a parsed AST. MUST run on the FX thread (creates Text/ImageView). */
    public static Node renderDocument(org.commonmark.node.Node ast, Path baseDir) {
        VBox content = new VBox();
        content.getStyleClass().add("markdown-preview");
        // Cap the readable column width so long lines don't stretch across a wide window (GitHub-style).
        content.setMaxWidth(MAX_CONTENT_WIDTH);
        if (ast != null) {
            appendBlocks(ast, content, baseDir);
        }
        // Center the capped-width column within the (fit-to-width) preview pane. A StackPane clamps the
        // content to the available width when the viewport is narrower than the cap, so it never overflows.
        StackPane wrap = new StackPane(content);
        wrap.getStyleClass().add("markdown-preview-wrap");
        StackPane.setAlignment(content, Pos.TOP_CENTER);
        return wrap;
    }

    // --- block level ---------------------------------------------------------------------------

    private static void appendBlocks(org.commonmark.node.Node parent, Pane container, Path baseDir) {
        for (org.commonmark.node.Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            Node fx = renderBlock(n, baseDir);
            if (fx != null) {
                container.getChildren().add(fx);
            }
        }
    }

    private static Node renderBlock(org.commonmark.node.Node node, Path baseDir) {
        if (node instanceof Heading h) {
            TextFlow tf = inlineFlow(h, baseDir);
            tf.getStyleClass().add("md-h" + Math.min(6, Math.max(1, h.getLevel())));
            return tf;
        }
        if (node instanceof Paragraph p) {
            // A paragraph that is just an image renders as a block image (not squeezed into a TextFlow).
            if (p.getFirstChild() instanceof org.commonmark.node.Image img && img.getNext() == null) {
                return imageNode(img, baseDir);
            }
            TextFlow tf = inlineFlow(p, baseDir);
            tf.getStyleClass().add("md-paragraph");
            return tf;
        }
        if (node instanceof BlockQuote) {
            VBox box = new VBox();
            box.getStyleClass().add("md-quote");
            appendBlocks(node, box, baseDir);
            return box;
        }
        if (node instanceof BulletList bl) {
            return renderList(bl, baseDir, false, 1);
        }
        if (node instanceof OrderedList ol) {
            Integer start = ol.getMarkerStartNumber();
            return renderList(ol, baseDir, true, start == null ? 1 : start);
        }
        if (node instanceof FencedCodeBlock f) {
            return codeBlock(f.getLiteral());
        }
        if (node instanceof IndentedCodeBlock i) {
            return codeBlock(i.getLiteral());
        }
        if (node instanceof ThematicBreak) {
            Separator s = new Separator();
            s.getStyleClass().add("md-hr");
            return s;
        }
        if (node instanceof HtmlBlock hb) {
            return codeBlock(hb.getLiteral()); // raw HTML shown as text (no interpretation)
        }
        if (node instanceof TableBlock tb) {
            return renderTable(tb, baseDir);
        }
        // Unknown block container: render its children.
        VBox box = new VBox();
        appendBlocks(node, box, baseDir);
        return box.getChildren().isEmpty() ? null : box;
    }

    private static Node renderList(org.commonmark.node.Node list, Path baseDir, boolean ordered, int start) {
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
            appendBlocks(item, content, baseDir);
            row.getChildren().addAll(marker, content);
            box.getChildren().add(row);
            n++;
        }
        return box;
    }

    private static Node renderTable(TableBlock tb, Path baseDir) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("md-table");
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
                    TextFlow tf = inlineFlow(cell, baseDir);
                    tf.getStyleClass().add(header ? "md-table-header" : "md-table-cell");
                    if (cell.getAlignment() == TableCell.Alignment.CENTER) {
                        tf.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
                    } else if (cell.getAlignment() == TableCell.Alignment.RIGHT) {
                        tf.setTextAlignment(javafx.scene.text.TextAlignment.RIGHT);
                    }
                    grid.add(tf, col, row);
                    col++;
                }
                row++;
            }
        }
        return grid;
    }

    private static Node codeBlock(String literal) {
        Label label = new Label(stripTrailingNewline(literal));
        label.getStyleClass().add("md-code-block");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    // --- inline level --------------------------------------------------------------------------

    private static TextFlow inlineFlow(org.commonmark.node.Node block, Path baseDir) {
        TextFlow flow = new TextFlow();
        appendInline(block, flow, List.of(), baseDir);
        return flow;
    }

    private static void appendInline(org.commonmark.node.Node parent, TextFlow flow,
            List<String> styles, Path baseDir) {
        for (org.commonmark.node.Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            emitInline(n, flow, styles, baseDir);
        }
    }

    private static void emitInline(org.commonmark.node.Node n, TextFlow flow,
            List<String> styles, Path baseDir) {
        if (n instanceof org.commonmark.node.Text t) {
            flow.getChildren().add(styledText(t.getLiteral(), styles));
        } else if (n instanceof Code c) {
            flow.getChildren().add(inlineCode(c.getLiteral()));
        } else if (n instanceof Emphasis) {
            appendInline(n, flow, with(styles, "md-italic"), baseDir);
        } else if (n instanceof StrongEmphasis) {
            appendInline(n, flow, with(styles, "md-bold"), baseDir);
        } else if (n instanceof Strikethrough) {
            appendInline(n, flow, with(styles, "md-strike"), baseDir);
        } else if (n instanceof Link link) {
            int from = flow.getChildren().size();
            appendInline(n, flow, with(styles, "md-link"), baseDir);
            installLinkTooltip(flow, from, link.getDestination());
        } else if (n instanceof org.commonmark.node.Image img) {
            flow.getChildren().add(imageNode(img, baseDir));
        } else if (n instanceof SoftLineBreak) {
            flow.getChildren().add(new Text(" "));
        } else if (n instanceof HardLineBreak) {
            flow.getChildren().add(new Text("\n"));
        } else if (n instanceof HtmlInline h) {
            flow.getChildren().add(inlineCode(h.getLiteral()));
        } else if (n instanceof TaskListItemMarker) {
            // rendered as a CheckBox by renderList — skip here
        } else {
            appendInline(n, flow, styles, baseDir); // unknown inline: descend
        }
    }

    /** Inline code as a {@code Label} so it can carry a GitHub-style rounded gray background (a
     *  {@link Text} can only fill its glyphs). Flows inline inside the surrounding {@link TextFlow}. */
    private static Label inlineCode(String literal) {
        Label code = new Label(literal);
        code.getStyleClass().add("md-inline-code");
        return code;
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

    private static Node imageNode(org.commonmark.node.Image img, Path baseDir) {
        String alt = imageAlt(img);
        String url = resolveUrl(img.getDestination(), baseDir);
        if (url == null) {
            return inlineCode(alt.isBlank() ? "[image]" : "[image: " + alt + "]");
        }
        ImageView view = new ImageView();
        view.getStyleClass().add("md-image");
        view.setPreserveRatio(true);
        Image image = new Image(url, true); // background load (off the FX thread)
        view.setImage(image);
        // Cap very wide images to the pane width; re-check once the size is known.
        Runnable cap = () -> {
            if (image.getWidth() > MAX_IMAGE_WIDTH) {
                view.setFitWidth(MAX_IMAGE_WIDTH);
            }
        };
        image.widthProperty().addListener((o, was, w) -> cap.run());
        cap.run();
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
