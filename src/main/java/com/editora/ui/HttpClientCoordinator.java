package com.editora.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.FileChooser;

import com.editora.editor.EditorBuffer;
import com.editora.http.CurlExport;
import com.editora.http.CurlImport;
import com.editora.http.HttpEnv;
import com.editora.http.HttpExchange;
import com.editora.http.HttpFile;
import com.editora.http.HttpResponseFormat;
import com.editora.http.HttpResult;
import com.editora.http.HttpVars;

import static com.editora.i18n.Messages.tr;

/**
 * Owns the HTTP Client feature (the {@code .http} request runner), extracted from {@code MainController} as
 * a feature coordinator. The response viewer ({@link HttpClientPanel}) is embedded <em>in the editor</em> as
 * that buffer's preview — an Editor/Split/Preview view of the {@code .http} file, mirroring the CSV grid (see
 * {@link CsvCoordinator}) rather than living in a tool window. So each {@code .http} buffer gets its
 * <b>own</b> panel (a Node can't live in two tabs), injected via {@link EditorBuffer#setHttpPreviewNode}; once
 * injected the buffer reports {@link EditorBuffer#hasPreview()} and the floating {@link MarkdownViewToggle}
 * attaches automatically. Unlike every other preview it is not rendered from the buffer text — the debounced
 * edit pulse deliberately skips it and only a completed run repopulates it.
 *
 * <p>Beyond the shared {@link CoordinatorHost} it takes a small {@link WindowOps} extension for the few
 * http-specific window services (open a tab, re-gate the run affordance, persist the selected environment).
 */
final class HttpClientCoordinator {

    /** The http-specific window services beyond {@link CoordinatorHost}. */
    interface WindowOps {
        /** Opens {@code buffer} in a new, selected editor tab (a response / imported-curl buffer). */
        void openTab(EditorBuffer buffer);

        /** Re-gates the run affordance for the active buffer (Run tool-window availability). */
        void updateRunGating();

        /** The persisted selected environment for this window's session. */
        String savedEnvironment();

        /** Persists the selected environment (workspace state + durable save). */
        void persistEnvironment(String env);
    }

    private final CoordinatorHost host;
    private final WindowOps ops;
    private final com.editora.http.HttpClientService service = new com.editora.http.HttpClientService();

    /** One response panel per open {@code .http} buffer (a Node can't be shared across tabs); dropped on
     *  close ({@link #onBufferClosed}) and when the feature is switched off. */
    private final Map<EditorBuffer, HttpClientPanel> panels = new IdentityHashMap<>();

    HttpClientCoordinator(CoordinatorHost host, WindowOps ops) {
        this.host = host;
        this.ops = ops;
    }

    /** Whether the HTTP Client is enabled (the setting, suppressed in Simple UI mode). */
    boolean isEnabled() {
        return host.settings().isHttpClientSupport() && !host.simpleModeActive();
    }

    // --- preview lifecycle -----------------------------------------------------------------------------

    /**
     * Attaches or removes the in-editor response panel to match the HTTP-client gate, mirroring {@link
     * CsvCoordinator#ensureCsvPreview}. Called from {@code ensurePreviewControls}/{@code
     * restoreMarkdownMode} <em>before</em> they read {@link EditorBuffer#hasPreview()}, since for a
     * node-gated preview the injected node <em>is</em> the gate.
     */
    void ensureHttpPreview(EditorBuffer buffer) {
        if (buffer == null) {
            return;
        }
        boolean want = buffer.isHttpFile() && isEnabled();
        boolean has = buffer.hasHttpPreview();
        if (want && !has) {
            HttpClientPanel p = createPanelFor(buffer);
            panels.put(buffer, p);
            buffer.setHttpPreviewNode(p); // makes hasPreview() true → the toggle attaches (controller)
            refreshEnvironments(buffer);
        } else if (!want && has) {
            buffer.setHttpPreviewNode(null); // resets the buffer to EDITOR
            panels.remove(buffer);
        }
    }

