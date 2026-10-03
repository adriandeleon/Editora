package com.editora.http;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * The outcome of a built-in HTTP request: the {@code status} code, response {@code headers}
 * ({@code [name, value]}), the {@code body} text, the {@code contentType}, timing/size, an {@code error}
 * message (non-null only when the request failed to complete — DNS/connect/timeout), and {@code warnings}
 * about the request itself (e.g. a header that couldn't be sent), shown atop the report so a dropped
 * {@code Authorization} isn't a silent, puzzling {@code 401}.
 *
 * <p>{@code rawBody} holds the response bytes exactly as they arrived; it is what {@code >>} writes, so a
 * download (an image, an archive) is byte-identical on disk. {@code body} is only those bytes decoded with the
 * response charset for display and request chaining — empty for a {@link #binary() binary} payload, which has
 * no text form. {@code truncated} is set when the response was longer than the size cap and {@code rawBody}
 * holds just the first part.
 */
public record HttpResult(
        int status,
        List<String[]> headers,
        String body,
        String contentType,
        long elapsedMs,
        long sizeBytes,
        String error,
        List<String> warnings,
        byte[] rawBody,
        boolean truncated) {

    /** How far into the payload {@link #looksBinary} looks for a NUL byte (git's heuristic). */
    private static final int SNIFF_BYTES = 8000;

    /** A text result (tests, errors): the raw bytes are the UTF-8 form of {@code body}. */
    public HttpResult(
            int status,
            List<String[]> headers,
            String body,
            String contentType,
            long elapsedMs,
            long sizeBytes,
            String error,
            List<String> warnings) {
        this(
                status,
                headers,
                body,
                contentType,
                elapsedMs,
                sizeBytes,
                error,
                warnings,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
                false);
    }

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

    /** A completed response built from its raw bytes; the display text is decoded with the response charset. */
    public static HttpResult ofBytes(
            int status,
            List<String[]> headers,
            byte[] raw,
            String contentType,
            long elapsedMs,
            List<String> warnings,
            boolean truncated) {
        byte[] bytes = raw == null ? new byte[0] : raw;
        String text = looksBinary(bytes, contentType) ? "" : new String(bytes, charsetOf(contentType));
        return new HttpResult(
                status, headers, text, contentType, elapsedMs, bytes.length, null, warnings, bytes, truncated);
    }

    /** A request that never produced a response (not sent, connection failure, cancelled). */
    public static HttpResult failure(String error, List<String> warnings) {
        return new HttpResult(0, List.of(), "", "", 0, 0, error, warnings);
    }

    public boolean failed() {
        return error != null;
    }

    /** True for a 2xx/3xx status on a completed request. */
    public boolean ok() {
        return error == null && status >= 200 && status < 400;
    }

    /** True when the payload has no text form (an image, an archive, …), so viewers show a note instead. */
    public boolean binary() {
        return rawBody != null && looksBinary(rawBody, contentType);
    }

    /** The {@code charset=} of a Content-Type, defaulting to UTF-8 when absent or unknown. */
    static Charset charsetOf(String contentType) {
        String name = charsetName(contentType);
        try {
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String charsetName(String contentType) {
        if (contentType == null) {
            return null;
        }
        int at = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (at < 0) {
            return null;
        }
        String v = contentType.substring(at + "charset=".length()).strip();
        int semi = v.indexOf(';');
        if (semi >= 0) {
            v = v.substring(0, semi).strip();
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.isEmpty() ? null : v;
    }

    /**
     * Whether {@code bytes} are not text: no declared charset, not a textual media type, and a NUL byte near
     * the start. A declared charset wins (UTF-16 text is full of NULs).
     */
    static boolean looksBinary(byte[] bytes, String contentType) {
        if (charsetName(contentType) != null) {
            return false;
        }
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") || ct.contains("javascript")) {
            return false;
        }
        int n = Math.min(bytes.length, SNIFF_BYTES);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
