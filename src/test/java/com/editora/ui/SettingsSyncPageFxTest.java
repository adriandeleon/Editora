package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.animation.Animation;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import com.editora.config.Settings;
import com.editora.git.QuietGit;
import com.editora.sync.FileSyncTarget;
import com.editora.sync.SyncCategory;
import com.editora.sync.SyncEngine;
import com.editora.sync.SyncReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Settings ▸ Sync in a real window, against a repository in a temp folder: connecting (and what is asked
 * first), the page's switches, Sync Now, and disconnecting. The other computer is a plain config directory
 * driven by the engine.
 */
@Tag("fx")
class SettingsSyncPageFxTest {

    @TempDir
    Path tmp;

    private Path remote;
    private Path other;
    private FxWindowFixture fx;
    private Settings settings;
    private SettingsSync sync;
    private SettingsWindow window;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(QuietGit.available(), "git is not installed");
        remote = newRemote("remote.git");
        other = Files.createDirectories(tmp.resolve("other"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fx != null) {
            FxTestSupport.runOnFx(() -> {
                settings.setSyncAuto(false);
                settings.setSyncEnabled(false);
                sync.settingsChanged();
                FxTestSupport.<Stage>field(window, "stage").hide();
            });
            awaitIdle();
            fx.dispose();
        }
    }

