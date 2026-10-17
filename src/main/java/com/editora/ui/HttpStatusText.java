package com.editora.ui;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.editora.http.HttpExchange;
import com.editora.http.HttpResult;
import com.editora.http.HttpTests;

import static com.editora.i18n.Messages.tr;

/**
 * What the HTTP Client says about a response, in the response view's status line and in the status bar: the
 * status code with its reason phrase ({@code 404 Not Found}), timing and size, a style class for the colour,
 * and the summary of a Run All. Pure — tested by {@code HttpStatusTextTest}.
 *
 * <p>The words used to come from the Run console ("Failed (exit 1)"): a 404 read "exit 404", a refused
 * connection "exit 0", and a Run All with two failures out of five "exit 5".
 */
final class HttpStatusText {

    private HttpStatusText() {}

    /** Style classes on the status line: one per outcome, so a theme can colour 2xx/3xx/4xx/5xx/failed. */
    static final String OK = "http-status-ok";

    static final String REDIRECT = "http-status-redirect";
    static final String CLIENT_ERROR = "http-status-client-error";
    static final String SERVER_ERROR = "http-status-server-error";
    static final String FAILED = "http-status-failed";
    static final List<String> ALL = List.of(OK, REDIRECT, CLIENT_ERROR, SERVER_ERROR, FAILED);

    /** The standard reason phrases (RFC 9110 and the common extensions). */
    private static final Map<Integer, String> REASONS = Map.ofEntries(
            Map.entry(100, "Continue"),
            Map.entry(101, "Switching Protocols"),
            Map.entry(103, "Early Hints"),
            Map.entry(200, "OK"),
            Map.entry(201, "Created"),
            Map.entry(202, "Accepted"),
            Map.entry(203, "Non-Authoritative Information"),
            Map.entry(204, "No Content"),
            Map.entry(205, "Reset Content"),
            Map.entry(206, "Partial Content"),
            Map.entry(207, "Multi-Status"),
            Map.entry(300, "Multiple Choices"),
            Map.entry(301, "Moved Permanently"),
            Map.entry(302, "Found"),
            Map.entry(303, "See Other"),
            Map.entry(304, "Not Modified"),
            Map.entry(307, "Temporary Redirect"),
            Map.entry(308, "Permanent Redirect"),
            Map.entry(400, "Bad Request"),
            Map.entry(401, "Unauthorized"),
            Map.entry(402, "Payment Required"),
            Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"),
            Map.entry(405, "Method Not Allowed"),
            Map.entry(406, "Not Acceptable"),
            Map.entry(407, "Proxy Authentication Required"),
            Map.entry(408, "Request Timeout"),
            Map.entry(409, "Conflict"),
            Map.entry(410, "Gone"),
            Map.entry(411, "Length Required"),
            Map.entry(412, "Precondition Failed"),
            Map.entry(413, "Content Too Large"),
            Map.entry(414, "URI Too Long"),
            Map.entry(415, "Unsupported Media Type"),
            Map.entry(416, "Range Not Satisfiable"),
            Map.entry(417, "Expectation Failed"),
            Map.entry(418, "I'm a teapot"),
            Map.entry(421, "Misdirected Request"),
            Map.entry(422, "Unprocessable Content"),
            Map.entry(423, "Locked"),
            Map.entry(425, "Too Early"),
            Map.entry(426, "Upgrade Required"),
            Map.entry(428, "Precondition Required"),
            Map.entry(429, "Too Many Requests"),
            Map.entry(431, "Request Header Fields Too Large"),
            Map.entry(451, "Unavailable For Legal Reasons"),
            Map.entry(500, "Internal Server Error"),
            Map.entry(501, "Not Implemented"),
            Map.entry(502, "Bad Gateway"),
            Map.entry(503, "Service Unavailable"),
            Map.entry(504, "Gateway Timeout"),
            Map.entry(505, "HTTP Version Not Supported"),
            Map.entry(507, "Insufficient Storage"),
            Map.entry(508, "Loop Detected"),
            Map.entry(511, "Network Authentication Required"));

