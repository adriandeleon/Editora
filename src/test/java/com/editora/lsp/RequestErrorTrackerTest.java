package com.editora.lsp;

import java.util.concurrent.CompletionException;

import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RequestErrorTracker}: which errors are the server's fault, what is said once, and when a server is given up. */
class RequestErrorTrackerTest {

    @Test
    void onlyFaultsInsideTheServerCount() {
        assertTrue(RequestErrorTracker.serverFault(ResponseErrorCode.InternalError.getValue()));
        assertTrue(RequestErrorTracker.serverFault(ResponseErrorCode.InvalidParams.getValue()));
        assertTrue(RequestErrorTracker.serverFault(ResponseErrorCode.MethodNotFound.getValue()));
        assertFalse(RequestErrorTracker.serverFault(ResponseErrorCode.RequestCancelled.getValue()));
        assertFalse(RequestErrorTracker.serverFault(ResponseErrorCode.ContentModified.getValue()));
        assertFalse(RequestErrorTracker.serverFault(ResponseErrorCode.ServerNotInitialized.getValue()));
        assertFalse(RequestErrorTracker.serverFault(-32000), "a server-defined code is a refusal, not a fault");
    }

    @Test
    void theResponseErrorIsUnwrappedFromCompletionWrappers() {
        ResponseError error = new ResponseError(ResponseErrorCode.InternalError, "Internal error.", "boom");
        ResponseErrorException thrown = new ResponseErrorException(error);
        assertSame(error, RequestErrorTracker.responseError(thrown));
        assertSame(error, RequestErrorTracker.responseError(new CompletionException(thrown)));
        assertSame(error, RequestErrorTracker.responseError(new RuntimeException(new CompletionException(thrown))));
        assertNull(RequestErrorTracker.responseError(new IllegalStateException("transport")));
        assertNull(RequestErrorTracker.responseError(null));
    }

    @Test
    void theSummaryIsTheMessageAndTheFirstLineOfTheData() {
        ResponseError lemminx = new ResponseError(
                ResponseErrorCode.InternalError,
                "Internal error.",
                "java.lang.NoClassDefFoundError: com/google/common/cache/CacheBuilder\n"
                        + "\tat org.eclipse.lemminx.uriresolver.CacheResourcesManager.<init>(CacheResourcesManager.java:144)\n");
        assertEquals(
                "Internal error. — java.lang.NoClassDefFoundError: com/google/common/cache/CacheBuilder",
                RequestErrorTracker.describe(lemminx));
        assertEquals(
                "Method not found",
                RequestErrorTracker.describe(
                        new ResponseError(ResponseErrorCode.MethodNotFound, "Method not found", null)));
        assertEquals("", RequestErrorTracker.describe(null));
        assertEquals("x".repeat(200) + "…", RequestErrorTracker.firstLine("\n\n" + "x".repeat(300)));
    }

    @Test
    void eachMethodIsAnnouncedOnceAndTheThirdFaultInARowMarksTheServer() {
        var t = new RequestErrorTracker();
        assertEquals(new RequestErrorTracker.Verdict(true, false), t.onError("maven-pom@/p", "hover"));
        assertEquals(new RequestErrorTracker.Verdict(false, false), t.onError("maven-pom@/p", "hover"));
        assertEquals(new RequestErrorTracker.Verdict(true, true), t.onError("maven-pom@/p", "completion"));
        assertEquals(3, t.consecutive("maven-pom@/p"));
        assertEquals(
                new RequestErrorTracker.Verdict(false, false), t.onError("maven-pom@/p", "completion"), "said once");
        assertEquals(
                new RequestErrorTracker.Verdict(true, false),
                t.onError("xml@/p", "hover"),
                "another server counts on its own");
    }

    @Test
    void aSuccessEndsTheRunAndARestartForgetsTheServer() {
        var t = new RequestErrorTracker();
        t.onError("s", "hover");
        t.onError("s", "completion");
        t.onSuccess("s");
        assertEquals(0, t.consecutive("s"));
        assertEquals(
                new RequestErrorTracker.Verdict(false, false), t.onError("s", "hover"), "still said once per method");
        assertEquals(new RequestErrorTracker.Verdict(false, false), t.onError("s", "hover"));
        assertEquals(new RequestErrorTracker.Verdict(true, true), t.onError("s", "definition"), "three in a row again");

        t.forget("s");
        assertEquals(0, t.consecutive("s"));
        assertEquals(
                new RequestErrorTracker.Verdict(true, false),
                t.onError("s", "hover"),
                "a fresh process is announced afresh");
        t.onSuccess("never-seen"); // harmless
    }
}
