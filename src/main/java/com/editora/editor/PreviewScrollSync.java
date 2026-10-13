package com.editora.editor;

import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.control.ScrollPane;

import com.editora.editor.EditorBuffer.MarkdownViewMode;
import org.fxmisc.richtext.CodeArea;

/**
 * Keeps a buffer's editor and its rendered preview at the same place: while both are on screen (Split), and
 * across a switch between Editor, Split and Preview.
 *
 * <p>The pane the mouse is over drives the other. Gating on which pane is <b>hovered</b> (the wheel target)
 * makes the sync strictly one-directional at any moment — the mouse is over at most one pane — so it cannot
 * oscillate (RichTextFX refines {@code estimatedScrollY} as paragraphs are measured, and a naïve bidirectional
 * copy would feed that back, the same pitfall {@code DiffViewerPane} guards against). A {@code syncing}
 * re-entrancy flag wraps each programmatic set as a second guard.
 *
 * <p><b>What "the same place" means.</b> For a Markdown document it is the same <em>source line</em>: the
 * preview knows which lines each rendered block came from ({@link MarkdownPreview}), so the line at the top of
 * the editor is brought to the top of the preview and back. Copying the scroll fraction — all there used to
 * be — showed different sections in the two panes as soon as an image, a diagram or a code block made one side
 * taller than the other. The fraction is still what the other previews in this pane use (a diagram, a
 * timeline): they have no lines to map.
 */
public final class PreviewScrollSync {

    private final CodeArea area;
    private final Supplier<MarkdownViewMode> mode;
    private final Supplier<ScrollPane> pane;
    private final Supplier<MarkdownPreview> document;

    /** Re-entrancy guard for the programmatic scroll sets. */
    private boolean syncing;
    /** Preview content height last seen by the scroll-sync listener. */
    private double contentHeight = -1;
    /** When the preview's content height last changed, so layout-driven vvalue moves can be ignored. */
    private long layoutChangedAt;
    /** How long after a preview content-height change to keep treating vvalue moves as layout, not scrolling. */
    static final long SETTLE_NANOS = 250_000_000L;
    /** The source line the view being left had at its top, carried over a mode switch; -1 = unknown. */
    private double carriedLine = -1;
    /** The line the next render should come up at (set by a mode switch, before that render exists). */
    private double pendingLine = -1;
    /** A line-accurate follow is queued behind the editor's layout (see {@link #previewFollowsEditor}). */
    private boolean followQueued;

    /**
     * @param pane the preview scroll pane, or null while it has not been created
     * @param document the Markdown document rendered in that pane, or null when the buffer has none
     */
    PreviewScrollSync(
            CodeArea area,
            Supplier<MarkdownViewMode> mode,
            Supplier<ScrollPane> pane,
            Supplier<MarkdownPreview> document) {
        this.area = area;
        this.mode = mode;
        this.pane = pane;
        this.document = document;
        area.currentParagraphProperty().addListener((o, was, now) -> markCaretBlock());
        area.estimatedScrollYProperty().addListener((o, ov, nv) -> {
            if (editorDrives() && !syncing) {
                previewFollowsEditor();
            }
        });
    }

    /**
     * Tells the preview which block holds the caret (Split only — in Preview there is no caret to see, in
     * Editor no preview). Called when the caret changes <em>line</em>, not on every keystroke.
     */
    void markCaretBlock() {
        MarkdownPreview doc = document.get();
        if (doc != null) {
            doc.markLine(mode.get() == MarkdownViewMode.SPLIT ? area.getCurrentParagraph() : -1);
        }
    }

    /**
     * A block of the preview was double-clicked: the editor's caret goes to the source {@code line} it came
     * from, and the editor takes the focus. With only the preview on screen, {@code showEditor} first brings
     * the editor in beside it (Split), so the reader sees where they landed without losing the rendering.
     */
    void jumpToSource(int line, Runnable showEditor) {
        if (mode.get() == MarkdownViewMode.PREVIEW) {
            showEditor.run();
        }
        int target = Math.max(0, Math.min(line, area.getParagraphs().size() - 1));
        Platform.runLater(() -> {
            area.moveTo(target, 0);
            area.requestFollowCaret();
            area.requestFocus();
            markCaretBlock();
        });
    }

    private static volatile boolean enabled = true;

    /** Whether editor and preview scroll together ({@code Settings.markdownScrollSync}; on by default). Off,
     *  each pane stays where its reader put it; positions are still handed over on a mode switch. */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    static boolean enabled() {
        return enabled;
    }

