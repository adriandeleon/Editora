package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

/**
 * A simple modeless window that displays {@link DebugLog}'s captured log (java.util.logging output +
 * uncaught exceptions) in a read-only monospace area, with Refresh / Copy / Clear / Export. Opened by
 * the {@code view.debugLog} command and the Settings → Advanced "Show Debug Log" button — the way to
 * see logs in a packaged build where stderr isn't visible.
 */
public final class DebugLogWindow {

    private final Stage stage = new Stage();
    private final TextArea area = new TextArea();
    private final Label fileLabel = new Label();
    private Path sessionFile;
    private boolean built;

    /** The session log file path to show in the footer (from {@code DebugLog.sessionFile(configDir)}). */
    public void setSessionFile(Path sessionFile) {
        this.sessionFile = sessionFile;
    }

    public void show(Window owner) {
        if (!built) {
            build(owner);
            built = true;
        }
        refresh();
        if (stage.isShowing()) {
            stage.toFront();
        } else {
            if (owner != null) {
                stage.setX(owner.getX() + Math.max(0, (owner.getWidth() - 900) / 2));
                stage.setY(owner.getY() + Math.max(0, (owner.getHeight() - 600) / 2));
            }
            stage.show();
        }
    }

    private void build(Window owner) {
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

        HBox buttons = new HBox(8, refresh, copy, clear, export, new javafx.scene.layout.Region(), close);
        HBox.setHgrow(buttons.getChildren().get(4), Priority.ALWAYS); // spacer pushes Close to the right
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
    }

    private void refresh() {
        area.setText(DebugLog.snapshot());
        area.positionCaret(area.getLength());
        area.setScrollTop(Double.MAX_VALUE); // keep the newest entries in view
        fileLabel.setText(sessionFile == null ? "" : tr("debuglog.file", sessionFile.toString()));
    }

    private void export() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("debuglog.export"));
        chooser.setInitialFileName("editora-log.txt");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Log/Text", "*.log", "*.txt"));
        java.io.File target = chooser.showSaveDialog(stage);
        if (target != null) {
            try {
                Files.writeString(target.toPath(), area.getText(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                fileLabel.setText(tr("debuglog.exportFailed", ex.getMessage()));
            }
        }
    }
}
