package com.editora.install;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionsTest {

    @Test
    void comparesNumberByNumberNotAsText() {
        assertTrue(Versions.compare("1.9.0", "1.10.0") < 0);
        assertTrue(Versions.compare("1.63.0", "1.62.0") > 0);
        assertTrue(Versions.compare("0.11.0", "0.11.0") == 0);
    }

    @Test
    void aMissingNumberCountsAsZeroAndTheTextAroundIsIgnored() {
        assertEquals(0, Versions.compare("v1.117.0", "1.117"));
        assertEquals(0, Versions.compare("2.50", "2.50.0"));
        assertTrue(Versions.compare("v3.14", "3.14.1") < 0);
    }

    @Test
    void dateStampedReleasesCompareByTheirParts() {
        assertTrue(Versions.compare("2026.08.05-11.00.00", "2026.10.15-11.42.38") < 0);
        assertTrue(Versions.compare("1.61.0.202607301809", "1.63.0.202610191322") < 0);
        assertTrue(Versions.compare("1.63.0.202610191322", "1.63.0.202610131109") > 0, "a later build of the same");
    }

    @Test
    void numbersTooLongForALongStillCompareByValue() {
        assertTrue(Versions.compare("1.99999999999999999999", "1.100000000000000000000") < 0);
        assertEquals(0, Versions.compare("1.007", "1.7"));
    }

    @Test
    void firstNumberFindsTheVersionInALine() {
        assertEquals("2.96.0", Versions.firstNumber("gh version 2.96.0 (2026-07-02)"));
        assertEquals("18.20.4", Versions.firstNumber("v18.20.4"));
        assertEquals("25", Versions.firstNumber("25"));
        assertNull(Versions.firstNumber("development build"));
        assertNull(Versions.firstNumber(null));
    }
}
