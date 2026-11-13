package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.ControlNames;
import com.editora.editor.UiZoom;
import com.editora.ui.SettingsWindow.Card;
import com.editora.ui.SettingsWindow.Category;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → Sync: the repository and branch, Connect / Disconnect / Create on GitHub, the status row, what is
 * synced and when. The page's controls, its load and its refresh from the {@link SettingsSync} service live here;
 * {@link SettingsWindow} builds it with the other pages and forwards the service to it.
 */
final class SettingsSyncPage {

    private final SettingsWindow w;
    private final ConfigManager config;

    SettingsSyncPage(SettingsWindow w, ConfigManager config) {
        this.w = w;
        this.config = config;
    }

    private @Nullable SettingsSync settingsSync;
    private @Nullable VBox syncCredentialsWarning;
    private final Runnable syncStateListener = this::refreshSyncControls;
    private @Nullable TextField syncUrlField;
    private @Nullable TextField syncBranchField;
    private @Nullable Button syncConnectButton;
    private @Nullable Button syncDisconnectButton;
    private @Nullable Button syncCreateGithubButton;
    private @Nullable Button syncNowButton;
    private @Nullable Button syncAnywayButton;
    private @Nullable Label syncStateLabel;
    /** The Status row's pill: Connected (green), Not connected (amber), Sync failing (red); see {@link SyncStatus}. */
    private @Nullable Label syncPill;

    private @Nullable Label syncAccountLabel;
    private @Nullable CheckBox syncSnippetsCheck;
    private @Nullable CheckBox syncAbbreviationsCheck;
    private @Nullable CheckBox syncTemplatesCheck;
    private @Nullable CheckBox syncDictionaryCheck;
    private @Nullable CheckBox syncKeybindingsCheck;
    private @Nullable CheckBox syncAutoCheck;
    private @Nullable Spinner<Integer> syncIntervalSpinner;
    /** A message about the connect attempt in progress; shown instead of the service's state until it ends. */
    private @Nullable String syncConnectMessage;
    /** Connect's look at the repository is under way; {@link #syncConnectRun} tells a cancelled one apart. */
    private boolean syncChecking;

    private int syncConnectRun;
    private @Nullable Button syncCancelButton;
    /**
     * Why the last Connect or Create did not go through (an unusable URL, an unreachable repository, a gh
     * error). Kept, rather than written to the label once, so that a refresh arriving afterwards — the
     * GitHub sign-in check finishing, the service reporting its state — does not replace it with "Not
     * connected." before it was read. It goes when the user edits the repository, tries again or
     * disconnects.
     */
    private @Nullable String syncNotice;

    void setSettingsSync(@Nullable SettingsSync sync) {
        if (settingsSync != null) {
            settingsSync.removeListener(syncStateListener);
        }
        settingsSync = sync;
        if (sync != null) {
            sync.addListener(syncStateListener);
        }
        refreshSyncControls();
    }

    @Nullable
    SettingsSync settingsSync() {
        return settingsSync;
    }

    void markSyncDirty() {
        if (settingsSync != null) {
            settingsSync.markDirty();
        }
    }

