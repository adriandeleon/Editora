package com.editora.lsp;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** HTML review A5: a format request that failed is told apart from one that changed nothing. */
class FormatFailureTest {

    @Test
    void aTimeoutALostServerAndARefusalAreToldApart() {
        assertEquals(
                FormatFailure.Kind.NO_ANSWER,
                FormatFailure.of(new CancellationException()).kind());
        assertEquals(
                FormatFailure.Kind.NO_ANSWER,
                FormatFailure.of(new CompletionException(new java.util.concurrent.TimeoutException()))
                        .kind());
        FormatFailure lost = FormatFailure.of(new IllegalStateException("language server not available"));
        assertEquals(FormatFailure.Kind.LOST, lost.kind());
        assertNull(lost.detail());
        FormatFailure refused = FormatFailure.of(new CompletionException(new ResponseErrorException(
                new ResponseError(ResponseErrorCode.InternalError, "Unexpected token", null))));
        assertEquals(FormatFailure.Kind.REFUSED, refused.kind());
        assertEquals("Unexpected token", refused.detail());
    }
}
