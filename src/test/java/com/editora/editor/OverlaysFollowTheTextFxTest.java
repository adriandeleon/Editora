package com.editora.editor;

import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.editora.text.OccurrenceSpan;
import com.editora.text.SearchMatches;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The overlays drawn over the text paint after the layout pass ({@link AfterLayoutPaint}): in every frame shown
 * while the gutter changes width — the 1,000th line widening the line numbers on the fold recompute's debounce,
 * the line numbers turned off and on — what they draw is where the text is, not where it was a frame before.
 */
@Tag("fx")
class OverlaysFollowTheTextFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    @Test
    void searchMatchesAreOnTheTextInEveryFrame() throws Exception {
        everyFrameOnTheText("searchOverlay", b -> b.setSearchMatches(SearchMatches.of(10, 14), 0), 10);
    }

    @Test
    void occurrencesAreOnTheTextInEveryFrame() throws Exception {
        everyFrameOnTheText(
                "occurrenceOverlay", b -> b.setOccurrenceSpans(List.of(new OccurrenceSpan(0, 10, 0, 14, false))), 10);
    }

    @Test
    void whitespaceMarkersAreOnTheTextInEveryFrame() throws Exception {
        everyFrameOnTheText("whitespace", b -> b.setWhitespaceVisible(true), 0);
    }

    /**
     * Turns an overlay on with {@code show} in a 999-line file, then checks every frame after each gutter change:
     * the leftmost thing it paints on the first line keeps its distance from the character at {@code column}.
     */
    private static void everyFrameOnTheText(String field, Consumer<EditorBuffer> show, int column) throws Exception {
        EditorBuffer[] buffer = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            buffer[0] = new EditorBuffer();
            buffer[0].setInitialContent("    alpha beta gamma\n".repeat(998) + "end"); // 999 lines
            buffer[0].setFont("Monospaced", 14);
            stage[0] = EditorFx.show(buffer[0], 700, 300);
            buffer[0].getArea().showParagraphAtTop(0);
            show.accept(buffer[0]);
        });
        EditorBuffer b = buffer[0];
        try {
            double offset = settledOffset(b, field, column);
            assertFalse(Double.isNaN(offset), field + " paints on the first line");
            Object overlay = EditorFx.callFx(() -> EditorFx.field(b, field));
            java.util.function.Supplier<String> check = () -> {
                double now = paintedLeft(b, field) - charX(b, field, column);
                return Math.abs(now - offset) <= 1.5
                        ? null
                        : "painted " + now + " px from the text, " + offset + " when settled";
            };

            double before = EditorFx.callFx(() -> charX(b, field, column));
            assertEquals(
                    List.of(),
                    FrameWatch.wrongFrames(b, overlay, check, () -> b.getArea().appendText("\nmore")),
                    field + " at the 1,000th line");
            assertTrue(EditorFx.callFx(() -> charX(b, field, column)) > before + 2, "the fixture widens the gutter");

            assertEquals(
                    List.of(),
                    FrameWatch.wrongFrames(b, overlay, check, () -> b.setLineNumbersVisible(false)),
                    field + " with the line numbers turned off");
            assertEquals(
                    List.of(),
                    FrameWatch.wrongFrames(b, overlay, check, () -> b.setLineNumbersVisible(true)),
                    field + " with the line numbers back");
        } finally {
            EditorFx.onFx(() -> {
                stage[0].close();
                b.dispose();
            });
        }
    }

    /** The painted-minus-text offset on the first line, once it reads the same twice (up to three seconds). */
    private static double settledOffset(EditorBuffer b, String field, int column) throws Exception {
        double last = Double.NaN;
        for (int attempt = 0; attempt < 60; attempt++) {
            EditorFx.drain();
            double now = EditorFx.callFx(() -> paintedLeft(b, field) - charX(b, field, column));
            if (now == last) {
                return now;
            }
            last = now;
            Thread.sleep(50);
        }
        return last;
    }

    /** The x of the leftmost painted pixel across the middle of the first line, in the overlay's coordinates. */
    private static double paintedLeft(EditorBuffer b, String field) {
        Node overlay = EditorFx.field(b, field);
        Bounds box = b.getArea()
                .getParagraphBoundsOnScreen(0)
                .map(overlay::screenToLocal)
                .orElse(null);
        if (box == null) {
            return Double.NaN;
        }
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        WritableImage img = overlay.snapshot(params, null);
        PixelReader px = img.getPixelReader();
        int y = (int) Math.round(box.getCenterY());
        if (y < 0 || y >= img.getHeight()) {
            return Double.NaN;
        }
        for (int x = 0; x < img.getWidth(); x++) {
            if ((px.getArgb(x, y) >>> 24) > 8) {
                return x;
            }
        }
        return Double.NaN;
    }

    /** The left edge of character {@code column} of the first line, in the overlay's coordinates. */
    private static double charX(EditorBuffer b, String field, int column) {
        Node overlay = EditorFx.field(b, field);
        int abs = b.getArea().getAbsolutePosition(0, column);
        return b.getArea()
                .getCharacterBoundsOnScreen(abs, abs + 1)
                .map(overlay::screenToLocal)
                .map(Bounds::getMinX)
                .orElse(Double.NaN);
    }
}
