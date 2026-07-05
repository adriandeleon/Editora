package com.editora.ai;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiErrorsTest {

    @Test
    void connectionRefusedWithNullMessageIsNotTheStringNull() {
        // The JDK HttpClient's ConnectException often carries no message — the old bare
        // e.getMessage() rendered "Not working: null" in the Settings AI page.
        assertEquals("connection refused", AiErrors.describe(new ConnectException()));
        assertEquals("connection refused", AiErrors.describe(new ConnectException("Connection refused")));
    }

    @Test
    void recognizedFailureIsFoundInTheCauseChain() {
        IOException wrapped = new IOException((String) null, new ConnectException());
        assertEquals("connection refused", AiErrors.describe(wrapped));
    }

    @Test
    void connectTimeoutIsDistinguishedFromResponseTimeout() {
        assertEquals("cannot connect (timed out)", AiErrors.describe(new HttpConnectTimeoutException("x")));
        assertEquals("timed out", AiErrors.describe(new HttpTimeoutException("request timed out")));
    }

    @Test
    void unknownHostIncludesTheHostName() {
        assertEquals("unknown host: myserver.local", AiErrors.describe(new UnknownHostException("myserver.local")));
        assertEquals("unknown host", AiErrors.describe(new UnknownHostException()));
    }

    @Test
    void noRouteToHost() {
        assertEquals("no route to host", AiErrors.describe(new NoRouteToHostException()));
    }

    @Test
    void unrecognizedExceptionFallsBackToItsFirstNonBlankMessage() {
        assertEquals("bad endpoint", AiErrors.describe(new IllegalArgumentException("bad endpoint")));
        IOException outer = new IOException("  ", new IOException("inner detail"));
        assertEquals("inner detail", AiErrors.describe(outer));
    }

    @Test
    void messagelessExceptionFallsBackToTheClassName() {
        assertEquals("IOException", AiErrors.describe(new IOException()));
        assertEquals("IOException", AiErrors.describe(new IOException("null")));
    }
}
