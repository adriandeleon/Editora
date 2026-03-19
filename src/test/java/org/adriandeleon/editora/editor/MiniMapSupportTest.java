package org.adriandeleon.editora.editor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniMapSupportTest {

    @Test
    void samplesTextIntoBucketsUsingTheWidestVisibleLine() {
        List<MiniMapSupport.MiniMapSample> samples = MiniMapSupport.sampleText(
                "alpha\n  betaValue\n\n\tgamma",
                2,
                20
        );

        assertEquals(2, samples.size());
        assertEquals(0.10d, samples.getFirst().indentFraction(), 0.001d);
        assertEquals(0.45d, samples.getFirst().widthFraction(), 0.001d);
        assertEquals(0.20d, samples.getLast().indentFraction(), 0.001d);
        assertEquals(0.25d, samples.getLast().widthFraction(), 0.001d);
    }

    @Test
    void limitsSamplingBucketsToActualLineCount() {
        List<MiniMapSupport.MiniMapSample> samples = MiniMapSupport.sampleText("single line", 12, 20);

        assertEquals(1, samples.size());
        assertEquals(0d, samples.getFirst().indentFraction(), 0.001d);
        assertEquals(0.55d, samples.getFirst().widthFraction(), 0.001d);
    }

    @Test
    void computesShortDocumentRenderHeightWithoutStretchingToViewportHeight() {
        MiniMapSupport.MiniMapLayout layout = MiniMapSupport.layout("one\ntwo\nthree", 240d, 600);

        assertEquals(3, layout.lineCount());
        assertEquals(3, layout.sampleCount());
        assertEquals(6d, layout.renderHeight(), 0.001d);
        assertEquals(2d, layout.rowHeight(), 0.001d);
    }

    @Test
    void viewportIndicatorExpandsToFullHeightWhenNothingCanScroll() {
        MiniMapSupport.ViewportIndicator indicator = MiniMapSupport.viewportIndicator(0d, 0d, 0d, 0d);

        assertEquals(0d, indicator.startFraction());
        assertEquals(1d, indicator.heightFraction());
    }

    @Test
    void computesViewportAndScrollMappingFromScrollBarValues() {
        MiniMapSupport.ViewportIndicator indicator = MiniMapSupport.viewportIndicator(0d, 100d, 25d, 20d);
        double targetValue = MiniMapSupport.scrollValueForFraction(0.75d, 0d, 100d, 20d);

        assertEquals(0.208d, indicator.startFraction(), 0.001d);
        assertEquals(0.166d, indicator.heightFraction(), 0.001d);
        assertEquals(80d, targetValue, 0.01d);
        assertTrue(targetValue >= 0d);
    }
}

