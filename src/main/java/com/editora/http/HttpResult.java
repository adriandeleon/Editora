package com.editora.http;

import java.util.List;

/**
 * The outcome of a built-in HTTP request: the {@code status} code, response {@code headers}
 * ({@code [name, value]}), the {@code body} text, the {@code contentType}, timing/size, and an
 * {@code error} message (non-null only when the request failed to complete — DNS/connect/timeout).
 */
public record HttpResult(
        int status,
        List<String[]> headers,
        String body,
        String contentType,
        long elapsedMs,
        long sizeBytes,
        String error) {

    public boolean failed() {
        return error != null;
    }

    /** True for a 2xx/3xx status on a completed request. */
    public boolean ok() {
        return error == null && status >= 200 && status < 400;
    }
}
