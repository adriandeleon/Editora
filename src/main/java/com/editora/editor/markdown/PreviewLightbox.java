package com.editora.editor.markdown;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Popup;

import org.jspecify.annotations.Nullable;

/**
 * A picture of the Markdown preview shown enlarged over the app's window: dimmed backdrop, the image as large
 * as the window allows (a small one is magnified, at most {@link #MAX_MAGNIFICATION} times). Esc, a click
 * anywhere, or the window losing the focus closes it. Nothing leaves the app and nothing is written.
 */
public final class PreviewLightbox {

    /** A 16px icon is not blown up to fill the window. */
    static final double MAX_MAGNIFICATION = 4;
    /** Kept free around the picture, in px. */
    private static final double MARGIN = 32;

    private final Popup popup = new Popup();
    private final ImageView view = new ImageView();

    private PreviewLightbox() {}

    /** The loaded image inside {@code picture} (an image's slot, a diagram's host), or null if none is. */
    public static @Nullable Image imageOf(@Nullable Node picture) {
        if (picture instanceof ImageView iv) {
            Image image = iv.getImage();
            return image == null || image.isError() || image.getWidth() <= 0 ? null : image;
        }
        if (picture instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Image found = imageOf(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The scale that shows a {@code w}×{@code h} image in a {@code maxW}×{@code maxH} area. Pure. */
    static double scale(double w, double h, double maxW, double maxH) {
        if (w <= 0 || h <= 0 || maxW <= 0 || maxH <= 0) {
            return 1;
        }
        return Math.min(MAX_MAGNIFICATION, Math.min(maxW / w, maxH / h));
    }

    /** Shows {@code image} over the window {@code owner} is in. */
    static PreviewLightbox show(Node owner, Image image) {
        PreviewLightbox box = new PreviewLightbox();
        Scene scene = owner.getScene();
        Bounds area = scene.getRoot().localToScreen(scene.getRoot().getBoundsInLocal());
        double w = Math.max(1, area.getWidth());
        double h = Math.max(1, area.getHeight());
        double scale = scale(image.getWidth(), image.getHeight(), w - 2 * MARGIN, h - 2 * MARGIN);
        box.view.setImage(image);
        box.view.setPreserveRatio(true);
        box.view.setSmooth(true);
        box.view.setFitWidth(image.getWidth() * scale);
        StackPane backdrop = new StackPane(box.view);
        backdrop.getStyleClass().add("md-lightbox");
        backdrop.setStyle("-fx-background-color: rgba(0, 0, 0, 0.78);");
        backdrop.setPrefSize(w, h);
        backdrop.setMinSize(w, h);
        backdrop.setMaxSize(w, h);
        backdrop.setOnMouseClicked(e -> box.close());
        backdrop.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                box.close();
                e.consume();
            }
        });
        box.popup.getContent().add(backdrop);
        box.popup.setAutoHide(true);
        box.popup.setHideOnEscape(true);
        box.popup.setAutoFix(false);
        box.popup.show(owner, area.getMinX(), area.getMinY());
        return box;
    }

    boolean isShowing() {
        return popup.isShowing();
    }

    void close() {
        popup.hide();
    }

    /** The enlarged picture (tests). */
    ImageView view() {
        return view;
    }

    /** The window it is shown in (tests send it Esc). */
    Popup popup() {
        return popup;
    }
}
