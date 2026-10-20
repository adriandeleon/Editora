package com.editora.http;

import java.net.http.HttpClient;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Which HTTP version a request is sent with, following the JetBrains client: with no version on the request
 * line, {@code http://} goes out as plain HTTP/1.1 and {@code https://} negotiates HTTP/2 (falling back to
 * HTTP/1.1 when the server does not offer it). {@code HTTP/1.1} and {@code HTTP/2} on the line are honoured.
 *
 * <p>Without this the JDK client's default preference for HTTP/2 sends every cleartext request with
 * {@code Connection: Upgrade, HTTP2-Settings} and {@code Upgrade: h2c}, which some servers and proxies
 * mishandle, and a request written {@code HTTP/1.1} could not be kept on HTTP/1.1. Pure, so it is unit-tested.
 */
public final class HttpVersions {

    private HttpVersions() {}

    /** The version to send with and, when the request asked for something the JDK cannot do, a warning. */
    public record Choice(
            HttpClient.Version version, @Nullable String warning) {}

    /**
     * The version for a request whose line said {@code requested} ({@code null} when it said nothing) to
     * {@code url}.
     */
    public static Choice choose(@Nullable String requested, String url) {
        String r = requested == null
                ? ""
                : requested.strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        boolean https = url != null && url.regionMatches(true, 0, "https:", 0, 6);
        if (requested == null || r.isEmpty()) {
            return new Choice(https ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1, null);
        }
        if (r.equals("HTTP/1.1")) {
            return new Choice(HttpClient.Version.HTTP_1_1, null);
        }
        if (r.equals("HTTP/2") || r.equals("HTTP/2.0")) {
            return new Choice(HttpClient.Version.HTTP_2, null);
        }
        if (r.startsWith("HTTP/2")) {
            // "HTTP/2 (Prior Knowledge)": the JDK client has no prior-knowledge mode
            return new Choice(HttpClient.Version.HTTP_2, tr("status.http.warn.priorKnowledge"));
        }
        if (r.equals("HTTP/1.0")) {
            return new Choice(HttpClient.Version.HTTP_1_1, tr("status.http.warn.http10"));
        }
        return new Choice(
                https ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1,
                tr("status.http.warn.unknownVersion", requested.strip()));
    }

    /** The display form of a response's version: {@code HTTP/1.1} or {@code HTTP/2}. */
    public static String label(HttpClient.Version v) {
        return v == HttpClient.Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1";
    }
}
