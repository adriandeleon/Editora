package com.editora.process;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decoding the output of a child that runs in the user's locale: UTF-8 when it is UTF-8, the native encoding
 * when it is not — a JVM or Python child writes the ANSI code page to a pipe on Windows (ISO-8859-1 under a
 * legacy Unix locale), which read as UTF-8 came out as {@code caf� ma�ana}.
 */
class ChildTextTest {

    private static final Charset CP1252 = Charset.forName("windows-1252");

    private static String decode(byte[] bytes, Charset fallback) {
        return ChildText.decode(bytes, 0, bytes.length, fallback);
    }

    @Test
    void utf8OutputIsUtf8WhateverTheNativeEncoding() {
        byte[] utf8 = "café mañana über €5 日本".getBytes(StandardCharsets.UTF_8);
        assertEquals("café mañana über €5 日本", decode(utf8, CP1252));
        assertEquals("café mañana über €5 日本", decode(utf8, StandardCharsets.UTF_8));
    }

    @Test
    void outputThatIsNotUtf8IsReadInTheNativeEncoding() {
        assertEquals("café mañana über €5", decode("café mañana über €5".getBytes(CP1252), CP1252));
        assertEquals("März", decode("März".getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1));
        assertEquals("日本語", decode("日本語".getBytes(Charset.forName("MS932")), Charset.forName("MS932")));
    }

    @Test
    void onAUtf8SystemMalformedBytesStillDecodeLeniently() {
        assertEquals("caf�", decode(new byte[] {'c', 'a', 'f', (byte) 0xE9}, StandardCharsets.UTF_8));
    }

    @Test
    void anUnknownNativeEncodingFallsBackToUtf8() {
        assertEquals(StandardCharsets.UTF_8, ChildText.nativeCharset(null));
        assertEquals(StandardCharsets.UTF_8, ChildText.nativeCharset("no-such-charset"));
        assertEquals(CP1252, ChildText.nativeCharset("Cp1252"));
    }

    /** A flush or a cap in the middle of a character must hold the lead bytes back, not take the fallback. */
    @Test
    void anIncompleteTrailingCharacterIsMeasured() {
        byte[] euro = "a€".getBytes(StandardCharsets.UTF_8); // 61 E2 82 AC
        assertEquals(0, ChildText.incompleteUtf8Tail(euro, 4));
        assertEquals(2, ChildText.incompleteUtf8Tail(euro, 3));
        assertEquals(1, ChildText.incompleteUtf8Tail(euro, 2));
        assertEquals(0, ChildText.incompleteUtf8Tail(euro, 1));
        assertEquals(0, ChildText.incompleteUtf8Tail(euro, 0));
        byte[] emoji = "😀".getBytes(StandardCharsets.UTF_8); // 4 bytes
        assertEquals(3, ChildText.incompleteUtf8Tail(emoji, 3));
        assertEquals(0, ChildText.incompleteUtf8Tail(emoji, 4));
    }
}
