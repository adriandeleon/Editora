package com.editora.http;

import java.util.List;

/**
 * The outcome of a built-in HTTP request: the {@code status} code, response {@code headers}
 * ({@code [name, value]}), the {@code body} text, the {@code contentType}, timing/size, an {@code error}
 * message (non-null only when the request failed to complete — DNS/connect/timeout), and {@code warnings}
 * about the request itself (e.g. a header that couldn't be sent), shown atop the report so a dropped
 * {@code Authorization} isn't a silent, puzzling {@code 401}.
 */
public record HttpResult(
        int status,
        List<String[]> headers,
        String body,
        String contentType,
        long elapsedMs,
        long sizeBytes,
        String error,
        List<String> warnings) {

    /** A result with no request-side warnings (the common case). */
    public HttpResult(
            int status,
            List<String[]> headers,
            String body,
            String contentType,
            long elapsedMs,
            long sizeBytes,
            String error) {
        this(status, headers, body, contentType, elapsedMs, sizeBytes, error, List.of());
    }

    public boolean failed() {
        return error != null;
    }

    /** True for a 2xx/3xx status on a completed request. */
    public boolean ok() {
        return error == null && status >= 200 && status < 400;
    }
}
