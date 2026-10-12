package com.editora.editor;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;

import com.editora.markdown.MarkdownToc;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;

/**
 * The rendered Markdown document of one buffer's live preview: its nodes, which source lines each top-level
 * block came from, and where each heading is.
 *
 * <p><b>Why it keeps the nodes.</b> The preview used to throw its whole node tree away 250 ms after every
 * keystroke and build, style and lay out a new one on the FX thread: ~0.5 s of frozen UI per pause in typing
 * for a 5,000-line document, seconds for 20,000. A keystroke changes one block. {@link #plan} (off the FX
 * thread) cuts the document into its top-level blocks and gives each a key — its exact source text — and
 * {@link #show} keeps every node whose key is unchanged, in place, and builds only the others. Nodes that
 * stay in the scene keep their CSS and layout, which was nine tenths of the cost.
 *
 * <p><b>Why it keeps the lines.</b> A scroll <em>fraction</em> means different places in the editor and in the
 * preview as soon as one tall image or code block sits above. With each block's source lines and its laid-out
 * position, a source line maps to a preview offset and back ({@link #yOfLine}, {@link #lineAtY}): the split
 * view's scroll sync, the position kept across a re-render and across a mode switch all go through that map,
 * and {@link #scrollToAnchor} finds a heading by its GitHub slug.
 */
final class MarkdownPreview {

    /** One top-level unit of a parsed document: a block, or a {@code <details>} with the blocks it folds. */
    record Unit(
            org.commonmark.node.Node first,
            org.commonmark.node.Node last,
            String key,
            int startLine,
            int endLine,
            String slug) {}

    /** A document cut into units, ready to {@link #show}. Built off the FX thread. */
    record Plan(List<Unit> units, Path baseDir) {}

    /** A rendered unit: {@code node} is null for one that shows nothing (a comment, a link definition). */
    private record Entry(String key, Node node, int startLine, int endLine, String slug) {}

    private final BlockColumn column = MarkdownRenderer.styleColumn(new BlockColumn());
    private final StackPane root = MarkdownRenderer.wrapColumn(column);
    private List<Entry> entries = List.of();
    private String contextSignature = "";
    private Consumer<String> linkOpener = u -> {};
    private ScrollPane pane;
    private int lastBuilt;
    private int lastReused;
    /** While positive (a {@code System.nanoTime} deadline), late layout — images arriving — re-anchors here. */
    private long anchorUntil;

    private double anchorLine = -1;

    /** With more new blocks than this in one render, styling the document once is cheaper than each block. */
    private static final int RESTYLE_ALL_ABOVE = 24;

    /** For {@link #show}: keep on screen what is on screen (the block at the top stays at the top). */
    static final double KEEP_VIEW = -1;

    /** How long after a render late-arriving content (images, diagrams) keeps the anchored line in place. */
    private static final long ANCHOR_HOLD_NANOS = 1_500_000_000L;

    MarkdownPreview() {
        root.heightProperty().addListener((o, was, now) -> {
            // The height changes in the middle of a layout pass: re-anchor once that pass is over.
            if (holdsAnchor() && !reanchorQueued) {
                reanchorQueued = true;
                javafx.application.Platform.runLater(() -> {
                    reanchorQueued = false;
                    if (holdsAnchor()) {
                        setScrollY(yOfLine(anchorLine));
                    }
                });
            }
        });
    }

    private boolean reanchorQueued;

    private boolean holdsAnchor() {
        return anchorLine >= 0 && System.nanoTime() < anchorUntil && pane != null && pane.getContent() == root;
    }

    /** The node that is the scroll pane's content while this document is showing. Stable across renders. */
    Node root() {
        return root;
    }

    /** Whether this document is what {@code pane} currently shows. */
    boolean isShowing() {
        return pane != null && pane.getContent() == root && !entries.isEmpty();
    }

    /** Counts the renders shown; observable, so whoever waits for a render need not poll. */
    private final javafx.beans.property.ReadOnlyIntegerWrapper shows =
            new javafx.beans.property.ReadOnlyIntegerWrapper();

