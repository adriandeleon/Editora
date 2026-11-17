package com.editora.editor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the indent guides are drawn, read off the overlay's pixels and compared with where the text's columns
 * are: they follow the text when the gutter changes width with nothing scrolled or edited, take the column
 * width from the laid-out text rather than a digit of the font, stay off the gutter when the view is scrolled
 * sideways, and fall on the tab stops of a tab-indented file. And what they rely on: every line's text starts
 * at the same x, whatever the line number beside it, in the app's stylesheet.
 */
@Tag("fx")
class IndentGuideOverlayFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    /** {@code lines} lines indented one to six levels of four spaces. */
    private static String nested(int lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            sb.append("    ".repeat(1 + i % 6)).append("<tag>value</tag>\n");
        }
        return sb.toString();
    }

    private static EditorBuffer open(String text, Stage[] stage) throws Exception {
        return open(text, null, stage);
    }

    private static EditorBuffer open(String text, String language, Stage[] stage) throws Exception {
        EditorBuffer[] buffer = new EditorBuffer[1];
        EditorFx.onFx(() -> {
            buffer[0] = new EditorBuffer();
            if (language != null) {
                buffer[0].setLanguageOverride(language); // Go indents with tabs
            }
            buffer[0].getArea().replaceText(text);
            buffer[0].getArea().moveTo(0);
            buffer[0].setFont("Monospaced", 14); // as the app always does: the font's digit is a column here
            stage[0] = EditorFx.show(buffer[0], 900, 400);
            // The app's stylesheet keeps the line numbers monospaced. Without it they take the editor font,
            // and in a proportional one each row's number is as wide as its digits: on macOS, whose system
            // font has proportional digits, line 1's text started 2 px left of line 6's.
            stage[0].getScene()
                    .getStylesheets()
                    .add(IndentGuideOverlayFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            buffer[0].setIndentGuidesVisible(true);
            buffer[0].getArea().showParagraphAtTop(0);
        });
        return buffer[0];
    }

    private static void close(EditorBuffer buffer, Stage stage) throws Exception {
        EditorFx.onFx(() -> {
            stage.close();
            buffer.dispose();
        });
    }

    /** The x of each guide painted across the middle of {@code line}, in the overlay's coordinates. */
    private static List<Double> painted(EditorBuffer buffer, int line) throws Exception {
        EditorFx.drain();
        return EditorFx.callFx(() -> {
            Node overlay = EditorFx.field(buffer, "indentGuides");
            Bounds box = buffer.getArea()
                    .getParagraphBoundsOnScreen(line)
                    .map(overlay::screenToLocal)
                    .orElse(null);
            List<Double> out = new ArrayList<>();
            if (box == null) {
                return out;
            }
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            WritableImage img = overlay.snapshot(params, null);
            PixelReader px = img.getPixelReader();
            int y = (int) Math.round(box.getCenterY());
            if (y < 0 || y >= img.getHeight()) {
                return out;
            }
            int run = -1;
            for (int x = 0; x <= img.getWidth(); x++) {
                boolean on = x < img.getWidth() && (px.getArgb(x, y) >>> 24) > 8;
                if (on && run < 0) {
                    run = x;
                } else if (!on && run >= 0) {
                    out.add((run + x - 1) / 2.0);
                    run = -1;
                }
            }
            return out;
        });
    }

    /** The left edge of each character of {@code line} named by {@code columns}, in the overlay's coordinates. */
    private static List<Double> charX(EditorBuffer buffer, int line, int... columns) throws Exception {
        return EditorFx.callFx(() -> {
            Node overlay = EditorFx.field(buffer, "indentGuides");
            List<Double> out = new ArrayList<>();
            for (int column : columns) {
                int abs = buffer.getArea().getAbsolutePosition(line, column);
                out.add(buffer.getArea()
                        .getCharacterBoundsOnScreen(abs, abs + 1)
                        .map(overlay::screenToLocal)
                        .orElseThrow()
                        .getMinX());
            }
            return out;
        });
    }

    /** Whether each painted guide is within a pixel and a half of the matching expected x, and as many. */
    private static boolean matches(List<Double> painted, List<Double> expected) {
        if (painted.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < painted.size(); i++) {
            if (Math.abs(painted.get(i) - expected.get(i)) > 1.5) {
                return false;
            }
        }
        return true;
    }

    /**
     * Asserts that the guides of {@code line} settle on {@code expected} within three seconds. A paint made for
     * an old layout that nothing asks to redo stays wrong however long one waits — which is the failure.
     */
    private static void assertGuides(EditorBuffer buffer, int line, Expected expected, String what) throws Exception {
        List<List<Double>> last = new ArrayList<>(List.of(List.of(), List.of()));
        Await.holdsWithin(
                EditorFx.check(() -> {
                    last.set(0, painted(buffer, line));
                    last.set(1, expected.get());
                    return matches(last.get(0), last.get(1));
                }),
                Duration.ofSeconds(3));
        List<Double> got = last.get(0);
        List<Double> want = last.get(1);
        assertTrue(matches(got, want), what + ": painted " + got + ", the text's columns " + want);
    }

    @FunctionalInterface
    private interface Expected {
        List<Double> get() throws Exception;
    }

    @Test
    void theGuidesFollowTheTextWhenTheGutterChangesWidth() throws Exception {
        Stage[] stage = new Stage[1];
        EditorBuffer buffer = open(nested(990), stage);
        try {
            int line = 5; // six levels: guides at columns 0, 4 … 20
            Expected columns = () -> charX(buffer, line, 0, 4, 8, 12, 16, 20);
            assertGuides(buffer, line, columns, "after opening");
            double before = columns.get().get(0);

            // 990 -> 1,010 lines, out of view: the line numbers gain a digit, nothing on screen is edited.
            EditorFx.onFx(() -> buffer.getArea().appendText(nested(20)));
            // the gutter is laid out wider in a later pulse
            Await.holdsWithin(EditorFx.check(() -> columns.get().get(0) > before + 2), Duration.ofSeconds(3));
            assertTrue(columns.get().get(0) > before + 2, "the fixture widens the gutter");
            assertGuides(buffer, line, columns, "after the gutter gained a digit");

            EditorFx.onFx(() -> buffer.setLineNumbersVisible(false));
            assertGuides(buffer, line, columns, "with the line numbers off");
            EditorFx.onFx(() -> buffer.setLineNumbersVisible(true));
            assertGuides(buffer, line, columns, "with the line numbers back");

            // A gutter that changes width behind the overlay's back: nothing scrolls, nothing is edited, the
            // overlay is not laid out again — only the lines are.
            EditorFx.onFx(() -> buffer.getArea().setParagraphGraphicFactory(p -> {
                javafx.scene.layout.Region wide = new javafx.scene.layout.Region();
                wide.setMinWidth(160);
                wide.setPrefWidth(160);
                return wide;
            }));
            assertGuides(buffer, line, columns, "after the gutter was replaced");
        } finally {
            close(buffer, stage[0]);
        }
    }

    @Test
    void theColumnWidthIsTheLaidOutTextsNotADigitOfTheFont() throws Exception {
        Stage[] stage = new Stage[1];
        EditorBuffer buffer = open(nested(40), stage);
        try {
            // A proportional font, where a space is narrower than the digit the font metrics measure: the guides
            // must stand where the spaces put the text.
            EditorFx.onFx(() -> buffer.setFont("System", 14));
            assertGuides(buffer, 5, () -> charX(buffer, 5, 0, 4, 8, 12, 16, 20), "in a proportional font");
            List<Double> x = charX(buffer, 5, 0, 20);
            assertTrue(x.get(1) - x.get(0) > 20, "the fixture lays out an indentation");
        } finally {
            close(buffer, stage[0]);
        }
    }

    @Test
    void everyLineStartsAtTheSameXInAProportionalFont() throws Exception {
        Stage[] stage = new Stage[1];
        EditorBuffer buffer = open(nested(40), stage);
        try {
            EditorFx.onFx(() -> buffer.setFont("System", 14));
            // Lines 1, 6 and 9 are a space and a digit in the gutter, 20 two digits: in a proportional font a
            // space is narrower than a digit, and on macOS a 1 is narrower than a 6, so a gutter in the editor
            // font would be ragged however long one waits. Polled: the new font is laid out a pulse or two later.
            List<Double> starts = new ArrayList<>();
            Await.holdsWithin(
                    EditorFx.check(() -> {
                        EditorFx.drain();
                        starts.clear();
                        for (int line : new int[] {0, 5, 8, 19}) {
                            starts.add(charX(buffer, line, 0).get(0));
                        }
                        return starts.stream().distinct().count() == 1;
                    }),
                    Duration.ofSeconds(3));
            assertEquals(1, starts.stream().distinct().count(), "where each line's text starts: " + starts);
        } finally {
            close(buffer, stage[0]);
        }
    }

    @Test
    void noGuideIsDrawnOverTheGutterWhenScrolledSideways() throws Exception {
        Stage[] stage = new Stage[1];
        EditorBuffer buffer = open("long" + "x".repeat(400) + "\n" + nested(40), stage);
        try {
            int line = 6; // six levels
            Expected all = () -> charX(buffer, line, 0, 4, 8, 12, 16, 20);
            assertGuides(buffer, line, all, "before scrolling");
            // where the text starts once the gutter is laid out: the same on three reads a pulse apart
            double[] left = {Double.NaN};
            int[] same = {0};
            Await.holdsWithin(
                    EditorFx.check(() -> {
                        double now = all.get().get(0);
                        same[0] = now == left[0] ? same[0] + 1 : 0;
                        left[0] = now;
                        return same[0] >= 2;
                    }),
                    Duration.ofSeconds(3));
            double textLeft = left[0];

            EditorFx.onFx(() -> buffer.getArea().scrollXToPixel(60));
            // Columns 0 and 4 went under the gutter; the rest moved left with the text.
            Expected visible = () -> {
                List<Double> out = new ArrayList<>();
                for (double x : all.get()) {
                    if (x >= textLeft - 0.5) {
                        out.add(x);
                    }
                }
                return out;
            };
            // The scroll is laid out a pulse later, and a layout still pending from the open can put it back: wait
            // until it holds, columns gone under the gutter, on two reads a pulse apart (scrolling again if undone).
            int[] held = {0};
            Await.holdsWithin(
                    EditorFx.check(() -> {
                        if (EditorFx.callFx(() -> buffer.getArea().getEstimatedScrollX()) < 59) {
                            EditorFx.onFx(() -> buffer.getArea().scrollXToPixel(60));
                            held[0] = 0;
                            return false;
                        }
                        held[0] = visible.get().size() < all.get().size() ? held[0] + 1 : 0;
                        return held[0] >= 2;
                    }),
                    Duration.ofSeconds(3));
            assertGuides(buffer, line, visible, "scrolled sideways");
            List<Double> got = painted(buffer, line);
            assertFalse(got.isEmpty(), "some guides are still on screen");
            assertTrue(got.get(0) >= textLeft - 1, "none left of the text area: " + got + " text at " + textLeft);
            assertTrue(visible.get().size() < all.get().size(), "the fixture scrolls a column under the gutter");
        } finally {
            close(buffer, stage[0]);
        }
    }

    @Test
    void aTabIndentedFileHasAGuideAtEachTabStop() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            text.append("\t".repeat(1 + i % 5)).append("x := ").append(i).append('\n');
        }
        Stage[] stage = new Stage[1];
        EditorBuffer buffer = open(text.toString(), "go", stage);
        try {
            for (int tabSize : new int[] {4, 8}) {
                EditorFx.onFx(() -> buffer.setTabSize(tabSize));
                // Line 4 is five tabs then "x": the guides split the run up to "x" into five equal stops.
                Expected stops = () -> {
                    List<Double> ends = charX(buffer, 4, 0, 5);
                    double step = (ends.get(1) - ends.get(0)) / 5;
                    List<Double> out = new ArrayList<>();
                    for (int k = 0; k < 5; k++) {
                        out.add(ends.get(0) + k * step);
                    }
                    return out;
                };
                assertGuides(buffer, 4, stops, "tab size " + tabSize);
            }
            assertEquals(5, painted(buffer, 4).size());
        } finally {
            close(buffer, stage[0]);
        }
    }
}
