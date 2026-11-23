package com.editora.http;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every Day.js format token {@code $timestamp} / {@code $isoTimestamp} formats accept, on one fixed instant. */
class DynamicVarsDayjsTokensTest {

    /** Friday 2 January 2026, 15:04:05.678 at +05:30 (09:34:05.678 UTC). */
    private static final ZonedDateTime T =
            ZonedDateTime.of(2026, 1, 2, 15, 4, 5, 678_000_000, ZoneOffset.ofHoursMinutes(5, 30));

    @Test
    void everyToken() {
        String[][] expected = {
            {"YYYY", "2026"},
            {"YY", "26"},
            {"MMMM", "January"},
            {"MMM", "Jan"},
            {"MM", "01"},
            {"M", "1"},
            {"DDDD", "002"},
            {"DDD", "2"},
            {"Do", "2nd"},
            {"DD", "02"},
            {"D", "2"},
            {"dddd", "Friday"},
            {"ddd", "Fri"},
            {"dd", "Fr"},
            {"d", "5"},
            {"HH", "15"},
            {"H", "15"},
            {"hh", "03"},
            {"h", "3"},
            {"mm", "04"},
            {"m", "4"},
            {"ss", "05"},
            {"s", "5"},
            {"SSS", "678"},
            {"SS", "67"},
            {"S", "6"},
            {"A", "PM"},
            {"a", "pm"},
            {"ZZ", "+0530"},
            {"Z", "+05:30"},
            {"X", "1767346445"},
            {"x", "1767346445678"},
            {"Q", "1"},
            {"WW", "01"},
            {"W", "1"},
            {"GGGG", "2026"},
            {"GG", "26"},
            {"ww", "01"},
            {"w", "1"},
            {"gggg", "2026"},
            {"gg", "26"}
        };
        for (String[] e : expected) {
            assertEquals(e[1], DynamicVars.dayjs(e[0], T), e[0]);
        }
    }

    @Test
    void ordinalsAndTwelveHourClocks() {
        int[] days = {1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31};
        String[] want = {"1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st"};
        for (int i = 0; i < days.length; i++) {
            assertEquals(want[i], DynamicVars.dayjs("Do", T.withDayOfMonth(days[i])));
        }
        assertEquals("12 am", DynamicVars.dayjs("hh a", T.withHour(0)));
        assertEquals("at 12 PM", DynamicVars.dayjs("[at] h A", T.withHour(12)));
    }
}