    /** Split, with the mouse anywhere but over the preview: the editor's position is the one that counts. */
    private boolean editorDrives() {
        ScrollPane p = pane.get();
        return enabled && mode.get() == MarkdownViewMode.SPLIT && p != null && !p.isHover();
    }

    /** Wires the preview → editor half onto the (just created) preview pane. */
    void install(ScrollPane p) {
        p.vvalueProperty().addListener((o, ov, nv) -> {
            if (syncing) {
                return; // our own programmatic set
            }
            // A ScrollPane's vvalue moves for two quite different reasons: the user wheeling, and the pane
            // re-anchoring as its content grows. isHover() can't tell them apart, and a preview that renders
            // progressively (typst pages, Mermaid diagrams, images resolving) changes height many times over
            // a second or two — each one arriving here looking exactly like a scroll and dragging the editor
            // with it. That was visible as the editor drifting ~100 lines down and back over ~2 s after
            // opening a file in SPLIT, with the mouse merely resting over the preview.
            double h = p.getContent() == null
                    ? -1
                    : p.getContent().getLayoutBounds().getHeight();
            if (previewHeightChanged(contentHeight, h)) {
                contentHeight = h;
                layoutChangedAt = System.nanoTime();
            }
            if (previewSettling(System.nanoTime() - layoutChangedAt, SETTLE_NANOS)) {
                // Still settling: the editor is the source of truth, so re-anchor the preview to it rather
                // than letting a layout-driven vvalue move the editor. Only in SPLIT: in PREVIEW the editor
                // is off screen, and following its stale scroll position threw away the user's paging (and
                // the position kept across a re-render).
                if (enabled && mode.get() == MarkdownViewMode.SPLIT) {
                    previewFollowsEditor();
                }
                return;
            }
            if (enabled && mode.get() == MarkdownViewMode.SPLIT && p.isHover()) {
                editorFollowsPreview();
            }
        });
    }

    /**
     * Whether the preview's content height moved enough to call this vvalue change layout-driven. Pure.
     * The epsilon keeps sub-pixel layout noise from being mistaken for a real resize.
     */
    static boolean previewHeightChanged(double lastHeight, double newHeight) {
        return Math.abs(newHeight - lastHeight) > 0.5;
    }

    /**
     * Whether we're still inside the settle window after the preview's content last resized, i.e. a vvalue
     * change should be read as the pane re-anchoring rather than as the user scrolling. Pure.
     *
     * <p>A window rather than a single-event check because a progressive render (typst pages, diagrams,
     * images) resizes over many pulses, and the vvalue changes it provokes don't all land on the same pulse
     * as the resize itself.
     */
    static boolean previewSettling(long nanosSinceResize, long settleNanos) {
        return nanosSinceResize < settleNanos;
    }

    boolean isSyncing() {
        return syncing;
    }

    private MarkdownPreview lines() {
        MarkdownPreview d = document.get();
        return d != null && d.isShowing() ? d : null;
    }

    double editorScrollableHeight() {
        Double total = area.totalHeightEstimateProperty().getValue();
        return (total == null ? 0 : total) - area.getHeight();
    }

