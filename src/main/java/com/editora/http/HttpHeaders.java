package com.editora.http;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * Prepares request headers for the JDK {@link HttpRequest} client, which is strict about what a header may
 * contain. Two problems this closes, both of which used to fail <em>silently</em> — the header was skipped
 * and the request went out without it, so a dropped {@code Authorization} produced a puzzling {@code 401}
 * with the {@code .http} file looking correct:
 *
 * <ol>
 *   <li><b>Whitespace/newline from a paste.</b> A token pasted from a terminal or copied out of a wrapped
 *       JSON blob often carries a trailing {@code \r\n} or spaces. The JDK rejects a value with a line break
 *       outright. Leading/trailing whitespace is stripped here — HTTP servers strip it anyway, so this is
 *       spec-safe — which fixes the common case so the header is actually sent.</li>
 *   <li><b>Genuinely un-sendable headers.</b> A restricted name ({@code Host}, {@code Content-Length}, …),
 *       an illegal name, or a value with an <em>embedded</em> control character (a header-injection attempt)
 *       can't be sent. These are <em>surfaced</em> as warnings rather than dropped in silence.</li>
 * </ol>
 */
public final class HttpHeaders {

    private static final URI PROBE = URI.create("http://localhost/");

    private HttpHeaders() {}

    /** The headers that can be sent (names/values whitespace-trimmed), plus a human-readable warning for
     *  each header that had to be dropped. */
    public record Partition(List<String[]> sendable, List<String> warnings) {}

    /**
     * Splits {@code headers} ({@code [name, value]} pairs) into those the JDK client will accept — trimmed —
     * and warnings for those it won't. Validity is decided by the real {@link HttpRequest.Builder}, so it
     * matches exactly what the client would accept.
     */
    public static Partition partition(List<String[]> headers) {
        List<String[]> sendable = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (headers == null) {
            return new Partition(sendable, warnings);
        }
        for (String[] h : headers) {
            String name = h[0] == null ? "" : h[0].strip();
            String value = h[1] == null ? "" : h[1].strip();
            try {
                HttpRequest.newBuilder(PROBE).header(name, value); // the client's own validation
                sendable.add(new String[] {name, value});
            } catch (IllegalArgumentException rejected) {
                warnings.add("Header \"" + name + "\" was not sent: " + reason(rejected));
            }
        }
        return new Partition(sendable, warnings);
    }

    /** A short, user-facing reason from the JDK's rejection message. */
    private static String reason(IllegalArgumentException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (m.contains("restricted header name")) {
            return "the HTTP client does not allow setting this header";
        }
        if (m.contains("invalid header name")) {
            return "the name contains an illegal character";
        }
        if (m.contains("invalid header value")) {
            return "the value contains a control character or line break";
        }
        return "the header is not valid";
    }
}
