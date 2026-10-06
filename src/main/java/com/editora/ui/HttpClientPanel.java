package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
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
 * The HTTP Client response viewer: a request/response viewer modeled on {@link RunPanel}. Shows the selected
 * {@link HttpExchange}'s status + response headers in one pane and its body — syntax-highlighted by content
 * type (JSON/XML/HTML, reusing the editor's TextMate grammars) — in a read-only {@link CodeArea} below. A
 * history picker keeps the last runs, plus actions for Cancel, "Copy as cURL", "Open in editor tab", Save,
 * and Clear, and an environment picker for {@code {{var}}} resolution. The controller drives it via
 * {@code started}/{@code showExchanges} on the FX thread.
 *
 * <p>Formatting and tokenizing a response are proportional to its size, so they run on a background worker
 * and land under a generation guard: only the newest selection's body is applied. The body pane shows a
 * pretty-printed, length-limited <em>view</em> (with a visible line when it is cut); Save and Open-in-tab
 * always use the response as received.
 *
 * <p>This is the {@code .http} buffer's <b>preview</b> — one instance per open {@code .http} buffer, embedded
 * in the editor's Editor/Split/Preview view by {@link HttpClientCoordinator}. It scrolls its own body area,
 * so it is hosted directly (no {@code ScrollPane} wrapper), like {@link CsvGridPanel}.
 */
public final class HttpClientPanel extends VBox {

    private static final int MAX_HISTORY = 20;
    static final int MAX_BODY_CHARS = 400_000;

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

    /** A formatted body ready to apply on the FX thread: the view plus its highlight spans (or null). */
    private record Rendered(HttpResponseFormat.BodyView view, StyleSpans<Collection<String>> spans) {}

    private final Label status = new Label();
    private final ComboBox<String> envCombo = new ComboBox<>();
    private final ComboBox<HttpExchange> historyCombo = new ComboBox<>();
    private final TextArea headersArea = new TextArea();
    private final CodeArea bodyArea = AreaUndo.none(new CodeArea());
    private final Button cancelButton = new Button();
    private final Button copyCurlButton = new Button();
    private final Button openTabButton = new Button();
    private final Button saveButton = new Button();
    private final Button clearButton = new Button();

    private final List<HttpExchange> history = new ArrayList<>();
    private final Consumer<HttpExchange> onCopyAsCurl;
    private final Consumer<HttpExchange> onOpenInTab;

    /** Receives the chosen environment name ({@code ""} = none) so the controller can persist it. */
    private Consumer<String> onEnvironmentChanged;

    private boolean updatingEnv;
    private boolean updatingHistory;
    private String fontStyle;
    private Runnable onCancel;

    /** Bumped for every body shown or cleared (FX thread); a formatting result from an older one is dropped. */
    private long bodyGeneration;

    /** Completes once the most recent body render has been applied or dropped. Test seam. */
    private CompletableFuture<Void> bodyRender = CompletableFuture.completedFuture(null);

    public HttpClientPanel(
            Runnable onSaveResponse,
            Consumer<HttpExchange> onCopyAsCurl,
            Consumer<HttpExchange> onOpenInTab,
            String fontFamily,
            int fontSize) {
        this.onCopyAsCurl = onCopyAsCurl;
        this.onOpenInTab = onOpenInTab;
        getStyleClass().add("http-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));

        status.getStyleClass().add("http-status");

        envCombo.getStyleClass().add("http-env");
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
        historyCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(HttpExchange ex) {
                return ex == null ? "" : historyLabel(ex);
            }

            @Override
            public HttpExchange fromString(String s) {
                return null;
            }
        });
        historyCombo.valueProperty().addListener((o, a, b) -> {
            if (!updatingHistory && b != null) {
                showExchange(b);
            }
        });

        cancelButton.setText(tr("httppanel.cancel"));
        cancelButton.getStyleClass().add("danger");
        cancelButton.setDisable(true);
        cancelButton.setOnAction(e -> {
            if (onCancel != null) {
                onCancel.run();
            }
        });
        copyCurlButton.setText(tr("httppanel.copyAsCurl"));
        copyCurlButton.setDisable(true);
        copyCurlButton.setOnAction(e -> withSelected(onCopyAsCurl));
        openTabButton.setText(tr("httppanel.openInTab"));
        openTabButton.setDisable(true);
        openTabButton.setOnAction(e -> withSelected(onOpenInTab));
        saveButton.setText(tr("httppanel.save"));
        saveButton.getStyleClass().add("success");
        saveButton.setDisable(true);
        saveButton.setOnAction(e -> {
            if (onSaveResponse != null) {
                onSaveResponse.run();
            }
        });
        clearButton.setText(tr("httppanel.clear"));
        clearButton.setOnAction(e -> clear());

        HBox header =
                new HBox(8, status, spacer(), cancelButton, copyCurlButton, openTabButton, clearButton, saveButton);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox controls = new HBox(
                8,
                new Label(tr("httppanel.history")),
                historyCombo,
                spacer(),
                new Label(tr("httppanel.environment")),
                envCombo);
        controls.setAlignment(Pos.CENTER_LEFT);

        headersArea.setEditable(false);
        headersArea.setWrapText(true);
        headersArea.getStyleClass().add("http-headers");
        headersArea.setPrefRowCount(5);

        bodyArea.getStyleClass().addAll("editor-area", "http-body");
        bodyArea.setEditable(false);
        bodyArea.setFocusTraversable(true);
        // No setShowCaret(OFF): a read-only area already hides its caret under the default AUTO, and OFF/ON
        // subscribe the caret to a static RichTextFX stream that then pins the area (and its window) forever.
        bodyArea.setWrapText(false);
        installBodyContextMenu(); // RichTextFX has no default menu — add Copy / Select All for the response
        setEditorFont(fontFamily, fontSize);

        SplitPane split = new SplitPane(headersArea, new VirtualizedScrollPane<>(bodyArea));
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.28);
        SplitPane.setResizableWithParent(headersArea, false);

        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().addAll(header, controls, split);
        idle();
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Updates the body editor font (called on a settings/font change). */
    public void setEditorFont(String fontFamily, int fontSize) {
        fontStyle = "-fx-font-family: \"" + fontFamily + "\"; -fx-font-size: " + fontSize + "px;";
        bodyArea.setStyle(fontStyle);
        headersArea.setStyle(fontStyle);
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

    /** No request run yet / cleared. */
    public void idle() {
        status.setText(tr("httppanel.idle"));
    }

    /** A request started: shows a running note for {@code label} (method + URL). */
    public void started(String label) {
        status.setText(tr("httppanel.running", label));
        cancelButton.setDisable(false);
    }

    /** The running request was cancelled by the user: back to an idle, non-cancellable state. */
    public void cancelled() {
        status.setText(tr("httppanel.cancelled"));
        cancelButton.setDisable(true);
    }

    /** Wires the Cancel button (the same action as the {@code http.cancelRequest} command, for this buffer). */
    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    /** Adds the finished exchanges to the history (newest first) and shows the first. */
    public void showExchanges(List<HttpExchange> exchanges) {
        cancelButton.setDisable(true);
        if (exchanges == null || exchanges.isEmpty()) {
            return;
        }
        for (int i = exchanges.size() - 1; i >= 0; i--) {
            history.add(0, exchanges.get(i));
        }
        while (history.size() > MAX_HISTORY) {
            history.remove(history.size() - 1);
        }
        updatingHistory = true;
        historyCombo.getItems().setAll(history);
        updatingHistory = false;
        HttpExchange first = exchanges.get(0);
        historyCombo.setValue(first);
        showExchange(first);
        boolean allOk = exchanges.stream().allMatch(ex -> ex.result().ok());
        status.setText(allOk ? tr("httppanel.done") : tr("httppanel.failed", exchanges.size()));
    }

    private void showExchange(HttpExchange ex) {
        HttpResult r = ex.result();
        StringBuilder head = new StringBuilder();
        for (String w : r.warnings()) {
            head.append("⚠  ").append(w).append('\n'); // an unsent header, a truncated body, a missing file…
        }
        if (r.failed()) {
            head.append("⚠  ").append(r.error());
        } else {
            head.append("HTTP ").append(r.status()).append('\n');
            for (String[] h : r.headers()) {
                head.append(h[0]).append(": ").append(h[1]).append('\n');
            }
            head.append('\n')
                    .append(r.status())
                    .append("  ·  ")
                    .append(r.elapsedMs())
                    .append(" ms  ·  ")
                    .append(humanSize(r.sizeBytes()));
        }
        headersArea.setText(head.toString());
        headersArea.positionCaret(0);

        boolean has = !r.failed();
        copyCurlButton.setDisable(false);
        openTabButton.setDisable(!has || r.binary());
        saveButton.setDisable(false);
        renderBody(r);
    }

    /**
     * Formats + tokenizes {@code r}'s body off the FX thread, then applies it — unless another exchange was
     * selected (or the panel cleared) meanwhile. A multi-megabyte JSON response used to freeze the window here
     * for as long as the pretty-printer and the TextMate tokenizer took.
     */
    private void renderBody(HttpResult r) {
        long generation = ++bodyGeneration;
        CompletableFuture<Void> done = new CompletableFuture<>();
        bodyRender = done;
        bodyArea.replaceText(""); // never leave the previous response under the new status + headers
        FORMAT.execute(() -> {
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
                        applyBody(r, rendered);
                    }
                    done.complete(null);
                } catch (RuntimeException e) {
                    done.completeExceptionally(e);
                }
            });
        });
    }

    /** Worker-thread half: the pretty-printed, length-limited view and its highlight spans. */
    private static Rendered format(HttpResult r) {
        HttpResponseFormat.BodyView view = HttpResponseFormat.view(r, MAX_BODY_CHARS);
        StyleSpans<Collection<String>> spans = null;
        IGrammar grammar = view.text().isEmpty() ? null : grammarFor(r.contentType());
        if (grammar != null) {
            try {
                spans = TextMateHighlighter.compute(view.text(), grammar);
            } catch (RuntimeException ignored) {
                // unknown/oversized — leave it unstyled
            }
        }
        return new Rendered(view, spans);
    }

    /** FX-thread half: shows the view, with a visible line wherever something was left out. */
    private void applyBody(HttpResult r, Rendered rendered) {
        HttpResponseFormat.BodyView view = rendered.view();
        StringBuilder text = new StringBuilder(view.text());
        if (view.binary()) {
            text.append(tr("httppanel.binaryBody", humanSize(r.sizeBytes())));
        }
        if (view.clipped()) {
            text.append("\n\n").append(tr("httppanel.bodyClipped", view.text().length(), view.totalChars()));
        }
        if (r.truncated()) {
            text.append("\n\n").append(tr("httppanel.responseTruncated", humanSize(r.sizeBytes())));
        }
        bodyArea.replaceText(text.toString());
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

    /** Completes when the latest body render has landed (or been superseded). Test accessor. */
    CompletableFuture<Void> bodyRenderForTest() {
        return bodyRender;
    }

    private static IGrammar grammarFor(String contentType) {
        String ext = extFor(contentType);
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
        String ct = contentType.toLowerCase();
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
        return HttpResponseFormat.humanSize(bytes);
    }

    private static String historyLabel(HttpExchange ex) {
        String state = ex.result().failed() ? "⚠" : String.valueOf(ex.result().status());
        return state + "  " + ex.label();
    }

    private void withSelected(Consumer<HttpExchange> action) {
        HttpExchange ex = historyCombo.getValue();
        if (ex != null && action != null) {
            action.accept(ex);
        }
    }

    private void clear() {
        history.clear();
        updatingHistory = true;
        historyCombo.getItems().clear();
        historyCombo.setValue(null);
        updatingHistory = false;
        headersArea.clear();
        bodyGeneration++; // a body still being formatted must not reappear after Clear
        bodyArea.clear();
        copyCurlButton.setDisable(true);
        openTabButton.setDisable(true);
        saveButton.setDisable(true);
        idle();
    }

    /** Populates the environment picker; {@code active} ({@code ""} = none) is selected. */
    public void setEnvironments(List<String> names, String active) {
        updatingEnv = true;
        List<String> items = new ArrayList<>();
        items.add(""); // the "no environment" option
        if (names != null) {
            items.addAll(names);
        }
        envCombo.getItems().setAll(items);
        envCombo.setValue(active == null ? "" : active);
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
        return historyCombo.getValue();
    }

    /** The current response as a full text report with the body as received (for Save response). */
    public String getResponseText() {
        HttpExchange ex = historyCombo.getValue();
        return ex == null ? "" : HttpResponseFormat.render(ex.result());
    }

    /** Focuses the environment picker (the {@code http.selectEnvironment} command's target). */
    public void focusEnvironment() {
        envCombo.requestFocus();
    }
}
