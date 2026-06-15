package com.editora.http;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The in-memory, per-run session that backs request chaining: each named request's {@link HttpResult} is
 * captured after it runs, so a later request in the same run can reference it with
 * {@code {{name.response.body.$.path}}}, {@code {{name.response.body.*}}}, {@code {{name.response.headers.H}}}
 * or {@code {{name.response.status}}}. Holds no data across runs and persists nothing. An unknown reference
 * resolves to {@code ""} (the unknown-variable contract). Pure, so it is unit-tested.
 */
public final class CapturedResponses {

    private static final String MARKER = ".response.";

    private final Map<String, HttpResult> byName = new LinkedHashMap<>();

    /** Records a completed request's result under its name (no-op for a blank name / null result). */
    public void put(String name, HttpResult result) {
        if (name != null && !name.isBlank() && result != null) {
            byName.put(name, result);
        }
    }

    /** True for a {@code name.response.<…>} reference (so {@link HttpVars} routes it here). */
    public static boolean isResponseRef(String reference) {
        return reference != null && reference.contains(MARKER);
    }

    /** Resolves a {@code name.response.<…>} reference against the captured responses, or {@code ""}. */
    public String resolve(String reference) {
        if (reference == null) {
            return "";
        }
        int marker = reference.indexOf(MARKER);
        if (marker < 0) {
            return "";
        }
        HttpResult r = byName.get(reference.substring(0, marker));
        if (r == null) {
            return "";
        }
        String rest = reference.substring(marker + MARKER.length());
        if (rest.equals("body") || rest.startsWith("body.")) {
            String path = rest.equals("body") ? "" : rest.substring("body.".length());
            return JsonPath.eval(r.body() == null ? "" : r.body(), path);
        }
        if (rest.startsWith("headers.")) {
            return headerValue(r, rest.substring("headers.".length()));
        }
        if (rest.equals("status")) {
            return String.valueOf(r.status());
        }
        return "";
    }

    private static String headerValue(HttpResult r, String name) {
        for (String[] h : r.headers()) {
            if (h[0].equalsIgnoreCase(name)) {
                return h[1];
            }
        }
        return "";
    }
}
