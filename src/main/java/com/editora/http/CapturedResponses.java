package com.editora.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.editora.i18n.Messages.tr;

/**
 * The session that backs request chaining: each named request's {@link HttpResult} is captured after it
 * runs, so a later request can reference it with {@code {{name.response.body.$.path}}},
 * {@code {{name.response.body.*}}}, {@code {{name.response.headers.H}}} or {@code {{name.response.status}}}.
 * One session lives as long as its {@code .http} buffer (every single run and Run All add to it), so running
 * "login" and then the request that uses its token works; it persists nothing. A failed request is captured
 * too, so a reference to it is reported as unresolved rather than silently empty. A <em>cancelled</em> run is not
 * captured: cancelling a re-run of "login" keeps the token of the run before it.
 *
 * <p>Only what a reference can read is kept — the status, the headers and the body text up to {@link
 * #MAX_BODY_CHARS} — never the raw bytes: the session lives as long as the buffer, outside the response
 * history's memory budget, so a file of large titled downloads must not pin every one of them. A reference
 * into a body that was too large to keep says so.
 *
 * <p>The session also holds the <b>session variables</b> a response sets — a {@code # @capture} line or a handler's
 * {@code client.global.set} — which later requests read as {@code {{name}}} ({@link HttpVars#scope}: above the
 * environment, below the file's own {@code @name = value} lines). They are cleared with the responses, on the same
 * environment rule.
 *
 * <p>Thread-safe: requests run on worker threads while the editor may clear the session.
 */
public final class CapturedResponses {

    private static final String MARKER = ".response.";

    /** The longest body kept for references, in characters (about 8 MB of memory). */
    public static final int MAX_BODY_CHARS = 4 * 1024 * 1024;

    /** What a reference can read of one response: {@code body} is null when it was too large to keep. */
    private record Captured(int status, List<String[]> headers, String body, long bodyChars, boolean failed) {}

    private final Map<String, Captured> byName = new LinkedHashMap<>();

    /** The session variables, in the order they were first set. */
    private final Map<String, String> globals = new LinkedHashMap<>();

    /** The environment the captured responses were made in; see {@link #resetUnlessEnvironment}. */
    private String environment;

    /**
     * Records a completed (or failed) request's result under its name; a no-op for a blank name, a null result
     * and a cancelled run ({@link HttpClientService#CANCELLED}), which leaves the previous capture in place.
     */
    public synchronized void put(String name, HttpResult result) {
        if (name == null || name.isBlank() || result == null) {
            return;
        }
        if (result.failed() && HttpClientService.CANCELLED.equals(result.error())) {
            return; // the user stopped it: what an earlier run returned is still the best answer
        }
        String body = result.body() == null ? "" : result.body();
        boolean keep = body.length() <= MAX_BODY_CHARS;
        List<String[]> headers = new ArrayList<>();
        if (result.headers() != null) {
            for (String[] h : result.headers()) {
                if (h != null && h.length >= 2) {
                    headers.add(new String[] {h[0], h[1]});
                }
            }
        }
        byName.put(
                name,
                new Captured(
                        result.status(), List.copyOf(headers), keep ? body : null, body.length(), result.failed()));
    }

    /** Forgets every captured response and session variable. */
    public synchronized void clear() {
        byName.clear();
        globals.clear();
    }

    /** Sets the session variable {@code name} to {@code value}; a null {@code value} removes it. */
    public synchronized void setGlobal(String name, String value) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (value == null) {
            globals.remove(name);
        } else {
            globals.put(name, value);
        }
    }

    /** Removes every session variable (a handler's {@code client.global.clearAll()}). */
    public synchronized void clearGlobals() {
        globals.clear();
    }

    /** A copy of the session variables, in the order they were first set. */
    public synchronized Map<String, String> globals() {
        return new LinkedHashMap<>(globals);
    }

    /**
     * Clears the session when {@code env} differs from the environment its responses were captured in (a
     * token from "dev" must not be sent to "prod"), then remembers {@code env}.
     */
    public synchronized void resetUnlessEnvironment(String env) {
        String e = env == null ? "" : env;
        if (environment != null && !environment.equals(e)) {
            byName.clear();
            globals.clear();
        }
        environment = e;
    }

    /** Whether a response is captured under {@code name}. */
    public synchronized boolean has(String name) {
        return byName.containsKey(name);
    }

    /** True for a {@code name.response.<…>} reference (so {@link HttpVars} routes it here). */
    public static boolean isResponseRef(String reference) {
        return reference != null && reference.contains(MARKER);
    }

    /** Resolves a {@code name.response.<…>} reference against the captured responses, or {@code ""}. */
    public String resolve(String reference) {
        String v = lookup(reference, null);
        return v == null ? "" : v;
    }

    /**
     * Resolves a {@code name.response.<…>} reference, or returns {@code null} — the request has not run in
     * this session, it failed, or the path/header is not in its response — with a short reason in
     * {@code reason[0]} when {@code reason} is non-null.
     */
    public String lookup(String reference, String[] reason) {
        if (reference == null) {
            return miss(reason, tr("http.unresolved.reason.undefined"));
        }
        int marker = reference.indexOf(MARKER);
        if (marker < 0) {
            return miss(reason, tr("http.unresolved.reason.undefined"));
        }
        String name = reference.substring(0, marker);
        Captured r;
        synchronized (this) {
            r = byName.get(name);
        }
        if (r == null) {
            return miss(reason, tr("http.unresolved.reason.noResponse", name));
        }
        if (r.failed()) {
            return miss(reason, tr("http.unresolved.reason.failed", name));
        }
        String rest = reference.substring(marker + MARKER.length());
        String found = null;
        if (rest.equals("body") || rest.startsWith("body.")) {
            String path = rest.equals("body") ? "" : rest.substring("body.".length());
            if (r.body() == null) {
                return miss(reason, tr("http.unresolved.reason.tooLarge", name, r.bodyChars(), MAX_BODY_CHARS));
            }
            String body = r.body();
            String p = path.strip();
            if (p.isEmpty() || p.equals("*") || p.equals("$")) {
                found = body; // the whole body, as received (JSON or not)
            } else {
                found = JsonPath.find(body, path);
            }
        } else if (rest.startsWith("headers.")) {
            found = headerValue(r, rest.substring("headers.".length()));
        } else if (rest.equals("status")) {
            found = String.valueOf(r.status());
        }
        return found == null ? miss(reason, tr("http.unresolved.reason.notFound", name)) : found;
    }

    private static String miss(String[] reason, String text) {
        if (reason != null && reason.length > 0) {
            reason[0] = text;
        }
        return null;
    }

    private static String headerValue(Captured r, String name) {
        for (String[] h : r.headers()) {
            if (h[0].equalsIgnoreCase(name)) {
                return h[1];
            }
        }
        return null;
    }
}
