package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.editora.config.HttpResponseHistory;
import com.editora.http.HttpExchange;
import com.editora.http.HttpFile;
import com.editora.http.HttpResult;
import com.editora.http.HttpTests;
import org.jspecify.annotations.Nullable;

/**
 * One row of the response view's History: the {@link HttpExchange}, the request's {@code name} (its
 * {@code # @name} or {@code ###} title, may be null) and position in the file (for Run again; -1 unknown), when
 * it ran, whether its body was dropped to save memory, and whether it is a binary response restored from
 * the saved history (whose bytes were never saved). A {@code noLog} entry ({@code # @no-log}) is shown but
 * never saved. Pure helpers convert to and from the stored form
 * ({@link HttpResponseHistory.Response}) and keep the history inside its memory budget.
 *
 * <p>{@code landedAt} is when the response arrived (a Clear is compared with it, not with {@code at}, so a run in
 * flight during a Clear is kept as it is shown); {@code urlTemplate} and {@code fingerprint} are the request's URL as
 * written and a digest of its text, by which Run finds the request again ({@link HttpRerunTarget}); null when
 * unknown (an entry saved before they were kept). {@code environment} is the environment the run resolved against
 * ({@code ""}: none), or null when unknown (an entry saved before it was kept, or built without a run).
 */
