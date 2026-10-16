package com.editora.ui;

import java.util.List;

import com.editora.http.HttpExchange;
import com.editora.http.HttpFixtures;
import com.editora.http.HttpResult;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The response view's status line and the status-bar words — no more "exit 404" (C3, C4, B15). */
class HttpStatusTextTest {

    private static HttpExchange ex(HttpResult r) {
        return HttpFixtures.exchange("GET http://x.test/a", "GET", "http://x.test/a", List.of(), "", r);
    }

    private static HttpResult status(int code) {
        return HttpFixtures.text(code, List.of(), "", "text/plain", 32, 44, null);
    }

    @Test
    void aStatusComesWithItsReasonPhrase() {
        assertEquals("200 OK", HttpStatusText.statusWithReason(200));
        assertEquals("404 Not Found", HttpStatusText.statusWithReason(404));
        assertEquals("503 Service Unavailable", HttpStatusText.statusWithReason(503));
        assertEquals("599", HttpStatusText.statusWithReason(599), "an unknown code stands alone");
    }

    @Test
    void theSummaryHasStatusTimeAndSize() {
        assertEquals(tr("httppanel.summary", "201 Created", "32", "44 B"), HttpStatusText.summary(status(201)));
        assertEquals(tr("httppanel.requestFailed"), HttpStatusText.summary(HttpResult.failure("refused", List.of())));
    }

    @Test
    void eachOutcomeHasItsColour() {
        assertEquals(HttpStatusText.OK, HttpStatusText.styleClass(status(204)));
        assertEquals(HttpStatusText.REDIRECT, HttpStatusText.styleClass(status(304)));
        assertEquals(HttpStatusText.CLIENT_ERROR, HttpStatusText.styleClass(status(404)));
        assertEquals(HttpStatusText.SERVER_ERROR, HttpStatusText.styleClass(status(500)));
        assertEquals(HttpStatusText.FAILED, HttpStatusText.styleClass(HttpResult.failure("x", List.of())));
    }

    @Test
    void theStatusBarNamesTheRequestAndHowItWent() {
        assertEquals(
                tr("status.http.finished", "GET http://x.test/a", "200 OK"), HttpStatusText.finished(ex(status(200))));
        assertEquals(
                tr("status.http.failedStatus", "GET http://x.test/a", "404 Not Found"),
                HttpStatusText.finished(ex(status(404))));
        assertEquals(
                tr("status.http.failedError", "GET http://x.test/a", "Connection refused"),
                HttpStatusText.finished(ex(HttpResult.failure("Connection refused\nmore detail", List.of()))),
                "only the first line of the error");
    }

    @Test
    void aRunAllCountsTheFailuresNotTheRequests() {
        HttpExchange ok = ex(status(200));
        HttpExchange bad = ex(status(404));
        HttpExchange refused = ex(HttpResult.failure("refused", List.of()));
        assertEquals(tr("status.http.allDone", 3), HttpStatusText.finishedAll(List.of(ok, ok, ok)));
        assertEquals(tr("status.http.someFailed", 2, 5), HttpStatusText.finishedAll(List.of(ok, bad, ok, refused, ok)));
        assertEquals(1, HttpStatusText.firstToShow(List.of(ok, bad, refused)), "the first failure is shown");
        assertEquals(0, HttpStatusText.firstToShow(List.of(ok, ok)));
        assertEquals(-1, HttpStatusText.firstToShow(List.of()));
    }

    @Test
    void numbersFollowTheUiLanguage() {
        String before = com.editora.i18n.Messages.current();
        try {
            com.editora.i18n.Messages.init("de");
            assertEquals("1,4", HttpStatusText.seconds(1.44));
            assertEquals("4,9 KB", HttpStatusText.size(5000));
            com.editora.i18n.Messages.init("en");
            assertEquals("1.4", HttpStatusText.seconds(1.44));
            assertEquals("598 B", HttpStatusText.size(598));
        } finally {
            com.editora.i18n.Messages.init(before);
        }
    }

