package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.http.HttpClientService;
import com.editora.http.HttpExchange;
import com.editora.http.HttpResult;
import com.editora.http.HttpTests;

import static com.editora.i18n.Messages.tr;

/**
 * What the response view's Run All results list ({@link HttpRunList}) shows: one row per request in run order — a
 * status pill (the code, or "failed", "not sent", "cancelled"), the request's name with its method and path beside
 * it, the time and the tests ({@code ✗ 2/3} when one failed, {@code ✓ 3} when all passed, {@code —} when none) — and
 * the title above them ("Run All · 3 of 10 failed · 4.1 s"). Pure — tested by {@code HttpRunRowsTest}.
 */
final class HttpRunRows {

    private HttpRunRows() {}

    /** What the tests column says: nothing ran, all passed (muted), or one failed or a script stopped (red). */
    enum Tests {
        NONE,
        PASSED,
        FAILED
    }

    /** The style class of a cancelled row's pill (neither a success nor a failure colour). */
    static final String CANCELLED = "http-status-cancelled";

    /**
     * One row: its {@code entry}; the pill's text and style class (an {@link HttpStatusText} outcome, or {@link
     * #CANCELLED}); the {@code name} (the request's {@code # @name} or {@code ###} title, else its method and path)
     * and the muted {@code detail} beside it (the method and path; null when the name already is them); the time;
     * the tests text and colour; and whether the "Only failures" filter keeps it.
     */
    record Row(
            HttpHistoryEntry entry,
            String status,
            String statusStyle,
            String name,
            String detail,
            String time,
            String tests,
            Tests testsStyle,
            boolean failure) {

        /** The row read out as one line (its accessible text and tooltip): "404, Missing page, GET /missing, 1 ms". */
        String spoken() {
            List<String> parts = new ArrayList<>(List.of(status, name));
            if (detail != null) {
                parts.add(detail);
            }
            parts.add(time);
            if (testsStyle != Tests.NONE) {
                parts.add(tr("httppanel.runList.testsSpoken", tests));
            }
            return String.join(", ", parts);
        }
    }

    /** The rows of {@code entries}, in run order. */
    static List<Row> rows(List<HttpHistoryEntry> entries) {
        List<Row> out = new ArrayList<>(entries == null ? 0 : entries.size());
        if (entries != null) {
            for (HttpHistoryEntry e : entries) {
                out.add(row(e));
            }
        }
        return out;
    }

    /** {@code entry}'s row. */
    static Row row(HttpHistoryEntry entry) {
        HttpExchange ex = entry.exchange();
        HttpResult r = ex.result();
        String status;
        String style;
        if (cancelled(r)) {
            status = tr("httppanel.runList.cancelledPill");
            style = CANCELLED;
        } else if (HttpStatusText.notSent(r)) {
            status = tr("httppanel.runList.notSentPill");
            style = HttpStatusText.FAILED;
        } else if (r.failed()) {
            status = tr("httppanel.runList.failedPill");
            style = HttpStatusText.FAILED;
        } else {
            status = String.valueOf(r.status());
            style = HttpStatusText.styleClass(r);
        }
        // A secret the run read ($processEnv, $dotenv) is never shown, as in the History label.
        String request = HttpSecretMask.mask(requestLine(ex.method(), ex.url()), entry.secretValues());
        String name = entry.name() == null ? request : entry.name();
        String detail = entry.name() == null ? null : request;
        String time = r.failed() ? "—" : tr("httppanel.runList.ms", String.valueOf(r.elapsedMs()));
        HttpTests t = r.tests();
        String tests;
        Tests testsStyle;
        if (t == null || (t.tests().isEmpty() && t.error() == null)) {
            tests = "—";
            testsStyle = Tests.NONE;
        } else if (t.hasFailures()) {
            String count = HttpStatusText.testsCount(t); // "2/3" passed of total, or "✗" for a script error alone
            tests = "✗".equals(count) ? count : "✗ " + count;
            testsStyle = Tests.FAILED;
        } else {
            tests = "✓ " + t.tests().size();
            testsStyle = Tests.PASSED;
        }
        boolean failure = !r.ok() || (t != null && t.hasFailures());
        return new Row(entry, status, style, name, detail, time, tests, testsStyle, failure);
    }

    /** {@code rows} as the "Only failures" filter shows them: the ones that did not succeed or whose tests failed. */
    static List<Row> filter(List<Row> rows, boolean onlyFailures) {
        return onlyFailures ? rows.stream().filter(Row::failure).toList() : rows;
    }

    /** The row selected when the list appears: the first that did not succeed, else the first whose tests failed. */
    static int initial(List<Row> rows) {
        return HttpStatusText.firstToShow(
                rows.stream().map(r -> r.entry().exchange()).toList());
    }

