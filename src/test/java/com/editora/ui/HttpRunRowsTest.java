package com.editora.ui;

import java.util.List;

import com.editora.http.HttpClientService;
import com.editora.http.HttpExchange;
import com.editora.http.HttpFixtures;
import com.editora.http.HttpResult;
import com.editora.http.HttpTests;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Run All results list's rows and title (U10). */
class HttpRunRowsTest {

    @BeforeAll
    static void english() {
        com.editora.i18n.Messages.init("en"); // the "Not sent:" prefix is read from the catalog
    }

    private static HttpResult status(int code, long ms) {
        return HttpFixtures.text(code, List.of(), "", "text/plain", ms, 44, null);
    }

    private static HttpResult tested(int code, boolean... passed) {
        List<HttpTests.Test> tests = new java.util.ArrayList<>();
        for (int i = 0; i < passed.length; i++) {
            tests.add(new HttpTests.Test("t" + i, passed[i], passed[i] ? "" : "no"));
        }
        return status(code, 5).withTests(new HttpTests(tests, List.of(), null), List.of());
    }

    private static HttpHistoryEntry entry(String name, String method, String url, HttpResult r, int index) {
        HttpExchange ex = HttpFixtures.exchange(method + " " + url, method, url, List.of(), "", r);
        return new HttpHistoryEntry(ex, name, index, 1000, false, false, false, false, 2000, null, null);
    }

    private static HttpHistoryEntry entry(String name, HttpResult r) {
        return entry(name, "GET", "http://127.0.0.1:18776/users/42", r, 0);
    }

    private static HttpResult notSent() {
        return HttpResult.failure(tr("httppanel.saveFirstToUse", "< ./body.json"), List.of());
    }

    @Test
    void aRowHasTheCodeTheNameWithItsRequestTheTimeAndNoTests() {
        HttpRunRows.Row row = HttpRunRows.row(entry("getUser", status(200, 83)));
        assertEquals("200", row.status());
        assertEquals(HttpStatusText.OK, row.statusStyle());
        assertEquals("getUser", row.name());
        assertEquals("GET /users/42", row.detail(), "the scheme and host are left out");
        assertEquals("83 ms", row.time());
        assertEquals("—", row.tests());
        assertEquals(HttpRunRows.Tests.NONE, row.testsStyle());
        assertFalse(row.failure());
        assertEquals("200, getUser, GET /users/42, 83 ms", row.spoken());
    }

    @Test
    void anUnnamedRequestIsNamedByItsMethodAndPathShownOnce() {
        HttpRunRows.Row row =
                HttpRunRows.row(entry(null, "POST", "https://api.test/login?next=%2F", status(201, 9), 3));
        assertEquals("POST /login?next=%2F", row.name());
        assertNull(row.detail());
    }

    @Test
    void theStatusPillSaysFailedNotSentOrCancelledWhenThereIsNoCode() {
        HttpRunRows.Row refused = HttpRunRows.row(entry("nobody", HttpResult.failure("Connection refused", List.of())));
        assertEquals(tr("httppanel.runList.failedPill"), refused.status());
        assertEquals(HttpStatusText.FAILED, refused.statusStyle());
        assertEquals("—", refused.time(), "a request that failed has no time");
        assertTrue(refused.failure());

        HttpRunRows.Row unsent = HttpRunRows.row(entry("body", notSent()));
        assertEquals(tr("httppanel.runList.notSentPill"), unsent.status());
        assertTrue(unsent.failure());

        HttpRunRows.Row cancelled =
                HttpRunRows.row(entry("slow", HttpResult.failure(HttpClientService.CANCELLED, List.of())));
        assertEquals(tr("httppanel.runList.cancelledPill"), cancelled.status());
        assertEquals(HttpRunRows.CANCELLED, cancelled.statusStyle());
    }

    @Test
    void eachCodeKeepsItsColour() {
        assertEquals(
                HttpStatusText.CLIENT_ERROR,
                HttpRunRows.row(entry("m", status(404, 1))).statusStyle());
        assertEquals(
                HttpStatusText.SERVER_ERROR,
                HttpRunRows.row(entry("b", status(500, 1))).statusStyle());
        assertEquals(
                HttpStatusText.REDIRECT,
                HttpRunRows.row(entry("r", status(304, 1))).statusStyle());
        assertTrue(HttpRunRows.row(entry("m", status(404, 1))).failure(), "a 4xx did not succeed");
        assertFalse(HttpRunRows.row(entry("r", status(304, 1))).failure());
    }

    @Test
    void testsSayPassedOfTotalWhenOneFailedAndTheCountWhenAllPassed() {
        HttpRunRows.Row failed = HttpRunRows.row(entry("getUser", tested(200, true, true, false)));
        assertEquals("✗ 2/3", failed.tests());
        assertEquals(HttpRunRows.Tests.FAILED, failed.testsStyle());
        assertTrue(failed.failure(), "a 200 whose tests failed is a failure for the filter");
        assertTrue(failed.spoken().endsWith(tr("httppanel.runList.testsSpoken", "✗ 2/3")), failed.spoken());

        HttpRunRows.Row passed = HttpRunRows.row(entry("login", tested(200, true, true, true)));
        assertEquals("✓ 3", passed.tests());
        assertEquals(HttpRunRows.Tests.PASSED, passed.testsStyle());
        assertFalse(passed.failure());

        HttpResult stopped = status(200, 5).withTests(new HttpTests(List.of(), List.of(), "Error: boom"), List.of());
        HttpRunRows.Row script = HttpRunRows.row(entry("s", stopped));
        assertEquals("✗", script.tests(), "a handler that stopped with no test run");
        assertEquals(HttpRunRows.Tests.FAILED, script.testsStyle());
    }

