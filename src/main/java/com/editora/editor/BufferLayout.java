package com.editora.editor;

import java.util.List;

import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import com.editora.editor.fold.StickyScrollBar;
import com.editora.editor.markdown.ScrollAnchor;
import com.editora.editor.minimap.Minimap;
import org.jspecify.annotations.Nullable;

/**
 * What a buffer's tab shows and where its floating controls sit: the code pane alone, a code split, or the
 * code pane beside (or replaced by) its preview, rebuilt by {@link #rebuildViewHost} whenever the mode or the
 * split changes; the one row of top-right controls (view toggle, open-in-browser, {@code .http} environment,
 * Expert exit, problem counter) kept clear of the scrollbar and minimap; and the sticky-scroll bar's place.
 */
final class BufferLayout {

    private final EditorBuffer b;

    BufferLayout(EditorBuffer b) {
        this.b = b;
    }

    /** Expert-mode exit control hosted over this buffer's primary code viewport, when it is active. */
    private @Nullable Node expertExitControl;

    /**
     * The one row holding every floating control at the top-right of the code pane (view-mode toggle,
     * open-in-browser, log controls, Expert exit). Sharing a row is what keeps them from overlapping.
     */
    private final HBox cornerControls = cornerControlRow();

    /** The floating Editor/Split/Preview control overlaid top-right (injected for Markdown buffers). */
    private @Nullable Node viewModeControl;
    /** The floating "open in browser" control overlaid top-right (injected for HTML buffers). */
    private @Nullable Node htmlPreviewControl;
    /** The {@code .http} environment pill, kept beside the Editor/Split/Preview toggle in every mode. */
    private @Nullable Node httpEnvControl;
    /** Holds the environment pill and the view toggle side by side over a preview (Split and Preview modes). */
    private final HBox previewCornerRow = previewCornerRow();
    /** Overlays the Editor/Split/Preview control top-right of this buffer's view; {@code null} removes it. */
    void setViewModeControl(@Nullable Node control) {
        this.viewModeControl = control;
        rebuildViewHost();
    }

    /** Whether the floating Editor/Split/Preview control is currently attached. */
    boolean hasViewModeControl() {
        return viewModeControl != null;
    }

    /** Overlays the HTML "open in browser" control top-right of the code pane; {@code null} removes it. */
    void setHtmlPreviewControl(@Nullable Node control) {
        if (htmlPreviewControl != null && htmlPreviewControl != control) {
            removeCornerControl(htmlPreviewControl);
        }
        this.htmlPreviewControl = control;
        rebuildViewHost();
    }

    /** Whether the floating HTML "open in browser" control is currently attached. */
    boolean hasHtmlPreviewControl() {
        return htmlPreviewControl != null;
    }

    /**
     * Overlays a {@code .http} file's environment pill beside the Editor/Split/Preview toggle — on the code pane in
     * Editor mode, over the response view in Split and Preview, so it is there in every mode; {@code null} removes it.
     */
    void setHttpEnvControl(@Nullable Node control) {
        if (httpEnvControl == control) {
            return;
        }
        if (httpEnvControl != null) {
            removeCornerControl(httpEnvControl);
            previewCornerRow.getChildren().remove(httpEnvControl);
        }
        this.httpEnvControl = control;
        rebuildViewHost();
    }

    /** Whether the {@code .http} environment pill is attached. */
    boolean hasHttpEnvControl() {
        return httpEnvControl != null;
    }

