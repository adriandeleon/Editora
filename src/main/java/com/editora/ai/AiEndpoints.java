package com.editora.ai;

import java.net.URI;

/**
 * Endpoint-safety checks for the AI credential: would attaching the API key to a given endpoint put it on
 * the wire in cleartext? Plain-http <em>loopback</em> is the intended local-inference path
 * ({@link AiProvider#OPENAI}'s default is LM Studio's {@code http://127.0.0.1:1234}; Ollama/vLLM/llama.cpp
 * are the same shape), so the rule is not "require https" — it is "require https <em>or</em> a loopback
 * host". Pure (java.base only), so it is unit-tested without a network.
 */
public final class AiEndpoints {

    private AiEndpoints() {}

    /**
     * True when a credential attached to {@code endpoint} would cross a network in cleartext: the scheme is
     * not {@code https} and the host is not loopback ({@code localhost}, {@code 127.0.0.0/8}, {@code ::1}).
     * An unparseable endpoint, or one with no host, is <em>not</em> flagged here — the request's own URI
     * error surfaces instead, rather than a misleading "cleartext" message.
     */
    public static boolean isCleartextRemote(String endpoint) {
        URI u = parse(endpoint);
        if (u == null) {
            return false;
        }
        String scheme = u.getScheme();
        if (scheme == null || scheme.equalsIgnoreCase("https")) {
            return false;
        }
        String host = u.getHost();
        return host != null && !isLoopback(host);
    }

    /** The host of {@code endpoint} for a status message, or the trimmed endpoint when it has no host. */
    public static String hostOf(String endpoint) {
        URI u = parse(endpoint);
        String host = u == null ? null : u.getHost();
        if (host != null) {
            return host;
        }
        return endpoint == null ? "" : endpoint.trim();
    }

    /** Whether {@code host} is a loopback name/address ({@code localhost}, {@code 127.0.0.0/8}, {@code ::1}). */
    static boolean isLoopback(String host) {
        String h = host.strip();
        if (h.startsWith("[") && h.endsWith("]")) { // an IPv6 literal, as URI.getHost() returns it
            h = h.substring(1, h.length() - 1);
        }
        if (h.equalsIgnoreCase("localhost")) {
            return true;
        }
        if (h.equals("::1") || h.equalsIgnoreCase("0:0:0:0:0:0:0:1")) {
            return true;
        }
        return h.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"); // 127.0.0.0/8
    }

    private static URI parse(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        try {
            return URI.create(endpoint.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
