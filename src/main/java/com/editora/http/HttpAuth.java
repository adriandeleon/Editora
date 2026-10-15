package com.editora.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Basic-auth convenience: builds a {@code Basic <base64>} header and rewrites the IntelliJ-style shorthand
 * (which the JDK client would otherwise send verbatim) into a proper base64 header just before sending:
 * {@code Authorization: Basic user pass} — the first word is the user, the rest of the line the password, so a
 * password may contain spaces — and {@code Basic user:pass}, which cannot be base64 because base64 has no
 * {@code :}. Pure, so it is unit-tested.
 */
public final class HttpAuth {

    private HttpAuth() {}

    /** {@code "Basic " + base64(user:pass)}. */
    public static String basic(String user, String pass) {
        String token = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    /** Returns {@code headers} with any {@code Authorization: Basic} shorthand value base64-encoded. */
    public static List<String[]> normalizeHeaders(List<String[]> headers) {
        List<String[]> out = new ArrayList<>();
        for (String[] h : headers) {
            if (h[0] != null && h[0].equalsIgnoreCase("Authorization") && h[1] != null) {
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
        String rest = value.substring(6).strip();
        int space = indexOfWhitespace(rest);
        if (space > 0) {
            // "Basic user pass word": the user, then everything after it is the password
            return basic(rest.substring(0, space), rest.substring(space).strip());
        }
        int colon = rest.indexOf(':');
        if (colon > 0) {
            return basic(rest.substring(0, colon), rest.substring(colon + 1)); // "Basic user:pass"
        }
        return value; // a single base64 token is already encoded
    }

    static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
