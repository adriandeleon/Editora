package com.editora.ui;

import java.io.IOException;
import java.nio.file.Path;

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
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A simple modeless window that displays {@link DebugLog}'s captured log (java.util.logging output +
 * uncaught exceptions) in a read-only monospace area, with Refresh / Copy / Clear / Export / Report a
 * Problem. Opened by the {@code view.debugLog} command and the Settings → Advanced "Show Debug Log" button — the
 * way to see logs in a packaged build where stderr isn't visible.
 */
public final class DebugLogWindow {

    // Built on the first show(): a Stage and a TextArea are not free, and most sessions never open this.
    private @Nullable Stage stage;
    private @Nullable TextArea area;
    private @Nullable Label fileLabel;
    private @Nullable Path sessionFile;
    private @Nullable Runnable onReport;

    /** The session log file path to show in the footer (from {@code DebugLog.sessionFile(configDir)}). */
    public void setSessionFile(@Nullable Path sessionFile) {
        this.sessionFile = sessionFile;
    }

    /** What the Report a Problem… button does (the {@code help.reportProblem} command); no button without it. */
    public void setOnReport(@Nullable Runnable onReport) {
        this.onReport = onReport;
    }

    public void show(Window owner) {
        Stage s = stage;
        if (s == null) {
            s = build(owner);
        }
        refresh();
        if (s.isShowing()) {
            s.toFront();
        } else {
            WindowPlacement.centerOnOwner(s, owner, 900, 600);
            s.show();
        }
    }

    /** Builds the window; its parts are recorded only once it is complete, so a failed build is retried. */
    private Stage build(Window owner) {
        Stage stage = new Stage();
        TextArea area = new TextArea();
        Label fileLabel = new Label();
        stage.setTitle(tr("debuglog.title"));
        if (owner != null) {
            stage.initOwner(owner);
        }

        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("debug-log-area");
        area.setPromptText(tr("debuglog.empty"));
        VBox.setVgrow(area, Priority.ALWAYS);

        Button refresh = new Button(tr("debuglog.refresh"));
        refresh.setOnAction(e -> refresh());
        Button copy = new Button(tr("debuglog.copy"));
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(area.getText());
            Clipboard.getSystemClipboard().setContent(cc);
        });
        Button clear = new Button(tr("debuglog.clear"));
        clear.setOnAction(e -> {
            DebugLog.clear();
            refresh();
        });
        Button export = new Button(tr("debuglog.export"));
        export.setOnAction(e -> export());
        Button close = new Button(tr("debuglog.close"));
        close.setOnAction(e -> stage.hide());

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS); // pushes Close to the right
        HBox buttons = new HBox(8, refresh, copy, clear, export);
        Runnable onReport = this.onReport;
        if (onReport != null) {
            Button report = new Button(tr("debuglog.report"));
            report.setOnAction(e -> onReport.run());
            buttons.getChildren().add(report);
        }
        buttons.getChildren().addAll(spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        fileLabel.getStyleClass().add("debug-log-file");
        fileLabel.setWrapText(true);

        VBox root = new VBox(8, area, fileLabel, buttons);
        root.setPadding(new Insets(10));
        VBox.setVgrow(area, Priority.ALWAYS);

        Scene scene = new Scene(root, 900, 600);
        scene.getStylesheets()
                .addAll(
                        DebugLogWindow.class
                                .getResource("/com/editora/styles/app.css")
                                .toExternalForm(),
                        DebugLogWindow.class
                                .getResource("/com/editora/styles/syntax.css")
                                .toExternalForm());
        stage.setScene(scene);
        this.stage = stage;
        this.area = area;
        this.fileLabel = fileLabel;
        return stage;
    }

    private void refresh() {
        TextArea area = this.area;
        Label fileLabel = this.fileLabel;
        if (area == null || fileLabel == null) {
            return; // not built yet
        }
        area.setText(DebugLog.snapshot());
        area.positionCaret(area.getLength());
        area.setScrollTop(Double.MAX_VALUE); // keep the newest entries in view
        fileLabel.setText(sessionFile == null ? "" : tr("debuglog.file", sessionFile.toString()));
    }

    private void export() {
        TextArea area = this.area;
        Label fileLabel = this.fileLabel;
        if (area == null || fileLabel == null) {
            return; // not built yet
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("debuglog.export"));
        chooser.setInitialFileName("editora-log.txt");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Log/Text", "*.log", "*.txt"));
        java.io.File target = chooser.showSaveDialog(stage);
        if (target != null) {
            try {
                // Staged: a failed write must not empty a log the Save dialog agreed to replace.
                com.editora.io.StagedExport.writeString(target.toPath(), area.getText());
            } catch (IOException ex) {
                fileLabel.setText(tr("debuglog.exportFailed", ex.getMessage()));
            }
        }
    }
}
