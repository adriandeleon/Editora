package com.editora.terminal;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a traced keystroke says. The formatting is the diagnostic, so it is worth pinning. */
class KeyTraceTest {

    @Test
    void charactersAreShownAsCodePoints() {
        // Written literally, the interesting cases are invisible in the log — which is exactly how
        // they were invisible on screen.
        assertEquals("U+0061", KeyTrace.codePoints("a"));
        assertEquals("U+0006", KeyTrace.codePoints("\u0006"));
        assertEquals("U+0000", KeyTrace.codePoints("\u0000"));
        assertEquals("<empty>", KeyTrace.codePoints(""));
        assertEquals("null", KeyTrace.codePoints(null));
    }

    @Test
    void bytesAreShownAsHex() {
        assertEquals("06", KeyTrace.hex(new byte[] {0x06}));
        assertEquals("1b 5b 41", KeyTrace.hex(new byte[] {0x1b, '[', 'A'}));
        assertEquals("<nothing>", KeyTrace.hex(null));
    }

    @Test
    void anInvalidByteSequenceIsCalledOut() {
        // The whole reason for the flag: a shell prints an invalid byte as an unprintable box, which
        // looks identical to a character it merely has no glyph for. One is a bug on this side.
        assertTrue(KeyTrace.hex(new byte[] {(byte) 0xff}).endsWith("INVALID-UTF8"));
        assertTrue(KeyTrace.hex(new byte[] {(byte) 0xc3}).endsWith("INVALID-UTF8")); // a truncated pair
        assertFalse(KeyTrace.hex(new byte[] {(byte) 0xc3, (byte) 0xa9}).contains("INVALID")); // é
        assertFalse(KeyTrace.hex(new byte[] {0x06}).contains("INVALID"));
    }

    @Test
    void aTracedEventNamesTheKeyTheModifiersAndTheBytes() {
        KeyEvent event = new KeyEvent(KeyEvent.KEY_PRESSED, "", "\u0006", KeyCode.F, false, true, false, false);
        String line = KeyTrace.describe(event, new byte[] {0x06});
        assertTrue(line.contains("KEY_PRESSED"), line);
        assertTrue(line.contains("code=F"), line);
        assertTrue(line.contains("text=U+0006"), line);
        // The physical key, and only it: isShortcutDown is Ctrl here and Meta on macOS, so
        // including it would print one key twice on one platform and not the other.
        assertTrue(line.contains("mods=[ctrl]"), line);
        assertTrue(line.contains("sent=06"), line);
    }

    @Test
    void anEventThatSentNothingSaysSo() {
        // The case that matters when a key "does nothing": it distinguishes not encoded from
        // encoded-and-swallowed, which look the same from the outside.
        KeyEvent event = new KeyEvent(KeyEvent.KEY_TYPED, "\u0006", "", KeyCode.UNDEFINED, false, true, false, false);
        assertTrue(KeyTrace.describe(event, null).contains("sent=<nothing>"));
    }
}
