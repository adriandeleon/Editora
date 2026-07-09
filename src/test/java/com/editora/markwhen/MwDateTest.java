package com.editora.markwhen;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure tests for the Markwhen date parser + its half-open intervals. */
class MwDateTest {

    @Test
    void yearOnlySpansTheWholeYear() {
        MwDate d = MwDate.parse("2023");
        assertEquals(MwDate.Granularity.YEAR, d.granularity());
        assertEquals(LocalDate.of(2023, 1, 1), d.start());
        assertEquals(LocalDate.of(2024, 1, 1), d.endExclusive());
    }

    @Test
    void yearMonthSpansTheWholeMonth() {
        MwDate d = MwDate.parse("2023-02");
        assertEquals(MwDate.Granularity.MONTH, d.granularity());
        assertEquals(LocalDate.of(2023, 2, 1), d.start());
        assertEquals(LocalDate.of(2023, 3, 1), d.endExclusive());
    }

    @Test
    void fullDateSpansOneDay() {
        MwDate d = MwDate.parse("2023-04-09");
        assertEquals(MwDate.Granularity.DAY, d.granularity());
        assertEquals(LocalDate.of(2023, 4, 9), d.start());
        assertEquals(LocalDate.of(2023, 4, 10), d.endExclusive());
    }

    @Test
    void slashSeparatorsAccepted() {
        assertEquals(LocalDate.of(2023, 4, 9), MwDate.parse("2023/04/9").start());
        assertEquals(MwDate.Granularity.MONTH, MwDate.parse("2023/4").granularity());
    }

    @Test
    void monthNameForms() {
        assertEquals(LocalDate.of(2025, 12, 1), MwDate.parse("Dec 1 2025").start());
        assertEquals(MwDate.Granularity.DAY, MwDate.parse("Dec 1 2025").granularity());
        assertEquals(LocalDate.of(2025, 12, 1), MwDate.parse("December 1, 2025").start());
        assertEquals(LocalDate.of(2025, 12, 1), MwDate.parse("1 Dec 2025").start());
        assertEquals(LocalDate.of(2025, 12, 3), MwDate.parse("3rd Dec 2025").start());
        MwDate my = MwDate.parse("Dec 2025");
        assertEquals(MwDate.Granularity.MONTH, my.granularity());
        assertEquals(LocalDate.of(2025, 12, 1), my.start());
    }

    @Test
    void invalidDatesReturnNull() {
        assertNull(MwDate.parse("2023-13-01")); // month 13
        assertNull(MwDate.parse("2023-02-30")); // Feb 30
        assertNull(MwDate.parse("2023-02-29")); // not a leap year
        assertNull(MwDate.parse("Foo 1 2025")); // not a month name
        assertNull(MwDate.parse("someday"));
        assertNull(MwDate.parse(""));
        assertNull(MwDate.parse(null));
        assertNull(MwDate.parse("now")); // relative dates deferred
    }

    @Test
    void leapDayValid() {
        assertEquals(LocalDate.of(2024, 2, 29), MwDate.parse("2024-02-29").start());
    }
}
