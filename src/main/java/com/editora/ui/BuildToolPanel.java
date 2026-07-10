package com.editora.ui;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.build.OutputStyle;
import com.editora.run.StackTraceLinks;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.Caret;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import static com.editora.i18n.Messages.tr;

/**
 * A build tool's console tool window: streams a running invocation's stdout/stderr (see
 * {@code com.editora.build.BuildService}) with a header showing the run state plus Stop and Clear. Modeled on
 * {@link RunPanel} minus the stdin field (a build isn't interactive), using a read-only RichTextFX
 * {@link CodeArea} so each line can be colored by the tool's {@link OutputStyle}. Reuses the
 * {@code .run-panel}/{@code .run-status} CSS + the shared {@code run.*} status strings. One instance per tool.
 */
public final class BuildToolPanel extends VBox implements ToolWindowContent {

    /** Trim the console once it exceeds this many characters (keeps the most recent output). */
    private static final int MAX_CHARS = 200_000;

    private final Label status = new Label();
    private final CodeArea output = new CodeArea();
    private final Button stopButton = new Button();
    private final Button clearButton = new Button();
    private final OutputStyle style;
    private Consumer<StackTraceLinks.Link> onLink;

    public BuildToolPanel(Runnable onStop, OutputStyle style) {
        this.style = style == null ? OutputStyle.passthrough() : style;
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
        output.setFocusTraversable(true);
        output.setShowCaret(Caret.CaretVisibility.OFF);
        output.getStyleClass().addAll("editor-area", "run-output");
        RunPanel.installLinkClicks(output, () -> onLink);

        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(output);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        getChildren().addAll(header, scrollPane);
        idle();
    }

    public void setOnLink(Consumer<StackTraceLinks.Link> onLink) {
        this.onLink = onLink;
    }

    /** Matches the console font to the editor's code-area font (family + effective size). */
    public void setOutputFont(String family, int size) {
        output.setStyle("-fx-font-family: \"" + family + "\"; -fx-font-size: " + size + "px;");
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

    /** Clears the console output (the Clear button + the {@code run.clear} palette command). */
    public void clearConsole() {
        output.clear();
    }

    public void started(String commandLine) {
        output.clear();
        status.setText(tr("run.running", commandLine));
        stopButton.setDisable(false);
    }

    /** Appends one line of output, colored per the tool's {@link OutputStyle}, auto-scrolling to the bottom. */
    public void appendOutput(String line, boolean stderr) {
        int start = output.getLength();
        output.appendText(line + "\n");
        String styleClass = style.styleClassFor(line);
        if (styleClass != null) {
            StyleSpans<Collection<String>> spans = new StyleSpansBuilder<Collection<String>>()
                    .add(List.of(styleClass), line.length())
                    .create();
            output.setStyleSpans(start, spans);
        }
        int len = output.getLength();
        if (len > MAX_CHARS) {
            output.deleteText(0, len - MAX_CHARS);
        }
        output.moveTo(output.getLength());
        output.requestFollowCaret();
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
