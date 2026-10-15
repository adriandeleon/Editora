package com.editora.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The request target when a request names its host in a {@code Host:} header. The JetBrains format (like HTTP
 * itself) accepts the origin form:
 *
 * <pre>
 * GET /api/items
 * Host: example.com
 * </pre>
 *
 * which used to fail with "URI with undefined scheme" while the {@code Host} header was dropped as restricted.
 * Here the URL is built from the header ({@code http://}, or {@code https://} for port 443), and the header is
 * then left to the client. A {@code Host} that only repeats the URL's own authority is dropped as redundant; a
 * different one (testing a virtual host by IP) is kept and sent as written. Pure, so it is unit-tested.
 */
public final class HttpTargets {

    private HttpTargets() {}

    /** The URL to send to, and the headers to send with it. */
    public record Target(String url, List<String[]> headers) {}

    /** Resolves {@code url} (already substituted) against a {@code Host} header among {@code headers}. */
    public static Target resolve(String url, List<String[]> headers) {
        String u = url == null ? "" : url.strip();
        int hostAt = indexOfHost(headers);
        if (hostAt < 0) {
            return new Target(u, headers);
        }
        String host = headers.get(hostAt)[1] == null ? "" : headers.get(hostAt)[1].strip();
        if (host.isEmpty()) {
            return new Target(u, headers);
        }
        if (u.startsWith("/") || u.equals("*")) {
            String scheme = host.endsWith(":443") ? "https://" : "http://";
            String authority = host.endsWith(":443") ? host.substring(0, host.length() - 4) : host;
            return new Target(scheme + authority + (u.equals("*") ? "/" : u), without(headers, hostAt));
        }
        if (host.equalsIgnoreCase(authority(u))) {
            return new Target(u, without(headers, hostAt)); // the client sends exactly this Host anyway
        }
        return new Target(u, headers);
    }

    private static int indexOfHost(List<String[]> headers) {
        if (headers == null) {
            return -1;
        }
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i)[0] != null && headers.get(i)[0].strip().equalsIgnoreCase("Host")) {
                return i;
            }
        }
        return -1;
    }

    private static List<String[]> without(List<String[]> headers, int index) {
        List<String[]> out = new ArrayList<>(headers);
        out.remove(index);
        return out;
    }

    /** The {@code host[:port]} of an absolute URL, as the client would send it in {@code Host}; "" if none. */
    private static String authority(String url) {
        try {
            String a = URI.create(url).getRawAuthority();
            if (a == null) {
                return "";
            }
            int at = a.lastIndexOf('@');
            return at >= 0 ? a.substring(at + 1) : a;
        } catch (RuntimeException e) {
            return "";
        }
    }
}
