package com.editora.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.editora.http.HttpFile.Parsed;
import org.junit.jupiter.api.Test;

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
    void rendererPrettyPrintsJsonAndAddsStatusFooter() {
        HttpResult r = new HttpResult(
                200,
                java.util.List.<String[]>of(new String[] {"content-type", "application/json"}),
                "{\"a\":1}",
                "application/json",
                12,
                7,
                null);
        String out = HttpResponseFormat.render(r);
        assertTrue(out.startsWith("HTTP 200\n"));
        assertTrue(out.contains("\"a\" : 1")); // pretty-printed
        assertTrue(out.contains("200"));
        assertTrue(out.contains("12 ms"));
    }

    @Test
    void rendererShowsErrorForFailedRequest() {
        HttpResult r = new HttpResult(0, java.util.List.of(), "", "", 0, 0, "Connection refused");
        assertTrue(HttpResponseFormat.render(r).contains("Connection refused"));
    }
}
