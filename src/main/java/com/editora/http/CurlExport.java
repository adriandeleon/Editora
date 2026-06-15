package com.editora.http;

import java.util.List;

/**
 * Renders a (resolved) request as a {@code curl} command for the response viewer's "Copy as cURL" action.
 * Single-quotes every value (POSIX-escaping embedded quotes), drops an explicit {@code -X GET}. Pure, so it
 * is unit-tested.
 */
public final class CurlExport {

    private CurlExport() {}

    public static String toCurl(String method, String url, List<String[]> headers, String body) {
        StringBuilder sb = new StringBuilder("curl");
        if (method != null && !method.isEmpty() && !method.equalsIgnoreCase("GET")) {
            sb.append(" -X ").append(method);
        }
        sb.append(" '").append(url == null ? "" : url).append('\'');
        if (headers != null) {
            for (String[] h : headers) {
                sb.append(" \\\n  -H '")
                        .append(h[0])
                        .append(": ")
                        .append(escape(h[1]))
                        .append('\'');
            }
        }
        if (body != null && !body.isBlank()) {
            sb.append(" \\\n  --data '").append(escape(body)).append('\'');
        }
        return sb.toString();
    }

    /** POSIX single-quote escaping: {@code '} → {@code '\''}. */
    private static String escape(String s) {
        return s.replace("'", "'\\''");
    }
}
