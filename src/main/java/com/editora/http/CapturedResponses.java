package com.editora.http;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.editora.i18n.Messages.tr;

/**
 * The session that backs request chaining: each named request's {@link HttpResult} is captured after it
 * runs, so a later request can reference it with {@code {{name.response.body.$.path}}},
 * {@code {{name.response.body.*}}}, {@code {{name.response.headers.H}}} or {@code {{name.response.status}}}.
 * One session lives as long as its {@code .http} buffer (every single run and Run All add to it), so running
 * "login" and then the request that uses its token works; it persists nothing. A failed request is captured
 * too, so a reference to it is reported as unresolved rather than silently empty.
 *
 * <p>Thread-safe: requests run on worker threads while the editor may clear the session.
 */
public final class CapturedResponses {

    private static final String MARKER = ".response.";

    private final Map<String, HttpResult> byName = new LinkedHashMap<>();

    /** The environment the captured responses were made in; see {@link #resetUnlessEnvironment}. */
    private String environment;

    /** Records a completed (or failed) request's result under its name (no-op for a blank name / null result). */
    public synchronized void put(String name, HttpResult result) {
        if (name != null && !name.isBlank() && result != null) {
            byName.put(name, result);
        }
    }

    /** Forgets every captured response. */
    public synchronized void clear() {
        byName.clear();
    }

    /**
     * Clears the session when {@code env} differs from the environment its responses were captured in (a
     * token from "dev" must not be sent to "prod"), then remembers {@code env}.
     */
    public synchronized void resetUnlessEnvironment(String env) {
        String e = env == null ? "" : env;
        if (environment != null && !environment.equals(e)) {
            byName.clear();
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
        HttpResult r;
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
            String body = r.body() == null ? "" : r.body();
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

    private static String headerValue(HttpResult r, String name) {
        for (String[] h : r.headers()) {
            if (h[0].equalsIgnoreCase(name)) {
                return h[1];
            }
        }
        return null;
    }
}
