package com.editora.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for default URL auto-encoding. */
class UrlEncodingTest {

    @Test
    void encodesSpacesAndIllegalChars() {
        assertEquals("https://x/a%20b", UrlEncoding.encodeIllegal("https://x/a b"));
        assertEquals("https://x/%7Bk%7D", UrlEncoding.encodeIllegal("https://x/{k}"));
    }

    @Test
    void preservesExistingEscapesAndStructure() {
        assertEquals("https://x/a%20b?q=1&r=2", UrlEncoding.encodeIllegal("https://x/a%20b?q=1&r=2"));
    }

    @Test
    void encodesNonAsciiAsUtf8() {
        assertEquals("https://x/caf%C3%A9", UrlEncoding.encodeIllegal("https://x/café"));
    }
}
