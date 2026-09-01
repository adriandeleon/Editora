package com.editora.ui;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.build.OutputStyle;
import com.editora.run.ConsoleUrls;
import com.editora.run.StackTraceLinks;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.Caret;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import static com.editora.i18n.Messages.tr;

/**
 * One build tool's console — a single tab inside the shared {@link BuildOutputPanel} "Output" tool
 * window. Streams that tool's running invocation's stdout/stderr (see {@code com.editora.build.BuildService})
 * with a header showing the run state plus Stop and Clear. {@link BuildOutputPanel} creates one of these per
 * tool that runs, so Maven/npm/Cargo/Go/Gradle each get their own tab. Modeled on {@link RunPanel} minus the
 * stdin field (a build isn't interactive), using a read-only RichTextFX {@link CodeArea} so each line can be
 * colored by the tool's {@link OutputStyle}. Reuses the {@code .run-panel}/{@code .run-status} CSS + the
 * shared {@code run.*} status strings.
 */
public final class BuildToolPanel extends VBox implements ToolWindowContent {

    /** Trim the console once it exceeds this many characters (keeps the most recent output). */
    private static final int MAX_CHARS = 200_000;

    private final Label status = new Label();
    private final CodeArea output = new CodeArea();
    private final Button stopButton = new Button();
    private final Button clearButton = new Button();
    private Consumer<StackTraceLinks.Link> onLink;
    private Consumer<String> onUrl;

    /** This tool's output style (set per run by {@link #started}). */
    private OutputStyle style = OutputStyle.passthrough();
    /** Stops this tool's running build (set per run by {@link #started}). */
    private Runnable onStop;

    public BuildToolPanel() {
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
        output.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY || e.getClickCount() != 1 || onUrl == null) {
                return;
            }
            int offset = output.hit(e.getX(), e.getY()).getInsertionIndex();
            ConsoleUrls.Link link = ConsoleUrls.at(output.getText(), offset);
            if (link != null) {
                onUrl.accept(link.url());
                e.consume();
            }
        });
        output.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> {
            int offset = output.hit(e.getX(), e.getY()).getInsertionIndex();
            output.setCursor(ConsoleUrls.at(output.getText(), offset) == null ? Cursor.TEXT : Cursor.HAND);
        });
        output.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED, e -> output.setCursor(null));
        ConsoleNav.installShared(output);
        ConsoleContextMenu.install(output); // right-click Select All / Copy

        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(output);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        getChildren().addAll(header, scrollPane);
        idle();
    }

    public void setOnLink(Consumer<StackTraceLinks.Link> onLink) {
        this.onLink = onLink;
    }

    public void setOnUrl(Consumer<String> onUrl) {
        this.onUrl = onUrl;
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

    /**
     * Turns this tab into an append-only <b>transcript</b> of finished one-shot commands (Git/GitHub) rather
     * than a streaming build console: there is nothing to stop, so the Stop button goes away entirely
     * instead of sitting permanently disabled.
     */
    public void setLogMode(boolean logMode) {
        stopButton.setVisible(!logMode);
        stopButton.setManaged(!logMode);
        if (logMode) {
            status.setText(tr("console.log.idle"));
        }
    }

    /** In log mode, the header line — the command most recently appended. */
    public void setLogStatus(String text) {
        status.setText(text);
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

    /** A build starts in this tab: set the header/style/Stop action and clear any prior output. */
    public void started(String header, OutputStyle style, Runnable onStop) {
        this.style = style == null ? OutputStyle.passthrough() : style;
        this.onStop = onStop;
        output.clear();
        status.setText(tr("run.running", header));
        stopButton.setDisable(false);
    }

    /**
     * Appends one line, colored per this tool's {@link OutputStyle}, auto-scrolling to the bottom — but
     * only while the caret is already at the end. A user who has scrolled back through a running build's
     * output (see {@link ConsoleNav}) stays put instead of being yanked to the tail on every new line;
     * navigating back to the end resumes the follow.
     */
    public void appendOutput(String line, boolean stderr) {
        appendStyled(line, style.styleClassFor(line));
    }

    /**
     * Appends one line under a caller-chosen {@code .text.<class>} ({@code null} = default foreground), with
     * the same follow/trim behaviour as {@link #appendOutput}. Used by the Git/GitHub command log, whose
     * colouring comes from {@code CommandLogFormat} rather than a build tool's {@link OutputStyle}.
     */
    public void appendStyled(String line, String styleClass) {
        int start = output.getLength();
        int caretBefore = output.getCaretPosition();
        boolean follow = caretBefore >= start;
        output.appendText(line + "\n");
        if (!line.isEmpty()) {
            StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
            int offset = 0;
            for (ConsoleUrls.Link link : ConsoleUrls.find(line)) {
                if (link.start() > offset) {
                    builder.add(styleClass == null ? List.of() : List.of(styleClass), link.start() - offset);
                }
                builder.add(styleClass == null ? List.of("console-url") : List.of(styleClass, "console-url"),
                        link.end() - link.start());
                offset = link.end();
            }
            if (offset < line.length()) {
                builder.add(styleClass == null ? List.of() : List.of(styleClass), line.length() - offset);
            }
            StyleSpans<Collection<String>> spans = builder.create();
            output.setStyleSpans(start, spans);
        }
        ConsoleNav.afterAppend(output, caretBefore, follow, MAX_CHARS);
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
