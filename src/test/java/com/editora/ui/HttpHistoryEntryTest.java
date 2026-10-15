package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.editora.config.HttpResponseHistory;
import com.editora.http.HttpExchange;
import com.editora.http.HttpResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** History rows: their labels, the memory budget (B4), and the masked, capped form saved to disk (C12). */
class HttpHistoryEntryTest {

    private static HttpExchange ex(String body, List<String[]> reqHeaders, List<String[]> respHeaders) {
        HttpResult r = new HttpResult(200, respHeaders, body, "application/json", 5, body.length(), null, List.of());
        return new HttpExchange("GET http://x.test/u", "GET", "http://x.test/u", reqHeaders, "{\"p\":1}", r);
    }

    private static HttpHistoryEntry entry(int bodyChars) {
        return new HttpHistoryEntry(ex("x".repeat(bodyChars), List.of(), List.of()), null, 0, 0, false, false, false);
    }

    @Test
    void theLabelHasTheOutcomeTheTimeAndTheName() {
        HttpHistoryEntry named =
                new HttpHistoryEntry(ex("{}", List.of(), List.of()), "Get user", 2, 3_723_000L, false, false, false);
        assertEquals("200  01:02:03  Get user — GET http://x.test/u", named.label(ZoneOffset.UTC));
        HttpHistoryEntry failed = HttpHistoryEntry.of(new HttpExchange(
                "GET http://x.test/u", "GET", "http://x.test/u", List.of(), "", HttpResult.failure("x", List.of())));
        assertTrue(failed.label(ZoneOffset.UTC).startsWith("⚠  "), failed.label(ZoneOffset.UTC));
    }

    @Test
    void pastTheBudgetTheOldestBodiesGoFirstAndTheNewestIsNeverStripped() {
        List<HttpHistoryEntry> history = new ArrayList<>(List.of(entry(100), entry(100), entry(100)));
        long each = history.get(0).bodyBytes(); // 100 bytes + 200 for the text
        assertEquals(300, each);
        List<HttpHistoryEntry> kept = HttpHistoryEntry.withinBudget(history, 2 * each);
        assertFalse(kept.get(0).bodyDropped());
        assertFalse(kept.get(1).bodyDropped());
        assertTrue(kept.get(2).bodyDropped(), "the oldest gave up its body");
        assertEquals(100, kept.get(2).result().sizeBytes(), "status, headers, timing and size stay");
        assertEquals(0, kept.get(2).bodyBytes());

        List<HttpHistoryEntry> lone = HttpHistoryEntry.withinBudget(List.of(entry(1000)), 10);
        assertFalse(lone.get(0).bodyDropped(), "the newest keeps its body however large");
        assertSame(
                history.get(1),
                HttpHistoryEntry.withinBudget(history, Long.MAX_VALUE).get(1));
    }

    @Test
    void theSavedFormMasksCredentialsAndCapsTheBody() {
        HttpExchange e = ex(
                "y".repeat(HttpResponseHistory.MAX_BODY_CHARS + 10),
                List.<String[]>of(
                        new String[] {"Authorization", "Bearer s3cr3t"},
                        new String[] {"X-Api-Key", "k"},
                        new String[] {"Accept", "application/json"}),
                List.<String[]>of(new String[] {"Set-Cookie", "session=abc"}, new String[] {"ETag", "\"1\""}));
        HttpResponseHistory.Response s = new HttpHistoryEntry(e, "n", 1, 9, false, false, false).toStored();
        assertEquals(
                List.of(
                        new HttpResponseHistory.Header("Authorization", HttpResponseHistory.MASK),
                        new HttpResponseHistory.Header("X-Api-Key", HttpResponseHistory.MASK),
                        new HttpResponseHistory.Header("Accept", "application/json")),
                s.requestHeaders());
        assertEquals(
                List.of(
                        new HttpResponseHistory.Header("Set-Cookie", HttpResponseHistory.MASK),
                        new HttpResponseHistory.Header("ETag", "\"1\"")),
                s.headers());
        assertEquals(HttpResponseHistory.MAX_BODY_CHARS, s.body().length());
        assertTrue(s.bodyCut());

        HttpHistoryEntry back = HttpHistoryEntry.fromStored(s);
        assertEquals("n", back.name());
        assertEquals(1, back.requestIndex());
        assertEquals(200, back.result().status());
        assertTrue(back.bodyCut(), "a cut body is shown as cut by the history");
        assertFalse(back.result().truncated(), "…not as a download that stopped short");
        assertEquals("{\"p\":1}", back.exchange().requestBody());
    }

    @Test
    void aBinaryBodyIsNotSavedAndComesBackAsNotKept() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 1};
        HttpResult r = HttpResult.ofBytes(200, List.of(), png, "image/png", 3, List.of(), false);
        HttpExchange e = new HttpExchange("GET http://x.test/i.png", "GET", "http://x.test/i.png", List.of(), "", r);
        HttpResponseHistory.Response s = HttpHistoryEntry.of(e).toStored();
        assertTrue(s.binary());
        assertEquals("", s.body());
        HttpHistoryEntry back = HttpHistoryEntry.fromStored(s);
        assertTrue(back.binaryNotKept());
        assertEquals(8, back.result().sizeBytes());
        assertEquals(0, back.result().body().getBytes(StandardCharsets.UTF_8).length);
    }
}
