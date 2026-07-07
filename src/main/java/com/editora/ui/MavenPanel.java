package com.editora.ui;

import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.run.StackTraceLinks;

import static com.editora.i18n.Messages.tr;

/**
 * The "Maven" tool window: a console that streams a running Maven invocation's stdout/stderr (see
 * {@code com.editora.maven.MavenService}), with a header showing the run state plus Stop and Clear
 * actions. Modeled directly on {@link RunPanel} minus the stdin field (a Maven build isn't interactive);
 * reuses the same {@code .run-panel}/{@code .run-output}/{@code .run-status} CSS classes and the shared
 * {@code run.*} i18n status strings.
 */
public final class MavenPanel extends VBox implements ToolWindowContent {

    private static final int MAX_CHARS = 200_000;

    private final Label status = new Label();
    private final TextArea output = new TextArea();
    private final Button stopButton = new Button();
    private final Button clearButton = new Button();
    private Consumer<StackTraceLinks.Link> onLink;

    public MavenPanel(Runnable onStop) {
        getStyleClass().add("run-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));

        status.getStyleClass().add("run-status");
        stopButton.setText(tr("run.stop"));
        stopButton.getStyleClass().add("run-stop");
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> {
            if (onStop != null) {
                onStop.run();
            }
        });
        clearButton.setText(tr("run.clear"));
        clearButton.setOnAction(e -> clearConsole());

        HBox header = new HBox(8, status, spacer(), clearButton, stopButton);
        header.setAlignment(Pos.CENTER_LEFT);

        output.setEditable(false);
        output.setWrapText(false);
        output.getStyleClass().add("run-output");
        RunPanel.installLinkClicks(output, () -> onLink);

        VBox.setVgrow(output, Priority.ALWAYS);
        getChildren().addAll(header, output);
        idle();
    }

    public void setOnLink(Consumer<StackTraceLinks.Link> onLink) {
        this.onLink = onLink;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    public void idle() {
        status.setText(tr("run.idle"));
        stopButton.setDisable(true);
    }

    public void clearConsole() {
        output.clear();
    }

    public void started(String commandLine) {
        output.clear();
        status.setText(tr("run.running", commandLine));
        stopButton.setDisable(false);
    }

    public void appendOutput(String line, boolean stderr) {
        output.appendText(line + "\n");
        int len = output.getLength();
        if (len > MAX_CHARS) {
            String kept = output.getText(len - MAX_CHARS, len);
            int nl = kept.indexOf('\n');
            output.replaceText(0, len, nl >= 0 ? kept.substring(nl + 1) : kept);
        }
        output.positionCaret(output.getLength());
        output.setScrollTop(Double.MAX_VALUE);
    }

    public void finished(int code) {
        status.setText(code < 0 ? tr("run.stopped") : tr("run.exited", code));
        stopButton.setDisable(true);
    }

    public void failed(String message) {
        status.setText(tr("run.failed", message));
        stopButton.setDisable(true);
    }

    @Override
    public void focusFirstItem() {
        output.requestFocus();
    }
}
