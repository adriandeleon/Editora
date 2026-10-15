package com.editora.http;

import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Default URL auto-encoding (mirroring IntelliJ): after variable substitution, percent-encode the characters
 * that are illegal in a URI (space, {@code " < > { } | \ ^ `}, a {@code %} that does not start an escape, and
 * every non-ASCII character as its UTF-8 bytes — emoji included) while leaving the URL's structure and any
 * existing {@code %xx} escapes intact — so a {@code GET https://x/a b?q={{v}}} works and an already-encoded URL
 * is untouched. A non-ASCII host name becomes its IDN (punycode) form. The {@code # @no-auto-encoding}
 * directive disables it. Also completes a target written without a scheme ({@code example.com/api} is
 * {@code http://}, as in the JetBrains spec). Pure, so it is unit-tested.
 */
public final class UrlEncoding {

    private static final String ILLEGAL = " \"<>{}|\\^`";

    private static final Pattern HAS_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://.*", Pattern.DOTALL);

    private UrlEncoding() {}

    /** Percent-encodes the illegal characters in {@code url}, preserving existing {@code %xx} escapes. */
    public static String encodeIllegal(String url) {
        if (url == null || url.isEmpty()) {
            return url == null ? "" : url;
        }
        String s = idnHost(url);
        StringBuilder sb = new StringBuilder(s.length() + 8);
        int n = s.length();
        for (int i = 0; i < n; ) {
            int cp = s.codePointAt(i);
            int len = Character.charCount(cp);
            if (cp == '%') {
                if (i + 2 < n && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                    sb.append(s, i, i + 3); // keep an existing escape
                    i += 3;
                    continue;
                }
                sb.append("%25"); // a bare "100%" is not an escape
            } else if (cp > 127 || ILLEGAL.indexOf(cp) >= 0) {
                for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                    sb.append('%')
                            .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                            .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            } else {
                sb.appendCodePoint(cp);
            }
            i += len;
        }
        return sb.toString();
    }

    /**
     * {@code url} with a scheme: unchanged when it has one; an origin-form target ({@code /api}) joined to the
     * request's {@code Host} header when there is one; anything else ({@code example.com/api},
     * {@code localhost:8080/x}) gets {@code http://}, the JetBrains default.
     */
    public static String withScheme(String url, String hostHeader) {
        if (url == null || url.isBlank() || HAS_SCHEME.matcher(url).matches() || url.equals("*")) {
            return url;
        }
        if (url.startsWith("/")) {
            return hostHeader == null || hostHeader.isBlank() ? url : "http://" + hostHeader.strip() + url;
        }
        return "http://" + url;
    }

    /** {@code url} with a non-ASCII host converted to its IDN form ({@code münchen.de} → {@code xn--mnchen-3ya.de}). */
    private static String idnHost(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        int start = scheme + 3;
        int end = start;
        while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) {
            end++;
        }
        String authority = url.substring(start, end);
        int at = authority.lastIndexOf('@');
        int hostStart = at + 1;
        int hostEnd = authority.length();
        if (!authority.startsWith("[", hostStart)) {
            int colon = authority.indexOf(':', hostStart);
            if (colon >= 0) {
                hostEnd = colon;
            }
        }
        String host = authority.substring(hostStart, hostEnd);
        if (host.chars().allMatch(c -> c < 128)) {
            return url;
        }
        try {
            String ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
            return url.substring(0, start + hostStart) + ascii + url.substring(start + hostEnd);
        } catch (IllegalArgumentException notIdn) {
            return url; // left for the percent-encoding (and the client's own error)
        }
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
