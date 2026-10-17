package com.editora.ui;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.editora.editor.HttpLineResult;
import com.editora.http.HttpClientService;
import com.editora.http.HttpExchange;
import com.editora.http.HttpFile;
import com.editora.http.HttpFixtures;
import com.editora.http.HttpResult;
import com.editora.http.HttpTests;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a request line shows after it ran, and which History entry each request shows (U11). */
class HttpInlineResultTest {

    @BeforeAll
    static void english() {
        com.editora.i18n.Messages.init("en");
    }

    private static HttpResult status(int code, long ms) {
        return HttpFixtures.text(code, List.of(), "", "text/plain", ms, 44, null);
    }

    private static HttpLineResult.Segment seg(String text, String cls) {
        return new HttpLineResult.Segment(text, cls);
    }

    private static HttpTests tests(boolean... passed) {
        List<HttpTests.Test> list = new java.util.ArrayList<>();
        for (int i = 0; i < passed.length; i++) {
            list.add(new HttpTests.Test("t" + i, passed[i], passed[i] ? null : "nope"));
        }
        return new HttpTests(list, List.of(), null);
    }

    @Test
    void aResponseShowsItsStatusInItsColourAndTheTimeMuted() {
        assertEquals(
                List.of(seg("200 OK", HttpInlineResult.OK), seg(" · 83 ms", HttpInlineResult.MUTED)),
                HttpInlineResult.segments(status(200, 83)));
        assertEquals("404 Not Found · 1 ms", HttpInlineResult.text(status(404, 1)));
        assertEquals(
                HttpInlineResult.CLIENT_ERROR,
                HttpInlineResult.segments(status(404, 1)).get(0).styleClass());
        assertEquals(
                HttpInlineResult.REDIRECT,
                HttpInlineResult.segments(status(302, 1)).get(0).styleClass());
        assertEquals(
                HttpInlineResult.SERVER_ERROR,
                HttpInlineResult.segments(status(500, 1)).get(0).styleClass());
        assertEquals(HttpInlineResult.OK, HttpInlineResult.glyphClass(status(204, 1)));
        assertEquals(HttpInlineResult.CLIENT_ERROR, HttpInlineResult.glyphClass(status(404, 1)));
        assertEquals(HttpInlineResult.SERVER_ERROR, HttpInlineResult.glyphClass(status(503, 1)));
    }

    @Test
    void failedTestsAreRedAndMakeTheGlyphRedUnderAGreenStatus() {
        HttpResult r = status(200, 83).withTests(tests(true, false, true), List.of());
        assertEquals(
                List.of(
                        seg("200 OK", HttpInlineResult.OK),
                        seg(" · 83 ms · ", HttpInlineResult.MUTED),
                        seg("✗ 1 of 3 tests", HttpInlineResult.TESTS_FAILED)),
                HttpInlineResult.segments(r));
        assertEquals(HttpInlineResult.FAILED, HttpInlineResult.glyphClass(r));

        HttpResult passed = status(201, 12).withTests(tests(true, true, true), List.of());
        assertEquals(
                List.of(seg("201 Created", HttpInlineResult.OK), seg(" · 12 ms · ✓ 3 tests", HttpInlineResult.MUTED)),
                HttpInlineResult.segments(passed),
                "tests that passed are said quietly");
        assertEquals(HttpInlineResult.OK, HttpInlineResult.glyphClass(passed));
        assertEquals(
                "200 OK · 5 ms · ✓ 1 test", HttpInlineResult.text(status(200, 5).withTests(tests(true), List.of())));
        assertEquals(
                "200 OK · 5 ms · ✗ 1 of 1 test",
                HttpInlineResult.text(status(200, 5).withTests(tests(false), List.of())));

        HttpResult handlerError = status(200, 5).withTests(new HttpTests(List.of(), List.of(), "boom"), List.of());
        assertEquals("200 OK · 5 ms · " + tr("httppanel.testsError"), HttpInlineResult.text(handlerError));
        assertEquals(HttpInlineResult.FAILED, HttpInlineResult.glyphClass(handlerError));
    }

    @Test
    void aFailureShowsItsErrorUpToTheAdvice() {
        HttpResult refused = HttpResult.failure(tr("status.http.error.refused", "localhost:1"), List.of());
        assertEquals(List.of(seg("Connection refused", HttpInlineResult.FAILED)), HttpInlineResult.segments(refused));
        assertEquals(HttpInlineResult.FAILED, HttpInlineResult.glyphClass(refused));

        HttpResult cancelled = HttpResult.failure(HttpClientService.CANCELLED, List.of());
        assertEquals(
                List.of(seg(tr("httpinline.cancelled"), HttpInlineResult.CANCELLED)),
                HttpInlineResult.segments(cancelled));
        assertEquals(HttpInlineResult.CANCELLED, HttpInlineResult.glyphClass(cancelled));

        HttpResult notSent = HttpResult.failure(tr("http.notSent.prefix") + " save the file first", List.of());
        assertEquals(tr("httppanel.notSent"), HttpInlineResult.text(notSent));

        String longError = "x".repeat(200);
        String shown = HttpInlineResult.shortError(longError);
        assertEquals(HttpInlineResult.MAX_ERROR, shown.length());
        assertTrue(shown.endsWith("…"));
        assertEquals("first line", HttpInlineResult.shortError("first line\nsecond line"));
        assertEquals(tr("httppanel.requestFailed"), HttpInlineResult.shortError(" "));
        assertTrue(HttpInlineResult.segments(null).isEmpty());
        assertNull(HttpInlineResult.glyphClass(null));
    }

