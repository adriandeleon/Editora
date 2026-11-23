package com.editora.editor.markdown;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.SourceSpan;
import org.jspecify.annotations.Nullable;

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
 *
 * <p><b>Only what is on screen is built.</b> The column ({@link BlockColumn}) is virtualized (PERF-08, Pauta
 * 6.2.0): every unit is an {@link Entry}, and a block is rendered when it comes within reach of the viewport, not
 * before. A 20,000-line document has a screenful of blocks in the scene, not thousands. What reads the whole
 * document reads the model instead: the scroll map sums the blocks' heights (measured, or estimated from their
 * lines) in O(log n); Find reads the text of a block that is not built from a rendering without pictures
 * ({@link MarkdownRenderer#renderTextOnly}); Copy of whole blocks copies their Markdown. Print and Export render
 * the whole document themselves ({@code MarkdownPrintLayout}), not through this class.
 */
public final class MarkdownPreview {

    private static final java.util.concurrent.atomic.AtomicLong SURFACES = new java.util.concurrent.atomic.AtomicLong();

    /**
     * This preview's Mermaid surface: a fence's key is this plus its source line, so a newer render of a fence
     * being typed in drops the one still queued for it (perf review P1).
     */
    private final String diagramSurface = "md-preview-" + SURFACES.incrementAndGet() + ":";

    /** One top-level unit of a parsed document: a block, or a {@code <details>} with the blocks it folds. */
    record Unit(
            org.commonmark.node.Node first,
            org.commonmark.node.Node last,
            @Nullable String key,
            int startLine,
            int endLine,
            @Nullable String slug,
            List<String> refs) {}

    /** A document cut into units, ready to {@link #show}. Built off the FX thread. {@code footnotes} is the
     *  number of each footnote label ({@link MarkdownRenderer#footnoteNumbers}). */
    public record Plan(
            List<Unit> units,
            @Nullable Path baseDir,
            Map<String, Integer> footnotes,
            String source,
            boolean remoteImages) {}

    /**
     * A unit of the document shown: built when it first comes within reach of the viewport. Its node is null
     * when not built, or when it shows nothing (a comment, a link definition).
     */
    final class Entry extends BlockColumn.Slot {
        final @Nullable String key;
        final int startLine;
        final int endLine;
        final @Nullable String slug;
        final List<String> refs;
        private final Unit unit;
        private final MarkdownRenderer.RenderContext ctx;
        /** Find's reading of a block that is not built (it depends only on the key). */
        @Nullable
        List<PreviewFind.Run> runs;

        Entry(Unit unit, MarkdownRenderer.RenderContext ctx) {
            this.unit = unit;
            this.ctx = ctx;
            this.key = unit.key();
            this.startLine = unit.startLine();
            this.endLine = unit.endLine();
            this.slug = unit.slug();
            this.refs = unit.refs();
        }

        @Nullable
        Node node() {
            return node;
        }

        /** Takes over what a block of the render before with the same key had built and measured. */
        void takeFrom(Entry before) {
            node = before.node;
            built = before.built;
            height = before.height;
            measuredAt = before.measuredAt;
            runs = before.runs;
        }

        @Override
        int lines() {
            return startLine < 0 ? 1 : endLine - startLine + 1;
        }

        @Override
        boolean likelyEmpty() {
            return unit.first() instanceof LinkReferenceDefinition
                    || (unit.first() instanceof HtmlBlock html
                            && unit.first() == unit.last()
                            && html.getLiteral().strip().startsWith("<!--")
                            && html.getLiteral().strip().endsWith("-->"));
        }

        @Override
        @Nullable
        Node build() {
            Node n = MarkdownRenderer.renderGroup(unit.first(), unit.last(), ctx);
            lastBuilt++;
            builtTotal++;
            if (n != null) {
                n.getProperties().put(BUILT_AT_LINE, startLine);
            }
            return n;
        }

        /** The text runs Find reads in this block, without building it when it is not built. */
        List<PreviewFind.Run> runs() {
            if (node != null) {
                return PreviewFind.runs(node);
            }
            if (built) {
                return List.of();
            }
            if (runs == null) {
                Node scratch = MarkdownRenderer.renderTextOnly(unit.first(), unit.last(), ctx);
                runs = scratch == null ? List.of() : PreviewFind.runs(scratch);
            }
            return runs;
        }
    }

    private final BlockColumn column = MarkdownRenderer.styleColumn(new BlockColumn());
    private final StackPane root = MarkdownRenderer.wrapColumn(column);
    private List<Entry> entries = List.of();
    /** The indices of the entries that came from source lines, in line order: the scroll map's blocks. */
    private int[] lined = new int[0];

    private String contextSignature = "";
    private Consumer<String> linkOpener = u -> {};
    private @Nullable ScrollPane pane;
    private int lastBuilt;
    private int lastReused;
    /** While positive (a {@code System.nanoTime} deadline), late layout — images arriving — re-anchors here. */
    private long anchorUntil;

    private double anchorLine = -1;

    /** For {@link #show}: keep on screen what is on screen (the block at the top stays at the top). */
    static final double KEEP_VIEW = -1;

    /** How long after a render late-arriving content (images, diagrams) keeps the anchored line in place. */
    private static final long ANCHOR_HOLD_NANOS = 1_500_000_000L;

    /** The thin bar beside the block that holds the editor's caret line (Split); unmanaged, so it moves
     *  nothing. */
    private final javafx.scene.layout.Region caretMarker = new javafx.scene.layout.Region();

    private @Nullable Node markedBlock;
    private int markedLine = -1;
    private java.util.function.IntConsumer jumpToSource = line -> {};

    /** Who puts the editor's caret on a source line when a block is double-clicked here. */
    public void onJumpToSource(java.util.function.IntConsumer jump) {
        this.jumpToSource = jump == null ? line -> {} : jump;
    }

    /**
     * Shows which block the editor's caret is in: a bar beside the block that came from source {@code line};
     * -1 removes it. Cheap enough for every change of line (a scan of the block list), and nothing is laid out
     * for it.
     */
    void markLine(int line) {
        markedLine = line;
        placeMarker();
    }

    /** The entry whose source lines hold {@code line}, or -1: a binary search, not a scan (PERF-08). */
    private int entryOfLine(int line) {
        int lo = 0;
        int hi = lined.length - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (entries.get(lined[mid]).startLine <= line) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found >= 0 && line <= entries.get(lined[found]).endLine ? lined[found] : -1;
    }

    private void placeMarker() {
        int at = markedLine < 0 ? -1 : entryOfLine(markedLine);
        markedBlock = at < 0 ? null : entries.get(at).node;
        Node block = markedBlock;
        boolean show = block != null && block.getParent() == column;
        caretMarker.setVisible(show);
        if (block != null && show) {
            javafx.geometry.Bounds b = block.getBoundsInParent();
            javafx.geometry.Bounds c = column.getBoundsInParent();
            caretMarker.resizeRelocate(
                    c.getMinX() + column.getInsets().getLeft() - 12, c.getMinY() + b.getMinY(), 3, b.getHeight());
        }
    }

    /** The source line a point of the document belongs to: the block under it, and how far down the block. */
    private int lineAt(Node target, double sceneY) {
        Node block = blockRootOf(target);
        Entry e = block == null ? null : entryOf(block);
        if (block != null && e != null && e.startLine >= 0) {
            javafx.geometry.Bounds b = block.localToScene(block.getBoundsInLocal());
            double frac = b.getHeight() <= 0 ? 0 : (sceneY - b.getMinY()) / b.getHeight();
            int lines = e.endLine - e.startLine + 1;
            return e.startLine + (int) Math.max(0, Math.min(lines - 1, Math.floor(frac * lines)));
        }
        return -1;
    }

    // --- the blocks, for selection and Find ------------------------------------------------------------

    /** The top-level block {@code node} is (in), built or on screen; null when it is not in a block. */
    @Nullable
    Node blockRootOf(@Nullable Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (BlockColumn.slotOf(n) != null) {
                return n;
            }
        }
        return null;
    }

    /** The entry of the current render whose node is {@code block}, or null. */
    private @Nullable Entry entryOf(Node block) {
        return BlockColumn.slotOf(block) instanceof Entry e
                        && e.index >= 0
                        && e.index < entries.size()
                        && entries.get(e.index) == e
                ? e
                : null;
    }

    /** The index of the block {@code node} is (in) in the document, or -1 when it is not of this render. */
    int blockIndexOf(@Nullable Node node) {
        Node block = blockRootOf(node);
        Entry e = block == null ? null : entryOf(block);
        return e == null ? -1 : e.index;
    }

    /** How many blocks the document has (built or not). */
    int blockCount() {
        return entries.size();
    }

    /** The node of block {@code i}, built now when it is not (not put in the scene); null when it shows nothing. */
    @Nullable
    Node blockNode(int i) {
        return column.node(i);
    }

    /** The node of block {@code i} when it is built, else null (nothing is built). */
    @Nullable
    Node builtNode(int i) {
        return entries.get(i).node;
    }

    /** The offset of block {@code i}'s top in the document (px), measured or estimated. */
    double blockTop(int i) {
        return top(i);
    }

    /** The block at offset {@code y} of the column (column coordinates); -1 when there is none. */
    int blockAtY(double y) {
        return column.indexAt(y);
    }

    /** The text runs of block {@code i} that Find reads. */
    List<PreviewFind.Run> runsOf(int i) {
        return entries.get(i).runs();
    }

    /** Keeps the blocks of these nodes in the scene wherever they are scrolled to (the ends of a selection). */
    void pinBlocks(@Nullable Node a, @Nullable Node b) {
        List<Entry> pins = new ArrayList<>(2);
        for (Node n : new Node[] {a, b}) {
            Node block = blockRootOf(n);
            Entry e = block == null ? null : entryOf(block);
            if (e != null && !pins.contains(e)) {
                pins.add(e);
            }
        }
        column.setPinned(pins);
    }

    /** Scrolls block {@code i} a third of the way down the pane and builds what is then on screen. */
    void revealBlock(int i) {
        if (pane != null && i >= 0 && i < entries.size()) {
            anchorUntil = 0;
            setScrollY(Math.max(0, top(i) - pane.getViewportBounds().getHeight() / 3));
            layoutNow();
        }
    }

    /** Lays out now what is waiting for the pulse, so the blocks now in view are built and measured. */
    void layoutNow() {
        if (pane != null && pane.getScene() != null) {
            pane.getScene().getRoot().layout();
        } else {
            column.fillNow();
        }
    }

    /** Shown instead of a blank pane when the document renders to nothing (an empty file, only comments). */
    private final javafx.scene.control.Label emptyNote =
            new javafx.scene.control.Label(com.editora.i18n.Messages.tr("markdown.preview.empty"));

    public MarkdownPreview() {
        emptyNote.getStyleClass().add("md-preview-empty");
        emptyNote.setVisible(false);
        emptyNote.setManaged(false);
        emptyNote.setMouseTransparent(true);
        StackPane.setAlignment(emptyNote, javafx.geometry.Pos.TOP_CENTER);
        root.getChildren().add(emptyNote);
        caretMarker.getStyleClass().add("md-caret-marker");
        caretMarker.setManaged(false);
        caretMarker.setVisible(false);
        caretMarker.setMouseTransparent(true);
        root.getChildren().add(caretMarker);
        column.onBuilt(this::zoomed);
        column.onAttach(slot -> find.attached(slot.index));
        column.onReflow(this::reflow);
        column.afterLayout(() -> {
            placeMarker();
            if (selection.isActive()) {
                selection.invalidate(); // the highlight follows the text it is on
            }
        });
        // A double click puts the editor's caret on the line the block came from — where it is not on text
        // (there it selects the word), or with the shortcut key held anywhere. Not on a link, a task box or a
        // <details> summary: those have their own click.
        column.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getClickCount() != 2 || e.getButton() != javafx.scene.input.MouseButton.PRIMARY) {
                return;
            }
            if (!e.isShortcutDown() && selection.onText(e)) {
                return;
            }
            Node target = e.getPickResult() == null ? null : e.getPickResult().getIntersectedNode();
            for (Node n = target; n != null && n != column; n = n.getParent()) {
                if (n instanceof javafx.scene.control.ButtonBase
                        || n.getOnMouseClicked() != null
                        || (n.hasProperties() && n.getProperties().containsKey(MarkdownRenderer.LINK_KEY))) {
                    return;
                }
            }
            int line = target == null ? -1 : lineAt(target, e.getSceneY());
            if (line >= 0) {
                jumpToSource.accept(line);
                e.consume();
            }
        });
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
    private boolean reflowQueued;
    private double reflowShift;

    /**
     * Blocks just measured changed the height of the document: once the pane knows the new height, scrolls by
     * {@code shift} px from where the reader is so what is at the top stays there (a scroll pane keeps its
     * fraction, not its offset) — or to the held line after a render. Shifts add up until it runs, and it
     * starts from wherever the reader has scrolled meanwhile.
     */
    private void reflow(double shift) {
        ScrollPane at = pane;
        double v = at == null ? 0 : at.getVvalue();
        if (v != reflowAtV) {
            reflowShift = 0; // measured at another place: what was owed there is not owed here
            reflowAtV = v;
        }
        reflowShift += shift;
        if (reflowQueued) {
            return;
        }
        reflowQueued = true;
        javafx.application.Platform.runLater(() -> {
            reflowQueued = false;
            ScrollPane p = pane;
            if (p == null || p.getContent() != root) {
                return;
            }
            double from = scrollY(); // what the reader sees now, before the pane learns the new height
            // Scrolled elsewhere since (a key, a command, the scroll sync): the shift was for the place left.
            double by = p.getVvalue() == reflowAtV ? reflowShift : 0;
            reflowShift = 0;
            column.requestLayout();
            p.layout();
            setScrollY(holdsAnchor() ? yOfLine(anchorLine) : Math.max(0, from + by));
            layoutNow(); // the blocks now in view, built before the next frame is drawn
        });
    }

    /** The scroll position the pending {@link #reflow}'s shift was measured at. */
    private double reflowAtV = Double.NaN;

    private boolean holdsAnchor() {
        return anchorLine >= 0 && System.nanoTime() < anchorUntil && pane != null && pane.getContent() == root;
    }

    private final PreviewFind find = new PreviewFind(this);

    /** The search over this document's rendered text (the Find bar, while only the preview is showing). */
    public PreviewFind find() {
        return find;
    }

    private double zoom = 1.0;

    /** Sizes the document's images, formulas and diagrams for {@code zoom} (1 = 100%); text follows the font. */
    public void setZoom(double zoom) {
        if (Math.abs(zoom - this.zoom) > 1e-9) {
            this.zoom = zoom;
            for (Entry e : entries) {
                if (e.node != null) {
                    PreviewImageLoader.zoomAll(e.node, zoom); // on screen or kept: a block built later is zoomed then
                }
            }
        }
    }

    private @Nullable PreviewLightbox lightbox;

    /** A picture or a diagram was clicked: shows it enlarged over the window, until Esc or a click. */
    public void enlarge(@Nullable Node picture) {
        javafx.scene.image.Image image = PreviewLightbox.imageOf(picture);
        if (image == null || pane == null || pane.getScene() == null) {
            return; // not loaded (a placeholder, an error): nothing to enlarge
        }
        closeLightbox();
        lightbox = PreviewLightbox.show(pane, image);
    }

    /** The picture or diagram {@code node} is (in), when a click on it would open it enlarged; else null. */
    public static @Nullable Node enlargeableAt(@Nullable Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n.hasProperties() && n.getProperties().containsKey(MarkdownRenderer.ENLARGE_KEY)) {
                return n;
            }
        }
        return null;
    }

    /** The enlarged picture now showing, or null. */
    @Nullable
    PreviewLightbox lightbox() {
        return lightbox != null && lightbox.isShowing() ? lightbox : null;
    }

    private void closeLightbox() {
        if (lightbox != null) {
            lightbox.close();
            lightbox = null;
        }
    }

    // --- remote images, asked for first ------------------------------------------------------------------

    private boolean remoteGranted;
    private Runnable rerender = () -> {};
    private javafx.scene.layout.@Nullable HBox remoteBar;

    /** Who renders this document again when the reader allows its remote images. */
    public void onRerender(Runnable render) {
        this.rerender = render == null ? () -> {} : render;
    }

    /** Whether the reader allowed this document's remote images (policy "ask"), for this session. */
    boolean remoteGranted() {
        return remoteGranted;
    }

    /** Allows this document's remote images, or (a new document is shown here) forgets that it was. */
    void grantRemote(boolean granted) {
        if (remoteGranted != granted) {
            remoteGranted = granted;
            if (granted) {
                showRemoteBar(false);
                rerender.run();
            }
        }
    }

    /** The bar that says the document has remote images and offers to load them; null until first needed. */
    javafx.scene.layout.@Nullable HBox remoteBar() {
        return remoteBar != null && remoteBar.isVisible() ? remoteBar : null;
    }

    private void showRemoteBar(boolean show) {
        if (remoteBar == null) {
            if (!show) {
                return;
            }
            javafx.scene.control.Label says =
                    new javafx.scene.control.Label(com.editora.i18n.Messages.tr("markdown.remoteImages.blocked"));
            says.setWrapText(true);
            says.setMinWidth(0);
            javafx.scene.layout.HBox.setHgrow(says, javafx.scene.layout.Priority.ALWAYS);
            says.setMaxWidth(Double.MAX_VALUE);
            javafx.scene.control.Button load =
                    new javafx.scene.control.Button(com.editora.i18n.Messages.tr("markdown.remoteImages.load"));
            load.getStyleClass().add("small");
            load.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            load.setOnAction(e -> grantRemote(true));
            remoteBar = new javafx.scene.layout.HBox(10, says, load);
            com.editora.editor.MenuChords.offerButton(remoteBar, "markdown.loadRemoteImages", load);
            remoteBar.getStyleClass().add("md-remote-bar");
            remoteBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            remoteBar.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            remoteBar.maxWidthProperty().bind(column.widthProperty());
            StackPane.setAlignment(remoteBar, javafx.geometry.Pos.TOP_CENTER);
            // The document starts below the bar instead of under it.
            remoteBar.heightProperty().addListener((o, was, now) -> padForRemoteBar());
            root.getChildren().add(remoteBar);
        }
        remoteBar.setVisible(show);
        remoteBar.setManaged(show);
        padForRemoteBar();
    }

    private void padForRemoteBar() {
        StackPane.setMargin(
                column,
                remoteBar != null && remoteBar.isVisible()
                        ? new javafx.geometry.Insets(remoteBar.getHeight() + 6, 0, 0, 0)
                        : null);
    }

    private final PreviewSelection selection = new PreviewSelection(this, column, root);
    private @Nullable String shownSource;
    private @Nullable Path shownBaseDir;

    /** The text selection of this document. */
    public PreviewSelection selection() {
        return selection;
    }

    /** The source line {@code sceneY} of the block {@code target} is in came from; -1 off the document. */
    public int sourceLineAt(@Nullable Node target, double sceneY) {
        return target == null ? -1 : lineAt(target, sceneY);
    }

    /** Goes to the source line of the block at a point (the context menu's "Go to Source Line"). */
    public boolean jumpToSourceAt(@Nullable Node target, double sceneY) {
        int line = sourceLineAt(target, sceneY);
        if (line >= 0) {
            jumpToSource.accept(line);
        }
        return line >= 0;
    }

    /** The source lines {@code {from, to}} of the blocks {@code first}…{@code last} of the document (indices of
     *  blocks, built or not), or null when one of them did not come from source lines. */
    private int @Nullable [] sourceLinesOf(int first, int last) {
        if (shownSource == null || first < 0 || last >= entries.size()) {
            return null;
        }
        int from = entries.get(first).startLine;
        int to = entries.get(last).endLine;
        return from < 0 || to < from ? null : new int[] {from, to};
    }

    /** The name the Copy's work is heard by, on its own thread: the source's lines, its plain text, its HTML. */
    public static final String COPY_SITE = "preview-copy-html";

    /**
     * Puts the selection on the clipboard, as plain text and as HTML: of the Markdown itself when the selection
     * is whole blocks (or everything), else read off the selected nodes. False when nothing is selected.
     *
     * <p>The Markdown's lines are cut out, its plain text and its HTML rendered and its local images read
     * (embedded) off the FX thread (PERF-01, Pauta 6.2.0); the clipboard is filled when that is done.
     */
    public boolean copySelection() {
        if (!selection.isActive()) {
            return false;
        }
        String source = shownSource;
        boolean all = selection.isAll() && source != null;
        int[] lines = null;
        String picked = null;
        if (!all) {
            picked = selection.text();
            int[] whole = selection.wholeBlocks();
            lines = whole == null ? null : sourceLinesOf(whole[0], whole[1]);
        }
        if (!all && (lines == null || source == null)) {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(picked);
            cc.putHtml(PreviewSelectionHtml.of(selection.parts()));
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            return true;
        }
        String whole = java.util.Objects.requireNonNull(source); // all, or lines found in it: shown
        int[] range = lines;
        String nodesText = picked;
        boolean math = MathImages.isEnabled();
        java.nio.file.Path base = shownBaseDir;
        record Copied(String text, @Nullable String html) {}
        com.editora.perf.OffFx.then(
                COPY_SITE,
                () -> {
                    String markdown;
                    String text;
                    if (range == null) {
                        markdown = whole;
                        text = MarkdownRenderer.plainText(whole);
                    } else {
                        String[] split = whole.split("\\R", -1);
                        markdown = String.join(
                                "\n",
                                java.util.Arrays.copyOfRange(
                                        split, Math.min(split.length, range[0]), Math.min(split.length, range[1] + 1)));
                        text = nodesText == null ? "" : nodesText;
                    }
                    return new Copied(text, MarkdownClipboardHtml.toHtmlIn(markdown, math, base));
                },
                answer -> {
                    Copied copied = answer.value();
                    if (copied == null) {
                        return;
                    }
                    javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                    cc.putString(copied.text());
                    if (copied.html() != null) {
                        cc.putHtml(copied.html());
                    }
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
                });
        return true;
    }

    /** The column of rendered blocks. */
    public Parent column() {
        return column;
    }

    /** How far below the top of the document {@code node} (somewhere inside a block) is, in px. */
    double offsetOf(Node node) {
        double y = 0;
        for (Node n = node; n != null && n != root; n = n.getParent()) {
            y += n.getBoundsInParent().getMinY();
        }
        return y;
    }

    /** Scrolls so that {@code node} is in view, a third of the way down the pane. */
    void reveal(Node node) {
        if (pane != null) {
            anchorUntil = 0;
            setScrollY(Math.max(0, offsetOf(node) - pane.getViewportBounds().getHeight() / 3));
        }
    }

    /** The node that is the scroll pane's content while this document is showing. Stable across renders. */
    Node root() {
        return root;
    }

    /** Whether this document is what {@code pane} currently shows. */
    public boolean isShowing() {
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

    private long builtTotal;

    /** Every block built since this preview was created — a render that reuses everything adds nothing. */
    long builtTotal() {
        return builtTotal;
    }

    /** Binds the scroll pane this document scrolls in; a gesture of the user's ends any anchoring. */
    public void attach(ScrollPane scrollPane) {
        this.pane = scrollPane;
        column.attach(scrollPane);
        selection.attach(scrollPane);
        scrollPane.addEventFilter(javafx.scene.input.ScrollEvent.ANY, e -> anchorUntil = 0);
        scrollPane.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> anchorUntil = 0);
        scrollPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> anchorUntil = 0);
    }

    private void showEmptyNote(boolean empty) {
        emptyNote.setVisible(empty);
        emptyNote.setManaged(empty);
    }

    /** Stops holding the last render's line in place: something else now decides where the document is. */
    public void releaseAnchor() {
        anchorUntil = 0;
    }

    /** Forgets every node (the preview was closed, or what it showed is no longer this document). */
    public void clear() {
        selection.clear();
        find.clear();
        entries = List.of();
        lined = new int[0];
        column.setSlots(entries);
        contextSignature = "";
        anchorUntil = 0;
    }

    // --- planning (pure, off the FX thread) -----------------------------------------------------

    /** An {@code <img … src="http…">} of the HTML subset. */
    private static final java.util.regex.Pattern REMOTE_IMG = java.util.regex.Pattern.compile(
            "<img\\b[^>]*\\bsrc\\s*=\\s*[\"']?\\s*https?://", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Whether the document names an {@code http(s)} image — Markdown's or the HTML subset's. Pure. */
    static boolean namesRemoteImage(org.commonmark.node.Node ast) {
        boolean[] found = new boolean[1];
        ast.accept(new org.commonmark.node.AbstractVisitor() {
            @Override
            public void visit(org.commonmark.node.Image image) {
                found[0] |= PreviewImageLoader.isRemote(image.getDestination());
            }

            @Override
            public void visit(org.commonmark.node.HtmlInline html) {
                found[0] |= REMOTE_IMG.matcher(html.getLiteral()).find();
            }

            @Override
            public void visit(org.commonmark.node.HtmlBlock html) {
                found[0] |= REMOTE_IMG.matcher(html.getLiteral()).find();
            }
        });
        return found[0];
    }

    /** Parses {@code markdown} and cuts it into keyed units. Pure CPU plus a {@code stat} per local image. */
    public static Plan plan(String markdown, @Nullable Path baseDir) {
        return plan(markdown, baseDir, null);
    }

    /**
     * As {@link #plan(String, Path)}, its formulas rasterized here for the glyph theme the preview will show
     * ({@code glyphsDark} null: the app's), so the FX thread finds them drawn (PERF-01, Pauta 6.2.0).
     */
    public static Plan plan(String markdown, @Nullable Path baseDir, @Nullable Boolean glyphsDark) {
        String md = markdown == null ? "" : markdown;
        org.commonmark.node.Node ast = MarkdownRenderer.parseForRender(md);
        MarkdownRenderer.warmMath(ast, glyphsDark);
        String definitions = definitions(ast);
        // The anchors the table of contents and the link opener use: one numbering for all three.
        Map<Heading, String> slugs = MarkdownDocInfo.headingAnchors(ast);
        Map<String, Integer> footnotes = MarkdownRenderer.footnoteNumbers(ast);
        String definitionsAndNotes = definitions + '\u0003' + footnotes;
        java.util.Set<String> referred = new java.util.HashSet<>();
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
                        + dependencies(n, last, text, definitionsAndNotes, baseDir);
            }
            String slug = n instanceof Heading h ? slugs.get(h) : null;
            if (n instanceof org.commonmark.ext.footnotes.FootnoteDefinition def) {
                slug = MarkdownRenderer.footnoteAnchor(def.getLabel()); // where a reference to it scrolls to
            }
            units.add(new Unit(n, last, key, start, end, slug, firstReferences(n, last, referred)));
            n = last.getNext();
        }
        return new Plan(units, baseDir, footnotes, md, namesRemoteImage(ast));
    }

    private static @Nullable SourceSpan firstSpan(org.commonmark.node.Node n) {
        List<SourceSpan> spans = n.getSourceSpans();
        return spans.isEmpty() ? null : spans.get(0);
    }

    private static @Nullable SourceSpan lastSpan(org.commonmark.node.Node n) {
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
            @Nullable Path baseDir) {
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

    /**
     * The back-anchors ({@link MarkdownRenderer#footnoteBackAnchor}) of the footnotes this unit is the first to
     * refer to — where a definition's "back" link scrolls. {@code referred} collects the labels seen so far.
     */
    private static List<String> firstReferences(
            org.commonmark.node.Node first, org.commonmark.node.Node last, java.util.Set<String> referred) {
        List<String> out = new ArrayList<>();
        AbstractVisitor v = new AbstractVisitor() {
            @Override
            public void visit(org.commonmark.node.CustomNode node) {
                if (node instanceof org.commonmark.ext.footnotes.FootnoteReference ref
                        && referred.add(MarkdownRenderer.footnoteKey(ref.getLabel()))) {
                    out.add(MarkdownRenderer.footnoteBackAnchor(ref.getLabel()));
                }
                visitChildren(node);
            }
        };
        for (org.commonmark.node.Node n = first; n != null; n = n.getNext()) {
            n.accept(v);
            if (n == last) {
                break;
            }
        }
        return out.isEmpty() ? List.of() : out;
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

    /** Whether diagrams render (off for an encrypted file: mmdc reads its source from a temporary file). */
    private boolean diagrams = true;

    public void setDiagrams(boolean on) {
        diagrams = on;
    }

    /**
     * Shows {@code plan} in the attached pane. A unit whose key is unchanged keeps the node it had; the others are
     * built when they come within reach of the viewport (the column is virtualized). {@code dark} is the
     * preview's own theme, or null to follow the app. {@code line} is the source line to bring to the top
     * afterwards (fractional), or {@link #KEEP_VIEW}.
     */
    public void show(Plan plan, Consumer<String> onLinkClick, @Nullable Boolean dark, double line) {
        this.linkOpener = onLinkClick == null ? u -> {} : onLinkClick;
        this.shownSource = plan.source();
        this.shownBaseDir = plan.baseDir();
        // What is at the top now, as a unit: text typed above it renumbers its lines but does not replace it.
        Entry topEntry = null;
        double topOffset = 0;
        if (line < 0 && isShowing()) {
            double y = scrollY();
            int i = column.indexAt(y - column.getBoundsInParent().getMinY());
            if (i >= 0) {
                topEntry = entries.get(i);
                topOffset = y - top(i);
            }
        }
        String signature = dark + "|" + MathImages.epoch() + "|" + MermaidImages.epoch() + "|"
                + MermaidImages.isEnabled() + diagrams + "|" + PreviewImageLoader.remotePolicy() + remoteGranted + "|"
                + plan.baseDir() + "|"
                + PreviewPrefs.signature() + "|" + MarkdownRenderer.wikiLinks() + "|" + WikiLinkTargets.epoch();
        column.setMaxWidth(PreviewPrefs.contentWidth());
        List<Entry> before = entries;
        if (!signature.equals(contextSignature)) {
            before = List.of(); // a theme, a tool or the folder changed: every node is out of date
            contextSignature = signature;
        }
        MarkdownRenderer.RenderContext ctx = MarkdownRenderer.previewContext(
                plan.baseDir(),
                this::follow,
                dark,
                plan.footnotes(),
                new MarkdownRenderer.Live(
                        this::taskClicked, this::enlarge, PreviewPrefs.hideFrontMatter(), remoteGranted, diagrams),
                fenceLine -> diagramSurface + fenceLine);
        showRemoteBar(plan.remoteImages()
                && !remoteGranted
                && PreviewImageLoader.remotePolicy() == PreviewImageLoader.RemotePolicy.ASK);
        List<Unit> units = plan.units();
        int[] ends = commonEnds(
                units.stream().map(Unit::key).toList(),
                before.stream().map(e -> e.key).toList());
        int prefix = ends[0];
        int suffix = ends[1];
        int n = units.size();
        int m = before.size();
        Map<String, ArrayDeque<Entry>> pool = new HashMap<>();
        for (int i = prefix; i < m - suffix; i++) {
            Entry e = before.get(i);
            if (e.key != null) {
                pool.computeIfAbsent(e.key, k -> new ArrayDeque<>()).add(e);
            }
        }
        Map<Entry, Entry> moved = new java.util.IdentityHashMap<>();
        List<Entry> now = new ArrayList<>(n);
        int reused = 0;
        int linedCount = 0;
        for (int i = 0; i < n; i++) {
            Unit u = units.get(i);
            Entry kept = null;
            if (i < prefix) {
                kept = before.get(i);
            } else if (i >= n - suffix) {
                kept = before.get(m - (n - i));
            } else if (u.key() != null) {
                ArrayDeque<Entry> same = pool.get(u.key());
                kept = same == null ? null : same.poll(); // a block that moved keeps its node too
            }
            Entry e = new Entry(u, ctx);
            if (kept != null) {
                e.takeFrom(kept);
                moved.put(kept, e);
                if (e.built) {
                    reused++;
                }
            }
            if (u.startLine() >= 0) {
                linedCount++;
            }
            now.add(e);
        }
        int[] lines = new int[linedCount];
        for (int i = 0, k = 0; i < n; i++) {
            if (now.get(i).startLine >= 0) {
                lines[k++] = i;
            }
        }
        entries = now;
        lined = lines;
        lastBuilt = 0;
        lastReused = reused;
        column.setSlots(now); // the blocks of the old render that are not kept leave the scene here

        if (pane == null) {
            find.reapply();
            shows.set(shows.get() + 1);
            return;
        }
        boolean placedNow = pane.getContent() != root;
        if (placedNow) {
            double v = pane.getVvalue();
            pane.setContent(root);
            if (line < 0) {
                ScrollPane scrolled = pane;
                javafx.application.Platform.runLater(() -> scrolled.setVvalue(v)); // new content: same place
            }
            root.applyCss(); // only the column's own style: its blocks are styled one by one as they enter
        }
        Entry keepEntry = topEntry == null ? null : moved.get(topEntry);
        boolean anchored = line >= 0 || keepEntry != null;
        if (anchored) {
            anchorLine = line >= 0 || keepEntry == null ? line : lineAtY(top(keepEntry.index) + topOffset);
            anchorUntil = System.nanoTime() + ANCHOR_HOLD_NANOS;
            // First to the place, with nothing built (the pane learns the new height), then build what is there:
            // not a screenful at the old place on the way.
            column.holdBuild(true);
            layoutNow();
            column.holdBuild(false);
            setScrollY(yOfLine(anchorLine));
        }
        layoutNow(); // builds what the viewport shows, and only that
        if (anchored) {
            setScrollY(yOfLine(anchorLine)); // measured now: the line is exact
            layoutNow();
        }
        find.reapply(); // rebuilt blocks lost their marks
        showEmptyNote(allEmpty());
        selection.revalidate();
        placeMarker(); // the caret's block may be a new node, or on other lines
        shows.set(shows.get() + 1);
    }

    /** Whether the document shows nothing at all (only comments and definitions, or no text). */
    private boolean allEmpty() {
        for (Entry e : entries) {
            if (e.built ? e.node != null : !e.likelyEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** A block just built, with its images sized for the current zoom. */
    private @Nullable Node zoomed(@Nullable Node node) {
        if (node != null && zoom != 1.0) {
            PreviewImageLoader.zoomAll(node, zoom);
        }
        return node;
    }

    private java.util.function.IntPredicate taskToggle = line -> false;

    /** Who toggles the task item on a source line when its box is ticked here; false = the source refused. */
    public void onTaskToggle(java.util.function.IntPredicate toggle) {
        this.taskToggle = toggle == null ? line -> false : toggle;
    }

    /**
     * A task box was ticked. It was built for a source line, but its block may have moved since without being
     * rebuilt (text typed above it): the line is its block's line now, plus how far into the block the item
     * was when the block was built.
     */
    private boolean taskClicked(javafx.scene.control.CheckBox box) {
        if (!(box.getProperties().get(MarkdownRenderer.TASK_LINE) instanceof Integer builtLine)) {
            return false;
        }
        Node block = blockRootOf(box);
        Entry e = block == null ? null : entryOf(block);
        if (block != null && e != null && e.startLine >= 0) {
            Object builtAt = block.getProperties().get(BUILT_AT_LINE);
            int offset = builtLine - (builtAt instanceof Integer start ? start : e.startLine);
            return taskToggle.test(e.startLine + offset);
        }
        return false;
    }

    /** Node property of a top-level block: the source line it started at when it was built. */
    private static final String BUILT_AT_LINE = "editora.md.builtAtLine";

    /**
     * Ticks or unticks the task item on {@code line} of {@code area}: one character replaced, so one undo
     * step. False when that line is not a task item (the text has changed since the preview was rendered).
     */
    public static boolean toggleTask(org.fxmisc.richtext.CodeArea area, int line) {
        if (line < 0 || line >= area.getParagraphs().size()) {
            return false;
        }
        String text = area.getParagraph(line).getText();
        int at = MarkdownRenderer.taskBoxOffset(text);
        if (at < 0) {
            return false;
        }
        area.replaceText(line, at, line, at + 1, text.charAt(at) == ' ' ? "x" : " ");
        return true;
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
    public boolean scrollToAnchor(String slug) {
        if (slug == null) {
            return false;
        }
        String want = MarkdownRenderer.localPath(slug.startsWith("#") ? slug.substring(1) : slug)
                .toLowerCase(Locale.ROOT);
        for (Entry e : entries) {
            if (!(e.built && e.node == null) && (want.equals(e.slug) || e.refs.contains(want))) {
                anchorUntil = 0;
                setScrollY(top(e.index));
                layoutNow(); // built and measured: the blocks above it may have moved it
                setScrollY(top(e.index));
                return true;
            }
        }
        return false;
    }

    /** The slugs of the rendered headings, in document order. */
    List<String> anchors() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.slug != null) {
                out.add(e.slug);
            }
        }
        return out;
    }

    // --- line ⇄ offset map -------------------------------------------------------------------------

    /** The offset of block {@code i}'s top in the document (px from the top of the content). */
    private double top(int i) {
        return column.getBoundsInParent().getMinY() + column.topOf(i);
    }

    private double bottom(int i) {
        return top(i) + column.heightOf(i);
    }

    /**
     * The offset in the document (px from its top) at which source {@code line} is rendered: as the pure
     * {@link #yOfLine(double, double[], double[], double[], double[])}, with a binary search over the blocks
     * that have lines instead of arrays of them all (it runs on every scroll of a split view).
     */
    double yOfLine(double line) {
        int n = lined.length;
        if (n == 0 || line <= 0) {
            return 0;
        }
        int lo = 0;
        int hi = n - 1;
        int k = -1; // the last block that starts at or before the line
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (entries.get(lined[mid]).startLine <= line) {
                k = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (k < 0) {
            double start = entries.get(lined[0]).startLine;
            return top(lined[0]) * line / start;
        }
        Entry e = entries.get(lined[k]);
        double after = e.endLine + 1;
        double top = top(lined[k]);
        double bottom = bottom(lined[k]);
        if (line < after) {
            return top + (bottom - top) * (line - e.startLine) / (after - e.startLine);
        }
        if (k + 1 == n) {
            return bottom;
        }
        double nextStart = entries.get(lined[k + 1]).startLine;
        return bottom + (top(lined[k + 1]) - bottom) * (line - after) / (nextStart - after);
    }

    /** The source line rendered at offset {@code y} of the document: the inverse of {@link #yOfLine(double)}. */
    double lineAtY(double y) {
        int n = lined.length;
        if (n == 0) {
            return 0;
        }
        Entry firstEntry = entries.get(lined[0]);
        double top0 = top(lined[0]);
        if (y <= top0) {
            return top0 <= 0 ? firstEntry.startLine : firstEntry.startLine * Math.max(0, y) / top0;
        }
        int lo = 0;
        int hi = n - 1;
        int i = n; // the first block whose bottom is below y
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (y < bottom(lined[mid])) {
                i = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        if (i == n) {
            return entries.get(lined[n - 1]).endLine + 1;
        }
        Entry e = entries.get(lined[i]);
        double top = top(lined[i]);
        double bottom = bottom(lined[i]);
        if (y >= top || i == 0) {
            double after = e.endLine + 1;
            return e.startLine + (after - e.startLine) * (y - top) / Math.max(1e-9, bottom - top);
        }
        Entry prev = entries.get(lined[i - 1]);
        double after = prev.endLine + 1;
        double prevBottom = bottom(lined[i - 1]);
        return after + (e.startLine - after) * (y - prevBottom) / Math.max(1e-9, top - prevBottom);
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
        ScrollPane p = pane;
        if (p == null) {
            return 0;
        }
        double range = p.getVmax() - p.getVmin();
        return range <= 0 ? 0 : Math.max(0, scrollable()) * (p.getVvalue() - p.getVmin()) / range;
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