    /** {@code 404 Not Found}; an unknown code is shown alone ({@code 599}). */
    static String statusWithReason(int status) {
        String reason = REASONS.get(status);
        return reason == null ? String.valueOf(status) : status + " " + reason;
    }

    /** The style class for {@code r}'s outcome. */
    static String styleClass(HttpResult r) {
        if (r == null || r.failed()) {
            return FAILED;
        }
        int s = r.status();
        if (s >= 500) {
            return SERVER_ERROR;
        }
        if (s >= 400) {
            return CLIENT_ERROR;
        }
        if (s >= 300) {
            return REDIRECT;
        }
        return OK;
    }

    /** The response view's status line for {@code r}: {@code 200 OK · 32 ms · 44 B}, or "Request failed". */
    static String summary(HttpResult r) {
        if (notSent(r)) {
            return tr("httppanel.notSent"); // never sent: "Request failed" read as a server or network problem
        }
        if (r.failed()) {
            return tr("httppanel.requestFailed");
        }
        return tr(
                "httppanel.summary", statusWithReason(r.status()), String.valueOf(r.elapsedMs()), size(r.sizeBytes()));
    }

    /** The status pill: {@code 404 Not Found}, "Not sent" or "Request failed". */
    static String pill(HttpResult r) {
        if (notSent(r)) {
            return tr("httppanel.notSent");
        }
        if (r == null || r.failed()) {
            return tr("httppanel.requestFailed");
        }
        return statusWithReason(r.status());
    }

    /** The timing and size beside the pill ({@code 32 ms · 44 B}), or "" for a request that failed. */
    static String timing(HttpResult r) {
        if (r == null || r.failed()) {
            return "";
        }
        return tr("httppanel.timing", String.valueOf(r.elapsedMs()), size(r.sizeBytes()));
    }

    /**
     * The Tests tab's count: passed of total ({@code 2/3}); "✗" when no test ran but a script stopped with an error;
     * null when there is nothing to count (no tests, or only a script's log).
     */
    static String testsCount(HttpTests t) {
        if (t == null || t.isEmpty()) {
            return null;
        }
        if (t.tests().isEmpty()) {
            return t.error() == null ? null : "✗";
        }
        return t.passed() + "/" + t.tests().size();
    }

    /** The UI language's locale, for numbers in the response view ({@code 1,4 s} in German). */
    static Locale uiLocale() {
        return Locale.forLanguageTag(com.editora.i18n.Messages.current());
    }

    /** Seconds with one decimal in the UI language ({@code 1.4} / {@code 1,4}). */
    static String seconds(double seconds) {
        return String.format(uiLocale(), "%.1f", seconds);
    }

