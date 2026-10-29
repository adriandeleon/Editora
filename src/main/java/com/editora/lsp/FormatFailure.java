package com.editora.lsp;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;

/**
 * Why a format request delivered no edits although it was sent: the server did not answer in time, it went away
 * (a restart or a crash took the request with it), or it answered with an error. Before, each of these read as
 * "Already formatted" (HTML review A5).
 */
public record FormatFailure(Kind kind, @Nullable String detail) {

    /** What happened to the request. */
    public enum Kind {
        /** The request timed out: the server is busy or hung. */
        NO_ANSWER,
        /** The session ended before it answered (Restart Language Servers, a crash, the window closing). */
        LOST,
        /** The server answered with an error; {@code detail} is what it said. */
        REFUSED
    }

    /** The failure {@code error} (a request future's) stands for. Pure. */
    public static FormatFailure of(Throwable error) {
        Throwable t = error;
        while (t instanceof CompletionException && t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        if (t instanceof CancellationException || t instanceof TimeoutException) {
            return new FormatFailure(Kind.NO_ANSWER, null); // a request is cancelled when its time runs out
        }
        String said = LanguageServerSession.responseErrorMessage(t);
        return said != null ? new FormatFailure(Kind.REFUSED, said) : new FormatFailure(Kind.LOST, null);
    }
}
