package com.editora.ui;

import java.util.Locale;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;

import com.editora.http.HttpResponseFormat;

import static com.editora.i18n.Messages.tr;

/**
 * An image response in the HTTP Client: a caption saying what it is ("PNG · 160 × 160 · 858 B"), Fit / 100%
 * toggles, and the picture on a checkerboard so transparent pixels read as transparent on any theme. Fit (the
 * default) scales a picture down to the width available and never up; 100% shows one image pixel per pixel.
 */
final class HttpImageView extends VBox {

    private static final double PAD = 8;

    private final Label caption = new Label();
    private final ToggleButton fit = segment(tr("httppanel.imageFit"), tr("httppanel.imageFit.tip"));
    private final ToggleButton actual = segment(tr("httppanel.imageActualSize"), tr("httppanel.imageActualSize.tip"));
    private final ImageView picture = new ImageView();
    private final StackPane frame = new StackPane(picture);
    private final ScrollPane scroll;

    HttpImageView() {
        getStyleClass().add("http-image-view");
        caption.getStyleClass().add("http-image-caption");
        caption.setMinWidth(0);
        ToggleGroup group = new ToggleGroup();
        fit.setToggleGroup(group);
        actual.setToggleGroup(group);
        fit.setSelected(true);
        // A toggle group lets the selected button be clicked off; one of the two is always on.
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) {
                group.selectToggle(was);
            }
            layoutPicture();
        });
        HBox toggles = new HBox(2, fit, actual);
        toggles.getStyleClass().add("http-image-toggles");
        toggles.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, caption, spacer, toggles);
        bar.getStyleClass().add("http-image-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        picture.setPreserveRatio(true);
        picture.setSmooth(true);
        frame.getStyleClass().add("http-image-frame");
        frame.setBackground(new Background(new BackgroundFill(Checker.PATTERN, null, null)));
        frame.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane stage = new StackPane(frame);
        stage.getStyleClass().add("http-image-stage");
        stage.setAlignment(Pos.TOP_CENTER);
        stage.setPadding(new Insets(PAD));
        scroll = new ScrollPane(stage);
        scroll.getStyleClass().add("http-image-scroll");
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        // Fit follows the space the panel gives: a wider panel shows more of a large picture, up to its own size.
        scroll.viewportBoundsProperty().addListener((o, a, b) -> layoutPicture());
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(bar, scroll);
    }

    /** Shows {@code image}, captioned from its content type and the response's size in bytes. */
    void show(Image image, String contentType, long bytes) {
        picture.setImage(image);
        caption.setText(
                image == null ? "" : caption(contentType, (int) image.getWidth(), (int) image.getHeight(), bytes));
        layoutPicture();
    }

    void clear() {
        show(null, null, 0);
    }

    boolean hasImage() {
        return picture.getImage() != null;
    }

    /** "PNG · 160 × 160 · 858 B": the format (from the content type), the size in pixels, the size in bytes. Pure. */
    static String caption(String contentType, int width, int height, long bytes) {
        return tr("httppanel.imageCaption", format(contentType), width, height, HttpResponseFormat.humanSize(bytes));
    }

    /** {@code image/png} → PNG, {@code image/x-icon} → ICO, {@code image/svg+xml} → SVG; unknown → IMAGE. Pure. */
    static String format(String contentType) {
        String ct =
                contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).strip();
        int semi = ct.indexOf(';');
        if (semi >= 0) {
            ct = ct.substring(0, semi).strip();
        }
        int slash = ct.indexOf('/');
        String sub = slash < 0 ? "" : ct.substring(slash + 1);
        int plus = sub.indexOf('+');
        if (plus >= 0) {
            sub = sub.substring(0, plus);
        }
        if (sub.startsWith("x-")) {
            sub = sub.substring(2);
        }
        return switch (sub) {
            case "" -> "IMAGE";
            case "jpg", "pjpeg" -> "JPEG";
            case "icon", "vnd.microsoft.icon" -> "ICO";
            case "ms-bmp" -> "BMP";
            default -> sub.toUpperCase(Locale.ROOT);
        };
    }

    private void layoutPicture() {
        Image image = picture.getImage();
        if (image == null) {
            picture.setFitWidth(0);
            return;
        }
        if (actual.isSelected()) {
            picture.setFitWidth(0); // the image's own size
            return;
        }
        Insets frameInsets = frame.getInsets(); // its border: counted, or Fit would always overflow by two pixels
        double room = scroll.getViewportBounds().getWidth() - 2 * PAD - frameInsets.getLeft() - frameInsets.getRight();
        // Before the first layout the viewport is empty: show the natural size rather than nothing.
        picture.setFitWidth(room > 1 ? Math.min(image.getWidth(), Math.floor(room)) : 0);
    }

    private static ToggleButton segment(String label, String tip) {
        ToggleButton b = new ToggleButton(label);
        b.getStyleClass().addAll("md-viewmode-segment", "flat");
        b.setTooltip(new Tooltip(tip));
        b.setAccessibleHelp(tip);
        return b;
    }

    /** Squares of half-transparent grey: a little darker than a light theme's background, lighter than a dark one's. */
    private static final class Checker {
        static final ImagePattern PATTERN = checker(); // made on first use: needs the toolkit, which caption() does not
    }

    private static ImagePattern checker() {
        int cell = 8;
        WritableImage tile = new WritableImage(2 * cell, 2 * cell);
        PixelWriter w = tile.getPixelWriter();
        Color dark = Color.gray(0.5, 0.28);
        Color light = Color.gray(0.5, 0.08);
        for (int y = 0; y < 2 * cell; y++) {
            for (int x = 0; x < 2 * cell; x++) {
                w.setColor(x, y, ((x / cell) + (y / cell)) % 2 == 0 ? dark : light);
            }
        }
        return new ImagePattern(tile, 0, 0, 2 * cell, 2 * cell, false);
    }

    // --- test hooks ---

    String captionForTest() {
        return caption.getText();
    }

    boolean fitSelectedForTest() {
        return fit.isSelected();
    }

    ToggleButton fitButtonForTest() {
        return fit;
    }

    ToggleButton actualSizeButtonForTest() {
        return actual;
    }

    /** The width the picture is drawn at. */
    double shownWidthForTest() {
        return picture.getLayoutBounds().getWidth();
    }
}
