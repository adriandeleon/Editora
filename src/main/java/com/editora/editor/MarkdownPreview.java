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
            String slug,
            List<String> refs) {}

    /** A document cut into units, ready to {@link #show}. Built off the FX thread. {@code footnotes} is the
     *  number of each footnote label ({@link MarkdownRenderer#footnoteNumbers}). */
    record Plan(List<Unit> units, Path baseDir, Map<String, Integer> footnotes, String source, boolean remoteImages) {}

    /** A rendered unit: {@code node} is null for one that shows nothing (a comment, a link definition). */
    private record Entry(String key, Node node, int startLine, int endLine, String slug, List<String> refs) {}

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

    /** The thin bar beside the block that holds the editor's caret line (Split); unmanaged, so it moves
     *  nothing. */
    private final javafx.scene.layout.Region caretMarker = new javafx.scene.layout.Region();

    private Node markedBlock;
    private int markedLine = -1;
    private java.util.function.IntConsumer jumpToSource = line -> {};

    /** Who puts the editor's caret on a source line when a block is double-clicked here. */
    void onJumpToSource(java.util.function.IntConsumer jump) {
        this.jumpToSource = jump == null ? line -> {} : jump;
    }

    /**
     * Shows which block the editor's caret is in: a bar beside the block that came from source {@code line};
     * -1 removes it. Cheap enough for every change of line (a scan of the block list), and nothing is laid out
     * for it.
     */
    void markLine(int line) {
        markedLine = line;
        Node block = null;
        if (line >= 0) {
            for (Entry e : entries) {
                if (e.node() != null && e.startLine() >= 0 && e.startLine() <= line && line <= e.endLine()) {
                    block = e.node();
                    break;
                }
            }
        }
        markedBlock = block;
        placeMarker();
    }

    private void placeMarker() {
        boolean show = markedBlock != null && markedBlock.getParent() == column;
        caretMarker.setVisible(show);
        if (show) {
            javafx.geometry.Bounds b = markedBlock.getBoundsInParent();
            javafx.geometry.Bounds c = column.getBoundsInParent();
            caretMarker.resizeRelocate(
                    c.getMinX() + column.getInsets().getLeft() - 12, c.getMinY() + b.getMinY(), 3, b.getHeight());
        }
    }

    /** The source line a point of the document belongs to: the block under it, and how far down the block. */
    private int lineAt(Node target, double sceneY) {
        Node block = target;
        while (block != null && block.getParent() != column) {
            block = block.getParent();
        }
        for (Entry e : entries) {
            if (e.node() == block && e.startLine() >= 0) {
                javafx.geometry.Bounds b = block.localToScene(block.getBoundsInLocal());
                double frac = b.getHeight() <= 0 ? 0 : (sceneY - b.getMinY()) / b.getHeight();
                int lines = e.endLine() - e.startLine() + 1;
                return e.startLine() + (int) Math.max(0, Math.min(lines - 1, Math.floor(frac * lines)));
            }
        }
        return -1;
    }

    /** Shown instead of a blank pane when the document renders to nothing (an empty file, only comments). */
    private final javafx.scene.control.Label emptyNote =
            new javafx.scene.control.Label(com.editora.i18n.Messages.tr("markdown.preview.empty"));

    MarkdownPreview() {
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

    private boolean holdsAnchor() {
        return anchorLine >= 0 && System.nanoTime() < anchorUntil && pane != null && pane.getContent() == root;
    }

    private final PreviewFind find = new PreviewFind(this);

    /** The search over this document's rendered text (the Find bar, while only the preview is showing). */
    PreviewFind find() {
        return find;
    }

    private double zoom = 1.0;

    /** Sizes the document's images, formulas and diagrams for {@code zoom} (1 = 100%); text follows the font. */
    void setZoom(double zoom) {
        if (Math.abs(zoom - this.zoom) > 1e-9) {
            this.zoom = zoom;
            PreviewImageLoader.zoomAll(column, zoom);
        }
    }

    private PreviewLightbox lightbox;

    /** A picture or a diagram was clicked: shows it enlarged over the window, until Esc or a click. */
    void enlarge(Node picture) {
        javafx.scene.image.Image image = PreviewLightbox.imageOf(picture);
        if (image == null || pane == null || pane.getScene() == null) {
            return; // not loaded (a placeholder, an error): nothing to enlarge
        }
        closeLightbox();
        lightbox = PreviewLightbox.show(pane, image);
    }

    /** The picture or diagram {@code node} is (in), when a click on it would open it enlarged; else null. */
    static Node enlargeableAt(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n.hasProperties() && n.getProperties().containsKey(MarkdownRenderer.ENLARGE_KEY)) {
                return n;
            }
        }
        return null;
    }

    /** The enlarged picture now showing, or null. */
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
    private javafx.scene.layout.HBox remoteBar;

    /** Who renders this document again when the reader allows its remote images. */
    void onRerender(Runnable render) {
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
    javafx.scene.layout.HBox remoteBar() {
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
        boolean shown = remoteBar != null && remoteBar.isVisible();
        StackPane.setMargin(column, shown ? new javafx.geometry.Insets(remoteBar.getHeight() + 6, 0, 0, 0) : null);
    }

    private final PreviewSelection selection = new PreviewSelection(this, column, root);
    private String shownSource;
    private Path shownBaseDir;

    /** The text selection of this document. */
    PreviewSelection selection() {
        return selection;
    }

    /** The source line {@code sceneY} of the block {@code target} is in came from; -1 off the document. */
    int sourceLineAt(Node target, double sceneY) {
        return target == null ? -1 : lineAt(target, sceneY);
    }

    /** Goes to the source line of the block at a point (the context menu's "Go to Source Line"). */
    boolean jumpToSourceAt(Node target, double sceneY) {
        int line = sourceLineAt(target, sceneY);
        if (line >= 0) {
            jumpToSource.accept(line);
        }
        return line >= 0;
    }

    /** The Markdown of the blocks {@code first}…{@code last} of the column, or null when one of them did not
     *  come from source lines. */
    private String sourceOf(int first, int last) {
        if (shownSource == null) {
            return null;
        }
        int from = -1;
        int to = -1;
        for (Entry e : entries) {
            if (e.node() == column.getChildren().get(first)) {
                from = e.startLine();
            }
            if (e.node() == column.getChildren().get(last)) {
                to = e.endLine();
            }
        }
        if (from < 0 || to < from) {
            return null;
        }
        String[] lines = shownSource.split("\\R", -1);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, Math.min(lines.length, to + 1)));
    }

    /**
     * Puts the selection on the clipboard, as plain text and as HTML: of the Markdown itself when the selection
     * is whole blocks (or everything), else read off the selected nodes. False when nothing is selected.
     */
    boolean copySelection() {
        if (!selection.isActive()) {
            return false;
        }
        String text;
        String markdown = null;
        if (selection.isAll() && shownSource != null) {
            markdown = shownSource;
            text = MarkdownRenderer.plainText(shownSource);
        } else {
            text = selection.text();
            int[] whole = selection.wholeBlocks();
            markdown = whole == null ? null : sourceOf(whole[0], whole[1]);
        }
        javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
        cc.putString(text);
        cc.putHtml(
                markdown != null
                        ? MarkdownClipboardHtml.toHtmlIn(markdown, MathImages.isEnabled(), shownBaseDir)
                        : PreviewSelectionHtml.of(selection.parts()));
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
        return true;
    }

    /** The column of rendered blocks. */
    Parent column() {
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

    private long builtTotal;

    /** Every block built since this preview was created — a render that reuses everything adds nothing. */
    long builtTotal() {
        return builtTotal;
    }

    /** Binds the scroll pane this document scrolls in; a gesture of the user's ends any anchoring. */
    void attach(ScrollPane scrollPane) {
        this.pane = scrollPane;
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
    void releaseAnchor() {
        anchorUntil = 0;
    }

    /** Forgets every node (the preview was closed, or what it showed is no longer this document). */
    void clear() {
        selection.clear();
        find.clear();
        buildSeq++;
        entries = List.of();
        column.getChildren().clear();
        contextSignature = "";
        anchorUntil = 0;
    }

    // --- planning (pure, off the FX thread) -----------------------------------------------------

    /** Parses {@code markdown} and cuts it into keyed units. Pure CPU plus a {@code stat} per local image. */
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

    static Plan plan(String markdown, Path baseDir) {
        String md = markdown == null ? "" : markdown;
        org.commonmark.node.Node ast = MarkdownRenderer.parseForRender(md);
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

    /**
     * Shows {@code plan} in the attached pane, building only the units that changed since the last one.
     * {@code dark} is the preview's own theme, or null to follow the app. {@code line} is the source line to
     * bring to the top afterwards (fractional), or {@link #KEEP_VIEW}.
     */
    void show(Plan plan, Consumer<String> onLinkClick, Boolean dark, double line) {
        this.linkOpener = onLinkClick == null ? u -> {} : onLinkClick;
        this.shownSource = plan.source();
        this.shownBaseDir = plan.baseDir();
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
                + MermaidImages.isEnabled() + "|" + PreviewImageLoader.remotePolicy() + remoteGranted + "|"
                + plan.baseDir() + "|"
                + PreviewPrefs.signature() + "|" + MarkdownRenderer.wikiLinks() + "|" + WikiLinkTargets.epoch();
        column.setMaxWidth(PreviewPrefs.contentWidth());
        if (!signature.equals(contextSignature)) {
            entries = List.of(); // a theme, a tool or the folder changed: every node is out of date
            column.getChildren().clear();
            contextSignature = signature;
        }
        MarkdownRenderer.RenderContext ctx = MarkdownRenderer.previewContext(
                plan.baseDir(),
                this::follow,
                dark,
                plan.footnotes(),
                new MarkdownRenderer.Live(
                        this::taskClicked, this::enlarge, PreviewPrefs.hideFrontMatter(), remoteGranted));
        showRemoteBar(plan.remoteImages()
                && !remoteGranted
                && PreviewImageLoader.remotePolicy() == PreviewImageLoader.RemotePolicy.ASK);
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
                    zoomed(node);
                    node.getProperties().put(BUILT_AT_LINE, u.startLine());
                }
            }
            now.add(new Entry(u.key(), node, u.startLine(), u.endLine(), u.slug(), u.refs()));
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
        builtTotal += built;
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
        find.reapply(); // rebuilt blocks lost their marks
        showEmptyNote(complete && column.getChildren().isEmpty());
        selection.revalidate();
        markLine(markedLine); // the caret's block may be a new node, or on other lines
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
            Node node = zoomed(MarkdownRenderer.renderGroup(u.first(), u.last(), ctx));
            if (node != null) {
                node.getProperties().put(BUILT_AT_LINE, u.startLine());
            }
            now.add(new Entry(u.key(), node, u.startLine(), u.endLine(), u.slug(), u.refs()));
            if (node != null) {
                nodes.add(node);
            }
        }
        column.getChildren().addAll(nodes);
        entries = now;
        lastBuilt += to - from;
        builtTotal += to - from;
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
        find.reapply();
        shows.set(shows.get() + 1);
    }

    /** A block just built, with its images sized for the current zoom. */
    private Node zoomed(Node node) {
        if (node != null && zoom != 1.0) {
            PreviewImageLoader.zoomAll(node, zoom);
        }
        return node;
    }

    private java.util.function.IntPredicate taskToggle = line -> false;

    /** Who toggles the task item on a source line when its box is ticked here; false = the source refused. */
    void onTaskToggle(java.util.function.IntPredicate toggle) {
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
        Node block = box;
        while (block != null && block.getParent() != column) {
            block = block.getParent();
        }
        for (Entry e : entries) {
            if (e.node() == block && e.startLine() >= 0) {
                Object builtAt = block.getProperties().get(BUILT_AT_LINE);
                int offset = builtLine - (builtAt instanceof Integer start ? start : e.startLine());
                return taskToggle.test(e.startLine() + offset);
            }
        }
        return false;
    }

    /** Node property of a top-level block: the source line it started at when it was built. */
    private static final String BUILT_AT_LINE = "editora.md.builtAtLine";

    /**
     * Ticks or unticks the task item on {@code line} of {@code area}: one character replaced, so one undo
     * step. False when that line is not a task item (the text has changed since the preview was rendered).
     */
    static boolean toggleTask(org.fxmisc.richtext.CodeArea area, int line) {
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
    boolean scrollToAnchor(String slug) {
        if (slug == null) {
            return false;
        }
        String want = MarkdownRenderer.localPath(slug.startsWith("#") ? slug.substring(1) : slug)
                .toLowerCase(Locale.ROOT);
        for (Entry e : entries) {
            if (e.node() != null && (want.equals(e.slug()) || e.refs().contains(want))) {
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