    /** SYSTEM ▸ Sync: the repository, what is synced, when, and how the last run went. */
    VBox build() {
        VBox p = w.page(tr("settings.cat.sync"), tr("settings.sync.subtitle"));
        Settings settings = config.getSettings();

        // A URL stored with a password or token before the field refused them: offered, once, to take it out.
        TextField urlField = new TextField();
        Button removeSecret = new Button(tr("settings.sync.credentials.remove"));
        removeSecret.setOnAction(e -> {
            String clean = com.editora.sync.SyncEngine.withoutCredentials(
                    config.getSettings().getSyncRepoUrl());
            config.getSettings().setSyncRepoUrl(clean);
            urlField.setText(clean);
            syncSettingsChanged(); // saves; the clone's remote is rewritten at the next sync
            showSyncNotice(tr("settings.sync.credentials.removed"));
        });
        Button keepSecret = new Button(tr("settings.sync.credentials.keep"));
        keepSecret.setOnAction(e -> {
            if (settingsSync != null) { // the warning only shows with one
                settingsSync.storedCredentialsKept = true;
            }
            refreshSyncControls();
        });
        syncCredentialsWarning = UiZoom.spacing(
                new VBox(
                        SettingsWindow.noteBox(tr("settings.sync.credentials.stored")),
                        UiZoom.spacing(new HBox(removeSecret, keepSecret), 8)),
                6);
        p.getChildren().add(syncCredentialsWarning);
        Card repo = w.card(p, null);
        syncUrlField = urlField;
        syncUrlField.setPromptText(tr("settings.sync.repo.prompt"));
        UiZoom.prefWidth(syncUrlField, SettingsWindow.REPO_URL_FIELD_WIDTH);
        // A password or token in the URL would be stored in plain text: refused, with the reason, until removed.
        syncUrlField.textProperty().addListener((o, was, now) -> {
            boolean secret = com.editora.sync.SyncEngine.hasCredentials(now);
            urlField.pseudoClassStateChanged(atlantafx.base.theme.Styles.STATE_DANGER, secret);
            urlField.setTooltip(secret ? ControlNames.tooltip(tr("settings.sync.repo.credentials")) : null);
        });
        w.commitOnEnterOrBlur(syncUrlField, settings::getSyncRepoUrl, text -> {
            if (com.editora.sync.SyncEngine.hasCredentials(text)) {
                showSyncNotice(tr("settings.sync.repo.credentials"));
                return;
            }
            config.getSettings().setSyncRepoUrl(text);
            config.save();
            syncNotice = null;
            refreshSyncControls();
        });
        w.cardRow(
                repo,
                Category.SYNC,
                SettingsWindow.settingRow(tr("settings.sync.repo"), tr("settings.sync.repo.desc"), syncUrlField),
                "sync repository url git remote github gitlab clone ssh https");
        TextField branchField = new TextField();
        syncBranchField = branchField;
        UiZoom.prefWidth(syncBranchField, 140);
        syncBranchField.textProperty().addListener((o, was, now) -> {
            boolean bad = !now.isBlank() && !com.editora.sync.SyncEngine.isUsableBranch(now.strip());
            branchField.pseudoClassStateChanged(atlantafx.base.theme.Styles.STATE_DANGER, bad);
            branchField.setTooltip(bad ? ControlNames.tooltip(tr("settings.sync.branch.invalid")) : null);
        });
        w.commitOnEnterOrBlur(syncBranchField, settings::getSyncBranch, text -> {
            if (!text.isBlank() && !com.editora.sync.SyncEngine.isUsableBranch(text.strip())) {
                return; // red, with the reason; the branch in use stays
            }
            config.getSettings().setSyncBranch(text);
            config.save();
            syncNotice = null;
            refreshSyncControls();
        });
        syncConnectButton = new Button(tr("settings.sync.connect"));
        syncConnectButton.setOnAction(e -> connectSync());
        syncDisconnectButton = new Button(tr("settings.sync.disconnect"));
        syncDisconnectButton.setOnAction(e -> {
            config.getSettings().setSyncEnabled(false);
            config.save();
            if (settingsSync != null) {
                settingsSync.disconnected();
            }
            syncNotice = null;
            refreshSyncControls();
        });
        syncCreateGithubButton = new Button(tr("settings.sync.createGithub"));
        syncCreateGithubButton.setOnAction(e -> createSyncRepoOnGithub());
        w.cardRow(
                repo,
                Category.SYNC,
                SettingsWindow.settingRow(
                        tr("settings.sync.branch"),
                        tr("settings.sync.branch.desc"),
                        UiZoom.spacing(
                                new HBox(
                                        syncBranchField,
                                        syncConnectButton,
                                        syncDisconnectButton,
                                        syncCreateGithubButton),
                                8)),
                "sync branch connect disconnect enable turn on off create private repository github gh");
        syncStateLabel = new Label();
        syncStateLabel.setWrapText(true);
        UiZoom.maxWidth(syncStateLabel, 420);
        syncPill = new Label();
        syncPill.setWrapText(true);
        UiZoom.maxWidth(syncPill, 420);
        // "synced 5 min ago" moves on while the window is open.
        javafx.animation.Timeline syncPillTick = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(30), e -> refreshSyncControls()));
        syncPillTick.setCycleCount(javafx.animation.Animation.INDEFINITE);
        w.stage.showingProperty().addListener((o, was, now) -> {
            if (now) {
                syncPillTick.play();
            } else {
                syncPillTick.stop();
            }
        });
        syncAccountLabel = new Label();
        syncAccountLabel.getStyleClass().add("settings-row-desc");
        syncAccountLabel.setWrapText(true);
        UiZoom.maxWidth(syncAccountLabel, 420);
        syncNowButton = new Button(tr("settings.sync.now"));
        syncNowButton.setOnAction(e -> {
            if (settingsSync != null) {
                settingsSync.syncNow();
            }
        });
        syncAnywayButton = new Button(tr("settings.sync.anyway"));
        syncAnywayButton.setOnAction(e -> {
            if (settingsSync != null) {
                settingsSync.syncAllowingLargeRemoval();
            }
        });
        syncCancelButton = new Button(tr("settings.sync.cancel"));
        syncCancelButton.setOnAction(e -> {
            if (!syncChecking) { // a sync under way: stopped before it writes anything here, or let finish
                if (settingsSync != null && !settingsSync.cancelSync()) {
                    showSyncNotice(tr("settings.sync.cancelTooLate"));
                }
                return;
            }
            // Connect's look: stops waiting; git finishes (or times out) on its own, unused
            syncConnectRun++;
            syncChecking = false;
            syncConnectMessage = null;
            showSyncNotice(tr("settings.sync.cancelled"));
        });
        Button openFolder = new Button(tr("settings.sync.openFolder"));
        openFolder.setOnAction(e -> openSyncFolder());
        w.cardRow(
                repo,
                Category.SYNC,
                SettingsWindow.settingRow(
                        tr("settings.sync.status"),
                        null,
                        UiZoom.spacing(
                                new VBox(
                                        new HBox(syncPill), // a pill as wide as its words
                                        syncStateLabel,
                                        syncAccountLabel,
                                        UiZoom.spacing(
                                                new HBox(syncNowButton, syncAnywayButton, syncCancelButton, openFolder),
                                                8)),
                                6)),
                "sync now status last synced received sent conflict backups folder github account signed in");

        Card what = w.card(p, tr("settings.sync.what"));
        syncSnippetsCheck = syncCategoryCheck("settings.sync.snippets", Settings::setSyncSnippets);
        syncAbbreviationsCheck = syncCategoryCheck("settings.sync.abbreviations", Settings::setSyncAbbreviations);
        syncTemplatesCheck = syncCategoryCheck("settings.sync.templates", Settings::setSyncTemplates);
        syncDictionaryCheck = syncCategoryCheck("settings.sync.dictionary", Settings::setSyncDictionary);
        syncKeybindingsCheck = syncCategoryCheck("settings.sync.keybindings", Settings::setSyncKeybindings);
        w.checkRow(what, Category.SYNC, syncSnippetsCheck, null, "sync snippets");
        w.checkRow(what, Category.SYNC, syncAbbreviationsCheck, null, "sync abbreviations abbrev");
        w.checkRow(what, Category.SYNC, syncTemplatesCheck, null, "sync templates file project");
        w.checkRow(what, Category.SYNC, syncDictionaryCheck, null, "sync spell check personal dictionary words");
        w.checkRow(
                what,
                Category.SYNC,
                syncKeybindingsCheck,
                tr("settings.sync.keybindings.desc"),
                "sync keybindings key bindings shortcuts keymap rebind");
        w.cardRow(
                what,
                Category.SYNC,
                SettingsWindow.note(tr("settings.sync.notSynced")),
                "sync not synced preferences macros");

        Card when = w.card(p, null);
        syncAutoCheck = new CheckBox(tr("settings.sync.auto"));
        syncAutoCheck.selectedProperty().addListener((obs, was, now) -> {
            if (syncIntervalSpinner != null) { // made just below
                syncIntervalSpinner.setDisable(!now);
            }
            if (w.loading) {
                return;
            }
            config.getSettings().setSyncAuto(now);
            syncSettingsChanged();
        });
        syncIntervalSpinner =
                IntSpinners.editable(1, Settings.MAX_GIT_AUTO_FETCH_MINUTES, settings.getSyncIntervalMinutes(), 1);
        UiZoom.prefWidth(syncIntervalSpinner, 90);
        syncIntervalSpinner.setAccessibleText(tr("settings.git.autoFetchMinutes"));
        syncIntervalSpinner.valueProperty().addListener((obs, was, now) -> {
            if (w.loading || now == null) {
                return;
            }
            config.getSettings().setSyncIntervalMinutes(now);
            syncSettingsChanged();
        });
        Label unit = new Label(tr("settings.git.autoFetchMinutes"));
        unit.setLabelFor(syncIntervalSpinner);
        w.cardRow(
                when,
                Category.SYNC,
                SettingsWindow.settingRow(
                        tr("settings.sync.auto"),
                        tr("settings.sync.auto.desc"),
                        UiZoom.spacing(
                                new HBox(syncIntervalSpinner, unit, SettingsWindow.switchFor(syncAutoCheck)), 8)),
                "sync automatically auto background interval minutes startup");

        w.row(
                p,
                Category.SYNC,
                null,
                SettingsWindow.noteBox(tr("settings.sync.privacy")),
                "private repository privacy secret");
        return p;
    }

    private CheckBox syncCategoryCheck(String key, java.util.function.BiConsumer<Settings, Boolean> set) {
        CheckBox check = new CheckBox(tr(key));
        check.selectedProperty().addListener((obs, was, now) -> {
            if (w.loading) {
                return;
            }
            set.accept(config.getSettings(), now);
            syncSettingsChanged();
        });
        return check;
    }

    /** Saves a changed {@code sync*} setting and lets the service re-read it. */
    private void syncSettingsChanged() {
        config.save();
        if (settingsSync != null) {
            settingsSync.settingsChanged();
        }
    }

    void load(Settings settings) {
        if (syncUrlField == null
                || syncBranchField == null
                || syncSnippetsCheck == null
                || syncAbbreviationsCheck == null
                || syncTemplatesCheck == null
                || syncDictionaryCheck == null
                || syncKeybindingsCheck == null
                || syncAutoCheck == null
                || syncIntervalSpinner == null) {
            return; // all made by syncPage(), which build() runs before any load()
        }
        syncUrlField.setText(settings.getSyncRepoUrl());
        syncBranchField.setText(settings.getSyncBranch());
        syncSnippetsCheck.setSelected(settings.isSyncSnippets());
        syncAbbreviationsCheck.setSelected(settings.isSyncAbbreviations());
        syncTemplatesCheck.setSelected(settings.isSyncTemplates());
        syncDictionaryCheck.setSelected(settings.isSyncDictionary());
        syncKeybindingsCheck.setSelected(settings.isSyncKeybindings());
        syncAutoCheck.setSelected(settings.isSyncAuto());
        syncIntervalSpinner.getValueFactory().setValue(settings.getSyncIntervalMinutes());
        syncIntervalSpinner.setDisable(!settings.isSyncAuto());
        refreshSyncControls();
        if (w.githubService != null) {
            w.githubService.detect(availability -> refreshSyncControls()); // "Create on GitHub" needs a signed-in gh
        }
    }

    /** Shows the service's state: which buttons apply, and the status line. */
    private void refreshSyncControls() {
        if (syncStateLabel == null
                || syncUrlField == null
                || syncBranchField == null
                || syncConnectButton == null
                || syncDisconnectButton == null
                || syncCreateGithubButton == null
                || syncNowButton == null
                || syncAnywayButton == null
                || syncCancelButton == null
                || syncCredentialsWarning == null
                || syncAccountLabel == null
                || syncPill == null) {
            return; // not built yet
        }
        Settings settings = config.getSettings();
        boolean available = settingsSync != null && settingsSync.primaryInstance();
        boolean connected = settings.isSyncEnabled();
        SettingsSync.State state = settingsSync == null ? null : settingsSync.state();
        boolean syncing = state != null && state.phase() == SettingsSync.Phase.SYNCING;
        syncUrlField.setDisable(connected);
        syncBranchField.setDisable(connected);
        syncConnectButton.setVisible(!connected);
        syncConnectButton.setManaged(!connected);
        syncConnectButton.setDisable(!available || syncConnectMessage != null);
        syncDisconnectButton.setVisible(connected);
        syncDisconnectButton.setManaged(connected);
        com.editora.github.GitHubService.Availability gh =
                w.githubService == null ? null : w.githubService.availability();
        boolean canCreate = available && !connected && gh != null && gh.found() && gh.authenticated();
        syncCreateGithubButton.setVisible(canCreate);
        syncCreateGithubButton.setManaged(canCreate);
        syncCreateGithubButton.setDisable(syncConnectMessage != null);
        syncNowButton.setDisable(!available || !connected || syncing);
        boolean needsConfirmation = state != null
                && state.phase() == SettingsSync.Phase.PROBLEM
                && state.report() != null
                && state.report().status() == com.editora.sync.SyncReport.Status.NEEDS_CONFIRMATION;
        syncAnywayButton.setVisible(needsConfirmation);
        syncAnywayButton.setManaged(needsConfirmation);
        syncCancelButton.setVisible(syncChecking || syncing);
        syncCancelButton.setManaged(syncChecking || syncing);
        boolean secret = com.editora.sync.SyncEngine.hasCredentials(settings.getSyncRepoUrl())
                && settingsSync != null
                && !settingsSync.storedCredentialsKept;
        syncCredentialsWarning.setVisible(secret);
        syncCredentialsWarning.setManaged(secret);
        String account = connected && settingsSync != null ? settingsSync.account() : "";
        syncAccountLabel.setText(account.isEmpty() ? "" : tr("sync.state.account", account));
        syncAccountLabel.setVisible(!account.isEmpty());
        syncAccountLabel.setManaged(!account.isEmpty());
        syncStateLabel.setText(
                syncConnectMessage != null
                        ? syncConnectMessage
                        : syncNotice != null
                                ? syncNotice
                                : settingsSync == null ? tr("sync.state.off") : settingsSync.stateText());
        SyncStatus.Pill pill = SyncStatus.of(
                connected && com.editora.sync.SyncEngine.isUsableUrl(settings.getSyncRepoUrl()),
                available,
                state,
                java.time.LocalTime.now());
        syncPill.setText(pill.text());
        syncPill.setAccessibleText(pill.accessibleText());
        syncPill.getStyleClass().setAll("settings-git-status", pill.level().pillClass());
        syncPill.setTooltip(ControlNames.tooltip(syncStateLabel.getText()));
        // The line under the pill: a notice, the reason a run failed, or what the last run did. The pill alone
        // says "Not connected" and "syncing…".
        SettingsSync.Phase phase = state == null ? SettingsSync.Phase.OFF : state.phase();
        boolean detail = syncConnectMessage != null
                || syncNotice != null
                || !available && settingsSync != null
                || phase == SettingsSync.Phase.PROBLEM
                || phase == SettingsSync.Phase.IDLE
                        && state != null // phase is OFF without one
                        && state.report() != null
                        && state.report().ok();
        syncStateLabel.setVisible(detail);
        syncStateLabel.setManaged(detail);
    }

    /** Shows {@code text} in the status line until the user next acts on the repository (see {@link #syncNotice}). */
    private void showSyncNotice(String text) {
        syncNotice = text;
        refreshSyncControls();
    }

    /**
     * "Connect": looks at the repository first. When it already holds data that this computer would receive,
     * says how much and asks; then switches sync on and runs the first sync.
     */
    private void connectSync() {
        SettingsSync sync = settingsSync;
        if (sync == null) {
            return;
        }
        w.commitPendingFields();
        syncNotice = null;
        Settings settings = config.getSettings();
        String url = settings.getSyncRepoUrl();
        if (syncUrlField == null || syncBranchField == null) {
            return; // Connect is a button on the page that makes both
        }
        if (com.editora.sync.SyncEngine.hasCredentials(syncUrlField.getText())) {
            showSyncNotice(tr("settings.sync.repo.credentials")); // the stored URL is not the one shown
            return;
        }
        String typedBranch = syncBranchField.getText().strip();
        if (!typedBranch.isEmpty() && !com.editora.sync.SyncEngine.isUsableBranch(typedBranch)) {
            showSyncNotice(tr("settings.sync.branch.invalid")); // nor is the stored branch
            return;
        }
        String branch = settings.getSyncBranch();
        if (!com.editora.sync.SyncEngine.isUsableUrl(url) || !com.editora.sync.SyncEngine.isUsableBranch(branch)) {
            showSyncNotice(tr("status.sync.invalidRepository"));
            return;
        }
        syncConnectMessage = tr("sync.state.checking");
        int run = ++syncConnectRun;
        syncChecking = true;
        refreshSyncControls();
        sync.preview(url, branch, report -> {
            if (run != syncConnectRun) {
                return; // cancelled: the user stopped waiting for this look
            }
            syncChecking = false;
            syncConnectMessage = null;
            if (!report.ok()) {
                showSyncNotice(SettingsSync.problemText(report));
                return;
            }
            if (!report.received().isEmpty()) {
                Alert confirm = Dialogs.styled(new Alert(
                        Alert.AlertType.CONFIRMATION,
                        tr(
                                "dialog.sync.connect.content",
                                report.received().size(),
                                report.sent().size(),
                                report.conflicts().size()),
                        ButtonType.OK,
                        ButtonType.CANCEL));
                confirm.initOwner(w.stage);
                confirm.setTitle(tr("settings.cat.sync"));
                confirm.setHeaderText(tr("dialog.sync.connect.header"));
                if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                    refreshSyncControls();
                    return;
                }
            }
            config.getSettings().setSyncEnabled(true);
            config.save();
            sync.connected();
            refreshSyncControls();
        });
    }

    /** "Create Private Repository on GitHub…": asks for a name, creates it with gh, then connects to it. */
    private void createSyncRepoOnGithub() {
        if (settingsSync == null || w.githubService == null) {
            return;
        }
        javafx.scene.control.TextInputDialog ask =
                Dialogs.styled(new javafx.scene.control.TextInputDialog("editora-sync"));
        ask.initOwner(w.stage);
        ask.setTitle(tr("settings.cat.sync"));
        ask.setHeaderText(tr("dialog.sync.createGithub.header"));
        ask.setContentText(tr("dialog.sync.createGithub.name"));
        String name = ask.showAndWait().map(String::strip).orElse("");
        if (name.isEmpty()) {
            return;
        }
        syncNotice = null;
        if (!name.matches("[A-Za-z0-9._-]+")) {
            showSyncNotice(tr("status.sync.invalidRepositoryName"));
            return;
        }
        syncConnectMessage = tr("sync.state.creating");
        refreshSyncControls();
        w.githubService.createPrivateRepo(name, tr("settings.sync.githubDescription"), created -> {
            syncConnectMessage = null;
            if (!created.ok()) {
                showSyncNotice(tr("status.sync.failed", created.error()));
                return;
            }
            config.getSettings().setSyncRepoUrl(created.url());
            config.save();
            if (syncUrlField != null) {
                syncUrlField.setText(created.url());
            }
            connectSync();
        });
    }

    private void openSyncFolder() {
        if (settingsSync == null) {
            return;
        }
        Path dir = settingsSync.syncDir();
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            showSyncNotice(String.valueOf(e.getMessage()));
            return;
        }
        com.editora.process.DesktopActions.reveal(dir, true, this::showSyncNotice);
    }
}
