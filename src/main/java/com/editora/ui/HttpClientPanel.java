package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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

import static com.editora.i18n.Messages.tr;

/**
 * The HTTP Client response viewer: a request/response viewer modeled on {@link RunPanel}. Shows the selected
 * {@link HttpExchange}'s status + response headers in one pane and its body — syntax-highlighted by content
 * type (JSON/XML/HTML, reusing the editor's TextMate grammars) — in a read-only {@link CodeArea} below. A
 * history picker keeps the last runs, plus actions for "Copy as cURL", "Open in editor tab", Save, and
 * Clear, and an environment picker for {@code {{var}}} resolution. The controller drives it via
 * {@code started}/{@code showExchanges} on the FX thread.
 *
 * <p>This is the {@code .http} buffer's <b>preview</b> — one instance per open {@code .http} buffer, embedded
 * in the editor's Editor/Split/Preview view by {@link HttpClientCoordinator}. It scrolls its own body area,
 * so it is hosted directly (no {@code ScrollPane} wrapper), like {@link CsvGridPanel}.
 */
public final class HttpClientPanel extends VBox {

    private static final int MAX_HISTORY = 20;
    private static final int MAX_BODY_CHARS = 400_000;

    private final Label status = new Label();
    private final ComboBox<String> envCombo = new ComboBox<>();
    private final ComboBox<HttpExchange> historyCombo = new ComboBox<>();
    private final TextArea headersArea = new TextArea();
    private final CodeArea bodyArea = new CodeArea();
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

        copyCurlButton.setText(tr("httppanel.copyAsCurl"));
        copyCurlButton.setDisable(true);
        copyCurlButton.setOnAction(e -> withSelected(onCopyAsCurl));
        openTabButton.setText(tr("httppanel.openInTab"));
        openTabButton.setDisable(true);
        openTabButton.setOnAction(e -> withSelected(onOpenInTab));
        saveButton.setText(tr("httppanel.save"));
        saveButton.setDisable(true);
        saveButton.setOnAction(e -> {
            if (onSaveResponse != null) {
                onSaveResponse.run();
            }
        });
        clearButton.setText(tr("httppanel.clear"));
        clearButton.setOnAction(e -> clear());

        HBox header = new HBox(8, status, spacer(), copyCurlButton, openTabButton, clearButton, saveButton);
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
        bodyArea.setShowCaret(org.fxmisc.richtext.Caret.CaretVisibility.OFF);
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
    }

    /** Adds the finished exchanges to the history (newest first) and shows the first. */
    public void showExchanges(List<HttpExchange> exchanges) {
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

        String body = r.failed() ? "" : HttpResponseFormat.prettyBody(r.body(), r.contentType());
        if (body.length() > MAX_BODY_CHARS) {
            body = body.substring(0, MAX_BODY_CHARS);
        }
        bodyArea.replaceText(body);
        bodyArea.setStyle(fontStyle);
        applyHighlight(body, r.contentType());
        bodyArea.moveTo(0);
        bodyArea.scrollToPixel(0, 0);

        boolean has = !r.failed();
        copyCurlButton.setDisable(false);
        openTabButton.setDisable(!has);
        saveButton.setDisable(false);
    }

    private void applyHighlight(String body, String contentType) {
        if (body.isEmpty()) {
            return; // RichTextFX rejects zero-length spans
        }
        IGrammar grammar = grammarFor(contentType);
        if (grammar == null) {
            return;
        }
        try {
            bodyArea.setStyleSpans(0, TextMateHighlighter.compute(body, grammar));
        } catch (RuntimeException ignored) {
            // unknown/oversized — leave it unstyled
        }
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
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        return kb < 1024
                ? String.format(java.util.Locale.ROOT, "%.1f KB", kb)
                : String.format(java.util.Locale.ROOT, "%.1f MB", kb / 1024);
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

    /** The current response as a full text report (for Save response). */
    public String getResponseText() {
        HttpExchange ex = historyCombo.getValue();
        return ex == null ? "" : HttpResponseFormat.render(ex.result());
    }

    /** Focuses the environment picker (the {@code http.selectEnvironment} command's target). */
    public void focusEnvironment() {
        envCombo.requestFocus();
    }
}
