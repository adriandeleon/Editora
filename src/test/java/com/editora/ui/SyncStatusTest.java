package com.editora.ui;

import java.time.LocalTime;
import java.util.List;

import com.editora.i18n.Messages;
import com.editora.sync.SyncReport;
import com.editora.sync.SyncReport.Status;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Sync page's status pill: which state each situation is, and what the pill says. */
class SyncStatusTest {

    private static final LocalTime NOW = LocalTime.of(10, 30);

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    private static SyncReport report(Status status, String detail) {
        return new SyncReport(status, List.of(), List.of(), List.of(), List.of(), detail);
    }

    private static SettingsSync.State state(SettingsSync.Phase phase, SyncReport report, LocalTime at) {
        return new SettingsSync.State(phase, report, at);
    }

    @Test
    void eachRunOutcomeHasItsState() {
        assertEquals(SyncStatus.Level.CONNECTED, SyncStatus.levelOf(null), "connected, nothing run yet");
        assertEquals(SyncStatus.Level.CONNECTED, SyncStatus.levelOf(report(Status.OK, "")));
        assertEquals(SyncStatus.Level.CONNECTED, SyncStatus.levelOf(report(Status.LOCAL_BUSY, "")));
        assertEquals(
                SyncStatus.Level.NOT_CONNECTED,
                SyncStatus.levelOf(
                        report(Status.FETCH_FAILED, "fatal: unable to access 'https://x/': Could not resolve host: x")),
                "offline: it retries by itself");
        assertEquals(
                SyncStatus.Level.NOT_CONNECTED,
                SyncStatus.levelOf(report(Status.PUSH_FAILED, "the repository kept changing")));
        assertEquals(
                SyncStatus.Level.FAILING,
                SyncStatus.levelOf(
                        report(Status.FETCH_FAILED, "fatal: could not read Username: terminal prompts disabled")),
                "the sign-in needs the user");
        assertEquals(
                SyncStatus.Level.FAILING,
                SyncStatus.levelOf(report(Status.FETCH_FAILED, "git@github.com: Permission denied (publickey).")));
        assertEquals(
                SyncStatus.Level.FAILING,
                SyncStatus.levelOf(report(Status.FETCH_FAILED, "remote: Repository not found.")));
        for (Status hard : List.of(Status.NO_GIT, Status.NEWER_FORMAT, Status.NEEDS_CONFIRMATION, Status.FAILED)) {
            assertEquals(SyncStatus.Level.FAILING, SyncStatus.levelOf(report(hard, "x")), hard.name());
        }
    }

    @Test
    void thePillSaysTheStateAndWhen() {
        SyncStatus.Pill off = SyncStatus.of(false, true, state(SettingsSync.Phase.OFF, null, null), NOW);
        assertEquals(SyncStatus.Level.NOT_CONNECTED, off.level());
        assertEquals(tr("settings.sync.pill.notConnected"), off.text());
        assertEquals(tr("settings.sync.pill.accessible", off.text()), off.accessibleText());
        assertEquals(
                SyncStatus.Level.NOT_CONNECTED,
                SyncStatus.of(true, false, state(SettingsSync.Phase.IDLE, null, null), NOW)
                        .level(),
                "a second Editora on the same configuration does not sync");

        SyncStatus.Pill ok = SyncStatus.of(
                true, true, state(SettingsSync.Phase.IDLE, report(Status.OK, ""), LocalTime.of(10, 25)), NOW);
        assertEquals(SyncStatus.Level.CONNECTED, ok.level());
        assertEquals("Connected · synced 5 min ago", ok.text());
        assertEquals("settings-git-found", ok.level().pillClass());

        SyncStatus.Pill syncing = SyncStatus.of(
                true, true, state(SettingsSync.Phase.SYNCING, report(Status.OK, ""), LocalTime.of(10, 25)), NOW);
        assertEquals("Connected · syncing…", syncing.text(), "a run under way keeps the state before it");

        SyncStatus.Pill failing =
                SyncStatus.of(true, true, state(SettingsSync.Phase.PROBLEM, report(Status.NO_GIT, ""), NOW), NOW);
        assertEquals(SyncStatus.Level.FAILING, failing.level());
        assertEquals("Sync failing", failing.text());
        assertEquals("settings-git-missing", failing.level().pillClass());

        SyncStatus.Pill offline = SyncStatus.of(
                true,
                true,
                state(SettingsSync.Phase.PROBLEM, report(Status.FETCH_FAILED, "Could not resolve host"), NOW),
                NOW);
        assertEquals(SyncStatus.Level.NOT_CONNECTED, offline.level());
        assertEquals("settings-git-warn", offline.level().pillClass());
        assertEquals(
                tr("settings.sync.pill.with", tr("settings.sync.pill.notConnected"), tr("settings.sync.pill.retrying")),
                offline.text());

        assertEquals(
                "Connected · not synced yet",
                SyncStatus.of(true, true, state(SettingsSync.Phase.IDLE, null, null), NOW)
                        .text());
    }

    @Test
    void howLongAgo() {
        assertEquals("synced just now", SyncStatus.ago(LocalTime.of(10, 29, 40), NOW));
        assertEquals("synced 59 min ago", SyncStatus.ago(LocalTime.of(9, 31), NOW));
        assertEquals("synced 2 h ago", SyncStatus.ago(LocalTime.of(8, 0), NOW));
        assertEquals("synced 40 min ago", SyncStatus.ago(LocalTime.of(23, 50), LocalTime.of(0, 30)), "over midnight");
    }
}
