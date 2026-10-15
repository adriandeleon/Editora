package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.editora.config.HttpResponseHistory;
import com.editora.http.HttpExchange;
import com.editora.http.HttpResult;

/**
 * One row of the response view's History: the {@link HttpExchange}, the request's {@code name} (its
 * {@code # @name} or {@code ###} title, may be null) and position in the file (for Run again; -1 unknown), when
 * it ran, whether its body was dropped to save memory, and whether it is a binary response restored from
 * the saved history (whose bytes were never saved). A {@code noLog} entry ({@code # @no-log}) is shown but
 * never saved. Pure helpers convert to and from the stored form
 * ({@link HttpResponseHistory.Response}) and keep the history inside its memory budget.
 */
record HttpHistoryEntry(
        HttpExchange exchange,
        String name,
        int requestIndex,
        long at,
        boolean bodyDropped,
        boolean binaryNotKept,
        boolean noLog) {

    /** At most this many characters of bodies are kept across one panel's history (about 100 MB). */
    static final long MAX_BODY_BYTES = 100L * 1024 * 1024;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    HttpHistoryEntry {
        name = name == null || name.isBlank() ? null : name.strip();
    }

    /** An entry without a name or position (tests, callers that only have the exchange). */
    static HttpHistoryEntry of(HttpExchange ex) {
        return new HttpHistoryEntry(ex, null, -1, System.currentTimeMillis(), false, false, false);
    }

    HttpResult result() {
        return exchange.result();
    }

    /** {@code 404  14:03:07  Get user — GET http://…}: the outcome, the time, the name when there is one. */
    String label(ZoneId zone) {
        HttpResult r = exchange.result();
        String state = r.failed() ? "⚠" : String.valueOf(r.status());
        String time = TIME.format(Instant.ofEpochMilli(at).atZone(zone));
        return name == null
                ? state + "  " + time + "  " + exchange.label()
                : state + "  " + time + "  " + name + " — " + exchange.label();
    }

    /** What this entry's bodies cost in memory (raw bytes plus the decoded text, two bytes a char). */
    long bodyBytes() {
        HttpResult r = exchange.result();
        long raw = r.rawBody() == null ? 0 : r.rawBody().length;
        long text = r.body() == null ? 0 : 2L * r.body().length();
        return raw + text;
    }

    /** This entry with its response body released: status, headers, timing and size stay. */
    HttpHistoryEntry withoutBody() {
        if (bodyDropped) {
            return this;
        }
        HttpResult r = exchange.result();
        HttpResult light = new HttpResult(
                r.status(),
                r.headers(),
                "",
                r.contentType(),
                r.elapsedMs(),
                r.sizeBytes(),
                r.error(),
                r.warnings(),
                new byte[0],
                r.truncated(),
                r.written(),
                r.protocol());
        HttpExchange ex = new HttpExchange(
                exchange.label(),
                exchange.method(),
                exchange.url(),
                exchange.headers(),
                exchange.requestBody(),
                light,
                exchange.details()); // how it was sent: Copy as cURL still reproduces it
        return new HttpHistoryEntry(ex, name, requestIndex, at, true, binaryNotKept, noLog);
    }

    /**
     * {@code history} (newest first) kept within {@code maxBytes} of bodies: the oldest entries lose their
     * bodies first; the newest entry is never stripped, however large. Pure.
     */
    static List<HttpHistoryEntry> withinBudget(List<HttpHistoryEntry> history, long maxBytes) {
        List<HttpHistoryEntry> out = new ArrayList<>(history);
        long total = 0;
        for (HttpHistoryEntry e : out) {
            total += e.bodyBytes();
        }
        for (int i = out.size() - 1; i > 0 && total > maxBytes; i--) {
            HttpHistoryEntry e = out.get(i);
            if (!e.bodyDropped() && e.bodyBytes() > 0) {
                total -= e.bodyBytes();
                out.set(i, e.withoutBody());
            }
        }
        return out;
    }

    // --- stored form -----------------------------------------------------------------------------------

    /** The form saved in the config folder: masked credential headers, a capped text body, no binary bytes. */
    HttpResponseHistory.Response toStored() {
        HttpResult r = exchange.result();
        boolean binary = r.binary() || binaryNotKept;
        String body = binary || bodyDropped ? "" : r.body();
        String kept = HttpResponseHistory.cappedBody(body);
        return new HttpResponseHistory.Response(
                at,
                name,
                requestIndex,
                exchange.method(),
                exchange.url(),
                HttpResponseHistory.maskedHeaders(exchange.headers()),
                HttpResponseHistory.cappedBody(exchange.requestBody()),
                r.status(),
                HttpResponseHistory.maskedHeaders(r.headers()),
                kept,
                kept.length() < (body == null ? 0 : body.length()),
                binary,
                r.contentType(),
                r.elapsedMs(),
                r.sizeBytes(),
                r.error(),
                r.warnings() == null ? List.of() : List.copyOf(r.warnings()),
                r.truncated());
    }

    /** An entry rebuilt from its stored form; a binary or dropped body comes back empty (the view says why). */
    static HttpHistoryEntry fromStored(HttpResponseHistory.Response s) {
        List<String[]> reqHeaders = pairs(s.requestHeaders());
        List<String[]> respHeaders = pairs(s.headers());
        String body = s.body() == null ? "" : s.body();
        List<String> warnings = s.warnings() == null ? List.of() : s.warnings();
        HttpResult result = new HttpResult(
                s.status(),
                respHeaders,
                body,
                s.contentType() == null ? "" : s.contentType(),
                s.elapsedMs(),
                s.sizeBytes(),
                s.error(),
                warnings,
                body.getBytes(StandardCharsets.UTF_8),
                s.truncated() || s.bodyCut(),
                List.of());
        String method = s.method() == null ? "GET" : s.method();
        String url = s.url() == null ? "" : s.url();
        HttpExchange ex = new HttpExchange(
                method + " " + url, method, url, reqHeaders, s.requestBody() == null ? "" : s.requestBody(), result);
        return new HttpHistoryEntry(ex, s.name(), s.requestIndex(), s.at(), false, s.binary(), false);
    }

    private static List<String[]> pairs(List<HttpResponseHistory.Header> headers) {
        List<String[]> out = new ArrayList<>();
        if (headers != null) {
            for (HttpResponseHistory.Header h : headers) {
                if (h != null && h.name() != null) {
                    out.add(new String[] {h.name(), h.value() == null ? "" : h.value()});
                }
            }
        }
        return out;
    }
}