    /** Re-target on tab switch: (re)evaluate the active buffer's preview attachment + environment list. */
    void refreshFor(EditorBuffer active) {
        if (active != null) {
            ensureHttpPreview(active);
        }
    }

    /** Drops the closed buffer's panel (the map is strongly keyed, so this hook is required). */
    void onBufferClosed(EditorBuffer closed) {
        if (closed != null) {
            panels.remove(closed);
        }
    }

    private HttpClientPanel createPanelFor(EditorBuffer buffer) {
        var s = host.settings();
        HttpClientPanel p = new HttpClientPanel(
                () -> saveResponse(buffer),
                this::copyAsCurl,
                this::openResponseInTab,
                s.getFontFamily(),
                editorFontSize());
        p.setOnEnvironmentChanged(ops::persistEnvironment);
        return p;
    }

    private int editorFontSize() {
        var s = host.settings();
        return Math.max(1, (int) Math.round(s.getFontSize() * s.getFontZoom()));
    }

    /** This buffer's response panel, or {@code null} when it has none (not a {@code .http} file / feature off). */
    private HttpClientPanel panelFor(EditorBuffer buffer) {
        return buffer == null ? null : panels.get(buffer);
    }

    /** The active buffer's response panel, or {@code null}. */
    private HttpClientPanel activePanel() {
        return panelFor(host.activeBuffer());
    }

    /** Test-harness accessor for a buffer's response panel (mirrors {@code CsvCoordinator.gridNodeFor}). */
    HttpClientPanel panelForTest(EditorBuffer buffer) {
        return panelFor(buffer);
    }

    /**
     * Reconciles the feature with its setting: gates the request ▶ glyphs on every buffer, (de)attaches the
     * in-editor preview, refreshes each panel's font, and re-gates the run affordance. Runs at startup and on
     * every settings apply.
     */
    void applySupport() {
        boolean on = isEnabled();
        host.forEachBuffer(b -> {
            b.setHttpEnabled(on);
            ensureHttpPreview(b);
        });
        String family = host.settings().getFontFamily();
        int size = editorFontSize();
        panels.values().forEach(p -> p.setEditorFont(family, size));
        ops.updateRunGating();
    }

    /** Whether {@code line} of {@code buffer} should run a request (run-glyph handler / per-buffer gate). */
    void runRequest(EditorBuffer buffer, int line) {
        if (!isEnabled()) {
            host.setStatus(tr("statusbar.tip.httpDisabled"));
            return;
        }
        if (buffer == null || buffer.getPath() == null) {
            host.setStatus(tr("status.http.saveFirst"));
            return;
        }
        String text = buffer.getContent();
        int index = HttpFile.requestIndexAt(text, line);
        if (index < 0) {
            host.setStatus(tr("status.http.noRequest"));
            return;
        }
        HttpFile.Request req = HttpFile.parse(text).get(index);
        HttpFile.Parsed parsed = HttpFile.parseRequest(req);
        String label = parsed.method() + " " + parsed.url();
        startRun(buffer, label);
        Path baseDir = buffer.getPath().toAbsolutePath().getParent();
        service.run(parsed, variables(buffer, text), baseDir, ex -> finishRun(buffer, label, ex));
    }

    // --- commands ---

    /** {@code http.runRequest}: run the request at the caret line of the active {@code .http} buffer. */
    void runRequestAtCaret() {
        ifHttp(() -> {
            EditorBuffer b = host.activeBuffer();
            if (b == null || !b.isHttpFile()) {
                host.setStatus(tr("status.http.noRequest"));
                return;
            }
            runRequest(b, b.getArea().getCurrentParagraph());
        });
    }