    /**
     * Whether {@code a} and {@code b} are the same run of the same request — one may be the History's copy of the
     * other with its body dropped to save memory, which a record's {@code equals} tells apart. Pure.
     */
    static boolean sameRun(HttpHistoryEntry a, HttpHistoryEntry b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.at() == b.at()
                && a.landedAt() == b.landedAt()
                && a.requestIndex() == b.requestIndex()
                && java.util.Objects.equals(a.exchange().method(), b.exchange().method())
                && java.util.Objects.equals(a.exchange().url(), b.exchange().url());
    }

    /** {@code METHOD /path?query} of a sent URL: the scheme and host are left out (every row of a file shares them). */
    static String requestLine(String method, String url) {
        String m = method == null || method.isBlank() ? "GET" : method.strip();
        return m + " " + pathOf(url);
    }

    /** {@code url} from its path on ({@code /users/42?x=1}); {@code /} for a bare origin; as is without a scheme. */
    static String pathOf(String url) {
        if (url == null) {
            return "";
        }
        String u = url.strip();
        int scheme = u.indexOf("://");
        if (scheme <= 0) {
            return u;
        }
        int start = scheme + 3;
        for (int i = start; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c == '/') {
                return u.substring(i);
            }
            if (c == '?' || c == '#') {
                return "/" + u.substring(i);
            }
        }
        return "/";
    }

    /** Whether {@code r} is a request the user cancelled while it was in flight. */
    static boolean cancelled(HttpResult r) {
        return r != null && r.failed() && HttpClientService.CANCELLED.equals(r.error());
    }

    /** The colour of the title's pill: all succeeded, something was not sent or the run was cancelled, or failed. */
    enum Outcome {
        OK,
        PARTIAL,
        FAILED
    }

    /**
     * The title line: the pill's text and colour (by the responses' statuses, as its words count them), the red note
     * on tests ("tests failed in 1 request"; "" when none failed), the run's time ("4.1 s"; "" when unknown), and the
     * whole line as one sentence for a screen reader.
     */
    record Title(String pill, Outcome outcome, String testsFailed, String time, String spoken) {}

    /**
     * The title of a Run All over {@code entries}: {@code cancelledOf} is the number of requests a cancelled run had
     * (0 for a run that finished); {@code tookMs} its wall-clock time, or -1 when unknown.
     */
    static Title title(List<HttpHistoryEntry> entries, int cancelledOf, long tookMs) {
        List<HttpExchange> exchanges =
                entries.stream().map(HttpHistoryEntry::exchange).toList();
        int n = exchanges.size();
        long unsent = exchanges.stream()
                .filter(ex -> HttpStatusText.notSent(ex.result()))
                .count();
        long failed = exchanges.stream().filter(ex -> !ex.result().ok()).count() - unsent;
        long testsFailed = exchanges.stream()
                .filter(ex -> ex.result().tests().hasFailures())
                .count();
        String pill;
        Outcome outcome;
        String spoken;
        if (cancelledOf > 0) {
            pill = failed > 0
                    ? tr("httppanel.runList.cancelledFailed", n, cancelledOf, failed)
                    : tr("httppanel.runList.cancelled", n, cancelledOf);
            outcome = failed > 0 ? Outcome.FAILED : Outcome.PARTIAL;
            spoken = HttpStatusText.runAllCancelledSummary(exchanges, cancelledOf)
                    + HttpStatusText.testsFailedSuffix(exchanges);
        } else {
            if (failed > 0 && unsent > 0) {
                pill = tr("httppanel.runList.failedNotSent", failed, unsent);
            } else if (failed > 0) {
                pill = tr("httppanel.runList.failed", failed, n);
            } else if (unsent > 0) {
                pill = tr("httppanel.runList.notSent", unsent, n);
            } else {
                pill = tr("httppanel.runList.ok", n);
            }
            outcome = failed > 0 ? Outcome.FAILED : unsent > 0 ? Outcome.PARTIAL : Outcome.OK;
            spoken = HttpStatusText.runAllSummary(exchanges);
            if (spoken == null) { // a run of one request (only a cancelled one is listed)
                spoken = tr("httppanel.runList.title") + ": " + pill;
            }
        }
        String tests = testsFailed > 0 ? tr("httppanel.runAllTestsFailed", testsFailed) : "";
        String time = tookMs >= 0 ? tr("httppanel.runList.seconds", HttpStatusText.seconds(tookMs / 1000.0)) : "";
        if (!time.isEmpty()) {
            spoken = spoken + " · " + time;
        }
        return new Title(pill, outcome, tests, time, spoken);
    }
}
