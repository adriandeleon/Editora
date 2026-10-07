package com.editora.ui;

import java.util.List;

import com.editora.doctor.DoctorCheck;
import com.editora.doctor.DoctorStatus;
import com.editora.github.GitHubService.Availability;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** What Settings' GitHub page and the Doctor's gh row say for each probe result — the same probe for both. */
@Tag("fx")
class GitHubStatusRowsFxTest {

    private static final String NEW = "gh version 2.96.0 (2026-07-02)";
    private static final String OLD = "gh version 2.40.1 (2023-12-13)";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static Availability found(String version, String auth) {
        return new Availability(
                true,
                "SIGNED_IN".equals(auth),
                version,
                com.editora.github.GitHubService.AuthState.valueOf(auth),
                List.of(),
                "");
    }

    /** G1: offline is neither "not authenticated" nor a red row. G19: an old gh names what it lacks. */
    @Test
    void theSettingsRowTellsTheCasesApart() {
        assertArrayEquals(
                new String[] {"settings-git-missing", tr("settings.github.notFound")},
                SettingsWindow.githubStatus(Availability.UNKNOWN));
        assertArrayEquals(
                new String[] {"settings-git-found", tr("settings.github.found", NEW)},
                SettingsWindow.githubStatus(found(NEW, "SIGNED_IN")));
        assertArrayEquals(
                new String[] {"settings-git-found", tr("settings.github.foundUnverified", NEW)},
                SettingsWindow.githubStatus(found(NEW, "UNVERIFIED")));
        assertArrayEquals(
                new String[] {"settings-git-missing", tr("settings.github.foundNoAuth")},
                SettingsWindow.githubStatus(found(NEW, "SIGNED_OUT")));
        assertArrayEquals(
                new String[] {"settings-git-missing", tr("settings.github.foundRejected")},
                SettingsWindow.githubStatus(found(NEW, "REJECTED")));
        assertArrayEquals(
                new String[] {"settings-git-missing", tr("settings.github.foundOld", "2.40.1", "2.50")},
                SettingsWindow.githubStatus(found(OLD, "SIGNED_IN")));
    }

    @Test
    void theDoctorRowAgreesWithTheIntegration() {
        DoctorCheck base = DoctorCheck.checking("github", "vcs", "GitHub CLI", "gh");

        assertEquals(
                DoctorStatus.MISSING,
                DoctorCoordinator.ghCheck(base, "gh", Availability.UNKNOWN).status());
        assertEquals(
                DoctorStatus.MISSING,
                DoctorCoordinator.ghCheck(base, "gh", null).status());
        assertEquals(
                DoctorStatus.OK,
                DoctorCoordinator.ghCheck(base, "gh", found(NEW, "SIGNED_IN")).status());

        DoctorCheck offline = DoctorCoordinator.ghCheck(base, "gh", found(NEW, "UNVERIFIED"));
        assertEquals(DoctorStatus.WARN, offline.status());
        assertEquals("doctor.tip.ghUnverified", offline.tipKey(), "not \"run gh auth login\"");

        assertEquals(
                "doctor.tip.ghAuth",
                DoctorCoordinator.ghCheck(base, "gh", found(NEW, "SIGNED_OUT")).tipKey());
        assertEquals(
                "doctor.tip.ghAuth",
                DoctorCoordinator.ghCheck(base, "gh", found(NEW, "REJECTED")).tipKey());

        DoctorCheck old = DoctorCoordinator.ghCheck(base, "gh", found(OLD, "SIGNED_IN"));
        assertEquals(DoctorStatus.WARN, old.status());
        assertEquals("doctor.tip.ghOld", old.tipKey());
        assertEquals(List.of("2.50"), old.tipArgs());
    }
}
