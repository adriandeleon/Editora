package com.editora.ui;

import java.io.ByteArrayInputStream;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;

import com.editora.editor.GrammarRegistry;
import com.editora.editor.TextMateHighlighter;
import com.editora.http.HttpExchange;
import com.editora.http.HttpResponseFormat;
import com.editora.http.HttpResult;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;

import static com.editora.i18n.Messages.tr;

/**
 * The HTTP Client response viewer, shown as the {@code .http} buffer's <b>preview</b> (one instance per open
 * {@code .http} buffer, embedded in the editor's Editor/Split/Preview view by {@link HttpClientCoordinator}).
 *
 * <p>From the top: a <b>Run/Cancel</b> button and the status line ({@code 200 OK · 32 ms · 44 B}, coloured by
 * outcome; "Running … 1.2 s" with an elapsed time while a request is in flight); the actions and the History
 * and Environment pickers, in rows that wrap rather than squeeze their labels into "…" in a narrow window; a
 * collapsible <b>Request</b> section with the request as it was actually sent (variables resolved); the
 * response headers; and the body — syntax-highlighted by content type (JSON/XML/HTML, reusing the editor's
 * TextMate grammars; JSON and one-line XML pretty-printed), or the picture for an {@code image/*} response.
 *
 * <p>Formatting and tokenizing a response are proportional to its size, so they run on a background worker
 * and land under a generation guard: only the newest selection's body is applied. The body pane shows a
 * pretty-printed, length-limited <em>view</em> (with a visible line when it is cut); Save and Open-in-tab
 * always use the response as received.
 *
 * <p>The history holds at most {@link #MAX_HISTORY} entries and {@link HttpHistoryEntry#MAX_BODY_BYTES} of
 * bodies: past that the oldest entries keep their status, headers and timing but drop their body, and say so.
 * It scrolls its own body area, so it is hosted directly (no {@code ScrollPane} wrapper), like {@link
 * CsvGridPanel}.
 */
public final class HttpClientPanel extends VBox {

    static final int MAX_HISTORY = 20;
    static final int MAX_BODY_CHARS = 400_000;

    /** The History picker's width when there is room, and the least it narrows to in a tight Split. */
    static final double HISTORY_WIDTH = 340;

    static final double HISTORY_MIN_WIDTH = 160;

    /** The least width the panel asks of a Split (its controls wrap or narrow below their preferred width). */
    static final double MIN_WIDTH = 260;

    /** Up to this many notes (warnings + files written) show expanded; more start collapsed behind the badge. */
    static final int NOTES_SHOWN_EXPANDED = 2;

    /** Shared worker for response formatting + highlighting; idles out, so it costs nothing when unused. */
    private static final ExecutorService FORMAT = formatExecutor();