    private Path newRemote(String name) throws Exception {
        Path repo = tmp.resolve(name);
        git(tmp, "init", "-q", "--bare", repo.toString());
        git(repo, "symbolic-ref", "HEAD", "refs/heads/main");
        return repo;
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        Process p = new ProcessBuilder(argv)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), out);
    }

    /** The other computer syncs: pushes what it has, receives what is there. */
    private SyncReport otherSyncs() {
        QuietGit git = new QuietGit(other.resolve("sync").resolve("repo"), false);
        return new SyncEngine(git, remote.toString(), "main", new FileSyncTarget(other), "other")
                .run(EnumSet.allOf(SyncCategory.class), false);
    }

    private void open(Path configDir) throws Exception {
        fx = configDir == null ? FxWindowFixture.create() : FxWindowFixture.create(configDir, shared -> {});
        settings = fx.shared.getSettings();
        sync = FxTestSupport.callOnFx(() -> fx.windowManager.settingsSync());
        window = FxTestSupport.field(fx.controller, "settingsWindow");
        FxTestSupport.runOnFx(() -> {
            settings.setSyncAuto(false);
            window.showSync(FxTestSupport.field(fx.controller, "stage"));
        });
    }

    private <T> T control(String name) {
        return FxTestSupport.field(window, name);
    }

    private String status() throws Exception {
        return FxTestSupport.callOnFx(
                () -> this.<Label>control("syncStateLabel").getText());
    }

    /** Waits until no connect attempt and no sync run is in progress. */
    private void awaitIdle() throws Exception {
        SettingsRig.awaitFx(
                "settings sync to come to rest",
                () -> control("syncConnectMessage") == null
                        && sync.state().phase() != SettingsSync.Phase.SYNCING
                        && !FxTestSupport.<Boolean>field(sync, "running"));
        FxTestSupport.drainFx();
    }

    private void connect(String url, String branch) throws Exception {
        FxTestSupport.runOnFx(() -> {
            SettingsRig.typeAndEnter(control("syncUrlField"), url);
            SettingsRig.typeAndEnter(control("syncBranchField"), branch);
            this.<Button>control("syncConnectButton").fire();
        });
    }

    @Test
    void connectNeedsARepositoryAndABranchNameBeforeItLooksAtAnything() throws Exception {
        open(null);
        assertEquals(tr("sync.state.off"), status());

        connect("", "main");
        assertEquals(tr("status.sync.invalidRepository"), status());

        connect(remote.toString(), "not..a/branch");
        assertEquals(tr("status.sync.invalidRepository"), status());

        connect("--upload-pack=evil", "main"); // an option is not a repository
        assertEquals(tr("status.sync.invalidRepository"), status());

        assertFalse(FxTestSupport.callOnFx(settings::isSyncEnabled));
        assertFalse(Files.exists(fx.configDir.resolve("sync")), "nothing was fetched");
        // What was typed is kept for the next attempt.
        assertEquals("--upload-pack=evil", FxTestSupport.callOnFx(settings::getSyncRepoUrl));
    }

    @Test
    void aRepositoryThatCannotBeReachedIsReportedAndSyncStaysOff() throws Exception {
        open(null);
        connect(tmp.resolve("gone.git").toString(), "main");
        FxTestSupport.runOnFx(() -> {
            assertEquals(
                    tr("sync.state.checking"),
                    this.<Label>control("syncStateLabel").getText());
            assertTrue(this.<Button>control("syncConnectButton").isDisable(), "one attempt at a time");
        });
        awaitIdle();

        String prefix = tr("status.sync.fetchFailed", "\u0000").split("\u0000")[0];
        assertTrue(status().startsWith(prefix), status());
        FxTestSupport.runOnFx(() -> {
            assertFalse(settings.isSyncEnabled());
            assertFalse(this.<Button>control("syncConnectButton").isDisable(), "and it can be tried again");
            assertTrue(this.<Button>control("syncConnectButton").isVisible());
            assertFalse(this.<Button>control("syncDisconnectButton").isVisible());
            assertTrue(this.<Button>control("syncNowButton").isDisable());
        });
    }

    @Test
    void connectingToARepositoryThatHoldsDataAsksBeforeBringingItIn() throws Exception {
        Files.writeString(other.resolve("dictionary.txt"), "zzfromother\n");
        assertTrue(otherSyncs().ok());
        open(null);

        CompletableFuture<SettingsRig.Shown> declined = SettingsRig.answerNextDialog(ButtonBar.ButtonData.CANCEL_CLOSE);
        connect(remote.toString(), "main");
        SettingsRig.Shown asked = declined.get(60, TimeUnit.SECONDS);
        awaitIdle();
        assertEquals(tr("dialog.sync.connect.header"), asked.header());
        assertEquals(tr("dialog.sync.connect.content", 1, 0, 0), asked.content());
        FxTestSupport.runOnFx(() -> {
            assertFalse(settings.isSyncEnabled(), "declined: not connected");
            assertFalse(fx.shared.getUserDictionary().contains("zzfromother"));
            assertFalse(this.<Button>control("syncConnectButton").isDisable());
            assertEquals(
                    tr("sync.state.off"), this.<Label>control("syncStateLabel").getText());
        });

        CompletableFuture<SettingsRig.Shown> accepted = SettingsRig.answerNextDialog(ButtonBar.ButtonData.OK_DONE);
        FxTestSupport.runOnFx(() -> this.<Button>control("syncConnectButton").fire());
        accepted.get(60, TimeUnit.SECONDS);
        awaitIdle();

        FxTestSupport.runOnFx(() -> {
            assertTrue(settings.isSyncEnabled());
            assertEquals(
                    SettingsSync.Phase.IDLE,
                    sync.state().phase(),
                    sync.state().report().detail());
            assertTrue(fx.shared.getUserDictionary().contains("zzfromother"), "the first sync ran");
            assertTrue(this.<TextField>control("syncUrlField").isDisable(), "the repository is fixed while connected");
            assertTrue(this.<TextField>control("syncBranchField").isDisable());
            assertFalse(this.<Button>control("syncConnectButton").isVisible());
            assertTrue(this.<Button>control("syncDisconnectButton").isVisible());
            assertFalse(this.<Button>control("syncNowButton").isDisable());
            assertFalse(this.<Button>control("syncAnywayButton").isVisible());
            assertEquals(sync.stateText(), this.<Label>control("syncStateLabel").getText());
            assertTrue(sync.stateText().contains(tr("sync.result.counts", 1, 0)), sync.stateText());
        });
        assertTrue(Files.readString(fx.configDir.resolve("settings.json")).contains("\"syncEnabled\""));

        // Sync Now sends what changed here.
        FxTestSupport.runOnFx(() -> {
            fx.shared.addUserWord("zzfromhere");
            this.<Button>control("syncNowButton").fire();
            assertEquals(SettingsSync.Phase.SYNCING, sync.state().phase());
            assertEquals(
                    tr("sync.state.syncing"),
                    this.<Label>control("syncStateLabel").getText());
            assertTrue(this.<Button>control("syncNowButton").isDisable(), "not twice at once");
        });
        awaitIdle();
        assertEquals(
                1, FxTestSupport.callOnFx(() -> sync.state().report().sent().size()));
        assertTrue(otherSyncs().ok());
        assertEquals("zzfromhere\nzzfromother\n", Files.readString(other.resolve("dictionary.txt")));

        // Disconnect: sync is off, the data stays, the repository can be changed again.
        FxTestSupport.runOnFx(() -> this.<Button>control("syncDisconnectButton").fire());
        awaitIdle();
        FxTestSupport.runOnFx(() -> {
            assertFalse(settings.isSyncEnabled());
            assertEquals(SettingsSync.Phase.OFF, sync.state().phase());
            assertEquals(
                    tr("sync.state.off"), this.<Label>control("syncStateLabel").getText());
            assertFalse(this.<TextField>control("syncUrlField").isDisable());
            assertTrue(this.<Button>control("syncConnectButton").isVisible());
            assertTrue(fx.shared.getUserDictionary().containsAll(List.of("zzfromother", "zzfromhere")));
        });
    }

    @Test
    void connectingToAnEmptyRepositoryAsksNothingAndSendsWhatIsHere() throws Exception {
        open(null);
        FxTestSupport.runOnFx(() -> fx.shared.addUserWord("zzmine"));

        connect(remote.toString(), "main");
        awaitIdle();

        FxTestSupport.runOnFx(() -> {
            assertTrue(settings.isSyncEnabled());
            assertEquals(
                    SettingsSync.Phase.IDLE,
                    sync.state().phase(),
                    sync.state().report().detail());
        });
        assertTrue(otherSyncs().ok());
        assertTrue(Files.readString(other.resolve("dictionary.txt")).contains("zzmine"));
    }

    @Test
    void theCategoryAndTimingSwitchesAreSavedAndReadByTheService() throws Exception {
        open(null);
        FxTestSupport.runOnFx(() -> {
            settings.setSyncRepoUrl(remote.toString());
            settings.setSyncEnabled(true);
            sync.settingsChanged();
            window.showSync(FxTestSupport.field(fx.controller, "stage"));
            assertEquals(
                    tr("sync.state.never"),
                    this.<Label>control("syncStateLabel").getText());

            for (String name : List.of(
                    "syncSnippetsCheck", "syncAbbreviationsCheck", "syncTemplatesCheck", "syncDictionaryCheck")) {
                CheckBox check = control(name);
                assertTrue(check.isSelected(), name + " is on by default");
                check.setSelected(false);
            }
            assertFalse(settings.isSyncSnippets() || settings.isSyncAbbreviations());
            assertFalse(settings.isSyncTemplates() || settings.isSyncDictionary());
            this.<CheckBox>control("syncDictionaryCheck").setSelected(true);
            assertTrue(settings.isSyncDictionary());

            CheckBox auto = control("syncAutoCheck");
            Spinner<Integer> minutes = control("syncIntervalSpinner");
            assertFalse(auto.isSelected());
            assertTrue(minutes.isDisable(), "no interval while automatic sync is off");
            auto.setSelected(true);
            assertTrue(settings.isSyncAuto());
            assertFalse(minutes.isDisable());
            minutes.getValueFactory().setValue(7);
            assertEquals(7, settings.getSyncIntervalMinutes());
            assertEquals(7, (int) FxTestSupport.<Integer>field(sync, "timerMinutes"), "the timer runs at it");
            minutes.getEditor().setText("0");
            minutes.getEditor().fireEvent(new javafx.event.ActionEvent());
            assertEquals(1, settings.getSyncIntervalMinutes(), "never below a minute");

            // With automatic sync on, a local change schedules a run instead of waiting for the interval.
            Animation dirty = FxTestSupport.field(sync, "dirty");
            assertNotEquals(Animation.Status.RUNNING, dirty.getStatus());
            fx.shared.addUserWord("zzchanged");
            sync.markDirty();
            assertEquals(Animation.Status.RUNNING, dirty.getStatus());

            auto.setSelected(false);
            assertFalse(settings.isSyncAuto());
            assertTrue(minutes.isDisable());
            assertEquals(0, (int) FxTestSupport.<Integer>field(sync, "timerMinutes"), "and the timer is gone");
            assertNotEquals(Animation.Status.RUNNING, dirty.getStatus(), "with the pending run");
            sync.markDirty();
            assertNotEquals(Animation.Status.RUNNING, dirty.getStatus(), "a change waits for Sync Now");
        });
        String saved = Files.readString(fx.configDir.resolve("settings.json"));
        assertTrue(saved.matches("(?s).*\"syncSnippets\"\\s*:\\s*false.*"), "the choice is on disk");

        // Only the dictionary is ticked: a snippet file here stays here.
        Files.createDirectories(fx.configDir.resolve("snippets"));
        Files.writeString(
                fx.configDir.resolve("snippets/plaintext.json"), "{ \"g\": { \"prefix\": \"zzg\", \"body\": \"x\" } }");
        FxTestSupport.runOnFx(() -> this.<Button>control("syncNowButton").fire());
        awaitIdle();
        assertEquals(
                SettingsSync.Phase.IDLE,
                FxTestSupport.callOnFx(() -> sync.state().phase()));
        assertTrue(otherSyncs().ok());
        assertTrue(Files.readString(other.resolve("dictionary.txt")).contains("zzchanged"));
        assertFalse(Files.exists(other.resolve("snippets/plaintext.json")));
    }

    @Test
    void aRunThatEndsAfterSyncWasSwitchedOffLeavesItOff() throws Exception {
        open(null);
        FxTestSupport.runOnFx(() -> {
            settings.setSyncRepoUrl(remote.toString());
            settings.setSyncEnabled(true);
            sync.settingsChanged();
            sync.syncNow();
            sync.syncNow(); // asked again while it runs: remembered, not started twice
            assertEquals(SettingsSync.Phase.SYNCING, sync.state().phase());
            assertTrue(FxTestSupport.<Boolean>field(sync, "again"));
            settings.setSyncEnabled(false); // switched off before the run comes back
        });
        awaitIdle();
        FxTestSupport.runOnFx(() -> {
            assertEquals(SettingsSync.Phase.OFF, sync.state().phase());
            assertFalse(FxTestSupport.<Boolean>field(sync, "again"), "nothing is left to repeat");
            sync.syncAllowingLargeRemoval(); // not connected: nothing to repeat
            assertEquals(SettingsSync.Phase.OFF, sync.state().phase());
        });
    }

    @Test
    void aSecondRequestDuringARunIsRepeatedAfterIt() throws Exception {
        open(null);
        FxTestSupport.runOnFx(() -> {
            settings.setSyncRepoUrl(remote.toString());
            settings.setSyncEnabled(true);
            sync.settingsChanged();
            sync.syncNow();
            sync.syncAllowingLargeRemoval();
            assertTrue(FxTestSupport.<Boolean>field(sync, "again"));
        });
        awaitIdle();
        FxTestSupport.runOnFx(() -> {
            assertEquals(
                    SettingsSync.Phase.IDLE,
                    sync.state().phase(),
                    sync.state().report().detail());
            Animation dirty = FxTestSupport.field(sync, "dirty");
            assertEquals(Animation.Status.RUNNING, dirty.getStatus(), "the repeat is scheduled");
            dirty.stop();

            sync.syncAllowingLargeRemoval(); // connected: a run that may remove most of a category
            assertEquals(SettingsSync.Phase.SYNCING, sync.state().phase());
        });
        awaitIdle();
        assertEquals(
                SettingsSync.Phase.IDLE,
                FxTestSupport.callOnFx(() -> sync.state().phase()));
    }

    /** A config folder kept in the user's own dotfiles repository: the clone must not land inside it. */
    @Test
    void aConfigFolderInsideAnotherRepositoryIsRefusedWithTheWayOut() throws Exception {
        Path dotfiles = Files.createDirectories(tmp.resolve("dotfiles"));
        git(dotfiles, "init", "-q");
        Path config = Files.createDirectories(dotfiles.resolve("editora"));
        assertTrue(SettingsSync.insideAnotherRepository(config));
        open(config);

        connect(remote.toString(), "main");
        awaitIdle();

        assertEquals(tr("status.sync.failed", tr("status.sync.insideRepository", config)), status());
        assertFalse(FxTestSupport.callOnFx(settings::isSyncEnabled));
        assertFalse(Files.exists(config.resolve("sync").resolve("repo")));

        // The way out the message names: ignore sync/ in that repository.
        Files.writeString(dotfiles.resolve(".gitignore"), "sync/\n");
        assertFalse(SettingsSync.insideAnotherRepository(config));
    }

    @Test
    void aFolderThatIsNotInARepositoryOrDoesNotExistIsNotInsideOne() throws Exception {
        assertFalse(SettingsSync.insideAnotherRepository(tmp.resolve("no-such-folder")));
        Path plain = Files.createDirectories(tmp.resolve("plain"));
        assumeTrue(
                !new QuietGit(plain, false).run("rev-parse", "--show-toplevel").ok(), "the temp folder is tracked");
        assertFalse(SettingsSync.insideAnotherRepository(plain));
    }

    @Test
    void theIntervalTimerOnlySyncsWhileAWindowHasTheFocus() throws Exception {
        open(null);
        FxTestSupport.runOnFx(() -> {
            settings.setSyncRepoUrl(remote.toString());
            settings.setSyncEnabled(true);
            sync.settingsChanged();
            boolean focused = fx.windowManager.anyWindowFocused();
            FxTestSupport.invoke(sync, "tick");
            assertEquals(
                    focused ? SettingsSync.Phase.SYNCING : SettingsSync.Phase.IDLE,
                    sync.state().phase(),
                    "an editor nobody is looking at does not talk to the network");
        });
        awaitIdle();
    }
}