    @Test
    void theStatusBarDoesNotRepeatTheUrlTheEngineAppendsToAnError() {
        HttpExchange refused = ex(HttpResult.failure(
                "Connection refused — is a server listening on x.test?  (http://x.test/a)", List.of()));
        assertEquals(
                tr(
                        "status.http.failedError",
                        "GET http://x.test/a",
                        "Connection refused — is a server listening on x.test?"),
                HttpStatusText.finished(refused));
        assertEquals("other (http://y/)", HttpStatusText.withoutUrl("other (http://y/)", "http://x.test/a"));
    }

    @Test
    void aRunAllHasASummaryAndASingleRunNone() {
        HttpExchange ok = ex(status(200));
        HttpExchange bad = ex(status(500));
        assertEquals(tr("httppanel.runAllFailed", 1L, 3), HttpStatusText.runAllSummary(List.of(ok, bad, ok)));
        assertEquals(tr("httppanel.runAllOk", 2), HttpStatusText.runAllSummary(List.of(ok, ok)));
        assertEquals(null, HttpStatusText.runAllSummary(List.of(ok)));
    }

    @Test
    void repeatedNotesAreShownOnceWithACount() {
        assertEquals(List.of("a (×2)", "b"), HttpStatusText.grouped(List.of("a", "b", "a", " ")));
        assertEquals(List.of(), HttpStatusText.grouped(null));
    }

    // --- round 3 ---------------------------------------------------------------------------------------------

    @Test
    void aRequestThatWasNeverSentSaysNotSentNotFailed() {
        // X13 / Z10: "GET … failed: Not sent: …", and a red "Request failed" for a request that never left.
        HttpResult refused = HttpResult.failure(tr("status.http.secretsRefused", "$env.X"), List.of());
        assertTrue(HttpStatusText.notSent(refused));
        assertFalse(HttpStatusText.notSent(HttpResult.failure("Connection refused", List.of())));
        assertFalse(HttpStatusText.notSent(status(200)));
        assertEquals(tr("httppanel.notSent"), HttpStatusText.summary(refused));
        String bar = HttpStatusText.finished(ex(refused));
        assertEquals(
                tr("status.http.notSentError", "GET http://x.test/a", HttpStatusText.notSentReason(refused.error())),
                bar);
        assertFalse(bar.contains(tr("http.notSent.prefix")), "said once: " + bar);
    }

    @Test
    void aRunAllCountsWhatWasNotSentApartFromWhatFailed() {
        HttpExchange ok = ex(status(200));
        HttpExchange bad = ex(status(500));
        HttpExchange unsent = ex(HttpResult.failure(tr("httppanel.saveFirstToUse", "< ./b.json"), List.of()));
        assertEquals(tr("status.http.someNotSent", 1L, 2), HttpStatusText.finishedAll(List.of(ok, unsent)));
        assertEquals(
                tr("status.http.someFailedNotSent", 1L, 1L, 3), HttpStatusText.finishedAll(List.of(ok, bad, unsent)));
        assertEquals(tr("httppanel.runAllNotSent", 1L, 2), HttpStatusText.runAllSummary(List.of(ok, unsent)));
    }

    @Test
    void aCancelledRunAllSaysSoAndHowFarItGot() {
        // X6: it read "Run All: all 2 succeeded", a complete green run.
        HttpExchange ok = ex(status(200));
        HttpExchange bad = ex(status(404));
        assertEquals(
                tr("httppanel.runAllCancelled", 2, 4, 1L), HttpStatusText.runAllCancelledSummary(List.of(ok, bad), 4));
        assertEquals(tr("httppanel.runAllCancelled", 0, 3, 0L), HttpStatusText.runAllCancelledSummary(List.of(), 3));
    }

    @Test
    void theWarningBadgeShortensToItsCount() {
        assertEquals(tr("httppanel.warnings", 11), HttpStatusText.warningBadge(11, false));
        assertEquals("⚠ 11", HttpStatusText.warningBadge(11, true));
    }
}
