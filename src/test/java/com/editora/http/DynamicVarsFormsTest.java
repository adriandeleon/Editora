package com.editora.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dynamic-variable forms and malformed arguments {@code DynamicVarsTest} does not reach. */
class DynamicVarsFormsTest {

    private static final LocalDateTime CLOCK = LocalDateTime.of(2026, 6, 10, 9, 30, 0);

    private static String value(String name) {
        return DynamicVars.value(name, CLOCK, null);
    }

    @Test
    void onlyADollarNameIsDynamic() {
        assertTrue(DynamicVars.isDynamic("$uuid"));
        assertFalse(DynamicVars.isDynamic("uuid"));
        assertFalse(DynamicVars.isDynamic(null));
        assertEquals("", DynamicVars.value(null, CLOCK, null));
    }

    @Test
    void randomIntDefaultsToZeroToAThousand() {
        for (int i = 0; i < 200; i++) {
            int v = Integer.parseInt(value("$randomInt"));
            assertTrue(v >= 0 && v < 1000, "out of range: " + v);
            int from = Integer.parseInt(value("$randomInt(990)"));
            assertTrue(from >= 990 && from < 1000, "out of range: " + from);
            int upTo = Integer.parseInt(value("$random.integer(,5)"));
            assertTrue(upTo >= 0 && upTo < 5, "out of range: " + upTo);
            // Arguments that are not numbers fall back to the defaults.
            int junk = Integer.parseInt(value("$randomInt(a,b)"));
            assertTrue(junk >= 0 && junk < 1000, "out of range: " + junk);
        }
    }

    @Test
    void anEmptyRangeGivesItsLowerBound() {
        assertEquals("3", value("$randomInt(3,3)"));
        assertEquals("5", value("$random.integer(5, 2)"));
        assertEquals("4.0", value("$random.float(4,4)"));
        assertEquals("9.5", value("$random.float(9.5)"));
    }

    @Test
    void randomFloatHonorsItsBounds() {
        for (int i = 0; i < 200; i++) {
            double unit = Double.parseDouble(value("$random.float"));
            assertTrue(unit >= 0 && unit < 1, "out of range: " + unit);
            double ranged = Double.parseDouble(value("$random.float(2, 3)"));
            assertTrue(ranged >= 2 && ranged < 3, "out of range: " + ranged);
            double upTo = Double.parseDouble(value("$random.float(,0.5)"));
            assertTrue(upTo >= 0 && upTo < 0.5, "out of range: " + upTo);
            double junk = Double.parseDouble(value("$random.float(x,y)"));
            assertTrue(junk >= 0 && junk < 1, "out of range: " + junk);
        }
    }

    @Test
    void randomStringLengthDefaultsAndClamps() {
        assertEquals(16, value("$random.alphabetic").length());
        assertEquals(16, value("$random.alphanumeric()").length());
        assertEquals(16, value("$random.hexadecimal(many)").length());
        assertEquals("", value("$random.alphabetic(-3)"));
        assertEquals("", value("$random.hexadecimal(0)"));
        // An unclosed argument list is not a call, so the name is unknown.
        assertEquals("", value("$random.alphabetic(5"));
    }

    @Test
    void guidAndRandomUuidAreUuids() {
        assertTrue(value("$guid").matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"));
        assertTrue(value(" $random.uuid ").matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"));
    }

    @Test
    void datetimeNamedFormats() {
        assertEquals("2026-06-10T09:30:00Z", value("$datetime"));
        assertEquals("2026-06-10T09:30:00Z", value("$datetime iso8601"));
        assertEquals("2026-06-11T09:30:00Z", value("$datetime ISO8601 1 d"));
        assertEquals("Wed, 10 Jun 2026 09:30:00 GMT", value("$datetime rfc1123"));
        assertEquals("2026", value("$datetime 'yyyy'"));
    }

    @Test
    void localDatetimeUsesTheSystemZone() {
        ZoneId zone = ZoneId.systemDefault();
        assertEquals(CLOCK.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), value("$localDatetime"));
        assertEquals(
                CLOCK.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), value("$localDatetime iso8601"));
        assertEquals(CLOCK.atZone(zone).format(DateTimeFormatter.RFC_1123_DATE_TIME), value("$localDatetime rfc1123"));
        assertEquals("2026-06-10 09:30", value("$localDatetime \"yyyy-MM-dd HH:mm\""));
        assertEquals("2026-06-10 11:30", value("$localDatetime \"yyyy-MM-dd HH:mm\" 2 h"));
    }

    @Test
    void aPatternThatCannotBeUsedGivesNothing() {
        assertEquals("", value("$datetime \"{\""));
        // Mismatched quotes are not stripped, which leaves an unterminated literal.
        assertEquals("", value("$datetime \"yyyy'"));
    }

    @Test
    void everyOffsetUnitMovesTheClock() {
        assertEquals("2027-06-10", value("$datetime \"yyyy-MM-dd\" 1 y"));
        assertEquals("2026-06-24", value("$datetime \"yyyy-MM-dd\" 2 w"));
        assertEquals("09:30:15", value("$datetime \"HH:mm:ss\" 15 s"));
        assertEquals("09:30:00.250", value("$datetime \"HH:mm:ss.SSS\" 250 ms"));
        assertEquals("07:30", value("$datetime \"HH:mm\" -2 h"));
        // An unknown unit, or an amount that is not a number, leaves the time alone.
        assertEquals("2026-06-10", value("$datetime \"yyyy-MM-dd\" 1 fortnight"));
        assertEquals("2026-06-10", value("$datetime \"yyyy-MM-dd\" soon d"));
        // "$timestamp" needs both an amount and a unit.
        assertEquals(value("$timestamp"), value("$timestamp 5"));
    }

    @Test
    void processEnvReadsTheEnvironment() {
        String path = System.getenv("PATH");
        assertEquals(path == null ? "" : path, value("$processEnv.PATH"));
        assertEquals(path == null ? "" : path, value("$processEnv PATH"));
        assertEquals("", value("$processEnv.EDITORA_SURELY_NOT_SET_12345"));
    }

    @Test
    void dotenvWithoutAKeyOrAFileIsEmpty(@TempDir Path dir) throws IOException {
        assertEquals("", DynamicVars.value("$dotenv.KEY", CLOCK, dir));
        Files.writeString(dir.resolve(".env"), "KEY=v\n");
        assertEquals("v", DynamicVars.value("$dotenv KEY", CLOCK, dir));
        assertEquals("v", DynamicVars.value("$dotenv . KEY", CLOCK, dir));
        assertEquals("", DynamicVars.value("$dotenv", CLOCK, dir));
        assertEquals("", DynamicVars.value("$dotenv.", CLOCK, dir));
    }

    @Test
    void aDotenvThatCannotBeReadIsEmpty(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve(".env"));
        assertEquals("", DynamicVars.value("$dotenv.KEY", CLOCK, dir));
    }
}
