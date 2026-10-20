package com.editora.ui;

import java.time.Duration;
import java.time.LocalTime;

import com.editora.sync.SyncReport;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Settings → Sync status pill: one of three states, with its words and its colour.
 *
 * <ul>
 *   <li><b>Connected</b> (green): sync is set up and on, and the last run went well (or none has run yet).
 *   <li><b>Not connected</b> (amber): not set up, turned off, a second Editora on the same configuration, or the
 *       last run could not reach the repository — a network problem that the next run retries by itself.
 *   <li><b>Sync failing</b> (red): the last run failed in a way only the user can fix — the sign-in was refused,
 *       the repository is not there, git is missing, a newer Editora wrote it, a large removal waits for a yes,
 *       or another hard failure.
 * </ul>
 *
 * A run under way keeps the state of the run before it and says "syncing…". Pure.
 */
final class SyncStatus {

    private SyncStatus() {}

    enum Level {
        CONNECTED("settings-git-found"),
        NOT_CONNECTED("settings-git-warn"),
        FAILING("settings-git-missing");

        private final String pillClass;

        Level(String pillClass) {
            this.pillClass = pillClass;
        }

        /** The pill's style class: the green / amber / red of the Settings status pills. */
        String pillClass() {
            return pillClass;
        }

        String label() {
            return tr(
                    switch (this) {
                        case CONNECTED -> "settings.sync.pill.connected";
                        case NOT_CONNECTED -> "settings.sync.pill.notConnected";
                        case FAILING -> "settings.sync.pill.failing";
                    });
        }
    }

    /** The pill: its state, its words, and the words a screen reader says. */
    record Pill(Level level, String text, String accessibleText) {}

    /**
     * The pill for a sync that is {@code connected} (set up and switched on), run by this process when
     * {@code primary}, whose service is in {@code state} (null: no service), at {@code now}.
     */
    static Pill of(boolean connected, boolean primary, SettingsSync.@Nullable State state, LocalTime now) {
        if (!connected || !primary || state == null || state.phase() == SettingsSync.Phase.OFF) {
            return pill(Level.NOT_CONNECTED, null);
        }
        SyncReport last = state.report();
        Level level = levelOf(last);
        String extra;
        if (state.phase() == SettingsSync.Phase.SYNCING) {
            extra = tr("settings.sync.pill.syncing");
        } else if (level == Level.NOT_CONNECTED) {
            extra = tr("settings.sync.pill.retrying");
        } else if (level == Level.FAILING) {
            extra = null; // the reason is in the line under the pill
        } else if (last == null || state.at() == null) {
            extra = tr("settings.sync.pill.notSyncedYet");
        } else {
            extra = ago(state.at(), now);
        }
        return pill(level, extra);
    }

    private static Pill pill(Level level, @Nullable String extra) {
        String text = extra == null ? level.label() : tr("settings.sync.pill.with", level.label(), extra);
        return new Pill(level, text, tr("settings.sync.pill.accessible", text));
    }

    /** The state a finished run leaves (null: none has run yet). */
    static Level levelOf(@Nullable SyncReport report) {
        if (report == null) {
            return Level.CONNECTED;
        }
        return switch (report.status()) {
            case OK, LOCAL_BUSY, CANCELLED -> Level.CONNECTED;
            case FETCH_FAILED, PUSH_FAILED -> needsTheUser(report.detail()) ? Level.FAILING : Level.NOT_CONNECTED;
            case NO_GIT, NEWER_FORMAT, NEEDS_CONFIRMATION, FAILED -> Level.FAILING;
        };
    }

    /**
     * Whether git's error output says the user has to act — the sign-in was refused, or the repository is not
     * there — rather than that the network failed (which the next run retries by itself).
     */
    static boolean needsTheUser(String gitOutput) {
        if (GitAuthFailure.classify(gitOutput) != GitAuthFailure.Kind.NONE) {
            return true;
        }
        String text = gitOutput == null ? "" : gitOutput.toLowerCase(java.util.Locale.ROOT);
        return text.contains("repository not found")
                || text.contains("does not appear to be a git repository")
                || text.contains("does not exist")
                || text.contains("not found") && text.contains("returned error: 404");
    }

    /** "synced just now", "synced 5 min ago", "synced 2 h ago" — from the time of day of the last run. */
    static String ago(LocalTime at, LocalTime now) {
        long minutes = Duration.between(at, now).toMinutes();
        if (minutes < 0) {
            minutes += 24 * 60; // the last run was before midnight
        }
        if (minutes < 1) {
            return tr("settings.sync.pill.justNow");
        }
        return minutes < 60
                ? tr("settings.sync.pill.minutesAgo", minutes)
                : tr("settings.sync.pill.hoursAgo", minutes / 60);
    }
}
