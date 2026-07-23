package com.editora.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure prefix-argument state machine (no toolkit). */
class PrefixArgTest {

    @Test
    void startsInactive() {
        PrefixArg a = new PrefixArg();
        assertFalse(a.isActive());
        assertEquals("", a.describe());
    }

    @Test
    void bareUniversalIsFour() {
        PrefixArg a = new PrefixArg();
        a.universal();
        assertTrue(a.isActive());
        assertEquals(4, a.value());
        assertEquals("C-u 4", a.describe());
    }

    @Test
    void repeatedUniversalsMultiplyByFour() {
        PrefixArg a = new PrefixArg();
        a.universal();
        a.universal();
        assertEquals(16, a.value());
        a.universal();
        assertEquals(64, a.value());
    }

    @Test
    void digitsAfterUniversalFormTheNumber() {
        PrefixArg a = new PrefixArg();
        a.universal();
        a.digit(3);
        assertEquals(3, a.value(), "the first digit replaces the C-u multiplier");
        a.digit(7);
        assertEquals(37, a.value());
        assertEquals("C-u 37", a.describe());
    }

    @Test
    void leadingMinusNegates() {
        PrefixArg a = new PrefixArg();
        a.universal();
        a.negate();
        assertEquals(-1, a.value(), "C-u - with no digits is minus one");
        assertEquals("C-u -", a.describe());
    }

    @Test
    void minusThenDigitsIsNegativeNumber() {
        PrefixArg a = new PrefixArg();
        a.universal();
        a.negate();
        a.digit(5);
        assertEquals(-5, a.value());
        assertEquals("C-u -5", a.describe());
    }

    @Test
    void hasDigitsTracksWhetherANumberWasTyped() {
        PrefixArg a = new PrefixArg();
        a.universal();
        assertFalse(a.hasDigits(), "a bare C-u has no explicit digits — so a following minus still negates");
        a.digit(1);
        assertTrue(a.hasDigits(), "after a digit, a following minus is the command, not a sign");
    }

    @Test
    void universalAfterDigitsMultipliesByFour() {
        PrefixArg a = new PrefixArg();
        a.universal();
        a.digit(3);
        a.universal();
        assertEquals(12, a.value());
    }

    @Test
    void zeroIsARepresentableArgument() {
        PrefixArg a = new PrefixArg();
        a.universal();
        a.digit(0);
        assertEquals(0, a.value());
    }

    @Test
    void multiDigitNumbersAccumulate() {
        PrefixArg a = new PrefixArg();
        a.universal();
        a.digit(1);
        a.digit(0);
        a.digit(0);
        assertEquals(100, a.value());
    }

    @Test
    void resetReturnsToInactive() {
        PrefixArg a = new PrefixArg();
        a.universal();
        a.digit(9);
        a.reset();
        assertFalse(a.isActive());
        assertFalse(a.hasDigits());
        assertEquals("", a.describe());
    }
}
