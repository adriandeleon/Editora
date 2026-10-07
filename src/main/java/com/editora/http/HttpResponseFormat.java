package com.editora.http;

import java.io.IOException;
import java.io.StringWriter;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;

/**
 * Renders an {@link HttpResult} into a readable response report: a status line, the response headers, a
 * blank line, the body <b>as received</b>, and a footer with status/time/size. Pure, so the formatting + JSON
 * pretty-print are unit-tested.
 *
 * <p>Pretty-printing is for the viewer only ({@link #view}) and works on the token stream, so it changes
 * whitespace and nothing else: a number keeps its exact text ({@code 10.50}, {@code 1e2}, a 30-digit decimal)
 * and duplicate keys survive. Anything saved or opened in a tab uses the unmodified body.
 */
public final class HttpResponseFormat {

    private static final JsonFactory JSON = new JsonFactory();

    /**
     * What the response viewer shows for a body: the (pretty-printed) {@code text}, cut to the viewer's limit
     * when {@code clipped} — {@code totalChars} is the uncut length, for the visible "truncated" line — or no
     * text at all for a {@code binary} payload.
     */
    public record BodyView(String text, int totalChars, boolean clipped, boolean binary) {}

    /** Builds the viewer's body for {@code r}, showing at most {@code maxChars} characters. Pure. */
    public static BodyView view(HttpResult r, int maxChars) {
        if (r.failed()) {
            return new BodyView("", 0, false, false);
        }
        if (r.binary()) {
            return new BodyView("", 0, false, true);
        }
        String pretty = prettyBody(r.body(), r.contentType());
        if (pretty.length() <= maxChars) {
            return new BodyView(pretty, pretty.length(), false, false);
        }
        int cut = maxChars;
        if (cut > 0 && Character.isHighSurrogate(pretty.charAt(cut - 1))) {
            cut--; // never split a surrogate pair
        }
        return new BodyView(pretty.substring(0, cut), pretty.length(), true, false);
    }

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
        StringBuilder sb = new StringBuilder();
        for (String w : r.warnings()) {
            sb.append("⚠  ").append(w).append('\n'); // e.g. a header that couldn't be sent — shown, not dropped
        }
        for (String w : r.written()) {
            sb.append("→  ").append(w).append('\n'); // what a ">>" redirect saved to disk
        }
        if (!r.warnings().isEmpty() || !r.written().isEmpty()) {
            sb.append('\n');
        }
        if (r.failed()) {
            return sb.append("⚠  ").append(r.error()).toString();
        }
        sb.append("HTTP ").append(r.status()).append('\n');
        for (String[] h : r.headers()) {
            sb.append(h[0]).append(": ").append(h[1]).append('\n');
        }
        // The body exactly as the server sent it — a saved report must not carry the viewer's reformatting.
        sb.append('\n').append(r.binary() ? "(binary body, " + humanSize(r.sizeBytes()) + " — not shown)" : r.body());
        if (r.truncated()) {
            sb.append("\n… [truncated: only the first ")
                    .append(humanSize(r.sizeBytes()))
                    .append(" were received]");
        }
        sb.append("\n\n— ")
                .append(r.status())
                .append("  ·  ")
                .append(r.elapsedMs())
                .append(" ms  ·  ")
                .append(humanSize(r.sizeBytes()));
        return sb.toString();
    }

    /**
     * Pretty-prints a JSON body (by content type) without changing any value; other bodies, and JSON that
     * does not parse, are returned unchanged.
     */
    public static String prettyBody(String body, String contentType) {
        if (body == null) {
            return "";
        }
        if (contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).contains("json")
                && !body.isBlank()) {
            try {
                return reindentJson(body);
            } catch (Exception e) {
                return body; // not valid JSON after all — show as-is
            }
        }
        return body;
    }

    /**
     * Copies the JSON token stream through a pretty-printing generator. Going through a tree (the old
     * {@code readValue(Object.class)}) parsed every number into a {@code double}/{@code int} and every object
     * into a map, so {@code 10.50} became {@code 10.5}, {@code 1e2} became {@code 100.0}, long decimals lost
     * digits and a repeated key vanished — the viewer showed data the server never sent.
     */
    private static String reindentJson(String body) throws IOException {
        StringWriter out = new StringWriter(body.length() + body.length() / 4);
        try (JsonParser parser = JSON.createParser(body);
                JsonGenerator gen = JSON.createGenerator(out)) {
            gen.setPrettyPrinter(new DefaultPrettyPrinter().withRootSeparator("\n"));
            for (JsonToken t = parser.nextToken(); t != null; t = parser.nextToken()) {
                if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT) {
                    gen.writeNumber(parser.getText()); // the number's own text, verbatim
                } else {
                    gen.copyCurrentEvent(parser);
                }
            }
        }
        return out.toString();
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        return kb < 1024
                ? String.format(java.util.Locale.ROOT, "%.1f KB", kb)
                : String.format(java.util.Locale.ROOT, "%.1f MB", kb / 1024);
    }
}
