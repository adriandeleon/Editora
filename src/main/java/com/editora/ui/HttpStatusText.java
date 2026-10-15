package com.editora.ui;

import java.util.List;
import java.util.Map;

import com.editora.http.HttpExchange;
import com.editora.http.HttpResponseFormat;
import com.editora.http.HttpResult;

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
        if (r.failed()) {
            return tr("httppanel.requestFailed");
        }
        return tr(
                "httppanel.summary",
                statusWithReason(r.status()),
                String.valueOf(r.elapsedMs()),
                HttpResponseFormat.humanSize(r.sizeBytes()));
    }

    /** The status-bar message after a single request: what was sent, and how it went. */
    static String finished(HttpExchange ex) {
        String label = ex.label();
        HttpResult r = ex.result();
        if (r.failed()) {
            return tr("status.http.failedError", label, firstLine(r.error()));
        }
        return r.ok()
                ? tr("status.http.finished", label, statusWithReason(r.status()))
                : tr("status.http.failedStatus", label, statusWithReason(r.status()));
    }

    /** The status-bar message after a Run All: "All 5 requests finished" / "2 of 5 requests failed". */
    static String finishedAll(List<HttpExchange> exchanges) {
        long failed = exchanges.stream().filter(ex -> !ex.result().ok()).count();
        return failed == 0
                ? tr("status.http.allDone", exchanges.size())
                : tr("status.http.someFailed", failed, exchanges.size());
    }

    /** The exchange a Run All should land on: the first that did not succeed, else the first. -1 if none. */
    static int firstToShow(List<HttpExchange> exchanges) {
        for (int i = 0; i < exchanges.size(); i++) {
            if (!exchanges.get(i).result().ok()) {
                return i;
            }
        }
        return exchanges.isEmpty() ? -1 : 0;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
