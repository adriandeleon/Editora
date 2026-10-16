package com.editora.ui;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
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
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;
import javafx.util.StringConverter;

import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.http.HttpExchange;
import com.editora.http.HttpResponseFormat;
import com.editora.http.HttpResult;
import com.editora.http.HttpTests;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

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
    static final double HISTORY_WIDTH = HttpPanelLayout.HISTORY_WIDTH;

    static final double HISTORY_MIN_WIDTH = 160;

    /** The least width the panel asks of a Split (its controls wrap or narrow below their preferred width). */
    static final double MIN_WIDTH = 260;

    /** Up to this many notes (warnings + files written) show expanded; more start collapsed behind the badge. */
    static final int NOTES_SHOWN_EXPANDED = 2;

    private final Button runButton = new Button();
    /** Holds the Run button, so a tooltip can say why it is disabled (a disabled control shows none of its own). */
    private final StackPane runHost = new StackPane(runButton);
    /** Why Run is unavailable for this file (a remote file), or null. */
    private String runBlockedWhy;

    private Tooltip runBlockedTip;
    private final Label status = new Label();
    /** "⚠ 11 warnings" beside the status line: a response can be 200 OK and still have been sent wrong. */
    private final Label warnBadge = new Label();
    /** "✓ 3 passed · ✗ 1 failed" beside the status line: what the request's @assert checks and handlers found. */
    private final Label testsBadge = new Label();
    /** The tests shown, so the badge can be shortened with the warnings badge. */
    private HttpTests shownTests = HttpTests.NONE;
    /** "Run All: 3 of 13 failed" under the status line after a Run All, until the next run. */
    private final Label runSummary = new Label();
    /** The Run All summary, and a note about the last run (Run sent the caret's request instead), until the next run. */
    private String summaryText;

    private String runNotice;
    /** The number of warnings the badge counts, and whether it is shortened to "⚠ 11" to leave the status whole. */
    private int warnCount;

    private boolean compactBadge;
    /** A note above the body when the view or the saved history holds only part of it (it used to sit at the end). */
    private final Label bodyNote = new Label();

    private final ComboBox<String> envCombo = new ComboBox<>();
    private final ComboBox<HttpHistoryEntry> historyCombo = new ComboBox<>();
    private final TextArea requestArea = new TextArea();
    private final TitledPane requestPane = new TitledPane();
    /** The response's warnings and the files a {@code >>} wrote, apart from the headers they used to bury. */
    private final TextArea notesArea = new TextArea();
    /** The Tests block: one row per test (✓/✗, name, why it failed), then the handlers' log and error. */
    private final VBox testRows = new VBox(2);

    private final TextArea testsLog = new TextArea();
    private final TitledPane testsPane = new TitledPane();

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
    private final VBox head;
    private final SplitPane split;
    private String fontFamily;
    private int fontSize;

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
        runHost.setMinWidth(Region.USE_PREF_SIZE);
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
        warnBadge.setOnMouseClicked(e -> toggleNotes());
        // Reachable by keyboard: a collapsed warnings block (more than two) could only be opened by mouse.
        warnBadge.setFocusTraversable(true);
        warnBadge.setAccessibleRole(AccessibleRole.BUTTON);
        warnBadge.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                toggleNotes();
                e.consume();
            }
        });
        testsBadge.getStyleClass().add("http-tests-badge");
        testsBadge.setMinWidth(Region.USE_PREF_SIZE);
        testsBadge.setOnMouseClicked(e -> toggleTests());
        testsBadge.setFocusTraversable(true);
        testsBadge.setAccessibleRole(AccessibleRole.BUTTON);
        testsBadge.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                toggleTests();
                e.consume();
            }
        });
        showBadge(testsBadge, null);
        runSummary.getStyleClass().add("http-run-summary");
        runSummary.setMinWidth(0);
        runSummary.setTextOverrun(OverrunStyle.ELLIPSIS);
        runSummary.setWrapText(true);
        showBadge(warnBadge, null);
        showBadge(runSummary, null);
        HBox top = new HBox(8, runHost, status, testsBadge, warnBadge);
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
        historyCombo.setMaxWidth(HttpPanelLayout.HISTORY_MAX_WIDTH); // wider in Preview, where there is room
        historyCombo.setMinWidth(HISTORY_MIN_WIDTH); // …but it gives way in a narrow Split, not the editor
        historyCombo.setCellFactory(list -> new HttpHistoryCell(zone));
        historyCombo.setButtonCell(new HttpHistoryCell(zone));
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
            if (!updatingHistory && b != null) {
                showEntry(b); // also during a long run: an older response can be looked at meanwhile
                if (running) {
                    showRunning(); // …while the status line still says what is running
                }
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
        requestPane.expandedProperty().addListener((o, a, b) -> fitHead());

        notesArea.setEditable(false);
        notesArea.setWrapText(true);
        notesArea.getStyleClass().add("http-notes");
        notesPane.setContent(notesArea);
        notesPane.setAnimated(false);
        notesPane.getStyleClass().add("http-notes-pane");
        notesPane.expandedProperty().addListener((o, a, b) -> fitHead());
        notesArea.widthProperty().addListener((o, a, w) -> {
            fitNotesRows(); // the rows its wrapped lines need at the width it actually got
            fitHead();
        });
        showNotes(List.of(), List.of());

        testRows.getStyleClass().add("http-test-rows");
        testsLog.setEditable(false);
        testsLog.setWrapText(true);
        testsLog.getStyleClass().add("http-tests-log");
        VBox testsBox = new VBox(4, testRows, testsLog);
        testsPane.setContent(testsBox);
        testsPane.setAnimated(false);
        testsPane.getStyleClass().add("http-tests-pane");
        testsPane.expandedProperty().addListener((o, a, b) -> fitHead());
        showTests(HttpTests.NONE);

        headersArea.setEditable(false);
        headersArea.setWrapText(true);
        headersArea.getStyleClass().add("http-headers");
        headersArea.setPrefRowCount(5);
        VBox.setVgrow(headersArea, Priority.ALWAYS);
        head = new VBox(4, requestPane, testsPane, notesPane, headersArea);

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
        bodyNote.getStyleClass().add("http-body-note");
        bodyNote.setWrapText(true);
        bodyNote.setMaxWidth(Double.MAX_VALUE);
        showBodyNote(null);
        VBox bodyBox = new VBox(2, bodyNote, bodyHost);
        VBox.setVgrow(bodyHost, Priority.ALWAYS);
        setEditorFont(fontFamily, fontSize);

        split = new SplitPane(head, bodyBox);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.3);
        SplitPane.setResizableWithParent(head, false);

        installKeys();
        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().addAll(top, runSummary, actions, controls, split); // the summary row shows after a Run All
        // A narrow Split gives the editor its share: the panel can shrink below its controls' preferred width,
        // and the History picker narrows to fit (it is the one wide control).
        setMinWidth(MIN_WIDTH);
        widthProperty().addListener((o, a, w) -> {
            fitHistoryPicker(w.doubleValue());
            fitBadge();
            fitNotesRows();
        });
        // The top section sizes to what it holds (warnings, the Request, five rows of headers) instead of a fixed 30%.
        split.heightProperty().addListener((o, a, h) -> fitHead());
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
        // The keymap's own "cancel" (Emacs C-g) leaves the panel like Escape. A filter: the console keys below
        // would otherwise take it as "deselect".
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            KeymapManager keymap = TextInputKeymap.sharedKeymap();
            String token = keymap == null || e.getCode() == KeyCode.ESCAPE ? null : KeyDispatcher.chord(e);
            if (token != null && KeyDispatcher.CANCEL.equals(keymap.commandFor(token))) {
                onEscape.run();
                e.consume();
            }
        });
        // The configured keymap in the read-only response areas: the panel owns its keys, so the editor-context
        // chords (Emacs M-w copy, C-n/C-p, C-v/M-v, M-</M->) are left to the focused area, which had none of them.
        ConsoleNav.installShared(bodyArea);
        for (TextArea area : List.of(requestArea, testsLog, notesArea, headersArea)) {
            TextInputKeymap.installSharedReadOnly(area);
        }
        traverse(
                requestArea,
                () -> requestPane,
                () -> testsPane.isVisible() ? testsPane : notesPane.isVisible() ? notesPane : headersArea);
        traverse(testsLog, () -> testsPane, () -> notesPane.isVisible() ? notesPane : headersArea);
        traverse(notesArea, () -> notesPane, () -> headersArea);
        traverse(
                headersArea,
                () -> notesPane.isVisible()
                        ? notesPane.isExpanded() ? notesArea : notesPane
                        : testsPane.isVisible()
                                ? testsPane.isExpanded() && testsLog.isVisible() ? testsLog : testsPane
                                : requestPane.isExpanded() ? requestArea : requestPane,
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
        this.fontFamily = fontFamily;
        this.fontSize = fontSize;
        fontStyle = "-fx-font-family: \"" + fontFamily + "\"; -fx-font-size: " + fontSize + "px;";
        bodyArea.setStyle(fontStyle);
        headersArea.setStyle(fontStyle);
        requestArea.setStyle(fontStyle);
        notesArea.setStyle(fontStyle);
        testsLog.setStyle(fontStyle);
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
        summaryText = null;
        runNotice = null;
        showSummaryRow();
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
        HttpHistoryEntry selected = historyCombo.getValue();
        if (selected != null) {
            showEntry(selected); // one the user picked during the run: its own status, not "Cancelled" over it
            return;
        }
        // Nothing is shown under "Cancelled": it used to show the previous response, which read as this one's. Any
        // History entry can be picked from here.
        clearView();
        updateActions();
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
        runButton.setDisable(!on && runBlockedWhy != null);
    }

    /**
     * Disables Run for this file, saying why in a tooltip ({@code why}; null enables it again): a remote file's
     * requests do not run from this machine.
     */
    public void setRunBlocked(String why) {
        runBlockedWhy = why;
        runButton.setDisable(!running && why != null);
        if (runBlockedTip != null) {
            Tooltip.uninstall(runHost, runBlockedTip);
            runBlockedTip = null;
        }
        if (why != null) {
            runBlockedTip = new Tooltip(why);
            Tooltip.install(runHost, runBlockedTip);
        }
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
        int pick = Math.max(
                0,
                HttpStatusText.firstToShow(
                        entries.stream().map(HttpHistoryEntry::exchange).toList()));
        HttpHistoryEntry wanted = entries.get(Math.min(pick, entries.size() - 1));
        // Newest first, like the rest of History: a Run All's requests used to be listed oldest first on top.
        history.addAll(0, entries.reversed());
        trimHistory();
        HttpHistoryEntry first = history.contains(wanted) ? wanted : history.get(0);
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
        showTests(r.tests());
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
        fitHead();
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
        // A running URL keeps its start and end; a response keeps its status code ("200 O…117 B" lost it).
        status.setTextOverrun(running ? OverrunStyle.CENTER_ELLIPSIS : OverrunStyle.ELLIPSIS);
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
        // A request that was never sent has no command to copy: its URL and headers may still be templates.
        copyCurlButton.setDisable(e == null || HttpStatusText.notSent(r));
        openTabButton.setDisable(!has || !bodyKept || r.binary());
        saveButton.setDisable(!has || !bodyKept);
        clearButton.setDisable(running || history.isEmpty());
    }

    private void clearView() {
        bodyGeneration++; // a body still being formatted must not reappear
        headersArea.clear();
        requestArea.clear();
        showNotes(List.of(), List.of());
        showTests(HttpTests.NONE);
        showBodyNote(null);
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
        HttpBodyRendering.FORMAT.execute(() -> {
            if (generation != bodyGeneration) {
                done.complete(null); // superseded while queued: stepping through History no longer queues renders
                return;
            }
            HttpBodyRendering.Rendered rendered;
            try {
                rendered = HttpBodyRendering.format(r, MAX_BODY_CHARS);
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

    /** FX-thread half: shows the view, with a visible line wherever something was left out. */
    private void applyBody(HttpHistoryEntry entry, HttpBodyRendering.Rendered rendered) {
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
        // What is missing goes above the body, where it is seen: at the end of a 400k-character view it was not.
        List<String> notes = new ArrayList<>();
        if (view.clipped()) {
            // A body the saved history cut is not whole in Save or Open in tab either: do not promise it.
            notes.add(tr(
                    entry.bodyCut() ? "httppanel.bodyClippedOnly" : "httppanel.bodyClipped",
                    view.text().length(),
                    view.totalChars()));
        }
        if (entry.bodyCut()) {
            notes.add(tr("httppanel.historyCut", humanSize(r.rawBody().length), humanSize(r.sizeBytes())));
        }
        if (r.truncated()) {
            notes.add(tr("httppanel.responseTruncated", humanSize(r.sizeBytes())));
        }
        showBodyNote(notes.isEmpty() ? null : String.join("\n", notes));
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

    // --- test accessors (compile-checked, instead of reading private fields by name) ---

    /** The panel's controls, for tests that drive or read them (compile-checked, instead of private fields by name). */
    record Controls(
            ComboBox<String> environmentPicker,
            ComboBox<HttpHistoryEntry> historyPicker,
            Button runButton,
            Button copyCurlButton,
            Button openTabButton,
            Button saveButton,
            Button clearButton,
            Label statusLabel,
            TextArea headersArea,
            TextArea notesArea,
            CodeArea bodyArea,
            Timeline elapsed,
            TitledPane notesPane,
            TitledPane requestPane,
            TextArea requestArea,
            Label warnBadgeLabel,
            Tooltip runBlockedTooltip) {}

    /** The panel's controls as they are now. Test accessor. */
    Controls controlsForTest() {
        return new Controls(
                envCombo,
                historyCombo,
                runButton,
                copyCurlButton,
                openTabButton,
                saveButton,
                clearButton,
                status,
                headersArea,
                notesArea,
                bodyArea,
                elapsed,
                notesPane,
                requestPane,
                requestArea,
                warnBadge,
                runBlockedTip);
    }

    /** The Clear button's action. */
    void clearForTest() {
        clear();
    }

    /** The body text currently shown (the view, not the raw response). Test accessor. */
    String shownBodyForTest() {
        return bodyArea.getText();
    }

    /** Whether the body pane currently shows a picture. Test accessor. */
    boolean showsImageForTest() {
        return imageScroll.isVisible() && imageView.getImage() != null;
    }

    /** Whether the warnings block is open. Test accessor. */
    boolean notesExpandedForTest() {
        return notesPane.isExpanded();
    }

    /** The note above the body (a cut view or history), or null when none shows. Test accessor. */
    String bodyNoteForTest() {
        return bodyNote.isVisible() ? bodyNote.getText() : null;
    }

    /** The status line's text. Test accessor. */
    String statusForTest() {
        return status.getText();
    }

    /** Completes when the latest body render has landed (or been superseded). Test accessor. */
    CompletableFuture<Void> bodyRenderForTest() {
        return bodyRender;
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
        summaryText = summary;
        showSummaryRow();
    }

    /**
     * A note about the run just started, shown under the status line until the next run — Run on a History entry
     * whose request is gone sends the caret's request instead, which a status-bar message alone hid within
     * milliseconds.
     */
    public void showRunNotice(String notice) {
        runNotice = notice;
        showSummaryRow();
    }

    private void showSummaryRow() {
        String text =
                summaryText == null ? runNotice : runNotice == null ? summaryText : summaryText + "\n" + runNotice;
        showBadge(runSummary, text);
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

    /** The tests badge's text, or null when hidden. Test accessor. */
    String testsBadgeForTest() {
        return testsBadge.isVisible() ? testsBadge.getText() : null;
    }

    /** The style classes of the tests badge. Test accessor. */
    List<String> testsBadgeStyleForTest() {
        return List.copyOf(testsBadge.getStyleClass());
    }

    /**
     * The Tests block as text — its title, one "✓ name" / "✗ name — why" line per test, then the log and error —
     * or "" when it is hidden. Test accessor.
     */
    String testsForTest() {
        if (!testsPane.isVisible()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(testsPane.getText());
        for (Node row : testRows.getChildren()) {
            if (row instanceof Label l) {
                sb.append('\n').append(l.getText());
            }
        }
        if (testsLog.isVisible()) {
            sb.append('\n').append(testsLog.getText());
        }
        return sb.toString();
    }

    /** Whether the Tests block is expanded. Test accessor. */
    boolean testsExpandedForTest() {
        return testsPane.isExpanded();
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
        fitNotesRows();
        notesPane.setText(
                warned > 0
                        ? tr("httppanel.warnings", warned)
                        : tr("httppanel.filesWritten", written == null ? 0 : written.size()));
        notesPane.setExpanded(lines.size() <= NOTES_SHOWN_EXPANDED);
        notesPane.setVisible(any);
        notesPane.setManaged(any);
        warnCount = warned;
        showBadge(warnBadge, warned > 0 ? HttpStatusText.warningBadge(warned, compactBadge) : null);
        fitBadge();
    }

    private void toggleNotes() {
        notesPane.setExpanded(!notesPane.isExpanded());
    }

    /**
     * Shows what the request's {@code @assert} checks and response handlers found in the Tests block: a ✓ or ✗ row
     * per test with why it failed, then the handlers' {@code client.log} lines and the error a handler stopped with.
     * Open when something failed (or there is little to show), closed when a long list all passed.
     */
    private void showTests(HttpTests tests) {
        HttpTests t = tests == null ? HttpTests.NONE : tests;
        shownTests = t;
        testRows.getChildren().clear();
        for (HttpTests.Test test : t.tests()) {
            String text = (test.passed() ? "✓  " : "✗  ")
                    + test.name()
                    + (test.passed() || test.message().isEmpty() ? "" : "  —  " + test.message());
            Label row = new Label(text);
            row.setWrapText(true);
            row.setMaxWidth(Double.MAX_VALUE);
            row.getStyleClass().addAll("http-test", test.passed() ? "http-test-passed" : "http-test-failed");
            testRows.getChildren().add(row);
        }
        testRows.setVisible(!t.tests().isEmpty());
        testRows.setManaged(!t.tests().isEmpty());
        List<String> lines = new ArrayList<>(t.log());
        if (t.error() != null) {
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add("✗  " + t.error());
        }
        boolean logged = !lines.isEmpty();
        testsLog.setText(String.join("\n", lines));
        testsLog.positionCaret(0);
        testsLog.setPrefRowCount(Math.max(1, Math.min(8, lines.size() + countWraps(lines))));
        testsLog.setVisible(logged);
        testsLog.setManaged(logged);
        boolean any = !t.isEmpty();
        testsPane.setText(any ? HttpStatusText.testsTitle(t) : "");
        testsPane.setExpanded(t.hasFailures() || t.tests().size() + lines.size() <= 4);
        testsPane.setVisible(any);
        testsPane.setManaged(any);
        testsBadge.getStyleClass().removeAll("http-tests-passed", "http-tests-failed");
        testsBadge.getStyleClass().add(HttpStatusText.testsStyle(t));
        showBadge(testsBadge, HttpStatusText.testsBadge(t, compactBadge));
        fitBadge();
    }

    /** A rough count of the extra rows long log lines wrap to, so a stack trace is not shown one line high. */
    private static int countWraps(List<String> lines) {
        int extra = 0;
        for (String l : lines) {
            extra += l.length() / 100;
        }
        return extra;
    }

    private void toggleTests() {
        testsPane.setExpanded(!testsPane.isExpanded());
    }

    private void showBodyNote(String text) {
        boolean on = text != null && !text.isBlank();
        bodyNote.setText(on ? text : "");
        bodyNote.setVisible(on);
        bodyNote.setManaged(on);
    }

    /** Sizes the notes box to its wrapped lines (it counted entries, so two long warnings showed one and a half). */
    private void fitNotesRows() {
        double width = notesArea.getWidth() > 0 ? notesArea.getWidth() : getWidth() - 24;
        int columns = HttpPanelLayout.columns(width - 28, charWidth()); // the area's padding and scroll bar
        List<String> lines = List.of(notesArea.getText().split("\n", -1));
        notesArea.setPrefRowCount(HttpPanelLayout.notesRows(lines, columns));
    }

    private double charWidth() {
        Text probe = new Text("0000000000");
        probe.setFont(fontFamily == null ? Font.font("monospace", 13) : Font.font(fontFamily, Math.max(1, fontSize)));
        return probe.getLayoutBounds().getWidth() / 10;
    }

    /**
     * Shortens the badges to "⚠ 11" and "✓3 ✗1" when the full ones would squeeze the status line itself.
     */
    private void fitBadge() {
        String testsFull = HttpStatusText.testsBadge(shownTests, false);
        if ((warnCount <= 0 && testsFull == null) || getWidth() <= 0) {
            return;
        }
        double badges = 0;
        if (warnCount > 0) {
            Text full = new Text(HttpStatusText.warningBadge(warnCount, false));
            full.setFont(warnBadge.getFont());
            badges += full.getLayoutBounds().getWidth() + 8;
        }
        if (testsFull != null) {
            Text full = new Text(testsFull);
            full.setFont(testsBadge.getFont());
            badges += full.getLayoutBounds().getWidth() + 8;
        }
        double needed = runHost.prefWidth(-1) + status.prefWidth(-1) + badges + 8 + 12;
        boolean compact =
                needed > getWidth() - getInsets().getLeft() - getInsets().getRight();
        if (warnCount > 0
                && (compact != compactBadge
                        || !warnBadge.getText().equals(HttpStatusText.warningBadge(warnCount, compact)))) {
            warnBadge.setText(HttpStatusText.warningBadge(warnCount, compact));
        }
        if (testsFull != null) {
            String t = HttpStatusText.testsBadge(shownTests, compact);
            if (!testsBadge.getText().equals(t)) {
                testsBadge.setText(t);
            }
        }
        compactBadge = compact;
    }

    /**
     * Gives the top section (Request, warnings, headers) the height it asks for, within bounds, instead of a fixed
     * 30%: with two warnings shown the headers were left two lines. Runs after layout, on each response and when
     * Request or the warnings are opened or closed; a divider the user dragged holds until then.
     */
    private void fitHead() {
        Platform.runLater(() -> {
            double h = split.getHeight();
            if (h <= 0 || getScene() == null) {
                return;
            }
            double want = head.prefHeight(split.getWidth()) + 8;
            split.setDividerPositions(HttpPanelLayout.headShare(want, h));
        });
    }

    /** Narrows the History picker to what the panel's width leaves, between its minimum and its usual width. */
    private void fitHistoryPicker(double panelWidth) {
        Insets in = getInsets();
        double room = panelWidth - in.getLeft() - in.getRight() - 90; // the "History:" label and the gap
        historyCombo.setPrefWidth(HttpPanelLayout.historyWidth(room));
    }
}
