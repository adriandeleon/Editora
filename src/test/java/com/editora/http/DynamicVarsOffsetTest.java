package com.editora.http;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the $datetime/$timestamp date-math offset arguments. */
class DynamicVarsOffsetTest {

    private static final LocalDateTime CLOCK = LocalDateTime.of(2026, 6, 10, 9, 30, 0);

    @Test
    void datetimeAppliesSignedOffsets() {
        assertEquals("2026-06-11", DynamicVars.value("$datetime \"yyyy-MM-dd\" 1 d", CLOCK, null));
        assertEquals("2026-05-10", DynamicVars.value("$datetime \"yyyy-MM-dd\" -1 M", CLOCK, null));
        assertEquals("10:00", DynamicVars.value("$datetime \"HH:mm\" 30 m", CLOCK, null));
    }

    @Test
    void datetimeWithoutOffsetIsUnchanged() {
        assertEquals("2026-06-10", DynamicVars.value("$datetime \"yyyy-MM-dd\"", CLOCK, null));
    }

    @Test
    void timestampAppliesOffset() {
        long base = CLOCK.toEpochSecond(ZoneOffset.UTC);
        assertEquals(String.valueOf(base), DynamicVars.value("$timestamp", CLOCK, null));
        assertEquals(String.valueOf(base + 3600), DynamicVars.value("$timestamp 1 h", CLOCK, null));
        assertEquals(String.valueOf(base - 86400), DynamicVars.value("$timestamp -1 d", CLOCK, null));
    }
}
