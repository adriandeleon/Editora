package com.editora.print;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.print.PageLayout;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.editora.editor.MarkdownPrintAssets;
import com.editora.editor.MarkdownRenderer;

/**
 * Builds printable JavaFX page nodes for the rendered Markdown preview — the {@code javafx.print}
 * companion to {@code com.editora.pdf.MarkdownPdfWriter}. Reuses {@link MarkdownRenderer} (parse +
 * native-node render) and the same light preview theme ({@code md-light} + {@code app.css}/{@code
 * syntax.css}) the live preview uses.
 *
 * <p><b>Block-aware pagination:</b> each top-level block (heading, paragraph, list, table, code
 * block, image, …) is measured at the printable width, then blocks are packed into pages (the pure,
 * unit-tested {@link #packBlocks}). An image, a diagram or a formula is never cut by a page boundary.
 *
 * <p><b>Text, a list, a quote, a code block or a table that does not fit what is left of the page continues
 * on the next</b> — it is cut at a line, an item or a row, sized against the room actually left
 * ({@link Packer#room}), rather than being moved whole. Moving it whole is what a first version did, and it
 * stranded every heading above a long listing on a page of its own: this repo's README printed as 72
 * pages, a third of them under two-thirds full. A cut needs at least {@link #MIN_SPLIT_ROOM} of room,
 * {@link #MIN_CODE_LINES} lines of a code block or {@link #MIN_TABLE_ROWS} rows of a table on the old
 * page, else the block starts the new one. <b>A heading stays with what follows it</b>: when the next
 * block opens a new page the heading goes along.
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
 * carrying the original's style classes so it renders identically. A {@code TextFlow} is cut at the word
 * its last line would have wrapped at ({@link #splitFlow}), which keeps the text vectors rather than
 * slicing a rendered bitmap: crisp on paper. Uniform scaling survives only as the last
 * resort for a genuinely atomic over-tall block (one enormous image), where it is the right answer.
 *
 * <p><b>A table is split between its rows</b> ({@link #splitTable}), each piece a table again with the same
 * columns and the header row repeated, and a code block between its lines (the print renderer builds it as
 * one node per line). Both are cut by arithmetic on measured row heights, so a 5,000-row CSV paginates in
 * time proportional to its length.
 *
 * <p><b>Always light.</b> Paper is white whatever the app theme is, so the page root carries the preview's
 * fixed light palette ({@code .markdown-preview-pane.md-light}) and the renderer is asked for light math
 * and diagrams.
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
        return packBlocks(heights, pageHeight, 0);
    }

    /**
     * As {@link #packBlocks(List, double)}, but charging {@code spacing} between consecutive blocks.
     *
     * <p>The page's own container is a {@code VBox} with CSS padding and spacing, and packing ignored both:
     * blocks summing to exactly the page height then overflowed it by the gaps between them. Measured on a
     * 200-item list before this: six of eight pages over the page, the worst by 31px. Invisible to a "was
     * anything scaled?" check, which is what the over-tall rule is guarded by, and invisible on screen
     * because the preview clips.
     */
    public static List<List<Integer>> packBlocks(List<Double> heights, double pageHeight, double spacing) {
        return packBlocks(heights, pageHeight, spacing, i -> false);
    }

    /**
     * As {@link #packBlocks(List, double, double)}, with a <b>keep-with-next</b> rule for headings: a block
     * for which {@code keepWithNext} answers true is not left as the last thing on a page. When the block
     * after it has to open a new page, the heading (and any run of headings directly above it) goes along —
     * provided something else stays behind on the old page and the headings and the block fit one page
     * together. Pure.
     */
    public static List<List<Integer>> packBlocks(
            List<Double> heights, double pageHeight, double spacing, java.util.function.IntPredicate keepWithNext) {
        Packer packer = new Packer(pageHeight, spacing);
        for (int i = 0; i < heights.size(); i++) {
            packer.add(heights.get(i), keepWithNext.test(i));
        }
        return packer.pages();
    }

    /**
     * The page being filled, one block at a time — {@link #packBlocks} as a running state, so the splitter
     * can ask how much room is left <em>before</em> it sizes the next piece ({@link #room}). Pure.
     */
    static final class Packer {
        private final double pageHeight;
        private final double gap;
        private final boolean valid;
        private final List<List<Integer>> pages = new ArrayList<>();
        private final List<Double> heights = new ArrayList<>();
        private final java.util.BitSet keep = new java.util.BitSet();
        private List<Integer> cur = new ArrayList<>();
        private double used;
        private boolean breakPending;

        Packer(double pageHeight, double spacing) {
            this.pageHeight = pageHeight;
            this.gap = Math.max(0, spacing);
            this.valid = pageHeight > 0 && Double.isFinite(pageHeight);
        }

        /** The tallest block that still goes on the current page (or on the next, once a break is asked for). */
        double room() {
            if (!valid) {
                return Double.MAX_VALUE;
            }
            if (breakPending) {
                return freshRoom();
            }
            return cur.isEmpty() ? pageHeight : pageHeight - used - gap;
        }

        /** The tallest block a new page would take now: a page, less the headings it would bring along. */
        double freshRoom() {
            if (!valid) {
                return Double.MAX_VALUE;
            }
            int from = trailingKept();
            return from == 0 || from == cur.size() ? pageHeight : pageHeight - stacked(from) - gap;
        }

        /** Whether ending the page here would help: it holds something besides headings waiting for a block. */
        boolean canBreak() {
            return valid && !breakPending && trailingKept() > 0;
        }

        /** The next block opens a new page, whatever its height. A no-op where {@link #canBreak} is false. */
        void breakPage() {
            breakPending = canBreak();
        }

        void add(double height, boolean keepWithNext) {
            double h = Math.max(0, height);
            int index = heights.size();
            heights.add(h);
            keep.set(index, keepWithNext);
            if (valid && h > pageHeight) { // taller than any page → its own page (scaled to fit)
                if (!cur.isEmpty()) {
                    pages.add(cur);
                    cur = new ArrayList<>();
                    used = 0;
                }
                pages.add(new ArrayList<>(List.of(index)));
                breakPending = false;
                return;
            }
            if (valid && !cur.isEmpty() && (breakPending || used + gap + h > pageHeight)) {
                int from = trailingKept();
                List<Integer> carried = new ArrayList<>();
                if (from > 0 && from < cur.size() && stacked(from) + gap + h <= pageHeight) {
                    carried.addAll(cur.subList(from, cur.size()));
                    cur.subList(from, cur.size()).clear();
                }
                pages.add(cur);
                cur = carried;
                used = cur.isEmpty() ? 0 : stacked(0);
            }
            breakPending = false;
            used += cur.isEmpty() ? h : gap + h;
            cur.add(index);
        }

        /** The pages so far (always at least one). */
        List<List<Integer>> pages() {
            List<List<Integer>> all = new ArrayList<>(pages);
            if (!cur.isEmpty() || all.isEmpty()) {
                all.add(new ArrayList<>(cur));
            }
            return all;
        }

        double height(int index) {
            return heights.get(index);
        }

        /** The state to come back to with {@link #rewind}: everything added after it is forgotten. */
        Mark mark() {
            return new Mark(pages.size(), new ArrayList<>(cur), used, heights.size(), breakPending);
        }

        /** Whether a page has been closed since {@code mark}. */
        boolean brokeSince(Mark mark) {
            return pages.size() > mark.pages();
        }

        void rewind(Mark mark) {
            pages.subList(mark.pages(), pages.size()).clear();
            cur = new ArrayList<>(mark.cur());
            used = mark.used();
            heights.subList(mark.count(), heights.size()).clear();
            keep.clear(mark.count(), Math.max(mark.count(), keep.length()));
            breakPending = mark.breakPending();
        }

        record Mark(int pages, List<Integer> cur, double used, int count, boolean breakPending) {}

        int count() {
            return heights.size();
        }

        /**
         * Where the current page's trailing run of keep-with-next blocks starts: its size when there is no
         * such run, and 0 when the run is the whole page (or the page is empty) — in which case nothing is
         * moved, since that would only leave an empty page behind.
         */
        private int trailingKept() {
            int from = cur.size();
            while (from > 0 && keep.get(cur.get(from - 1))) {
                from--;
            }
            return from;
        }

        /** The height of the current page's blocks from position {@code from} on, with the gaps between them. */
        private double stacked(int from) {
            double h = 0;
            for (int i = from; i < cur.size(); i++) {
                h += heights.get(cur.get(i)) + (i > from ? gap : 0);
            }
            return h;
        }
    }

    /**
     * What the splitter sizes its pieces against: the room left on the page being filled, which shrinks as
     * pieces are {@link #placed}. {@link #fixed} is the degenerate form — every piece gets the same limit —
     * for content that is not flowing down a page (the cells of one table row, split side by side).
     */
    private static final class Flow {
        private final Packer packer;
        private final double fixed;

        Flow(Packer packer) {
            this.packer = packer;
            this.fixed = 0;
        }

        private Flow(double fixed) {
            this.packer = null;
            this.fixed = fixed;
        }

        static Flow fixed(double limit) {
            return new Flow(limit);
        }

        double limit() {
            return packer == null ? fixed : packer.room();
        }

        double freshLimit() {
            return packer == null ? fixed : packer.freshRoom();
        }

        boolean canBreak() {
            return packer != null && packer.canBreak();
        }

        void breakPage() {
            if (packer != null) {
                packer.breakPage();
            }
        }

        void placed(double height) {
            if (packer != null) {
                packer.add(height, false);
            }
        }

        Packer.Mark mark() {
            return packer == null ? null : packer.mark();
        }

        boolean brokeSince(Packer.Mark mark) {
            return packer != null && packer.brokeSince(mark);
        }

        void rewind(Packer.Mark mark) {
            if (packer != null) {
                packer.rewind(mark);
            }
        }
    }

    /**
     * Renders {@code ast} (light theme), measures its blocks at {@code layout}'s printable width, and
     * returns one printable page {@link Node} (a {@code pw×ph} root, CSS attached) per page.
     */
    public static List<Node> paginate(org.commonmark.node.Node ast, Path baseDir, PageLayout layout) {
        return paginate(ast, baseDir, null, layout.getPrintableWidth(), layout.getPrintableHeight());
    }

    /** As {@link #paginate(org.commonmark.node.Node, Path, PageLayout)} for a printable area given in points. */
    public static List<Node> paginate(org.commonmark.node.Node ast, Path baseDir, double pw, double ph) {
        return paginate(ast, baseDir, null, pw, ph);
    }

    /**
     * Paginates {@code ast} onto {@code pw×ph} pages. Takes the printable area as numbers rather than a
     * {@link PageLayout} because a layout can only come from a printer, and nothing here needs one.
     *
     * <p>{@code assets} are the document's images, diagrams and code colours, resolved beforehand off the FX
     * thread ({@link MarkdownPrintAssets#resolve}) so that every block has its final size when it is
     * measured. Null means "not resolved": images and diagrams then arrive after pagination and can
     * overflow their page, and code prints uncoloured — acceptable only where there are none.
     */
    public static List<Node> paginate(
            org.commonmark.node.Node ast, Path baseDir, MarkdownPrintAssets assets, double pw, double ph) {
        return paginate(ast, baseDir, assets, pw, ph, null);
    }

    /**
     * As {@link #paginate(org.commonmark.node.Node, Path, MarkdownPrintAssets, double, double)}, with a
     * {@code footer} line at the bottom of every page (null for none). The footer's height is taken off the
     * page before anything is packed, so the content never runs into it.
     */
    public static List<Node> paginate(
            org.commonmark.node.Node ast,
            Path baseDir,
            MarkdownPrintAssets assets,
            double pw,
            double ph,
            PageFooter footer) {
        double pageHeight = ph;
        ph = footer == null ? ph : PageFooter.bodyHeight(ph); // everything below lays out above the footer
        // Render to native nodes, then pull out the inner ".markdown-preview" VBox of blocks.
        Node wrap = MarkdownRenderer.renderForPrint(ast, baseDir, assets);
        VBox content = (VBox) ((StackPane) wrap).getChildren().get(0);

        prepareTables(content, pw, ph);
        List<Double> heights = measureBlockHeights(content, pw, ph);
        List<Node> blocks = new ArrayList<>(content.getChildrenUnmodifiable());

        // Detach the blocks so they can be re-parented — into split pieces first, then into pages.
        content.getChildren().clear();

        // The page's own container costs padding above and below and a gap between each pair of blocks, so
        // the height available to content is less than the printable height. Read from CSS rather than
        // assumed, because both come from the .markdown-preview rule.
        VBox probe = new VBox();
        probe.getStyleClass().add("markdown-preview");
        measureBlockHeights(probe, pw, ph); // applies CSS, which is what resolves padding/spacing
        double pagePadding = probe.getPadding().getTop() + probe.getPadding().getBottom();
        double pageSpacing = probe.getSpacing();
        double sidePadding =
                Math.max(probe.getPadding().getLeft(), probe.getPadding().getRight());
        double contentHeight = Math.max(1, ph - pagePadding);

        // Deal the blocks onto pages as they are split: a piece is sized against the room left on the page
        // it will land on, and the packer is told about it straight away so the next one is too.
        Packer packer = new Packer(contentHeight, pageSpacing);
        Flow flow = new Flow(packer);
        List<Node> pieces = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Node block = blocks.get(i);
            if (isHeading(block)) {
                pieces.add(block);
                packer.add(heights.get(i), true);
            } else {
                pieces.addAll(split(block, heights.get(i), List.of(), pw, contentHeight, flow, 0));
            }
        }
        List<List<Integer>> packed = packer.pages();
        List<Double> pieceHeights = new ArrayList<>();
        if (packer.count() == pieces.size()) {
            for (int i = 0; i < pieces.size(); i++) {
                pieceHeights.add(packer.height(i));
            }
        } else {
            // The splitter lost count of its own pieces. Cannot happen as written; if it ever does, measure
            // what there is and pack that, rather than put a piece on the wrong page.
            for (Node piece : pieces) {
                pieceHeights.add(measureOne(piece, pw, ph));
            }
            packed = packBlocks(pieceHeights, contentHeight, pageSpacing);
        }
        blocks = pieces;
        heights = pieceHeights;

        List<Node> pages = new ArrayList<>();
        for (List<Integer> idxs : packed) {
            VBox pageContent = new VBox();
            pageContent.getStyleClass().add("markdown-preview");
            pageContent.setMaxWidth(pw);
            pageContent.setPrefWidth(pw);
            for (int i : idxs) {
                Node piece = blocks.get(i);
                int last = pageContent.getChildren().size() - 1;
                if (last >= 0 && continues(pageContent.getChildren().get(last), piece)) {
                    // Two pieces of one list or quote on the same page (the end of an item cut by the last
                    // page break, then the items after it): one list again, not two with a block's gap between.
                    ((VBox) pageContent.getChildren().get(last)).getChildren().addAll(((VBox) piece).getChildren());
                } else {
                    pageContent.getChildren().add(piece);
                }
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
            pageRoot.getStyleClass().addAll(LIGHT_PAGE_CLASSES);
            pageRoot.setStyle("-fx-background-color: white;");
            StackPane.setAlignment(body, javafx.geometry.Pos.TOP_LEFT);
            if (footer != null) {
                Node line = footer.line(pages.size() + 1, packed.size(), pw, sidePadding);
                StackPane.setAlignment(line, javafx.geometry.Pos.BOTTOM_LEFT);
                pageRoot.getChildren().add(line); // after the body: callers find the body at index 0
            }
            pageRoot.setPrefSize(pw, pageHeight);
            pageRoot.setMinSize(pw, pageHeight);
            pageRoot.setMaxSize(pw, pageHeight);
            Scene pageScene = new Scene(pageRoot, pw, pageHeight);
            attachStyles(pageScene);
            pageRoot.applyCss();
            pageRoot.layout();
            pages.add(pageRoot);
        }
        return pages;
    }

    /** Node-property key naming the block a split piece was cut from (see {@link #cloneShell}). */
    private static final Object ORIGIN_KEY = new Object();

    /** Whether {@code next} is a further piece of the same stacked block (list, quote) as {@code previous}. */
    private static boolean continues(Node previous, Node next) {
        Object origin = previous.getProperties().get(ORIGIN_KEY);
        return origin != null
                && origin == next.getProperties().get(ORIGIN_KEY)
                && previous instanceof VBox
                && next instanceof VBox
                && previous.getStyleClass().equals(next.getStyleClass());
    }

    /** A heading block — the one kind of block that is kept with whatever follows it. */
    private static boolean isHeading(Node block) {
        for (String c : block.getStyleClass()) {
            if (c.length() == 5 && c.startsWith("md-h") && Character.isDigit(c.charAt(4))) {
                return true;
            }
        }
        return false;
    }

    /** The least room worth starting a split block in: about three lines of body text. */
    static final double MIN_SPLIT_ROOM = 60;

    /** A code block is not cut with fewer lines than this on either side of the page break. */
    static final int MIN_CODE_LINES = 3;

    /** A table is not cut with fewer body rows than this left under its header on the old page. */
    static final int MIN_TABLE_ROWS = 2;

    /**
     * Whether {@code node} is one thing that cannot be continued overleaf — an image, a formula, a rule, a
     * diagram — as opposed to text (cut at a line) or a stack (a list of items, a quote of paragraphs, a code
     * block of lines, a table of rows — cut between them). A unit that fits a page is moved to the next page
     * whole. A wrapper around a single unit is itself a unit.
     */
    private static boolean isUnit(Node node) {
        if (node instanceof GridPane grid) {
            return !grid.getStyleClass().contains("md-table");
        }
        if (node instanceof TextFlow) {
            return false;
        }
        if (!(node instanceof Pane pane) || pane.getChildren().isEmpty()) {
            return true;
        }
        if (pane instanceof HBox row && row.getChildren().size() == 2) {
            return isUnit(row.getChildren().get(1)); // a list item: its marker beside its content
        }
        if (pane.getChildren().size() == 1) {
            return isUnit(pane.getChildren().get(0));
        }
        return !(pane instanceof VBox);
    }

    /**
     * The classes that pin a page to the preview's light palette.
     *
     * <p>Both are needed: the rule that redefines the looked-up colours is the compound
     * {@code .markdown-preview-pane.md-light}. With {@code md-light} alone nothing matched, the colours stayed
     * the app theme's, and under a dark theme the inner {@code .markdown-preview} box painted a dark sheet
     * with light text over the page's white background.
     */
    private static final List<String> LIGHT_PAGE_CLASSES = List.of("markdown-preview-pane", "md-light");

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
        return split(block, height, List.of(), pw, ph, Flow.fixed(ph), 0);
    }

    /**
     * {@code ph} is a whole page; {@code flow} is what each piece is actually sized against — the room left
     * on the page it will land on — and is told about every piece the moment it is final
     * ({@link Flow#placed}), exactly once, in document order.
     */
    private static List<Node> split(
            Node block, double height, List<Wrapper> chain, double pw, double ph, Flow flow, int depth) {
        if (block instanceof GridPane grid && grid.getProperties().containsKey(TABLE_ROWS_KEY)) {
            return splitTable(grid, height, chain, pw, ph, flow, depth); // rows detached: no height to go by
        }
        if (!(ph > 0) || height <= flow.limit() || depth >= MAX_SPLIT_DEPTH) {
            flow.placed(height);
            return List.of(block);
        }
        if (flow.canBreak()) {
            if (isUnit(block) && height <= flow.freshLimit()) {
                flow.breakPage(); // an image, a formula, a rule: it goes to the next page whole
                flow.placed(height);
                return List.of(block);
            }
            if (flow.limit() < MIN_SPLIT_ROOM) {
                flow.breakPage(); // too little left to be worth starting in
                if (height <= flow.limit()) {
                    flow.placed(height);
                    return List.of(block);
                }
            }
        }
        // A list item is a marker beside its content; splitting it means splitting the content and
        // repeating the row, so the continuation keeps the item's indentation instead of sliding left.
        if (block instanceof HBox row && row.getChildren().size() == 2) {
            return splitListItem(row, chain, pw, ph, flow, depth);
        }
        if (block instanceof TextFlow text && !text.getChildren().isEmpty()) {
            return splitFlow(text, height, chain, pw, ph, flow);
        }
        if (block instanceof GridPane grid) {
            return splitTable(grid, height, chain, pw, ph, flow, depth);
        }
        if (!(block instanceof Pane pane) || pane.getChildren().isEmpty()) {
            flow.placed(height);
            return List.of(block); // atomic: the last-resort scale in paginate() handles it
        }
        List<Wrapper> inner = append(chain, pane, 0);
        // A single-child container is not atomic — it is a wrapper around the thing that overflows, and a
        // list item's content box is very often exactly that (one nested list).
        if (pane.getChildren().size() == 1) {
            Node only = pane.getChildren().get(0);
            pane.getChildren().clear();
            List<Node> subs = split(only, measureWrapped(only, inner, pw, ph), inner, pw, ph, flow, depth + 1);
            if (subs.size() == 1) {
                pane.getChildren().add(subs.get(0)); // no progress — hand the original back intact
                return List.of(pane);
            }
            return wrapEach(subs, pane, pw);
        }

        List<Node> children = new ArrayList<>(pane.getChildren());
        pane.getChildren().clear();
        // For a VBox the height of a group is arithmetic (see ownHeights), so measure each child once here
        // and spend no layout at all on the search. Null for every other container, where it is not.
        double[] own = ownHeights(pane, chain, children, pw, ph);
        boolean codeLines = pane.getStyleClass().contains("md-code-lines");
        double[] fit = new double[1]; // the measured height of the prefix last taken
        List<Node> out = new ArrayList<>();
        int from = 0;
        while (from < children.size()) {
            int rest = children.size() - from;
            int take = largestPrefixThatFits(pane, chain, children, from, own, pw, ph, flow.limit(), fit);
            double firstHeight = take == 0 ? measureWrapped(children.get(from), inner, pw, ph) : 0;
            if (take < rest && flow.canBreak()) {
                // Rather than leave a line or two of a listing behind, or cut a paragraph that would fit a
                // page whole, end the page here and size the piece against the next one.
                boolean tooFewLines = codeLines && take < MIN_CODE_LINES;
                boolean wholeUnit = take == 0 && isUnit(children.get(from)) && firstHeight <= flow.freshLimit();
                if (tooFewLines || wholeUnit) {
                    flow.breakPage();
                    continue;
                }
            }
            if (take == 0) {
                // Even one child overflows: recurse into it, then carry on after it.
                Node child = children.get(from);
                Packer.Mark before = flow.mark();
                List<Node> subs = split(child, firstHeight, inner, pw, ph, flow, depth + 1);
                if (subs.size() == 1 && flow.brokeSince(before)) {
                    // It was not cut after all: it moved to the next page whole. Take that back and start
                    // the page here instead, so the children after it join it in one piece rather than
                    // being sized as if they had the page to themselves.
                    children.set(from, subs.get(0));
                    flow.rewind(before);
                    flow.breakPage();
                    continue;
                }
                out.addAll(wrapEach(subs, pane, pw));
                from++;
                continue;
            }
            if (codeLines && take < rest && rest - take < MIN_CODE_LINES) {
                // …and do not carry a line or two over either, when the old page can spare them.
                int fewer = rest - MIN_CODE_LINES;
                if (fewer >= MIN_CODE_LINES) {
                    take = fewer;
                    fit[0] = measurePrefix(pane, chain, children, from, take, pw, ph);
                }
            }
            Pane piece = cloneShell(pane, pw);
            piece.getChildren().addAll(children.subList(from, from + take));
            flow.placed(fit[0]);
            out.add(piece);
            from += take;
        }
        return out;
    }

    /**
     * Continues a paragraph (or a table cell — any {@code TextFlow}) on the next page: each piece is a flow
     * like the original holding the runs, and the part of one run, that fit the room it is given.
     *
     * <p>The cut is the last word that still fits, found by measuring the candidate <em>as one flow</em> —
     * whole runs first, then a binary search over the words of the run the page ends in. That makes it the
     * place the line would have wrapped anyway, so the first piece ends in a full line and the next starts
     * a new one: on paper the paragraph simply carries on overleaf. Cutting each run separately, as this
     * once did, put a line break at every run boundary near the cut.
     *
     * <p>No single line is left behind or carried over: if fewer than two lines would stay on the old page
     * the paragraph starts on the new one, and if fewer than two would move, and it fits a page, all of it
     * moves. A flow that cannot be cut at all (one image, one formula) goes to a fresh page whole.
     */
    private static List<Node> splitFlow(
            TextFlow block, double height, List<Wrapper> chain, double pw, double ph, Flow flow) {
        List<Node> runs = new ArrayList<>(block.getChildren());
        block.getChildren().clear();
        double oneLine = linesHeight(block, runs, "X", chain, pw, ph);
        double twoLines = linesHeight(block, runs, "X\nX", chain, pw, ph) - 0.5;
        List<Node> out = new ArrayList<>();
        double[] fit = new double[1];
        double rest = height; // the height of what is still to be placed
        double cap = Double.MAX_VALUE; // set to end a piece a line early, so two lines are carried over
        while (!runs.isEmpty()) {
            double limit = Math.min(flow.limit(), cap);
            cap = Double.MAX_VALUE;
            if (rest <= limit) {
                out.add(flowOf(block, runs, pw));
                flow.placed(rest);
                break;
            }
            int take = largestPrefixThatFits(block, chain, runs, 0, null, pw, ph, limit, fit);
            String head = null;
            if (take < runs.size() && runs.get(take) instanceof Text run && run.getText() != null) {
                int chars = wordsThatFit(block, runs.subList(0, take), run, chain, pw, ph, limit, fit);
                head = chars > 0 ? run.getText().substring(0, chars) : null;
            }
            boolean nothing = take == 0 && head == null;
            if ((nothing || fit[0] < twoLines) && flow.canBreak()) {
                flow.breakPage(); // not even two lines fit here
                continue;
            }
            if (nothing || take == runs.size()) {
                out.add(flowOf(block, runs, pw)); // cannot be cut and has a page to itself: as it is
                flow.placed(rest);
                break;
            }
            List<Node> first = new ArrayList<>(runs.subList(0, take));
            List<Node> after = new ArrayList<>(runs.subList(head == null ? take : take + 1, runs.size()));
            if (head != null) {
                Text run = (Text) runs.get(take);
                first.add(textLike(run, head));
                String tail = run.getText().substring(head.length()).stripLeading();
                if (!tail.isEmpty()) {
                    after.add(0, textLike(run, tail));
                }
            } else if (!after.isEmpty() && after.get(0) instanceof Text lead && lead.getText() != null) {
                after.set(0, textLike(lead, lead.getText().stripLeading())); // the space the line broke at
            }
            double firstHeight = fit[0];
            double afterHeight = after.isEmpty() ? 0 : measureFlow(block, after, chain, pw, ph);
            if (!after.isEmpty() && afterHeight < twoLines && oneLine > 0) {
                // One line would be carried over. Leave a line more behind if two still stay; failing
                // that, and if it fits a page, take the whole paragraph over. Either way the cut is
                // not made: `runs` still holds the paragraph as it was, spaces and all.
                double shorter = firstHeight - (twoLines + 0.5 - oneLine) + 0.5;
                if (shorter >= twoLines && shorter < limit) {
                    cap = shorter;
                    continue;
                }
                if (flow.canBreak() && rest <= flow.freshLimit()) {
                    flow.breakPage();
                    continue;
                }
            }
            out.add(flowOf(block, first, pw));
            flow.placed(firstHeight);
            runs = after;
            rest = afterHeight;
        }
        if (out.size() == 1 && out.get(0) instanceof Pane only) {
            block.getChildren().setAll(new ArrayList<>(only.getChildren())); // uncut: the original, intact
            return List.of(block);
        }
        return out;
    }

    /** A clone of {@code template} holding {@code runs}. */
    private static Node flowOf(TextFlow template, List<Node> runs, double pw) {
        Pane piece = cloneShell(template, pw);
        piece.getChildren().addAll(runs);
        return piece;
    }

    private static double measureFlow(TextFlow template, List<Node> runs, List<Wrapper> chain, double pw, double ph) {
        return measurePrefix(template, chain, runs, 0, runs.size(), pw, ph);
    }

    /** The height of {@code template} holding {@code sample} set in its own text; 0 when it has no text. */
    private static double linesHeight(
            TextFlow template, List<Node> runs, String sample, List<Wrapper> chain, double pw, double ph) {
        for (Node run : runs) {
            if (run instanceof Text text) {
                return measureFlow(template, List.of(textLike(text, sample)), chain, pw, ph);
            }
        }
        return 0;
    }

    /**
     * How many characters of {@code run} — whole words — still fit {@code limit} when it follows {@code lead}
     * in a clone of {@code template}; 0 when not even its first word does. Leaves the height in {@code fit[0]}.
     */
    private static int wordsThatFit(
            TextFlow template,
            List<Node> lead,
            Text run,
            List<Wrapper> chain,
            double pw,
            double ph,
            double limit,
            double[] fit) {
        String all = run.getText();
        List<Node> candidate = new ArrayList<>(lead);
        candidate.add(run);
        int lo = 0;
        int hi = all.length() - 1; // the whole run is known not to fit
        while (lo < hi) {
            int mid = wordBoundary(all, (lo + hi + 1) / 2);
            if (mid <= lo) {
                break; // no boundary left to try between lo and hi
            }
            candidate.set(lead.size(), textLike(run, all.substring(0, mid)));
            double h = measureFlow(template, candidate, chain, pw, ph);
            if (h <= limit) {
                lo = mid;
                fit[0] = h;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** {@code len} walked back to the end of the last whole word of {@code all}; 0 when there is none. */
    private static int wordBoundary(String all, int len) {
        int p = Math.min(len, all.length());
        if (p >= all.length()) {
            return all.length();
        }
        while (p > 0 && all.charAt(p) != ' ') {
            p--;
        }
        return p;
    }

    /**
     * A {@code Text} carrying {@code s} with the template's styling, so the split is invisible.
     *
     * <p>The font is deliberately <b>not</b> copied. It comes from CSS through the style classes, and a font
     * set from code as well does not survive the move from the measuring scene to the page: the copy fell
     * back to the toolkit default (13px against the page's 14px), so a split paragraph was measured at one
     * size and printed, visibly smaller, at another.
     */
    private static Text textLike(Text template, String s) {
        Text t = new Text(s);
        t.getStyleClass().setAll(template.getStyleClass());
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
        out.add(new Wrapper(pane, leadWidth, -1));
        return out;
    }

    /**
     * An ancestor a piece will be re-wrapped in, plus the width taken from it by a leading sibling.
     *
     * <p>{@code leadWidth} exists for the list-item row: its content shares the row with the bullet, so a
     * candidate measured in an empty row clone gets the full page width, comes out short, and the assembled
     * row then overflows. Measured on this repo's CLAUDE.md, ignoring it left 244 of a 296-page list's
     * pieces over the page — each by only ~20%, which is exactly what a missing bullet column costs.
     *
     * <p>{@code column} is the same idea for a table cell: a cell is only as wide as its column, so a piece
     * of one is measured in that column of a clone of its table ({@code -1} for every other wrapper).
     */
    private record Wrapper(Pane template, double leadWidth, int column) {}

    /**
     * Each child's own height inside {@code pane}, or null when the container's height is not the sum of
     * them — which is the whole point of the distinction.
     *
     * <p>A {@code VBox} stacks its children at their preferred heights, and every child is laid out at the
     * same width whatever its siblings are (the clone is pinned to the printable width), so a child measured
     * alone is the same height it will be in a group: the group's height is arithmetic. A {@code TextFlow}
     * is the opposite — its runs <em>wrap</em>, so summing them is meaningless — and an {@code HBox} puts
     * its children side by side. Both of those keep measuring for real.
     *
     * <p>This is the difference between measuring each child once and laying out a candidate group per step
     * of a binary search, per emitted piece. Measured on this repo's CLAUDE.md, where pagination is 94%
     * layout: 3,728 measurements over 1.31M cumulative nodes.
     */
    private static double[] ownHeights(Pane pane, List<Wrapper> chain, List<Node> children, double pw, double ph) {
        if (!(pane instanceof VBox)) {
            return null;
        }
        double padding = pane.getPadding().getTop() + pane.getPadding().getBottom();
        double[] own = new double[children.size()];
        for (int i = 0; i < children.size(); i++) {
            Pane solo = cloneShell(pane, pw);
            solo.getChildren().add(children.get(i));
            own[i] = measureWrapped(solo, chain, pw, ph) - padding;
            solo.getChildren().clear();
            if (!(own[i] > 0)) {
                return null; // not the shape assumed above; measure for real instead of guessing
            }
        }
        return own;
    }

    /** The predicted height of children {@code [from, from+count)} stacked in {@code pane}. */
    private static double stackedHeight(Pane pane, double[] own, int from, int count) {
        double h = pane.getPadding().getTop() + pane.getPadding().getBottom();
        for (int i = from; i < from + count; i++) {
            h += own[i];
        }
        return h + Math.max(0, count - 1) * ((VBox) pane).getSpacing();
    }

    /**
     * The largest number of children from {@code from} whose piece still fits {@code limit}; 0 if none do.
     * The piece's measured height is left in {@code fit[0]}.
     *
     * <p>With {@code own} heights available the count is arithmetic, and then <b>verified once</b> against a
     * real layout, backing off while it overflows. The arithmetic is a hint, not an oracle: a piece that
     * overflowed the page is precisely the bug this whole splitter exists to fix, so it is not something to
     * take on trust from a model of how VBox lays out. Without {@code own} it is a binary search, each step
     * a real layout of the candidate group.
     */
    private static int largestPrefixThatFits(
            Pane template,
            List<Wrapper> chain,
            List<Node> children,
            int from,
            double[] own,
            double pw,
            double ph,
            double limit,
            double[] fit) {
        int remaining = children.size() - from;
        if (own != null) {
            int lo = 0;
            while (lo < remaining && stackedHeight(template, own, from, lo + 1) <= limit) {
                lo++;
            }
            while (lo > 0 && (fit[0] = measurePrefix(template, chain, children, from, lo, pw, ph)) > limit) {
                lo--; // the estimate was optimistic — step back until it really fits
            }
            return lo;
        }
        int lo = 0;
        int hi = remaining;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            double h = measurePrefix(template, chain, children, from, mid, pw, ph);
            if (h <= limit) {
                lo = mid;
                fit[0] = h;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** Lays out {@code count} children in a clone of {@code template} and hands them back detached. */
    private static double measurePrefix(
            Pane template, List<Wrapper> chain, List<Node> children, int from, int count, double pw, double ph) {
        Pane probe = cloneShell(template, pw);
        probe.getChildren().addAll(children.subList(from, from + count));
        double h = measureWrapped(probe, chain, pw, ph);
        probe.getChildren().clear();
        return h;
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
            if (shell instanceof GridPane table && w.column() >= 0) {
                if (outer instanceof Region cell) {
                    asCell(cell);
                }
                table.add(outer, w.column(), 0);
            } else {
                shell.getChildren().add(outer);
            }
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
    private static List<Node> splitListItem(HBox row, List<Wrapper> chain, double pw, double ph, Flow flow, int depth) {
        Node marker = row.getChildren().get(0);
        Node content = row.getChildren().get(1);
        double markerWidth = marker.getLayoutBounds().getWidth();
        row.getChildren().clear();
        List<Wrapper> inner = append(chain, row, markerWidth);
        List<Node> parts = split(content, measureWrapped(content, inner, pw, ph), inner, pw, ph, flow, depth + 1);
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

    // --- tables --------------------------------------------------------------------------------------

    /** The smallest text a wide table is shrunk to. Below this it is not worth printing; the cells wrap. */
    private static final double MIN_TABLE_FONT = 7;

    /** A dense cell's left + right padding and border, matching {@code .md-table-dense} in app.css. */
    private static final double DENSE_CELL_INSETS = 3 + 3 + 1 + 1;

    /**
     * A table at or above this many rows is taken apart before anything is laid out ({@link #prepareTables}).
     * Comfortably more than a page holds, so a table that fits a page is never touched.
     */
    private static final int LONG_TABLE_ROWS = 64;

    /** How many rows of a long table are laid out together to measure them. */
    private static final int MEASURE_CHUNK_ROWS = 32;

    /** Node-property key under which a long table's detached rows travel to {@link #splitTable}. */
    private static final Object TABLE_ROWS_KEY = new Object();

    /**
     * The rows of a table (the header row first, when it has one), each row's laid-out height, and what the
     * table costs beyond its rows — its own insets plus whatever it is nested in.
     */
    private record TableRows(List<List<Node>> rows, double[] heights, double overhead) {}

    /** What a table's cells need across: per column, the widest unbreakable piece and the widest whole line. */
    private static final class ColumnNeeds {
        final double[] word;
        final double[] line;
        double cellInsets;
        double fontSize;

        ColumnNeeds(int cols) {
            word = new double[cols];
            line = new double[cols];
        }

        /** Counts one laid-out cell (CSS applied, so its font and insets are the real ones). */
        void add(Node cell) {
            int col = columnOf(cell);
            if (col >= word.length || !(cell instanceof TextFlow flow)) {
                return;
            }
            cellInsets = flow.getInsets().getLeft() + flow.getInsets().getRight();
            double text = Math.max(0, flow.prefWidth(-1) - cellInsets);
            line[col] = Math.max(line[col], text);
            word[col] = Math.max(word[col], text * longestWordShare(flow));
            if (fontSize == 0
                    && !flow.getChildren().isEmpty()
                    && flow.getChildren().get(0) instanceof Text t) {
                fontSize = t.getFont().getSize();
            }
        }
    }

    /**
     * Gets every table ready to be measured: a very long one is taken apart so it is never laid out whole,
     * and one with more columns than the page has width for is given room.
     *
     * <p><b>Long tables.</b> A {@code GridPane} works out its row metrics by scanning all of its children
     * for each row, so one layout of a table costs rows × cells. That is nothing for the tables people write
     * and ruinous for a CSV: a 5,000-row, 5-column table takes seconds per layout, and pagination lays a
     * document out several times. So a top-level table of {@link #LONG_TABLE_ROWS} rows or more has its rows
     * detached here and measured {@link #MEASURE_CHUNK_ROWS} at a time in a scratch copy of the table — the
     * columns are percentages of the same width, so a row is the same height there as in the whole. The
     * rows and their heights ride along on the (now empty) table for {@link #splitTable} to deal out.
     * Only top-level tables, because that is where a scratch copy has the table's real width; a long table
     * nested in a list or a quote is still split correctly, just laid out whole first.
     *
     * <p><b>Wide tables.</b> A table's columns share the page width, so a 20-column CSV on portrait Letter
     * gets about 23pt a column — less than the cells' own padding — and every cell wrapped to one character
     * a line: ten rows measured several pages tall. Such a table gets tighter cells, columns sized by what
     * each actually holds, and text reduced just far enough that no word has to break (never below
     * {@link #MIN_TABLE_FONT}; past that long words do break, but a few letters at a time, not one).
     * "Too wide" is judged by each column's longest <em>word</em>, not its longest line: a cell holding a
     * paragraph is meant to wrap, and must not shrink the whole table. A table whose words all fit is left
     * exactly as the preview shows it.
     */
    private static void prepareTables(VBox content, double pw, double ph) {
        List<GridPane> tables = new ArrayList<>();
        collectTables(content, tables);
        if (tables.isEmpty()) {
            return;
        }
        java.util.Map<GridPane, List<List<Node>>> detached = new java.util.IdentityHashMap<>();
        for (GridPane table : tables) {
            if (table.getParent() == content) {
                List<List<Node>> rows = rowsOf(table);
                if (rows.size() >= LONG_TABLE_ROWS) {
                    table.getChildren().clear();
                    detached.put(table, rows);
                }
            }
        }
        measureBlockHeights(content, pw, ph); // applies CSS and gives every table its width on the page
        for (GridPane table : tables) {
            int cols = table.getColumnConstraints().size();
            ColumnNeeds needs = new ColumnNeeds(cols);
            List<List<Node>> rows = detached.get(table);
            TableRows measured = null;
            if (rows == null) {
                table.getChildren().forEach(needs::add);
            } else {
                measured = measureRows(table, rows, needs, pw, ph);
            }
            if (fitColumns(table, needs, table.getWidth()) && rows != null) {
                measured = measureRows(table, rows, null, pw, ph); // the rows are a different height now
            }
            if (measured != null) {
                table.getProperties().put(TABLE_ROWS_KEY, measured);
            }
        }
    }

    /**
     * Measures detached {@code rows} a chunk at a time in a scratch copy of {@code table}, feeding every
     * cell to {@code needs} (when given) while it is laid out.
     */
    private static TableRows measureRows(
            GridPane table, List<List<Node>> rows, ColumnNeeds needs, double pw, double ph) {
        double[] heights = new double[rows.size()];
        double overhead = 0;
        for (int from = 0; from < rows.size(); from += MEASURE_CHUNK_ROWS) {
            int to = Math.min(rows.size(), from + MEASURE_CHUNK_ROWS);
            GridPane probe = (GridPane) cloneShell(table, pw);
            for (int r = from; r < to; r++) {
                addRow(probe, rows.get(r), r - from);
            }
            double stacked = probe.getVgap() * (to - from - 1);
            double whole = measureOne(probe, pw, ph);
            for (int r = from; r < to; r++) {
                heights[r] = rowHeight(rows.get(r));
                stacked += heights[r];
                if (needs != null) {
                    rows.get(r).forEach(needs::add);
                }
            }
            overhead = Math.max(0, whole - stacked);
            probe.getChildren().clear();
        }
        return new TableRows(rows, heights, overhead);
    }

    /**
     * Gives {@code table} tighter cells, need-sized columns and, if it must, smaller text — when its columns'
     * longest words do not fit {@code available}. Returns whether it changed anything.
     */
    private static boolean fitColumns(GridPane table, ColumnNeeds needs, double available) {
        int cols = needs.word.length;
        double needed = cols * needs.cellInsets;
        for (double w : needs.word) {
            needed += w;
        }
        if (cols == 0 || needed <= available + 0.5 || !(available > 0) || !(needs.fontSize > 0)) {
            return false;
        }
        table.getStyleClass().add("md-table-dense");
        double room = Math.max(1, available - cols * DENSE_CELL_INSETS);
        // 3% under the exact ratio: glyph widths do not scale perfectly linearly with the font size.
        double scale = Math.min(1, room / Math.max(1, needed - cols * needs.cellInsets) * 0.97);
        double size = Math.max(MIN_TABLE_FONT, needs.fontSize * scale);
        if (size < needs.fontSize) {
            table.setStyle("-fx-font-size: " + size + "px;");
        }
        // Each column gets its longest word; whatever is left goes to the columns that would otherwise wrap.
        double shrink = size / needs.fontSize;
        double used = 0;
        double wanted = 0;
        for (int c = 0; c < cols; c++) {
            used += DENSE_CELL_INSETS + needs.word[c] * shrink;
            wanted += (needs.line[c] - needs.word[c]) * shrink;
        }
        double spare = Math.max(0, available - used);
        double total = used + (wanted > 0 ? spare : 0);
        for (int c = 0; c < cols; c++) {
            double width = DENSE_CELL_INSETS + needs.word[c] * shrink;
            if (wanted > 0) {
                width += spare * (needs.line[c] - needs.word[c]) * shrink / wanted;
            }
            table.getColumnConstraints().get(c).setPercentWidth(width / total * 100.0);
        }
        return true;
    }

    /**
     * How much of a cell's unwrapped width its longest unbreakable piece takes, 0–1, judged by character
     * count: text breaks at spaces, and inline code (a {@code Label}) or an image does not break at all.
     */
    private static double longestWordShare(TextFlow cell) {
        int total = 0;
        int longest = 0;
        for (Node run : cell.getChildren()) {
            if (run instanceof Text text && text.getText() != null) {
                String s = text.getText();
                total += s.length();
                int start = 0;
                for (int i = 0; i <= s.length(); i++) {
                    if (i == s.length() || Character.isWhitespace(s.charAt(i))) {
                        longest = Math.max(longest, i - start);
                        start = i + 1;
                    }
                }
            } else if (run instanceof Label label && label.getText() != null) {
                total += label.getText().length();
                longest = Math.max(longest, label.getText().length());
            } else {
                return 1; // an image or a formula: no characters to go by, so take the cell as one piece
            }
        }
        return total == 0 ? 1 : (double) longest / total;
    }

    /** {@code table}'s cells grouped by row, in row order. */
    private static List<List<Node>> rowsOf(GridPane table) {
        List<List<Node>> rows = new ArrayList<>();
        for (Node cell : table.getChildren()) {
            int r = rowOf(cell);
            while (rows.size() <= r) {
                rows.add(new ArrayList<>());
            }
            rows.get(r).add(cell);
        }
        return rows;
    }

    private static void collectTables(Node node, List<GridPane> out) {
        if (node instanceof GridPane grid && grid.getStyleClass().contains("md-table")) {
            out.add(grid);
        } else if (node instanceof Pane pane) {
            for (Node child : pane.getChildren()) {
                collectTables(child, out);
            }
        }
    }

    private static int rowOf(Node cell) {
        Integer r = GridPane.getRowIndex(cell);
        return r == null ? 0 : r;
    }

    private static int columnOf(Node cell) {
        Integer c = GridPane.getColumnIndex(cell);
        return c == null ? 0 : c;
    }

    /**
     * Splits an over-tall table between its rows: each piece is a table again — same columns, same styling —
     * with the header row repeated at its top.
     *
     * <p>A table is a {@code GridPane}, and the generic splitter knows nothing about rows: it regrouped the
     * grid's children, the cells, into plain boxes, so every cell became a full-width row of its own. A
     * 120-row, 3-column table printed as 17 pages of "#, Name, Value, 1, name 1, value 1, …" one under the
     * other. It was slow as well: with no arithmetic for a grid it fell back to a binary search with a full
     * layout per step, per piece — 156s on the FX thread for a 5,000-row CSV.
     *
     * <p>Here the table is laid out <b>once</b> (or, for a long one, not at all: {@link #prepareTables} has
     * already measured its rows in chunks), each row's height read off its cells, and rows are dealt onto
     * pieces by addition. Every piece is then measured for real and a row handed back while it
     * overflows — the same "arithmetic is a hint, not an oracle" rule as {@link #largestPrefixThatFits} — but
     * that is one layout of one page's worth of rows, so the whole thing stays linear in the row count.
     *
     * <p>A single row taller than the page (one cell holding paragraphs of text) is cut across several rows
     * first ({@link #splitTallRow}), so no text is lost and nothing is scaled.
     */
    private static List<Node> splitTable(
            GridPane grid, double height, List<Wrapper> chain, double pw, double ph, Flow flow, int depth) {
        TableRows table = (TableRows) grid.getProperties().remove(TABLE_ROWS_KEY);
        if (table == null) {
            double full = measureWrapped(grid, chain, pw, ph); // the one layout of the whole table
            List<List<Node>> all = rowsOf(grid);
            double[] measured = new double[all.size()];
            double stacked = grid.getVgap() * Math.max(0, all.size() - 1);
            for (int r = 0; r < all.size(); r++) {
                measured[r] = rowHeight(all.get(r));
                stacked += measured[r];
            }
            table = new TableRows(all, measured, Math.max(0, full - stacked));
        }
        List<List<Node>> rows = table.rows();
        double[] heights = table.heights();
        double overhead = table.overhead();
        int headerRows = !rows.isEmpty() && isHeaderRow(rows.get(0)) ? 1 : 0;
        if (rows.size() - headerRows < 1) {
            flow.placed(height);
            return List.of(grid); // nothing but a header: no row boundary to cut at
        }
        double vgap = grid.getVgap();
        double headerHeight = headerRows == 0 ? 0 : heights[0] + vgap;
        double budget = ph - overhead - headerHeight; // what a whole page leaves for body rows
        grid.getChildren().clear();

        // Cut any row that cannot fit a page by itself into several that can.
        List<List<Node>> body = new ArrayList<>();
        List<Double> bodyHeights = new ArrayList<>();
        for (int r = headerRows; r < rows.size(); r++) {
            if (heights[r] <= budget || !(budget > 0)) {
                body.add(rows.get(r));
                bodyHeights.add(heights[r]);
                continue;
            }
            for (List<Node> part : splitTallRow(grid, rows.get(r), chain, pw, ph - headerHeight, depth)) {
                GridPane probe = (GridPane) cloneShell(grid, pw);
                addRow(probe, part, 0);
                bodyHeights.add(measureWrapped(probe, chain, pw, ph) - overhead);
                probe.getChildren().clear();
                body.add(part);
            }
        }

        List<Node> header = headerRows == 0 ? List.of() : rows.get(0);
        List<Node> out = new ArrayList<>();
        int from = 0;
        while (from < body.size()) {
            double limit = flow.limit(); // this piece's share: what is left of the page it starts on
            double room = limit - overhead - headerHeight;
            int take = 0;
            double used = 0;
            while (from + take < body.size() && used + bodyHeights.get(from + take) <= room) {
                used += bodyHeights.get(from + take) + vgap;
                take++;
            }
            if (take < Math.min(MIN_TABLE_ROWS, body.size() - from) && flow.canBreak()) {
                flow.breakPage(); // a header over one row, or over none: start the table on the next page
                continue;
            }
            take = Math.max(1, take); // a row that still cannot fit goes alone, for the last-resort scale
            GridPane piece = (GridPane) cloneShell(grid, pw);
            int at = 0;
            if (!header.isEmpty()) {
                addRow(piece, out.isEmpty() ? header : copyRow(header), at++);
            }
            for (int i = 0; i < take; i++) {
                addRow(piece, body.get(from + i), at++);
            }
            double pieceHeight = measureWrapped(piece, chain, pw, ph);
            while (take > 1 && pieceHeight > limit) {
                piece.getChildren().removeAll(body.get(from + --take)); // the estimate was optimistic
                pieceHeight = measureWrapped(piece, chain, pw, ph);
            }
            flow.placed(pieceHeight);
            out.add(piece);
            from += take;
        }
        return out;
    }

    private static boolean isHeaderRow(List<Node> row) {
        return !row.isEmpty() && row.get(0).getStyleClass().contains("md-table-header");
    }

    /** A row is as tall as its tallest cell; valid once the table has been laid out. */
    private static double rowHeight(List<Node> row) {
        double h = 0;
        for (Node cell : row) {
            h = Math.max(h, cell.getLayoutBounds().getHeight());
        }
        return h;
    }

    private static void addRow(GridPane table, List<Node> cells, int row) {
        for (Node cell : cells) {
            table.add(cell, columnOf(cell), row);
        }
    }

    /**
     * Cuts one over-tall table row into several rows that each fit {@code ph}: every cell is split as the
     * block it is (a paragraph of runs), in its own column, and part <i>n</i> of each cell makes up row
     * <i>n</i>. A cell that runs out before its neighbours is continued with empty cells, so the column
     * borders carry on down the page.
     */
    private static List<List<Node>> splitTallRow(
            GridPane grid, List<Node> row, List<Wrapper> chain, double pw, double ph, int depth) {
        List<List<Node>> parts = new ArrayList<>();
        int count = 1;
        for (Node cell : row) {
            List<Wrapper> inColumn = new ArrayList<>(chain);
            inColumn.add(new Wrapper(grid, 0, columnOf(cell)));
            List<Node> pieces =
                    split(cell, measureWrapped(cell, inColumn, pw, ph), inColumn, pw, ph, Flow.fixed(ph), depth + 1);
            for (Node piece : pieces) {
                GridPane.setColumnIndex(piece, columnOf(cell));
                if (piece instanceof Region region) {
                    asCell(region);
                }
            }
            parts.add(pieces);
            count = Math.max(count, pieces.size());
        }
        List<List<Node>> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<Node> cells = new ArrayList<>();
            for (int c = 0; c < row.size(); c++) {
                List<Node> pieces = parts.get(c);
                Node cell = i < pieces.size() ? pieces.get(i) : emptyCellLike(row.get(c), columnOf(pieces.get(0)));
                cells.add(cell);
            }
            out.add(cells);
        }
        return out;
    }

    /**
     * Undoes the page-width pin {@link #cloneShell} puts on every piece: a table cell is as wide as its
     * column, and a preferred width of the whole page would be read by the grid as the cell's own.
     */
    private static void asCell(Region cell) {
        cell.setPrefWidth(Region.USE_COMPUTED_SIZE);
        cell.setMaxWidth(Double.MAX_VALUE);
    }

    private static Node emptyCellLike(Node cell, int column) {
        TextFlow empty = new TextFlow();
        empty.getStyleClass().setAll(cell.getStyleClass());
        empty.setMaxWidth(Double.MAX_VALUE);
        GridPane.setColumnIndex(empty, column);
        return empty;
    }

    /** A copy of a header row, for the top of each continuation piece. */
    private static List<Node> copyRow(List<Node> row) {
        List<Node> out = new ArrayList<>();
        for (Node cell : row) {
            Node copy = copyInline(cell);
            GridPane.setColumnIndex(copy, columnOf(cell));
            out.add(copy);
        }
        return out;
    }

    /**
     * A copy of a table cell or of one inline node inside it — text, inline code, an image or a formula,
     * which is everything the renderer puts in a cell. Anything else is copied as an empty node rather than
     * moved, because a JavaFX node can only be in one place.
     */
    private static Node copyInline(Node node) {
        if (node instanceof Text text) {
            return textLike(text, text.getText());
        }
        if (node instanceof Label label) {
            Label copy = new Label(label.getText());
            copy.getStyleClass().setAll(label.getStyleClass());
            return copy;
        }
        if (node instanceof ImageView view) {
            ImageView copy = new ImageView(view.getImage());
            copy.getStyleClass().setAll(view.getStyleClass());
            copy.setPreserveRatio(view.isPreserveRatio());
            copy.setSmooth(view.isSmooth());
            copy.setFitWidth(view.getFitWidth());
            copy.setFitHeight(view.getFitHeight());
            return copy;
        }
        if (node instanceof TextFlow flow) {
            TextFlow copy = new TextFlow();
            copy.getStyleClass().setAll(flow.getStyleClass());
            copy.setTextAlignment(flow.getTextAlignment());
            copy.setLineSpacing(flow.getLineSpacing());
            copy.setMaxWidth(flow.getMaxWidth());
            for (Node child : flow.getChildren()) {
                copy.getChildren().add(copyInline(child));
            }
            return copy;
        }
        Region blank = new Region();
        blank.getStyleClass().setAll(node.getStyleClass());
        return blank;
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
        } else if (template instanceof GridPane grid) {
            GridPane g = new GridPane();
            g.setHgap(grid.getHgap());
            g.setVgap(grid.getVgap());
            for (ColumnConstraints cc : grid.getColumnConstraints()) {
                ColumnConstraints c = new ColumnConstraints();
                c.setPercentWidth(cc.getPercentWidth());
                c.setHgrow(cc.getHgrow());
                g.getColumnConstraints().add(c);
            }
            g.setStyle(grid.getStyle()); // a wide table's reduced font size (fitColumns) is set inline
            copy = g;
        } else {
            copy = new VBox();
        }
        copy.getStyleClass().setAll(template.getStyleClass());
        Object origin = template.getProperties().get(ORIGIN_KEY);
        copy.getProperties().put(ORIGIN_KEY, origin == null ? template : origin);
        copy.setPadding(template.getPadding());
        copy.setMaxWidth(pw);
        copy.setPrefWidth(pw);
        return copy;
    }

    /** The laid-out height of one detached node at the printable width; leaves it detached. */
    public static double measureOne(Node node, double pw, double ph) {
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
            root.getStyleClass().addAll(LIGHT_PAGE_CLASSES);
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
