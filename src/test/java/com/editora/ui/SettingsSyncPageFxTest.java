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
        workerDone();
        SettingsRig.awaitFx(
                "settings sync to come to rest",
                () -> control("syncConnectMessage") == null
                        && sync.state().phase() != SettingsSync.Phase.SYNCING
                        && !FxTestSupport.<Boolean>field(sync, "running"));
        workerDone(); // a disconnect deletes the clone there
        FxTestSupport.drainFx();
    }

    /**
     * Waits for what the sync worker was given so far. A connect's look at the repository is not a sync run —
     * nothing above waits for it — and after Cancel it still fetches into {@code sync/repo}: the temp folder
     * deleted under it failed with "directory not empty" on macOS.
     */
    private void workerDone() throws Exception {
        java.util.concurrent.ExecutorService worker = FxTestSupport.field(sync, "worker");
        worker.submit(() -> {}).get(60, TimeUnit.SECONDS); // one thread: everything queued before it has run
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
        assertEquals(tr("settings.sync.branch.invalid"), status(), "the field says it too, and keeps main");

        connect("--upload-pack=evil", "main"); // an option is not a repository
        assertEquals(tr("status.sync.invalidRepository"), status());

        assertFalse(FxTestSupport.callOnFx(settings::isSyncEnabled));
        assertFalse(Files.exists(fx.configDir.resolve("sync")), "nothing was fetched");
        // What was typed is kept for the next attempt.
        assertEquals("--upload-pack=evil", FxTestSupport.callOnFx(settings::getSyncRepoUrl));
    }

    @Test
    void whyConnectWasRefusedStaysUntilTheRepositoryIsEdited() throws Exception {
        open(null);
        connect("", "main");
        assertEquals(tr("status.sync.invalidRepository"), status());

        // A refresh from elsewhere — the GitHub sign-in check finishing, the service reporting its state —
        // used to put "Not connected." back before the message could be read.
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(window, "refreshSyncControls"));
        assertEquals(tr("status.sync.invalidRepository"), status());

        FxTestSupport.runOnFx(() -> SettingsRig.typeAndEnter(control("syncUrlField"), remote.toString()));
        assertEquals(tr("sync.state.off"), status(), "editing the repository clears it");
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
                    "syncSnippetsCheck",
                    "syncAbbreviationsCheck",
                    "syncTemplatesCheck",
                    "syncDictionaryCheck",
                    "syncKeybindingsCheck")) {
                CheckBox check = control(name);
                assertTrue(check.isSelected(), name + " is on by default");
                check.setSelected(false);
            }
            assertFalse(settings.isSyncSnippets() || settings.isSyncAbbreviations());
            assertFalse(settings.isSyncTemplates() || settings.isSyncDictionary() || settings.isSyncKeybindings());
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
            assertEquals(7, settings.getSyncIntervalMinutes(), "never below a minute: 0 is refused, not saved");
            assertTrue(minutes.getEditor().getPseudoClassStates().contains(atlantafx.base.theme.Styles.STATE_DANGER));

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

    /**
     * A password or token pasted into the repository URL used to be saved as typed — in plain text in
     * settings.json, its exports and the clone's .git/config. The field now refuses it and says why.
     */
    @Test
    void aRepositoryUrlWithATokenIsRefusedWithTheReason() throws Exception {
        open(null);
        FxTestSupport.runOnFx(() -> {
            TextField url = FxTestSupport.field(window, "syncUrlField");
            Label state = FxTestSupport.field(window, "syncStateLabel");
            url.setText("https://me:ghp_FAKEFAKEFAKE@github.com/me/editora-sync.git");
            url.fireEvent(new javafx.event.ActionEvent());
            assertEquals("", settings.getSyncRepoUrl(), "nothing is saved");
            assertTrue(url.getPseudoClassStates().contains(atlantafx.base.theme.Styles.STATE_DANGER));
            assertEquals(tr("settings.sync.repo.credentials"), url.getTooltip().getText());
            assertEquals(tr("settings.sync.repo.credentials"), state.getText());
            FxTestSupport.<Button>field(window, "syncConnectButton").fire();
            assertFalse(settings.isSyncEnabled(), "Connect does not reach for the stored URL instead");

            url.setText("git@github.com:me/editora-sync.git");
            assertFalse(url.getPseudoClassStates().contains(atlantafx.base.theme.Styles.STATE_DANGER));
            url.fireEvent(new javafx.event.ActionEvent());
            assertEquals("git@github.com:me/editora-sync.git", settings.getSyncRepoUrl());
        });
    }

    /** A branch git would refuse used to be saved without a word, failing only at Connect. */
    @Test
    void aBranchNameGitWouldRefuseIsNotSaved() throws Exception {
        open(null);
        FxTestSupport.runOnFx(() -> {
            TextField branch = control("syncBranchField");
            SettingsRig.typeAndEnter(branch, "a..b");
            assertEquals("main", settings.getSyncBranch());
            assertTrue(branch.getPseudoClassStates().contains(atlantafx.base.theme.Styles.STATE_DANGER));
            assertEquals(tr("settings.sync.branch.invalid"), branch.getTooltip().getText());
            SettingsRig.typeAndEnter(branch, "editora/sync");
            assertEquals("editora/sync", settings.getSyncBranch());
            assertTrue(SettingsRig.texts(FxTestSupport.<Stage>field(window, "stage")
                            .getScene()
                            .getRoot())
                    .contains(tr("settings.sync.notSynced")));
        });
    }

    /** Connect's look at the repository could take minutes with no way out; Cancel stops waiting. */
    @Test
    void connectCanBeCancelledWhileItLooksAtTheRepository() throws Exception {
        open(null);
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService worker = FxTestSupport.field(sync, "worker");
        worker.execute(
                () -> { // stands for a slow network: the look waits behind this
                    try {
                        hold.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        connect(remote.toUri().toString(), "main");
        FxTestSupport.runOnFx(() -> {
            Button cancel = control("syncCancelButton");
            assertTrue(cancel.isVisible(), "offered while it looks");
            cancel.fire();
            assertFalse(cancel.isVisible());
            assertEquals(
                    tr("settings.sync.cancelled"),
                    this.<Label>control("syncStateLabel").getText());
            assertFalse(this.<Button>control("syncConnectButton").isDisabled(), "Connect can be pressed again");
        });
        hold.countDown();
        awaitIdle();
        assertFalse(settings.isSyncEnabled(), "the look that finished after Cancel connects nothing");
    }

    /**
     * A URL stored with a token before the field refused them stayed as it was, with nothing on the page but a
     * red field. The page now offers, once, to take the secret out of the stored URL.
     */
    @Test
    void aTokenAlreadyInTheStoredUrlIsOfferedForRemoval() throws Exception {
        open(null);
        FxTestSupport.runOnFx(() -> {
            settings.setSyncRepoUrl("https://me:ghp_FAKEFAKEFAKE@github.com/me/editora-sync.git");
            window.showSync(FxTestSupport.field(fx.controller, "stage"));
            javafx.scene.layout.VBox warning = control("syncCredentialsWarning");
            assertTrue(warning.isVisible());
            assertTrue(SettingsRig.texts(warning).contains(tr("settings.sync.credentials.stored")));
            ((Button) SettingsRig.button(warning, tr("settings.sync.credentials.remove"))).fire();
            assertEquals("https://me@github.com/me/editora-sync.git", settings.getSyncRepoUrl());
            assertEquals(
                    "https://me@github.com/me/editora-sync.git",
                    this.<TextField>control("syncUrlField").getText());
            assertFalse(warning.isVisible());
            assertEquals(
                    tr("settings.sync.credentials.removed"),
                    this.<Label>control("syncStateLabel").getText());
        });
        fx.shared.flushWrites();
        assertFalse(Files.readString(fx.configDir.resolve("settings.json")).contains("ghp_FAKE"), "saved");
    }

    @Test
    void keepingTheStoredTokenIsAskedOnlyOnce() throws Exception {
        open(null);
        FxTestSupport.runOnFx(() -> {
            settings.setSyncRepoUrl("https://ghp_FAKEFAKEFAKE0000@github.com/me/editora-sync.git");
            window.showSync(FxTestSupport.field(fx.controller, "stage"));
            javafx.scene.layout.VBox warning = control("syncCredentialsWarning");
            ((Button) SettingsRig.button(warning, tr("settings.sync.credentials.keep"))).fire();
            assertFalse(warning.isVisible());
            assertEquals("https://ghp_FAKEFAKEFAKE0000@github.com/me/editora-sync.git", settings.getSyncRepoUrl());
            window.showSync(FxTestSupport.field(fx.controller, "stage"));
            assertFalse(warning.isVisible(), "not again this run");
        });
    }

    /** S2: Sync Now could not be stopped. Cancel stops it before it writes anything on this computer. */
    @Test
    void syncNowCanBeCancelledBeforeItWritesAnything() throws Exception {
        open(null);
        connect(remote.toString(), "main");
        awaitIdle();
        Files.writeString(other.resolve("dictionary.txt"), "zzfromother\n");
        assertTrue(otherSyncs().ok());

        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService worker = FxTestSupport.field(sync, "worker");
        worker.execute(
                () -> { // stands for a slow network: the sync waits behind this
                    try {
                        hold.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        FxTestSupport.runOnFx(() -> {
            this.<Button>control("syncNowButton").fire();
            Button cancel = control("syncCancelButton");
            assertTrue(cancel.isVisible(), "offered while it syncs");
            cancel.fire();
        });
        hold.countDown();
        awaitIdle();
        FxTestSupport.runOnFx(() -> {
            assertFalse(fx.shared.getUserDictionary().contains("zzfromother"), "nothing arrived");
            assertFalse(this.<Button>control("syncCancelButton").isVisible());
            assertNotEquals(
                    tr("sync.state.syncing"),
                    this.<Label>control("syncStateLabel").getText());
        });

        FxTestSupport.runOnFx(() -> this.<Button>control("syncNowButton").fire());
        awaitIdle();
        FxTestSupport.runOnFx(
                () -> assertTrue(fx.shared.getUserDictionary().contains("zzfromother"), "the next sync runs in full"));
    }

    /** Puts the sync service in {@code phase} after a run that ended with {@code report} (no git, no network). */
    private void fakeState(SettingsSync.Phase phase, SyncReport report, java.time.LocalTime at) {
        FxTestSupport.call(
                sync, "setState", new Class<?>[] {SettingsSync.State.class}, new SettingsSync.State(phase, report, at));
    }

    private static SyncReport report(SyncReport.Status status, String detail) {
        return new SyncReport(status, List.of(), List.of(), List.of(), List.of(), detail);
    }

    /** The pill is as wide as its words, never the width of the row (adl's screenshots). */
    private static void assertHugsItsText(Label pill) {
        pill.getScene().getRoot().applyCss();
        pill.getScene().getRoot().layout();
        double pref = pill.prefWidth(-1);
        double row = ((javafx.scene.layout.Region) pill.getParent().getParent()).getWidth();
        assertTrue(
                Math.abs(pill.getWidth() - Math.min(pref, pill.getMaxWidth())) < 2,
                pill.getText() + ": " + pill.getWidth() + " wide, its text needs " + pref);
        assertTrue(pill.getWidth() < row - 20, pill.getText() + " fills the row (" + row + ")");
    }

    /** adl: a green pill when connected, a yellow or red one when not, on the Status row of the Sync page. */
    @Test
    void theStatusRowIsAPillInEveryState() throws Exception {
        open(null);
        FxTestSupport.runOnFx(() -> {
            Label pill = control("syncPill");
            Label detail = control("syncStateLabel");
            // Not set up.
            assertEquals(tr("settings.sync.pill.notConnected"), pill.getText());
            assertTrue(pill.getStyleClass().contains("settings-git-warn"));
            assertEquals(tr("settings.sync.pill.accessible", pill.getText()), pill.getAccessibleText());
            assertHugsItsText(pill);
            assertFalse(detail.isVisible(), "the pill says it all");

            settings.setSyncRepoUrl("git@example.invalid:me/editora-sync.git"); // never contacted: no run starts
            settings.setSyncEnabled(true);
            fakeState(SettingsSync.Phase.IDLE, report(SyncReport.Status.OK, ""), java.time.LocalTime.now());
            assertEquals(
                    tr("settings.sync.pill.with", tr("settings.sync.pill.connected"), tr("settings.sync.pill.justNow")),
                    pill.getText());
            assertTrue(pill.getStyleClass().contains("settings-git-found"));
            assertTrue(detail.isVisible(), "what the last run did");
            assertHugsItsText(pill);

            fakeState(SettingsSync.Phase.SYNCING, report(SyncReport.Status.OK, ""), java.time.LocalTime.now());
            assertEquals(
                    tr("settings.sync.pill.with", tr("settings.sync.pill.connected"), tr("settings.sync.pill.syncing")),
                    pill.getText());
            assertTrue(pill.getStyleClass().contains("settings-git-found"));

            fakeState(
                    SettingsSync.Phase.PROBLEM,
                    report(
                            SyncReport.Status.FETCH_FAILED,
                            "fatal: unable to access: Could not resolve host: example.invalid"),
                    java.time.LocalTime.now());
            assertTrue(pill.getStyleClass().contains("settings-git-warn"), "offline: it retries by itself");
            assertHugsItsText(pill);
            assertTrue(pill.getText().startsWith(tr("settings.sync.pill.notConnected")));

            fakeState(
                    SettingsSync.Phase.PROBLEM,
                    report(SyncReport.Status.FETCH_FAILED, "fatal: could not read Username: terminal prompts disabled"),
                    java.time.LocalTime.now());
            assertEquals(tr("settings.sync.pill.failing"), pill.getText());
            assertTrue(pill.getStyleClass().contains("settings-git-missing"));
            assertHugsItsText(pill);
            assertTrue(detail.isVisible(), "with the reason under it");
            assertEquals(sync.stateText(), detail.getText());
            assertEquals(detail.getText(), pill.getTooltip().getText());

            fakeState(SettingsSync.Phase.OFF, null, null);
            settings.setSyncEnabled(false);
            settings.setSyncRepoUrl("");
        });
    }
}
