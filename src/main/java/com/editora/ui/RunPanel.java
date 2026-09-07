package com.editora.ui;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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
 * The "Run" tool window: a console that streams a launched program's stdout/stderr (see
 * {@link com.editora.run.RunService}), with a header showing the run state plus Stop and Clear actions.
 * A {@link ToolWindowContent} modeled on {@link ProblemsPanel}; the controller drives it via
 * {@code started/appendOutput/finished/failed} on the FX thread.
 *
 * <p>Output goes into a read-only monospace RichTextFX {@link CodeArea} (like the build console) whose
 * contents are capped so a runaway program can't grow memory without bound. stdout and stderr are
 * interleaved in arrival order; stderr lines are colored ({@code .text.run-stderr}) so error output — e.g. a
 * Python traceback — stands out from normal output.
 */
public final class RunPanel extends VBox implements ToolWindowContent {

    /** Trim the console once it exceeds this many characters (keeps the most recent output). */
    private static final int MAX_CHARS = 200_000;

    private final Label status = new Label();
    private final CodeArea output = new CodeArea();
    private final TextField input = new TextField();
    private final Button stopButton = new Button();
    private final Button clearButton = new Button();
    /** Receives a line typed into the stdin field (controller → process stdin). */
    private Consumer<String> onInput;
    /** Receives a double-clicked stack-trace location (controller resolves + jumps). */
    private Consumer<StackTraceLinks.Link> onLink;
    /** Receives a clicked HTTP(S) URL (controller opens it in the system browser). */
    private Consumer<String> onUrl;

    private final OutputStyle lineStyle = OutputStyle.console();

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
        clearButton.setOnAction(e -> clearConsole());

        HBox header = new HBox(8, status, spacer(), clearButton, stopButton);
        header.setAlignment(Pos.CENTER_LEFT);

        output.setEditable(false);
        output.setWrapText(false);
        output.setFocusTraversable(true);
        output.setShowCaret(Caret.CaretVisibility.OFF);
        output.getStyleClass().addAll("editor-area", "run-output");
        installLinkClicks(output, () -> onLink);
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
        ConsoleNav.installShared(output); // configured-keymap scrolling while the console has focus
        ConsoleContextMenu.install(output); // right-click Select All / Copy

        // stdin: one line per Enter, echoed into the console (the program won't echo it back).
        input.getStyleClass().add("run-input");
        input.setPromptText(tr("run.stdinPrompt"));
        input.setDisable(true);
        input.setOnAction(e -> {
            String line = input.getText();
            if (line == null || onInput == null) {
                return;
            }
            appendOutput(line, false);
            onInput.accept(line);
            input.clear();
        });

        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(output);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(header, scroll, input);
        idle();
    }

    public void setOnInput(Consumer<String> onInput) {
        this.onInput = onInput;
    }

    public void setOnLink(Consumer<StackTraceLinks.Link> onLink) {
        this.onLink = onLink;
    }

    /** Sets the system-browser opener used by single-clicked HTTP(S) URLs in program output. */
    public void setOnUrl(Consumer<String> onUrl) {
        this.onUrl = onUrl;
    }

    /** Matches the console font to the editor's code-area font (family + effective size). */
    public void setOutputFont(String family, int size) {
        output.setStyle("-fx-font-family: \"" + family + "\"; -fx-font-size: " + size + "px;");
    }

    /** Double-clicking a console line that holds a stack-trace location jumps to it. Shared with the
     *  Debug console (see {@link DebugPanel}). */
    static void installLinkClicks(
            TextArea console, java.util.function.Supplier<Consumer<StackTraceLinks.Link>> handler) {
        console.setOnMouseClicked(e -> {
            Consumer<StackTraceLinks.Link> h = handler.get();
            if (h == null || e.getClickCount() != 2) {
                return;
            }
            String text = console.getText();
            int caret = Math.min(console.getCaretPosition(), text.length());
            int start = text.lastIndexOf('\n', Math.max(0, caret - 1)) + 1;
            int end = text.indexOf('\n', caret);
            StackTraceLinks.Link link = StackTraceLinks.parse(text.substring(start, end < 0 ? text.length() : end));
            if (link != null) {
                h.accept(link);
            }
        });
    }

    /** {@link CodeArea} overload of {@link #installLinkClicks(TextArea, java.util.function.Supplier)} — used
     *  by {@link BuildToolPanel}, whose console is a RichTextFX {@code CodeArea} so it can color lines via
     *  {@code com.editora.build.OutputStyle}. */
    static void installLinkClicks(
            CodeArea console, java.util.function.Supplier<Consumer<StackTraceLinks.Link>> handler) {
        console.setOnMouseClicked(e -> {
            Consumer<StackTraceLinks.Link> h = handler.get();
            if (h == null || e.getClickCount() != 2) {
                return;
            }
            String text = console.getText();
            int caret = Math.min(console.getCaretPosition(), text.length());
            int start = text.lastIndexOf('\n', Math.max(0, caret - 1)) + 1;
            int end = text.indexOf('\n', caret);
            StackTraceLinks.Link link = StackTraceLinks.parse(text.substring(start, end < 0 ? text.length() : end));
            if (link != null) {
                h.accept(link);
            }
        });
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
        input.setDisable(true);
    }

    /** Clears the console output (the Clear button + the {@code run.clear} palette command). */
    public void clearConsole() {
        output.clear();
    }

    /** A run started: clears the console, shows the command, enables Stop + the stdin field. */
    public void started(String commandLine) {
        output.clear();
        status.setText(tr("run.running", commandLine));
        stopButton.setDisable(false);
        input.setDisable(false);
    }

    /** Appends one line of program output, applying stderr/log-level colors and clickable URL styling. */
    public void appendOutput(String line, boolean stderr) {
        int start = output.getLength();
        int caretBefore = output.getCaretPosition();
        boolean follow = caretBefore >= start; // scrolled back? stay put
        output.appendText(line + "\n");
        if (!line.isEmpty()) {
            String styleClass = stderr ? "run-stderr" : lineStyle.styleClassFor(line);
            StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
            int offset = 0;
            for (ConsoleUrls.Link link : ConsoleUrls.find(line)) {
                if (link.start() > offset) {
                    builder.add(styleClass == null ? List.of() : List.of(styleClass), link.start() - offset);
                }
                builder.add(
                        styleClass == null ? List.of("console-url") : List.of(styleClass, "console-url"),
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

    /** The process exited; shows the exit code (or a "stopped" note for a killed run) and disables Stop. */
    public void finished(int code) {
        status.setText(code < 0 ? tr("run.stopped") : tr("run.exited", code));
        stopButton.setDisable(true);
        input.setDisable(true);
    }

    /** The process failed to launch (e.g. {@code java} not found). */
    public void failed(String message) {
        status.setText(tr("run.failed", message));
        stopButton.setDisable(true);
        input.setDisable(true);
    }

    @Override
    public void focusFirstItem() {
        output.requestFocus();
    }
}
