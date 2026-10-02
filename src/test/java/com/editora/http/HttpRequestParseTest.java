package com.editora.http;

import com.editora.http.HttpFile.Parsed;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for parsing a single request block into method/URL/headers/body, env vars, and rendering. */
class HttpRequestParseTest {

    @Test
    void parsesMethodUrlHeadersAndBody() {
        String block = """
                POST https://api.test/users
                Content-Type: application/json
                Authorization: Bearer {{token}}

                {
                  "name": "Ada"
                }""";
        Parsed p = HttpFile.parseRequest(block);
        assertEquals("POST", p.method());
        assertEquals("https://api.test/users", p.url());
        assertEquals(2, p.headers().size());
        assertEquals("Content-Type", p.headers().get(0)[0]);
        assertEquals("application/json", p.headers().get(0)[1]);
        assertTrue(p.body().contains("\"name\": \"Ada\""));
    }

    @Test
    void joinsIndentedUrlContinuationLines() {
        Parsed p = HttpFile.parseRequest("GET https://api.test/get\n    ?a=1\n    &b=2\nAccept: */*\n");
        assertEquals("https://api.test/get?a=1&b=2", p.url());
        assertEquals(1, p.headers().size());
    }

    @Test
    void bareUrlNoHeadersNoBody() {
        Parsed p = HttpFile.parseRequest("https://api.test/ping\n");
        assertEquals("GET", p.method());
        assertEquals("https://api.test/ping", p.url());
        assertTrue(p.headers().isEmpty());
        assertEquals("", p.body());
    }

    @Test
    void responseHandlerLineEndsTheBody() {
        Parsed p = HttpFile.parseRequest("GET https://api.test/x\n\nbody line\n> {% client.test(); %}\n");
        assertEquals("body line", p.body());
    }

    @Test
    void envVariablesReadForOneEnvironment() {
        String json = """
                { "dev": { "host": "http://localhost", "token": "d" },
                  "prod": { "host": "https://api.test", "token": "p" } }
                """;
        assertEquals("https://api.test", HttpEnv.variables(json, "prod").get("host"));
        assertEquals("d", HttpEnv.variables(json, "dev").get("token"));
        assertTrue(HttpEnv.variables(json, "missing").isEmpty());
    }

    @Test
    void rendererKeepsTheBodyAsReceivedAndAddsStatusFooter() {
        HttpResult r = new HttpResult(
                200,
                java.util.List.<String[]>of(new String[] {"content-type", "application/json"}),
                "{\"a\":1.50}",
                "application/json",
                12,
                7,
                null);
        String out = HttpResponseFormat.render(r);
        assertTrue(out.startsWith("HTTP 200\n"));
        assertTrue(out.contains("\n{\"a\":1.50}\n"), "a saved report carries the body verbatim: " + out);
        assertTrue(out.contains("200"));
        assertTrue(out.contains("12 ms"));
        // The viewer is what pretty-prints.
        assertTrue(HttpResponseFormat.view(r, 1000).text().contains("\"a\" : 1.50"));
    }

    @Test
    void rendererShowsErrorForFailedRequest() {
        HttpResult r = new HttpResult(0, java.util.List.of(), "", "", 0, 0, "Connection refused");
        assertTrue(HttpResponseFormat.render(r).contains("Connection refused"));
    }

    // --- body without a blank line before it (#441) -------------------------------------------------

    @Test
    void aBodyWithNoBlankLineBeforeItWarnsAndIsNotSentAsAHeader() {
        // The reported typo: no blank line before the JSON body. `{"a": 1}` has a colon, so the old parser
        // split it into a bogus header ({"a" = 1}) and sent the POST with no body — silently.
        Parsed p = HttpFile.parseRequest("""
                POST https://example.com/api
                Content-Type: application/json
                {"a": 1}""");
        assertEquals(1, p.headers().size(), "only the real header survives");
        assertEquals("Content-Type", p.headers().get(0)[0]);
        assertEquals("", p.body(), "no body without the blank separator (spec-compliant)");
        assertTrue(
                p.warning() != null && p.warning().contains("blank line"),
                "the malformed shape is surfaced, not silently dropped: " + p.warning());
    }

