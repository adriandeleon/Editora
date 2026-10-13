package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.editora.i18n.Messages;
import com.editora.markdown.MarkdownLint;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the Markdown-lint squiggles are drawn: on empty lines (C13), once per visual row of a wrapped range
 * (C14), and in the colour of the editor's background on the overview stripe (C20); and what the tooltip says
 * (C15).
 */
@Tag("fx")
class MarkdownLintOverlayFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static EditorBuffer markdown(String text, List<MarkdownLint.Diagnostic> diagnostics) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setLanguageOverride("markdown");
        buffer.getArea().replaceText(text);
        buffer.getArea().moveTo(0);
        buffer.setMarkdownLintValidator((t, sink) -> sink.accept(diagnostics));
        return buffer;
    }

    /** The y-bands (runs of pixel rows) the overlay paints on, each as {@code {top, bottom, minX, maxX}}. */
    private static List<int[]> bands(EditorBuffer buffer) throws Exception {
        // The overlay repaints on a later pulse than the layout that sizes it: read until two looks agree.
        List<int[]> last = List.of();
        for (int attempt = 0; attempt < 60; attempt++) {
            List<int[]> now = paintedBands(buffer);
            if (!now.isEmpty() && now.size() == last.size()) {
                return now;
            }
            last = now;
            Thread.sleep(50);
        }
        return last;
    }

    private static List<int[]> paintedBands(EditorBuffer buffer) throws Exception {
        EditorFx.drain();
        return EditorFx.callFx(() -> {
            Node overlay = EditorFx.field(buffer, "mdLintOverlay");
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            WritableImage img = overlay.snapshot(params, null);
            PixelReader px = img.getPixelReader();
            List<int[]> out = new ArrayList<>();
            int[] open = null;
            for (int y = 0; y < img.getHeight(); y++) {
                int min = -1;
                int max = -1;
                for (int x = 0; x < img.getWidth(); x++) {
                    if ((px.getArgb(x, y) >>> 24) > 40) {
                        min = min < 0 ? x : min;
                        max = x;
                    }
                }
                if (min < 0) {
                    open = null;
                } else if (open == null) {
                    open = new int[] {y, y, min, max};
                    out.add(open);
                } else {
                    open[1] = y;
                    open[2] = Math.min(open[2], min);
                    open[3] = Math.max(open[3], max);
                }
            }
            return out;
        });
    }

    @Test
    void anIssueOnAnEmptyLineIsUnderlined() throws Exception {
        EditorBuffer[] buffer = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            buffer[0] = markdown(
                    "# Title\n\n\n\ntext\n",
                    List.of(new MarkdownLint.Diagnostic(3, 1, 1, "warning", "MD012", "blank lines")));
            stage[0] = EditorFx.show(buffer[0], 600, 300);
            buffer[0].setMarkdownLintEnabled(true);
        });
        // The gutter is sized on a later pulse than the first paint, and the text moves right when it is: a
        // squiggle read off that first paint sits where the text used to start. Look again until the overlay
        // has repainted for the settled layout (it never would if it failed to repaint, and the test fails).
        List<int[]> bands = List.of();
        double[] lineBox = new double[3];
        for (int attempt = 0; attempt < 60; attempt++) {
            bands = bands(buffer[0]);
            lineBox = EditorFx.callFx(() -> {
                CodeArea area = buffer[0].getArea();
                Node overlay = EditorFx.field(buffer[0], "mdLintOverlay");
                var box =
                        overlay.screenToLocal(area.getParagraphBoundsOnScreen(2).orElseThrow());
                var text = overlay.screenToLocal(
                        area.getCharacterBoundsOnScreen(0, 1).orElseThrow());
                return new double[] {box.getMinY(), box.getMaxY(), text.getMinX()};
            });
            if (bands.size() == 1 && Math.abs(bands.get(0)[2] - lineBox[2]) <= 2) {
                break;
            }
            Thread.sleep(50);
        }
        assertEquals(1, bands.size(), "one short squiggle on the blank line");
        int[] band = bands.get(0);
        assertTrue(band[3] - band[2] >= 8 && band[3] - band[2] <= 24, "about two characters wide");
        assertTrue(band[0] >= lineBox[0] && band[1] <= lineBox[1] + 1, "inside line 3");
        assertTrue(
                Math.abs(band[2] - lineBox[2]) <= 2,
                "starting where the text starts, not in the gutter: squiggle at x=" + band[2] + ", text at x="
                        + lineBox[2]);
        EditorFx.onFx(() -> {
            stage[0].close();
            buffer[0].dispose();
        });
    }

    @Test
    void aRangeThatWrapsIsUnderlinedOnEachOfItsRows() throws Exception {
        String line = "word ".repeat(60).strip();
        EditorBuffer[] buffer = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            buffer[0] = markdown(
                    line + "\n\nshort line here\n",
                    List.of(
                            new MarkdownLint.Diagnostic(1, 1, line.length(), "warning", "MD034", "whole line"),
                            new MarkdownLint.Diagnostic(3, 1, 5, "warning", "MD034", "one word")));
            buffer[0].setWordWrap(true);
            stage[0] = EditorFx.show(buffer[0], 420, 400);
            buffer[0].setMarkdownLintEnabled(true);
        });
        List<int[]> bands = bands(buffer[0]);
        int rows = EditorFx.callFx(() -> buffer[0].getArea().getParagraphLinesCount(0));
        assertTrue(rows >= 3, "the fixture wraps: " + rows);
        assertEquals(rows + 1, bands.size(), "one underline per visual row, plus the short one below");
        int[] shortOne = bands.get(bands.size() - 1);
        assertTrue(shortOne[3] - shortOne[2] < 80, "a five-character range stays five characters wide");
        // No underline is wider than the text: the union box of a wrapped range used to span the editor.
        double textWidth = EditorFx.callFx(() -> buffer[0].getArea().getWidth());
        for (int[] band : bands) {
            assertTrue(band[3] - band[2] < textWidth - 20, "band " + band[0] + " is " + (band[3] - band[2]) + " px");
        }
        EditorFx.onFx(() -> {
            stage[0].close();
            buffer[0].dispose();
        });
    }

    @Test
    void theOverviewStripeTakesItsColourFromTheEditorBackground() throws Exception {
        EditorFx.onFx(() -> {
            CodeArea area = new CodeArea("a\nb\n");
            MarkdownLintStripe stripe = new MarkdownLintStripe(area);
            Color onLight = EditorFx.field(stripe, "warning");
            assertEquals(OverlayPalette.of(Color.WHITE).warning(), onLight);
            Color dark = Color.web("#171a24");
            area.setBackground(new Background(new BackgroundFill(dark, CornerRadii.EMPTY, Insets.EMPTY)));
            Color onDark = EditorFx.field(stripe, "warning");
            assertEquals(OverlayPalette.of(dark).warning(), onDark);
            assertNotEquals(onLight, onDark);
        });
    }

    @Test
    void messagesAreShownInTheInterfaceLanguage() {
        MarkdownLint.Diagnostic real = MarkdownLint.lint("a   \n").stream()
                .filter(d -> d.code().equals("MD009"))
                .findFirst()
                .orElseThrow();
        assertEquals("MD009: " + Messages.tr("mdlint.msg.MD009"), MarkdownLintText.labelled(real));
        assertEquals(
                "MD009: " + Messages.tr("mdlint.msg.MD009") + "\nMD999: as given",
                MarkdownLintHover.text(
                        List.of(real, new MarkdownLint.Diagnostic(1, 1, 1, "warning", "MD999", "as given"))));
        for (String key : MarkdownLint.messageKeys()) {
            assertNotEquals(key, Messages.tr(key), "missing from the catalog: " + key);
        }
        for (String code : MarkdownLint.CODES) {
            assertNotEquals("mdlint.rule." + code, Messages.tr("mdlint.rule." + code));
        }
    }
}
