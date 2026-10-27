package com.editora.http;

import java.util.List;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the per-run captured-response session backing request chaining. */
class CapturedResponsesTest {

    @BeforeAll
    static void loadMessages() {
        Messages.init("en"); // the reasons are read in English: not left to whichever test ran first in the fork
    }

    private static HttpResult result() {
        return HttpFixtures.text(
                201,
                List.of(new String[] {"Content-Type", "application/json"}, new String[] {"X-Trace", "t-1"}),
                "{ \"token\": \"abc\", \"id\": 7 }",
                "application/json",
                12,
                25,
                null);
    }

    @Test
    void resolvesBodyPathHeaderAndStatus() {
        CapturedResponses cr = new CapturedResponses();
        cr.put("login", result());
        assertEquals("abc", cr.resolve("login.response.body.$.token"));
        assertEquals("7", cr.resolve("login.response.body.id"));
        assertEquals("t-1", cr.resolve("login.response.headers.X-Trace"));
        assertEquals("201", cr.resolve("login.response.status"));
    }

    @Test
    void headerLookupIsCaseInsensitive() {
        CapturedResponses cr = new CapturedResponses();
        cr.put("login", result());
        assertEquals("application/json", cr.resolve("login.response.headers.content-type"));
    }

    @Test
    void bodyStarReturnsRawBody() {
        CapturedResponses cr = new CapturedResponses();
        cr.put("login", result());
        assertEquals(
                "{ \"token\": \"abc\", \"id\": 7 }".replaceAll("\\s+", ""),
                cr.resolve("login.response.body.*").replaceAll("\\s+", ""));
    }

    @Test
    void unknownRequestOrRefIsEmpty() {
        CapturedResponses cr = new CapturedResponses();
        cr.put("login", result());
        assertEquals("", cr.resolve("other.response.body.$.token"));
        assertEquals("", cr.resolve("login.response.body.$.missing"));
        assertEquals("", cr.resolve("not a ref"));
    }

    @Test
    void isResponseRefDetectsTheMarker() {
        assertTrue(CapturedResponses.isResponseRef("login.response.body.$.x"));
        assertFalse(CapturedResponses.isResponseRef("host"));
        assertFalse(CapturedResponses.isResponseRef(null));
    }

    @Test
    void aCancelledRunKeepsTheResponseOfTheRunBeforeIt() {
        CapturedResponses cr = new CapturedResponses();
        cr.put("login", result());
        cr.put("login", HttpResult.failure(HttpClientService.CANCELLED, List.of()));
        assertEquals("abc", cr.resolve("login.response.body.$.token"), "the token from the completed run stays");
        cr.put("login", HttpResult.failure("connection refused", List.of()));
        String[] why = new String[1];
        assertEquals(null, cr.lookup("login.response.body.$.token", why), "a real failure still replaces it");
        assertTrue(why[0].contains("login"), why[0]);
    }

    @Test
    void aHugeBodyIsNotKeptButItsStatusAndHeadersAre() {
        CapturedResponses cr = new CapturedResponses();
        String huge = "{\"token\":\"abc\",\"pad\":\"" + "x".repeat(CapturedResponses.MAX_BODY_CHARS) + "\"}";
        byte[] raw = huge.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        cr.put(
                "dump",
                HttpFixtures.bytes(
                        200,
                        List.<String[]>of(new String[] {"ETag", "e1"}),
                        raw,
                        "application/json",
                        1,
                        List.of(),
                        false));
        String[] why = new String[1];
        assertEquals(null, cr.lookup("dump.response.body.$.token", why));
        assertTrue(why[0].contains("too large"), why[0]);
        assertEquals("200", cr.resolve("dump.response.status"));
        assertEquals("e1", cr.resolve("dump.response.headers.ETag"));
    }
}