    @Test
    void theTooltipSaysTheTimeAndTheDayWhenItIsNotToday() {
        ZoneId zone = ZoneId.of("UTC");
        long now =
                LocalDateTime.of(2026, 10, 16, 18, 0).atZone(zone).toInstant().toEpochMilli();
        long earlier = LocalDateTime.of(2026, 10, 16, 16, 43, 31)
                .atZone(zone)
                .toInstant()
                .toEpochMilli();
        long yesterday =
                LocalDateTime.of(2026, 10, 15, 9, 5, 0).atZone(zone).toInstant().toEpochMilli();
        assertEquals("16:43:31", HttpInlineResult.ranAt(earlier, now, zone));
        assertEquals("2026-10-15 09:05:00", HttpInlineResult.ranAt(yesterday, now, zone));
    }

    // --- which entry each request shows ---

    private static final String FILE = "### List\nGET http://x.test/users\n\n"
            + "### One\n# @name getUser\nGET http://x.test/users/42\n\n"
            + "### Create\nPOST http://x.test/users\nContent-Type: application/json\n\n{}\n";

    private static HttpHistoryEntry ran(String text, int index, long at, HttpResult r) {
        HttpFile.Request req = HttpFile.parse(text).get(index);
        HttpFile.Parsed p = HttpFile.parseRequest(req);
        HttpExchange ex = HttpFixtures.exchange(p.method() + " " + p.url(), p.method(), p.url(), List.of(), "", r);
        return HttpHistoryEntry.ran(ex, p, index, at, false);
    }

    @Test
    void eachRequestShowsItsNewestEntry() {
        HttpHistoryEntry oldList = ran(FILE, 0, 1, status(500, 9));
        HttpHistoryEntry user = ran(FILE, 1, 2, status(404, 3));
        HttpHistoryEntry newList = ran(FILE, 0, 3, status(200, 7));
        Map<Integer, HttpHistoryEntry> shown =
                HttpInlineResult.newestPerRequest(HttpFile.parse(FILE), List.of(newList, user, oldList));
        assertEquals(Map.of(0, newList, 1, user), shown, "Create never ran: nothing on its line");
    }

    @Test
    void aRequestWhoseLineWasEditedLosesItsResult() {
        HttpHistoryEntry user = ran(FILE, 1, 2, status(200, 3));
        HttpHistoryEntry create = ran(FILE, 2, 3, status(201, 3));
        List<HttpHistoryEntry> history = List.of(create, user);

        // The named request now asks for another user: its name still finds it, but its result was for /42.
        String edited = FILE.replace("users/42", "users/43");
        assertEquals(Map.of(2, create), HttpInlineResult.newestPerRequest(HttpFile.parse(edited), history));

        // A line inserted above moves the requests: each keeps its own result.
        String moved = "@token = abc\n\n" + FILE;
        assertEquals(Map.of(1, user, 2, create), HttpInlineResult.newestPerRequest(HttpFile.parse(moved), history));

        // A header edited under an unchanged line keeps it (the line is what the result describes).
        String header = FILE.replace("application/json", "text/plain");
        assertEquals(Map.of(1, user, 2, create), HttpInlineResult.newestPerRequest(HttpFile.parse(header), history));

        // The unnamed POST now goes elsewhere: no result rather than a wrong one.
        String elsewhere = FILE.replace("POST http://x.test/users", "POST http://x.test/accounts");
        assertEquals(Map.of(1, user), HttpInlineResult.newestPerRequest(HttpFile.parse(elsewhere), history));
    }

    @Test
    void anOlderEntryShowsWhenTheLineIsBackToWhatItRan() {
        HttpHistoryEntry for42 = ran(FILE, 1, 1, status(200, 3));
        String edited = FILE.replace("users/42", "users/43");
        HttpHistoryEntry for43 = ran(edited, 1, 2, status(404, 3));
        List<HttpHistoryEntry> history = List.of(for43, for42);
        assertEquals(Map.of(1, for43), HttpInlineResult.newestPerRequest(HttpFile.parse(edited), history));
        assertEquals(Map.of(1, for42), HttpInlineResult.newestPerRequest(HttpFile.parse(FILE), history));
    }

    @Test
    void anEntrySavedBeforeTheUrlAsWrittenWasKeptMatchesByTheUrlItSent() {
        String text = "@host = http://x.test\n\n### List\nGET {{host}}/users\n";
        HttpExchange ex = HttpFixtures.exchange(
                "GET http://x.test/users", "GET", "http://x.test/users", List.of(), "", status(200, 3));
        HttpHistoryEntry old = new HttpHistoryEntry(ex, "List", 0, 1, false, false, false);
        assertEquals(Map.of(0, old), HttpInlineResult.newestPerRequest(HttpFile.parse(text), List.of(old)));
        String other = text.replace("/users", "/accounts");
        assertTrue(HttpInlineResult.newestPerRequest(HttpFile.parse(other), List.of(old))
                .isEmpty());
        assertTrue(HttpInlineResult.newestPerRequest(List.of(), List.of(old)).isEmpty());
        assertTrue(HttpInlineResult.newestPerRequest(HttpFile.parse(text), List.of())
                .isEmpty());
    }
}
