package com.editora.ai;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * Turns a request/connection exception into a short human-readable description for the AI status
 * surfaces ("Not working: …"). The JDK {@link java.net.http.HttpClient} often throws exceptions whose
 * own message is null (the detail lives in the cause chain), so a bare {@code e.getMessage()} renders
 * the literal string "null" — this walks the chain, recognizes the common connection failures, and
 * always returns something meaningful. Pure (java.base + java.net.http only).
 */
final class AiErrors {

    private AiErrors() {}

    /** A short description of {@code e}: the recognized failure kind, else the first non-blank message
     *  in the cause chain, else the exception's class name. Never null/blank/"null". */
    static String describe(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException) {
                return "cannot connect (timed out)";
            }
            if (t instanceof HttpTimeoutException) {
                return "timed out";
            }
            if (t instanceof UnknownHostException) {
                String host = t.getMessage();
                return host == null || host.isBlank() ? "unknown host" : "unknown host: " + host;
            }
            if (t instanceof NoRouteToHostException) {
                return "no route to host";
            }
            if (t instanceof ConnectException) {
                return "connection refused";
            }
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && !m.isBlank() && !"null".equals(m)) {
                return m;
            }
        }
        return e.getClass().getSimpleName();
    }
}
