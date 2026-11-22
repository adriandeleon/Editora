package com.editora.editor;

import javafx.geometry.Bounds;
import javafx.scene.layout.Region;

import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * The underline of a Ctrl/Cmd-hover link ({@link DefinitionLinks}): a mouse-transparent region over the text with one
 * one-pixel bar, placed when a link shows and hidden otherwise. No canvas and nothing painted per scroll. Attached on
 * the first link a buffer shows (a buffer nobody Ctrl-hovers never builds it); the split's second view gets its twin
 * through {@link SecondaryPane#follow}, and {@link #show} hands the bar to whichever of the two is asked for.
 */
final class DefinitionLinkOverlay extends Region implements SecondaryPane.Followed {

    private final CodeArea view;
    private final Region bar = new Region();
    private @Nullable DefinitionLinkOverlay follower;

    DefinitionLinkOverlay(CodeArea view) {
        this.view = view;
        getStyleClass().add("definition-link-overlay");
        bar.getStyleClass().add("definition-link-underline");
        bar.setManaged(false);
        bar.setVisible(false);
        getChildren().add(bar);
        setMouseTransparent(true);
        setPickOnBounds(false);
    }

    @Override
    public DefinitionLinkOverlay follower(CodeArea secondView) {
        DefinitionLinkOverlay twin = new DefinitionLinkOverlay(secondView);
        follower = twin;
        return twin;
    }

    /** Underlines the text at {@code screen} (character bounds on screen) in {@code inView}; false when it cannot. */
    boolean show(CodeArea inView, Bounds screen) {
        if (inView != view) {
            return follower != null && follower.show(inView, screen);
        }
        Bounds b = screenToLocal(screen);
        if (b == null) {
            return false;
        }
        double thickness = Math.max(1, Math.round(UiZoom.px(1)));
        bar.resizeRelocate(b.getMinX(), b.getMaxY() - thickness, b.getWidth(), thickness);
        bar.setVisible(true);
        return true;
    }

    /** Takes the underline away from {@code inView}. */
    void hide(CodeArea inView) {
        if (inView != view) {
            if (follower != null) {
                follower.hide(inView);
            }
            return;
        }
        bar.setVisible(false);
    }

    /** Whether the bar shows in {@code inView}, for tests. */
    boolean showingForTest(CodeArea inView) {
        if (inView != view) {
            return follower != null && follower.showingForTest(inView);
        }
        return bar.isVisible();
    }

    @Override
    protected void layoutChildren() {
        // the bar is placed by show(), not by layout
    }
}