    // --- bodies that start with '<' (XML / HTML / SOAP) ----------------------------------------------

    @Test
    void anXmlBodyIsAnInlineBodyNotAFileReference() {
        Parsed p = HttpFile.parseRequest("""
                POST https://example.com/soap
                Content-Type: text/xml

                <?xml version="1.0"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body/>
                </soap:Envelope>""");
        assertNull(p.bodyRef(), "\"<?xml …\" is not \"< path\"");
        assertTrue(p.body().startsWith("<?xml version=\"1.0\"?>\n<soap:Envelope"), p.body());
        assertTrue(p.body().endsWith("</soap:Envelope>"), p.body());

        Parsed html = HttpFile.parseRequest("PUT https://example.com/page\n\n<html><body>hi</body></html>");
        assertNull(html.bodyRef());
        assertEquals("<html><body>hi</body></html>", html.body());

        Parsed doctype = HttpFile.parseRequest("PUT https://example.com/page\n\n<!DOCTYPE html>\n<p>x</p>");
        assertNull(doctype.bodyRef());
        assertEquals("<!DOCTYPE html>\n<p>x</p>", doctype.body());
    }

    @Test
    void aFileReferenceNeedsWhitespaceAfterTheAngleBracket() {
        assertEquals(
                "./body.xml",
                HttpFile.parseRequest("POST https://x.test/\n\n< ./body.xml")
                        .bodyRef()
                        .path());
        assertEquals(
                "body.json",
                HttpFile.parseRequest("POST https://x.test/\n\n<\tbody.json")
                        .bodyRef()
                        .path());
        HttpFile.BodyRef substituted =
                HttpFile.parseRequest("POST https://x.test/\n\n<@ ./body.json").bodyRef();
        assertTrue(substituted.substitute());
        assertEquals("./body.json", substituted.path());
        assertNull(substituted.encoding());
        HttpFile.BodyRef encoded = HttpFile.parseRequest("POST https://x.test/\n\n<@utf-16 body.json")
                .bodyRef();
        assertEquals("utf-16", encoded.encoding());
        assertEquals("body.json", encoded.path());
        // "<" alone, or "<@enc" with no path, names no file: inline text.
        assertNull(HttpFile.parseRequest("POST https://x.test/\n\n<").bodyRef());
        assertNull(HttpFile.parseRequest("POST https://x.test/\n\n<@latin1").bodyRef());
    }

    @Test
    void commentLinesInsideTheHeaderBlockAreNeitherHeadersNorWarnings() {
        Parsed p = HttpFile.parseRequest("""
                GET https://example.com/
                Accept: application/json
                #Authorization: Bearer old-token
                # switched to the key below
                // X-Debug: 1
                X-Api-Key: abc

                """);
        assertEquals(2, p.headers().size(), "only the two real headers");
        assertEquals("Accept", p.headers().get(0)[0]);
        assertEquals("X-Api-Key", p.headers().get(1)[0]);
        assertNull(p.warning(), "a comment is not a malformed header");
    }

    @Test
    void aWellFormedRequestHasNoWarning() {
        Parsed p = HttpFile.parseRequest("""
                POST https://example.com/api
                Content-Type: application/json

                {"a": 1}""");
        assertEquals("{\"a\": 1}", p.body());
        assertNull(p.warning());
    }

    @Test
    void aValidHeaderIsNeverMistakenForABody() {
        // Real header names use tchars beyond letters (X-Api-Key, values with colons like a URL) — none warn.
        Parsed p = HttpFile.parseRequest("""
                GET https://example.com/
                X-Api-Key: abc.def
                Referer: https://x/y:8080

                """);
        assertEquals(2, p.headers().size());
        assertEquals("https://x/y:8080", p.headers().get(1)[1]);
        assertNull(p.warning());
    }
}
