package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.build.OutputStyle;
import com.editora.run.AnsiText;
import com.editora.run.ConsoleUrls;
import com.editora.run.StackTraceLinks;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import static com.editora.i18n.Messages.tr;

/**
 * The "Run" tool window: a console that streams a launched program's stdout/stderr (see
 * {@link com.editora.run.RunService}), with a header showing the run state (a spinner while something runs)
 * plus Stop and Clear actions.
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
    /** The runtime the program runs under ("Java 25.0.3 (Temurin)"); hidden until the probe answers. */
    private final Label runtime = runtimeChip();
    /** Spins while a program (or a before-launch step) is running, so a quiet long run still looks alive. */
    private final ProgressIndicator busy = busySpinner();
    /** Whether a program (or before-launch step) is alive — the Run tool window's stripe button follows it. */
    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper(this, "running");

    private final CodeArea output = AreaUndo.none(new CodeArea());
    private final TextField input = new TextField();
    private final Button stopButton = new Button();
    private final Button clearButton = new Button();
    /** Closes the program's standard input (end of file), as Ctrl+D does in a terminal. */
    private final Button endInputButton = new Button();
    /** Receives a line typed into the stdin field (controller → process stdin). */
    private Consumer<String> onInput;
    /** Ends the program's input (controller → close the process's stdin). */
    private Runnable onEndInput;
    /** Receives a double-clicked stack-trace location (controller resolves + jumps). */
    private Consumer<StackTraceLinks.Link> onLink;
    /** Receives a clicked HTTP(S) URL (controller opens it in the system browser). */
    private Consumer<String> onUrl;

    private final OutputStyle lineStyle = OutputStyle.console();
    private final ConsoleAppender appender = new ConsoleAppender(output, MAX_CHARS);
    /** Colour state of each stream of the current run (SGR codes are read, drawn, and removed). */
    private AnsiText.Parser stdoutAnsi = new AnsiText.Parser();

    private AnsiText.Parser stderrAnsi = new AnsiText.Parser();
    /**
     * A carriage return was the last thing written: the next text replaces the current line, as a terminal
     * draws a {@code \r} progress bar, instead of every frame becoming a line of its own.
     */
    private boolean overwriteLine;

    public RunPanel(Runnable onStop) {
        getStyleClass().add("run-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));

        status.getStyleClass().add("run-status");
        stopButton.setText(tr("run.stop"));
        stopButton.setMinWidth(Region.USE_PREF_SIZE);
        stopButton.getStyleClass().add("run-stop");
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> {
            if (onStop != null) {
                onStop.run();
            }
        });
        clearButton.setText(tr("run.clear"));
        clearButton.setMinWidth(Region.USE_PREF_SIZE);
        clearButton.setOnAction(e -> clearConsole());

        endInputButton.setText(tr("run.endInput"));
        endInputButton.setMinWidth(Region.USE_PREF_SIZE);
        endInputButton.getStyleClass().add("run-end-input");
        endInputButton.setDisable(true);
        endInputButton.setOnAction(e -> endInput());

        HBox header = new HBox(8, busy, runtime, status, spacer(), clearButton, endInputButton, stopButton);
        header.setAlignment(Pos.CENTER_LEFT);

        output.setEditable(false);
        output.setWrapText(false);
        output.setFocusTraversable(true);
        // No setShowCaret(OFF): a read-only area already hides its caret under the default AUTO, and OFF/ON
        // subscribe the caret to a static RichTextFX stream that then pins the area (and its window) forever.
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
        // Ctrl+D on an empty line ends the input, as in a terminal: `while read` loops and `cat` otherwise
        // wait for text nobody can type, and Stop was the only way out. With text in the field it stays the
        // field's own key (delete forward, in an Emacs keymap).
        input.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.D
                    && e.isControlDown()
                    && !e.isAltDown()
                    && !e.isShiftDown()
                    && !e.isMetaDown()
                    && input.getText().isEmpty()) {
                endInput();
                e.consume();
            }
        });

        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(output);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(header, scroll, input);
        idle();
    }

    public void setOnInput(Consumer<String> onInput) {
        this.onInput = onInput;
    }

    public void setOnEndInput(Runnable onEndInput) {
        this.onEndInput = onEndInput;
    }

    /** Ends the program's input: no more lines can be sent until the next run. */
    public void endInput() {
        if (input.isDisabled() || onEndInput == null) {
            return;
        }
        onEndInput.run();
        input.setDisable(true);
        endInputButton.setDisable(true);
        input.setPromptText(tr("run.inputEnded"));
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

    /** The small header spinner the Run, Output and Test Results tool windows show while something runs;
     *  starts hidden (see {@link #showSpinner}). */
    static ProgressIndicator busySpinner() {
        ProgressIndicator busy = new ProgressIndicator();
        busy.getStyleClass().add("run-busy");
        busy.setPrefSize(14, 14);
        busy.setMaxSize(14, 14);
        busy.setFocusTraversable(false);
        showSpinner(busy, false);
        return busy;
    }

    /** The Run and Debug tool windows' runtime chip; starts hidden (see {@link #showRuntime}). */
    static Label runtimeChip() {
        Label chip = new Label();
        chip.getStyleClass().add("runtime-chip");
        chip.setMinWidth(Region.USE_PREF_SIZE); // the command line beside it shortens first
        showRuntime(chip, null);
        return chip;
    }

    /** Shows {@code version} in a {@link #runtimeChip}, its banner on hover; {@code null} hides the chip. */
    static void showRuntime(Label chip, com.editora.run.RuntimeVersion version) {
        boolean shown = version != null;
        chip.setText(shown ? version.label() : "");
        chip.setTooltip(
                shown && !version.detail().isBlank() ? new javafx.scene.control.Tooltip(version.detail()) : null);
        chip.setAccessibleText(shown ? tr("run.runtime", version.label()) : null);
        chip.setVisible(shown);
        chip.setManaged(shown);
    }

    /** Shows or hides a {@link #busySpinner}; a hidden indicator also stops animating. */
    static void showSpinner(ProgressIndicator busy, boolean running) {
        busy.setVisible(running);
        busy.setManaged(running);
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Resets to the idle state (no run yet / finished and cleared). */
    public void idle() {
        status.setText(tr("run.idle"));
        setRunning(false);
    }

    /** Clears the console output (the Clear button + the {@code run.clear} palette command). */
    public void clearConsole() {
        appender.discard();
        output.clear();
        overwriteLine = false;
    }

    /**
     * The runtime this run's program runs under, once its probe answers; {@code null} hides the chip. Kept
     * after the program exits — it still says what the output came from — and cleared by the next run.
     */
    public void setRuntime(com.editora.run.RuntimeVersion version) {
        showRuntime(runtime, version);
    }

    /** The runtime chip's text, or "" while it is hidden. */
    String runtimeText() {
        return runtime.isVisible() ? runtime.getText() : "";
    }

    /** A run started: clears the console, shows the command, enables Stop + the stdin field. */
    public void started(String commandLine) {
        clearConsole();
        stdoutAnsi = new AnsiText.Parser();
        stderrAnsi = new AnsiText.Parser();
        status.setText(tr("run.running", commandLine));
        setRunning(true);
    }

    /**
     * Writes the command line that was started as the console's first, muted line — which interpreter ran,
     * with which flags, on which file — the way IDEs open a run's console.
     */
    public void showCommandLine(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return;
        }
        String line = commandLine.strip() + "\n";
        appender.append(line, spans(line, List.of(), "run-command"));
    }

    /** Appends one line of program output, applying stderr/log-level colors and clickable URL styling. */
    public void appendOutput(String line, boolean stderr) {
        appendOutput(line, stderr, true);
    }

    /** Appends a prompt without adding a newline, so the user's response can follow it. */
    public void appendPartialOutput(String text, boolean stderr) {
        appendOutput(text, stderr, false);
    }

    private void appendOutput(String line, boolean stderr, boolean completeLine) {
        AnsiText.Chunk chunk = (stderr ? stderrAnsi : stdoutAnsi).feed(line);
        String text = chunk.text();
        // A trace line goes to stderr too, but it is the run's own log, not an error: drawn muted, not red.
        String base = stderr
                ? (StackTraceLinks.isTraceLine(text) ? "run-trace" : "run-stderr")
                : lineStyle.styleClassFor(text);
        int from = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\r' && c != AnsiText.CLEAR_SCREEN) {
                continue;
            }
            write(text, from, i, chunk.runs(), base);
            if (c == '\r') {
                overwriteLine = true;
            } else {
                clearConsole(); // `clear`, or an erase-screen sequence
            }
            from = i + 1;
        }
        write(text, from, text.length(), chunk.runs(), base);
        if (completeLine) {
            overwriteLine = false; // "50%\r" then a newline: a terminal keeps the line and moves on
            appender.append("\n", null);
        }
    }

    /** Writes {@code text[from, to)} with its colours, replacing the current line after a carriage return. */
    private void write(String text, int from, int to, List<AnsiText.Run> runs, String base) {
        if (from >= to) {
            return;
        }
        if (overwriteLine) {
            appender.clearCurrentLine();
            overwriteLine = false;
        }
        String piece = text.substring(from, to);
        appender.append(piece, spans(piece, slice(runs, from, to), base));
    }

    /** The colour runs covering {@code [from, to)} of a chunk, re-based to start at 0. */
    static List<AnsiText.Run> slice(List<AnsiText.Run> runs, int from, int to) {
        List<AnsiText.Run> out = new ArrayList<>();
        int pos = 0;
        for (AnsiText.Run r : runs) {
            int start = Math.max(pos, from);
            int end = Math.min(pos + r.length(), to);
            if (end > start) {
                out.add(new AnsiText.Run(end - start, r.classes()));
            }
            pos += r.length();
            if (pos >= to) {
                break;
            }
        }
        return out;
    }

    /**
     * The styles of one piece of output: its line class ({@code base}: stderr, or a log level), the program's
     * own colours from {@code runs}, and the URL links found in its text.
     */
    static StyleSpans<Collection<String>> spans(String piece, List<AnsiText.Run> runs, String base) {
        java.util.TreeSet<Integer> cuts = new java.util.TreeSet<>(List.of(0, piece.length()));
        int pos = 0;
        for (AnsiText.Run r : runs) {
            pos += r.length();
            cuts.add(Math.min(pos, piece.length()));
        }
        List<ConsoleUrls.Link> urls = ConsoleUrls.find(piece);
        for (ConsoleUrls.Link u : urls) {
            cuts.add(u.start());
            cuts.add(u.end());
        }
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        Integer prev = null;
        for (int cut : cuts) {
            if (prev != null && cut > prev) {
                List<String> classes = new ArrayList<>();
                if (base != null) {
                    classes.add(base);
                }
                classes.addAll(classesAt(runs, prev));
                for (ConsoleUrls.Link u : urls) {
                    if (prev >= u.start() && prev < u.end()) {
                        classes.add("console-url");
                        break;
                    }
                }
                builder.add(classes, cut - prev);
            }
            prev = cut;
        }
        return builder.create();
    }

    private static java.util.Set<String> classesAt(List<AnsiText.Run> runs, int offset) {
        int pos = 0;
        for (AnsiText.Run r : runs) {
            if (offset < pos + r.length()) {
                return r.classes();
            }
            pos += r.length();
        }
        return java.util.Set.of();
    }

    /** The process exited; shows the exit code (or a "stopped" note for a killed run) and disables Stop. */
    public void finished(int code) {
        status.setText(code < 0 ? tr("run.stopped") : tr("run.exited", code));
        setRunning(false);
    }

    /** The program ended because Stop was pressed: says so, rather than "finished" with a signal's exit code. */
    public void stopped() {
        status.setText(tr("run.stopped"));
        setRunning(false);
    }

    /** The process failed to launch (e.g. {@code java} not found). */
    public void failed(String message) {
        status.setText(tr("run.failed", message));
        setRunning(false);
    }

    /** Stop, the stdin field, and the spinner all follow whether a process is alive. */
    private void setRunning(boolean running) {
        stopButton.setDisable(!running);
        input.setDisable(!running);
        endInputButton.setDisable(!running);
        input.setPromptText(tr("run.stdinPrompt"));
        showSpinner(busy, running);
        this.running.set(running);
    }

    /** True from {@link #started} until {@link #finished}, {@link #failed} or {@link #idle}. */
    public ReadOnlyBooleanProperty runningProperty() {
        return running.getReadOnlyProperty();
    }

    @Override
    public void focusFirstItem() {
        output.requestFocus();
    }
}