    /** {@code http.runFile}: run every request in the active {@code .http} file sequentially. */
    void runFile() {
        ifHttp(() -> {
            EditorBuffer b = host.activeBuffer();
            if (b == null || !b.isHttpFile()) {
                host.setStatus(tr("status.http.noRequest"));
                return;
            }
            if (b.getPath() == null) {
                host.setStatus(tr("status.http.saveFirst"));
                return;
            }
            String text = b.getContent();
            List<HttpFile.Parsed> reqs =
                    HttpFile.parse(text).stream().map(HttpFile::parseRequest).toList();
            if (reqs.isEmpty()) {
                host.setStatus(tr("status.http.noRequest"));
                return;
            }
            String label = b.getPath().getFileName().toString();
            startRun(b, label);
            Path baseDir = b.getPath().toAbsolutePath().getParent();
            service.runAll(reqs, variables(b, text), baseDir, exchanges -> {
                HttpClientPanel p = panelFor(b);
                if (p != null) {
                    p.showExchanges(exchanges);
                }
                boolean allOk = exchanges.stream().allMatch(ex -> ex.result().ok());
                host.setStatus(allOk ? tr("status.http.done", label) : tr("status.http.failed", exchanges.size()));
            });
        });
    }

    /** {@code http.selectEnvironment}: reveal the response preview and focus its environment picker. */
    void selectEnvironment() {
        ifHttp(() -> {
            EditorBuffer b = host.activeBuffer();
            HttpClientPanel p = panelFor(b);
            if (p == null) {
                host.setStatus(tr("status.http.noRequest"));
                return;
            }
            b.revealHttpPreview();
            refreshEnvironments(b);
            p.focusEnvironment();
        });
    }

    /** {@code http.importCurl}: turn a clipboard {@code curl} command into a request block. */
    void importCurl() {
        ifHttp(() -> {
            String clip = Clipboard.getSystemClipboard().getString();
            if (clip == null || clip.isBlank() || !clip.contains("curl")) {
                host.setStatus(tr("status.http.notCurl"));
                return;
            }
            String request = CurlImport.toHttpRequest(clip.strip());
            EditorBuffer b = host.activeBuffer();
            if (b != null && b.isHttpFile() && b.isEditable()) {
                String snippet = (b.getArea().getLength() == 0 ? "" : "\n") + "###\n" + request + "\n";
                b.getArea().insertText(b.getArea().getLength(), snippet);
                b.getArea().moveTo(b.getArea().getLength());
            } else {
                EditorBuffer nb = new EditorBuffer();
                nb.setDisplayName("requests.http");
                ops.openTab(nb);
                nb.setContent(request);
            }
            host.setStatus(tr("status.http.curlImported"));
        });
    }

    /** {@code http.copyAsCurl}: copy the response viewer's selected request as a {@code curl} command. */
    void copyActiveAsCurl() {
        ifHttp(() -> {
            HttpClientPanel p = activePanel();
            HttpExchange ex = p == null ? null : p.getSelectedExchange();
            if (ex == null) {
                host.setStatus(tr("status.http.noResponse"));
                return;
            }
            copyAsCurl(ex);
        });
    }

    /** {@code http.openResponseInTab}: open the response viewer's selected response in a new editor tab. */
    void openActiveResponseInTab() {
        ifHttp(() -> {
            HttpClientPanel p = activePanel();
            HttpExchange ex = p == null ? null : p.getSelectedExchange();
            if (ex == null) {
                host.setStatus(tr("status.http.noResponse"));
                return;
            }
            openResponseInTab(ex);
        });
    }

    /**
     * Updates this buffer's environment picker from the {@code .env}/env-JSON files beside it (called when
     * the preview is attached and from the focus/tab-switch run-gating pass).
     */
    void refreshEnvironments(EditorBuffer buffer) {
        HttpClientPanel p = panelFor(buffer);
        if (p != null && buffer.getPath() != null) {
            Path dir = buffer.getPath().toAbsolutePath().getParent();
            p.setEnvironments(environmentNames(dir), ops.savedEnvironment());
        }
    }

    // --- internals ---

    /**
     * Shows the request as running in {@code buffer}'s response preview, revealing that preview (Editor →
     * Split) so the result is visible without the user having to open it — the in-editor equivalent of the
     * tool window this feature used to auto-open.
     */
    private void startRun(EditorBuffer buffer, String label) {
        HttpClientPanel p = panelFor(buffer);
        if (p != null) {
            buffer.revealHttpPreview();
            p.started(label);
        }
        host.setStatus(tr("status.http.running", label));
    }