    /**
     * Rebuilds {@link EditorBuffer#viewHost} for the current modes. The floating control is parented to the editor
     * pane ({@link EditorBuffer#root}) in Editor mode, at the right edge of the code area; in Split and Preview it
     * overlays the preview — on a split's half-width code pane it sat over the first line of text, and
     * this way it also stays in the tab's top-right corner in all three modes.
     */
    void rebuildViewHost() {
        detachViewModeControl();
        Node content;
        if (b.markdownViewMode != EditorBuffer.MarkdownViewMode.EDITOR) {
            StackPane host = b.previewPanes.previewModeHost();
            Node corner = previewCorner();
            if (corner != null) {
                StackPane.setAlignment(corner, Pos.TOP_RIGHT);
                UiZoom.follow(corner, () -> StackPane.setMargin(corner, UiZoom.insets(6, 10, 0, 0)));
                host.getChildren().add(corner);
            }
            content = host;
            if (b.markdownViewMode == EditorBuffer.MarkdownViewMode.SPLIT) {
                SplitPane pane = new SplitPane(b.root, host);
                pane.setOrientation(Orientation.HORIZONTAL);
                pane.setDividerPositions(b.previewPanes.previewDivider); // where it was left, not back to the middle
                pane.getDividers().get(0).positionProperty().addListener((o, was, now) -> {
                    if (pane.isPressed()
                            && Math.abs(now.doubleValue() - b.previewPanes.previewDivider) > 0.002) { // a drag
                        b.previewPanes.previewDivider = now.doubleValue();
                        b.previewPanes.onPreviewLayoutChanged.run();
                    }
                });
                attachControlToCodePane();
                content = pane;
            }
        } else if (b.split != EditorBuffer.Split.NONE) {
            b.ensureSecondaryView();
            SplitPane pane = new SplitPane(b.root, b.root2);
            pane.setOrientation(
                    b.split == EditorBuffer.Split.SIDE_BY_SIDE ? Orientation.HORIZONTAL : Orientation.VERTICAL);
            pane.setDividerPositions(0.5);
            attachControlToCodePane();
            content = pane;
        } else {
            attachControlToCodePane();
            content = b.root;
        }
        boolean hadFocus = b.area2 != null && b.area2.isFocused(); // leaving a split takes that view off the scene
        int top2 = hadFocus ? ScrollAnchor.firstVisibleLine(b.area2) : -1;
        b.viewHost.getChildren().setAll(content);
        if (b.area2 != null
                && (b.split == EditorBuffer.Split.NONE || b.markdownViewMode != EditorBuffer.MarkdownViewMode.EDITOR)) {
            b.focusedArea = b.area;
            b.focusedView.set(b.area);
            if (hadFocus) {
                // The user was looking at the second view: the one that stays takes over its caret, its
                // selection and its place in the file, and the keyboard (or JavaFX hands that to the first
                // control in the window).
                ScrollAnchor.adopt(b.area, b.area2, top2);
                b.area.requestFocus();
            }
        }
    }

    /** Removes the floating corner control(s) from whichever pane currently hosts them. */
    private void detachViewModeControl() {
        removeCornerControl(viewModeControl);
        removeCornerControl(htmlPreviewControl);
        removeCornerControl(httpEnvControl);
        removeCornerControl(previewCornerRow);
        previewCornerRow.getChildren().clear();
    }

    /**
     * What sits at the top-right of a preview: the view toggle, with the {@code .http} environment pill to its left
     * when the buffer has one (both in one row, so they never overlap).
     */
    private @Nullable Node previewCorner() {
        if (httpEnvControl == null) {
            return viewModeControl;
        }
        previewCornerRow.getChildren().setAll(httpEnvControl);
        if (viewModeControl != null) {
            previewCornerRow.getChildren().add(viewModeControl);
        }
        return previewCornerRow;
    }

