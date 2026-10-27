package com.editora.terminal;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The flag stacks programs push, pop, set and query. */
class KeyboardFlagsTest {

    private final KeyboardFlags flags = new KeyboardFlags();

    private String query(boolean alternate) {
        return new String(flags.apply('?', "", alternate), StandardCharsets.US_ASCII);
    }

    @Test
    void startsInLegacyMode() {
        assertEquals(0, flags.current(false));
        assertEquals("\u001b[?0u", query(false));
    }

    @Test
    void pushAndPopNest() {
        assertNull(flags.apply('>', "1", false));
        flags.apply('>', "11", false);
        assertEquals(11, flags.current(false));
        flags.apply('<', "", false);
        assertEquals(1, flags.current(false));
        // Popping past the bottom is legacy, not an error.
        flags.apply('<', "5", false);
        assertEquals(0, flags.current(false));
    }

    @Test
    void eachScreenHasItsOwnStack() {
        flags.apply('>', "1", false);
        flags.apply('>', "31", true);
        assertEquals(1, flags.current(false));
        assertEquals(31, flags.current(true));
        assertEquals("\u001b[?31u", query(true));
    }

    @Test
    void setReplacesOrsOrClears() {
        flags.apply('=', "1", false);
        assertEquals(1, flags.current(false));
        flags.apply('=', "8;2", false);
        assertEquals(9, flags.current(false));
        flags.apply('=', "1;3", false);
        assertEquals(8, flags.current(false));
        flags.apply('=', "2", false);
        assertEquals(2, flags.current(false));
    }

    @Test
    void unknownBitsAreMaskedOff() {
        flags.apply('>', "255", false);
        assertEquals(KeyboardFlags.ALL, flags.current(false));
    }

    @Test
    void theStackIsBoundedAndDropsTheOldest() {
        for (int i = 0; i < KeyboardFlags.MAX_DEPTH + 5; i++) flags.apply('>', "1", false);
        flags.apply('<', String.valueOf(KeyboardFlags.MAX_DEPTH - 1), false);
        assertEquals(1, flags.current(false));
        flags.apply('<', "", false);
        assertEquals(0, flags.current(false));
    }

    @Test
    void aResetClearsBothScreens() {
        flags.apply('>', "1", false);
        flags.apply('>', "1", true);
        flags.reset();
        assertEquals(0, flags.current(false));
        assertEquals(0, flags.current(true));
    }

    @Test
    void readsParametersWithTheirDefaults() {
        assertArrayEquals(new int[] {0, 1}, KeyboardFlags.numbers(""));
        assertArrayEquals(new int[] {5, 1}, KeyboardFlags.numbers("5"));
        assertArrayEquals(new int[] {5, 3}, KeyboardFlags.numbers("5;3"));
        assertArrayEquals(new int[] {0, 2}, KeyboardFlags.numbers(";2"));
        assertArrayEquals(new int[] {0, 1}, KeyboardFlags.numbers("99999999999"));
    }
}