    private void finishRun(EditorBuffer buffer, String label, HttpExchange ex) {
        HttpClientPanel p = panelFor(buffer);
        if (p != null) {
            p.showExchanges(List.of(ex));
        }
        HttpResult r = ex.result();
        host.setStatus(r.failed() || !r.ok() ? tr("status.http.failed", r.status()) : tr("status.http.done", label));
    }

    private void copyAsCurl(HttpExchange ex) {
        String curl = CurlExport.toCurl(ex.method(), ex.url(), ex.headers(), ex.requestBody());
        ClipboardContent cc = new ClipboardContent();
        cc.putString(curl);
        Clipboard.getSystemClipboard().setContent(cc);
        host.setStatus(tr("status.http.curlCopied"));
    }

    private void openResponseInTab(HttpExchange ex) {
        HttpResult r = ex.result();
        if (r.failed()) {
            host.setStatus(tr("status.http.noResponse"));
            return;
        }
        EditorBuffer buffer = new EditorBuffer();
        buffer.setDisplayName("response" + HttpResponseFormat.extensionFor(r.contentType()));
        ops.openTab(buffer);
        buffer.setContent(HttpResponseFormat.prettyBody(r.body(), r.contentType()));
    }

    /** Save-response, bound to the owning buffer's own panel when it is created. */
    private void saveResponse(EditorBuffer buffer) {
        HttpClientPanel p = panelFor(buffer);
        String text = p == null ? null : p.getResponseText();
        if (text == null || text.isEmpty()) {
            host.setStatus(tr("status.http.noResponse"));
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("dialog.saveResponse.title"));
        chooser.setInitialFileName("response.txt");
        File chosen = chooser.showSaveDialog(host.window());
        if (chosen == null) {
            return;
        }
        try {
            Files.writeString(chosen.toPath(), text);
            host.setStatus(tr("status.http.saved", chosen.toPath().getFileName()));
        } catch (IOException e) {
            host.setStatus(tr("status.http.saveFailed", e.getMessage()));
        }
    }

    /** The resolved variable map for a {@code .http} buffer: env vars overlaid with the file's {@code @var}s. */
    private Map<String, String> variables(EditorBuffer buffer, String text) {
        Path dir = buffer.getPath().toAbsolutePath().getParent();
        // This buffer's own picker — each .http file has its own panel, so a run always resolves {{vars}}
        // against the environment selected for the file being run.
        HttpClientPanel p = panelFor(buffer);
        String env = p == null ? "" : p.getSelectedEnvironment();
        Map<String, String> envVars = new LinkedHashMap<>();
        envVars.putAll(envVars(dir == null ? null : dir.resolve("http-client.env.json"), env));
        envVars.putAll(envVars(dir == null ? null : dir.resolve("http-client.private.env.json"), env));
        return HttpVars.resolve(envVars, HttpFile.fileVariablePairs(text), LocalDateTime.now());
    }

    private Map<String, String> envVars(Path file, String env) {
        if (file == null || env == null || env.isEmpty() || !Files.exists(file)) {
            return Map.of();
        }
        try {
            return HttpEnv.variables(Files.readString(file), env);
        } catch (IOException e) {
            return Map.of();
        }
    }

    /** The environment names declared in the {@code .http} file's directory (for the picker). */
    private List<String> environmentNames(Path dir) {
        if (dir == null) {
            return List.of();
        }
        Path env = dir.resolve("http-client.env.json");
        if (!Files.exists(env)) {
            return List.of();
        }
        try {
            return HttpEnv.environmentNames(Files.readString(env));
        } catch (IOException e) {
            return List.of();
        }
    }

    private void ifHttp(Runnable action) {
        if (isEnabled()) {
            action.run();
        } else {
            host.setStatus(tr("statusbar.tip.httpDisabled"));
        }
    }

    /** Stops the HTTP-client worker thread (window close). */
    public void shutdown() {
        service.shutdown();
    }
}
