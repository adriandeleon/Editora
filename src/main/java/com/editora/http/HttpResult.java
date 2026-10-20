package com.editora.http;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

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
 * holds just the first part. {@code written} lists what the request's {@code >>} redirects saved to disk, one
 * line per file — the response view shows them, so a file that was created or replaced is never a surprise.
 * {@code tests} are what the request's {@code @assert} checks and response handlers found ({@link HttpTests#NONE}
 * when it has none).
 */
public record HttpResult(
        int status,
        List<String[]> headers,
        String body,
        String contentType,
        long elapsedMs,
        long sizeBytes,
        @Nullable String error,
        List<String> warnings,
        byte[] rawBody,
        boolean truncated,
        List<String> written,
        @Nullable String protocol,
        HttpTests tests) {

    public HttpResult {
        written = written == null ? List.of() : List.copyOf(written);
        tests = tests == null ? HttpTests.NONE : tests;
    }

    /** This result with {@code tests} and {@code moreWarnings} added after its own warnings. */
    public HttpResult withTests(HttpTests tests, List<String> moreWarnings) {
        List<String> all = warnings;
        if (moreWarnings != null && !moreWarnings.isEmpty()) {
            all = new java.util.ArrayList<>(warnings == null ? List.of() : warnings);
            all.addAll(moreWarnings);
        }
        return new HttpResult(
                status,
                headers,
                body,
                contentType,
                elapsedMs,
                sizeBytes,
                error,
                all,
                rawBody,
                truncated,
                written,
                protocol,
                tests);
    }

    /** This result with {@code warnings} in place of its own. */
    public HttpResult withWarnings(List<String> warnings) {
        return new HttpResult(
                status,
                headers,
                body,
                contentType,
                elapsedMs,
                sizeBytes,
                error,
                warnings,
                rawBody,
                truncated,
                written,
                protocol,
                tests);
    }

    /** How far into the payload {@link #looksBinary} looks for a NUL byte (git's heuristic). */
    private static final int SNIFF_BYTES = 8000;

    /**
     * A completed response built from its raw bytes; the display text is decoded with the response charset.
     * {@code written} lists what its {@code >>} redirects saved; {@code protocol} is the version it arrived over
     * ({@code HTTP/1.1}, {@code HTTP/2}), or null when unknown.
     */
    public static HttpResult ofBytes(
            int status,
            List<String[]> headers,
            byte[] raw,
            String contentType,
            long elapsedMs,
            List<String> warnings,
            boolean truncated,
            List<String> written,
            String protocol) {
        byte[] bytes = raw == null ? new byte[0] : raw;
        String text = looksBinary(bytes, contentType) ? "" : new String(bytes, charsetOf(contentType, bytes));
        return new HttpResult(
                status,
                headers,
                text,
                contentType,
                elapsedMs,
                bytes.length,
                null,
                warnings,
                bytes,
                truncated,
                written,
                protocol,
                HttpTests.NONE);
    }

    /** A request that never produced a response (not sent, connection failure, cancelled). */
    public static HttpResult failure(String error, List<String> warnings) {
        return new HttpResult(
                0, List.of(), "", "", 0, 0, error, warnings, new byte[0], false, List.of(), null, HttpTests.NONE);
    }

    /**
     * {@link #error()} as shown to the user: the marker of a cancelled run ({@link HttpClientService#CANCELLED},
     * compared as is) reads in the UI language.
     */
    public @Nullable String shownError() {
        return HttpClientService.CANCELLED.equals(error) ? tr("http.request.cancelled") : error;
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

    /** The charset this response's text was decoded with (see {@link #charsetOf(String, byte[])}). */
    public String bodyCharset() {
        return charsetOf(contentType, rawBody).name();
    }

    /** The {@code charset=} of a Content-Type, defaulting to UTF-8 when absent or unknown. */
    static Charset charsetOf(String contentType) {
        return forName(charsetName(contentType));
    }

    /**
     * The charset to decode {@code bytes} with: the Content-Type's {@code charset=}; else, for HTML, a
     * {@code <meta charset>} / {@code <meta http-equiv content="…charset=…">} near the start, and for XML the
     * prolog's {@code encoding=}; else UTF-8.
     */
    static Charset charsetOf(String contentType, byte[] bytes) {
        String declared = charsetName(contentType);
        if (declared != null) {
            return forName(declared);
        }
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (bytes != null && (ct.contains("html") || ct.contains("xml"))) {
            // The prolog and <meta> are ASCII in every charset a server would declare this way.
            String head =
                    new String(bytes, 0, Math.min(bytes.length, SNIFF_CHARSET_BYTES), StandardCharsets.ISO_8859_1);
            java.util.regex.Matcher m = (ct.contains("html") ? META_CHARSET : XML_ENCODING).matcher(head);
            if (m.find()) {
                return forName(m.group(1));
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Charset forName(@Nullable String name) {
        try {
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** How far into an HTML/XML body the charset declaration is looked for (HTML requires it in the first 1024). */
    private static final int SNIFF_CHARSET_BYTES = 2048;

    private static final java.util.regex.Pattern META_CHARSET =
            java.util.regex.Pattern.compile("(?i)<meta[^>]+charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9._:-]+)");

    private static final java.util.regex.Pattern XML_ENCODING =
            java.util.regex.Pattern.compile("^\\s*<\\?xml[^>]*?encoding\\s*=\\s*[\"']([A-Za-z0-9._:-]+)[\"']");

    /** Media types that are never text, whatever their bytes look like. */
    private static boolean binaryType(String ct) {
        return ct.startsWith("image/") && !ct.contains("svg")
                || ct.startsWith("audio/")
                || ct.startsWith("video/")
                || ct.startsWith("font/")
                || ct.startsWith("application/octet-stream")
                || ct.startsWith("application/zip")
                || ct.startsWith("application/gzip")
                || ct.startsWith("application/x-gzip")
                || ct.startsWith("application/x-tar")
                || ct.startsWith("application/x-7z")
                || ct.startsWith("application/pdf")
                || ct.startsWith("application/protobuf")
                || ct.startsWith("application/x-protobuf")
                || ct.startsWith("application/grpc")
                || ct.startsWith("application/msgpack")
                || ct.startsWith("application/cbor")
                || ct.startsWith("application/wasm")
                || ct.startsWith("application/vnd.ms-excel")
                || ct.startsWith("application/vnd.openxmlformats");
    }

    private static @Nullable String charsetName(@Nullable String contentType) {
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
     * Whether {@code bytes} are not text: no declared charset and not a textual media type, and either a media
     * type that is never text (an image, {@code application/octet-stream}, a PDF, …) or a NUL byte near the
     * start. A declared charset wins (UTF-16 text is full of NULs).
     */
    static boolean looksBinary(byte[] bytes, @Nullable String contentType) {
        if (charsetName(contentType) != null) {
            return false;
        }
        String ct =
                contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).strip();
        if (ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") || ct.contains("javascript")) {
            return false;
        }
        if (binaryType(ct)) {
            return true;
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
