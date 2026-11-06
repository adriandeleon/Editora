package com.editora.ui;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.ui.SettingsWindow.Card;
import com.editora.ui.SettingsWindow.Category;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → Git (the switch, the git command and what was found, inline blame, pull mode, background fetch) and
 * GitHub (the switch, the gh command and its sign-in state). The pages' controls, their load and the two probes live
 * here; {@link SettingsWindow} builds them with the other pages.
 */
final class SettingsGitPages {

    private final SettingsWindow w;
    private final ConfigManager config;
    private final com.editora.git.GitService gitService;
    private final com.editora.github.GitHubService githubService;

    SettingsGitPages(
            SettingsWindow w,
            ConfigManager config,
            com.editora.git.GitService gitService,
            com.editora.github.GitHubService githubService) {
        this.w = w;
        this.config = config;
        this.gitService = gitService;
        this.githubService = githubService;
    }

    @Initializer
    void buildControls() {
        gitCheck = new CheckBox(tr("settings.enableGit"));
        gitCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setGitSupport(now);
            w.apply();
            w.refreshToolWindowRows(); // reflect on the Tool Windows page's Commit row
            refreshGithubStatus(); // the GitHub page says when it is waiting for Git support
        });

        blameCheck = new CheckBox(tr("settings.git.blameInline"));
        blameCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setGitBlameInline(now);
            w.apply();
        });

        gitPathField = new TextField();
        gitPathField.setPromptText("git");
        w.commitOnEnterOrBlur(gitPathField, () -> config.getSettings().getGitPath(), now -> {
            config.getSettings().setGitPath(now);
            w.apply(); // applySupport pushes the command into GitService
            probeGit();
        });

        gitPullModeCombo = new ComboBox<>();
        gitPullModeCombo.getItems().setAll(com.editora.git.GitPullMode.values());
        gitPullModeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(com.editora.git.GitPullMode mode) {
                return mode == null ? "" : GitCoordinator.pullModeLabel(mode);
            }

            @Override
            public com.editora.git.@Nullable GitPullMode fromString(String label) {
                return null; // not editable
            }
        });
        gitPullModeCombo.setMinWidth(170); // no fixed width: the translated items differ a lot in length
        gitPullModeCombo.valueProperty().addListener((obs, was, now) -> {
            if (w.loading || now == null) {
                return;
            }
            config.getSettings().setGitPullMode(now.id());
            w.apply();
        });

        gitAutoFetchCheck = new CheckBox(tr("settings.git.autoFetch"));
        gitAutoFetchCheck.selectedProperty().addListener((obs, was, now) -> {
            gitAutoFetchSpinner.setDisable(!now);
            if (w.loading) {
                return;
            }
            config.getSettings().setGitAutoFetch(now);
            w.apply(); // applySupport starts or stops the timer
        });
        gitAutoFetchSpinner = IntSpinners.editable(
                1,
                com.editora.config.Settings.MAX_GIT_AUTO_FETCH_MINUTES,
                config.getSettings().getGitAutoFetchMinutes(),
                1);
        gitAutoFetchSpinner.setPrefWidth(90);
        gitAutoFetchSpinner.setAccessibleText(tr("settings.git.autoFetchMinutes"));
        gitAutoFetchSpinner.valueProperty().addListener((obs, was, now) -> {
            if (w.loading || now == null) {
                return;
            }
            config.getSettings().setGitAutoFetchMinutes(now);
            w.apply();
        });

        githubCheck = new CheckBox(tr("settings.enableGithub"));
        githubCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setGithubSupport(now);
            w.apply();
            ghPathField.setDisable(!now);
            refreshGithubStatus();
        });
        ghPathField = new TextField();
        ghPathField.setPromptText("gh");
        w.commitOnEnterOrBlur(ghPathField, () -> config.getSettings().getGhPath(), now -> {
            config.getSettings().setGhPath(now);
            w.apply();
            refreshGithubStatus();
        });
    }

    private CheckBox gitCheck;
    private CheckBox blameCheck;
    private CheckBox githubCheck;
    private TextField ghPathField;
    private @Nullable Label githubStatusLabel;

    private @Nullable Label gitStatusLabel;
    private TextField gitPathField;
    private ComboBox<com.editora.git.GitPullMode> gitPullModeCombo;
    private CheckBox gitAutoFetchCheck;
    private Spinner<Integer> gitAutoFetchSpinner;

    /** Reference implementation of the UI Kit card page — see {@link SettingsWindow#card}/{@link SettingsWindow#settingRow}. The
     *  cards are untitled (the kit's are too): the page heading already says "Git", and each row names
     *  itself. */
    VBox gitPage() {
        VBox p = w.page(tr("settings.cat.git"), tr("settings.git.subtitle"));

        Card c = w.card(p, null);
        gitStatusLabel = new Label(tr("settings.git.checking"));
        gitStatusLabel.getStyleClass().add("settings-git-status");
        gitStatusLabel.setWrapText(true);
        gitStatusLabel.setMaxWidth(340);
        w.cardRow(
                c,
                Category.GIT,
                SettingsWindow.settingRow(
                        tr("settings.enableGit"), tr("settings.git.hint"), SettingsWindow.switchFor(gitCheck)),
                "git version control vcs enable");
        Button gitBrowse = w.browseButton(tr("settings.git.command"), gitPathField);
        gitPathField.setPrefWidth(SettingsWindow.PATH_FIELD_WIDTH);
        w.cardRow(
                c,
                Category.GIT,
                SettingsWindow.settingRow(
                        tr("settings.git.command"),
                        tr("settings.git.command.desc"),
                        new HBox(6, gitPathField, gitBrowse)),
                "git command path executable browse");
        w.cardRow(
                c,
                Category.GIT,
                SettingsWindow.settingRow(tr("settings.git.detected"), null, gitStatusLabel),
                "git command found version installed not found");

        // Blame, pull and fetch only matter while Git is on; the command path stays editable to fix detection.
        p.getChildren().add(SettingsDependents.note(gitCheck.selectedProperty(), tr("settings.enableGit")));
        Card c2 = w.card(p, null);
        SettingsDependents.bind(gitCheck.selectedProperty(), c2.body());
        w.cardRow(
                c2,
                Category.GIT,
                SettingsWindow.settingRow(
                        tr("settings.git.blameInline"),
                        tr("settings.git.blameInline.desc"),
                        new HBox(8, w.keymapsPage.chordChip("git.toggleBlame"), SettingsWindow.switchFor(blameCheck))),
                "git blame annotate inline author history line");
        w.cardRow(
                c2,
                Category.GIT,
                SettingsWindow.settingRow(
                        tr("settings.git.pullMode"), tr("settings.git.pullMode.desc"), gitPullModeCombo),
                "git pull mode fast-forward ff-only rebase merge diverged");
        Label autoFetchUnit = new Label(tr("settings.git.autoFetchMinutes"));
        autoFetchUnit.setLabelFor(gitAutoFetchSpinner);
        w.cardRow(
                c2,
                Category.GIT,
                SettingsWindow.settingRow(
                        tr("settings.git.autoFetch"),
                        tr("settings.git.autoFetch.desc"),
                        new HBox(8, gitAutoFetchSpinner, autoFetchUnit, SettingsWindow.switchFor(gitAutoFetchCheck))),
                "git fetch automatically auto background interval minutes remote prune");

        return p;
    }

    VBox githubPage() {
        VBox p = w.page(tr("settings.cat.github"), tr("settings.github.subtitle"));
        Card c = w.card(p, null);
        githubStatusLabel = new Label(tr("settings.github.checking"));
        githubStatusLabel.getStyleClass().add("settings-git-status");
        githubStatusLabel.setWrapText(true);
        githubStatusLabel.setMaxWidth(340);
        w.checkRow(c, Category.GITHUB, githubCheck, tr("settings.github.hint"), "github gh pull request pr enable");
        Button ghBrowse = w.browseButton(tr("settings.github.ghPath"), ghPathField);
        ghPathField.setPrefWidth(SettingsWindow.PATH_FIELD_WIDTH);
        w.controlRow(
                c,
                Category.GITHUB,
                tr("settings.github.ghPath"),
                null,
                new HBox(6, ghPathField, ghBrowse),
                "github gh path executable command");
        w.controlRow(
                c,
                Category.GITHUB,
                tr("settings.git.detected"),
                null,
                githubStatusLabel,
                "github gh cli found installed authenticated not found");
        refreshGithubStatus();
        return p;
    }

    void refreshGithubStatus() {
        if (githubStatusLabel == null || githubService == null) {
            return;
        }
        if (config.getSettings().isGithubSupport() && !config.getSettings().isGitSupport()) {
            // GitHub rides on the Git integration: say so here rather than report a healthy gh that does nothing.
            githubStatusLabel.getStyleClass().setAll("settings-git-status", "settings-git-missing");
            githubStatusLabel.setText(tr("settings.github.needsGit"));
            return;
        }
        // Re-applying an unchanged command keeps the service's cached answer, so it is shown at once and
        // only replaced when the fresh probe lands — no "Checking…" flash on every Settings sync.
        githubService.setCommand(config.getSettings().getGhPath());
        com.editora.github.GitHubService.Availability cached = githubService.availability();
        if (cached == null) {
            githubStatusLabel.getStyleClass().setAll("settings-git-status");
            githubStatusLabel.setText(tr("settings.github.checking"));
        } else {
            showGithubStatus(cached);
        }
        githubService.detect(this::showGithubStatus);
    }

    private void showGithubStatus(com.editora.github.GitHubService.Availability a) {
        if (githubStatusLabel == null) {
            return; // the GitHub page makes it before anything probes
        }
        String[] verdict = githubStatus(a, config.getSettings().getGhPath());
        githubStatusLabel.getStyleClass().setAll("settings-git-status", verdict[0]);
        githubStatusLabel.setText(verdict[1]);
    }

    /** The GitHub page's status row for a probe result: {@code [style class, text]}. Pure — unit-tested. */
    static String[] githubStatus(com.editora.github.GitHubService.Availability a, String ghPath) {
        String missing = "settings-git-missing";
        if (!a.found()) {
            // A typo in the path is not a missing install: name the path rather than say "install gh".
            return new String[] {
                missing,
                ghPath == null || ghPath.isBlank()
                        ? tr("settings.github.notFound")
                        : tr("settings.github.notFoundAt", ghPath.strip())
            };
        }
        return switch (a.auth()) {
            case SIGNED_OUT -> new String[] {missing, tr("settings.github.foundNoAuth")};
            case REJECTED -> new String[] {missing, tr("settings.github.foundRejected")};
            // Offline is not a fault of the setup: gh is there and has an account.
            case UNVERIFIED -> new String[] {"settings-git-found", tr("settings.github.foundUnverified", a.version())};
            case SIGNED_IN ->
                a.supportsChecks()
                        ? new String[] {"settings-git-found", tr("settings.github.found", a.version())}
                        : new String[] {
                            missing,
                            tr(
                                    "settings.github.foundOld",
                                    com.editora.github.GhVersion.number(a.version()),
                                    com.editora.github.GhVersion.MINIMUM)
                        };
        };
    }

    /**
     * Probes for the {@code git} command off-thread and updates the Git page: shows the version when
     * found (and enables the checkbox), or "not found" + disables the checkbox when git isn't on PATH.
     */
    void probeGit() {
        Label status = gitStatusLabel;
        if (status == null || gitService == null) {
            return;
        }
        gitService.setCommand(config.getSettings().getGitPath()); // probe what the setting names
        status.getStyleClass().setAll("settings-git-status");
        status.setText(tr("settings.git.checking"));
        gitService.version(version -> {
            boolean found = version != null && !version.isBlank();
            status.getStyleClass().setAll("settings-git-status", found ? "settings-git-found" : "settings-git-missing");
            String path = config.getSettings().getGitPath();
            status.setText(
                    found
                            ? tr("settings.git.found", version)
                            : path == null || path.isBlank()
                                    ? tr("settings.git.notFound")
                                    : tr("settings.git.notFoundAt", path));
        });
    }

    void loadGit(Settings settings) {
        gitCheck.setSelected(settings.isGitSupport());
        blameCheck.setSelected(settings.isGitBlameInline());
        gitPathField.setText(settings.getGitPath());
        gitPullModeCombo.setValue(com.editora.git.GitPullMode.of(settings.getGitPullMode()));
        gitAutoFetchCheck.setSelected(settings.isGitAutoFetch());
        gitAutoFetchSpinner.getValueFactory().setValue(settings.getGitAutoFetchMinutes());
        gitAutoFetchSpinner.setDisable(!settings.isGitAutoFetch());
    }

    void loadGithub(Settings settings) {
        githubCheck.setSelected(settings.isGithubSupport());
        ghPathField.setText(settings.getGhPath());
        ghPathField.setDisable(!settings.isGithubSupport());
        refreshGithubStatus();
    }

    void syncGitBlameCheck() {
        boolean prev = w.loading;
        w.loading = true;
        try {
            blameCheck.setSelected(config.getSettings().isGitBlameInline());
            gitPullModeCombo.setValue(
                    com.editora.git.GitPullMode.of(config.getSettings().getGitPullMode()));
            gitAutoFetchCheck.setSelected(config.getSettings().isGitAutoFetch());
            gitAutoFetchSpinner.getValueFactory().setValue(config.getSettings().getGitAutoFetchMinutes());
            gitAutoFetchSpinner.setDisable(!config.getSettings().isGitAutoFetch());
        } finally {
            w.loading = prev;
        }
    }
}
