package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
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
import com.editora.editor.UiZoom;
import com.editora.run.AnsiText;
import com.editora.run.ConsoleUrls;
import com.editora.run.StackTraceLinks;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.jspecify.annotations.Nullable;

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
    private @Nullable Consumer<String> onInput;
    /** Ends the program's input (controller → close the process's stdin). */
    private @Nullable Runnable onEndInput;
    /** Receives a double-clicked stack-trace location (controller resolves + jumps). */
    private @Nullable Consumer<StackTraceLinks.Link> onLink;
    /** Receives a clicked HTTP(S) URL (controller opens it in the system browser). */
    private @Nullable Consumer<String> onUrl;

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
        UiZoom.spacing(this, 6);
        UiZoom.padding(this, 6);

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

        HBox header =
                UiZoom.spacing(new HBox(busy, runtime, status, spacer(), clearButton, endInputButton, stopButton), 8);
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
            ConsoleUrls.Link link = urlAt(output, offset);
            if (link != null) {
                onUrl.accept(link.url());
                e.consume();
            }
        });
        output.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> {
            int offset = output.hit(e.getX(), e.getY()).getInsertionIndex();
            output.setCursor(urlAt(output, offset) == null ? Cursor.TEXT : Cursor.HAND);
        });
        output.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED, e -> output.setCursor(null));
        ConsoleNav.installShared(output); // configured-keymap scrolling while the console has focus
        ConsoleContextMenu.install(output); // right-click Select All / Copy

        // stdin: one line per Enter, echoed into the console (the program won't echo it back).
        input.getStyleClass().add("run-input");
        input.setPromptText(tr("run.stdinPrompt"));
        input.setAccessibleText(tr("run.stdinPrompt"));
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

        // The console under the layer that paints every match of its find bar.
        javafx.scene.Node scroll = ConsoleFindBar.withMarks(output, new VirtualizedScrollPane<>(output));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        // The keymap's find chord, pressed in the console, opens a find bar over it (Escape closes it).
        findBar = ConsoleFindBar.install(output);
        getChildren().addAll(header, findBar, scroll, input);
        idle();
    }

    private final ConsoleFindBar findBar;

    /** Opens the find bar over the console, or steps to the next match when it is open ({@code run.findInOutput}). */
    void openFind() {
        findBar.openOrNext(output.getSelectedText());
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

    /** Draws the console in the UI's colours when the UI and editor themes disagree (see {@link ConsoleColours}). */
    public void setUiColours(boolean ui) {
        ConsoleColours.apply(output, ui);
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

    /**
     * The console line around {@code offset}: its text, read alone, and where it starts. A hover or a click looks at
     * one line, never at a copy of the whole console (PERF-01, Pauta 6.2.0: it runs on every mouse move). Null past
     * the end.
     */
    record LineAt(String text, int start) {}

    static @Nullable LineAt lineAt(CodeArea area, int offset) {
        if (offset < 0 || offset >= area.getLength()) {
            return null;
        }
        int paragraph = area.offsetToPosition(offset, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor();
        return new LineAt(area.getParagraph(paragraph).getText(), area.getAbsolutePosition(paragraph, 0));
    }

    /** The URL under {@code offset} of {@code area}, read from its line alone; offsets are the console's. */
    static ConsoleUrls.@Nullable Link urlAt(CodeArea area, int offset) {
        LineAt line = lineAt(area, offset);
        if (line == null) {
            return null;
        }
        ConsoleUrls.Link link = ConsoleUrls.at(line.text(), offset - line.start());
        return link == null
                ? null
                : new ConsoleUrls.Link(line.start() + link.start(), line.start() + link.end(), link.url());
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
            int caret = console.getCaretPosition();
            LineAt line = caret < console.getLength() ? lineAt(console, caret) : null;
            String text = line != null
                    ? line.text()
                    : console.getParagraphs().isEmpty()
                            ? ""
                            : console.getParagraph(console.getParagraphs().size() - 1)
                                    .getText(); // the caret at the very end: the last line
            StackTraceLinks.Link link = StackTraceLinks.parse(text);
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
        UiZoom.prefSize(busy, 14, 14);
        UiZoom.maxSize(busy, 14, 14);
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
    static void showRuntime(Label chip, com.editora.run.@Nullable RuntimeVersion version) {
        boolean shown = version != null;
        chip.setText(version != null ? version.label() : "");
        chip.setTooltip(
                version != null && !version.detail().isBlank()
                        ? com.editora.editor.ControlNames.tooltip(version.detail())
                        : null);
        chip.setAccessibleText(version != null ? tr("run.runtime", version.label()) : null);
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
        appender.resetTrimmed();
        skipped = false;
        output.clear();
        overwriteLine = false;
    }

    /**
     * The runtime this run's program runs under, once its probe answers; {@code null} hides the chip. Kept
     * after the program exits — it still says what the output came from — and cleared by the next run.
     */
    public void setRuntime(com.editora.run.@Nullable RuntimeVersion version) {
        showRuntime(runtime, version);
    }

    /** The runtime chip's text, or "" while it is hidden. */
    String runtimeText() {
        return runtime.isVisible() ? runtime.getText() : "";
    }

    /** A run started: clears the console, shows the command, enables Stop + the stdin field. */
    public void started(@Nullable String commandLine) {
        clearConsole();
        stdoutAnsi = new AnsiText.Parser();
        stderrAnsi = new AnsiText.Parser();
        status.setText(tr("run.running", commandLine));
        setRunning(true);
    }

    /**
     * As {@link #started}, for a program that is still being started off the FX thread (PERF-01, Pauta 6.2.0): Stop
     * already applies, the input waits for {@link #runningAs}, so what is typed follows the command line.
     */
    public void starting(@Nullable String label) {
        started(label);
        input.setDisable(true);
        endInputButton.setDisable(true);
    }

    /**
     * The program a run {@link #starting} with has started as {@code commandLine}: the header names it, and the
     * input opens. The console is not cleared again.
     */
    public void runningAs(String commandLine) {
        status.setText(tr("run.running", commandLine));
        if (running.get()) {
            input.setDisable(false);
            endInputButton.setDisable(false);
        }
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

    /** Appends a muted line of Editora's own (a hint about the output), set apart from the program's. */
    public void appendNotice(String text) {
        showCommandLine(text);
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
        boolean dropped = completeLine && com.editora.process.OutputPump.OUTPUT_DROPPED.equals(text);
        skipped |= dropped;
        // A trace line goes to stderr too, but it is the run's own log, not an error: drawn muted, not red. The
        // pump's "output skipped" marker is Editora's note, not the program's: muted too.
        String base = dropped
                ? "run-command"
                : stderr
                        ? (StackTraceLinks.isTraceLine(text) ? "run-trace" : "run-stderr")
                        : lineStyle.styleClassFor(text);
        // Only what a terminal would still show is written: the console is cleared once at the chunk's last
        // erase-screen, and of the carriage-return segments after it only the last non-empty one is drawn — a
        // progress counter's 64 KiB "line" of thousands of "\r12345" pieces is one clear and one append, not two
        // rich-text edits per piece.
        Carriage c = Carriage.of(text);
        if (c.clearScreen()) {
            clearConsole(); // `clear`, or an erase-screen sequence
        }
        if (c.returnBefore()) {
            overwriteLine = true;
        }
        write(text, c.from(), c.to(), chunk.runs(), base);
        if (c.returnAfter()) {
            overwriteLine = true;
        }
        if (completeLine) {
            overwriteLine = false; // "50%\r" then a newline: a terminal keeps the line and moves on
            appender.append("\n", null);
        }
    }

    /**
     * What survives of one output chunk on a terminal: whether it erases the screen ({@code clearScreen}: an
     * {@link AnsiText#CLEAR_SCREEN} anywhere in it), the one piece {@code [from, to)} still visible — the last
     * non-empty carriage-return segment after the last erase — and whether a carriage return comes before
     * that piece (it overwrites the current line) or after it (the next text will). An empty piece
     * ({@code from == to}) writes nothing.
     */
    record Carriage(boolean clearScreen, int from, int to, boolean returnBefore, boolean returnAfter) {

        static Carriage of(String text) {
            int start = text.lastIndexOf(AnsiText.CLEAR_SCREEN) + 1;
            boolean clear = start > 0;
            int end = text.length();
            int to = end;
            // Skip trailing returns: "50%\r" keeps "50%" visible and marks the line for overwriting.
            while (to > start && text.charAt(to - 1) == '\r') {
                to--;
            }
            boolean after = to < end;
            int from = text.lastIndexOf('\r', to - 1) + 1;
            if (from < start) {
                from = start;
            }
            boolean before = from > start; // a '\r' at from - 1, inside this chunk's surviving part
            return new Carriage(clear, from, to, before, after);
        }
    }

    /** Writes {@code text[from, to)} with its colours, replacing the current line after a carriage return. */
    private void write(String text, int from, int to, List<AnsiText.Run> runs, @Nullable String base) {
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
    static StyleSpans<Collection<String>> spans(String piece, List<AnsiText.Run> runs, @Nullable String base) {
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
        // One walk over the cuts, the colour runs and the links together: looking each cut's run and link up
        // from the start made a heavily coloured 64 KiB line quadratic.
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int run = 0;
        int runEnd = runs.isEmpty() ? 0 : runs.get(0).length();
        int url = 0;
        Integer prev = null;
        for (int cut : cuts) {
            if (prev != null && cut > prev) {
                while (run < runs.size() && prev >= runEnd) {
                    run++;
                    if (run < runs.size()) {
                        runEnd += runs.get(run).length();
                    }
                }
                while (url < urls.size() && prev >= urls.get(url).end()) {
                    url++;
                }
                List<String> classes = new ArrayList<>();
                if (base != null) {
                    classes.add(base);
                }
                if (run < runs.size()) {
                    classes.addAll(runs.get(run).classes());
                }
                if (url < urls.size() && prev >= urls.get(url).start()) {
                    classes.add("console-url");
                }
                builder.add(classes, cut - prev);
            }
            prev = cut;
        }
        return builder.create();
    }

    /** The process exited; shows the exit code (or a "stopped" note for a killed run) and disables Stop. */
    public void finished(int code) {
        status.setText((code < 0 ? tr("run.stopped") : tr("run.exited", code)) + trimNote());
        setRunning(false);
    }

    /** The program ended because Stop was pressed: says so, rather than "finished" with a signal's exit code. */
    public void stopped() {
        status.setText(tr("run.stopped") + trimNote());
        setRunning(false);
    }

    /** " — earlier output dropped" when the console cut its oldest lines to stay under its cap, else "". */
    private String trimNote() {
        if (skipped) {
            return " " + tr("run.outputSkipped"); // lines missing from the middle, not only the oldest ones
        }
        return appender.trimmed() ? " " + tr("run.outputTrimmed", MAX_CHARS) : "";
    }

    /** The output pump skipped lines this run because the console could not keep up (its marker arrived). */
    private boolean skipped;

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