    private double editorScrollY() {
        Double y = area.estimatedScrollYProperty().getValue();
        return y == null ? 0 : y;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /**
     * The source line the preview should have at its top for the editor's scroll position: 0 while the editor
     * is at its very top and +infinity at its very bottom (so both panes reach their ends together), else the
     * editor's first visible line. Pure.
     */
    static double previewTarget(double editorY, double editorScrollable, int topLine) {
        if (editorScrollable <= 1 || editorY <= 0.5) {
            return 0; // top
        }
        if (editorY >= editorScrollable - 0.5) {
            return Double.POSITIVE_INFINITY; // bottom
        }
        return topLine;
    }

    /** editor → preview: bring the editor's top line to the top of the preview. */
    void previewFollowsEditor() {
        ScrollPane p = pane.get();
        if (p == null) {
            return;
        }
        double scrollable = editorScrollableHeight();
        MarkdownPreview doc = lines();
        if (doc == null && scrollable <= 1) {
            return;
        }
        double end = previewTarget(editorScrollY(), scrollable, 1);
        if (doc == null || end == 0 || Double.isInfinite(end)) {
            // No lines to map, or the editor is at its very top or bottom: the same fraction of the range.
            double frac = scrollable <= 1 ? 0 : clamp01(editorScrollY() / scrollable);
            syncing = true;
            try {
                p.setVvalue(p.getVmin() + frac * (p.getVmax() - p.getVmin()));
            } finally {
                syncing = false;
            }
            return;
        }
        // The editor reports its new scroll offset before it has laid out the lines that are now visible,
        // so its first visible line is only right after that layout: follow it one pulse later.
        if (!followQueued) {
            followQueued = true;
            Platform.runLater(() -> {
                followQueued = false;
                MarkdownPreview now = lines();
                int top = ScrollAnchor.firstVisibleLine(area);
                if (now == null || top < 0 || !(mode.get() == MarkdownViewMode.SPLIT)) {
                    return;
                }
                double target = previewTarget(editorScrollY(), editorScrollableHeight(), top);
                if (target == 0 || Double.isInfinite(target)) {
                    return; // reached an end meanwhile: the fraction path has put it there
                }
                syncing = true;
                try {
                    now.releaseAnchor(); // the editor decides where the preview is, not the last render
                    now.setScrollY(now.yOfLine(target));
                } finally {
                    syncing = false;
                }
            });
        }
    }

    /** preview → editor: bring the source line at the top of the preview to the top of the editor. */
    void editorFollowsPreview() {
        ScrollPane p = pane.get();
        if (p == null) {
            return;
        }
        double scrollable = editorScrollableHeight();
        if (scrollable <= 1) {
            return;
        }
        double range = p.getVmax() - p.getVmin();
        double frac = range > 0 ? clamp01((p.getVvalue() - p.getVmin()) / range) : 0;
        MarkdownPreview doc = lines();
        syncing = true;
        try {
            if (doc == null || frac <= 0 || frac >= 1) {
                area.estimatedScrollYProperty().setValue(frac * scrollable);
            } else {
                ScrollAnchor.restore(area, (int) Math.floor(doc.topLine()), frac * scrollable);
            }
        } finally {
            syncing = false;
        }
    }

    /**
     * The source line a fresh render should come up at: the editor's top line while the editor is on screen
     * and driving (Split, mouse not over the preview); otherwise the preview's own — a re-render must not move
     * what the reader is looking at. -1 when neither is known yet.
     */
    double anchorLine() {
        ScrollPane p = pane.get();
        MarkdownPreview doc = document.get();
        if (pendingLine >= 0) {
            double line = pendingLine;
            pendingLine = -1;
            return line;
        }
        if (enabled && mode.get() == MarkdownViewMode.SPLIT && (p == null || !p.isHover())) {
            // The line read here can be a layout behind (the editor reports a scroll before it has laid the
            // new lines out); follow again once the render is in and the editor has settled.
            Platform.runLater(this::previewFollowsEditor);
            double scrollable = editorScrollableHeight();
            double target = previewTarget(editorScrollY(), scrollable, ScrollAnchor.firstVisibleLine(area));
            return Double.isInfinite(target) ? Double.MAX_VALUE : Math.max(0, target);
        }
        return MarkdownPreview.KEEP_VIEW;
    }

    /** Call before the view mode changes from {@code from}: remembers which source line the reader is at. */
    void leaving(MarkdownViewMode from) {
        MarkdownPreview doc = lines();
        if (from == MarkdownViewMode.PREVIEW) {
            carriedLine = doc == null ? -1 : doc.topLine();
        } else {
            // At the very top it is the top that should show (line 0 is also "unknown" for an unlaid editor).
            carriedLine = editorScrollY() <= 0.5 ? -1 : ScrollAnchor.firstVisibleLine(area);
        }
    }

    /**
     * Call after the view mode changed to {@code to}: the view that comes on screen takes over the place the
     * one that left was at. Entering Preview used to show wherever the preview happened to be scrolled, and
     * leaving it put the editor back where it was before — pages away from what had just been read.
     */
    void entered(MarkdownViewMode from, MarkdownViewMode to) {
        markCaretBlock(); // shown in Split only
        double line = carriedLine;
        carriedLine = -1;
        pendingLine = -1;
        if (line >= 0 && from == MarkdownViewMode.PREVIEW && to != MarkdownViewMode.PREVIEW) {
            // The editor was off screen: put it where the preview was read.
            Platform.runLater(() -> ScrollAnchor.restore(area, (int) Math.floor(line), editorScrollY()));
        }
        if (line >= 0 && to != MarkdownViewMode.EDITOR) {
            pendingLine = line; // the render requested by this switch comes up there
        }
        if (to == MarkdownViewMode.SPLIT) {
            Platform.runLater(this::previewFollowsEditor); // (metrics settle first)
        }
    }
}
