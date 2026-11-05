package com.editora.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the Overview card opens: before the project's own column, the way the map flows ({@code overviewSpot}). */
class ProjectMapOverviewSpotTest {

    private static final double M = ProjectMapPreview.EDGE_MARGIN;

    /** {@code {dx, dy, x, y, w, h}} for a 440 × 520 card (min 320 × 220) in a 1200 × 760 panel. */
    private static double[] spot(double[] column, boolean horizontal, boolean reverse, List<double[]> chrome) {
        return MapCards.overviewSpot(column, 440, 520, 320, 220, 1200, 760, horizontal, reverse, chrome);
    }

    @Test
    void leftToRightWithRoomTheCardSitsLeftOfTheColumnAndNothingPans() {
        double[] s = spot(new double[] {600, 100, 225, 300}, true, false, List.of());
        assertEquals(0, s[0], 0.01);
        assertEquals(0, s[1], 0.01);
        assertEquals(440, s[4], 0.01);
        assertEquals(600 - 18 - 440, s[2], 0.01, "a column gap before the column");
        assertEquals(100, s[3], 0.01, "level with the column's top");
    }

    @Test
    void leftToRightWithoutRoomTheMapPansJustEnough() {
        double[] s = spot(new double[] {300, 100, 225, 300}, true, false, List.of());
        double needed = 440 - (300 - 18 - M);
        assertEquals(needed, s[0], 0.01, "the map moves right by what is missing");
        assertEquals(M, s[2], 0.01, "the card at the edge");
        assertEquals(440, s[4], 0.01, "at its full width");
    }

    @Test
    void aNarrowPanelPansOnlyAsFarAsTheColumnStaysInViewThenNarrowsTheCard() {
        double[] s = MapCards.overviewSpot(
                new double[] {200, 100, 225, 300}, 440, 520, 320, 220, 700, 760, true, false, List.of());
        double ahead = 700 - M - (200 + 225);
        assertEquals(ahead, s[0], 0.01, "the column's far side reaches the edge, no further");
        assertEquals(200 + ahead - 18 - M, s[4], 0.01, "the card narrows to the room before the column");
        assertEquals(M, s[2], 0.01);

        double[] tight = MapCards.overviewSpot(
                new double[] {200, 100, 225, 300}, 440, 520, 320, 220, 560, 760, true, false, List.of());
        assertEquals(320, tight[4], 0.01, "never below its minimum");
        assertEquals(M, tight[2], 0.01, "at the edge, over the column's start");
    }

    @Test
    void rightToLeftTheCardSitsRightOfTheColumnAndTheMapPansLeft() {
        double[] s = spot(new double[] {700, 100, 225, 300}, true, true, List.of());
        double room = 1200 - M - (700 + 225) - 18;
        assertEquals(-(440 - room), s[0], 0.01, "the map moves left by what is missing");
        assertEquals(1200 - M - 440, s[2], 0.01);
        assertEquals(100, s[3], 0.01);
    }

    @Test
    void topToBottomTheCardSitsAboveTheColumnAndBottomToTopBelowIt() {
        double[] down = spot(new double[] {160, 400, 900, 120}, false, false, List.of());
        assertEquals(0, down[0], 0.01);
        assertTrue(down[1] > 0, "the map moves down to make room above the column");
        assertEquals(160, down[2], 0.01, "level with the column's start");
        assertEquals(400 + down[1] - 18, down[3] + down[5], 0.01, "a gap above the column");

        double[] up = spot(new double[] {160, 100, 900, 120}, false, true, List.of());
        assertTrue(up[1] < 0, "the map moves up to make room below the column");
        assertEquals(100 + up[1] + 120 + 18, up[3], 0.01, "a gap below the column");
        assertTrue(up[3] + up[5] <= 760 - M + 0.01);
    }

    @Test
    void theZoomBarIsClearedAcrossTheFlow() {
        double[] bar = {0, 760 - 40, 170, 40, MapCards.CHROME_WEIGHT};
        double[] across = spot(new double[] {600, 300, 225, 300}, true, false, List.of(bar));
        assertTrue(across[3] + across[5] <= bar[1], "the card ends above the zoom bar");
        assertTrue(across[5] >= 220, "no shorter than its minimum");

        double[] down = spot(new double[] {100, 100, 900, 120}, false, true, List.of(bar));
        assertTrue(down[2] >= bar[0] + bar[2], "below the column, the card steps past the zoom bar");
    }
}
