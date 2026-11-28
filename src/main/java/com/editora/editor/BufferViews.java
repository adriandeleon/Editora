package com.editora.editor;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import com.editora.editor.minimap.Minimap;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * How a buffer's views look and render: the split's second view (built on first split), the font, the line
 * highlight, word wrap and its re-measure, the minimap, show-whitespace, and pausing the per-frame work of a tab
 * that is in the background.
 *
 * <p>Moved out of {@code EditorBuffer} for size.
 */
final class BufferViews {

    private final EditorBuffer b;

    BufferViews(EditorBuffer b) {
        this.b = b;
    }

    @Nullable
    private Minimap minimap2;
    /** Minimap block + viewport colors; vary per editor theme (see {@link #setMinimapColors}); neutral until then. */
    private Color minimapText = Color.gray(0.6);

    private Color minimapViewport = Color.gray(0.5, 0.14);
    /** Lazily builds the secondary view (scroll pane + its own minimap) sharing this document. */
    void ensureSecondaryView() {
        if (b.area2 != null) {
            b.refreshGutter(); // marks and folds changed while it was off screen
            return;
        }
        b.area2 = b.tagRename.newArea(b.area.getContent()); // shares the EditableStyledDocument
        b.area2.getStyleClass().add("editor-area");
        b.area2.wrapTextProperty().bind(b.area.wrapTextProperty()); // one Word Wrap setting, two views
        b.area2.setLineHighlighterOn(b.area.isLineHighlighterOn());
        b.area2.setUndoManager(b.area.getUndoManager()); // one history for the shared document
        b.area2.setEditable(b.area.isEditable());
        b.typingAids.addViewModePaging(b.area2); // same pager keys in the secondary split view
        b.completionActions.addCompletionKeys(b.area2);
        b.completionActions.installCommitCharacters(b.area2);
        b.snippetKeys.addSnippetKeys(b.area2);
        b.typingAids.addAutoClose(b.area2);
        b.typingAids.addAutoIndent(b.area2);
        b.completionActions.installCompletionTrigger(b.area2);
        b.lspAssists.installOccurrenceTrigger(b.area2); // LSP document highlight (#675)
        b.inlayDecor.installCodeLensClick(b.area2);
        if (b.inlayDecor.inlayFactoryInstalled) {
            b.area2.setInlayFactory(b.inlayDecor::inlaysOn); // hints, lenses and results set before the split opened
            b.inlayDecor.attachSwatches(b.area2);
        }
        b.imageDrop.install(b.area2);
        if (b.caretOps.multiCaretEnabled && !b.hugeFile && b.caretOps.multiCaret2 == null) {
            b.caretOps.multiCaret2 =
                    MultiCarets.install(b.area2, b.typingAids::tabEdit); // same multi-caret add-on in the split view
        }
        b.area2.setLineHighlighterFill(b.lineHighlightColor);
        b.refreshGutter();
        b.area2.setStyle("-fx-font-family: \"" + b.fontFamily + "\"; -fx-font-size: " + b.fontSize + "px;");
        b.area2.caretPositionProperty().addListener((obs, old, now) -> {
            b.resetGoalColumn();
            b.scheduleBraceMatch();
            b.bulb.schedule();
        });
        b.area2.focusedProperty().addListener((obs, was, now) -> {
            CodeArea split = b.area2;
            if (now && split != null) {
                b.focusedArea = split;
                b.focusedView.set(split);
            }
        });
        b.wiring.installFormatBarListeners(b.area2);
        b.editorMenu.installContextMenu(b.area2);
        b.scrollPane2 = new VirtualizedScrollPane<>(b.area2);
        // Give the secondary view its own minimap (tracks this pane's viewport), docked like the primary.
        minimap2 = b.minimap.follower(b.area2);
        b.pane2 = new SecondaryPane(b.area2, b.scrollPane2, minimap2, b.scrollPane, b.minimap);
        b.root2 = b.pane2.root();
        b.pane2.follow(b.root.getChildren()); // the same overlays and stripes as pane 1, in the same stack
        b.wiring.installPointerHovers(b.area2);
        applyMinimap(b.scrollPane2, minimap2, b.minimapVisible && !b.largeFile && !b.heavyFile);
    }
    /** Docks/undocks a minimap on the right of its scroll pane (shared by both split panes). */
    private static void applyMinimap(@Nullable Region scroll, Minimap mm, boolean visible) {
        mm.setVisible(visible);
        mm.setManaged(visible);
        AnchorPane.setRightAnchor(scroll, visible ? Minimap.WIDTH : 0d);
    }
    /** Applies the editor font, overriding the stylesheet defaults. */
    void setFont(String family, int size) {
        if (b.fontApplied && java.util.Objects.equals(family, b.fontFamily) && size == b.fontSize) {
            return; // unchanged: skip the area-wide restyle + the ruler re-measure (a forced VirtualFlow layout)
        }
        b.fontApplied = true;
        b.fontFamily = family;
        b.fontSize = size;
        String style = "-fx-font-family: \"" + family + "\"; -fx-font-size: " + size + "px;";
        b.area.setStyle(style);
        if (b.area2 != null) {
            b.area2.setStyle(style);
        }
        if (b.whitespace != null) {
            b.whitespace.setFont(family, size); // an overlay attached later takes the font from the fields
        }
        if (b.indentGuides != null) {
            b.indentGuides.setFont(family, size);
        }
        b.spell.geometryChanged();
        if (b.inlineValues != null) {
            b.inlineValues.setFont(family, size);
        }
        b.stickyScroll.setFont(family, size);
        if (b.gitLines.blame() != null) {
            // The blame annotation column width is font-relative — recompute + rebuild so it stays aligned.
            b.blameColumnWidth = b.gutter.measureBlameColumnWidth(b.gitLines.blame());
            b.refreshGutter();
        }
        b.rulerPlacement.markRulerInputsDirty(); // the glyph advance changed
    }
    /** Toggle the highlight on the line containing the caret. */
    void setLineHighlightOn(boolean on) {
        // Not in large-file mode: it lays out the caret's whole line, ~270 ms a keystroke on a 2 MB line.
        LineHighlighter.set(b.area, on, b.largeFile || b.loading);
        if (b.area2 != null) {
            LineHighlighter.set(b.area2, on, b.largeFile || b.loading);
        }
    }
    /** Sets the current-line highlight color (varies per editor theme; not stylable via CSS). */
    void setLineHighlightColor(Color color) {
        b.lineHighlightColor = color;
        b.area.setLineHighlighterFill(color);
        if (b.area2 != null) {
            b.area2.setLineHighlighterFill(color);
        }
    }
    /**
     * Marks whether this buffer is the active (visible) tab. A background tab releases per-buffer
     * GPU-backed caches at once — the minimap snapshot and every {@link TabSurface} overlay's canvas — so
     * retained VRAM doesn't scale with the number of open files; they regenerate when the tab is shown
     * again. What it holds on the heap only for drawing goes later, if it stays hidden (see
     * {@link #releaseHiddenCaches}). Called by the controller on tab selection and when a tab is added in
     * the background.
     */
    void setRenderingActive(boolean active) {
        boolean wasInactive = !b.renderingActive;
        b.renderingActive = active;
        if (!active) b.snippetSession.cancel(); // a background tab: Tab must not come back to its fields
        if (active && wasInactive) {
            b.rulerPlacement.scheduleRulerMeasure(); // catch up on measures skipped while the tab was hidden
        }
        b.minimap.setRenderingActive(active);
        // A full-viewport overlay keeps a Canvas + RTTexture alive for a tab the user cannot see. The
        // stripes are narrow (small textures); every overlay that can hold a viewport-sized one lets go.
        for (Node child : b.root.getChildren()) {
            if (child instanceof TabSurface surface) {
                surface.setRenderingActive(active);
            }
        }
        if (active) {
            hiddenRelease.cancel();
        } else {
            hiddenRelease.start(); // heap-side caches go later, if the tab stays hidden (releaseHiddenCaches)
        }
    }

