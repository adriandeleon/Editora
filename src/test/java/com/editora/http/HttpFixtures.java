package com.editora.http;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The one place tests build {@link HttpResult}s and {@link HttpExchange}s, so the records keep a single canonical
 * constructor (plus {@link HttpResult#ofBytes} and {@link HttpResult#failure}) instead of compatibility overloads.
 */
public final class HttpFixtures {

    private HttpFixtures() {}

    /** A completed text result: the raw bytes are the UTF-8 form of {@code body}; nothing saved, protocol unknown. */
    public static HttpResult text(
            int status,
            List<String[]> headers,
            String body,
            String contentType,
            long elapsedMs,
            long sizeBytes,
            String error,
            List<String> warnings) {
        return new HttpResult(
                status,
                headers,
                body,
                contentType,
                elapsedMs,
                sizeBytes,
                error,
                warnings,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
                false,
                List.of(),
                null,
                HttpTests.NONE);
    }

    /** {@link #text} with no warnings. */
    public static HttpResult text(
            int status,
            List<String[]> headers,
            String body,
            String contentType,
            long elapsedMs,
            long sizeBytes,
            String error) {
        return text(status, headers, body, contentType, elapsedMs, sizeBytes, error, List.of());
    }

    /** A result decoded from {@code raw}, as a run builds it; nothing saved, protocol unknown. */
    public static HttpResult bytes(
            int status,
            List<String[]> headers,
            byte[] raw,
            String contentType,
            long elapsedMs,
            List<String> warnings,
            boolean truncated) {
        return HttpResult.ofBytes(status, headers, raw, contentType, elapsedMs, warnings, truncated, List.of(), null);
    }

    /** {@code text} with {@code {{vars}}} filled in from a plain map, no chaining, no folder, misses dropped. */
    public static String substitute(String text, java.util.Map<String, String> vars, java.time.ZonedDateTime now) {
        return HttpVars.substitute(text, HttpVars.Scope.of(vars), now, null, null, null);
    }

    /** The wire bytes of a multipart form, as {@link Multipart#assemble} streams it. */
    public static byte[] form(
            List<Multipart.Part> parts,
            String boundary,
            java.nio.file.Path baseDir,
            java.util.function.Function<String, String> subst) {
        try (Multipart.Body body = Multipart.assemble(parts, boundary, baseDir, subst, new java.util.ArrayList<>())) {
            return body.toBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** An exchange without request details. */
    public static HttpExchange exchange(
            String label, String method, String url, List<String[]> headers, String requestBody, HttpResult result) {
        return new HttpExchange(label, method, url, headers, requestBody, result, null);
    }
}
