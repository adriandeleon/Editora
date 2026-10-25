package com.editora.lsp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.jspecify.annotations.Nullable;

/**
 * What to tell the user about a request a language server answered with an <em>error</em> — the decision behind
 * {@link LspManager}'s "a broken server must not look like a quiet one" (Maven review C2).
 *
 * <p>Every request path used to map a JSON-RPC error to "nothing to show": empty completion, "No documentation
 * at the caret", no definition, an empty outline — exactly what a server with nothing to say produces, so a
 * server failing every request inside itself (the lemminx-maven bundle without its {@code provided} jars) shipped
 * unnoticed. Now a server-side fault — {@code InternalError} (-32603), {@code InvalidParams} (-32602),
 * {@code MethodNotFound} (-32601) — is logged with the server's {@code data} (where lemminx puts the stack trace),
 * announced once per server and method in the echo area, and counted: {@link #CONSECUTIVE_LIMIT} such errors
 * in a row with no successful answer in between mark the server as failing, which the coordinator treats like
 * a start that failed. A successful answer resets the count; a restart forgets the server.
 *
 * <p>Pure state, keyed by the caller's session key; synchronized because answers arrive on lsp4j's threads.
 */
final class RequestErrorTracker {

    /** Consecutive server faults after which a server counts as failing. */
    static final int CONSECUTIVE_LIMIT = 3;

    /** What to do about one error: say it (first time for this server + method), and/or give the server up. */
    record Verdict(boolean announce, boolean markFailing) {}

    private record State(Set<String> announced, int consecutive) {}

    private final Map<String, State> byServer = new HashMap<>();

    /** Whether {@code code} is a fault inside the server rather than a refusal or a cancellation. Pure. */
    static boolean serverFault(int code) {
        return code == ResponseErrorCode.InternalError.getValue()
                || code == ResponseErrorCode.InvalidParams.getValue()
                || code == ResponseErrorCode.MethodNotFound.getValue();
    }

    /** The {@link ResponseError} behind a failed request's throwable (unwrapping completion wrappers), or null. */
    static @Nullable ResponseError responseError(Throwable error) {
        Throwable t = error;
        for (int i = 0; t != null && i < 8; i++) {
            if (t instanceof ResponseErrorException re) {
                return re.getResponseError();
            }
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }
        return null;
    }

    /**
     * One line that says what went wrong: the server's message and the first line of its {@code data}
     * (lemminx's {@code data} is the stack trace, whose first line names the missing class). Pure.
     */
    static String describe(ResponseError error) {
        if (error == null) {
            return "";
        }
        String message = error.getMessage() == null ? "" : error.getMessage().strip();
        String data = error.getData() == null ? "" : firstLine(String.valueOf(error.getData()));
        if (data.isEmpty()) {
            return message;
        }
        return message.isEmpty() ? data : message + " — " + data;
    }

    /** The first non-blank line of {@code text}, cut to 200 characters. Pure. */
    static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\\R")) {
            String s = line.strip();
            if (!s.isEmpty()) {
                return s.length() > 200 ? s.substring(0, 200) + "…" : s;
            }
        }
        return "";
    }

    /** Records a server fault of {@code method} on {@code serverKey} and says what to do about it. */
    synchronized Verdict onError(String serverKey, String method) {
        State s = byServer.getOrDefault(serverKey, new State(new HashSet<>(), 0));
        boolean announce = s.announced().add(method);
        int consecutive = s.consecutive() + 1;
        byServer.put(serverKey, new State(s.announced(), consecutive));
        return new Verdict(announce, consecutive == CONSECUTIVE_LIMIT);
    }

    /** A request {@code serverKey} answered: the run of faults is over. */
    synchronized void onSuccess(String serverKey) {
        State s = byServer.get(serverKey);
        if (s != null && s.consecutive() != 0) {
            byServer.put(serverKey, new State(s.announced(), 0));
        }
    }

    /** The server was shut down or restarted: a fresh process gets fresh announcements and a fresh count. */
    synchronized void forget(String serverKey) {
        byServer.remove(serverKey);
    }

    /** How many faults in a row {@code serverKey} has had (0 when unknown). */
    synchronized int consecutive(String serverKey) {
        State s = byServer.get(serverKey);
        return s == null ? 0 : s.consecutive();
    }
}
