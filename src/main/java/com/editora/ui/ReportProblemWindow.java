package com.editora.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.AppInfo;
import com.editora.plugin.PluginDescriptor;
import com.editora.plugin.PluginManager;
import com.editora.report.ProblemReport;
import com.editora.report.Redactor;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Help → Report a Problem ({@code help.reportProblem}): the redacted problem report in an editable area, for the
 * user to read and trim, then Open GitHub Issue — which copies it and opens a new issue with it filled in (or,
 * when it is too long for a link, says to paste it). Copy and Save… are there for any other channel. Nothing is
 * sent from here: the user submits the issue on GitHub.
 */
public final class ReportProblemWindow {

    /** Shown in the issue's report field when the report itself did not fit in the link (GitHub's form is English). */
    static final String PASTE_HINT =
            "Paste the report here: Editora copied it to your clipboard. Or drag a saved copy into this box.";

    private final Supplier<ProblemReport.Assembled> source;
    private final Consumer<String> openUrl;

    // Built on the first show(), like the Debug Log window: most sessions never open this.
    private @Nullable Stage stage;
    private @Nullable TextArea area;
    private @Nullable Label summary;
    private @Nullable Label status;
    private @Nullable Button openIssue;
    private String systemDetails = "";
    /** Bumped by each show(): a report still being prepared for an earlier one is dropped. */
    private int generation;

    /**
     * @param source builds the report — called off the FX thread (it reads and redacts two log files)
     * @param openUrl opens the new-issue link in the browser
     */
    public ReportProblemWindow(Supplier<ProblemReport.Assembled> source, Consumer<String> openUrl) {
        this.source = source;
        this.openUrl = openUrl;
    }

    /**
     * The window for one editor window: the report over {@code configDir}'s session logs ({@link DebugLog}'s
     * in-memory capture standing in when this session's file cannot be read) and the plugins {@code plugins}
     * has found.
     */
    static ReportProblemWindow forConfigDir(
            Path configDir, Supplier<@Nullable PluginManager> plugins, Consumer<String> openUrl) {
        return new ReportProblemWindow(
                () -> ProblemReport.assemble(
                        Objects.requireNonNull(DebugLog.sessionFile(configDir)),
                        Objects.requireNonNull(DebugLog.previousSessionFile(configDir)),
                        pluginLines(plugins.get()),
                        DebugLog.snapshot(),
                        Redactor.forThisMachine()),
                openUrl);
    }

    /** {@code id 1.2.0}, {@code id 1.2.0 (disabled)} — one per discovered plugin. */
    static List<String> pluginLines(@Nullable PluginManager pm) {
        List<String> lines = new ArrayList<>();
        if (pm != null) {
            for (PluginDescriptor d : pm.descriptors()) {
                String version = d.manifest().version;
                lines.add(d.id()
                        + (version == null || version.isBlank() ? "" : " " + version)
                        + (d.enabled() ? "" : " (disabled)"));
            }
        }
        return lines;
    }

    public void show(Window owner) {
        Stage s = stage;
        if (s == null) {
            s = build(owner);
        }
        prepare();
        if (s.isShowing()) {
            s.toFront();
        } else {
            WindowPlacement.centerOnOwner(s, owner, 820, 620);
            s.show();
        }
    }

    private Stage build(Window owner) {
        Stage stage = new Stage();
        stage.setTitle(tr("report.title"));
        if (owner != null) {
            stage.initOwner(owner);
        }

        Label intro = new Label(tr("report.intro"));
        intro.setWrapText(true);
        Label summary = new Label();
        summary.getStyleClass().add("debug-log-file");
        summary.setWrapText(true);

        TextArea area = new TextArea();
        area.setWrapText(false);
        area.getStyleClass().add("debug-log-area");
        VBox.setVgrow(area, Priority.ALWAYS);

        Label status = new Label();
        status.getStyleClass().add("debug-log-file");
        status.setWrapText(true);

        Button openIssue = new Button(tr("report.openIssue"));
        openIssue.setDefaultButton(true);
        openIssue.setOnAction(e -> openIssue());
        Button copy = new Button(tr("report.copy"));
        copy.setOnAction(e -> {
            copyToClipboard(area.getText());
            status.setText(tr("report.copied"));
        });
        Button save = new Button(tr("report.save"));
        save.setOnAction(e -> save());
        Button close = new Button(tr("report.close"));
        close.setCancelButton(true);
        close.setOnAction(e -> stage.hide());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, openIssue, copy, save, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(8, intro, summary, area, status, buttons);
        root.setPadding(new Insets(10));

        Scene scene = new Scene(root, 820, 620);
        scene.getStylesheets()
                .addAll(
                        ReportProblemWindow.class
                                .getResource("/com/editora/styles/app.css")
                                .toExternalForm(),
                        ReportProblemWindow.class
                                .getResource("/com/editora/styles/syntax.css")
                                .toExternalForm());
        stage.setScene(scene);
        this.stage = stage;
        this.area = area;
        this.summary = summary;
        this.status = status;
        this.openIssue = openIssue;
        return stage;
    }

    /** Rebuilds the report off the FX thread — the logs have grown since the last show — and fills the window. */
    private void prepare() {
        TextArea area = this.area;
        Label summary = this.summary;
        Label status = this.status;
        Button openIssue = this.openIssue;
        if (area == null || summary == null || status == null || openIssue == null) {
            return; // not built yet
        }
        int mine = ++generation;
        area.setEditable(false);
        area.setText(tr("report.preparing"));
        summary.setText("");
        status.setText("");
        openIssue.setDisable(true);
        Thread.ofVirtual().name("problem-report").start(() -> {
            ProblemReport.Assembled report;
            try {
                report = source.get();
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    if (mine == generation) {
                        area.setText(tr("report.failed", String.valueOf(e.getMessage())));
                    }
                });
                return;
            }
            Platform.runLater(() -> {
                if (mine != generation) {
                    return;
                }
                systemDetails = report.systemDetails();
                area.setText(report.text());
                area.setEditable(true);
                area.positionCaret(0);
                area.setScrollTop(0);
                summary.setText(
                        report.redacted() == 0 ? tr("report.redactedNone") : tr("report.redacted", report.redacted()));
                openIssue.setDisable(false);
            });
        });
    }

    /** Copies the report as it now reads (the user may have trimmed it) and opens the new-issue page. */
    private void openIssue() {
        TextArea area = this.area;
        Label status = this.status;
        if (area == null || status == null) {
            return;
        }
        String report = area.getText();
        copyToClipboard(report);
        openUrl.accept(ProblemReport.issueUrl(AppInfo.GITHUB_REPO, systemDetails, report, PASTE_HINT));
        status.setText(
                ProblemReport.fitsInIssueUrl(AppInfo.GITHUB_REPO, systemDetails, report)
                        ? tr("report.openedInline")
                        : tr("report.openedPaste"));
    }

    private void save() {
        TextArea area = this.area;
        Label status = this.status;
        if (area == null || status == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("report.save"));
        chooser.setInitialFileName(ProblemReport.fileName(LocalDateTime.now()));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text", "*.txt"));
        java.io.File target = chooser.showSaveDialog(stage);
        if (target != null) {
            try {
                com.editora.io.StagedExport.writeString(target.toPath(), area.getText());
                status.setText(tr("report.saved", target.toPath().toString()));
            } catch (IOException ex) {
                status.setText(tr("report.saveFailed", String.valueOf(ex.getMessage())));
            }
        }
    }

    private static void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
