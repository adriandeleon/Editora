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

    @Test
    void encodesCharactersOutsideTheBmpWhole() {
        // Each UTF-16 half used to be encoded alone, which sent "%3F%3F" for an emoji.
        assertEquals("https://x/?q=%F0%9F%98%80", UrlEncoding.encodeIllegal("https://x/?q=😀"));
    }

    @Test
    void aPercentThatStartsNoEscapeIsEncoded() {
        assertEquals("https://x/?d=100%25", UrlEncoding.encodeIllegal("https://x/?d=100%"));
        assertEquals("https://x/?d=%25zz", UrlEncoding.encodeIllegal("https://x/?d=%zz"));
        assertEquals("https://x/a%2Fb", UrlEncoding.encodeIllegal("https://x/a%2Fb"));
    }

    @Test
    void aNonAsciiHostBecomesItsIdnForm() {
        assertEquals("http://xn--mnchen-3ya.de/stra%C3%9Fe", UrlEncoding.encodeIllegal("http://münchen.de/straße"));
        assertEquals("http://u:p@xn--mnchen-3ya.de:8080/", UrlEncoding.encodeIllegal("http://u:p@münchen.de:8080/"));
    }

    @Test
    void aTargetWithoutASchemeIsHttp() {
        assertEquals("http://example.com/api", UrlEncoding.withScheme("example.com/api", null));
        assertEquals("http://localhost:8080/x", UrlEncoding.withScheme("localhost:8080/x", null));
        assertEquals("https://x/y", UrlEncoding.withScheme("https://x/y", null));
        assertEquals("http://api.test:9/users", UrlEncoding.withScheme("/users", " api.test:9 "));
        assertEquals("/users", UrlEncoding.withScheme("/users", null), "origin form without a Host stays as is");
    }
}
