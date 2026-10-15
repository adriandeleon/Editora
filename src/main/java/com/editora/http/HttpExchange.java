package com.editora.http;

import java.util.List;

/**
 * One executed request/response pair: the {@code label} (method + URL) and the fully <b>resolved</b> request
 * ({@code method}/{@code url}/{@code headers}/{@code requestBody}, with {@code {{vars}}} already substituted)
 * alongside its {@link HttpResult}. Produced by {@link HttpClientService} so the response viewer can show a
 * history entry, render the body by content type, and build a "Copy as cURL" command from resolved values.
 *
 * <p>{@code requestBody} is the body as text, for display. What actually went out beyond that text — a file
 * uploaded as is, a multipart form, Digest credentials, whether redirects were followed — is in
 * {@link #details()}, so the cURL export can reproduce the request rather than its display string.
 */
public record HttpExchange(
        String label,
        String method,
        String url,
        List<String[]> headers,
        String requestBody,
        HttpResult result,
        Details details) {

    public HttpExchange {
        details = details == null ? Details.NONE : details;
    }

    /** An exchange without request details (tests, a request that was never built). */
    public HttpExchange(
            String label, String method, String url, List<String[]> headers, String requestBody, HttpResult result) {
        this(label, method, url, headers, requestBody, result, Details.NONE);
    }

    /**
     * How the request went out, beyond its resolved text.
     *
     * @param bodyFile        the absolute path of a {@code < file} body uploaded byte for byte, else null
     * @param formParts       the fields of a {@code multipart/form-data} body (inline values substituted)
     * @param digestUser      the user of an {@code Authorization: Digest user pass} shorthand, else null
     * @param digestPass      its password
     * @param followRedirects false under {@code @no-redirect}
     * @param httpVersion     the protocol version the request asked for ({@code HTTP/1.1}, {@code HTTP/2}), or null
     * @param finalUrl        the URL the response finally came from when redirects were followed, else null
     * @param noLog           the request carries {@code @no-log}: keep it out of any saved history
     */
    public record Details(
            String bodyFile,
            List<FormPart> formParts,
            String digestUser,
            String digestPass,
            boolean followRedirects,
            String httpVersion,
            String finalUrl,
            boolean noLog) {

        public static final Details NONE = new Details(null, List.of(), null, null, true, null, null, false);

        public Details {
            formParts = formParts == null ? List.of() : List.copyOf(formParts);
        }

        /** A copy that records where the response finally came from. */
        public Details withFinalUrl(String url) {
            return new Details(bodyFile, formParts, digestUser, digestPass, followRedirects, httpVersion, url, noLog);
        }
    }

    /**
     * One {@code multipart/form-data} field: its {@code name} and either an inline {@code value} or a {@code file}
     * (absolute path) uploaded under {@code filename}; {@code contentType} when the part declared one.
     */
    public record FormPart(String name, String value, String file, String filename, String contentType) {}
}
