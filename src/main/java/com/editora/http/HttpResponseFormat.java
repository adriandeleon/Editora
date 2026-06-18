package com.editora.http;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Renders an {@link HttpResult} into a readable response report: a status line, the response headers, a
 * blank line, the body (pretty-printed when it is JSON), and a footer with status/time/size. Pure, so the
 * formatting + JSON pretty-print are unit-tested.
 */
public final class HttpResponseFormat {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpResponseFormat() {}

    /** File extension (with leading dot) to save a response body under, inferred from its content type. */
    public static String extensionFor(String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        if (ct.contains("json")) {
            return ".json";
        }
        if (ct.contains("html")) {
            return ".html";
        }
        if (ct.contains("xml")) {
            return ".xml";
        }
        return ".txt";
    }

    public static String render(HttpResult r) {
        if (r.failed()) {
            return "⚠  " + r.error();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP ").append(r.status()).append('\n');
        for (String[] h : r.headers()) {
            sb.append(h[0]).append(": ").append(h[1]).append('\n');
        }
        sb.append('\n').append(prettyBody(r.body(), r.contentType()));
        sb.append("\n\n— ")
                .append(r.status())
                .append("  ·  ")
                .append(r.elapsedMs())
                .append(" ms  ·  ")
                .append(humanSize(r.sizeBytes()));
        return sb.toString();
    }

    /** Pretty-prints a JSON body (by content type); other bodies are returned unchanged. */
    public static String prettyBody(String body, String contentType) {
        if (body == null) {
            return "";
        }
        if (contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).contains("json")
                && !body.isBlank()) {
            try {
                Object tree = MAPPER.readValue(body, Object.class);
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
            } catch (Exception e) {
                return body; // not valid JSON after all — show as-is
            }
        }
        return body;
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        return kb < 1024
                ? String.format(java.util.Locale.ROOT, "%.1f KB", kb)
                : String.format(java.util.Locale.ROOT, "%.1f MB", kb / 1024);
    }
}
