package com.editora.ui;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.run.StackTraceLinks;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The "External Tools" output tool window: a read-only console showing the captured stdout/stderr of a
 * one-shot external-tool run, with a header (tool — command, exit code; a spinner while a tool runs) and a
 * Clear action. A
 * {@link ToolWindowContent} modeled on {@link RunPanel} but without the Stop/stdin controls (External Tools
 * runs are one-shot; stdin, if any, is fed at launch). A RichTextFX {@link CodeArea} so stderr is colored
 * ({@code .text.run-stderr}); reuses the {@code run-*} styles + the shared double-click stack-trace link
 * handler so paths in output are clickable.
 */
public final class ExternalToolPanel extends VBox implements ToolWindowContent {

    private static final int MAX_CHARS = 200_000;

    private final Label status = new Label();
    /** Spins from {@link #started} until every launched tool has {@link #ended} (runs queue one at a time). */
    private final ProgressIndicator busy = RunPanel.busySpinner();

    private final CodeArea output = AreaUndo.none(new CodeArea());
    private @Nullable Consumer<StackTraceLinks.Link> onLink;

    /** Tools launched and not yet {@link #ended}. */
    private int running;

    /** The header once nothing runs: idle, or the last {@link #show}n run's result. */
    private String settledStatus = tr("externalTool.idle");

    public ExternalToolPanel() {
        getStyleClass().add("run-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));

        status.getStyleClass().add("run-status");
        status.setText(settledStatus);
        Button clear = new Button(tr("run.clear"));
        clear.setMinWidth(Region.USE_PREF_SIZE);
        clear.setOnAction(e -> clearConsole());

        HBox header = new HBox(8, busy, status, spacer(), clear);
        header.setAlignment(Pos.CENTER_LEFT);

        output.setEditable(false);
        output.setWrapText(false);
        output.getStyleClass().addAll("editor-area", "run-output");
        RunPanel.installLinkClicks(output, () -> onLink);
        ConsoleNav.installShared(output); // configured-keymap scrolling while the console has focus

        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(output);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(header, scroll);
    }

    public void setOnLink(Consumer<StackTraceLinks.Link> onLink) {
        this.onLink = onLink;
    }

    /** Matches the console font to the editor's code-area font (family + effective size). */
    public void setOutputFont(String family, int size) {
        output.setStyle("-fx-font-family: \"" + family + "\"; -fx-font-size: " + size + "px;");
    }

    /** Clears the output console (the Clear button + the {@code externalTool.clearOutput} palette command). */
    public void clearConsole() {
        output.clear();
        settle(tr("externalTool.idle"));
    }

    /** A tool was launched: the header says so and spins until it has {@link #ended}. */
    public void started(String toolName) {
        running++;
        status.setText(tr("status.externalTool.running", toolName));
        RunPanel.showSpinner(busy, true);
    }

    /** A launched tool's result is back (before it is {@link #show}n, if it is). The last one stops the spinner
     *  and puts back the settled header — a tool whose output went into the editor leaves the previous run's. */
    public void ended() {
        running = Math.max(0, running - 1);
        if (running == 0) {
            RunPanel.showSpinner(busy, false);
            status.setText(settledStatus);
        }
    }

    /** Records the header for when nothing runs; shows it now unless a queued tool is still running. */
    private void settle(String text) {
        settledStatus = text;
        if (running == 0) {
            status.setText(text);
        }
    }

    /** Shows one tool run's output: a header (name + command), then stdout, then stderr (colored), then the
     *  status line. */
    public void show(String toolName, String commandLine, String out, String err, int exit) {
        settle(exit == 0 ? tr("externalTool.done", toolName) : tr("externalTool.exited", toolName, exit));
        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(commandLine).append('\n');
        if (out != null && !out.isEmpty()) {
            sb.append(out);
            if (!out.endsWith("\n")) {
                sb.append('\n');
            }
        }
        int errStart = sb.length();
        if (err != null && !err.isEmpty()) {
            sb.append(err);
            if (!err.endsWith("\n")) {
                sb.append('\n');
            }
        }
        setText(sb.toString(), errStart, sb.length() - errStart);
    }

    /** Replaces the console text and tints the {@code [errStart, errStart+errLen)} stderr range, accounting for
     *  any front-trim done to honor the char cap. */
    private void setText(String text, int errStart, int errLen) {
        int cut = Math.max(0, text.length() - MAX_CHARS);
        String t = cut > 0 ? text.substring(cut) : text;
        output.replaceText(t);
        if (errLen > 0) {
            int start = Math.max(0, errStart - cut);
            int end = Math.min(t.length(), errStart + errLen - cut);
            if (end > start) {
                StyleSpans<Collection<String>> spans = new StyleSpansBuilder<Collection<String>>()
                        .add(List.of("run-stderr"), end - start)
                        .create();
                output.setStyleSpans(start, spans);
            }
        }
        output.moveTo(output.getLength());
        output.requestFollowCaret();
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
