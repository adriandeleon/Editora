package com.editora.http;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clock-dependent dynamic variables must describe the real instant, whatever the user's time zone (they
 * used to format local wall time as if it were UTC), plus the REST Client forms and the guards around them.
 */
class DynamicVarsClockTest {

    /** 2026-06-10T09:30:00Z, seen from Monterrey (UTC-6). */
    private static final ZonedDateTime MONTERREY =
            ZonedDateTime.of(2026, 6, 10, 3, 30, 0, 0, ZoneId.of("America/Monterrey"));

    private static final long EPOCH = 1_781_083_800L;

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    private static String value(String name) {
        return DynamicVars.value(name, MONTERREY, null);
    }

    @Test
    void utcVariablesAreTheInstantNotTheLocalWallTime() {
        assertEquals(String.valueOf(EPOCH), value("$timestamp"));
        assertEquals(String.valueOf(EPOCH + 3600), value("$timestamp 1 h"));
        assertEquals("2026-06-10T09:30:00Z", value("$isoTimestamp"));
        assertEquals("2026-06-10T09:30:00Z", value("$datetime iso8601"));
        assertEquals("Wed, 10 Jun 2026 09:30:00 GMT", value("$datetime rfc1123"));
        assertEquals("09:30", value("$datetime \"HH:mm\""));
    }

    @Test
    void isoTimestampsHaveMillisecondPrecision() {
        ZonedDateTime t = MONTERREY.plusNanos(123_456_789);
        assertEquals("2026-06-10T09:30:00.123Z", DynamicVars.value("$isoTimestamp", t, null));
    }

    @Test
    void localDatetimeIsShownInTheSystemZone() {
        ZoneId zone = ZoneId.systemDefault();
        assertEquals(
                MONTERREY.withZoneSameInstant(zone).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
                value("$localDatetime \"HH:mm\""));
    }

    @Test
    void dayJsFormatsAreTranslatedNotMisreadAsJavaPatterns() {
        ZonedDateTime newYearsEve = ZonedDateTime.of(2026, 12, 30, 12, 0, 0, 0, java.time.ZoneOffset.UTC);
        // In Java "DD" is the day of the year and "YYYY" the week-based year: 364-12-2027.
        assertEquals("30-12-2026", DynamicVars.value("$datetime \"DD-MM-YYYY\"", newYearsEve, null));
        assertEquals("2026-12-31", DynamicVars.value("$datetime \"YYYY-MM-DD\" 1 d", newYearsEve, null));
        assertEquals("2026-12-30T12:00", DynamicVars.value("$datetime \"YYYY-MM-DD[T]HH:mm\"", newYearsEve, null));
        assertEquals("Wed 12:00 PM", DynamicVars.value("$datetime \"ddd hh:mm A\"", newYearsEve, null));
        assertEquals(String.valueOf(newYearsEve.toEpochSecond()), DynamicVars.value("$datetime X", newYearsEve, null));
        // Java patterns still work unchanged.
        assertEquals("2026-12-30", DynamicVars.value("$datetime \"yyyy-MM-dd\"", newYearsEve, null));
    }

    @Test
    void restClientRandomIntFormAndEnvAlias() {
        assertEquals("5", value("$randomInt 5 6"));
        String path = System.getenv("PATH");
        if (path != null) {
            assertEquals(path, value("$env.PATH"));
            assertEquals(path, value("$env PATH"));
        }
    }

    @Test
    void randomStringsAreCapped() {
        assertEquals(
                DynamicVars.MAX_RANDOM_LENGTH,
                value("$random.alphabetic(2000000000)").length());
    }

    @Test
    void whatCannotBeResolvedSaysWhy() {
        String[] why = new String[1];
        assertNull(DynamicVars.resolve("$random.name.firstName", MONTERREY, null, why));
        assertNotNull(why[0]);
        assertTrue(why[0].contains("dynamic variable"), why[0]);
        assertNull(DynamicVars.resolve("$processEnv EDITORA_SURELY_NOT_SET_12345", MONTERREY, null, why));
        assertTrue(why[0].contains("environment variable"), why[0]);
        assertNull(DynamicVars.resolve("$dotenv TOKEN", MONTERREY, null, why));
        assertTrue(why[0].contains(".env"), why[0]);
        assertNull(DynamicVars.resolve("$datetime \"{\"", MONTERREY, null, why));
        assertTrue(why[0].contains("format"), why[0]);
    }
}