    final HiddenTabTimer hiddenRelease =
            new HiddenTabTimer(EditorBuffer.HIDDEN_RELEASE_DELAY, this::releaseHiddenCaches);
    /**
     * What a tab hidden for {@link EditorBuffer#HIDDEN_RELEASE_DELAY} gives back — only what is cheaply rebuilt on
     * demand: the cached whole-document String when it is a second copy (a dirty buffer's; a clean buffer's
     * snapshot <em>is</em> its saved baseline, so dropping it would free nothing), and the spell overlay's
     * per-line spans and squiggle geometry. Undo entries and Undo History checkpoints are user data and stay.
     * Package-private so a test can run it without waiting.
     */
    void releaseHiddenCaches() {
        if (b.renderingActive || b.disposed) {
            return;
        }
        b.documentSnapshots.releaseUnless(b.baseline == null ? null : b.baseline.heldText());
        b.spellOverlay.releaseHiddenCaches();
    }
    /**
     * Nudges the just-revealed editor area to repaint after a {@code TabPane} tab switch. Flowless's
     * {@code VirtualFlow} lays out synchronously while the tab content is being swapped in — before its
     * viewport bounds are established — so it can present <em>blank</em> until the next layout pulse (which,
     * before this, only a second click into the area supplied). A deferred {@code requestLayout} runs on the
     * following pulse, once bounds are set, forcing a re-measure + repaint of the visible paragraphs. It does
     * <em>not</em> move the scroll position or steal focus, so a Find-in-Files preview that keeps focus in its
     * results tree is unaffected. Called by the controller on tab selection.
     */
    void onTabShown() {
        javafx.application.Platform.runLater(() -> {
            b.area.requestLayout();
            if (b.area2 != null) {
                b.area2.requestLayout();
            }
        });
    }
    /** Sets the minimap's block and viewport-overlay colors (the minimap is canvas-drawn, not CSS). */
    void setMinimapColors(Color text, Color viewport) {
        this.minimapText = text;
        this.minimapViewport = viewport;
        b.minimap.setColors(text, viewport);
    }
    /** Toggles soft word wrap on the editor surface (and the split view); the 80-column ruler stays visible. */
    void setWordWrap(boolean wrap) {
        boolean changed = wrap != b.area.isWrapText();
        if (changed) {
            b.rulerPlacement.markRulerInputsDirty(); // wrapping re-flows the text and removes the horizontal scroll
        }
        b.area.setWrapText(wrap); // the split's second view is bound to this
        if (!changed) {
            return;
        }
        cancelWrapRemeasure();
        if (wrap) {
            startWrapRemeasure();
        }
    }
    /** Per-pulse time budget for {@link #startWrapRemeasure}, so a big document never blocks a frame. */
    private static final long WRAP_REMEASURE_BUDGET_NANOS = 6_000_000L;

