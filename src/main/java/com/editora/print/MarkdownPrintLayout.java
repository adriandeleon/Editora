package com.editora.print;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.print.PageLayout;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.editora.editor.MarkdownRenderer;

/**
 * Builds printable JavaFX page nodes for the rendered Markdown preview — the {@code javafx.print}
 * companion to {@code com.editora.pdf.MarkdownPdfWriter}. Reuses {@link MarkdownRenderer} (parse +
 * native-node render) and the same light preview theme ({@code md-light} + {@code app.css}/{@code
 * syntax.css}) the live preview uses.
 *
 * <p><b>Block-aware pagination:</b> each top-level block (heading, paragraph, list, table, code
 * block, image, …) is measured at the printable width, then whole blocks are greedily packed into
 * pages so nothing is split across a page boundary (the pure, unit-tested {@link #packBlocks}).
 *
 * <p><b>A block taller than a page is split, not scaled.</b> It used to get a page of its own, shrunk
 * uniformly to fit — fine for an oversized image, catastrophic for anything made of text, because a
 * Markdown list is <em>one</em> top-level block however long it is. Measured on this repo's own
 * CLAUDE.md: of 19 top-level blocks six were over-tall, and one was a 296-page list rendered onto a
 * single page at 0.3% scale. The document printed as fourteen pages, most nearly blank, a few
 * microscopic.
 *
 * <p>So an over-tall container is regrouped into clones of itself holding as many of its children as fit
 * ({@link #splitToFit}) — a list becomes several lists, a long paragraph several paragraphs, each
 * carrying the original's style classes so it renders identically. A {@code TextFlow} splits at its
 * inline runs, which is what keeps the text vectors rather than slicing a rendered bitmap: crisp on
 * paper, at the cost of a seam that does not hang-indent. Uniform scaling survives only as the last
 * resort for a genuinely atomic over-tall block (one enormous image), where it is the right answer.
 *
 * <p>Everything except {@link #packBlocks} needs the JavaFX toolkit and runs on the FX thread.
 */
public final class MarkdownPrintLayout {

    private MarkdownPrintLayout() {}

    /**
     * Greedily packs block {@code heights} into pages no taller than {@code pageHeight}, never
     * splitting a block. A block taller than a page gets its own (single-block) page. Returns the
     * block indices for each page (always at least one page).
     */
    public static List<List<Integer>> packBlocks(List<Double> heights, double pageHeight) {
        List<List<Integer>> pages = new ArrayList<>();
        boolean validPage = pageHeight > 0 && Double.isFinite(pageHeight);
        List<Integer> cur = new ArrayList<>();
        double used = 0;
        for (int i = 0; i < heights.size(); i++) {
            double h = Math.max(0, heights.get(i));
            if (validPage && h > pageHeight) { // taller than any page → its own page (scaled to fit)
                if (!cur.isEmpty()) {
                    pages.add(cur);
                    cur = new ArrayList<>();
                    used = 0;
                }
                pages.add(new ArrayList<>(List.of(i)));
                continue;
            }
            if (validPage && !cur.isEmpty() && used + h > pageHeight) {
                pages.add(cur);
                cur = new ArrayList<>();
                used = 0;
            }
            cur.add(i);
            used += h;
        }
        if (!cur.isEmpty()) {
            pages.add(cur);
        }
        if (pages.isEmpty()) {
            pages.add(new ArrayList<>());
        }
        return pages;
    }

    /**
     * Renders {@code ast} (light theme), measures its blocks at {@code layout}'s printable width, and
     * returns one printable page {@link Node} (a {@code pw×ph} root, CSS attached) per page.
     */
    public static List<Node> paginate(org.commonmark.node.Node ast, Path baseDir, PageLayout layout) {
        double pw = layout.getPrintableWidth();
        double ph = layout.getPrintableHeight();

        // Render to native nodes, then pull out the inner ".markdown-preview" VBox of blocks.
        Node wrap = MarkdownRenderer.renderDocument(ast, baseDir);
        VBox content = (VBox) ((StackPane) wrap).getChildren().get(0);

        List<Double> heights = measureBlockHeights(content, pw, ph);
        List<Node> blocks = new ArrayList<>(content.getChildrenUnmodifiable());

        // Detach the blocks so they can be re-parented — into split pieces first, then into pages.
        content.getChildren().clear();

        List<Node> pieces = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            pieces.addAll(splitToFit(blocks.get(i), heights.get(i), pw, ph));
        }
        List<Double> pieceHeights = new ArrayList<>();
        for (Node piece : pieces) {
            pieceHeights.add(measureOne(piece, pw, ph));
        }
        List<List<Integer>> packed = packBlocks(pieceHeights, ph);
        blocks = pieces;
        heights = pieceHeights;

