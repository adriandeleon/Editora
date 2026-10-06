package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three outcomes {@link LatestRequests} chooses between for a repeated request. */
class LatestRequestsTest {

    private final LatestRequests latest = new LatestRequests();
    private final List<CompletableFuture<String>> sent = new ArrayList<>();
    private final List<String> delivered = new ArrayList<>();

    private boolean issue(String uri, String kind, Object stamp, String tag, boolean latestHandlerWins) {
        return latest.issue(
                uri,
                kind,
                stamp,
                latestHandlerWins,
                () -> {
                    var future = new CompletableFuture<String>();
                    sent.add(future);
                    return future;
                },
                (result, error) -> delivered.add(tag + ":" + (error == null ? result : "error")));
    }

    @Test
    void aRequestForANewerStampCancelsTheOlderOneAndSilencesIt() {
        assertTrue(issue("u", "symbols", 1, "first", true));
        assertTrue(issue("u", "symbols", 2, "second", true));

        assertTrue(sent.get(0).isCancelled(), "the superseded request is cancelled, which tells the server");
        assertFalse(sent.get(1).isDone());
        assertTrue(delivered.isEmpty(), "a cancelled request must not report an (empty) result");

        sent.get(1).complete("outline");
        assertEquals(List.of("second:outline"), delivered);
        assertEquals(0, latest.inFlight());
    }

    @Test
    void anIdenticalRequestIsNotSentAgainWhileOneIsUnanswered() {
        assertTrue(issue("u", "symbols", 7, "first", true));
        assertFalse(issue("u", "symbols", 7, "second", true), "same stamp: the answer on its way serves both");
        assertEquals(1, sent.size());
        assertEquals(1, latest.inFlight());

        sent.get(0).complete("outline");
        assertEquals(List.of("second:outline"), delivered, "the later caller's guards judge the reply, once");
    }

    @Test
    void theRunningHandlerIsKeptWhenItCarriesItsOwnState() {
        assertTrue(issue("u", "diagnostic", 7, "first", false));
        assertFalse(issue("u", "diagnostic", 7, "second", false));

        sent.get(0).complete("report");
        assertEquals(List.of("first:report"), delivered);
    }

    @Test
    void anAnsweredRequestIsSentAgain() {
        issue("u", "symbols", 7, "first", true);
        sent.get(0).complete("a");

        assertTrue(issue("u", "symbols", 7, "second", true), "nothing is unanswered any more");
        assertEquals(2, sent.size());
    }

    @Test
    void kindsAndDocumentsAreIndependent() {
        issue("u", "symbols", 1, "symbols", true);
        issue("u", "folding", 1, "folding", true);
        issue("v", "symbols", 1, "other", true);

        assertEquals(3, latest.inFlight());
        assertTrue(sent.stream().noneMatch(CompletableFuture::isCancelled));
    }

    @Test
    void aFailedRequestStillReachesItsHandler() {
        issue("u", "symbols", 1, "first", true);
        sent.get(0).completeExceptionally(new IllegalStateException("server gone"));

        assertEquals(List.of("first:error"), delivered, "only a superseded request is silenced");
    }

    @Test
    void cancellingADocumentCancelsEveryKindOfIt() {
        issue("u", "symbols", 1, "symbols", true);
        issue("u", "folding", 1, "folding", true);
        issue("v", "symbols", 1, "other", true);

        latest.cancelAll("u");

        assertTrue(sent.get(0).isCancelled());
        assertTrue(sent.get(1).isCancelled());
        assertFalse(sent.get(2).isDone(), "another document's request is left alone");
        assertTrue(delivered.isEmpty());
        assertEquals(1, latest.inFlight());
        assertTrue(issue("u", "symbols", 1, "again", true), "after a cancel the same stamp is sent afresh");
    }

    @Test
    void anAlreadyAnsweredSendDeliversAtOnce() {
        boolean sentNow = latest.issue(
                "u",
                "symbols",
                1,
                true,
                () -> CompletableFuture.completedFuture("ready"),
                (result, error) -> delivered.add(result));

        assertTrue(sentNow);
        assertEquals(List.of("ready"), delivered);
        assertEquals(0, latest.inFlight());
    }
}