    private javafx.animation.@Nullable AnimationTimer wrapRemeasure;
    /**
     * Makes a just-enabled word wrap take effect. Flowless caches the minimum width of every paragraph it has
     * ever laid out and lays all cells out at the widest cached value, but on a change it only drops the
     * entries of cells that are currently realized. An unwrapped long line scrolled past earlier therefore
     * keeps the whole view at its old width: nothing wraps and the horizontal scrollbar stays. There is no
     * public way to clear that cache, so this realizes every non-empty paragraph once (in budgeted slices,
     * one per pulse) so the next layout pass re-measures it, after which Flowless drops the cell again.
     */
    private void startWrapRemeasure() {
        List<CodeArea> areas = b.area2 == null ? List.of(b.area) : List.of(b.area, b.area2);
        wrapRemeasure = new javafx.animation.AnimationTimer() {
            private int areaIndex;
            private int next;

            @Override
            public void handle(long now) {
                long deadline = System.nanoTime() + WRAP_REMEASURE_BUDGET_NANOS;
                while (areaIndex < areas.size()) {
                    CodeArea target = areas.get(areaIndex);
                    if (target.getScene() == null) {
                        areaIndex++; // not showing: no layout pass would release the realized cells
                        next = 0;
                        continue;
                    }
                    int size = target.getParagraphs().size();
                    while (next < size && System.nanoTime() < deadline) {
                        if (target.getParagraphLength(next) > 0) {
                            target.getParagraphLinesCount(next); // realizes the cell (an empty line is never wide)
                        }
                        next++;
                    }
                    // Only a cell whose layout is dirty is measured again, and getParagraphLinesCount lays the cell
                    // out itself. A cell the flow placed with a width it measured before wrap was on (one it shows
                    // again after scrolling back, say) would keep that width, and with it the whole view's.
                    for (Node box : target.lookupAll(".paragraph-box")) {
                        if (box instanceof javafx.scene.Parent cell) {
                            cell.requestLayout();
                        }
                    }
                    target.requestLayout();
                    if (next < size) {
                        return; // continue after this pulse's layout has re-measured the slice
                    }
                    areaIndex++;
                    next = 0;
                }
                cancelWrapRemeasure();
            }
        };
        wrapRemeasure.start();
    }

    void cancelWrapRemeasure() {
        if (wrapRemeasure != null) {
            wrapRemeasure.stop();
            wrapRemeasure = null;
        }
    }
    /** Show/hide the minimap overview (on every split pane); reclaims its width for the editor when hidden. */
    void setMinimapVisible(boolean visible) {
        b.minimapVisible = visible;
        boolean effective = EditorBuffer.minimapEffective(visible, b.largeFile, b.markdownViewMode);
        applyMinimap(b.scrollPane, b.minimap, effective);
        for (Node child : b.root.getChildren()) {
            if (child.getProperties().containsKey(BufferOverlays.OVER_TEXT)) {
                AnchorPane.setRightAnchor(child, AnchorPane.getRightAnchor(b.scrollPane));
            }
        }
        if (minimap2 != null) {
            applyMinimap(b.scrollPane2, minimap2, effective);
        }
        b.updateDiagnosticStripe(); // show the scrollbar stripe when the minimap no longer carries marks
        b.viewLayout.positionCornerControls(); // keep floating editor controls clear of the minimap
    }
    /** Show/hide the "hidden characters" markers (spaces, tabs, line ends). */
    void setWhitespaceVisible(boolean visible) {
        if (visible || b.whitespace != null) { // turning it off on a buffer that never had it builds nothing
            b.whitespace().setActive(visible);
        }
    }
}