        List<Node> pages = new ArrayList<>();
        for (List<Integer> idxs : packed) {
            VBox pageContent = new VBox();
            pageContent.getStyleClass().add("markdown-preview");
            pageContent.setMaxWidth(pw);
            pageContent.setPrefWidth(pw);
            for (int i : idxs) {
                pageContent.getChildren().add(blocks.get(i));
            }
            Node body = pageContent;
            // A single over-tall block: scale it down uniformly to fit the page height.
            if (idxs.size() == 1) {
                double h = heights.get(idxs.get(0));
                if (h > ph && ph > 0) {
                    double s = ph / h;
                    pageContent.setScaleX(s);
                    pageContent.setScaleY(s);
                    body = new Group(pageContent);
                }
            }
            StackPane pageRoot = new StackPane(body);
            pageRoot.getStyleClass().add("md-light");
            pageRoot.setStyle("-fx-background-color: white;");
            StackPane.setAlignment(body, javafx.geometry.Pos.TOP_LEFT);
            pageRoot.setPrefSize(pw, ph);
            pageRoot.setMinSize(pw, ph);
            pageRoot.setMaxSize(pw, ph);
            Scene pageScene = new Scene(pageRoot, pw, ph);
            attachStyles(pageScene);
            pageRoot.applyCss();
            pageRoot.layout();
            pages.add(pageRoot);
        }
        return pages;
    }

    /**
     * How deep the splitter may recurse before accepting whatever is left.
     *
     * <p>Purely a safety net: every step descends into a node's own children, so it terminates on the
     * document's nesting whatever this is. It has to clear real nesting though — a bullet under a bullet
     * costs three levels (list → item row → content box).
     */
    private static final int MAX_SPLIT_DEPTH = 32;

    /**
     * Breaks {@code block} into pieces that each fit {@code ph}, by regrouping its children into clones of
     * itself. Returns the block untouched when it already fits or cannot be split.
     *
     * <p>Greedy, largest-prefix-first, found by binary search on the child count: a {@code TextFlow}'s
     * height is <em>not</em> the sum of its inline runs' heights (they wrap), so a candidate has to be
     * measured rather than added up. That costs O(log n) layouts per piece, which print — a deliberate,
     * one-off action — can well afford.
     *
     * <p><b>A candidate is measured inside the wrapper chain it will end up in</b> ({@code chain}: this
     * block's ancestors, outermost first). As the recursion unwinds, every ancestor re-wraps the piece in a
     * clone of itself, and those wrappers cost padding and spacing — so a piece measured bare lands over
     * the page once it is handed back. Charging a measured "overhead" per level instead is what the first
     * attempt did, and it collapsed: a node's height depends on its ancestry through CSS (a {@code Text}
     * run measured outside its {@code TextFlow} is a different height entirely), so the subtractions ran
     * the budget negative and splitting bailed out on the very items that needed it.
     */
    static List<Node> splitToFit(Node block, double height, double pw, double ph) {
        return split(block, height, List.of(), pw, ph, 0);
    }

    private static List<Node> split(Node block, double height, List<Wrapper> chain, double pw, double ph, int depth) {
        if (!(ph > 0) || height <= ph || depth >= MAX_SPLIT_DEPTH) {
            return List.of(block);
        }
        // A list item is a marker beside its content; splitting it means splitting the content and
        // repeating the row, so the continuation keeps the item's indentation instead of sliding left.
        if (block instanceof HBox row && row.getChildren().size() == 2) {
            return splitListItem(row, chain, pw, ph, depth);
        }
        // A plain paragraph is a SINGLE Text run — no inline code or emphasis to split at — so run-level
        // splitting cannot help it and it would be scaled. Split the string instead.
        if (block instanceof Text text) {
            return splitText(text, chain, pw, ph);
        }
        if (!(block instanceof Pane pane) || pane.getChildren().isEmpty()) {
            return List.of(block); // atomic: the last-resort scale in paginate() handles it
        }
        List<Wrapper> inner = append(chain, pane, 0);
        // A single-child container is not atomic — it is a wrapper around the thing that overflows, and a
        // list item's content box is very often exactly that (one nested list).
        if (pane.getChildren().size() == 1) {
            Node only = pane.getChildren().get(0);
            pane.getChildren().clear();
            List<Node> subs = split(only, measureWrapped(only, inner, pw, ph), inner, pw, ph, depth + 1);
            if (subs.size() == 1) {
                pane.getChildren().add(subs.get(0)); // no progress — hand the original back intact
                return List.of(pane);
            }
            return wrapEach(subs, pane, pw);
        }

        List<Node> children = new ArrayList<>(pane.getChildren());
        pane.getChildren().clear();
        List<Node> out = new ArrayList<>();
        int from = 0;
        while (from < children.size()) {
            int take = largestPrefixThatFits(pane, chain, children, from, pw, ph);
            if (take == 0) {
                // Even one child overflows: recurse into it, then carry on after it.
                Node child = children.get(from);
                List<Node> subs = split(child, measureWrapped(child, inner, pw, ph), inner, pw, ph, depth + 1);
                out.addAll(wrapEach(subs, pane, pw));
                from++;
                continue;
            }
            Pane piece = cloneShell(pane, pw);
            piece.getChildren().addAll(children.subList(from, from + take));
            out.add(piece);
            from += take;
        }
        return out;
    }

    /**
     * Splits one over-tall {@code Text} run into several, cut at whitespace so no word is broken.
     *
     * <p>Needed because an ordinary paragraph — no inline code, no emphasis — renders as exactly one run,
     * and a document of plain prose would otherwise be the one shape still scaled down. The cut point is
     * found by binary search on the character index and then walked back to the nearest space, each
     * candidate measured inside the real wrapper chain (so the enclosing {@code TextFlow} wraps it as it
     * actually will).
     *
     * <p>A single word taller than the page cannot be split and is returned as-is, for the last-resort
     * scale to deal with.
     */
    private static List<Node> splitText(Text text, List<Wrapper> chain, double pw, double ph) {
        String all = text.getText();
        if (all == null || all.isBlank()) {
            return List.of(text);
        }
        List<Node> out = new ArrayList<>();
        int from = 0;
        while (from < all.length()) {
            int take = largestTextPrefixThatFits(text, all, from, chain, pw, ph);
            if (take <= 0) {
                // Not even one word fits: emit the rest whole rather than loop forever.
                out.add(textLike(text, all.substring(from)));
                break;
            }
            out.add(textLike(text, all.substring(from, from + take)));
            from += take;
            while (from < all.length() && all.charAt(from) == ' ') {
                from++; // the space that became the line break is not carried onto the next piece
            }
        }
        return out.size() > 1 ? out : List.of(text);
    }

    private static int largestTextPrefixThatFits(
            Text template, String all, int from, List<Wrapper> chain, double pw, double ph) {
        int lo = 0;
        int hi = all.length() - from;
        while (lo < hi) {
            int mid = wordBoundary(all, from, (lo + hi + 1) / 2);
            if (mid <= lo) {
                break; // no boundary left to try between lo and hi
            }
            double h = measureWrapped(textLike(template, all.substring(from, from + mid)), chain, pw, ph);
            if (h <= ph) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** {@code len} walked back to the end of the last whole word, so a cut never lands inside one. */
    private static int wordBoundary(String all, int from, int len) {
        int end = Math.min(from + len, all.length());
        if (end >= all.length()) {
            return all.length() - from;
        }
        int p = end;
        while (p > from && all.charAt(p) != ' ') {
            p--;
        }
        return p > from ? p - from : len;
    }

    /** A {@code Text} carrying {@code s} with the template's styling, so the split is invisible. */
    private static Text textLike(Text template, String s) {
        Text t = new Text(s);
        t.getStyleClass().setAll(template.getStyleClass());
        t.setFont(template.getFont());
        t.setFill(template.getFill());
        t.setStrikethrough(template.isStrikethrough());
        t.setUnderline(template.isUnderline());
        return t;
    }

    /** Each piece in its own clone of {@code template}, so the group's styling survives the split. */
    private static List<Node> wrapEach(List<Node> pieces, Pane template, double pw) {
        List<Node> out = new ArrayList<>();
        for (Node piece : pieces) {
            Pane holder = cloneShell(template, pw);
            holder.getChildren().add(piece);
            out.add(holder);
        }
        return out;
    }

    private static List<Wrapper> append(List<Wrapper> chain, Pane pane, double leadWidth) {
        List<Wrapper> out = new ArrayList<>(chain);
        out.add(new Wrapper(pane, leadWidth));
        return out;
    }

    /**
     * An ancestor a piece will be re-wrapped in, plus the width taken from it by a leading sibling.
     *
     * <p>{@code leadWidth} exists for the list-item row: its content shares the row with the bullet, so a
     * candidate measured in an empty row clone gets the full page width, comes out short, and the assembled
     * row then overflows. Measured on this repo's CLAUDE.md, ignoring it left 244 of a 296-page list's
     * pieces over the page — each by only ~20%, which is exactly what a missing bullet column costs.
     */
    private record Wrapper(Pane template, double leadWidth) {}

    /** The largest number of children from {@code from} whose piece still fits {@code ph}; 0 if none do. */
    private static int largestPrefixThatFits(
            Pane template, List<Wrapper> chain, List<Node> children, int from, double pw, double ph) {
        int lo = 0;
        int hi = children.size() - from;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            Pane probe = cloneShell(template, pw);
            probe.getChildren().addAll(children.subList(from, from + mid));
            double h = measureWrapped(probe, chain, pw, ph);
            probe.getChildren().clear(); // hand the children back for the next probe
            if (h <= ph) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /**
     * The height {@code node} will have once every wrapper in {@code chain} has re-wrapped it, leaving the
     * node detached again so the caller can reuse it.
     */
    private static double measureWrapped(Node node, List<Wrapper> chain, double pw, double ph) {
        Node outer = node;
        List<Pane> shells = new ArrayList<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            Wrapper w = chain.get(i);
            Pane shell = cloneShell(w.template(), pw);
            if (w.leadWidth() > 0) {
                Region lead = new Region();
                lead.setMinWidth(w.leadWidth());
                lead.setPrefWidth(w.leadWidth());
                shell.getChildren().add(lead);
                HBox.setHgrow(outer, Priority.ALWAYS);
            }
            shell.getChildren().add(outer);
            shells.add(shell);
            outer = shell;
        }
        double h = measureOne(outer, pw, ph);
        for (Pane shell : shells) {
            shell.getChildren().clear();
        }
        return h;
    }

    /**
     * Splits a {@code .md-list-item} row: the first piece keeps the real marker, each continuation gets a
     * blank of the marker's width, so the text stays in its column and the bullet is not repeated.
     */
    private static List<Node> splitListItem(HBox row, List<Wrapper> chain, double pw, double ph, int depth) {
        Node marker = row.getChildren().get(0);
        Node content = row.getChildren().get(1);
        double markerWidth = marker.getLayoutBounds().getWidth();
        row.getChildren().clear();
        List<Wrapper> inner = append(chain, row, markerWidth);
        List<Node> parts = split(content, measureWrapped(content, inner, pw, ph), inner, pw, ph, depth + 1);
        if (parts.size() == 1) {
            row.getChildren().addAll(marker, parts.get(0)); // nothing gained; put it back as it was
            return List.of(row);
        }
        List<Node> out = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            HBox r = new HBox(row.getSpacing());
            r.getStyleClass().setAll(row.getStyleClass());
            r.setPadding(row.getPadding());
            r.setAlignment(row.getAlignment());
            Node lead = marker;
            if (i > 0) {
                Region blank = new Region();
                blank.setMinWidth(markerWidth);
                blank.setPrefWidth(markerWidth);
                lead = blank;
            }
            Node part = parts.get(i);
            HBox.setHgrow(part, Priority.ALWAYS);
            r.getChildren().addAll(lead, part);
            out.add(r);
        }
        return out;
    }

    /** An empty container of the same kind and styling as {@code template}, ready to take a subset. */
    private static Pane cloneShell(Pane template, double pw) {
        Pane copy;
        if (template instanceof TextFlow tf) {
            TextFlow t = new TextFlow();
            t.setLineSpacing(tf.getLineSpacing());
            t.setTextAlignment(tf.getTextAlignment());
            copy = t;
        } else if (template instanceof HBox hb) {
            HBox h = new HBox(hb.getSpacing());
            h.setAlignment(hb.getAlignment());
            copy = h;
        } else if (template instanceof VBox vb) {
            VBox v = new VBox(vb.getSpacing());
            v.setAlignment(vb.getAlignment());
            copy = v;
        } else {
            copy = new VBox();
        }
        copy.getStyleClass().setAll(template.getStyleClass());
        copy.setPadding(template.getPadding());
        copy.setMaxWidth(pw);
        copy.setPrefWidth(pw);
        return copy;
    }

    /** The laid-out height of one detached node at the printable width; leaves it detached. */
    static double measureOne(Node node, double pw, double ph) {
        VBox holder = new VBox(node);
        holder.getStyleClass().add("markdown-preview");
        List<Double> h = measureBlockHeights(holder, pw, ph);
        holder.getChildren().clear();
        return h.isEmpty() ? 0 : h.get(0);
    }

    /**
     * Lays out the preview {@code content} VBox (the inner {@code .markdown-preview} block list) at a
     * <b>definite</b> width {@code pw} and returns each top-level block's laid-out height, used to paginate.
     *
     * <p>The measure scene is created at a fixed width (pw) and is <b>not</b> wrapped in a {@code Group}: a
     * Group shrink-wraps to its child's intrinsic width, which for a percent-width table {@code GridPane}
     * collapses the columns to a few characters, wrapping every cell and grossly over-measuring the table's
     * height (so it got bumped to its own page, leaving the previous page mostly empty and not matching the
     * on-screen preview). Block heights come from the VBox's per-child preferred heights, so the scene's
     * height doesn't affect them. Runs on the FX thread.
     */
    public static List<Double> measureBlockHeights(VBox content, double pw, double ph) {
        content.setMaxWidth(pw);
        content.setPrefWidth(pw);
        StackPane root = measureRoot(pw, ph);
        root.getChildren().setAll(content);
        root.applyCss();
        root.layout();
        List<Double> heights = new ArrayList<>();
        for (Node b : content.getChildrenUnmodifiable()) {
            heights.add(b.getLayoutBounds().getHeight());
        }
        root.getChildren().clear(); // leave `content` detached for its real parent
        return heights;
    }

    private static StackPane cachedMeasureRoot;
    private static double cachedMeasureWidth = -1;

    /**
     * The shared off-screen root every measurement lays out in, rebuilt only when the page width changes.
     *
     * <p>Splitting measures a great many candidates — binary search per piece, over a document that can be
     * hundreds of pages — and a fresh {@code Scene} per measurement re-parses both stylesheets every time.
     * Measured on this repo's CLAUDE.md: 21.3s to paginate with a scene per measurement. Pagination runs on
     * the FX thread (a {@code Scene} cannot be built off it), so that is the window frozen, which is why
     * this is cached rather than left simple.
     *
     * <p>FX-thread-only, like everything else here, so the cache needs no synchronisation.
     */
    private static StackPane measureRoot(double pw, double ph) {
        if (cachedMeasureRoot == null || cachedMeasureWidth != pw) {
            StackPane root = new StackPane();
            root.getStyleClass().add("md-light");
            root.setPrefWidth(pw);
            root.setMaxWidth(pw);
            Scene scene = new Scene(root, pw, Math.max(ph, 1));
            attachStyles(scene);
            cachedMeasureRoot = root;
            cachedMeasureWidth = pw;
        }
        return cachedMeasureRoot;
    }

    private static void attachStyles(Scene scene) {
        addStylesheet(scene, "/com/editora/styles/app.css");
        addStylesheet(scene, "/com/editora/styles/syntax.css");
    }

    private static void addStylesheet(Scene scene, String resource) {
        java.net.URL url = MarkdownPrintLayout.class.getResource(resource);
        if (url != null) {
            scene.getStylesheets().add(url.toExternalForm());
        }
    }
}
