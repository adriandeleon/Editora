package com.editora.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A drag past the edge scrolls by time and distance, steadily. */
class AutoScrollTest {

    private static final double LINE = 20;
    private static final long FRAME = 16_666_667L;

    @Test
    void justPastTheEdgeStillScrolls() {
        assertTrue(AutoScroll.linesPerSecond(0, LINE) >= AutoScroll.SLOWEST);
    }

    @Test
    void furtherOutIsFasterUpToTheCeiling() {
        assertTrue(AutoScroll.linesPerSecond(5 * LINE, LINE) > AutoScroll.linesPerSecond(LINE, LINE));
        assertEquals(AutoScroll.FASTEST, AutoScroll.linesPerSecond(1e6, LINE));
    }

    @Test
    void theFirstFrameOnlyStartsTheClock() {
        AutoScroll scroll = new AutoScroll();
        assertEquals(0, scroll.step(1_000_000_000L, 1e6, LINE));
    }

    @Test
    void aSlowScrollCarriesItsFractionRatherThanStalling() {
        AutoScroll scroll = new AutoScroll();
        long now = 0;
        scroll.step(now, 0, LINE);
        int total = 0;
        // One second of frames at the slowest rate: six lines, though no single frame earns one.
        for (int i = 0; i < 60; i++) total += scroll.step(now += FRAME, 0, LINE);
        assertEquals(6, total, 1);
    }

    @Test
    void aStalledFrameIsPaidAsOneFrameNotAsTheWholeStall() {
        AutoScroll scroll = new AutoScroll();
        scroll.step(0, 1e6, LINE);
        int lines = scroll.step(5_000_000_000L, 1e6, LINE);
        assertTrue(lines <= AutoScroll.FASTEST * 0.1 + 1, "a five-second stall scrolled " + lines);
    }

    @Test
    void resetForgetsTheClockAndTheCarry() {
        AutoScroll scroll = new AutoScroll();
        scroll.step(0, 0, LINE);
        scroll.step(FRAME, 0, LINE);
        scroll.reset();
        assertEquals(0, scroll.step(10 * FRAME, 1e6, LINE));
    }
}
