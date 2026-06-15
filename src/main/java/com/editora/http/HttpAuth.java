package com.editora.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Basic-auth convenience: builds a {@code Basic <base64>} header and rewrites the IntelliJ-style
 * {@code Authorization: Basic user pass} two-token form (which the JDK client would otherwise send
 * verbatim) into a proper base64 header just before sending. Pure, so it is unit-tested.
 */
public final class HttpAuth {

    private HttpAuth() {}

    /** {@code "Basic " + base64(user:pass)}. */
    public static String basic(String user, String pass) {
        String token = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    /** Returns {@code headers} with any {@code Authorization: Basic user pass} value base64-encoded. */
    public static List<String[]> normalizeHeaders(List<String[]> headers) {
        List<String[]> out = new ArrayList<>();
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase("Authorization")) {
                out.add(new String[] {h[0], normalizeBasic(h[1].strip())});
            } else {
                out.add(h);
            }
        }
        return out;
    }

    private static String normalizeBasic(String value) {
        if (!value.regionMatches(true, 0, "Basic ", 0, 6)) {
            return value;
        }
        String[] parts = value.substring(6).strip().split("\\s+");
        // Two whitespace-separated tokens = the user/pass shorthand; a single token is already base64.
        return parts.length == 2 ? basic(parts[0], parts[1]) : value;
    }
}
