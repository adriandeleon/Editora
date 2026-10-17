package com.editora.ui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.editora.editor.HttpLineResult;
import com.editora.http.HttpClientService;
import com.editora.http.HttpFile;
import com.editora.http.HttpResult;
import com.editora.http.HttpTests;

import static com.editora.i18n.Messages.tr;

/**
 * What a {@code .http} request line shows after it ran (U11): {@code 200 OK · 83 ms · ✗ 1 of 3 tests},
 * {@code 404 Not Found · 1 ms}, {@code Connection refused}, {@code Not sent}. The status part is coloured by the
 * outcome, the rest is muted, failed tests are red, and the request's ▶ takes the colour of the whole outcome (a 200
 * whose tests failed has a red ▶). Also which History entry each request of the file shows: the newest one whose
 * request still reads as it did when it ran. Pure — tested by {@code HttpInlineResultTest}.
 */
final class HttpInlineResult {

    private HttpInlineResult() {}

    /** The muted part: time, passed tests, the separators. Every class below starts with this one. */
    static final String MUTED = "http-result";

    static final String OK = "http-result-ok";
    static final String REDIRECT = "http-result-redirect";
    static final String CLIENT_ERROR = "http-result-client-error";
    static final String SERVER_ERROR = "http-result-server-error";
    static final String FAILED = "http-result-failed";
    static final String CANCELLED = "http-result-cancelled";
    /** Failed tests or a script error, after a status that may be green. */
    static final String TESTS_FAILED = "http-result-tests-failed";

    /** The longest error shown on the line; the panel has the whole of it. */
    static final int MAX_ERROR = 60;

    private static final String SEPARATOR = " · ";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** The parts of the text after {@code r}'s request line, in order, adjacent parts of one class merged. */
    static List<HttpLineResult.Segment> segments(HttpResult r) {
        List<HttpLineResult.Segment> out = new ArrayList<>();
        if (r == null) {
            return out;
        }
        if (cancelled(r)) {
            add(out, tr("httpinline.cancelled"), CANCELLED);
        } else if (HttpStatusText.notSent(r)) {
            add(out, tr("httppanel.notSent"), FAILED); // the panel's word, in its colour
        } else if (r.failed()) {
            add(out, shortError(r.shownError()), FAILED);
        } else {
            add(out, HttpStatusText.statusWithReason(r.status()), statusClass(r.status()));
            add(out, SEPARATOR + tr("httpinline.ms", String.valueOf(r.elapsedMs())), MUTED);
        }
        addTests(out, r);
        return out;
    }

    /** The text alone ({@link #segments} joined). */
    static String text(HttpResult r) {
        StringBuilder sb = new StringBuilder();
        for (HttpLineResult.Segment s : segments(r)) {
            sb.append(s.text());
        }
        return sb.toString();
    }

    /**
     * The class the request's ▶ takes: the status's, red when its tests failed or a script stopped with an error,
     * amber when it was cancelled.
     */
    static String glyphClass(HttpResult r) {
        if (r == null) {
            return null;
        }
        if (cancelled(r)) {
            return CANCELLED;
        }
        if (r.failed() || r.tests().hasFailures()) {
            return FAILED;
        }
        return statusClass(r.status());
    }

    private static boolean cancelled(HttpResult r) {
        return r.failed() && HttpClientService.CANCELLED.equals(r.error());
    }

    private static String statusClass(int status) {
        if (status >= 500) {
            return SERVER_ERROR;
        }
        if (status >= 400) {
            return CLIENT_ERROR;
        }
        if (status >= 300) {
            return REDIRECT;
        }
        return OK;
    }

    /** "✗ 1 of 3 tests" (red), "✓ 3 tests" (muted), "✗ handler error" (red); nothing when no test ran. */
    private static void addTests(List<HttpLineResult.Segment> out, HttpResult r) {
        HttpTests t = r.tests();
        if (t == null || (t.tests().isEmpty() && t.error() == null)) {
            return;
        }
        if (t.failed() > 0) {
            add(out, SEPARATOR, MUTED);
            add(out, tr("httpinline.testsFailed", t.failed(), t.tests().size()), TESTS_FAILED);
        } else if (!t.tests().isEmpty()) {
            add(out, SEPARATOR + tr("httpinline.testsPassed", t.passed()), MUTED);
        }
        if (t.error() != null) {
            add(out, SEPARATOR, MUTED);
            add(
                    out,
                    tr(HttpStatusText.preRequestFailed(r) ? "httppanel.testsPreError" : "httppanel.testsError"),
                    TESTS_FAILED);
        }
    }

