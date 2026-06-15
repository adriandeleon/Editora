package com.editora.http;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the per-run captured-response session backing request chaining. */
class CapturedResponsesTest {

    private static HttpResult result() {
        return new HttpResult(
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
}
