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

    /** {@code details} may be null for a request that was never built (it is then {@link Details#NONE}). */
    public HttpExchange {
        details = details == null ? Details.NONE : details;
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
     * @param secretValues    the values the run read from the process environment or {@code .env}, to be masked in
     *                        whatever is kept of it; in memory only, never saved
     */
    public record Details(
            String bodyFile,
            List<FormPart> formParts,
            String digestUser,
            String digestPass,
            boolean followRedirects,
            String httpVersion,
            String finalUrl,
            boolean noLog,
            List<String> secretValues) {

        public static final Details NONE = new Details(null, List.of(), null, null, true, null, null, false);

        public Details {
            formParts = formParts == null ? List.of() : List.copyOf(formParts);
            secretValues = secretValues == null ? List.of() : List.copyOf(secretValues);
        }

        /** Details with no secret values (an entry restored from disk, a request that read none). */
        public Details(
                String bodyFile,
                List<FormPart> formParts,
                String digestUser,
                String digestPass,
                boolean followRedirects,
                String httpVersion,
                String finalUrl,
                boolean noLog) {
            this(bodyFile, formParts, digestUser, digestPass, followRedirects, httpVersion, finalUrl, noLog, List.of());
        }

        /** A copy that records where the response finally came from. */
        public Details withFinalUrl(String url) {
            return new Details(
                    bodyFile,
                    formParts,
                    digestUser,
                    digestPass,
                    followRedirects,
                    httpVersion,
                    url,
                    noLog,
                    secretValues);
        }

        /**
         * A copy that carries {@code values}: what the run read from {@code $processEnv}/{@code $env}/{@code $dotenv}
         * ({@link HttpVars.Unresolved#secretValues()}), so whatever keeps the exchange (the saved history, the
         * History label) can mask them. Held in memory only: never saved, and left out of {@link #toString()}.
         */
        public Details withSecretValues(List<String> values) {
            return new Details(
                    bodyFile, formParts, digestUser, digestPass, followRedirects, httpVersion, finalUrl, noLog, values);
        }

        @Override
        public String toString() {
            return "Details[bodyFile=" + bodyFile + ", formParts=" + formParts.size() + ", digestUser=" + digestUser
                    + ", followRedirects=" + followRedirects + ", httpVersion=" + httpVersion + ", finalUrl="
                    + finalUrl + ", noLog=" + noLog + ", secretValues=" + secretValues.size() + "]";
        }
    }

    /**
     * One {@code multipart/form-data} field: its {@code name} and either an inline {@code value} or a {@code file}
     * (absolute path) uploaded under {@code filename}; {@code contentType} when the part declared one.
     */
    /**
     * Whether {@code requestBody} is the one-line summary the response view shows for a body that was streamed
     * ({@code (multipart/form-data, N bytes)}, {@code (body from ./x, 2 MB)}) rather than the text sent. Without
     * {@link Details} saying how it was sent, such an entry cannot be turned back into a command. Pure.
     */
    public static boolean isBodyPlaceholder(String requestBody) {
        if (requestBody == null || !requestBody.startsWith("(") || !requestBody.endsWith(")")) {
            return false;
        }
        return requestBody.startsWith("(multipart/form-data, ")
                || requestBody.startsWith("(body from ")
                || requestBody.startsWith("(binary body from ");
    }

    public record FormPart(String name, String value, String file, String filename, String contentType) {}
}