record HttpHistoryEntry(
        HttpExchange exchange,
        @Nullable String name,
        int requestIndex,
        long at,
        boolean bodyDropped,
        boolean binaryNotKept,
        boolean noLog,
        boolean bodyCut,
        long landedAt,
        @Nullable String urlTemplate,
        @Nullable String fingerprint,
        @Nullable String environment) {

    /** At most this many characters of bodies are kept across one panel's history (about 100 MB). */
    static final long MAX_BODY_BYTES = 100L * 1024 * 1024;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    HttpHistoryEntry {
        name = name == null || name.isBlank() ? null : name.strip();
    }

    /** An entry whose environment is not known. */
    HttpHistoryEntry(
            HttpExchange exchange,
            @Nullable String name,
            int requestIndex,
            long at,
            boolean bodyDropped,
            boolean binaryNotKept,
            boolean noLog,
            boolean bodyCut,
            long landedAt,
            @Nullable String urlTemplate,
            @Nullable String fingerprint) {
        this(
                exchange,
                name,
                requestIndex,
                at,
                bodyDropped,
                binaryNotKept,
                noLog,
                bodyCut,
                landedAt,
                urlTemplate,
                fingerprint,
                null);
    }

    /** An entry that lands now, of a request not known by its text. */
    HttpHistoryEntry(
            HttpExchange exchange,
            @Nullable String name,
            int requestIndex,
            long at,
            boolean bodyDropped,
            boolean binaryNotKept,
            boolean noLog,
            boolean bodyCut) {
        this(
                exchange,
                name,
                requestIndex,
                at,
                bodyDropped,
                binaryNotKept,
                noLog,
                bodyCut,
                System.currentTimeMillis(),
                null,
                null);
    }

    /** A live entry: nothing of its body was cut by the saved history. */
    HttpHistoryEntry(
            HttpExchange exchange,
            @Nullable String name,
            int requestIndex,
            long at,
            boolean bodyDropped,
            boolean binaryNotKept,
            boolean noLog) {
        this(exchange, name, requestIndex, at, bodyDropped, binaryNotKept, noLog, false);
    }

    /**
     * The entry of a run that just landed: {@code request} (null when unknown) at {@code requestIndex}, started at
     * {@code at}; its name, URL template and fingerprint come from the request's text.
     */
    static HttpHistoryEntry ran(
            HttpExchange exchange, HttpFile.Parsed request, int requestIndex, long at, boolean noLog) {
        return ran(exchange, request, requestIndex, at, noLog, null);
    }

    /** {@link #ran(HttpExchange, HttpFile.Parsed, int, long, boolean)}, run in {@code environment} ({@code ""}: none). */
    static HttpHistoryEntry ran(
            HttpExchange exchange,
            HttpFile.@Nullable Parsed request,
            int requestIndex,
            long at,
            boolean noLog,
            @Nullable String environment) {
        return new HttpHistoryEntry(
                exchange,
                request == null ? null : request.name(),
                requestIndex,
                at,
                false,
                false,
                noLog,
                false,
                System.currentTimeMillis(),
                request == null ? null : request.url(),
                request == null ? null : HttpRerunTarget.fingerprint(request),
                environment);
    }

    /**
     * The values the run read from {@code $processEnv}/{@code $env}/{@code $dotenv}: masked in the saved history and
     * the History label (adl, 2026-10-15). Kept in memory only.
     */
    List<String> secretValues() {
        return exchange.details() == null ? List.of() : exchange.details().secretValues();
    }

    /** The key this entry is saved under ({@link HttpResponseHistory#entryKey}), without building the stored form. */
    String storedKey() {
        List<String> secrets = secretValues();
        return HttpResponseHistory.entryKey(
                at, requestIndex, name, exchange.method(), HttpSecretMask.mask(exchange.url(), secrets));
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
        String shown =
                HttpSecretMask.mask(exchange.label(), secretValues()); // a secret the run read is never shown here
        return name == null ? state + "  " + time + "  " + shown : state + "  " + time + "  " + name + " — " + shown;
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
                r.protocol(),
                r.tests());
        HttpExchange ex = new HttpExchange(
                exchange.label(),
                exchange.method(),
                exchange.url(),
                exchange.headers(),
                exchange.requestBody(),
                light,
                exchange.details()); // how it was sent: Copy as cURL still reproduces it
        return new HttpHistoryEntry(
                ex,
                name,
                requestIndex,
                at,
                true,
                binaryNotKept,
                noLog,
                bodyCut,
                landedAt,
                urlTemplate,
                fingerprint,
                environment);
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

    /**
     * The form saved in the config folder: masked credential headers, a capped text body, no binary bytes, the
     * protocol, the charset the body was decoded with, and how the request was sent (never the Digest password).
     */
    HttpResponseHistory.Response toStored() {
        return toStored(secretValues());
    }

    /**
     * {@link #toStored()} with {@code secrets} — the values the run read from the environment or {@code .env} —
     * masked wherever they appear: the URL, the header values, the bodies, the form values, the error, the
     * warnings, and the test results and handler log.
     */
    HttpResponseHistory.Response toStored(List<String> secrets) {
        HttpResult r = exchange.result();
        boolean binary = r.binary() || binaryNotKept;
        String body = binary || bodyDropped ? "" : HttpSecretMask.mask(r.body(), secrets);
        String kept = HttpResponseHistory.cappedBody(body);
        HttpExchange.Details given = exchange.details();
        HttpExchange.Details d = given == null ? HttpExchange.Details.NONE : given; // what HttpExchange makes of null
        List<HttpResponseHistory.FormField> fields = new ArrayList<>();
        for (HttpExchange.FormPart p : d.formParts()) {
            fields.add(new HttpResponseHistory.FormField(
                    p.name(),
                    p.value() == null ? null : HttpResponseHistory.cappedBody(HttpSecretMask.mask(p.value(), secrets)),
                    p.file(),
                    p.filename(),
                    p.contentType()));
        }
        HttpResponseHistory.Sent sent = new HttpResponseHistory.Sent(
                d.bodyFile(),
                fields,
                d.digestUser(),
                d.followRedirects(),
                d.httpVersion(),
                HttpSecretMask.mask(d.finalUrl(), secrets));
        List<String> warnings = new ArrayList<>();
        for (String w : r.warnings() == null ? List.<String>of() : r.warnings()) {
            warnings.add(HttpSecretMask.mask(w, secrets));
        }
        String url = HttpSecretMask.mask(exchange.url(), secrets); // non-null in, non-null out
        return new HttpResponseHistory.Response(
                at,
                name,
                requestIndex,
                exchange.method(),
                url == null ? exchange.url() : url,
                HttpSecretMask.maskValues(HttpResponseHistory.maskedHeaders(exchange.headers()), secrets),
                HttpResponseHistory.cappedBody(HttpSecretMask.mask(exchange.requestBody(), secrets)),
                r.status(),
                HttpSecretMask.maskValues(HttpResponseHistory.maskedHeaders(r.headers()), secrets),
                kept,
                bodyCut || kept.length() < (body == null ? 0 : body.length()),
                binary,
                r.contentType(),
                r.elapsedMs(),
                r.sizeBytes(),
                HttpSecretMask.mask(r.error(), secrets),
                List.copyOf(warnings),
                r.truncated(),
                r.protocol(),
                binary || r.failed() ? null : r.bodyCharset(),
                sent,
                landedAt,
                urlTemplate,
                fingerprint,
                storedTests(r.tests(), secrets),
                environment);
    }

    /** {@code tests} as saved, with {@code secrets} masked in every text; null when nothing ran. */
    static HttpResponseHistory.@Nullable Tests storedTests(HttpTests tests, List<String> secrets) {
        if (tests == null || tests.isEmpty()) {
            return null;
        }
        List<HttpResponseHistory.TestResult> results = new ArrayList<>();
        for (HttpTests.Test t : tests.tests()) {
            results.add(new HttpResponseHistory.TestResult(
                    HttpSecretMask.mask(t.name(), secrets), t.passed(), HttpSecretMask.mask(t.message(), secrets)));
        }
        List<String> log = new ArrayList<>();
        for (String line : tests.log()) {
            log.add(HttpSecretMask.mask(line, secrets));
        }
        return new HttpResponseHistory.Tests(
                List.copyOf(results), List.copyOf(log), HttpSecretMask.mask(tests.error(), secrets));
    }

    /** Saved test results back in memory; {@link HttpTests#NONE} for none (or an entry from before schema 4). */
    static HttpTests restoredTests(HttpResponseHistory.@Nullable Tests stored) {
        if (stored == null) {
            return HttpTests.NONE;
        }
        List<HttpTests.Test> tests = new ArrayList<>();
        if (stored.tests() != null) {
            for (HttpResponseHistory.TestResult t : stored.tests()) {
                if (t != null) {
                    String testName = t.name(); // Test makes null "" (an older file may lack either)
                    String message = t.message();
                    tests.add(new HttpTests.Test(
                            testName == null ? "" : testName, t.passed(), message == null ? "" : message));
                }
            }
        }
        List<String> log = new ArrayList<>();
        if (stored.log() != null) {
            for (String line : stored.log()) {
                if (line != null) {
                    log.add(line);
                }
            }
        }
        return new HttpTests(tests, log, stored.error());
    }

    /**
     * An entry rebuilt from its stored form; a binary or dropped body comes back empty (the view says why). The
     * body's bytes are rebuilt in the charset it was decoded with, and how the request was sent comes back too
     * (an entry saved before schema 2 has none: {@link HttpExchange.Details#NONE}).
     */
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
                body.getBytes(charset(s.charset())),
                s.truncated(), // as received; a body cut by the history is bodyCut, not "not downloaded"
                List.of(),
                s.protocol(),
                restoredTests(s.tests()));
        String method = s.method() == null ? "GET" : s.method();
        String url = s.url() == null ? "" : s.url();
        HttpExchange ex = new HttpExchange(
                method + " " + url,
                method,
                url,
                reqHeaders,
                s.requestBody() == null ? "" : s.requestBody(),
                result,
                details(s.sent()));
        return new HttpHistoryEntry(
                ex,
                s.name(),
                s.requestIndex(),
                s.at(),
                false,
                s.binary(),
                false,
                s.bodyCut(),
                s.landed(),
                s.urlTemplate(),
                s.fingerprint(),
                s.environment());
    }

    private static java.nio.charset.Charset charset(@Nullable String name) {
        try {
            return name == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(name);
        } catch (RuntimeException unknown) {
            return StandardCharsets.UTF_8;
        }
    }

    private static HttpExchange.Details details(HttpResponseHistory.@Nullable Sent sent) {
        if (sent == null) {
            return HttpExchange.Details.NONE;
        }
        List<HttpExchange.FormPart> parts = new ArrayList<>();
        if (sent.formParts() != null) {
            for (HttpResponseHistory.FormField f : sent.formParts()) {
                if (f != null) {
                    parts.add(new HttpExchange.FormPart(f.name(), f.value(), f.file(), f.filename(), f.contentType()));
                }
            }
        }
        return new HttpExchange.Details(
                sent.bodyFile(),
                parts,
                sent.digestUser(),
                null, // never stored: Copy as cURL lets curl ask for it
                sent.followRedirects(),
                sent.httpVersion(),
                sent.finalUrl(),
                false);
    }

    private static List<String[]> pairs(@Nullable List<HttpResponseHistory.Header> headers) {
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
