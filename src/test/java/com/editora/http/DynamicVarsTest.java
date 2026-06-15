package com.editora.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the dynamic-variable family. */
class DynamicVarsTest {

    private static final LocalDateTime CLOCK = LocalDateTime.of(2026, 6, 10, 9, 30, 0);

    @Test
    void timestampAndIsoUseTheClock() {
        assertEquals(String.valueOf(CLOCK.toEpochSecond(ZoneOffset.UTC)), DynamicVars.value("$timestamp", CLOCK, null));
        assertEquals("2026-06-10T09:30:00Z", DynamicVars.value("$isoTimestamp", CLOCK, null));
    }

    @Test
    void uuidIsAUuid() {
        String s = DynamicVars.value("$uuid", CLOCK, null);
        assertEquals(36, s.length());
        assertTrue(s.matches("[0-9a-f-]{36}"));
    }

    @Test
    void datetimeFormatsWithAPattern() {
        assertEquals("2026-06-10", DynamicVars.value("$datetime \"yyyy-MM-dd\"", CLOCK, null));
    }

    @Test
    void randomIntegerHonorsBounds() {
        for (int i = 0; i < 200; i++) {
            int v = Integer.parseInt(DynamicVars.value("$random.integer(5,10)", CLOCK, null));
            assertTrue(v >= 5 && v < 10, "out of range: " + v);
        }
    }

    @Test
    void randomStringsHaveTheRightLengthAndCharset() {
        assertEquals(
                12, DynamicVars.value("$random.alphabetic(12)", CLOCK, null).length());
        assertTrue(DynamicVars.value("$random.alphabetic(12)", CLOCK, null).matches("[A-Za-z]{12}"));
        assertTrue(DynamicVars.value("$random.alphanumeric(8)", CLOCK, null).matches("[A-Za-z0-9]{8}"));
        assertTrue(DynamicVars.value("$random.hexadecimal(16)", CLOCK, null).matches("[0-9a-f]{16}"));
        assertTrue(DynamicVars.value("$random.email", CLOCK, null).matches("[a-z]+@example\\.com"));
    }

    @Test
    void dotenvReadsTheSiblingFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(".env"), "SECRET=s3cr3t\n");
        assertEquals("s3cr3t", DynamicVars.value("$dotenv.SECRET", CLOCK, dir));
        assertEquals("", DynamicVars.value("$dotenv.MISSING", CLOCK, dir));
        assertEquals("", DynamicVars.value("$dotenv.SECRET", CLOCK, null));
    }

    @Test
    void unknownIsEmpty() {
        assertEquals("", DynamicVars.value("$nope", CLOCK, null));
    }
}
