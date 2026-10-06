package com.editora.perf;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure half of the startup instrumentation. The marks themselves need a real launch (see
 * {@code scripts/measure-startup.sh}); what's testable here is that the report reads correctly — cumulative
 * times since process start, per-phase deltas, and the headline number.
 */
class StartupTest {

    @Test
    void formatsCumulativeTimesAndPerPhaseDeltas() {
        String out = Startup.format(List.of(
                new Startup.Mark(Startup.MAIN, 120),
                new Startup.Mark(Startup.FX_START, 300),
                new Startup.Mark(Startup.FIRST_PAINT, 760)));

        assertTrue(out.contains("main              120  (+120)"), out);
        assertTrue(out.contains("fx-start          300  (+180)"), out); // delta from the previous mark
        assertTrue(out.contains("first-paint       760  (+460)"), out);
        assertTrue(out.contains("TIME-TO-FIRST-PAINT 760 ms"), out);
    }

    @Test
    void omitsTheHeadlineWhenTheRunNeverPainted() {
        String out = Startup.format(List.of(new Startup.Mark(Startup.MAIN, 120)));

        assertFalse(out.contains("TIME-TO-FIRST-PAINT"), out);
    }

    @Test
    void reportsSessionRestoredWhetherItLandsBeforeOrAfterTheReport() {
        // No session to restore: the mark precedes first paint and is part of the report.
        String early = Startup.format(List.of(
                new Startup.Mark(Startup.WINDOW_SHOWN, 500),
                new Startup.Mark(Startup.SESSION_RESTORED, 510),
                new Startup.Mark(Startup.FIRST_PAINT, 560)));
        assertTrue(early.contains("session-restored    510  (+10)"), early);
        assertTrue(early.contains("TIME-TO-SESSION-RESTORED 510 ms"), early);
        assertTrue(early.contains("TIME-TO-FIRST-PAINT 560 ms"), early);

        // A real session finishes restoring after the report was printed at first paint: a late line.
        String late = Startup.formatLate(
                new Startup.Mark(Startup.FIRST_PAINT, 760), new Startup.Mark(Startup.SESSION_RESTORED, 1900));
        assertTrue(late.contains("session-restored   1900  (+1140)"), late);
        assertTrue(late.contains("TIME-TO-SESSION-RESTORED 1900 ms"), late);
        assertFalse(Startup.formatLate(null, new Startup.Mark("other", 5)).contains("TIME-TO"));
    }

    @Test
    void handlesAnEmptyRun() {
        // No EDITORA_PERF_T0 in the test JVM, so the header flags the origin as approximate.
        assertTrue(Startup.format(List.of()).startsWith("[perf] startup (ms since process start"));
        assertFalse(Startup.exactOrigin());
    }

    @Test
    void isOffByDefaultSoTheInstrumentationCostsNothing() {
        // No -Deditora.perf / EDITORA_PERF in the test JVM, so every mark() is a static boolean test.
        assertFalse(Startup.enabled());
        Startup.mark(Startup.FIRST_PAINT); // must not record, and must not exit the JVM
        assertTrue(Startup.marks().isEmpty());
    }
}
