package com.editora.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Window;
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
import org.fxmisc.richtext.model.StyleSpans;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The HTTP Client response viewer, shown as the {@code .http} buffer's <b>preview</b> (one instance per open
 * {@code .http} buffer, embedded in the editor's Editor/Split/Preview view by {@link HttpClientCoordinator}).
 *
 * <p>One header row: the <b>Run/Cancel</b> button with its chord from the keymap, the status as a pill coloured by
 * outcome ({@code 200 OK}; "Running … 1.2 s" while a request is in flight), the timing and size, then the History
 * picker ({@link HttpHeaderRow}). Under it a tab strip ({@link HttpTabStrip}): <b>Body</b> (the
 * default) · <b>Headers</b> (a name/value list) · <b>Tests</b> · <b>Request</b> (as it was actually sent, variables
 * resolved) · <b>Notes</b> (warnings and files written), with counts, and the actions as icons at its right end. A
 * run switches to Tests on its own only when a test or a script failed ({@link HttpResponseTab#choose}); otherwise
 * the tab the user picked stays. After a Run All a results list sits between the header and the tabs ({@link
 * HttpRunList}): one row per request, the one selected shown in the tabs. The body is syntax-highlighted by content type (JSON/XML/HTML, reusing the editor's
 * TextMate grammars; JSON and one-line XML pretty-printed), or the picture for an {@code image/*} response.
 *
 * <p>Between responses the Body tab shows a state instead of an empty form: before any response, which request Run
 * will send and the file's requests to run ({@link HttpEmptyState}); while a request is sent, a progress line under
 * the header and the response shown before it collapsed to one "Previous: 200 OK · 16:42:31" row (its body never
 * shows under the new run, where it read as the new response); "Waiting for your answer…", without a clock, while a
 * trust prompt is open; after Cancel, how long it ran and that row again; and for a request that got no response, a
 * card naming the failure with what to do next ({@link HttpErrorCard}).
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

    /** The History picker's usual width (it narrows first in a tight Split, and grows in a wide Preview). */
    static final double HISTORY_WIDTH = HttpPanelLayout.HISTORY_WIDTH;

    /** The least width the panel asks of a Split (its rows give way or wrap below their preferred width). */
    static final double MIN_WIDTH = 260;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Button runButton = new Button();
    /** The Run (or, while running, Cancel) chord from the live keymap; hidden when the command is unbound. */
    private final Label runChord = new Label();
    /** Holds Run and its chord, so a tooltip can say why Run is disabled (a disabled control shows none of its own). */
    private final HBox runHost = new HBox(6, runButton, runChord);
    /** Why Run is unavailable for this file (a remote file), or null. */
    private @Nullable String runBlockedWhy;

    private @Nullable Tooltip runBlockedTip;
    /** The status pill: "200 OK" coloured by outcome; plain text while running, idle or cancelled. */
    private final Label status = new Label();
    /** "83 ms · 177 B" beside the pill. */
    private final Label meta = new Label();
    /** The whole status line ("200 OK · 83 ms · 177 B"), as read by a screen reader and tests. */
    private String statusLine = "";

    private final Tooltip statusTip = new Tooltip();
    /** A note about the last run under the header (Run sent the caret's request instead), until the next run. */
    private final Label runSummary = new Label();

    private @Nullable String runNotice;
    /** After a Run All: one row per request, until the next run or Clear. */
    private final HttpRunList runList;
    /** How long the last run took from Run to its end (or to Cancel), for the Run All title; -1 unknown. */
    private long runTookNanos = -1;
    /** A note above the body when the view or the saved history holds only part of it (it used to sit at the end). */
    private final Label bodyNote = new Label();

    private final ComboBox<HttpHistoryEntry> historyCombo = new ComboBox<>();
    private final HttpHeaderRow header;
    private final HttpTabStrip tabs;
    /** The tab the user last picked by hand: it stays across runs while a response has it. */
    private HttpResponseTab pickedTab = HttpResponseTab.BODY;

    /** The request as it was sent, highlighted with the {@code .http} grammar. */
    private final CodeArea requestArea = AreaUndo.none(new CodeArea());

    private final VirtualizedScrollPane<CodeArea> requestScroll = new VirtualizedScrollPane<>(requestArea);
    /** The response's warnings and the files a {@code >>} wrote. */
    private final TextArea notesArea = new TextArea();
    /** The Tests tab: its title, one row per test (✓/✗, name, why it failed), then the handlers' log and error. */
    private final Label testsTitle = new Label();

    private final VBox testRows = new VBox(2);
    private final TextArea testsLog = new TextArea();
    private final ScrollPane testsScroll;
    /** The Headers tab: the status line, then one row per header. */
    private final Label headersStatus = new Label();

    private final TableView<String[]> headersTable = new TableView<>();
    private final TableColumn<String[], String> headerNameColumn = new TableColumn<>(tr("httppanel.headerName"));
    /** Measures the header names, to size their column. */
    private final Text probe = new Text();

    private final VBox headersBox;
    private final CodeArea bodyArea = AreaUndo.none(new CodeArea());
    private final VirtualizedScrollPane<CodeArea> bodyScroll = new VirtualizedScrollPane<>(bodyArea);
    private final HttpImageView imageView = new HttpImageView();
    private final VBox bodyBox;
    private final Map<HttpResponseTab, Node> tabContent = new EnumMap<>(HttpResponseTab.class);

    /** What the Body tab shows: a response, the empty state, the error card, the Previous row, or nothing yet. */
    enum BodyView {
        RESPONSE,
        EMPTY,
        ERROR,
        PREVIOUS,
        BLANK
    }

    private BodyView bodyView = BodyView.BLANK;
    private final HttpEmptyState emptyState;
    private final HttpErrorCard errorCard;
    /** "Previous: 200 OK · 16:42:31 · getUser": the response shown before the run, which a click shows again. */
    private final Button previousRow = new Button();

    private final VBox previousPane = new VBox(previousRow);
    /** The thin indeterminate line under the header row while a request is being sent. */
    private final ProgressBar progress = new ProgressBar(0);

    private final ReadOnlyBooleanWrapper emptyShowing = new ReadOnlyBooleanWrapper(false);
    /** The entry the tabs show, or null (empty state, running, cancelled). */
    private @Nullable HttpHistoryEntry shown;
    /** The entry shown when the current (or cancelled) run started, collapsed to {@link #previousRow}; or null. */
    private @Nullable HttpHistoryEntry previous;
    /**
     * After Cancel: History shows {@link #previous} selected, but only its row is on show — so Run sends the caret's
     * request (as it did after a cancel before), and the actions wait for the response to be shown.
     */
    private boolean collapsed;
    /** The run waits for the answer to a trust prompt: nothing is sent yet, so no clock runs. */
    private boolean waiting;
    /** The request as resolved ("GET http://…/users/42"), the running status's tooltip; or null. */
    private @Nullable String runningDetail;

    private IntConsumer onRunLine = line -> {};
    private Runnable onDisposed = () -> {};

    private final ToggleButton wrapToggle = new ToggleButton();
    private final Button copyCurlButton;
    private final Button openTabButton;
    private final Button saveButton;
    private final MenuButton moreButton = new MenuButton();
    private final MenuItem clearItem = new MenuItem(tr("httppanel.clear"), Icons.trash());
    /** The icon actions' places in the "⋯" menu, shown when a narrow strip folds the icons away. */
    private final CheckMenuItem wrapItem = new CheckMenuItem(tr("httppanel.wrap"));

    private final MenuItem copyCurlItem = new MenuItem(tr("httppanel.copyAsCurl"), Icons.copy());
    private final MenuItem openTabItem = new MenuItem(tr("httppanel.openInTab"), Icons.openInTab());
    private final MenuItem saveItem = new MenuItem(tr("httppanel.save"), Icons.saveAs());
    private final SeparatorMenuItem foldedSeparator = new SeparatorMenuItem();
    private final List<HttpHistoryEntry> history = new ArrayList<>();
    private final ZoneId zone = ZoneId.systemDefault();

    /** Run again: the selected entry (to re-send that request), or null (run the request at the caret). */
    private Consumer<HttpHistoryEntry> onRun = e -> {};

    /** A Run All row's Enter or double-click: go to that entry's request in the editor. */
    private Consumer<HttpHistoryEntry> onJump = e -> {};

    private @Nullable Runnable onCancel;
    private Runnable onEscape = () -> {};
    private Runnable onCleared = () -> {};

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
        runChord.getStyleClass().addAll("chord-chip", "http-run-chord");
        runChord.setMinWidth(Region.USE_PREF_SIZE);
        runHost.setAlignment(Pos.CENTER_LEFT);
        runHost.setMinWidth(Region.USE_PREF_SIZE);
        runButton.setOnAction(e -> {
            if (running) {
                if (onCancel != null) {
                    onCancel.run();
                }
            } else {
                onRun.accept(selectedShown()); // after a Cancel, the caret's request, as before
            }
        });

        status.getStyleClass().add("http-status");
        status.setMinWidth(0);
        status.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS); // a long URL keeps its start and its end
        meta.getStyleClass().add("http-meta");
        meta.setMinWidth(Region.USE_PREF_SIZE);
        runSummary.getStyleClass().add("http-run-summary");
        runSummary.setMinWidth(0);
        runSummary.setTextOverrun(OverrunStyle.ELLIPSIS);
        runSummary.setWrapText(true);
        showBadge(runSummary, null);

        historyCombo.getStyleClass().add("http-history");
        historyCombo.setMinWidth(0); // the header row sizes it: it narrows first in a tight Split
        historyCombo.setMaxWidth(HttpPanelLayout.HISTORY_MAX_WIDTH);
        Icons.name(historyCombo, tr("httppanel.historyName"));
        historyCombo.setCellFactory(list -> new HttpHistoryCell(zone));
        historyCombo.setButtonCell(new HttpHistoryCell(zone));
        historyCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(HttpHistoryEntry e) {
                return e == null ? "" : e.label(zone);
            }

            @Override
            public @Nullable HttpHistoryEntry fromString(String s) {
                return null;
            }
        });
        historyCombo.valueProperty().addListener((o, a, b) -> {
            if (!updatingHistory && b != null) {
                showEntry(b); // also during a long run: an older response can be looked at meanwhile
                selectRunRow(b); // its Run All row, or none: an entry from another run keeps the list unselected
                if (running) {
                    showRunning(); // …while the status line still says what is running
                }
            }
        });
        header = new HttpHeaderRow(runHost, status, meta, historyCombo);
        runList = new HttpRunList(this::showRunRow, e -> onJump.accept(e), e -> onRun.accept(e), e -> {
            if (onCopyAsCurl != null) {
                onCopyAsCurl.accept(e.exchange());
            }
        });
        // At most about 40% of the panel, scrolling on its own: the tabs below keep their room.
        runList.maxHeightProperty().bind(heightProperty().multiply(0.4));
        progress.getStyleClass().add("http-progress");
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setFocusTraversable(false);
        showProgress(false);

        // --- the actions: icons at the strip's end, Clear (and the icons, when folded) in the "⋯" menu ---
        wrapToggle.setGraphic(Icons.wrap());
        wrapToggle.getStyleClass().addAll("flat", "http-action");
        wrapToggle.selectedProperty().addListener((o, a, b) -> bodyArea.setWrapText(b || isNoteOnly()));
        wrapItem.selectedProperty().bindBidirectional(wrapToggle.selectedProperty());
        copyCurlButton = Icons.button(Icons.copy(), tr("httppanel.copyAsCurl"), () -> withSelected(onCopyAsCurl));
        openTabButton = Icons.button(Icons.openInTab(), tr("httppanel.openInTab"), () -> withSelected(onOpenInTab));
        saveButton = Icons.button(Icons.saveAs(), tr("httppanel.save"), () -> {
            if (onSaveResponse != null) {
                onSaveResponse.run();
            }
        });
        actionTip(wrapToggle, tr("httppanel.wrap"), null, null);
        actionTip(copyCurlButton, tr("httppanel.copyAsCurl"), "http.copyAsCurl", tr("httppanel.copyAsCurl.tip"));
        actionTip(openTabButton, tr("httppanel.openInTab"), "http.openResponseInTab", null);
        actionTip(saveButton, tr("httppanel.save"), null, null);
        for (Region r : List.of(copyCurlButton, openTabButton, saveButton)) {
            r.getStyleClass().addAll("flat", "http-action");
        }
        copyCurlItem.setOnAction(e -> copyCurlButton.fire());
        openTabItem.setOnAction(e -> openTabButton.fire());
        saveItem.setOnAction(e -> saveButton.fire());
        copyCurlItem.disableProperty().bind(copyCurlButton.disableProperty());
        openTabItem.disableProperty().bind(openTabButton.disableProperty());
        saveItem.disableProperty().bind(saveButton.disableProperty());
        clearItem.setOnAction(e -> clear());
        moreButton.setGraphic(Icons.more());
        moreButton.getStyleClass().addAll("flat", "http-action", "http-more");
        moreButton.setMinWidth(Region.USE_PREF_SIZE);
        Icons.name(moreButton, tr("httppanel.more"));
        moreButton.getItems().addAll(wrapItem, copyCurlItem, openTabItem, saveItem, foldedSeparator, clearItem);
        foldIcons(false);

        Map<HttpResponseTab, String> tabNames = new EnumMap<>(HttpResponseTab.class);
        tabNames.put(HttpResponseTab.BODY, tr("httppanel.tab.body"));
        tabNames.put(HttpResponseTab.HEADERS, tr("httppanel.tab.headers"));
        tabNames.put(HttpResponseTab.TESTS, tr("httppanel.tab.tests"));
        tabNames.put(HttpResponseTab.REQUEST, tr("httppanel.request"));
        tabNames.put(HttpResponseTab.NOTES, tr("httppanel.tab.notes"));
        tabs = new HttpTabStrip(
                tabNames,
                this::showTab,
                t -> pickedTab = t,
                List.of(wrapToggle, copyCurlButton, openTabButton, saveButton),
                moreButton,
                this::foldIcons);

        // --- Body ---
        bodyArea.getStyleClass().addAll("editor-area", "http-body");
        bodyArea.setEditable(false);
        bodyArea.setFocusTraversable(true);
        // No setShowCaret(OFF): a read-only area already hides its caret under the default AUTO, and OFF/ON
        // subscribe the caret to a static RichTextFX stream that then pins the area (and its window) forever.
        bodyArea.setWrapText(false);
        installCopyMenu(bodyArea); // RichTextFX has no default menu — add Copy / Select All for the response
        imageView.setVisible(false);
        StackPane bodyHost = new StackPane(bodyScroll, imageView);
        bodyNote.getStyleClass().add("http-body-note");
        bodyNote.setWrapText(true);
        bodyNote.setMaxWidth(Double.MAX_VALUE);
        showBodyNote(null);
        bodyBox = new VBox(2, bodyNote, bodyHost);
        VBox.setVgrow(bodyHost, Priority.ALWAYS);
        // The Body tab's states: before any response, while running or after Cancel, and when nothing came back.
        emptyState = new HttpEmptyState(line -> onRunLine.accept(line));
        errorCard = new HttpErrorCard(
                () -> onRun.accept(selectedShown()),
                () -> copyCurlButton.fire(),
                copyCurlButton.disableProperty(),
                () -> {
                    if (tabs != null) {
                        tabs.show(HttpResponseTab.TESTS);
                    }
                });
        previousRow.getStyleClass().addAll("flat", "http-previous");
        previousRow.setMnemonicParsing(false);
        previousRow.setMaxWidth(Double.MAX_VALUE);
        previousRow.setAlignment(Pos.CENTER_LEFT);
        previousRow.setTextOverrun(OverrunStyle.ELLIPSIS);
        previousRow.setTooltip(new Tooltip(tr("httppanel.previous.tip")));
        previousRow.setOnAction(e -> showPrevious());
        previousPane.getStyleClass().add("http-previous-pane");
        previousPane.setPadding(new Insets(6, 2, 2, 2));
        StackPane.setAlignment(errorCard, Pos.TOP_LEFT);
        StackPane bodyStack = new StackPane(bodyBox, emptyState, errorCard, previousPane);
        bodyStack.getStyleClass().add("http-body-stack");
        unmanagedWhenHidden(bodyStack);

        // --- Headers ---
        headersStatus.getStyleClass().add("http-headers-status");
        headersStatus.setMaxWidth(Double.MAX_VALUE);
        buildHeadersTable();
        headersBox = new VBox(4, headersStatus, headersTable);
        VBox.setVgrow(headersTable, Priority.ALWAYS);

        // --- Tests ---
        testsTitle.getStyleClass().add("http-tests-title");
        testRows.getStyleClass().add("http-test-rows");
        testsLog.setEditable(false);
        testsLog.setWrapText(true);
        testsLog.getStyleClass().add("http-tests-log");
        VBox testsBox = new VBox(4, testsTitle, testRows, testsLog);
        testsBox.setPadding(new Insets(4, 2, 4, 2));
        testsScroll = new ScrollPane(testsBox);
        testsScroll.setFitToWidth(true);
        testsScroll.getStyleClass().add("http-tests-pane");

        // --- Request ---
        requestArea.getStyleClass().addAll("editor-area", "http-request");
        requestArea.setEditable(false);
        requestArea.setWrapText(true);
        installCopyMenu(requestArea);

        // --- Notes ---
        notesArea.setEditable(false);
        notesArea.setWrapText(true);
        notesArea.getStyleClass().add("http-notes");

        tabContent.put(HttpResponseTab.BODY, bodyStack);
        tabContent.put(HttpResponseTab.HEADERS, headersBox);
        tabContent.put(HttpResponseTab.TESTS, testsScroll);
        tabContent.put(HttpResponseTab.REQUEST, requestScroll);
        tabContent.put(HttpResponseTab.NOTES, notesArea);
        StackPane content = new StackPane();
        content.getStyleClass().add("http-tab-content");
        for (HttpResponseTab t : HttpResponseTab.values()) {
            content.getChildren().add(tabContent.get(t));
        }
        unmanagedWhenHidden(content);
        showTab(HttpResponseTab.BODY);
        showNotes(List.of(), List.of());
        showTests(HttpTests.NONE, false);
        setEditorFont(fontFamily, fontSize);

        installKeys();
        VBox.setVgrow(content, Priority.ALWAYS);
        VBox headerBox = new VBox(3, header, progress);
        getChildren().addAll(headerBox, runSummary, runList, tabs, content); // the run notice; the Run All list
        // A narrow Split gives the editor its share: the panel can shrink below its rows' preferred width.
        setMinWidth(MIN_WIDTH);
        setRunning(false);
        // The chords follow the keymap: re-read when the window comes back (after a keymap change in Settings).
        sceneProperty()
                .flatMap(Scene::windowProperty)
                .flatMap(Window::focusedProperty)
                .addListener((o, a, focused) -> {
                    if (Boolean.TRUE.equals(focused)) {
                        refreshRunChord();
                    }
                });
        updateActions();
        showBodyView(BodyView.EMPTY);
        idle();
    }

    /**
     * Names an icon action ({@code name} is its accessible text) and gives it a tooltip that adds the keymap's chord
     * for {@code commandId} when it is bound, read when the tooltip shows (so a keymap change is reflected), and
     * {@code extra} on a line of its own.
     */
    private static void actionTip(Control control, String name, @Nullable String commandId, @Nullable String extra) {
        control.setAccessibleText(name);
        Tooltip tip = new Tooltip(name);
        tip.setOnShowing(e -> tip.setText(withChord(name, commandId) + (extra == null ? "" : "\n" + extra)));
        tip.setWrapText(extra != null);
        tip.setMaxWidth(420);
        control.setTooltip(tip);
    }

    /** {@code name}, then the live keymap's chord for {@code commandId} in brackets when it is bound. */
    private static String withChord(String name, @Nullable String commandId) {
        String chord = chordFor(commandId);
        return chord == null ? name : tr("httppanel.actionChord", name, chord);
    }

    /** The live keymap's chord for {@code commandId}, or null when unbound (or no keymap is set yet). */
    private static @Nullable String chordFor(@Nullable String commandId) {
        KeymapManager keymap = TextInputKeymap.sharedKeymap();
        String chord = keymap == null || commandId == null ? null : keymap.displayChord(commandId);
        return chord == null || chord.isEmpty() ? null : chord;
    }

    /** Shows Run's (or, while running, Cancel's) chord beside the button; hidden when the command is unbound. */
    private void refreshRunChord() {
        String chord = chordFor(running ? "http.cancelRequest" : "http.runRequest");
        runChord.setText(chord == null ? "" : chord);
        runChord.setVisible(chord != null);
        runChord.setManaged(chord != null);
        runButton.setAccessibleText(
                chord == null ? runButton.getText() : tr("httppanel.actionChord", runButton.getText(), chord));
        emptyState.setChord(chordFor("http.runRequest"));
    }

    /** Folded (a narrow strip): the icons' actions show in the "⋯" menu above Clear. */
    private void foldIcons(boolean folded) {
        for (MenuItem item : List.of(wrapItem, copyCurlItem, openTabItem, saveItem, foldedSeparator)) {
            item.setVisible(folded);
        }
    }

    private void buildHeadersTable() {
        headersTable.getStyleClass().add("http-headers");
        headersTable.setPlaceholder(new Label(""));
        headersTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        TableColumn<String[], String> name = headerNameColumn;
        name.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue()[0]));
        name.getStyleClass().add("http-header-name");
        name.setCellFactory(col -> new WrappingCell(col));
        TableColumn<String[], String> value = new TableColumn<>(tr("httppanel.headerValue"));
        value.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue()[1]));
        value.getStyleClass().add("http-header-value");
        value.setCellFactory(col -> new WrappingCell(col)); // a long cookie or policy wraps instead of ending in "…"
        // The value column takes what the name column leaves (less the scroll bar's room).
        value.prefWidthProperty()
                .bind(headersTable
                        .widthProperty()
                        .subtract(name.widthProperty())
                        .subtract(18));
        for (TableColumn<String[], String> col : List.of(name, value)) {
            col.setSortable(false);
            col.setReorderable(false);
        }
        headersTable.getColumns().setAll(List.of(name, value));
        MenuItem copyValue = new MenuItem(tr("httppanel.copyValue"), Icons.copy());
        copyValue.setOnAction(e -> copyHeaders(false));
        MenuItem copyLine = new MenuItem(tr("httppanel.copyHeader"));
        copyLine.setOnAction(e -> copyHeaders(true));
        MenuItem copyAll = new MenuItem(tr("httppanel.copyAllHeaders"));
        copyAll.setOnAction(e -> putOnClipboard(headersText()));
        ContextMenu menu = new ContextMenu(copyValue, copyLine, copyAll);
        menu.setOnShowing(e -> {
            boolean any = !headersTable.getSelectionModel().getSelectedItems().isEmpty();
            copyValue.setDisable(!any);
            copyLine.setDisable(!any);
        });
        headersTable.setContextMenu(menu);
        // Copy by keyboard: the platform's copy chord, or the keymap's (Emacs M-w), copies the selected rows.
        headersTable.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            KeymapManager keymap = TextInputKeymap.sharedKeymap();
            String token = keymap == null ? null : KeyDispatcher.chord(e);
            String command = keymap == null || token == null ? null : keymap.commandFor(token);
            boolean copy = "edit.copy".equals(command) || (e.getCode() == KeyCode.C && e.isShortcutDown());
            if (copy) {
                copyHeaders(true);
                e.consume();
            } else if ("edit.selectAll".equals(command)) {
                headersTable.getSelectionModel().selectAll();
                e.consume();
            }
        });
    }

    /** Sizes the name column to the longest name, within a third of the table (a long name wraps). */
    private void fitHeaderNames(List<String[]> headers) {
        probe.setFont(headersStatus.getFont());
        double widest = 60;
        for (String[] h : headers) {
            probe.setText(h[0]);
            widest = Math.max(widest, probe.getLayoutBounds().getWidth());
        }
        double cap = Math.max(120, headersTable.getWidth() / 3);
        headerNameColumn.setPrefWidth(Math.min(cap, Math.ceil(widest) + 24));
    }

    /** A header cell whose text wraps at the column's width. */
    private static final class WrappingCell extends TableCell<String[], String> {
        private final Text text = new Text();

        WrappingCell(TableColumn<String[], String> column) {
            text.getStyleClass().add("http-header-text");
            text.wrappingWidthProperty().bind(column.widthProperty().subtract(14));
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            text.setText(empty || item == null ? "" : item);
            setGraphic(empty || item == null ? null : text);
        }
    }

    /** Copies the selected headers: their values, or "name: value" lines. */
    private void copyHeaders(boolean withNames) {
        List<String> lines = new ArrayList<>();
        for (String[] h : headersTable.getSelectionModel().getSelectedItems()) {
            lines.add(withNames ? h[0] + ": " + h[1] : h[1]);
        }
        if (!lines.isEmpty()) {
            putOnClipboard(String.join("\n", lines));
        }
    }

    private static void putOnClipboard(String text) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(text);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    /** The Headers tab as text: the status line, then one "name: value" line per header; "" when none shows. */
    private String headersText() {
        if (headersStatus.getText() == null || headersStatus.getText().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(headersStatus.getText()).append('\n');
        for (String[] h : headersTable.getItems()) {
            sb.append(h[0]).append(": ").append(h[1]).append('\n');
        }
        return sb.toString();
    }

    /**
     * Keyboard: Escape returns to the editor; Tab and Shift+Tab move out of the read-only text areas (a text
     * area keeps Tab for itself, so focus used to stay in the response body for good): Tab from a tab's content
     * goes round to Run, Shift+Tab back to the strip's last action.
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
            if (keymap != null && token != null && KeyDispatcher.CANCEL.equals(keymap.commandFor(token))) {
                onEscape.run();
                e.consume();
            }
        });
        // The configured keymap in the read-only response areas: the panel owns its keys, so the editor-context
        // chords (Emacs M-w copy, C-n/C-p, C-v/M-v, M-</M->) are left to the focused area, which had none of them.
        ConsoleNav.installShared(bodyArea);
        ConsoleNav.installShared(requestArea);
        for (TextArea area : List.of(testsLog, notesArea)) {
            TextInputKeymap.installSharedReadOnly(area);
        }
        Supplier<Node> first = () -> runButton.isDisabled() ? historyCombo : runButton;
        Supplier<Node> last = () -> moreButton;
        for (Node area : List.of(bodyArea, requestArea, testsLog, notesArea, headersTable)) {
            traverse(area, last, first);
        }
    }

    private static void traverse(Node from, Supplier<Node> back, Supplier<Node> forward) {
        from.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.TAB && !e.isShortcutDown() && !e.isAltDown()) {
                (e.isShiftDown() ? back : forward).get().requestFocus();
                e.consume();
            }
        });
    }

    /** Updates the response view's text font (called on a settings/font change). */
    public void setEditorFont(String fontFamily, int fontSize) {
        fontStyle = "-fx-font-family: \"" + fontFamily + "\"; -fx-font-size: " + fontSize + "px;";
        for (Node n : List.<Node>of(bodyArea, requestArea, notesArea, testsLog, headersTable, headersStatus)) {
            n.setStyle(fontStyle);
        }
    }

    /**
     * Right-click menu for a read-only RichTextFX {@link CodeArea} (the body, the request), which — unlike a {@code
     * TextArea} — has no built-in context menu, so without this there's no way to copy by mouse. Copy puts the
     * selection (or the whole text when nothing is selected) on the clipboard without disturbing the selection.
     */
    private static void installCopyMenu(CodeArea area) {
        MenuItem copy = new MenuItem(tr("editmenu.copy"), Icons.copy());
        copy.setOnAction(
                e -> putOnClipboard(area.getSelection().getLength() > 0 ? area.getSelectedText() : area.getText()));
        MenuItem selectAll = new MenuItem(tr("editmenu.selectAll"), Icons.selectAll());
        selectAll.setOnAction(e -> area.selectAll());
        ContextMenu menu = new ContextMenu(copy, selectAll);
        // Use the UI font (not the area's inherited monospace), matching the editor right-click menu.
        menu.getStyleClass().add("editor-context-menu");
        area.setOnContextMenuRequested(e -> {
            menu.show(area, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    // --- run state -------------------------------------------------------------------------------------

    /** No request run yet / cleared: the empty state says what Run will send, so the status line says nothing. */
    public void idle() {
        setStatus("", null, "", null);
    }

    /** As {@link #started(String, String)} without a resolved request to show in the tooltip. */
    public void started(String label) {
        started(label, null);
    }

    /**
     * A request started: the response on show is collapsed to the "Previous" row (still in History; its body is not
     * left under the new run, where it read as the new response), the button turns into Cancel, a progress line runs
     * under the header, and the status says "Sending {@code label}…" with the time it has been running; {@code
     * detail} (the request as resolved, null for none) is its tooltip.
     */
    public void started(@Nullable String label, @Nullable String detail) {
        runningLabel = label == null ? "" : label;
        runningDetail = detail == null || detail.equals(runningLabel) ? null : detail;
        runningSince = System.nanoTime();
        waiting = false;
        // A run that replaces one in flight keeps the response that was on show before the first.
        previous = shown != null ? shown : collapsed || running ? previous : null;
        collapsed = false;
        setRunning(true);
        updatingHistory = true;
        historyCombo.setValue(null);
        updatingHistory = false;
        clearView();
        runNotice = null;
        showSummaryRow();
        runList.hide(); // a Run All's results stay until the next run, whichever it is
        runTookNanos = -1;
        showPreviousRow();
        updateActions();
        showProgress(true);
        showRunning();
        elapsed.playFromStart();
    }

    /**
     * The run waits for the answer to a trust prompt ({@code true}) or got it ({@code false}): "Waiting for your
     * answer…" without a clock while nothing is sent, and the clock starts from zero once the request goes out.
     */
    public void awaitingAnswer(boolean on) {
        if (!running || waiting == on) {
            return;
        }
        waiting = on;
        if (!on) {
            runningSince = System.nanoTime();
        }
        showProgress(!on);
        showRunning();
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
        if (waiting) {
            setStatus(tr("httppanel.waiting"), null, "", runningDetail);
            return;
        }
        String seconds = HttpPanelStates.secondsSince(runningSince, System.nanoTime());
        setStatus(tr("httppanel.running", runningLabel, seconds), null, "", runningDetail);
    }

    /**
     * The running request was cancelled by the user: back to Run. The status says how long it ran, and the response
     * shown before it comes back as its "Previous" row, selected in History; with none, the empty state. A response
     * the user picked from History during the run stays, with its own status (the cancel is said above the tabs).
     */
    public void cancelled() {
        String ran = tr("httppanel.cancelledAfter", HttpPanelStates.secondsSince(runningSince, System.nanoTime()));
        boolean sending = running && !waiting;
        stopRunning();
        String said = sending ? ran : "";
        HttpHistoryEntry picked = historyCombo.getValue();
        if (picked != null && picked == shown) {
            showEntry(picked);
            showRunNotice(said.isEmpty() ? null : said);
            return;
        }
        rest(said);
    }

    /**
     * Back to what was on show before the run, with nothing new to show: the "Previous" row (selected in History) when
     * there was a response, else the empty state; {@code statusText} on the status line.
     */
    private void rest(String statusText) {
        clearView();
        if (previous != null && history.contains(previous)) {
            collapsed = true;
            updatingHistory = true;
            historyCombo.setValue(previous);
            updatingHistory = false;
            showPreviousRow();
        } else {
            previous = null;
            collapsed = false;
            showBodyView(BodyView.EMPTY);
        }
        updateActions();
        setStatus(statusText, null, "", null);
    }

    private void stopRunning() {
        if (running) {
            runTookNanos = System.nanoTime() - runningSince;
        }
        elapsed.stop();
        waiting = false;
        showProgress(false);
        setRunning(false);
    }

    /** Shows or hides the progress line (an indeterminate bar animates only while it is shown). */
    private void showProgress(boolean on) {
        progress.setProgress(on ? ProgressBar.INDETERMINATE_PROGRESS : 0);
        progress.setVisible(on);
        progress.setManaged(on);
    }

    /** The Body tab shows {@link #previous}'s row, or nothing when there was none. */
    private void showPreviousRow() {
        if (previous == null) {
            showBodyView(BodyView.BLANK);
            return;
        }
        String line = HttpPanelStates.previousLine(previous, zone);
        previousRow.setText(line);
        previousRow.setAccessibleText(line + ". " + tr("httppanel.previous.tip"));
        showBodyView(BodyView.PREVIOUS);
    }

    /** The "Previous" row was clicked: its response is shown (during a run, the status still says what runs). */
    private void showPrevious() {
        HttpHistoryEntry p = previous;
        if (p == null || !history.contains(p)) {
            return;
        }
        updatingHistory = true;
        historyCombo.setValue(p);
        updatingHistory = false;
        showEntry(p);
        if (running) {
            showRunning();
        }
    }

    /**
     * Switches what the Body tab shows. The tab strip shows with a response, or while the "Previous" row stands for
     * one; in the empty state and a first run it has nothing to offer (only Body, every action disabled) and goes.
     */
    private void showBodyView(BodyView view) {
        bodyView = view;
        bodyBox.setVisible(view == BodyView.RESPONSE);
        emptyState.setVisible(view == BodyView.EMPTY);
        errorCard.setVisible(view == BodyView.ERROR);
        previousPane.setVisible(view == BodyView.PREVIOUS);
        boolean strip = view == BodyView.RESPONSE || view == BodyView.ERROR || view == BodyView.PREVIOUS;
        tabs.setVisible(strip);
        tabs.setManaged(strip);
        if (!strip) {
            tabs.show(HttpResponseTab.BODY);
        }
        boolean anyHistory = !history.isEmpty();
        historyCombo.setVisible(anyHistory);
        emptyShowing.set(view == BodyView.EMPTY);
    }

    private void setRunning(boolean on) {
        running = on;
        runButton.setText(on ? tr("httppanel.cancel") : tr("httppanel.run"));
        runButton.getStyleClass().removeAll("danger", "accent");
        runButton.getStyleClass().add(on ? "danger" : "accent");
        runButton.setDisable(!on && runBlockedWhy != null);
        header.setStatusShrinks(on || waiting);
        refreshRunChord();
    }

    /**
     * Disables Run for this file, saying why in a tooltip ({@code why}; null enables it again): a remote file's
     * requests do not run from this machine.
     */
    public void setRunBlocked(@Nullable String why) {
        runBlockedWhy = why;
        runButton.setDisable(!running && why != null);
        emptyState.setRunBlocked(why != null);
        errorCard.setRunBlocked(why != null);
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

    /** Wires the empty state's rows: called with the line of the request a row stands for (run it). */
    public void setOnRunLine(IntConsumer onRunLine) {
        this.onRunLine = onRunLine == null ? line -> {} : onRunLine;
    }

    /** Told once when the panel is disposed (who feeds its empty state lets go of the buffer). */
    void setOnDisposed(Runnable onDisposed) {
        this.onDisposed = onDisposed == null ? () -> {} : onDisposed;
    }

    /** Whether the Body tab shows the empty state (its feed follows the buffer only then). */
    ReadOnlyBooleanProperty emptyStateShowingProperty() {
        return emptyShowing.getReadOnlyProperty();
    }

    /** Fills the empty state: the file's requests, and which of them ({@code caret}, -1 for none) Run sends. */
    void showEmptyRequests(List<HttpEmptyState.Row> rows, int caret) {
        emptyState.setRequests(rows, caret);
    }

    // --- showing responses -----------------------------------------------------------------------------

    /**
     * Adds the finished {@code entries} (in run order) to the top of the history and shows the first that did
     * not succeed — for a Run All, the failure the user needs to see — else the first.
     */
    public void showEntries(List<HttpHistoryEntry> entries) {
        runList.hide();
        addAndShow(entries);
        runTookNanos = -1; // a later Run All that sends nothing (never "started") has no time to show
    }

    /**
     * The finished {@code entries} of a Run All (in run order), shown as {@link #showEntries} does and listed one row
     * per request above the tabs, the one shown selected. {@code cancelledOf} is the number of requests of a Run All
     * the user cancelled (0: it finished). Fewer than two requests in a run that finished show no list.
     */
    public void showRunAll(List<HttpHistoryEntry> entries, int cancelledOf) {
        if (entries == null || (entries.size() < 2 && cancelledOf < 2)) {
            showEntries(entries);
            return;
        }
        stopRunning(); // from Run to here (or to Cancel, which stopped it already)
        long tookMs = runTookNanos < 0 ? -1 : runTookNanos / 1_000_000;
        runTookNanos = -1;
        HttpHistoryEntry shown = addAndShow(entries);
        runList.show(entries, cancelledOf, tookMs, shown);
    }

    /** Adds {@code entries} to History and shows the one to land on; returns it (null when there are none). */
    private @Nullable HttpHistoryEntry addAndShow(List<HttpHistoryEntry> entries) {
        boolean wasRunning = running;
        stopRunning();
        if (entries == null || entries.isEmpty()) {
            if (wasRunning && shown == null) {
                rest(""); // nothing came back: what was on show before
            }
            updateActions();
            return null;
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
        previous = null;
        showEntry(first);
        return first;
    }

    /** {@code entry} was picked in History: its Run All row is selected, or no row when it has none. */
    private void selectRunRow(HttpHistoryEntry entry) {
        runList.select(entry);
    }

    /** A Run All row was selected: shows its response (the History's copy of it, when History still has it). */
    private void showRunRow(HttpHistoryEntry entry) {
        HttpHistoryEntry shown = entry;
        for (HttpHistoryEntry h : history) {
            if (HttpRunRows.sameRun(h, entry)) {
                shown = h;
                break;
            }
        }
        updatingHistory = true;
        historyCombo.setValue(shown);
        updatingHistory = false;
        showEntry(shown);
        if (running) {
            showRunning();
        }
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
        historyCombo.setVisible(!history.isEmpty());
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
        shown = entry;
        collapsed = false;
        String sent = TIME.format(Instant.ofEpochMilli(entry.at()).atZone(zone));
        String tip = HttpStatusText.summary(r) + "\n" + tr("httppanel.sentAt", sent);
        String timing = HttpStatusText.timing(r);
        // Which environment this response was sent in: the pill beside the editor says what the next run uses.
        String env = entry.environment();
        if (env != null && !env.isEmpty()) {
            String note = tr("httppanel.envNote", env);
            timing = timing.isEmpty() ? note : tr("httppanel.statusLine", timing, note);
            tip += "\n" + tr("httppanel.sentIn", env);
        }
        setStatus(HttpStatusText.pill(r), HttpStatusText.styleClass(r), timing, tip);
        // A request that got no response shows its card; the strip comes back with the response.
        showBodyView(r.failed() ? BodyView.ERROR : BodyView.RESPONSE);
        showNotes(r.warnings(), r.written()); // an unsent header, a truncated body…; the files a ">>" wrote
        boolean preRequest = HttpStatusText.preRequestFailed(r);
        showTests(r.tests(), preRequest);
        if (r.failed()) {
            headersStatus.setText("");
            headersTable.getItems().clear();
        } else {
            String protocol = r.protocol() == null || r.protocol().isBlank() ? "HTTP" : r.protocol();
            headersStatus.setText(protocol + ' ' + HttpStatusText.statusWithReason(r.status()));
            headersTable.getItems().setAll(r.headers());
            fitHeaderNames(r.headers());
        }
        tabs.setCount(
                HttpResponseTab.HEADERS,
                r.failed() ? null : String.valueOf(r.headers().size()),
                HttpTabStrip.CountStyle.NEUTRAL,
                tr("httppanel.headersCount", r.headers().size()));
        Set<HttpResponseTab> available = EnumSet.of(HttpResponseTab.BODY, HttpResponseTab.REQUEST);
        if (!r.failed()) {
            available.add(HttpResponseTab.HEADERS);
        }
        if (!r.tests().isEmpty()) {
            available.add(HttpResponseTab.TESTS);
        }
        if (!notesArea.getText().isEmpty()) {
            available.add(HttpResponseTab.NOTES);
        }
        tabs.setAvailable(available);
        // A send failure opens on its card in Body, a failed test on Tests; otherwise the tab picked by hand stays.
        tabs.show(HttpResponseTab.choose(pickedTab, r.tests().hasFailures(), r.failed(), available));
        updateActions();
        render(entry, requestText(ex));
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

    /**
     * Sets the status: {@code text} as a pill coloured by {@code outcome} (an {@link HttpStatusText} style class), or
     * as plain text when {@code outcome} is null (running, idle, cancelled); {@code timing} beside it; {@code tip}
     * (null for none) as its tooltip.
     */
    private void setStatus(String text, @Nullable String outcome, String timing, @Nullable String tip) {
        status.setText(text);
        meta.setText(timing == null ? "" : timing);
        statusLine = meta.getText().isEmpty() ? text : tr("httppanel.statusLine", text, meta.getText());
        status.setAccessibleText(statusLine);
        status.getStyleClass().removeAll(HttpStatusText.ALL);
        status.getStyleClass().remove("state-pill");
        if (outcome != null) {
            status.getStyleClass().addAll("state-pill", outcome);
        }
        if (tip == null) {
            status.setTooltip(null);
        } else {
            statusTip.setText(tip);
            status.setTooltip(statusTip);
        }
    }

    /** Enables the actions that make sense for the selected response. */
    private void updateActions() {
        HttpHistoryEntry e = running ? null : selectedShown();
        HttpResult r = e == null ? null : e.result();
        boolean has = r != null && !r.failed();
        boolean bodyKept = e != null && !e.bodyDropped() && !e.binaryNotKept();
        // A request that was never sent has no command to copy: its URL and headers may still be templates.
        copyCurlButton.setDisable(e == null || HttpStatusText.notSent(r));
        openTabButton.setDisable(!has || !bodyKept || r == null || r.binary());
        saveButton.setDisable(!has || !bodyKept);
        clearItem.setDisable(running || history.isEmpty());
    }

    private void clearView() {
        bodyGeneration++; // a body still being formatted must not reappear
        shown = null;
        headersStatus.setText("");
        headersTable.getItems().clear();
        requestArea.clear();
        showNotes(List.of(), List.of());
        showTests(HttpTests.NONE, false);
        tabs.setCount(HttpResponseTab.HEADERS, null, null, null);
        tabs.setAvailable(EnumSet.of(HttpResponseTab.BODY));
        showBodyNote(null);
        bodyArea.clear();
        imageView.clear();
        imageView.setVisible(false);
        bodyScroll.setVisible(true);
    }

    /**
     * Shows {@code entry}'s request now and formats + tokenizes its body and the request's highlighting off the FX
     * thread, then applies them — unless another exchange was selected (or the panel cleared) meanwhile. A
     * multi-megabyte JSON response used to freeze the window here for as long as the pretty-printer and the
     * TextMate tokenizer took. A request that failed shows its error in the body's place.
     */
    private void render(HttpHistoryEntry entry, String request) {
        HttpResult r = entry.result();
        long generation = ++bodyGeneration;
        CompletableFuture<Void> done = new CompletableFuture<>();
        bodyRender = done;
        bodyArea.replaceText(""); // never leave the previous response under the new status + headers
        imageView.clear();
        String shownRequest =
                request.length() > MAX_BODY_CHARS ? request.substring(0, MAX_BODY_CHARS) + "\n…" : request;
        requestArea.replaceText(shownRequest);
        requestArea.moveTo(0);
        requestArea.requestFollowCaret();
        if (r.failed()) {
            showError(r);
        }
        HttpBodyRendering.FORMAT.execute(() -> {
            if (generation != bodyGeneration) {
                done.complete(null); // superseded while queued: stepping through History no longer queues renders
                return;
            }
            HttpBodyRendering.Rendered rendered;
            StyleSpans<Collection<String>> requestSpans;
            try {
                rendered = r.failed() ? null : HttpBodyRendering.format(r, MAX_BODY_CHARS);
                requestSpans = HttpBodyRendering.highlight(shownRequest, "http");
            } catch (RuntimeException | Error e) {
                done.completeExceptionally(e);
                return;
            }
            Platform.runLater(() -> {
                try {
                    if (generation == bodyGeneration) {
                        if (rendered != null) {
                            applyBody(entry, rendered);
                        }
                        applySpans(requestArea, requestSpans);
                    }
                    done.complete(null);
                } catch (RuntimeException e) {
                    done.completeExceptionally(e);
                }
            });
        });
    }

    /** A request that got no response: the card naming the failure, with its explanation and what to do next. */
    private void showError(HttpResult r) {
        imageView.setVisible(false);
        bodyScroll.setVisible(true);
        showBodyNote(null);
        errorCard.show(HttpFailureKind.of(r), r.shownError());
    }

    private static void applySpans(CodeArea area, @Nullable StyleSpans<Collection<String>> spans) {
        if (spans != null && spans.length() > 0 && spans.length() <= area.getLength()) {
            try {
                area.setStyleSpans(0, spans);
            } catch (RuntimeException ignored) {
                // leave it unstyled
            }
        }
    }

    /** FX-thread half: shows the view, with a visible line wherever something was left out. */
    private void applyBody(HttpHistoryEntry entry, HttpBodyRendering.Rendered rendered) {
        HttpResult r = entry.result();
        if (rendered.image() != null) {
            imageView.show(rendered.image(), r.contentType(), r.sizeBytes());
            imageView.setVisible(true);
            bodyScroll.setVisible(false);
            bodyArea.replaceText("");
            return;
        }
        imageView.setVisible(false);
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
        applySpans(bodyArea, rendered.spans()); // covers the body; an appended note stays plain
        bodyArea.moveTo(0);
        bodyArea.scrollToPixel(0, 0);
    }

    /** Shows {@code tab}'s content (the strip's selection changed, by the user or by a run). */
    /**
     * Only the shown child of {@code stack} counts in its size. A hidden empty state (a row per request) or tab used to
     * ask for its full height, and the panel took that room from the Run All list above the tabs.
     */
    private static void unmanagedWhenHidden(StackPane stack) {
        for (Node child : stack.getChildren()) {
            child.managedProperty().bind(child.visibleProperty());
        }
    }

    private void showTab(HttpResponseTab tab) {
        tabContent.forEach((t, node) -> node.setVisible(t == tab));
    }

    // --- test accessors (compile-checked, instead of reading private fields by name) ---

    /** The panel's controls, for tests that drive or read them (compile-checked, instead of private fields by name). */
    record Controls(
            ComboBox<HttpHistoryEntry> historyPicker,
            Button runButton,
            Label runChord,
            ProgressBar progress,
            Button copyCurlButton,
            Button openTabButton,
            Button saveButton,
            ToggleButton wrapToggle,
            MenuButton moreButton,
            MenuItem clearItem,
            Label statusLabel,
            Label metaLabel,
            TableView<String[]> headersTable,
            TextArea notesArea,
            CodeArea bodyArea,
            CodeArea requestArea,
            TextArea testsLog,
            Timeline elapsed,
            @Nullable Tooltip runBlockedTooltip) {}

    /** The panel's controls as they are now. Test accessor. */
    Controls controlsForTest() {
        return new Controls(
                historyCombo,
                runButton,
                runChord,
                progress,
                copyCurlButton,
                openTabButton,
                saveButton,
                wrapToggle,
                moreButton,
                clearItem,
                status,
                meta,
                headersTable,
                notesArea,
                bodyArea,
                requestArea,
                testsLog,
                elapsed,
                runBlockedTip);
    }

    /** The tab strip. Test accessor. */
    HttpTabStrip tabsForTest() {
        return tabs;
    }

    /** What the Body tab shows. Test accessor. */
    BodyView bodyViewForTest() {
        return bodyView;
    }

    /** The empty state. Test accessor. */
    HttpEmptyState emptyStateForTest() {
        return emptyState;
    }

    /** The error card. Test accessor. */
    HttpErrorCard errorCardForTest() {
        return errorCard;
    }

    /** The "Previous" row's text, or null when it is not on show. Test accessor. */
    @Nullable
    String previousRowForTest() {
        return bodyView == BodyView.PREVIOUS ? previousRow.getText() : null;
    }

    /** Clicks the "Previous" row. Test accessor. */
    void clickPreviousForTest() {
        previousRow.fire();
    }

    /** The header row. Test accessor. */
    HttpHeaderRow headerForTest() {
        return header;
    }

    /** {@code tab}'s content pane. Test accessor. */
    @Nullable
    Node tabContentForTest(HttpResponseTab tab) {
        return tabContent.get(tab);
    }

    /** The selected tab. Test accessor. */
    HttpResponseTab selectedTabForTest() {
        return tabs.selected();
    }

    /** What {@code tab} shows ("Tests 2/3", only "2/3" when narrow), or null when it is hidden. Test accessor. */
    @Nullable
    String tabTextForTest(HttpResponseTab tab) {
        return tabs.shownTextForTest(tab);
    }

    /** The Clear action. */
    void clearForTest() {
        clear();
    }

    /** The body text currently shown (the view, not the raw response). Test accessor. */
    String shownBodyForTest() {
        return bodyArea.getText();
    }

    /** Whether the body pane currently shows a picture. Test accessor. */
    boolean showsImageForTest() {
        return imageView.isVisible() && imageView.hasImage();
    }

    HttpImageView imageViewForTest() {
        return imageView;
    }

    /** The note above the body (a cut view or history), or null when none shows. Test accessor. */
    @Nullable
    String bodyNoteForTest() {
        return bodyNote.isVisible() ? bodyNote.getText() : null;
    }

    /** The status line's text: the pill and the timing beside it ("200 OK  ·  3 ms  ·  44 B"). Test accessor. */
    String statusForTest() {
        return statusLine;
    }

    /** Completes when the latest body render has landed (or been superseded). Test accessor. */
    CompletableFuture<Void> bodyRenderForTest() {
        return bodyRender;
    }

    private static String humanSize(long bytes) {
        return HttpStatusText.size(bytes);
    }

    /** The selected History entry when it is on show — not while only its "Previous" row stands for it. */
    private @Nullable HttpHistoryEntry selectedShown() {
        return collapsed ? null : historyCombo.getValue();
    }

    private void withSelected(Consumer<HttpExchange> action) {
        HttpHistoryEntry e = selectedShown();
        if (e != null && action != null) {
            action.accept(e.exchange());
        }
    }

    private void clear() {
        runList.hide();
        history.clear();
        updatingHistory = true;
        historyCombo.getItems().clear();
        historyCombo.setValue(null);
        updatingHistory = false;
        previous = null;
        collapsed = false;
        clearView();
        showBodyView(BodyView.EMPTY);
        updateActions();
        idle();
        onCleared.run();
    }

    /** The selected exchange (for Copy as cURL / Open in tab), or {@code null}. */
    public @Nullable HttpExchange getSelectedExchange() {
        HttpHistoryEntry e = selectedShown();
        return e == null ? null : e.exchange();
    }

    /** The selected history entry, or {@code null}. */
    @Nullable
    HttpHistoryEntry getSelectedEntry() {
        return selectedShown();
    }

    /**
     * A note about the run just started, shown under the header until the next run — Run on a History entry whose
     * request is gone sends the caret's request instead, which a status-bar message alone hid within milliseconds.
     */
    public void showRunNotice(@Nullable String notice) {
        runNotice = notice;
        showSummaryRow();
    }

    private void showSummaryRow() {
        showBadge(runSummary, runNotice);
    }

    /** Where a Run All row's Enter or double-click goes: that entry's request in the editor. */
    public void setOnJumpToRequest(Consumer<HttpHistoryEntry> onJump) {
        this.onJump = onJump == null ? e -> {} : onJump;
    }

    /**
     * Selects {@code entry} in History and shows it — a click on a request's result in the editor. The entry is
     * found by its saved key, since trimming may have replaced it with a copy without its body. False when History
     * no longer holds it.
     */
    public boolean showHistoryEntry(HttpHistoryEntry entry) {
        if (entry == null) {
            return false;
        }
        String key = entry.storedKey();
        for (HttpHistoryEntry e : history) {
            if (e == entry || e.storedKey().equals(key)) {
                historyCombo.setValue(e); // its listener shows it (also during a run, as a pick from History does)
                return true;
            }
        }
        return false;
    }

    /**
     * The tab closed or the feature was switched off: stops the elapsed-time animation (an indefinite timeline
     * kept the panel, its history and the closed buffer alive), drops the shown response and every callback.
     */
    public void dispose() {
        elapsed.stop();
        showProgress(false);
        running = false;
        waiting = false;
        previous = null;
        clearView();
        runList.hide();
        Runnable disposed = onDisposed;
        onDisposed = () -> {};
        disposed.run();
        onRunLine = line -> {};
        history.clear();
        updatingHistory = true;
        historyCombo.getItems().clear();
        historyCombo.setValue(null);
        updatingHistory = false;
        onRun = e -> {};
        onJump = e -> {};
        onCancel = null;
        onEscape = () -> {};
        onCleared = () -> {};
    }

    /** The notes (warnings, files written) shown, or "" when there are none. Test accessor. */
    String notesForTest() {
        return notesArea.getText();
    }

    /** The Headers tab as text: the status line, then "name: value" lines; "" when none shows. Test accessor. */
    String headersForTest() {
        return headersText();
    }

    /**
     * The Tests tab as text — its title, one "✓ name" / "✗ name — why" line per test, then the log and error — or ""
     * when the response ran none. Test accessor.
     */
    String testsForTest() {
        if (testsTitle.getText().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(testsTitle.getText());
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

    /** The note about the last run under the header, or null when hidden. Test accessor. */
    @Nullable
    String runNoticeForTest() {
        return runSummary.isVisible() ? runSummary.getText() : null;
    }

    /** The Run All results list. Test accessor. */
    HttpRunList runListForTest() {
        return runList;
    }

    private boolean isNoteOnly() {
        HttpHistoryEntry e = selectedShown();
        return e != null
                && (e.bodyDropped()
                        || e.binaryNotKept()
                        || e.result().binary()
                        || e.result().failed());
    }

    private static void showBadge(Label badge, @Nullable String text) {
        boolean on = text != null && !text.isBlank();
        badge.setText(on ? text : "");
        badge.setVisible(on);
        badge.setManaged(on);
    }

    /**
     * Fills the Notes tab with the response's warnings and the files it wrote — they used to sit above the status
     * line in the five-row headers box, so eleven unresolved variables pushed every header out of view while the
     * status line said a plain green "200 OK". Repeated lines are shown once with a count. The tab's count is amber
     * when there is a warning; a run does not switch to it.
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
        int wrote = written == null ? 0 : written.size();
        notesArea.setText(String.join("\n", lines));
        notesArea.positionCaret(0);
        String detail = warned > 0 && wrote > 0
                ? HttpStatusText.warningBadge(warned, false) + ", " + tr("httppanel.filesWritten", wrote)
                : warned > 0 ? HttpStatusText.warningBadge(warned, false) : tr("httppanel.filesWritten", wrote);
        tabs.setCount(
                HttpResponseTab.NOTES,
                lines.isEmpty() ? null : String.valueOf(warned + wrote),
                warned > 0 ? HttpTabStrip.CountStyle.WARN : HttpTabStrip.CountStyle.NEUTRAL,
                detail);
    }

    /**
     * Shows what the request's {@code @assert} checks and scripts found in the Tests tab: a ✓ or ✗ row per test with
     * why it failed, then the scripts' {@code client.log} lines and the error a script stopped with, named as a
     * pre-request script's when {@code preRequest}. The tab's count is passed of total, red when something failed.
     */
    private void showTests(HttpTests tests, boolean preRequest) {
        HttpTests t = tests == null ? HttpTests.NONE : tests;
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
        testsLog.setPrefRowCount(Math.max(2, Math.min(20, lines.size() + countWraps(lines))));
        testsLog.setVisible(logged);
        testsLog.setManaged(logged);
        testsTitle.setText(t.isEmpty() ? "" : HttpStatusText.testsTitle(t, preRequest));
        tabs.setCount(
                HttpResponseTab.TESTS,
                HttpStatusText.testsCount(t),
                t.hasFailures() ? HttpTabStrip.CountStyle.FAILED : HttpTabStrip.CountStyle.PASSED,
                HttpStatusText.testsBadge(t, false, preRequest));
    }

    /** A rough count of the extra rows long log lines wrap to, so a stack trace is not shown one line high. */
    private static int countWraps(List<String> lines) {
        int extra = 0;
        for (String l : lines) {
            extra += l.length() / 100;
        }
        return extra;
    }

    private void showBodyNote(@Nullable String text) {
        boolean on = text != null && !text.isBlank();
        bodyNote.setText(on ? text : "");
        bodyNote.setVisible(on);
        bodyNote.setManaged(on);
    }
}
