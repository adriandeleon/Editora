package com.editora.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Consumer;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import com.editora.build.OutputStyle;
import com.editora.git.GitOutputDiffs;
import com.editora.git.GitOutputHighlights;
import com.editora.git.GitOutputLinks;
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
 * One build tool's console — a single tab inside the shared {@link BuildOutputPanel} "Output" tool
 * window. Streams that tool's running invocation's stdout/stderr (see {@code com.editora.build.BuildService})
 * with a header showing the run state (a spinner while something runs) plus Find, Previous run, Save, Clear
 * and Stop. {@link BuildOutputPanel} creates one of these per tool that runs, so Maven/npm/Cargo/Go/Gradle
 * each get their own tab. Modeled on {@link RunPanel} minus the stdin field (a build isn't interactive),
 * using a read-only RichTextFX {@link CodeArea} so each line can be colored by the tool's
 * {@link OutputStyle}. Reuses the {@code .run-panel}/{@code .run-status} CSS + the shared {@code run.*}
 * status strings.
 *
 * <p>A new run no longer throws the last one away (Maven review B22): its output moves into a second,
 * read-only buffer that the <b>Previous run</b> toggle shows in place of the live console, so two runs can
 * be compared without re-running. Find ({@code find.show}'s chord, or the header button) searches whichever
 * buffer is shown; <b>Save output…</b> writes it to a file.
 */
public final class BuildToolPanel extends VBox implements ToolWindowContent {

    /**
     * Trim the console once it exceeds this many characters (keeps the most recent output). A constant, not a
     * setting (Maven review B22 asked; there is no schema for it in this pass): a RichTextFX area holding a
     * few hundred thousand styled characters is still cheap to append to and to search, while a {@code -X}
     * build's several megabytes would not be. The header says when earlier output was dropped; the whole
     * stream is still in the terminal's hands if a user needs it — Run custom with {@code > build.log} —
     * and {@link #saveOutputTo} writes what is left.
     */
    static final int MAX_CHARS = 200_000;

    private final Label status = new Label();
    /** The runtime an npm script runs under ("Node.js 24.18.1"); hidden until its probe answers, or for others. */
    private final Label runtime = RunPanel.runtimeChip();
    /** Spins while this tab's build (or, in log mode, a long command) is running. */
    private final ProgressIndicator busy = RunPanel.busySpinner();

    private final CodeArea output = AreaUndo.none(new CodeArea());
    /** The run before the current one, moved here by {@link #started}; shown by {@link #previousButton}. */
    private final CodeArea previous = AreaUndo.none(new CodeArea());

    private final VirtualizedScrollPane<CodeArea> outputScroll = new VirtualizedScrollPane<>(output);
    private final VirtualizedScrollPane<CodeArea> previousScroll = new VirtualizedScrollPane<>(previous);
    private final Button stopButton = new Button();
    private final Button clearButton = new Button();
    private final Button findButton = new Button();
    private final Button saveButton = new Button();
    private final ToggleButton previousButton = new ToggleButton();
    private final ConsoleFindBar findBar = new ConsoleFindBar(this::shownArea);
    private @Nullable Consumer<StackTraceLinks.Link> onLink;
    private @Nullable Consumer<String> onUrl;
    private @Nullable Consumer<GitOutputDiffs.Target> onGitDiff;
    private boolean gitTranscript;

    /** This tool's output style (set per run by {@link #started}). */
    private OutputStyle style = OutputStyle.passthrough();
    /** Stops this tool's running build (set per run by {@link #started}). */
    private @Nullable Runnable onStop;

    private final ConsoleAppender appender = new ConsoleAppender(output, MAX_CHARS);

    /** A transcript tab ({@link #setLogMode}), and whether a cancellable command is running in it. */
    private boolean logMode;

    private boolean logRunning;

    /** A build {@link #started} in this tab and has not finished, failed, or been reset to idle. */
    private boolean buildRunning;

    /** Whether the spinner shows: a build or a long logged command is running ({@link #runningProperty}). */
    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper(this, "running");

    /** The last line is progress its command will overwrite ({@link #appendProgress}). */
    private boolean transientTail;

    /** The header of the live run — what {@link #status} shows unless the previous run is up. */
    private String liveStatus = "";
    /** The header the previous run ended with, shown while {@link #previous} is up; null = no previous run. */
    private @Nullable String previousStatus;

    private boolean showingPrevious;

    /** The tab's name ("Maven"), for the suggested file name of {@link #saveOutput}. */
    private String title = "output";

    public BuildToolPanel() {
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
        clearButton.setOnAction(e -> {
            if (showingPrevious) {
                clearPrevious();
            } else {
                clearConsole();
            }
        });
        iconButton(findButton, Icons.find(), tr("console.find"));
        findButton.setOnAction(e -> openFind());
        iconButton(saveButton, Icons.save(), tr("console.saveOutput"));
        saveButton.setOnAction(e -> saveOutput());
        previousButton.setText(tr("console.previousRun"));
        previousButton.setTooltip(new Tooltip(tr("console.previousRun.tip")));
        previousButton.setMinWidth(Region.USE_PREF_SIZE);
        previousButton.setFocusTraversable(false);
        previousButton.setDisable(true);
        previousButton.selectedProperty().addListener((obs, was, now) -> showPrevious(now));

        HBox header = new HBox(
                8, busy, runtime, status, spacer(), findButton, previousButton, saveButton, clearButton, stopButton);
        header.setAlignment(Pos.CENTER_LEFT);

        for (CodeArea area : List.of(output, previous)) {
            area.setEditable(false);
            area.setWrapText(false);
            area.setFocusTraversable(true);
            // No setShowCaret(OFF): a read-only area already hides its caret under the default AUTO, and OFF/ON
            // subscribe the caret to a static RichTextFX stream that then pins the area (and its window) forever.
            area.getStyleClass().addAll("editor-area", "run-output");
            RunPanel.installLinkClicks(area, () -> onLink);
            installMouse(area);
            ConsoleNav.installShared(area);
            ConsoleNav.onCommand(area, "find.show", this::openFind);
            ConsoleContextMenu.install(area); // right-click Select All / Copy
        }
        findBar.setOnClose(() -> shownArea().requestFocus());

        StackPane body = new StackPane(outputScroll, previousScroll);
        previousScroll.setVisible(false);
        previousScroll.setManaged(false);
        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().addAll(header, findBar, body);
        idle();
    }

    private static void iconButton(Button button, Node graphic, String name) {
        button.setGraphic(graphic);
        button.setTooltip(new Tooltip(name));
        button.setAccessibleText(name);
        button.setFocusTraversable(false);
        button.getStyleClass().addAll("button-icon", "flat");
    }

    /** URL, Git-diff and Git-file clicks plus the hand cursor over them, on {@code area}. */
    private void installMouse(CodeArea area) {
        area.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY || e.getClickCount() != 1) {
                return;
            }
            int offset = area.hit(e.getX(), e.getY()).getInsertionIndex();
            ConsoleUrls.Link link = ConsoleUrls.at(area.getText(), offset);
            if (link != null && onUrl != null) {
                onUrl.accept(link.url());
                e.consume();
                return;
            }
            GitOutputDiffs.Target diff = gitTranscript ? GitOutputDiffs.at(area.getText(), offset) : null;
            if (diff != null && onGitDiff != null) {
                onGitDiff.accept(diff);
                e.consume();
                return;
            }
            GitOutputLinks.Link file = gitTranscript ? GitOutputLinks.at(area.getText(), offset) : null;
            if (file != null && onLink != null) {
                onLink.accept(new StackTraceLinks.Link(file.file(), 1));
                e.consume();
            }
        });
        area.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> {
            int offset = area.hit(e.getX(), e.getY()).getInsertionIndex();
            boolean linked = ConsoleUrls.at(area.getText(), offset) != null
                    || (gitTranscript
                            && (GitOutputLinks.at(area.getText(), offset) != null
                                    || GitOutputDiffs.at(area.getText(), offset) != null));
            area.setCursor(linked ? Cursor.HAND : Cursor.TEXT);
        });
        area.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED, e -> area.setCursor(null));
    }

    public void setOnLink(Consumer<StackTraceLinks.Link> onLink) {
        this.onLink = onLink;
    }

    public void setOnUrl(Consumer<String> onUrl) {
        this.onUrl = onUrl;
    }

    /** The clicked change graph of a pulled file ({@code 4 ++--}): open its diff across the pull. */
    public void setOnGitDiff(Consumer<GitOutputDiffs.Target> onGitDiff) {
        this.onGitDiff = onGitDiff;
    }

    /** Matches the console font to the editor's code-area font (family + effective size). */
    public void setOutputFont(String family, int size) {
        String css = "-fx-font-family: \"" + family + "\"; -fx-font-size: " + size + "px;";
        output.setStyle(css);
        previous.setStyle(css);
    }

    /** The tab's name, for the file name {@link #saveOutput} suggests ("maven-output.txt"). */
    public void setTitle(String title) {
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /**
     * Turns this tab into an append-only <b>transcript</b> of finished one-shot commands (Git/GitHub) rather
     * than a streaming build console: there is nothing to stop, so the Stop button goes away entirely
     * instead of sitting permanently disabled — and there is no "previous run" either, so that toggle goes too.
     */
    public void setLogMode(boolean logMode) {
        this.logMode = logMode;
        showStop(!logMode || logRunning);
        previousButton.setVisible(!logMode);
        previousButton.setManaged(!logMode);
        if (logMode) {
            setStatus(tr("console.log.idle"));
        }
    }

    /**
     * In log mode: a long command (a clone, a fetch) is running and {@code onStop} cancels it, so the Stop
     * button is there for as long as it runs; {@code null} when it has ended.
     */
    public void setLogStop(@Nullable Runnable onStop) {
        this.onStop = onStop;
        logRunning = onStop != null;
        stopButton.setDisable(!logRunning);
        showStop(!logMode || logRunning);
        showBusy();
    }

    /** Shown while a build or a long logged command runs; hidden, the indicator also stops animating. */
    private void showBusy() {
        running.set(buildRunning || logRunning);
        RunPanel.showSpinner(busy, running.get());
    }

    /** True while this tab's build or long logged command runs — {@link BuildOutputPanel} spins its tab title. */
    public ReadOnlyBooleanProperty runningProperty() {
        return running.getReadOnlyProperty();
    }

    private void showStop(boolean show) {
        stopButton.setVisible(show);
        stopButton.setManaged(show);
    }

    /** Enables file-path links for the Git transcript without treating GitHub's plain CLI output as paths. */
    public void setGitTranscript(boolean gitTranscript) {
        this.gitTranscript = gitTranscript;
    }

    /** In log mode, the header line — the command most recently appended. */
    public void setLogStatus(String text) {
        setStatus(text);
    }

    /** The live run's header; the label shows it unless the previous run is up, which keeps its own. */
    private void setStatus(String text) {
        liveStatus = text == null ? "" : text;
        if (!showingPrevious) {
            status.setText(liveStatus);
        }
    }

    /** Resets to the idle state (no run yet / finished and cleared). */
    public void idle() {
        setStatus(tr("run.idle"));
        stopButton.setDisable(true);
        buildRunning = false;
        showBusy();
    }

    /** Clears the live console output (the Clear button + the {@code run.clear} palette command). */
    public void clearConsole() {
        transientTail = false;
        appender.discard();
        appender.resetTrimmed();
        output.clear();
    }

    /** Drops the previous run's buffer (Clear while it is shown) and returns to the live console. */
    private void clearPrevious() {
        previous.clear();
        previousStatus = null;
        previousButton.setSelected(false);
        previousButton.setDisable(true);
    }

    /**
     * A build starts in this tab: set the header/style/Stop action. What the console held — the last run, in
     * full, styles and all — becomes the previous run (reachable through the toggle) before the live console
     * is cleared for the new one; an empty console leaves the previous run as it was.
     */
    public void started(String header, OutputStyle style, Runnable onStop) {
        this.style = style == null ? OutputStyle.passthrough() : style;
        this.onStop = onStop;
        RunPanel.showRuntime(runtime, null);
        keepAsPrevious();
        if (showingPrevious) {
            previousButton.setSelected(false); // the new run is what to watch; its predecessor stays a click away
        }
        clearConsole();
        stdoutAnsi = new AnsiText.Parser();
        stderrAnsi = new AnsiText.Parser();
        setStatus(tr("run.running", header));
        stopButton.setDisable(false);
        buildRunning = true;
        showBusy();
    }

    /** Moves the live console's contents into the previous-run buffer, when there are any. */
    private void keepAsPrevious() {
        appender.flush();
        if (output.getLength() == 0 || logMode) {
            return;
        }
        previous.replace(0, previous.getLength(), output.getDocument());
        previous.moveTo(0);
        previousStatus = liveStatus;
        previousButton.setDisable(false);
    }

    /** Swaps the previous run's buffer in for the live console (or back), header included. */
    private void showPrevious(boolean show) {
        if (show && previousStatus == null) {
            previousButton.setSelected(false);
            return;
        }
        showingPrevious = show;
        previousScroll.setVisible(show);
        previousScroll.setManaged(show);
        outputScroll.setVisible(!show);
        outputScroll.setManaged(!show);
        status.setText(show ? tr("console.previousRun.header", previousStatus) : liveStatus);
        findBar.retarget();
    }

    /** The buffer on screen: the previous run's while its toggle is down, else the live console. */
    private CodeArea shownArea() {
        return showingPrevious ? previous : output;
    }

    /** Whether the previous run's buffer is shown instead of the live console. */
    boolean isShowingPrevious() {
        return showingPrevious;
    }

    /** The text of the buffer on screen (the previous run's, or the live console's). */
    String shownText() {
        appender.flush();
        return shownArea().getText();
    }

    /** Opens the find bar over the buffer on screen, seeded with its one-line selection. */
    void openFind() {
        findBar.open(shownArea().getSelectedText());
    }

    ConsoleFindBar findBar() {
        return findBar;
    }

    /** <b>Save output…</b>: writes the buffer on screen to a file the user picks. */
    private void saveOutput() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("console.saveOutput"));
        chooser.setInitialFileName(title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + "-output.txt");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Log/Text", "*.log", "*.txt"));
        Window owner = getScene() == null ? null : getScene().getWindow();
        java.io.File target = chooser.showSaveDialog(owner);
        if (target != null) {
            saveOutputTo(target.toPath());
        }
    }

    /**
     * Writes the buffer on screen to {@code target} (staged: a failed write must not empty a file the Save
     * dialog agreed to replace). A failure is reported as a line in the console, where the user is looking.
     */
    void saveOutputTo(Path target) {
        try {
            com.editora.io.StagedExport.writeString(target, shownText());
        } catch (IOException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            appendStyled(tr("console.saveOutput.failed", message), "log-error");
        }
    }

    /**
     * Appends one line, colored per this tool's {@link OutputStyle}, auto-scrolling to the bottom — but
     * only while the caret is already at the end. A user who has scrolled back through a running build's
     * output (see {@link ConsoleNav}) stays put instead of being yanked to the tail on every new line;
     * navigating back to the end resumes the follow.
     */
    public void appendOutput(String line, boolean stderr) {
        AnsiText.Parser ansi = stderr ? stderrAnsi : stdoutAnsi;
        if (line.indexOf('\u001B') < 0 && !ansi.active()) {
            appendStyled(line, style.styleClassFor(line));
            return;
        }
        // A program's own colours (an npm script printing SGR codes): drawn, as the Run console draws them,
        // instead of showing the escape bytes. A carriage return keeps only what follows it, as a terminal.
        AnsiText.Chunk chunk = ansi.feed(line);
        String text = chunk.text();
        int from = Math.max(text.lastIndexOf('\r'), text.lastIndexOf(AnsiText.CLEAR_SCREEN)) + 1;
        String shown = text.substring(from);
        transientTail = false;
        appender.append(
                shown + "\n",
                RunPanel.spans(shown, RunPanel.slice(chunk.runs(), from, text.length()), style.styleClassFor(shown)));
    }

    /** Colour state of each stream of the current build (SGR codes are read, drawn, and removed). */
    private AnsiText.Parser stdoutAnsi = new AnsiText.Parser();

    private AnsiText.Parser stderrAnsi = new AnsiText.Parser();

    /**
     * Appends one line under a caller-chosen {@code .text.<class>} ({@code null} = default foreground), with
     * the same follow/trim behaviour as {@link #appendOutput}. Used by the Git/GitHub command log, whose
     * colouring comes from {@code CommandLogFormat} rather than a build tool's {@link OutputStyle}.
     */
    public void appendStyled(String line, @Nullable String styleClass) {
        transientTail = false; // anything appended after a progress line leaves it standing
        StyleSpans<Collection<String>> spans = null;
        if (!line.isEmpty()) {
            StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
            List<GitOutputLinks.Link> fileLinks = gitTranscript ? GitOutputLinks.find(line) : List.of();
            List<GitOutputHighlights.Span> highlights = gitTranscript ? GitOutputHighlights.find(line) : List.of();
            List<ConsoleUrls.Link> urlLinks = ConsoleUrls.find(line);
            // Underlined as a link whether or not an "Updating a..b" stands above it: that is the
            // transcript's to say, at click time.
            int[] graph = gitTranscript ? GitOutputDiffs.graph(line) : null;
            TreeSet<Integer> boundaries = new TreeSet<>(List.of(0, line.length()));
            if (graph != null) {
                boundaries.add(graph[0]);
                boundaries.add(graph[1]);
            }
            for (ConsoleUrls.Link link : urlLinks) {
                boundaries.add(link.start());
                boundaries.add(link.end());
            }
            for (GitOutputLinks.Link link : fileLinks) {
                boundaries.add(link.start());
                boundaries.add(link.end());
            }
            for (GitOutputHighlights.Span span : highlights) {
                boundaries.add(span.start());
                boundaries.add(span.end());
            }
            Integer prior = null;
            for (int end : boundaries) {
                if (prior != null) {
                    int begin = prior;
                    List<String> classes = new ArrayList<>(4);
                    if (gitTranscript) {
                        classes.add("git-output-text");
                    }
                    if (styleClass != null) {
                        classes.add(styleClass);
                    }
                    for (GitOutputHighlights.Span span : highlights) {
                        if (span.start() <= begin && begin < span.end()) {
                            classes.add(span.styleClass());
                        }
                    }
                    boolean linked = fileLinks.stream().anyMatch(link -> link.contains(begin))
                            || urlLinks.stream().anyMatch(link -> link.start() <= begin && begin < link.end());
                    if (linked) {
                        classes.add("console-url");
                    }
                    if (graph != null && graph[0] <= begin && begin < graph[1]) {
                        classes.add("git-output-diff-link");
                    }
                    builder.add(classes, end - begin);
                }
                prior = end;
            }
            spans = builder.create();
        }
        appender.append(line + "\n", spans);
    }

    /**
     * Appends one line of a running command's output. A {@code transientLine} is progress the command
     * overwrites ({@code Receiving objects:  12%}): the next line passed here takes its place, as it would in
     * a terminal, instead of a clone leaving hundreds of percentages behind it.
     */
    public void appendProgress(String line, @Nullable String styleClass, boolean transientLine) {
        if (transientTail) {
            appender.flush();
            int paragraphs = output.getParagraphs().size();
            if (paragraphs >= 2) {
                // The console ends in a newline, so the last line is the paragraph before the empty one.
                output.deleteText(output.getAbsolutePosition(paragraphs - 2, 0), output.getLength());
            }
        }
        appendStyled(line, styleClass);
        transientTail = transientLine;
    }

    /** The runtime this build's program runs under, once its probe answers; null hides the chip. */
    public void setRuntime(com.editora.run.@Nullable RuntimeVersion version) {
        RunPanel.showRuntime(runtime, version);
    }

    /** The runtime chip's text, or "" while it is hidden. */
    String runtimeText() {
        return runtime.isVisible() ? runtime.getText() : "";
    }

    /** The build exited with {@code code} on its own ({@code < 0}: it was killed before it could report one). */
    public void finished(int code) {
        ended(code < 0 ? tr("run.stopped") : tr("run.exited", code));
    }

    /**
     * The build ended because the user asked it to (Maven review B5): "Stopped", whatever code the killed
     * process reported — SIGTERM's 143 is not a build result, and read as a crash.
     */
    public void stopped() {
        ended(tr("run.stopped"));
    }

    private void ended(String verdict) {
        setStatus(verdict + (appender.trimmed() ? " " + tr("run.outputTrimmed", MAX_CHARS) : ""));
        stopButton.setDisable(true);
        buildRunning = false;
        showBusy();
    }

    public void failed(String message) {
        setStatus(tr("run.failed", message));
        stopButton.setDisable(true);
        buildRunning = false;
        showBusy();
    }

    @Override
    public void focusFirstItem() {
        shownArea().requestFocus();
    }
}
