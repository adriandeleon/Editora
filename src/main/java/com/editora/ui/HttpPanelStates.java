package com.editora.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.editora.http.HttpFile;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The words of the response view's states before and between responses ({@link HttpClientPanel}): how a request is
 * named in the empty state and while it is sent, and the one-line "Previous: 200 OK · 16:42:31 · getUser" row that
 * stands for the response shown before a run. Pure apart from the message catalog — tested by {@code
 * HttpPanelStatesTest}.
 */
final class HttpPanelStates {

    private HttpPanelStates() {}

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * How a request is named to the user: its {@code ###} title ({@code titleLine} is the line its section starts
     * with, null when unknown), else its {@code # @name} ({@code name}), else its method and URL as written.
     */
    static String requestLabel(@Nullable String titleLine, @Nullable String name, String method, String url) {
        String title = titleLine == null ? null : HttpFile.separatorTitle(titleLine);
        if (title != null) {
            return title;
        }
        if (name != null && !name.isBlank()) {
            return name.strip();
        }
        String m = method == null ? "" : method.strip();
        String u = url == null ? "" : url.strip();
        return m.isEmpty() ? u : u.isEmpty() ? m : m + " " + u;
    }

    /**
     * How a request being sent is named on the status line ("Sending Get one user…"): its {@code ###} title, else its
     * {@code # @name}, else {@code resolved} — its method and URL with the variables filled in.
     */
    static String runLabel(@Nullable String titleLine, @Nullable String name, String resolved) {
        String title = titleLine == null ? null : HttpFile.separatorTitle(titleLine);
        if (title != null) {
            return title;
        }
        return name != null && !name.isBlank() ? name.strip() : resolved == null ? "" : resolved;
    }

    /**
     * The row that stands for {@code entry} — the response shown before a run — while the run is sent and after it is
     * cancelled: its status, the time it was sent and its request's name ({@code Previous: 200 OK · 16:42:31 ·
     * getUser}), or its method and URL with any secret it read masked.
     */
    static String previousLine(HttpHistoryEntry entry, ZoneId zone) {
        String time = TIME.format(Instant.ofEpochMilli(entry.at()).atZone(zone));
        String who = entry.name() != null
                ? entry.name()
                : HttpSecretMask.mask(entry.exchange().label(), entry.secretValues());
        return tr("httppanel.previous", HttpStatusText.pill(entry.result()), time, who);
    }

    /** Seconds since {@code sinceNanos} ({@link System#nanoTime}), with one decimal in the UI language. */
    static String secondsSince(long sinceNanos, long nowNanos) {
        return HttpStatusText.seconds(Math.max(0, nowNanos - sinceNanos) / 1_000_000_000.0);
    }
}
