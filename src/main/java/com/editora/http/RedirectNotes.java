package com.editora.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.editora.i18n.Messages.tr;

/** Warnings about where redirects led. Pure apart from the message catalog. */
final class RedirectNotes {

    /** Request headers that are not specific to one site, so their following a redirect is unremarkable. */
    private static final Set<String> GENERIC = Set.of(
            "accept",
            "accept-language",
            "accept-encoding",
            "cache-control",
            "content-type",
            "user-agent",
            "pragma",
            "if-none-match",
            "if-modified-since",
            "origin",
            "referer",
            // stripped by the JDK client on a cross-host redirect
            "authorization",
            "cookie",
            "proxy-authorization");

    private RedirectNotes() {}

    static List<String> warnings(
            String url,
            URI finalUri,
            List<URI> redirects,
            int status,
            java.net.http.HttpHeaders responseHeaders,
            List<String[]> requestHeaders,
            boolean follow) {
        List<String> out = new ArrayList<>();
        URI start;
        try {
            start = URI.create(url);
        } catch (RuntimeException e) {
            return out;
        }
        if (!redirects.isEmpty() && !sameOrigin(start, finalUri)) {
            List<String> carried = new ArrayList<>();
            for (String[] h : requestHeaders) {
                if (!GENERIC.contains(h[0].toLowerCase(Locale.ROOT)) && !carried.contains(h[0])) {
                    carried.add(h[0]);
                }
            }
            out.add(
                    carried.isEmpty()
                            ? tr("status.http.warn.redirectedHost", HttpHosts.authority(finalUri))
                            : tr(
                                    "status.http.warn.redirectedHostHeaders",
                                    HttpHosts.authority(finalUri),
                                    String.join(", ", carried)));
        }
        String location = responseHeaders.firstValue("Location").orElse(null);
        if (follow && status >= 300 && status < 400 && location != null) {
            // The client makes at most HttpClientService.REDIRECT_LIMIT exchanges, so it follows
            // HttpClientService.REDIRECT_LIMIT - 1 redirects and
            // hands back the next 3xx: the chain counts the followed ones, plus this one.
            if (redirects.size() >= HttpClientService.REDIRECT_LIMIT - 1) {
                out.add(tr("status.http.warn.redirectLimit", redirects.size() + 1));
            } else if ("https".equalsIgnoreCase(finalUri.getScheme())
                    && location.regionMatches(true, 0, "http:", 0, 5)) {
                out.add(tr("status.http.warn.redirectDowngrade", location));
            }
        }
        return out;
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null
                && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost())
                && HttpHosts.port(a) == HttpHosts.port(b);
    }
}
