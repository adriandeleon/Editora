package com.editora.ui;

import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The About dialog: name, version and tagline, the update check's last answer with "Check now", the licence and
 * links, and the environment block "Copy details" copies. Its own class rather than part of {@link SettingsWindow},
 * where it had lived without using any of the window's state.
 */
final class AboutDialog {

    private AboutDialog() {}

    /**
     * Shows the About dialog. Shared by the {@code help.about} command and the toolbar About button.
     * The settings-file path is a link that opens that file in the editor via {@code openFile}.
     */
    static void show(
            Window owner,
            Path settingsFile,
            Consumer<Path> openFile,
            Consumer<String> openUrl,
            String commit,
            Updates updates) {
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.INFORMATION));
        alert.initOwner(owner);
        alert.setTitle(tr("dialog.about.title", com.editora.AppInfo.NAME));
        // The whole dialog is the content: no Alert header band or graphic, so the icon, name, version and
        // tagline read as one block the way a native About panel does.
        alert.setHeaderText(null);
        alert.setGraphic(null);
        alert.getDialogPane().getStyleClass().add("about-dialog");
        // Dialogs.styled above attaches app.css: without it the panel's .about-* rules never apply.

        // For a snapshot build, append the git branch to the version string so a build made from a
        // worktree/feature branch can be told apart from one made off master. Empty for release builds
        // and for packaged snapshots with no git checkout.
        String versionString = com.editora.AppInfo.VERSION;
        if (com.editora.AppInfo.isSnapshot()) {
            String branch = com.editora.AppInfo.gitBranch();
            if (!branch.isBlank()) {
                versionString += " (" + branch + ")";
            }
        }

        // --- header: icon beside name / version / tagline ------------------------------------------
        Label name = new Label(com.editora.AppInfo.NAME);
        name.getStyleClass().add("about-name");
        Label version = new Label(tr("about.version", versionString));
        version.getStyleClass().add("about-version");
        Label tagline = new Label(tr("about.tagline"));
        tagline.getStyleClass().add("about-tagline");
        tagline.setWrapText(true);
        VBox titleBox = new VBox(2, name, version, tagline);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(16, titleBox);
        var iconStream = AboutDialog.class.getResourceAsStream("/com/editora/icons/icon-128.png");
        if (iconStream != null) {
            ImageView logo = new ImageView(new Image(iconStream));
            logo.setFitWidth(72);
            logo.setFitHeight(72);
            logo.setPreserveRatio(true);
            header.getChildren().add(0, logo);
        }
        header.setAlignment(Pos.TOP_LEFT);

        // --- update status: what the last check found, when, and a way to check again ------------------
        VBox updateStatus = new VBox(2);
        updateStatus.getStyleClass().add("about-update-status");
        showUpdateStatus(updateStatus, updates, alert, openUrl, null);

        // --- copyright + links ----------------------------------------------------------------------
        Label copyright = new Label(com.editora.AppInfo.COPYRIGHT);
        copyright.getStyleClass().add("about-copyright");

        VBox links = new VBox(2);
        links.getChildren().add(aboutLink(com.editora.AppInfo.LICENSE, () -> {
            if (openUrl != null) {
                openUrl.accept(com.editora.AppInfo.HOMEPAGE);
            }
        }));
        links.getChildren().add(aboutLink(tr("about.homepage"), () -> {
            if (openUrl != null) {
                openUrl.accept(com.editora.AppInfo.HOMEPAGE);
            }
        }));
        links.getChildren().add(aboutLink(tr("about.releases"), () -> {
            if (openUrl != null) {
                openUrl.accept(com.editora.AppInfo.RELEASES_PAGE);
            }
        }));
        Hyperlink settingsLink = aboutLink(
                tr("settings.aboutSettingsLabel") + " " + SettingsWindow.displaySettingsPath(settingsFile), () -> {
                    if (openFile != null) {
                        openFile.accept(settingsFile);
                    }
                });
        settingsLink.setTooltip(new Tooltip(tr("settings.openFileTip")));
        settingsLink.setOnAction(e -> {
            alert.close();
            if (openFile != null) {
                openFile.accept(settingsFile);
            }
        });
        links.getChildren().add(settingsLink);

        // --- environment block ------------------------------------------------------------------------
        String details = environmentDetails(versionString, commit, settingsFile);
        Label env = new Label(details);
        env.getStyleClass().add("about-env");

        // --- footer: Copy details (in the content, so it does not dismiss) --------------------------
        Button copyDetails = new Button(tr("about.copyDetails"));
        copyDetails.setOnAction(e -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(details);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            copyDetails.setText(tr("about.copied"));
        });
        HBox footer = new HBox(copyDetails);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(14, header, updateStatus, copyright, links, env, footer);
        content.getStyleClass().add("about-content");
        alert.getDialogPane().setContent(content);
        // A single Close button; "Copy details" lives in the content so pressing it keeps the panel open.
        alert.getButtonTypes()
                .setAll(new ButtonType(tr("dialog.close"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE));
        alert.showAndWait();
    }

    /** The About panel's view of the update check: what the last check found, and a way to check again. */
    interface Updates {
        com.editora.update.UpdateStatus status();

        /** Checks now; {@code done} runs on the FX thread with the failure reason, or null on an answer. */
        void checkNow(Consumer<@Nullable String> done);
    }

    /**
     * Fills the About panel's update block: "Update available: X.Y.Z" (a link to that release) or "You are
     * up to date.", then when the last check answered and a "Check now" link that re-renders the block with
     * the new answer. Nothing is claimed before any check has answered: an update check that is switched off
     * must not read as "up to date".
     */
    private static void showUpdateStatus(
            VBox box, Updates updates, Alert alert, Consumer<String> openUrl, @Nullable String failure) {
        box.getChildren().clear();
        com.editora.update.UpdateStatus status = updates.status();
        switch (status.verdict(com.editora.AppInfo.VERSION)) {
            case UPDATE_AVAILABLE -> {
                String url = status.latestUrl().isBlank() ? com.editora.AppInfo.RELEASES_PAGE : status.latestUrl();
                Hyperlink updateLink = aboutLink(tr("about.updateAvailable") + " " + status.latestVersion(), () -> {});
                updateLink.getStyleClass().add("about-update");
                updateLink.setOnAction(e -> {
                    alert.close();
                    if (openUrl != null) {
                        openUrl.accept(url);
                    }
                });
                box.getChildren().add(updateLink);
            }
            case UP_TO_DATE -> {
                Label upToDate = new Label(tr("about.upToDate"));
                upToDate.getStyleClass().add("about-uptodate");
                box.getChildren().add(upToDate);
            }
            case NOT_CHECKED -> {
                // nothing to claim yet
            }
        }
        if (failure != null) {
            Label failed = new Label(tr("status.update.failed", failure));
            failed.getStyleClass().add("about-update-failed");
            failed.setWrapText(true);
            box.getChildren().add(failed);
        }
        Label when = new Label(
                status.checkedAtMs() > 0
                        ? tr(
                                "about.lastChecked",
                                HistoryRowText.fullText(
                                        status.checkedAtMs(),
                                        java.time.ZoneId.systemDefault(),
                                        FileHistoryPanel.locale()))
                        : tr("about.notChecked"));
        when.getStyleClass().add("about-checked");
        Hyperlink check = aboutLink(tr("about.checkNow"), () -> {});
        check.setOnAction(e -> {
            check.setText(tr("about.checking"));
            check.setDisable(true);
            updates.checkNow(error -> {
                Window panel = box.getScene() == null ? null : box.getScene().getWindow();
                if (panel == null || !panel.isShowing()) {
                    return; // closed while the check ran
                }
                showUpdateStatus(box, updates, alert, openUrl, error);
                panel.sizeToScene(); // a new line (the verdict, a failure) needs the room
            });
        });
        HBox line = new HBox(8, when, check);
        line.setAlignment(Pos.BASELINE_LEFT);
        box.getChildren().add(line);
    }

    /** A borderless link row for the About panel. */
    private static Hyperlink aboutLink(String text, Runnable onClick) {
        Hyperlink link = new Hyperlink(text);
        link.setPadding(Insets.EMPTY);
        link.setOnAction(e -> onClick.run());
        return link;
    }

    /** The monospace environment block, also what "Copy details" puts on the clipboard. */
    private static String environmentDetails(String versionString, String commit, Path settingsFile) {
        StringBuilder sb = new StringBuilder();
        sb.append(com.editora.AppInfo.NAME)
                .append(' ')
                .append(versionString)
                .append(" (")
                .append(tr("about.built", com.editora.AppInfo.buildTime()))
                .append(")\n");
        if (commit != null && !commit.isBlank()) {
            sb.append(tr("about.commit", commit)).append('\n');
        }
        sb.append("Java: ")
                .append(System.getProperty("java.version", "?"))
                .append(" (")
                .append(System.getProperty("java.vendor", "?"))
                .append(")\n");
        sb.append("JavaFX: ")
                .append(System.getProperty("javafx.runtime.version", "?"))
                .append('\n');
        sb.append("OS: ")
                .append(System.getProperty("os.name", "?"))
                .append(' ')
                .append(System.getProperty("os.version", "?"))
                .append(" (")
                .append(System.getProperty("os.arch", "?"))
                .append(")\n");
        Path configDir = settingsFile == null ? null : settingsFile.getParent();
        if (configDir != null) {
            sb.append("Config: ").append(SettingsWindow.displaySettingsPath(configDir));
        }
        return sb.toString();
    }
}