    private static HBox previewCornerRow() {
        HBox row = cornerControlRow();
        row.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE); // a StackPane overlay keeps its own size
        return row;
    }

    void removeCornerControl(@Nullable Node control) {
        if (control == null) {
            return;
        }
        cornerControls.getChildren().remove(control);
        if (cornerControls.getChildren().isEmpty()) {
            b.root.getChildren().remove(cornerControls);
        }
        if (b.previewPanes.previewHost != null) {
            b.previewPanes.previewHost.getChildren().remove(control);
        }
        if (b.previewPanes.csvPreviewHost != null) {
            b.previewPanes.csvPreviewHost.getChildren().remove(control);
        }
        if (b.previewPanes.httpPreviewHost != null) {
            b.previewPanes.httpPreviewHost.getChildren().remove(control);
        }
    }

    /** Overlays the corner control(s) at the top-right of the code pane ({@link EditorBuffer#root}), clear of its minimap.
     *  They share one row ({@link #cornerControls}), so however many are present they sit side by side. */
    private void attachControlToCodePane() {
        placeCornerControl(b.markdownViewMode == EditorBuffer.MarkdownViewMode.EDITOR ? httpEnvControl : null);
        placeCornerControl(b.markdownViewMode == EditorBuffer.MarkdownViewMode.EDITOR ? viewModeControl : null);
        placeCornerControl(htmlPreviewControl);
        placeStickyScroll();
    }

    /**
     * Anchors the sticky-scroll bar across the top of the code pane. Unlike the corner controls it spans
     * the width, so it clears the scrollbar gutter on the right and the minimap beyond it — a pinned line
     * running under either would be clipped mid-token.
     */
    void placeStickyScroll() {
        Node bar = b.stickyScroll.node();
        StickyScrollBar.anchor(bar, codePaneControlInset()); // writes only a changed constraint
        if (bar.getParent() != b.root) {
            b.root.getChildren().add(bar);
            // The bar is attached lazily when scrolling first produces pinned lines, while file-specific
            // controls may already be present. AnchorPane paints later children on top, so without an
            // explicit order that timing lets the full-width bar cover Markdown's view-mode toggle or
            // HTML's browser button. A control row attached after the bar is added last and so is above it.
            if (cornerControls.getParent() == b.root) {
                cornerControls.toFront();
            }
        }
    }

    /**
     * Adds {@code control} to the shared top-right row, mounting the row on first use. The Expert exit
     * control stays outermost so it does not move when a file-specific control comes and goes.
     */
    void placeCornerControl(@Nullable Node control) {
        if (control == null) {
            return;
        }
        List<Node> row = cornerControls.getChildren();
        if (!row.contains(control)) {
            int expert = control == expertExitControl ? -1 : row.indexOf(expertExitControl);
            row.add(expert < 0 ? row.size() : expert, control);
        }
        if (cornerControls.getParent() != b.root) {
            b.root.getChildren().add(cornerControls);
            UiZoom.follow(cornerControls, () -> {
                AnchorPane.setTopAnchor(cornerControls, UiZoom.px(6));
                positionCornerControls();
            });
        }
        AnchorPane.setRightAnchor(cornerControls, codePaneControlInset());
    }

    /**
     * Width the editor's own vertical scrollbar takes on the right edge of the code pane. The floating
     * controls clear it: sitting on the thumb both looks wrong and swallows the drag, since the control is
     * on top.
     */
    private static final double SCROLLBAR_GUTTER = 14;

    private double codePaneControlInset() {
        return UiZoom.px(SCROLLBAR_GUTTER)
                + ((b.minimapVisible && !b.largeFile && !b.heavyFile) ? Minimap.WIDTH + UiZoom.px(6) : UiZoom.px(10));
    }

    /** Keeps every floating editor control clear of the minimap when it toggles (no full view rebuild). */
    void positionCornerControls() {
        if (cornerControls.getParent() == b.root) {
            AnchorPane.setRightAnchor(cornerControls, codePaneControlInset());
        }
    }

    /**
     * Mounts the Expert-mode exit control inside the primary code pane, clear of its scrollbar and minimap,
     * beside any file-specific corner control. Passing {@code null} detaches the current control. A
     * controller moves the one shared button as the active tab changes, so inactive buffers retain no
     * overlay node.
     */
    void setExpertExitControl(@Nullable Node control) {
        if (expertExitControl != control) {
            removeCornerControl(expertExitControl);
        }
        expertExitControl = control;
        placeCornerControl(control);
    }

    private static HBox cornerControlRow() {
        HBox row = UiZoom.spacing(new HBox(), 6);
        row.setAlignment(Pos.CENTER_RIGHT);
        row.setFillHeight(false); // each control keeps its own height
        row.setPickOnBounds(false); // clicks between controls fall through to the editor
        return row;
    }
}
