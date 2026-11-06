package com.editora.editor;

import javafx.application.Platform;
import javafx.geometry.Bounds;

import com.editora.editor.markdown.MarkdownFormatBar;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * The floating bars over a buffer's selection: the Markdown/Typst format bar and the AI actions bar (Explain,
 * Rewrite), each shown or moved at most once per pulse as the selection, the scroll or the focus changes, the AI bar
 * stacked above the format bar when both apply.
 */
final class SelectionBars {

    private final EditorBuffer b;

    SelectionBars(EditorBuffer b) {
        this.b = b;
    }

    /** Floating Markdown format bar (lazily created), shown on a non-empty selection in a Markdown buffer. */
    private @Nullable MarkdownFormatBar formatBar;

    private boolean formatBarEnabled = true;
    private boolean formatBarUpdatePending;
    /** Floating AI selection-actions bar (Explain/Rewrite), lazily created; shown on any non-empty
     *  selection while AI Actions is enabled + a cached connectivity probe says the endpoint is reachable
     *  (see {@code AiCoordinator.applySupport} — never re-probed per selection). */
    private @Nullable AiActionsBar aiActionsBar;

    boolean aiActionsEnabled;
    private boolean aiActionsBarUpdatePending;
    private Runnable aiExplainHandler = () -> {};
    private Runnable aiRewriteHandler = () -> {};
    /** Enables/disables the floating Markdown format bar (driven from Settings). */
    void setFormatBarEnabled(boolean enabled) {
        this.formatBarEnabled = enabled;
        if (!enabled) {
            hideFormatBar();
        } else {
            scheduleFormatBar();
        }
    }

    void scheduleFormatBar() {
        if (formatBarUpdatePending || !MarkdownFormatBar.updateNeeded(formatBar, formatBarEnabled, b.focusedArea)) {
            return;
        }
        formatBarUpdatePending = true;
        Platform.runLater(this::updateFormatBar);
    }

    void hideFormatBar() {
        if (formatBar != null) {
            formatBar.node().setVisible(false);
        }
    }

    private void updateFormatBar() {
        formatBarUpdatePending = false;
        CodeArea a = b.focusedArea != null ? b.focusedArea : b.area;
        boolean show = formatBarEnabled
                && (b.isMarkdown() || b.isTypst())
                && b.isEditable()
                && !b.hugeFile
                && b.markdownViewMode != EditorBuffer.MarkdownViewMode.PREVIEW
                && MarkdownFormatBar.wanted(a);
        if (!show) {
            hideFormatBar();
            return;
        }
        if (formatBar == null) {
            formatBar = new MarkdownFormatBar(b);
        }
        formatBar.showFor(a, (a == b.area2 && b.root2 != null) ? b.root2 : b.root);
    }

    /** Injects the Explain/Rewrite callbacks (wired once from the controller, like {@link EditorBuffer#setOpenUrlHandler}) —
     *  each is a no-arg action reading the current selection off {@link EditorBuffer#getFocusedArea()} itself. */
    void setAiActionHandlers(Runnable onExplainSelection, Runnable onRewriteSelection) {
        this.aiExplainHandler = onExplainSelection == null ? () -> {} : onExplainSelection;
        this.aiRewriteHandler = onRewriteSelection == null ? () -> {} : onRewriteSelection;
    }

    /** {@link AiActionsBar}'s Explain button. */
    void requestExplainSelection() {
        aiExplainHandler.run();
    }

    /** {@link AiActionsBar}'s Rewrite button. */
    void requestRewriteSelection() {
        aiRewriteHandler.run();
    }

    /** Enables/disables the floating AI selection-actions bar — the effective gate (setting + a cached
     *  connectivity probe), pushed from the controller; never toggled per-selection/keystroke. */
    void setAiActionsEnabled(boolean enabled) {
        this.aiActionsEnabled = enabled;
        if (!enabled) {
            hideAiActionsBar();
        } else {
            scheduleAiActionsBar();
        }
    }

    void scheduleAiActionsBar() {
        if (aiActionsBarUpdatePending || !AiActionsBar.updateNeeded(aiActionsBar, aiActionsEnabled, b.focusedArea)) {
            return;
        }
        aiActionsBarUpdatePending = true;
        Platform.runLater(this::updateAiActionsBar);
    }

    void hideAiActionsBar() {
        if (aiActionsBar != null) {
            aiActionsBar.node().setVisible(false);
        }
    }

    private void updateAiActionsBar() {
        aiActionsBarUpdatePending = false;
        CodeArea a = b.focusedArea != null ? b.focusedArea : b.area;
        boolean show = aiActionsEnabled
                && !b.hugeFile
                && b.markdownViewMode != EditorBuffer.MarkdownViewMode.PREVIEW
                && a.getSelection().getLength() > 0;
        if (!show) {
            hideAiActionsBar();
            return;
        }
        int selStart = a.getSelection().getStart();
        Bounds screen = a.getCharacterBoundsOnScreen(selStart, Math.min(a.getLength(), selStart + 1))
                .orElse(null);
        if (screen == null) {
            hideAiActionsBar();
            return;
        }
        javafx.scene.layout.AnchorPane targetPane = (a == b.area2 && b.root2 != null) ? b.root2 : b.root;
        Bounds local = targetPane.screenToLocal(screen);
        if (local == null) {
            hideAiActionsBar();
            return;
        }
        if (aiActionsBar == null) {
            aiActionsBar = new AiActionsBar(b);
        }
        aiActionsBar.setEditable(b.isEditable());
        javafx.scene.Node bar = aiActionsBar.node();
        if (bar.getParent() != targetPane) {
            if (bar.getParent() instanceof javafx.scene.layout.AnchorPane ap) {
                ap.getChildren().remove(bar);
            }
            targetPane.getChildren().add(bar);
        }
        bar.setVisible(true);
        bar.applyCss();
        double w = bar.prefWidth(-1);
        double h = bar.prefHeight(-1);
        double x = Math.max(0, Math.min(local.getMinX(), Math.max(0, targetPane.getWidth() - w)));
        // The Markdown format bar anchors at the same point — updateFormatBar() runs first (see
        // installFormatBarListeners), so stack above it when both apply to the same selection.
        double stackAbove = (formatBar != null && formatBar.node().isVisible())
                ? formatBar.node().getLayoutBounds().getHeight() + 4
                : 0;
        double y = local.getMinY() - h - 4 - stackAbove;
        if (y < 0) {
            y = local.getMaxY() + 4 + stackAbove;
        }
        bar.resizeRelocate(x, y, w, h);
        bar.toFront();
    }
}
