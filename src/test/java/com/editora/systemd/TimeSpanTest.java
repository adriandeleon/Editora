package com.editora.systemd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TimeSpan} parses systemd time spans ({@code RestartSec=}, {@code OnActiveSec=}, …). A hostile or
 * fat-fingered unit file can hand it a number that overflows a {@code long}; that must degrade to "can't
 * parse" (the method's {@code -1} sentinel), not throw out of the systemd preview.
 */
class TimeSpanTest {

    @Test
    void ordinarySpansParse() {
        assertEquals(90, TimeSpan.seconds("1min 30s"));
        assertEquals(3600, TimeSpan.seconds("1h"));
        assertEquals(45, TimeSpan.seconds("45"));
    }

    @Test
    void aHugeNumberDoesNotThrow() {
        // Long.parseLong would throw on 20 digits; describe() had no guard, so rendering the preview threw.
        assertEquals(-1, TimeSpan.seconds("99999999999999999999"));
        assertEquals(-1, TimeSpan.seconds("99999999999999999999s"));
        // describe() falls back to the raw text rather than throwing.
        assertEquals(
                "99999999999999999999",
                TimeSpan.describe("99999999999999999999").strip());
    }

    @Test
    void aParseableButOverflowingProductDoesNotWrapToGarbage() {
        // Long.MAX weeks * 604800 s/week overflows the multiply; without Math.multiplyExact this silently
        // produced a negative/wrong "describe" instead of admitting it can't represent it.
        assertEquals(-1, TimeSpan.seconds("9223372036854775807w"));
    }

    @Test
    void junkIsRejected() {
        assertEquals(-1, TimeSpan.seconds("1min garbage"));
        assertEquals(-1, TimeSpan.seconds("abc"));
        assertEquals(-1, TimeSpan.seconds(""));
    }
}