    /** A byte count as the response view shows it ({@code 598 B}, {@code 4.9 KB} / {@code 4,9 KB}). */
    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        return kb < 1024 ? String.format(uiLocale(), "%.1f KB", kb) : String.format(uiLocale(), "%.1f MB", kb / 1024);
    }

    /**
     * The response view's line for a finished Run All: "Run All: 3 of 13 failed" or "Run All: all 13 succeeded";
     * null for a single run.
     */
    static String runAllSummary(List<HttpExchange> exchanges) {
        if (exchanges == null || exchanges.size() < 2) {
            return null;
        }
        long unsent = exchanges.stream().filter(ex -> notSent(ex.result())).count();
        long failed = exchanges.stream().filter(ex -> !ex.result().ok()).count() - unsent;
        if (unsent > 0) {
            return failed == 0
                    ? tr("httppanel.runAllNotSent", unsent, exchanges.size())
                    : tr("httppanel.runAllFailedNotSent", failed, unsent, exchanges.size());
        }
        String line = failed == 0
                ? tr("httppanel.runAllOk", exchanges.size())
                : tr("httppanel.runAllFailed", failed, exchanges.size());
        return line + testsFailedSuffix(exchanges);
    }

    /** " · tests failed in 2 requests" when some exchange's tests failed (or its handler stopped), else "". */
    static String testsFailedSuffix(List<HttpExchange> exchanges) {
        long n = exchanges.stream()
                .filter(ex -> ex.result() != null && ex.result().tests().hasFailures())
                .count();
        return n == 0 ? "" : " · " + tr("httppanel.runAllTestsFailed", n);
    }

    /** One part of the tests badge: its text, and whether it reports a failure (red) or passes (green). */
    record BadgePart(String text, boolean failed) {}

    /**
     * The parts of the tests badge beside the status line — "✓ 3 passed", "✗ 1 failed", "✗ handler error" — each
     * coloured on its own; empty when the response ran no tests and its handler stopped with no error. {@code compact}
     * shortens them to "✓3", "✗1".
     */
    static List<BadgePart> testsBadgeParts(HttpTests t, boolean compact, boolean preRequest) {
        List<BadgePart> parts = new java.util.ArrayList<>();
        if (t == null || (t.tests().isEmpty() && t.error() == null)) {
            return parts;
        }
        int passed = t.passed();
        int failed = t.failed();
        if (compact) {
            if (passed > 0) {
                parts.add(new BadgePart("✓" + passed, false));
            }
            if (failed > 0 || t.error() != null) {
                parts.add(new BadgePart("✗" + (failed + (t.error() != null ? 1 : 0)), true));
            }
            return parts;
        }
        if (passed > 0) {
            parts.add(new BadgePart(tr("httppanel.testsPassed", passed), false));
        }
        if (failed > 0) {
            parts.add(new BadgePart(tr("httppanel.testsFailed", failed), true));
        }
        if (t.error() != null) {
            parts.add(new BadgePart(tr(preRequest ? "httppanel.testsPreError" : "httppanel.testsError"), true));
        }
        return parts;
    }

    /** The tests badge as one text ("✓ 3 passed · ✗ 1 failed", or "✓3 ✗1" compact), or null when there is none. */
    static String testsBadge(HttpTests t, boolean compact, boolean preRequest) {
        List<BadgePart> parts = testsBadgeParts(t, compact, preRequest);
        if (parts.isEmpty()) {
            return null;
        }
        return String.join(
                compact ? " " : BADGE_SEPARATOR,
                parts.stream().map(BadgePart::text).toList());
    }

    /** What separates the full badge's parts. */
    static final String BADGE_SEPARATOR = " · ";

    /**
     * The Tests block's title: "Tests: 3 passed, 1 failed"; when it ran no test, "Script output", or the error's
     * script ({@code preRequest}: see {@link #preRequestFailed}).
     */
    static String testsTitle(HttpTests t, boolean preRequest) {
        if (t.tests().isEmpty()) {
            if (t.error() == null) {
                return tr("httppanel.testsTitleLog"); // the log lines may come from either kind of script
            }
            return tr(preRequest ? "httppanel.testsTitlePreError" : "httppanel.testsTitleError");
        }
        return tr("httppanel.testsTitle", t.passed(), t.failed());
    }

    /**
     * The response view's line after a Run All the user cancelled: how far it got and how the finished requests
     * went ("Run All cancelled: 2 of 4 ran, 1 failed"). A cancelled run used to read "Run All: all 2 succeeded",
     * which looked like a complete, green run. Pure.
     */
    static String runAllCancelledSummary(List<HttpExchange> finished, int total) {
        int ran = finished == null ? 0 : finished.size();
        long failed = finished == null
                ? 0
                : finished.stream().filter(ex -> !ex.result().ok()).count();
        return tr("httppanel.runAllCancelled", ran, total, failed);
    }

    /** The status-bar message after a single request: what was sent, and how it went. */
    static String finished(HttpExchange ex) {
        String label = ex.label();
        HttpResult r = ex.result();
        if (notSent(r)) {
            // "GET … not sent: …", not "GET … failed: Not sent: …"
            return tr(
                    "status.http.notSentError", label, notSentReason(withoutUrl(firstLine(r.shownError()), ex.url())));
        }
        if (r.failed()) {
            return tr("status.http.failedError", label, withoutUrl(firstLine(r.shownError()), ex.url()));
        }
        String line = r.ok()
                ? tr("status.http.finished", label, statusWithReason(r.status()))
                : tr("status.http.failedStatus", label, statusWithReason(r.status()));
        HttpTests t = r.tests();
        if (t.hasFailures()) {
            line += " · " + (t.failed() > 0 ? tr("httppanel.testsFailed", t.failed()) : tr("httppanel.testsError"));
        }
        return line;
    }

    /** The status-bar message after a Run All: "All 5 requests finished" / "2 of 5 requests failed". */
    static String finishedAll(List<HttpExchange> exchanges) {
        long unsent = exchanges.stream().filter(ex -> notSent(ex.result())).count();
        long failed = exchanges.stream().filter(ex -> !ex.result().ok()).count() - unsent;
        if (unsent > 0) { // a refused or "save first" request did not fail: it was never sent
            return failed == 0
                    ? tr("status.http.someNotSent", unsent, exchanges.size())
                    : tr("status.http.someFailedNotSent", failed, unsent, exchanges.size());
        }
        String line = failed == 0
                ? tr("status.http.allDone", exchanges.size())
                : tr("status.http.someFailed", failed, exchanges.size());
        return line + testsFailedSuffix(exchanges);
    }

    /**
     * Whether {@code r} is a request that was never sent — refused by the trust prompt, a body file that cannot be
     * read, an untitled buffer's "save first": its error starts with the UI language's "Not sent:" prefix
     * ({@code http.notSent.prefix}), which every such message in the six catalogs shares. Pure.
     */
    /**
     * Whether {@code r}'s script error is a pre-request script's. A pre-request script that stops with an error keeps
     * its request unsent, and a response handler only runs once a response has arrived, so an error on a request that
     * was not sent is always the pre-request script's. Pure.
     */
    static boolean preRequestFailed(HttpResult r) {
        return r != null && r.tests().error() != null && notSent(r);
    }

    static boolean notSent(HttpResult r) {
        return r != null && r.failed() && notSentReason(r.error()) != null;
    }

    /** {@code error} without its leading "Not sent:" prefix, or null when it has none. Pure. */
    static String notSentReason(String error) {
        if (error == null) {
            return null;
        }
        String prefix = tr("http.notSent.prefix");
        String e = error.strip();
        if (!e.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        return e.substring(prefix.length()).strip();
    }

    /**
     * The warning badge's text: {@code "⚠ 11 warnings"}, or {@code "⚠ 11"} when {@code compact} — at a narrow width
     * the full badge used to squeeze the status line itself to "200 O…117 B". Pure.
     */
    static String warningBadge(int count, boolean compact) {
        return compact ? "⚠ " + count : tr("httppanel.warnings", count);
    }

    /**
     * The exchange a Run All should land on: the first that did not succeed, else the first whose tests failed, else
     * the first. -1 if none.
     */
    static int firstToShow(List<HttpExchange> exchanges) {
        for (int i = 0; i < exchanges.size(); i++) {
            if (!exchanges.get(i).result().ok()) {
                return i;
            }
        }
        for (int i = 0; i < exchanges.size(); i++) {
            if (exchanges.get(i).result().tests().hasFailures()) {
                return i;
            }
        }
        return exchanges.isEmpty() ? -1 : 0;
    }

    /** {@code lines} with repeats shown once, as {@code "line (×3)"}, in first-seen order. Pure. */
    static List<String> grouped(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String l : lines) {
            if (l != null && !l.isBlank()) {
                counts.merge(l, 1, Integer::sum);
            }
        }
        List<String> out = new java.util.ArrayList<>(counts.size());
        counts.forEach((l, n) -> out.add(n == 1 ? l : l + " (×" + n + ")"));
        return out;
    }

    /** {@code error} without the {@code  (url)} the engine appends: the status bar already starts with the URL. */
    static String withoutUrl(String error, String url) {
        if (error == null || url == null || url.isBlank()) {
            return error;
        }
        String suffix = "(" + url + ")";
        String e = error.strip();
        return e.endsWith(suffix) ? e.substring(0, e.length() - suffix.length()).strip() : error;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
