package com.editora.http;

import java.nio.charset.StandardCharsets;

/**
 * Default URL auto-encoding (mirroring IntelliJ): after variable substitution, percent-encode the characters
 * that are illegal in a URI (space, {@code " < > { } | \ ^ `} and non-ASCII bytes) while leaving the URL's
 * structure and any existing {@code %xx} escapes intact — so a {@code GET https://x/a b?q={{v}}} works and an
 * already-encoded URL is untouched. The {@code # @no-auto-encoding} directive disables it. Pure, so it is
 * unit-tested.
 */
public final class UrlEncoding {

    private static final String ILLEGAL = " \"<>{}|\\^`";

    private UrlEncoding() {}

    /** Percent-encodes the illegal characters in {@code url}, preserving existing {@code %xx} escapes. */
    public static String encodeIllegal(String url) {
        if (url == null || url.isEmpty()) {
            return url == null ? "" : url;
        }
        StringBuilder sb = new StringBuilder(url.length() + 8);
        int n = url.length();
        for (int i = 0; i < n; i++) {
            char c = url.charAt(i);
            if (c == '%' && i + 2 < n && isHex(url.charAt(i + 1)) && isHex(url.charAt(i + 2))) {
                sb.append(url, i, i + 3); // keep an existing escape
                i += 2;
            } else if (c > 127 || ILLEGAL.indexOf(c) >= 0) {
                for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                    sb.append('%')
                            .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                            .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
