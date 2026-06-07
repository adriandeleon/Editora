package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The "Run" tool window: a console that streams a launched program's stdout/stderr (see
 * {@link com.editora.run.RunService}), with a header showing the run state plus Stop and Clear actions.
 * A {@link ToolWindowContent} modeled on {@link ProblemsPanel}; the controller drives it via
 * {@code started/appendOutput/finished/failed} on the FX thread.
 *
 * <p>Output goes into a read-only monospace {@link TextArea} (cheap and robust for large/streaming text)
 * whose contents are capped so a runaway program can't grow memory without bound. stdout and stderr are
 * interleaved in arrival order; per-stream coloring is a future enhancement.
 */
public final class RunPanel extends VBox implements ToolWindowContent {

    /** Trim the console once it exceeds this many characters (keeps the most recent output). */
    private static final int MAX_CHARS = 200_000;

    private final Label status = new Label();
    private final TextArea output = new TextArea();
    private final Button stopButton = new Button();
    private final Button clearButton = new Button();

    public RunPanel(Runnable onStop) {
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
        clearButton.setOnAction(e -> output.clear());

        HBox header = new HBox(8, status, spacer(), clearButton, stopButton);
        header.setAlignment(Pos.CENTER_LEFT);

        output.setEditable(false);
        output.setWrapText(false);
        output.getStyleClass().add("run-output");

        VBox.setVgrow(output, Priority.ALWAYS);
        getChildren().addAll(header, output);
        idle();
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Resets to the idle state (no run yet / finished and cleared). */
    public void idle() {
        status.setText(tr("run.idle"));
        stopButton.setDisable(true);
    }

    /** A run started: clears the console, shows the command, enables Stop. */
    public void started(String commandLine) {
        output.clear();
        status.setText(tr("run.running", commandLine));
        stopButton.setDisable(false);
    }

    /** Appends one line of program output (stdout or stderr), trims if over the cap, and auto-scrolls. */
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

    /** The process exited; shows the exit code (or a "stopped" note for a killed run) and disables Stop. */
    public void finished(int code) {
        status.setText(code < 0 ? tr("run.stopped") : tr("run.exited", code));
        stopButton.setDisable(true);
    }

    /** The process failed to launch (e.g. {@code java} not found). */
    public void failed(String message) {
        status.setText(tr("run.failed", message));
        stopButton.setDisable(true);
    }

    @Override
    public void focusFirstItem() {
        output.requestFocus();
    }
}