    javafx.beans.property.ReadOnlyIntegerProperty showsProperty() {
        return shows.getReadOnlyProperty();
    }

    /** Blocks built by the last {@link #show}, and blocks it kept from the render before. */
    int lastBuilt() {
        return lastBuilt;
    }

    int lastReused() {
        return lastReused;
    }

    /** Binds the scroll pane this document scrolls in; a gesture of the user's ends any anchoring. */
    void attach(ScrollPane scrollPane) {
        this.pane = scrollPane;
        scrollPane.addEventFilter(javafx.scene.input.ScrollEvent.ANY, e -> anchorUntil = 0);
        scrollPane.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> anchorUntil = 0);
        scrollPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> anchorUntil = 0);
    }

    /** Forgets every node (the preview was closed, or what it showed is no longer this document). */
    void clear() {
        buildSeq++;
        entries = List.of();
        column.getChildren().clear();
        contextSignature = "";
        anchorUntil = 0;
    }

    // --- planning (pure, off the FX thread) -----------------------------------------------------

    /** Parses {@code markdown} and cuts it into keyed units. Pure CPU plus a {@code stat} per local image. */
    static Plan plan(String markdown, Path baseDir) {
        String md = markdown == null ? "" : markdown;
        org.commonmark.node.Node ast = MarkdownRenderer.parseForRender(md);
        String definitions = definitions(ast);
        Map<String, Integer> slugs = new HashMap<>();
        List<Unit> units = new ArrayList<>();
        for (org.commonmark.node.Node n = ast.getFirstChild(); n != null; ) {
            org.commonmark.node.Node last = MarkdownRenderer.groupEnd(n, false);
            SourceSpan from = firstSpan(n);
            SourceSpan to = lastSpan(last);
            String key = null;
            int start = -1;
            int end = -1;
            if (from != null && to != null && to.getInputIndex() + to.getLength() <= md.length()) {
                start = from.getLineIndex();
                end = Math.max(start, to.getLineIndex());
                String text = md.substring(from.getInputIndex(), to.getInputIndex() + to.getLength());
                key = n.getClass().getSimpleName()
                        + '\u0000'
                        + text
                        + dependencies(n, last, text, definitions, baseDir);
            }
            String slug = n instanceof Heading h ? unique(MarkdownToc.slug(plainText(h)), slugs) : null;
            units.add(new Unit(n, last, key, start, end, slug));
            n = last.getNext();
        }
        return new Plan(units, baseDir);
    }

    private static SourceSpan firstSpan(org.commonmark.node.Node n) {
        List<SourceSpan> spans = n.getSourceSpans();
        return spans.isEmpty() ? null : spans.get(0);
    }

    private static SourceSpan lastSpan(org.commonmark.node.Node n) {
        List<SourceSpan> spans = n.getSourceSpans();
        return spans.isEmpty() ? null : spans.get(spans.size() - 1);
    }

    /**
     * What a unit's rendering depends on besides its own text: the document's link and footnote definitions
     * when it could use one (it contains a {@code [}), and the current content of the local image files it
     * shows — so an image exported again is a changed block.
     */
    private static String dependencies(
            org.commonmark.node.Node first,
            org.commonmark.node.Node last,
            String text,
            String definitions,
            Path baseDir) {
        StringBuilder sb = new StringBuilder();
        if (text.indexOf('[') >= 0) {
            sb.append('\u0000').append(definitions);
        }
        if (text.indexOf("![") >= 0 || text.contains("<img") || text.contains("<IMG")) {
            for (String dest : imageDestinations(first, last)) {
                String url = MarkdownRenderer.resolveUrl(dest, baseDir);
                sb.append('\u0000').append(PreviewImageLoader.fileStamp(url));
            }
        }
        return sb.toString();
    }

    /** The destinations of every image in the siblings {@code first}…{@code last}, Markdown and HTML. */
    static List<String> imageDestinations(org.commonmark.node.Node first, org.commonmark.node.Node last) {
        List<String> out = new ArrayList<>();
        AbstractVisitor v = new AbstractVisitor() {
            @Override
            public void visit(Image image) {
                out.add(image.getDestination());
                visitChildren(image);
            }

            @Override
            public void visit(HtmlBlock html) {
                html(html.getLiteral());
            }

            @Override
            public void visit(HtmlInline html) {
                html(html.getLiteral());
            }

            private void html(String literal) {
                for (MarkdownHtml.Token t : MarkdownHtml.tokens(literal)) {
                    if (t instanceof MarkdownHtml.Tag tag && tag.name().equals("img") && tag.attr("src") != null) {
                        out.add(tag.attr("src"));
                    }
                }
            }
        };
        for (org.commonmark.node.Node n = first; n != null; n = n.getNext()) {
            n.accept(v);
            if (n == last) {
                break;
            }
        }
        return out;
    }

    /** Every link reference definition of the document, as one string: a change re-renders their users. */
    private static String definitions(org.commonmark.node.Node ast) {
        StringBuilder sb = new StringBuilder();
        ast.accept(new AbstractVisitor() {
            @Override
            public void visit(LinkReferenceDefinition d) {
                sb.append(d.getLabel())
                        .append('\u0001')
                        .append(d.getDestination())
                        .append('\u0001');
                sb.append(d.getTitle()).append('\u0002');
            }
        });
        return sb.toString();
    }

    /** A heading's text as a reader sees it (markup stripped, code kept) — what its slug is made from. */
    static String plainText(org.commonmark.node.Node node) {
        StringBuilder sb = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                sb.append(text.getLiteral());
            }

            @Override
            public void visit(Code code) {
                sb.append(code.getLiteral());
            }
        });
        return sb.toString();
    }

    /** GitHub's sequence for a repeated slug: {@code a}, {@code a-1}, {@code a-2} (as {@code MarkdownToc}). */
    static String unique(String base, Map<String, Integer> seen) {
        Integer n = seen.get(base);
        if (n == null) {
            seen.put(base, 0);
            return base;
        }
        seen.put(base, n + 1);
        return base + "-" + (n + 1);
    }

    /**
     * How many leading and trailing keys two renders have in common: {@code {prefix, suffix}}, never
     * overlapping. A null key matches nothing. Pure; unit-tested.
     */
    static int[] commonEnds(List<String> now, List<String> before) {
        int n = now.size();
        int m = before.size();
        int prefix = 0;
        while (prefix < n
                && prefix < m
                && now.get(prefix) != null
                && now.get(prefix).equals(before.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < n - prefix
                && suffix < m - prefix
                && now.get(n - 1 - suffix) != null
                && now.get(n - 1 - suffix).equals(before.get(m - 1 - suffix))) {
            suffix++;
        }
        return new int[] {prefix, suffix};
    }

    // --- showing (FX thread) ---------------------------------------------------------------------

    /**
     * Shows {@code plan} in the attached pane, building only the units that changed since the last one.
     * {@code dark} is the preview's own theme, or null to follow the app. {@code line} is the source line to
     * bring to the top afterwards (fractional), or {@link #KEEP_VIEW}.
     */
    void show(Plan plan, Consumer<String> onLinkClick, Boolean dark, double line) {
        this.linkOpener = onLinkClick == null ? u -> {} : onLinkClick;
        long seq = ++buildSeq; // whatever an earlier render still had to build is this render's business now
        // What is at the top now, as a node: text typed above it renumbers its lines but does not replace it.
        Node topNode = null;
        double topOffset = 0;
        if (line < 0 && isShowing()) {
            double y = scrollY();
            for (Entry e : entries) {
                if (e.node() != null
                        && top(e.node()) + e.node().getBoundsInParent().getHeight() > y) {
                    topNode = e.node();
                    topOffset = y - top(topNode);
                    break;
                }
            }
        }
        String signature = dark + "|" + MathImages.epoch() + "|" + MermaidImages.epoch() + "|"
                + MermaidImages.isEnabled() + "|" + plan.baseDir();
        if (!signature.equals(contextSignature)) {
            entries = List.of(); // a theme, a tool or the folder changed: every node is out of date
            column.getChildren().clear();
            contextSignature = signature;
        }
        MarkdownRenderer.RenderContext ctx = MarkdownRenderer.previewContext(plan.baseDir(), this::follow, dark);
        List<Unit> units = plan.units();
        List<Entry> before = entries;
        int[] ends = commonEnds(
                units.stream().map(Unit::key).toList(),
                before.stream().map(Entry::key).toList());
        int prefix = ends[0];
        int suffix = ends[1];
        int n = units.size();
        int m = before.size();

        Map<String, ArrayDeque<Entry>> pool = new HashMap<>();
        List<Node> oldMiddle = new ArrayList<>();
        for (int i = prefix; i < m - suffix; i++) {
            Entry e = before.get(i);
            if (e.key() != null) {
                pool.computeIfAbsent(e.key(), k -> new ArrayDeque<>()).add(e);
            }
            if (e.node() != null) {
                oldMiddle.add(e.node());
            }
        }
        List<Entry> now = new ArrayList<>(n);
        List<Node> newMiddle = new ArrayList<>();
        List<Node> fresh = new ArrayList<>();
        int built = 0;
        int shown = n; // units [shown, n) are built later, a slice per pulse
        for (int i = 0; i < n; i++) {
            Unit u = units.get(i);
            if (built >= SLICE && suffix == 0 && i >= prefix) {
                shown = i;
                break;
            }
            Entry kept = null;
            if (i < prefix) {
                kept = before.get(i);
            } else if (i >= n - suffix) {
                kept = before.get(m - (n - i));
            } else if (u.key() != null) {
                ArrayDeque<Entry> same = pool.get(u.key());
                kept = same == null ? null : same.poll(); // a block that moved keeps its node too
            }
            Node node;
            if (kept != null) {
                node = kept.node();
            } else {
                node = MarkdownRenderer.renderGroup(u.first(), u.last(), ctx);
                built++;
                if (node != null) {
                    fresh.add(node);
                }
            }
            now.add(new Entry(u.key(), node, u.startLine(), u.endLine(), u.slug()));
            if (i >= prefix && i < n - suffix && node != null) {
                newMiddle.add(node);
            }
        }
        if (!oldMiddle.equals(newMiddle)) {
            int at = 0;
            for (int i = 0; i < prefix; i++) {
                if (before.get(i).node() != null) {
                    at++;
                }
            }
            // Only the changed stretch leaves and enters the scene: the rest keeps its CSS and layout.
            column.getChildren().remove(at, at + oldMiddle.size());
            column.getChildren().addAll(at, newMiddle);
        }
        entries = now;
        lastBuilt = built;
        lastReused = shown - built;
        boolean complete = shown == n;
        int next = shown;

        if (pane == null) {
            if (complete) {
                shows.set(shows.get() + 1);
            } else {
                buildRest(seq, units, next, ctx, -1);
            }
            return;
        }
        if (pane.getContent() != root) {
            double v = pane.getVvalue();
            pane.setContent(root);
            if (line < 0) {
                javafx.application.Platform.runLater(() -> pane.setVvalue(v)); // new content: same place
            }
        }
        boolean keep = topNode != null && topNode.getParent() == column;
        if (line >= 0 || keep) {
            // Style what is new, then lay out from the top of the scene (a pane that has only just been put
            // there has no size yet). Not applyCss() on anything higher: it restyles every node below the one
            // it is called on, clean or not, and each restyled text asks for layout again — for one new block
            // in a long document that was the whole cost of the render this class exists to avoid.
            if (fresh.size() > RESTYLE_ALL_ABOVE) {
                root.applyCss();
            } else {
                fresh.forEach(Node::applyCss);
            }
            javafx.scene.Parent top =
                    pane.getScene() == null ? pane : pane.getScene().getRoot();
            top.layout();
            anchorLine = line >= 0 ? line : lineAtY(top(topNode) + topOffset);
            anchorUntil = System.nanoTime() + ANCHOR_HOLD_NANOS;
            setScrollY(yOfLine(anchorLine));
        }
        if (complete) {
            shows.set(shows.get() + 1);
        } else {
            double target = line; // may lie in the part not built yet: gone to again once it is
            javafx.application.Platform.runLater(() -> buildRest(seq, units, next, ctx, target));
        }
    }

    /** Most blocks built in one go. A long document is built a slice per pulse, so opening its preview
     *  shows the first screens at once and keeps the window responding instead of freezing for seconds. */
    static final int SLICE = 250;

    private long buildSeq;

    /**
     * Builds the next slice of a document whose first part is already showing, appends it, and queues itself
     * for the one after — unless a newer render has started ({@code seq}), which then takes over from what is
     * built so far. {@code line} is where the finished document should be scrolled to (-1: left alone).
     */
    private void buildRest(long seq, List<Unit> units, int from, MarkdownRenderer.RenderContext ctx, double line) {
        if (seq != buildSeq) {
            return;
        }
        int to = Math.min(units.size(), from + SLICE);
        List<Entry> now = new ArrayList<>(entries);
        List<Node> nodes = new ArrayList<>();
        for (int i = from; i < to; i++) {
            Unit u = units.get(i);
            Node node = MarkdownRenderer.renderGroup(u.first(), u.last(), ctx);
            now.add(new Entry(u.key(), node, u.startLine(), u.endLine(), u.slug()));
            if (node != null) {
                nodes.add(node);
            }
        }
        column.getChildren().addAll(nodes);
        entries = now;
        lastBuilt += to - from;
        if (to < units.size()) {
            javafx.application.Platform.runLater(() -> buildRest(seq, units, to, ctx, line));
            return;
        }
        if (line >= 0 && pane != null && anchorUntil != 0) { // (0: the reader has scrolled meanwhile)
            javafx.scene.Parent top =
                    pane.getScene() == null ? pane : pane.getScene().getRoot();
            root.applyCss();
            top.layout();
            anchorLine = line;
            anchorUntil = System.nanoTime() + ANCHOR_HOLD_NANOS;
            setScrollY(yOfLine(line));
        }
        shows.set(shows.get() + 1);
    }

    /** A click on a rendered link: a {@code #slug} of this document scrolls to it, anything else is opened. */
    private void follow(String destination) {
        if (destination != null && destination.startsWith("#") && scrollToAnchor(destination.substring(1))) {
            return;
        }
        linkOpener.accept(destination);
    }

    /**
     * Scrolls the heading whose GitHub-style slug is {@code slug} (as {@code MarkdownToc} writes them; a
     * leading {@code #}, case and {@code %XX} escapes are tolerated) to the top. False when there is none.
     */
    boolean scrollToAnchor(String slug) {
        if (slug == null) {
            return false;
        }
        String want = MarkdownRenderer.localPath(slug.startsWith("#") ? slug.substring(1) : slug)
                .toLowerCase(Locale.ROOT);
        for (Entry e : entries) {
            if (e.slug() != null && e.node() != null && e.slug().equals(want)) {
                anchorUntil = 0;
                setScrollY(top(e.node()));
                return true;
            }
        }
        return false;
    }

    /** The slugs of the rendered headings, in document order. */
    List<String> anchors() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.slug() != null) {
                out.add(e.slug());
            }
        }
        return out;
    }

    // --- line ⇄ offset map -------------------------------------------------------------------------

    private double top(Node node) {
        return column.getBoundsInParent().getMinY() + node.getBoundsInParent().getMinY();
    }

    /** Arrays of the laid-out units that have lines: {@code [start, end]} lines and {@code [top, bottom]} px. */
    private double[][] geometry() {
        int count = 0;
        for (Entry e : entries) {
            if (e.node() != null && e.startLine() >= 0) {
                count++;
            }
        }
        double[][] g = new double[4][count];
        int i = 0;
        for (Entry e : entries) {
            if (e.node() != null && e.startLine() >= 0) {
                double top = top(e.node());
                g[0][i] = e.startLine();
                g[1][i] = e.endLine();
                g[2][i] = top;
                g[3][i] = top + e.node().getBoundsInParent().getHeight();
                i++;
            }
        }
        return g;
    }

    /** The offset in the document (px from its top) at which source {@code line} is rendered. */
    double yOfLine(double line) {
        double[][] g = geometry();
        return yOfLine(line, g[0], g[1], g[2], g[3]);
    }

    /** The source line rendered at offset {@code y} of the document. */
    double lineAtY(double y) {
        double[][] g = geometry();
        return lineAtY(y, g[0], g[1], g[2], g[3]);
    }

    /**
     * Maps a (fractional) source line to an offset, given each block's first and last line and its top and
     * bottom edge, in document order. Inside a block the offset moves in proportion; in the blank lines
     * between two blocks it moves across the gap between them. Pure; unit-tested.
     */
    static double yOfLine(double line, double[] start, double[] end, double[] top, double[] bottom) {
        int n = start.length;
        if (n == 0) {
            return 0;
        }
        if (line <= 0) {
            return 0;
        }
        if (line <= start[0]) {
            return top[0] * line / start[0];
        }
        for (int i = 0; i < n; i++) {
            double after = end[i] + 1; // the line after the block's last
            if (line < after) {
                return top[i] + (bottom[i] - top[i]) * (line - start[i]) / (after - start[i]);
            }
            if (i + 1 == n) {
                return bottom[i];
            }
            if (line < start[i + 1]) {
                return bottom[i] + (top[i + 1] - bottom[i]) * (line - after) / (start[i + 1] - after);
            }
        }
        return bottom[n - 1];
    }

    /** The inverse of {@link #yOfLine(double, double[], double[], double[], double[])}. Pure; unit-tested. */
    static double lineAtY(double y, double[] start, double[] end, double[] top, double[] bottom) {
        int n = start.length;
        if (n == 0) {
            return 0;
        }
        if (y <= top[0]) {
            return top[0] <= 0 ? start[0] : start[0] * Math.max(0, y) / top[0];
        }
        for (int i = 0; i < n; i++) {
            double after = end[i] + 1;
            if (y < bottom[i]) {
                return start[i] + (after - start[i]) * (y - top[i]) / Math.max(1e-9, bottom[i] - top[i]);
            }
            if (i + 1 == n) {
                return after;
            }
            if (y < top[i + 1]) {
                return after + (start[i + 1] - after) * (y - bottom[i]) / Math.max(1e-9, top[i + 1] - bottom[i]);
            }
        }
        return end[n - 1] + 1;
    }

    // --- scrolling ---------------------------------------------------------------------------------

    private double scrollable() {
        return pane == null
                ? 0
                : root.getLayoutBounds().getHeight() - pane.getViewportBounds().getHeight();
    }

    /** How far the document is scrolled, in px from its top. */
    double scrollY() {
        double range = pane == null ? 0 : pane.getVmax() - pane.getVmin();
        return range <= 0 ? 0 : Math.max(0, scrollable()) * (pane.getVvalue() - pane.getVmin()) / range;
    }

    /** Scrolls so that offset {@code y} of the document is at the top of the pane (as far as it goes). */
    void setScrollY(double y) {
        if (pane == null) {
            return;
        }
        double scrollable = scrollable();
        double frac = scrollable <= 0 ? 0 : Math.max(0, Math.min(1, y / scrollable));
        pane.setVvalue(pane.getVmin() + frac * (pane.getVmax() - pane.getVmin()));
    }

    /** The source line at the top of the pane. */
    double topLine() {
        return lineAtY(scrollY());
    }
}
