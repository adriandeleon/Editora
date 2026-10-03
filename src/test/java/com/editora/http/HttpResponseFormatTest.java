package com.editora.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Response-save extension inference, the value-preserving JSON pretty-printer, and the viewer body. */
class HttpResponseFormatTest {

    @Test
    void extensionForKnownContentTypes() {
        assertEquals(".json", HttpResponseFormat.extensionFor("application/json"));
        assertEquals(".json", HttpResponseFormat.extensionFor("application/vnd.api+json; charset=utf-8"));
        assertEquals(".html", HttpResponseFormat.extensionFor("text/html"));
        assertEquals(".xml", HttpResponseFormat.extensionFor("application/xml"));
    }

    @Test
    void extensionForIsCaseInsensitive() {
        assertEquals(".json", HttpResponseFormat.extensionFor("APPLICATION/JSON"));
        assertEquals(".html", HttpResponseFormat.extensionFor("TEXT/HTML"));
    }

    @Test
    void extensionForFallsBackToTxt() {
        assertEquals(".txt", HttpResponseFormat.extensionFor("text/plain"));
        assertEquals(".txt", HttpResponseFormat.extensionFor(""));
        assertEquals(".txt", HttpResponseFormat.extensionFor(null));
    }

    private static HttpResult json(String body) {
        return new HttpResult(200, java.util.List.of(), body, "application/json", 1, body.length(), null);
    }

    @Test
    void prettyPrintingNeverChangesANumber() {
        String body = "{\"price\":10.50,\"exp\":1e2,\"big\":1234567890.12345678901234567890,"
                + "\"id\":9007199254740993,\"neg\":-0.0,\"zero\":0.10}";
        String pretty = HttpResponseFormat.prettyBody(body, "application/json");
        assertTrue(pretty.contains("\"price\" : 10.50"), pretty);
        assertTrue(pretty.contains("\"exp\" : 1e2"), pretty);
        assertTrue(pretty.contains("\"big\" : 1234567890.12345678901234567890"), pretty);
        assertTrue(pretty.contains("\"id\" : 9007199254740993"), pretty);
        assertTrue(pretty.contains("\"neg\" : -0.0"), pretty);
        assertTrue(pretty.contains("\"zero\" : 0.10"), pretty);
        // Whitespace is the only difference from what the server sent.
        assertEquals(body, pretty.replaceAll("\\s+", ""));
    }

    @Test
    void prettyPrintingKeepsDuplicateKeysAndNesting() {
        String pretty =
                HttpResponseFormat.prettyBody("{\"a\":1,\"a\":2,\"l\":[true,null,\"x y\"]}", "application/json");
        assertEquals("{\"a\":1,\"a\":2,\"l\":[true,null,\"xy\"]}", pretty.replaceAll("\\s+", ""));
        assertTrue(pretty.contains("\"x y\""), "spaces inside strings are untouched");
        assertTrue(pretty.lines().count() > 3, "it is actually re-indented: " + pretty);
    }

    @Test
    void invalidJsonAndNonJsonBodiesAreShownRaw() {
        assertEquals("{\"a\": 1,}", HttpResponseFormat.prettyBody("{\"a\": 1,}", "application/json"));
        assertEquals("{\"a\":NaN}", HttpResponseFormat.prettyBody("{\"a\":NaN}", "application/json"));
        assertEquals("<a/>", HttpResponseFormat.prettyBody("<a/>", "application/xml"));
        assertEquals("", HttpResponseFormat.prettyBody(null, "application/json"));
    }

    @Test
    void viewClipsALongBodyAndReportsTheFullLength() {
        String body = "x".repeat(1000);
        HttpResult r = new HttpResult(200, java.util.List.of(), body, "text/plain", 1, 1000, null);
        HttpResponseFormat.BodyView view = HttpResponseFormat.view(r, 400);
        assertTrue(view.clipped());
        assertEquals(400, view.text().length());
        assertEquals(1000, view.totalChars());

        HttpResponseFormat.BodyView whole = HttpResponseFormat.view(r, 1000);
        assertFalse(whole.clipped());
        assertEquals(body, whole.text());
        // A cut never lands inside a surrogate pair.
        HttpResult emoji = new HttpResult(200, java.util.List.of(), "ab\uD83D\uDE00cd", "text/plain", 1, 8, null);
        assertEquals("ab", HttpResponseFormat.view(emoji, 3).text());
    }

    @Test
    void binaryResponsesHaveNoTextView() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
        HttpResult r = HttpResult.ofBytes(200, java.util.List.of(), png, "image/png", 1, java.util.List.of(), false);
        assertTrue(r.binary());
        assertEquals("", r.body());
        assertEquals(12, r.sizeBytes());
        assertTrue(HttpResponseFormat.view(r, 100).binary());
        assertTrue(HttpResponseFormat.render(r).contains("binary body"));
        // A declared charset (UTF-16 is full of NULs) and a textual type are text.
        byte[] utf16 = "héllo".getBytes(java.nio.charset.StandardCharsets.UTF_16);
        HttpResult t = HttpResult.ofBytes(
                200, java.util.List.of(), utf16, "text/plain; charset=utf-16", 1, java.util.List.of(), false);
        assertFalse(t.binary());
        assertEquals("héllo", t.body());
        HttpResult latin = HttpResult.ofBytes(
                200,
                java.util.List.of(),
                "café".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                "text/plain; charset=\"ISO-8859-1\"",
                1,
                java.util.List.of(),
                false);
        assertEquals("café", latin.body());
        assertEquals(4, latin.rawBody().length, "the raw bytes stay in the wire encoding");
    }

    @Test
    void reportMarksATruncatedResponse() {
        HttpResult r = HttpResult.ofBytes(
                200, java.util.List.of(), "partial".getBytes(), "text/plain", 1, java.util.List.of(), true);
        assertTrue(r.truncated());
        assertTrue(HttpResponseFormat.render(r).contains("truncated"));
        assertFalse(HttpResponseFormat.render(json("{}")).contains("truncated"));
    }
}