    private static ExecutorService formatExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "http-response-format");
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** A formatted body ready to apply on the FX thread: the view, the text shown, its spans (or null), a picture. */
    private record Rendered(
            HttpResponseFormat.BodyView view, String text, StyleSpans<Collection<String>> spans, Image image) {}

    private final Button runButton = new Button();
    private final Label status = new Label();
    /** "⚠ 11 warnings" beside the status line: a response can be 200 OK and still have been sent wrong. */
    private final Label warnBadge = new Label();
    /** "Run All: 3 of 13 failed" under the status line after a Run All, until the next run. */
    private final Label runSummary = new Label();

    private final ComboBox<String> envCombo = new ComboBox<>();
    private final ComboBox<HttpHistoryEntry> historyCombo = new ComboBox<>();
    private final TextArea requestArea = new TextArea();
    private final TitledPane requestPane = new TitledPane();
    /** The response's warnings and the files a {@code >>} wrote, apart from the headers they used to bury. */
    private final TextArea notesArea = new TextArea();

    private final TitledPane notesPane = new TitledPane();
    private final TextArea headersArea = new TextArea();
    private final CodeArea bodyArea = AreaUndo.none(new CodeArea());
    private final VirtualizedScrollPane<CodeArea> bodyScroll = new VirtualizedScrollPane<>(bodyArea);
    private final ImageView imageView = new ImageView();
    private final ScrollPane imageScroll = new ScrollPane(imageView);
    private final Button copyCurlButton = new Button();
    private final Button openTabButton = new Button();
    private final Button saveButton = new Button();
    private final Button clearButton = new Button();
    private final ToggleButton wrapToggle = new ToggleButton();

    private final List<HttpHistoryEntry> history = new ArrayList<>();
    private final ZoneId zone = ZoneId.systemDefault();

    /** Receives the chosen environment name ({@code ""} = none) so the controller can persist it. */
    private Consumer<String> onEnvironmentChanged;

    /** Run again: the selected entry (to re-send that request), or null (run the request at the caret). */
    private Consumer<HttpHistoryEntry> onRun = e -> {};

    private Runnable onCancel;
    private Runnable onEscape = () -> {};
    private Runnable onCleared = () -> {};

    private boolean updatingEnv;
    private boolean updatingHistory;
    private boolean running;
    private String fontStyle;

    /** The running request's label and start, for the elapsed time on the status line. */
    private String runningLabel = "";

    private long runningSince;
    private final Timeline elapsed = new Timeline(new KeyFrame(Duration.millis(200), e -> showRunning()));

    /**
     * Bumped for every body shown or cleared (FX thread); a formatting task for an older one is skipped before
     * it starts and its result dropped. Volatile: the format worker reads it.
     */
    private volatile long bodyGeneration;

    /** Completes once the most recent body render has been applied or dropped. Test seam. */
    private CompletableFuture<Void> bodyRender = CompletableFuture.completedFuture(null);

    public HttpClientPanel(
            Runnable onSaveResponse,
            Consumer<HttpExchange> onCopyAsCurl,
            Consumer<HttpExchange> onOpenInTab,
            String fontFamily,
            int fontSize) {
        getStyleClass().add("http-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));
        elapsed.setCycleCount(Timeline.INDEFINITE);

        runButton.getStyleClass().add("http-run");
        runButton.setMinWidth(Region.USE_PREF_SIZE);
        runButton.setOnAction(e -> {
            if (running) {
                if (onCancel != null) {
                    onCancel.run();
                }
            } else {
                onRun.accept(historyCombo.getValue());
            }
        });
        setRunning(false);

        status.getStyleClass().add("http-status");
        status.setMinWidth(0);
        status.setMaxWidth(Region.USE_PREF_SIZE); // the badges sit right after it, not at the far edge
        status.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS); // a long URL keeps its start and its end
        HBox.setHgrow(status, Priority.ALWAYS);
        warnBadge.getStyleClass().add("http-warn-badge");
        warnBadge.setMinWidth(Region.USE_PREF_SIZE);
        warnBadge.setOnMouseClicked(e -> notesPane.setExpanded(!notesPane.isExpanded()));
        runSummary.getStyleClass().add("http-run-summary");
        runSummary.setMinWidth(0);
        runSummary.setTextOverrun(OverrunStyle.ELLIPSIS);
        showBadge(warnBadge, null);
        showBadge(runSummary, null);
        HBox top = new HBox(8, runButton, status, warnBadge);
        top.setAlignment(Pos.CENTER_LEFT);

        envCombo.getStyleClass().add("http-env");
        envCombo.setPrefWidth(150);
        envCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String s) {
                return s == null || s.isEmpty() ? tr("httppanel.noEnv") : s;
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        envCombo.valueProperty().addListener((o, a, b) -> {
            if (!updatingEnv && onEnvironmentChanged != null) {
                onEnvironmentChanged.accept(b == null ? "" : b);
            }
        });

        historyCombo.getStyleClass().add("http-history");
        historyCombo.setPrefWidth(HISTORY_WIDTH); // a steady width: the row no longer jumps with each URL…
        historyCombo.setMaxWidth(HISTORY_WIDTH);
        historyCombo.setMinWidth(HISTORY_MIN_WIDTH); // …but it gives way in a narrow Split, not the editor
        historyCombo.setCellFactory(list -> new HistoryCell());
        historyCombo.setButtonCell(new HistoryCell());
        historyCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(HttpHistoryEntry e) {
                return e == null ? "" : e.label(zone);
            }

            @Override
            public HttpHistoryEntry fromString(String s) {
                return null;
            }
        });
        historyCombo.valueProperty().addListener((o, a, b) -> {
            if (!updatingHistory && b != null && !running) {
                showEntry(b);
            }
        });

        copyCurlButton.setText(tr("httppanel.copyAsCurl"));
        copyCurlButton.setTooltip(new javafx.scene.control.Tooltip(tr("httppanel.copyAsCurl.tip"))); // POSIX quoting
        copyCurlButton.setOnAction(e -> withSelected(onCopyAsCurl));
        openTabButton.setText(tr("httppanel.openInTab"));
        openTabButton.setOnAction(e -> withSelected(onOpenInTab));
        saveButton.setText(tr("httppanel.save"));
        saveButton.setOnAction(e -> {
            if (onSaveResponse != null) {
                onSaveResponse.run();
            }
        });
        clearButton.setText(tr("httppanel.clear"));
        clearButton.setOnAction(e -> clear());
        wrapToggle.setText(tr("httppanel.wrap"));
        wrapToggle.selectedProperty().addListener((o, a, b) -> bodyArea.setWrapText(b || isNoteOnly()));
        for (Region r : List.of(copyCurlButton, openTabButton, saveButton, clearButton, wrapToggle)) {
            r.setMinWidth(Region.USE_PREF_SIZE);
        }
        FlowPane actions = new FlowPane(6, 4, copyCurlButton, openTabButton, saveButton, clearButton, wrapToggle);

        FlowPane controls = new FlowPane(
                16,
                4,
                pickerRow(tr("httppanel.history"), historyCombo),
                pickerRow(tr("httppanel.environment"), envCombo));

        requestArea.setEditable(false);
        requestArea.setWrapText(true);
        requestArea.setPrefRowCount(4);
        requestArea.getStyleClass().add("http-request");
        requestPane.setText(tr("httppanel.request"));
        requestPane.setContent(requestArea);
        requestPane.setExpanded(false);
        requestPane.setAnimated(false);
        requestPane.getStyleClass().add("http-request-pane");

        notesArea.setEditable(false);
        notesArea.setWrapText(true);
        notesArea.getStyleClass().add("http-notes");
        notesPane.setContent(notesArea);
        notesPane.setAnimated(false);
        notesPane.getStyleClass().add("http-notes-pane");
        showNotes(List.of(), List.of());

        headersArea.setEditable(false);
        headersArea.setWrapText(true);
        headersArea.getStyleClass().add("http-headers");
        headersArea.setPrefRowCount(5);
        VBox.setVgrow(headersArea, Priority.ALWAYS);
        VBox head = new VBox(4, requestPane, notesPane, headersArea);

        bodyArea.getStyleClass().addAll("editor-area", "http-body");
        bodyArea.setEditable(false);
        bodyArea.setFocusTraversable(true);
        // No setShowCaret(OFF): a read-only area already hides its caret under the default AUTO, and OFF/ON
        // subscribe the caret to a static RichTextFX stream that then pins the area (and its window) forever.
        bodyArea.setWrapText(false);
        installBodyContextMenu(); // RichTextFX has no default menu — add Copy / Select All for the response
        imageView.setPreserveRatio(true);
        imageScroll.setVisible(false);
        imageScroll.getStyleClass().add("http-image");
        StackPane bodyHost = new StackPane(bodyScroll, imageScroll);
        setEditorFont(fontFamily, fontSize);

        SplitPane split = new SplitPane(head, bodyHost);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.3);
        SplitPane.setResizableWithParent(head, false);

        installKeys();
        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().addAll(top, runSummary, actions, controls, split); // the summary row shows after a Run All
        // A narrow Split gives the editor its share: the panel can shrink below its controls' preferred width,
        // and the History picker narrows to fit (it is the one wide control).
        setMinWidth(MIN_WIDTH);
        widthProperty().addListener((o, a, w) -> fitHistoryPicker(w.doubleValue()));
        updateActions();
        idle();
    }

    private static HBox pickerRow(String label, Node picker) {
        Label l = new Label(label);
        l.setMinWidth(Region.USE_PREF_SIZE); // a label never shrinks to "…"
        HBox row = new HBox(6, l, picker);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Keyboard: Escape returns to the editor; Tab and Shift+Tab move out of the read-only text areas (a text
     * area keeps Tab for itself, so focus used to stay in the response body for good).
     */
    private void installKeys() {
        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && !e.isShortcutDown() && !e.isAltDown()) {
                onEscape.run();
                e.consume();
            }
        });
        traverse(
                requestArea,
                () -> wrapToggle,
                () -> notesPane.isVisible() && notesPane.isExpanded() ? notesArea : headersArea);
        traverse(notesArea, () -> requestPane.isExpanded() ? requestArea : envCombo, () -> headersArea);
        traverse(
                headersArea,
                () -> notesPane.isVisible() && notesPane.isExpanded()
                        ? notesArea
                        : requestPane.isExpanded() ? requestArea : envCombo,
                () -> bodyArea);
        traverse(bodyArea, () -> headersArea, () -> runButton);
    }

    private static void traverse(Node from, Supplier<Node> back, Supplier<Node> forward) {
        from.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.TAB && !e.isShortcutDown() && !e.isAltDown()) {
                (e.isShiftDown() ? back : forward).get().requestFocus();
                e.consume();
            }
        });
    }

    /** Updates the body editor font (called on a settings/font change). */
    public void setEditorFont(String fontFamily, int fontSize) {
        fontStyle = "-fx-font-family: \"" + fontFamily + "\"; -fx-font-size: " + fontSize + "px;";
        bodyArea.setStyle(fontStyle);
        headersArea.setStyle(fontStyle);
        requestArea.setStyle(fontStyle);
        notesArea.setStyle(fontStyle);
    }

    /**
     * Right-click menu for the response body. The body is a read-only RichTextFX {@link CodeArea}, which —
     * unlike a {@code TextArea} — has no built-in context menu, so without this there's no way to copy the
     * response by mouse. Copy puts the selection (or the whole body when nothing is selected) on the
     * clipboard without disturbing the selection.
     */
    private void installBodyContextMenu() {
        MenuItem copy = new MenuItem(tr("editmenu.copy"), Icons.copy());
        copy.setOnAction(e -> {
            String text = bodyArea.getSelection().getLength() > 0 ? bodyArea.getSelectedText() : bodyArea.getText();
            ClipboardContent cc = new ClipboardContent();
            cc.putString(text);
            Clipboard.getSystemClipboard().setContent(cc);
        });
        MenuItem selectAll = new MenuItem(tr("editmenu.selectAll"), Icons.selectAll());
        selectAll.setOnAction(e -> bodyArea.selectAll());
        ContextMenu menu = new ContextMenu(copy, selectAll);
        // Use the UI font (not the body's inherited monospace), matching the editor right-click menu.
        menu.getStyleClass().add("editor-context-menu");
        bodyArea.setOnContextMenuRequested(e -> {
            menu.show(bodyArea, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    // --- run state -------------------------------------------------------------------------------------

    /** No request run yet / cleared. */
    public void idle() {
        setStatus(tr("httppanel.idle"), null);
    }

    /**
     * A request started: the previous response is cleared from view (it is still in History), the button
     * turns into Cancel, and the status line shows {@code label} with the time it has been running.
     */
    public void started(String label) {
        runningLabel = label == null ? "" : label;
        runningSince = System.nanoTime();
        setRunning(true);
        updatingHistory = true;
        historyCombo.setValue(null);
        updatingHistory = false;
        clearView();
        showBadge(runSummary, null);
        updateActions();
        showRunning();
        elapsed.playFromStart();
    }

    /** Run All progress: request {@code done + 1} of {@code total}, {@code label}, is now in flight. */
    public void progress(int done, int total, String label) {
        if (running) {
            runningLabel = tr("httppanel.runningOf", done + 1, total, label == null ? "" : label);
            showRunning();
        }
    }

    private void showRunning() {
        if (!running) {
            return;
        }
        double seconds = (System.nanoTime() - runningSince) / 1_000_000_000.0;
        setStatus(tr("httppanel.running", runningLabel, HttpStatusText.seconds(seconds)), null);
    }

    /** The running request was cancelled by the user: back to Run, showing the selected response again. */
    public void cancelled() {
        stopRunning();
        if (historyCombo.getValue() == null && !history.isEmpty()) {
            updatingHistory = true;
            historyCombo.setValue(history.get(0));
            updatingHistory = false;
        }
        HttpHistoryEntry selected = historyCombo.getValue();
        if (selected != null) {
            showEntry(selected);
        } else {
            updateActions();
        }
        setStatus(tr("httppanel.cancelled"), null);
    }

    private void stopRunning() {
        elapsed.stop();
        setRunning(false);
    }

    private void setRunning(boolean on) {
        running = on;
        runButton.setText(on ? tr("httppanel.cancel") : tr("httppanel.run"));
        runButton.getStyleClass().removeAll("danger", "accent");
        runButton.getStyleClass().add(on ? "danger" : "accent");
    }

    /** Whether a request is in flight (the button reads Cancel). */
    boolean isRunning() {
        return running;
    }

    /** Wires Cancel (the same action as the {@code http.cancelRequest} command, for this buffer). */
    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    /** Wires Run: called with the selected history entry (re-send it) or null (run the request at the caret). */
    public void setOnRun(Consumer<HttpHistoryEntry> onRun) {
        this.onRun = onRun == null ? e -> {} : onRun;
    }

    /** Where Escape goes: back to the editor. */
    public void setOnEscape(Runnable onEscape) {
        this.onEscape = onEscape == null ? () -> {} : onEscape;
    }

    /** Told when the user clears the history (so a saved history is cleared too). */
    public void setOnCleared(Runnable onCleared) {
        this.onCleared = onCleared == null ? () -> {} : onCleared;
    }

    // --- showing responses -----------------------------------------------------------------------------

    /**
     * Adds the finished {@code entries} (in run order) to the top of the history and shows the first that did
     * not succeed — for a Run All, the failure the user needs to see — else the first.
     */
    public void showEntries(List<HttpHistoryEntry> entries) {
        stopRunning();
        if (entries == null || entries.isEmpty()) {
            updateActions();
            return;
        }
        history.addAll(0, entries);
        int pick = Math.max(
                0,
                HttpStatusText.firstToShow(
                        entries.stream().map(HttpHistoryEntry::exchange).toList()));
        trimHistory();
        HttpHistoryEntry first = history.get(Math.min(pick, history.size() - 1));
        updatingHistory = true;
        historyCombo.getItems().setAll(history);
        historyCombo.setValue(first);
        updatingHistory = false;
        showEntry(first);
    }

    /** Puts a saved history back (newest first) without running anything, showing the newest. */
    public void restore(List<HttpHistoryEntry> saved) {
        if (saved == null || saved.isEmpty() || running || !history.isEmpty()) {
            return; // a run that finished before the saved history was read wins
        }
        history.addAll(saved);
        trimHistory();
        updatingHistory = true;
        historyCombo.getItems().setAll(history);
        historyCombo.setValue(history.get(0));
        updatingHistory = false;
        showEntry(history.get(0));
    }

    /**
     * Appends {@code older} entries (a file's saved history, found when an untitled buffer is first saved under
     * that name) after the ones already here; with none here yet it behaves like {@link #restore}.
     */
    public void addOlder(List<HttpHistoryEntry> older) {
        if (older == null || older.isEmpty()) {
            return;
        }
        if (history.isEmpty() && !running) {
            restore(older);
            return;
        }
        HttpHistoryEntry selected = historyCombo.getValue();
        history.addAll(older);
        trimHistory();
        updatingHistory = true;
        historyCombo.getItems().setAll(history);
        historyCombo.setValue(selected != null && history.contains(selected) ? selected : null);
        updatingHistory = false;
        updateActions();
    }

    /** The history, newest first (for saving). */
    public List<HttpHistoryEntry> history() {
        return List.copyOf(history);
    }

    private void trimHistory() {
        // Each request's newest response is kept first, so one Run All cannot push out another request's only one.
        List<HttpHistoryEntry> kept = HttpHistoryEntry.withinBudget(
                HttpHistoryRetention.keep(history, MAX_HISTORY), HttpHistoryEntry.MAX_BODY_BYTES);
        history.clear();
        history.addAll(kept);
    }

    private void showEntry(HttpHistoryEntry entry) {
        HttpExchange ex = entry.exchange();
        HttpResult r = ex.result();
        setStatus(HttpStatusText.summary(r), HttpStatusText.styleClass(r));
        requestArea.setText(requestText(ex));
        requestArea.positionCaret(0);
        showNotes(r.warnings(), r.written()); // an unsent header, a truncated body…; the files a ">>" wrote
        StringBuilder head = new StringBuilder();
        if (r.failed()) {
            head.append("⚠  ").append(r.shownError());
        } else {
            String protocol = r.protocol() == null || r.protocol().isBlank() ? "HTTP" : r.protocol();
            head.append(protocol)
                    .append(' ')
                    .append(HttpStatusText.statusWithReason(r.status()))
                    .append('\n');
            for (String[] h : r.headers()) {
                head.append(h[0]).append(": ").append(h[1]).append('\n');
            }
        }
        headersArea.setText(head.toString());
        headersArea.positionCaret(0);
        updateActions();
        renderBody(entry);
    }

    /** The request as it was sent: request line, headers, then the body after a blank line. Pure. */
    static String requestText(HttpExchange ex) {
        StringBuilder sb = new StringBuilder();
        sb.append(ex.method()).append(' ').append(ex.url()).append('\n');
        String finalUrl = ex.details() == null ? null : ex.details().finalUrl();
        if (finalUrl != null && !finalUrl.equals(ex.url())) {
            sb.append(tr("httppanel.redirectedTo", finalUrl)).append('\n'); // where the response came from
        }
        if (ex.headers() != null) {
            for (String[] h : ex.headers()) {
                sb.append(h[0]).append(": ").append(h[1]).append('\n');
            }
        }
        if (ex.requestBody() != null && !ex.requestBody().isEmpty()) {
            sb.append('\n').append(ex.requestBody());
        }
        return sb.toString();
    }

    private void setStatus(String text, String styleClass) {
        status.setText(text);
        status.getStyleClass().removeAll(HttpStatusText.ALL);
        if (styleClass != null) {
            status.getStyleClass().add(styleClass);
        }
    }

    /** Enables the actions that make sense for the selected response. */
    private void updateActions() {
        HttpHistoryEntry e = running ? null : historyCombo.getValue();
        HttpResult r = e == null ? null : e.result();
        boolean has = r != null && !r.failed();
        boolean bodyKept = e != null && !e.bodyDropped() && !e.binaryNotKept();
        copyCurlButton.setDisable(e == null);
        openTabButton.setDisable(!has || !bodyKept || r.binary());
        saveButton.setDisable(!has || !bodyKept);
        clearButton.setDisable(running || history.isEmpty());
    }

    private void clearView() {
        bodyGeneration++; // a body still being formatted must not reappear
        headersArea.clear();
        requestArea.clear();
        showNotes(List.of(), List.of());
        bodyArea.clear();
        imageView.setImage(null);
        imageScroll.setVisible(false);
        bodyScroll.setVisible(true);
    }

    /**
     * Formats + tokenizes {@code entry}'s body off the FX thread, then applies it — unless another exchange was
     * selected (or the panel cleared) meanwhile. A multi-megabyte JSON response used to freeze the window here
     * for as long as the pretty-printer and the TextMate tokenizer took.
     */
    private void renderBody(HttpHistoryEntry entry) {
        HttpResult r = entry.result();
        long generation = ++bodyGeneration;
        CompletableFuture<Void> done = new CompletableFuture<>();
        bodyRender = done;
        bodyArea.replaceText(""); // never leave the previous response under the new status + headers
        imageView.setImage(null);
        FORMAT.execute(() -> {
            if (generation != bodyGeneration) {
                done.complete(null); // superseded while queued: stepping through History no longer queues renders
                return;
            }
            Rendered rendered;
            try {
                rendered = format(r);
            } catch (RuntimeException | Error e) {
                done.completeExceptionally(e);
                return;
            }
            Platform.runLater(() -> {
                try {
                    if (generation == bodyGeneration) {
                        applyBody(entry, rendered);
                    }
                    done.complete(null);
                } catch (RuntimeException e) {
                    done.completeExceptionally(e);
                }
            });
        });
    }

    /** Worker-thread half: the pretty-printed, length-limited view, its highlight spans, or the picture. */
    private static Rendered format(HttpResult r) {
        Image image = imageOf(r);
        if (image != null) {
            return new Rendered(HttpResponseFormat.view(r, 0), "", null, image);
        }
        HttpResponseFormat.BodyView view = HttpResponseFormat.view(r, MAX_BODY_CHARS);
        String ext = extFor(r.contentType());
        String text = "xml".equals(ext) ? HttpXmlIndent.indent(view.text()) : view.text();
        StyleSpans<Collection<String>> spans = null;
        IGrammar grammar = text.isEmpty() ? null : grammarFor(ext);
        if (grammar != null) {
            try {
                spans = TextMateHighlighter.compute(text, grammar);
            } catch (RuntimeException ignored) {
                // unknown/oversized — leave it unstyled
            }
        }
        return new Rendered(view, text, spans, null);
    }

    /** The decoded picture of an {@code image/*} response (not SVG, which is text), or null. */
    private static Image imageOf(HttpResult r) {
        String ct = r.contentType() == null ? "" : r.contentType().toLowerCase(Locale.ROOT);
        if (!ct.startsWith("image/") || ct.contains("svg") || r.rawBody() == null || r.rawBody().length == 0) {
            return null;
        }
        try {
            Image image = new Image(new ByteArrayInputStream(r.rawBody()));
            return image.isError() || image.getWidth() <= 0 ? null : image;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** FX-thread half: shows the view, with a visible line wherever something was left out. */
    private void applyBody(HttpHistoryEntry entry, Rendered rendered) {
        HttpResult r = entry.result();
        if (rendered.image() != null) {
            imageView.setImage(rendered.image());
            imageScroll.setVisible(true);
            bodyScroll.setVisible(false);
            bodyArea.replaceText("");
            return;
        }
        imageScroll.setVisible(false);
        bodyScroll.setVisible(true);
        HttpResponseFormat.BodyView view = rendered.view();
        StringBuilder text = new StringBuilder(rendered.text());
        if (entry.bodyDropped()) {
            text.append(tr("httppanel.bodyDropped", humanSize(r.sizeBytes())));
        } else if (entry.binaryNotKept()) {
            text.append(tr("httppanel.binaryNotKept", humanSize(r.sizeBytes())));
        } else if (view.binary()) {
            text.append(tr("httppanel.binaryBody", humanSize(r.sizeBytes())));
        }
        if (view.clipped()) {
            // A body the saved history cut is not whole in Save or Open in tab either: do not promise it.
            text.append("\n\n")
                    .append(tr(
                            entry.bodyCut() ? "httppanel.bodyClippedOnly" : "httppanel.bodyClipped",
                            view.text().length(),
                            view.totalChars()));
        }
        if (entry.bodyCut()) {
            text.append("\n\n")
                    .append(tr("httppanel.historyCut", humanSize(r.rawBody().length), humanSize(r.sizeBytes())));
        }
        if (r.truncated()) {
            text.append("\n\n").append(tr("httppanel.responseTruncated", humanSize(r.sizeBytes())));
        }
        bodyArea.replaceText(text.toString());
        // A note in place of a body (binary, dropped, not kept) wraps whatever the Wrap toggle says.
        bodyArea.setWrapText(wrapToggle.isSelected() || rendered.text().isEmpty());
        bodyArea.setStyle(fontStyle);
        if (rendered.spans() != null && rendered.spans().length() > 0) {
            try {
                bodyArea.setStyleSpans(0, rendered.spans()); // covers the body; an appended note stays plain
            } catch (RuntimeException ignored) {
                // leave it unstyled
            }
        }
        bodyArea.moveTo(0);
        bodyArea.scrollToPixel(0, 0);
    }

    /** The body text currently shown (the view, not the raw response). Test accessor. */
    String shownBodyForTest() {
        return bodyArea.getText();
    }

    /** Whether the body pane currently shows a picture. Test accessor. */
    boolean showsImageForTest() {
        return imageScroll.isVisible() && imageView.getImage() != null;
    }

    /** The status line's text. Test accessor. */
    String statusForTest() {
        return status.getText();
    }

    /** Completes when the latest body render has landed (or been superseded). Test accessor. */
    CompletableFuture<Void> bodyRenderForTest() {
        return bodyRender;
    }

    private static IGrammar grammarFor(String ext) {
        if (ext == null) {
            return null;
        }
        try {
            return GrammarRegistry.shared().forFileName("response." + ext);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String extFor(String contentType) {
        if (contentType == null) {
            return null;
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("json")) {
            return "json";
        }
        if (ct.contains("html")) {
            return "html";
        }
        if (ct.contains("xml")) {
            return "xml";
        }
        return null;
    }

    private static String humanSize(long bytes) {
        return HttpStatusText.size(bytes);
    }

    private void withSelected(Consumer<HttpExchange> action) {
        HttpHistoryEntry e = historyCombo.getValue();
        if (e != null && action != null) {
            action.accept(e.exchange());
        }
    }

    private void clear() {
        history.clear();
        updatingHistory = true;
        historyCombo.getItems().clear();
        historyCombo.setValue(null);
        updatingHistory = false;
        clearView();
        updateActions();
        idle();
        onCleared.run();
    }

    /** Populates the environment picker; {@code active} ({@code ""} = none) is selected when this file has it. */
    public void setEnvironments(List<String> names, String active) {
        updatingEnv = true;
        List<String> items = new ArrayList<>();
        items.add(""); // the "no environment" option
        if (names != null) {
            items.addAll(names);
        }
        if (!items.equals(envCombo.getItems())) {
            envCombo.getItems().setAll(items);
        }
        envCombo.setValue(HttpEnvironments.choose(active, items)); // a name this file does not define → none
        envCombo.setDisable(items.size() <= 1);
        updatingEnv = false;
    }

    /** The selected environment name, or {@code ""} for none. */
    public String getSelectedEnvironment() {
        String v = envCombo.getValue();
        return v == null ? "" : v;
    }

    public void setOnEnvironmentChanged(Consumer<String> onEnvironmentChanged) {
        this.onEnvironmentChanged = onEnvironmentChanged;
    }

    /** The selected exchange (for Copy as cURL / Open in tab), or {@code null}. */
    public HttpExchange getSelectedExchange() {
        HttpHistoryEntry e = historyCombo.getValue();
        return e == null ? null : e.exchange();
    }

    /** The selected history entry, or {@code null}. */
    HttpHistoryEntry getSelectedEntry() {
        return historyCombo.getValue();
    }

    /** Focuses the environment picker (the {@code http.selectEnvironment} command's target). */
    public void focusEnvironment() {
        envCombo.requestFocus();
    }

    /** After a Run All: "Run All: 3 of 13 failed" beside the status line (null hides it). */
    public void showRunSummary(String summary) {
        showBadge(runSummary, summary);
    }

    /**
     * The tab closed or the feature was switched off: stops the elapsed-time animation (an indefinite timeline
     * kept the panel, its history and the closed buffer alive), drops the shown response and every callback.
     */
    public void dispose() {
        elapsed.stop();
        running = false;
        clearView();
        history.clear();
        updatingHistory = true;
        historyCombo.getItems().clear();
        historyCombo.setValue(null);
        updatingHistory = false;
        onRun = e -> {};
        onCancel = null;
        onEscape = () -> {};
        onCleared = () -> {};
        onEnvironmentChanged = null;
    }

    /** Whether the elapsed-time animation is running. Test accessor. */
    boolean isTimerRunningForTest() {
        return elapsed.getStatus() == javafx.animation.Animation.Status.RUNNING;
    }

    /** The warning badge's text, or null when hidden. Test accessor. */
    String warnBadgeForTest() {
        return warnBadge.isVisible() ? warnBadge.getText() : null;
    }

    /** The notes (warnings, files written) shown, or "" when the notes block is hidden. Test accessor. */
    String notesForTest() {
        return notesPane.isVisible() ? notesArea.getText() : "";
    }

    /** The headers text shown. Test accessor. */
    String headersForTest() {
        return headersArea.getText();
    }

    /** The Run All summary, or null when hidden. Test accessor. */
    String runSummaryForTest() {
        return runSummary.isVisible() ? runSummary.getText() : null;
    }

    private boolean isNoteOnly() {
        HttpHistoryEntry e = historyCombo.getValue();
        return e != null && (e.bodyDropped() || e.binaryNotKept() || e.result().binary());
    }

    private static void showBadge(Label badge, String text) {
        boolean on = text != null && !text.isBlank();
        badge.setText(on ? text : "");
        badge.setVisible(on);
        badge.setManaged(on);
    }

    /**
     * Shows the response's warnings and the files it wrote in their own block — they used to sit above the
     * status line in the five-row headers box, so eleven unresolved variables pushed every header out of view
     * while the status line said a plain green "200 OK". Repeated lines are shown once with a count.
     */
    private void showNotes(List<String> warnings, List<String> written) {
        List<String> lines = new ArrayList<>();
        for (String w : HttpStatusText.grouped(warnings)) {
            lines.add("⚠  " + w);
        }
        for (String w : HttpStatusText.grouped(written)) {
            lines.add("→  " + w);
        }
        int warned = warnings == null ? 0 : warnings.size();
        boolean any = !lines.isEmpty();
        notesArea.setText(String.join("\n", lines));
        notesArea.positionCaret(0);
        notesArea.setPrefRowCount(Math.max(1, Math.min(lines.size(), 4)));
        notesPane.setText(
                warned > 0
                        ? tr("httppanel.warnings", warned)
                        : tr("httppanel.filesWritten", written == null ? 0 : written.size()));
        notesPane.setExpanded(lines.size() <= NOTES_SHOWN_EXPANDED);
        notesPane.setVisible(any);
        notesPane.setManaged(any);
        showBadge(warnBadge, warned > 0 ? tr("httppanel.warnings", warned) : null);
    }

    /** Narrows the History picker to what the panel's width leaves, between its minimum and its usual width. */
    private void fitHistoryPicker(double panelWidth) {
        Insets in = getInsets();
        double room = panelWidth - in.getLeft() - in.getRight() - 90; // the "History:" label and the gap
        historyCombo.setPrefWidth(Math.max(HISTORY_MIN_WIDTH, Math.min(HISTORY_WIDTH, room)));
    }

    /** A History row: the status code coloured by outcome (or ⚠), then the time, the name and the request. */
    private final class HistoryCell extends ListCell<HttpHistoryEntry> {
        private final Label code = new Label();

        HistoryCell() {
            code.getStyleClass().add("http-history-code");
            code.setMinWidth(Region.USE_PREF_SIZE);
        }

        @Override
        protected void updateItem(HttpHistoryEntry e, boolean empty) {
            super.updateItem(e, empty);
            if (empty || e == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            HttpResult r = e.result();
            code.setText(r.failed() ? "⚠" : String.valueOf(r.status()));
            code.getStyleClass().removeAll(HttpStatusText.ALL);
            code.getStyleClass().add(HttpStatusText.styleClass(r));
            setGraphic(code);
            String full = e.label(zone); // "404  14:03:07  Get user — GET http://…": the code is the graphic
            setText(full.substring(Math.min(full.length(), code.getText().length()))
                    .strip());
        }
    }
}
