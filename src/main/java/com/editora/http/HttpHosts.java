package com.editora.http;

import java.net.URI;

import org.jspecify.annotations.Nullable;

/**
 * The host of a request URL, read one way for the whole package: what the trust prompt names and the send check
 * compares ({@link #authority}), whether a {@code Host} header only repeats the URL's, the host a redirect led to,
 * and the host an error message names ({@link #host}, {@link #hostPort}). Pure.
 */
final class HttpHosts {

    private HttpHosts() {}

    /**
     * {@code host[:port]} of {@code url} as written, userinfo dropped — read leniently, so a {@code {{reference}}}
     * kept in a dry-resolved host stays as is. A URL without {@code scheme://} is returned stripped (it names no host
     * of its own).
     */
    static String authority(String url) {
        String u = url == null ? "" : url.strip();
        int scheme = u.indexOf("://");
        if (scheme < 0) {
            return u;
        }
        int start = scheme + 3;
        int end = start;
        while (end < u.length() && "/?#".indexOf(u.charAt(end)) < 0) {
            end++;
        }
        String authority = u.substring(start, end);
        int at = authority.lastIndexOf('@');
        return at < 0 ? authority : authority.substring(at + 1);
    }

    /** {@code host[:port]} of {@code u}, the port only when the URI gives one. */
    static String authority(URI u) {
        return u.getHost() + (u.getPort() >= 0 ? ":" + u.getPort() : "");
    }

    /** The host of {@code url} for a message, or the URL itself when it cannot be parsed or has none. */
    static String host(String url) {
        URI u = uri(url);
        return u == null || u.getHost() == null ? String.valueOf(url) : u.getHost();
    }

    /**
     * {@code host:port} of {@code url} with the scheme's default port filled in (443 for https, else 80), or the URL
     * itself when it cannot be parsed or has no host.
     */
    static String hostPort(String url) {
        URI u = uri(url);
        if (u == null || u.getHost() == null) {
            return String.valueOf(url);
        }
        return u.getHost() + ":" + port(u);
    }

    /** {@code u}'s port, else its scheme's default (443 for https, else 80). */
    static int port(URI u) {
        return u.getPort() >= 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    /** {@code url} parsed, or null. */
    static @Nullable URI uri(String url) {
        try {
            return url == null ? null : URI.create(url);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
