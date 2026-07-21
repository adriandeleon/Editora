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
