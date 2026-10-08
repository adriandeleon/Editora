package com.editora.ui;

import java.util.List;

import com.editora.i18n.Messages;
import com.editora.sync.SyncCategory;
import com.editora.sync.SyncMerge;
import com.editora.sync.SyncReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What settings sync says about a run: the one-line summary and, for a run that did not finish, why. */
class SettingsSyncTextTest {

    @BeforeAll
    static void messages() {
        Messages.init("en");
    }

    private static SyncReport.Change change(String label) {
        return new SyncReport.Change(SyncCategory.SNIPPETS, label);
    }

    private static SyncReport report(
            SyncReport.Status status,
            List<SyncReport.Change> received,
            List<SyncReport.Change> sent,
            List<SyncReport.Change> conflicts,
            List<SyncReport.Skipped> skipped,
            String detail) {
        return new SyncReport(status, received, sent, conflicts, skipped, detail);
    }

    private static SyncReport failed(SyncReport.Status status, String detail) {
        return report(status, List.of(), List.of(), List.of(), List.of(), detail);
    }

    @Test
    void aRunThatMovedNothingIsUpToDate() {
        SyncReport nothing = report(SyncReport.Status.OK, List.of(), List.of(), List.of(), List.of(), "");
        assertEquals(tr("sync.result.upToDate"), SettingsSync.summary(nothing));
    }

    @Test
    void aRunThatMovedDataCountsBothDirections() {
        SyncReport moved = report(
                SyncReport.Status.OK,
                List.of(change("a"), change("b"), change("c")),
                List.of(change("d")),
                List.of(),
                List.of(),
                "");
        assertEquals(tr("sync.result.counts", 3, 1), SettingsSync.summary(moved));
    }

    @Test
    void conflictsAreNamedUpToFiveAndTheRestElided() {
        SyncReport few = report(
                SyncReport.Status.OK,
                List.of(change("x")),
                List.of(),
                List.of(change("one"), change("two")),
                List.of(),
                "");
        assertEquals(
                tr("sync.result.counts", 1, 0) + ". " + tr("sync.result.conflicts", "one, two"),
                SettingsSync.summary(few));

        SyncReport five = report(
                SyncReport.Status.OK,
                List.of(),
                List.of(),
                List.of(change("1"), change("2"), change("3"), change("4"), change("5")),
                List.of(),
                "");
        assertTrue(SettingsSync.summary(five).endsWith(tr("sync.result.conflicts", "1, 2, 3, 4, 5")));

        SyncReport many = report(
                SyncReport.Status.OK,
                List.of(),
                List.of(),
                List.of(change("1"), change("2"), change("3"), change("4"), change("5"), change("6")),
                List.of(),
                "");
        String summary = SettingsSync.summary(many);
        assertTrue(summary.endsWith(tr("sync.result.conflicts", "1, 2, 3, 4, 5, …")), summary);
        assertFalse(summary.contains("6"));
    }

    @Test
    void filesLeftAloneAreListedByPath() {
        SyncReport skipped = report(
                SyncReport.Status.OK,
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new SyncReport.Skipped("snippets/java.json", SyncMerge.Skip.LOCAL_UNREADABLE),
                        new SyncReport.Skipped("templates/a.json", SyncMerge.Skip.REMOTE_UNREADABLE)),
                "");
        assertEquals(
                tr("sync.result.upToDate") + ". " + tr("sync.result.skipped", "snippets/java.json, templates/a.json"),
                SettingsSync.summary(skipped));
    }

    @Test
    void eachWayARunCanStopHasItsOwnLine() {
        assertEquals(tr("status.sync.noGit"), SettingsSync.problemText(failed(SyncReport.Status.NO_GIT, "")));
        assertEquals(
                tr("status.sync.fetchFailed", "no route to host"),
                SettingsSync.problemText(failed(SyncReport.Status.FETCH_FAILED, "no route to host")));
        assertEquals(
                tr("status.sync.pushFailed", "rejected"),
                SettingsSync.problemText(failed(SyncReport.Status.PUSH_FAILED, "rejected")));
        assertEquals(
                tr("status.sync.newerFormat"), SettingsSync.problemText(failed(SyncReport.Status.NEWER_FORMAT, "")));
        assertEquals(
                tr("status.sync.needsConfirmation", tr("settings.sync.templates")),
                SettingsSync.problemText(failed(SyncReport.Status.NEEDS_CONFIRMATION, "TEMPLATES")));
        assertEquals(
                tr("status.sync.failed", "disk full"),
                SettingsSync.problemText(failed(SyncReport.Status.FAILED, "disk full")));
        assertEquals(
                tr("status.sync.failed", "busy"),
                SettingsSync.problemText(failed(SyncReport.Status.LOCAL_BUSY, "busy")));
    }

    @Test
    void aSignInFailureComesWithWhatToDoAboutIt() {
        String detail = "fatal: Authentication failed for 'https://example.org/me/sync.git/'";
        String text = SettingsSync.problemText(failed(SyncReport.Status.FETCH_FAILED, detail));
        assertEquals(tr("status.sync.fetchFailed", GitAuthFailure.withGuidance(detail)), text);
        assertTrue(text.length() > tr("status.sync.fetchFailed", detail).length(), "guidance is added");
    }

    @Test
    void aCategoryIsNamedAsTheSettingsPageNamesIt() {
        assertEquals(tr("settings.sync.snippets"), SettingsSync.categoryName("SNIPPETS"));
        assertEquals(tr("settings.sync.abbreviations"), SettingsSync.categoryName("ABBREVIATIONS"));
        assertEquals(tr("settings.sync.templates"), SettingsSync.categoryName("TEMPLATES"));
        assertEquals(tr("settings.sync.dictionary"), SettingsSync.categoryName("DICTIONARY"));
        assertEquals("SOMETHING_NEWER", SettingsSync.categoryName("SOMETHING_NEWER"), "an unknown one by its name");
    }
}
