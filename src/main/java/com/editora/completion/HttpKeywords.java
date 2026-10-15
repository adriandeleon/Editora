package com.editora.completion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Autocomplete words for {@code .http}/{@code .rest} buffers: the request methods and the common request
 * header names (and the {@code Content-Type}/{@code Accept} values typed most). Offered only for a token
 * that is a single identifier run — {@code Cont} → {@code Content-Type} — because the completion replaces
 * just that run: a header half typed past its hyphen is left alone rather than doubled. Pure; tested.
 */
public final class HttpKeywords {

    private HttpKeywords() {}

    private static final List<String> WORDS = List.of(
            // methods (JetBrains format, plus WebDAV/QUERY which it accepts too)
            "GET",
            "POST",
            "PUT",
            "PATCH",
            "DELETE",
            "HEAD",
            "OPTIONS",
            "TRACE",
            "CONNECT",
            // request headers
            "Accept",
            "Accept-Encoding",
            "Accept-Language",
            "Authorization",
            "Cache-Control",
            "Connection",
            "Content-Disposition",
            "Content-Encoding",
            "Content-Length",
            "Content-Type",
            "Cookie",
            "Host",
            "If-Match",
            "If-Modified-Since",
            "If-None-Match",
            "Origin",
            "Referer",
            "User-Agent",
            "X-Requested-With",
            // values
            "application/json",
            "application/xml",
            "application/x-www-form-urlencoded",
            "multipart/form-data",
            "text/plain",
            "text/html",
            "Basic",
            "Bearer",
            "Digest");

    /**
     * Words starting with {@code prefix} (case-insensitive), excluding an exact match, capped at {@code max} —
     * only when {@code token} (the whole non-whitespace token) is the identifier run itself.
     */
    public static List<String> startingWith(String prefix, String token, int max) {
        if (prefix == null || prefix.isEmpty() || (token != null && !token.isEmpty() && !token.equals(prefix))) {
            return List.of();
        }
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String w : WORDS) {
            String lower = w.toLowerCase(Locale.ROOT);
            if (lower.startsWith(p) && !lower.equals(p)) {
                out.add(w);
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return out;
    }
}
