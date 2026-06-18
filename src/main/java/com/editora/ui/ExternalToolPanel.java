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
 * The "External Tools" output tool window: a read-only console showing the captured stdout/stderr of a
 * one-shot external-tool run, with a header (tool — command, exit code) and a Clear action. A
 * {@link ToolWindowContent} modeled on {@link RunPanel} but without the Stop/stdin controls (External Tools
 * runs are one-shot; stdin, if any, is fed at launch). Reuses the {@code run-*} styles + the shared
 * double-click stack-trace link handler so paths in output are clickable.
 */
public final class ExternalToolPanel extends VBox implements ToolWindowContent {

    private static final int MAX_CHARS = 200_000;

    private final Label status = new Label();
    private final TextArea output = new TextArea();
    private Consumer<StackTraceLinks.Link> onLink;

    public ExternalToolPanel() {
        getStyleClass().add("run-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));

        status.getStyleClass().add("run-status");
        status.setText(tr("externalTool.idle"));
        Button clear = new Button(tr("run.clear"));
        clear.setOnAction(e -> {
            output.clear();
            status.setText(tr("externalTool.idle"));
        });

        HBox header = new HBox(8, status, spacer(), clear);
        header.setAlignment(Pos.CENTER_LEFT);

        output.setEditable(false);
        output.setWrapText(false);
        output.getStyleClass().add("run-output");
        RunPanel.installLinkClicks(output, () -> onLink);

        VBox.setVgrow(output, Priority.ALWAYS);
        getChildren().addAll(header, output);
    }

    public void setOnLink(Consumer<StackTraceLinks.Link> onLink) {
        this.onLink = onLink;
    }

    /** Shows one tool run's output: a header (name + command), then stdout, then stderr, then the status line. */
    public void show(String toolName, String commandLine, String out, String err, int exit) {
        status.setText(exit == 0 ? tr("externalTool.done", toolName) : tr("externalTool.exited", toolName, exit));
        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(commandLine).append('\n');
        if (out != null && !out.isEmpty()) {
            sb.append(out);
            if (!out.endsWith("\n")) {
                sb.append('\n');
            }
        }
        if (err != null && !err.isEmpty()) {
            sb.append(err);
            if (!err.endsWith("\n")) {
                sb.append('\n');
            }
        }
        setText(sb.toString());
    }

    private void setText(String text) {
        String t = text;
        if (t.length() > MAX_CHARS) {
            t = t.substring(t.length() - MAX_CHARS);
        }
        output.setText(t);
        output.positionCaret(output.getLength());
        output.setScrollTop(Double.MAX_VALUE);
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    @Override
    public void focusFirstItem() {
        output.requestFocus();
    }
}