    private static void add(List<HttpLineResult.Segment> out, String text, String styleClass) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int last = out.size() - 1;
        if (last >= 0 && out.get(last).styleClass().equals(styleClass)) {
            out.set(last, new HttpLineResult.Segment(out.get(last).text() + text, styleClass));
        } else {
            out.add(new HttpLineResult.Segment(text, styleClass));
        }
    }

    /**
     * An error as short as the line can take: its first line, up to the advice after a dash ("Connection refused — is
     * a server listening on …?" is "Connection refused"), at most {@link #MAX_ERROR} characters.
     */
    static String shortError(String error) {
        if (error == null || error.isBlank()) {
            return tr("httppanel.requestFailed");
        }
        String s = error.strip();
        int nl = s.indexOf('\n');
        if (nl >= 0) {
            s = s.substring(0, nl).strip();
        }
        int dash = s.indexOf(" — ");
        if (dash > 0) {
            s = s.substring(0, dash).strip();
        }
        if (s.length() > MAX_ERROR) {
            s = s.substring(0, MAX_ERROR - 1).strip() + "…";
        }
        return s;
    }

    /** When a request ran, for the result's tooltip: the time today, else the day too. */
    static String ranAt(long at, long now, ZoneId zone) {
        ZonedDateTime when = Instant.ofEpochMilli(at).atZone(zone);
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        return (when.toLocalDate().equals(today) ? TIME : DAY_TIME).format(when);
    }

    // --- which entry each request shows -------------------------------------------------------------------------

    /**
     * The History entry each request of the file shows (index in {@code requests} → entry), from {@code history}
     * (newest first): the newest entry that came from the request ({@link HttpRerunTarget}) while its method/URL line
     * read as it does now. A request whose line was edited since its last run shows nothing rather than a result that
     * belongs to another URL. An entry saved before the URL as written was kept is matched by the URL it sent.
     */
    static Map<Integer, HttpHistoryEntry> newestPerRequest(
            List<HttpFile.Request> requests, List<HttpHistoryEntry> history) {
        Map<Integer, HttpHistoryEntry> out = new LinkedHashMap<>();
        if (requests == null || requests.isEmpty() || history == null) {
            return out;
        }
        Set<String> lines = new HashSet<>();
        for (HttpFile.Request r : requests) {
            lines.add(line(r.method(), r.url()));
        }
        for (HttpHistoryEntry e : history) {
            if (out.size() == requests.size()) {
                break;
            }
            String method = e.exchange().method();
            if (e.urlTemplate() != null && !lines.contains(line(method, e.urlTemplate()))) {
                continue; // no request reads that line any more: no need to look for one
            }
            int i = HttpRerunTarget.find(requests, e);
            if (i < 0 || out.containsKey(i) || !sameLine(requests.get(i), e)) {
                continue;
            }
            out.put(i, e);
        }
        return out;
    }

    /** Whether {@code r}'s method/URL line reads as it did when {@code e} ran. */
    static boolean sameLine(HttpFile.Request r, HttpHistoryEntry e) {
        String method = e.exchange().method();
        if (!normalMethod(method).equalsIgnoreCase(normalMethod(r.method()))) {
            return false;
        }
        return e.urlTemplate() != null
                ? e.urlTemplate().strip().equals(r.url() == null ? "" : r.url().strip())
                : HttpRerunTarget.templateMatches(r.url(), e.exchange().url());
    }

    private static String line(String method, String url) {
        return normalMethod(method).toUpperCase(java.util.Locale.ROOT) + " " + (url == null ? "" : url.strip());
    }

    private static String normalMethod(String m) {
        return m == null || m.isBlank() ? "GET" : m.strip();
    }
}