    @Test
    void onlyFailuresKeepsTheOnesThatDidNotSucceedInRunOrder() {
        List<HttpRunRows.Row> rows = HttpRunRows.rows(List.of(
                entry("a", status(200, 1)),
                entry("b", tested(200, false)),
                entry("c", status(404, 1)),
                entry("d", status(201, 1)),
                entry("e", HttpResult.failure("refused", List.of()))));
        assertEquals(rows, HttpRunRows.filter(rows, false));
        assertEquals(
                List.of("b", "c", "e"),
                HttpRunRows.filter(rows, true).stream()
                        .map(HttpRunRows.Row::name)
                        .toList());
        assertEquals(2, HttpRunRows.initial(rows), "the first that did not succeed is selected first");
        List<HttpRunRows.Row> green =
                HttpRunRows.rows(List.of(entry("a", status(200, 1)), entry("b", tested(200, false))));
        assertEquals(1, HttpRunRows.initial(green), "else the first whose tests failed");
        assertEquals(List.of(), HttpRunRows.filter(HttpRunRows.rows(List.of(entry("a", status(200, 1)))), true));
    }

    @Test
    void theTitleCountsFailuresAndSaysHowLongTheRunTook() {
        List<HttpHistoryEntry> run = List.of(
                entry("a", status(200, 1)),
                entry("b", tested(200, true, false)),
                entry("c", status(404, 1)),
                entry("d", status(500, 1)));
        HttpRunRows.Title t = HttpRunRows.title(run, 0, 4100);
        assertEquals(tr("httppanel.runList.failed", 2L, 4), t.pill());
        assertEquals("2 of 4 failed", t.pill());
        assertEquals(HttpRunRows.Outcome.FAILED, t.outcome());
        assertEquals(tr("httppanel.runAllTestsFailed", 1L), t.testsFailed());
        assertEquals("4.1 s", t.time());
        assertEquals("Run All: 2 of 4 failed · tests failed in 1 request · 4.1 s", t.spoken());

        HttpRunRows.Title ok =
                HttpRunRows.title(List.of(entry("a", status(200, 1)), entry("b", status(204, 1))), 0, -1);
        assertEquals("All 2 succeeded", ok.pill());
        assertEquals(HttpRunRows.Outcome.OK, ok.outcome());
        assertEquals("", ok.testsFailed());
        assertEquals("", ok.time(), "no time when it is not known");
        assertEquals(tr("httppanel.runAllOk", 2), ok.spoken());

        HttpRunRows.Title onlyTests =
                HttpRunRows.title(List.of(entry("a", status(200, 1)), entry("b", tested(200, false))), 0, -1);
        assertEquals(HttpRunRows.Outcome.OK, onlyTests.outcome(), "the pill's colour follows its words");
        assertEquals(tr("httppanel.runAllTestsFailed", 1L), onlyTests.testsFailed(), "the tests say it, in red");
    }

    @Test
    void notSentRequestsAreCountedApart() {
        HttpRunRows.Title unsent = HttpRunRows.title(List.of(entry("a", status(200, 1)), entry("b", notSent())), 0, -1);
        assertEquals(tr("httppanel.runList.notSent", 1L, 2), unsent.pill());
        assertEquals(HttpRunRows.Outcome.PARTIAL, unsent.outcome());
        HttpRunRows.Title both = HttpRunRows.title(
                List.of(entry("a", status(500, 1)), entry("b", notSent()), entry("c", status(200, 1))), 0, -1);
        assertEquals(tr("httppanel.runList.failedNotSent", 1L, 1L), both.pill());
        assertEquals(HttpRunRows.Outcome.FAILED, both.outcome());
    }

    @Test
    void aCancelledRunSaysHowFarItGot() {
        HttpRunRows.Title t =
                HttpRunRows.title(List.of(entry("a", status(200, 1)), entry("b", status(200, 1))), 4, 1200);
        assertEquals("Cancelled · 2 of 4 ran", t.pill());
        assertEquals(HttpRunRows.Outcome.PARTIAL, t.outcome());
        assertEquals(tr("httppanel.runAllCancelled", 2, 4, 0L) + " · 1.2 s", t.spoken());
        HttpRunRows.Title failed = HttpRunRows.title(List.of(entry("a", status(404, 1))), 3, -1);
        assertEquals("Cancelled · 1 of 3 ran, 1 failed", failed.pill());
        assertEquals(HttpRunRows.Outcome.FAILED, failed.outcome());
    }

    @Test
    void thePathOfASentUrl() {
        assertEquals("/users/42", HttpRunRows.pathOf("http://127.0.0.1:18776/users/42"));
        assertEquals("/", HttpRunRows.pathOf("https://example.com"));
        assertEquals("/?q=1", HttpRunRows.pathOf("https://example.com?q=1"));
        assertEquals("/a#top", HttpRunRows.pathOf("https://example.com/a#top"));
        assertEquals("example.com/x", HttpRunRows.pathOf("example.com/x"), "no scheme: as is");
        assertEquals("", HttpRunRows.pathOf(null));
        assertEquals("GET /x", HttpRunRows.requestLine(null, "http://h/x"));
    }

    @Test
    void theSameRunIsRecognisedInItsBodylessHistoryCopy() {
        HttpHistoryEntry e = entry("a", status(200, 1));
        HttpHistoryEntry copy = e.withoutBody();
        assertTrue(copy.bodyDropped());
        assertFalse(e.equals(copy), "a record tells them apart");
        assertTrue(HttpRunRows.sameRun(e, copy));
        assertFalse(HttpRunRows.sameRun(e, entry("a", "GET", "http://127.0.0.1:18776/users/42", status(200, 1), 1)));
        assertFalse(HttpRunRows.sameRun(e, null));
    }
}
