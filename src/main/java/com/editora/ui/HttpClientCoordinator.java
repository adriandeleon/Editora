package com.editora.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
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
 * a feature coordinator. It is the most window-entangled of the coordinators — it has a response tool window
 * ({@link HttpClientPanel}), opens response tabs, reads/writes the clipboard, and scans {@code .env} files —
 * so beyond the shared {@link CoordinatorHost} it takes a small {@link WindowOps} extension for the few
 * http-specific window services (open a tab, open/toggle the tool window, re-gate the run affordance, persist
 * the selected environment). {@code MainController} keeps the {@code ToolWindow} registration + availability
 * plumbing and delegates the logic here.
 */
final class HttpClientCoordinator {

    /** The http-specific window services beyond {@link CoordinatorHost}. */
    interface WindowOps {
        /** Opens {@code buffer} in a new, selected editor tab (a response / imported-curl buffer). */
        void openTab(EditorBuffer buffer);

        /** Opens the HTTP response tool window (optionally focusing it). */
        void openToolWindow(boolean focus);

        /** Toggles the HTTP response tool window. */
        void toggleToolWindow();

        /** Re-gates the run/HTTP affordance for the active buffer (tool-window availability). */
        void updateRunGating();

        /** The persisted selected environment for this window's session. */
        String savedEnvironment();

        /** Persists the selected environment (workspace state + durable save). */
        void persistEnvironment(String env);
    }

    private final CoordinatorHost host;
    private final WindowOps ops;
    private final com.editora.http.HttpClientService service = new com.editora.http.HttpClientService();
    private HttpClientPanel panel; // built lazily for the tool window (needs the editor font from Settings)

    HttpClientCoordinator(CoordinatorHost host, WindowOps ops) {
        this.host = host;
        this.ops = ops;
    }

    /** Whether the HTTP Client is enabled (the setting, suppressed in Simple UI mode). */
    boolean isEnabled() {
        return host.settings().isHttpClientSupport() && !host.simpleModeActive();
    }

    /** The response tool window's content (built lazily; {@code MainController} wraps it in a {@code ToolWindow}). */
    HttpClientPanel panel() {
        if (panel == null) {
            var s = host.settings();
            panel = new HttpClientPanel(
                    this::saveResponse, this::copyAsCurl, this::openResponseInTab, s.getFontFamily(), Math.max(1, (int)
                            Math.round(s.getFontSize() * s.getFontZoom())));
            panel.setOnEnvironmentChanged(ops::persistEnvironment);
        }
        return panel;
    }

    /**
     * Reconciles the feature with its setting: gates the request ▶ glyphs on every buffer, updates the panel
     * font, and re-gates the tool window. Runs at startup and on every settings apply.
     */
    void applySupport() {
        boolean on = isEnabled();
        host.forEachBuffer(b -> b.setHttpEnabled(on));
        var s = host.settings();
        panel().setEditorFont(s.getFontFamily(), Math.max(1, (int) Math.round(s.getFontSize() * s.getFontZoom())));
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
        startRun(label);
        Path baseDir = buffer.getPath().toAbsolutePath().getParent();
        service.run(parsed, variables(buffer, text), baseDir, ex -> finishRun(label, ex));
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
            startRun(label);
            Path baseDir = b.getPath().toAbsolutePath().getParent();
            service.runAll(reqs, variables(b, text), baseDir, exchanges -> {
                panel().showExchanges(exchanges);
                boolean allOk = exchanges.stream().allMatch(ex -> ex.result().ok());
                host.setStatus(allOk ? tr("status.http.done", label) : tr("status.http.failed", exchanges.size()));
            });
        });
    }

    /** {@code http.selectEnvironment}: open + focus the tool window to pick an environment. */
    void selectEnvironment() {
        ifHttp(() -> ops.openToolWindow(true));
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
            HttpExchange ex = panel().getSelectedExchange();
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
            HttpExchange ex = panel().getSelectedExchange();
            if (ex == null) {
                host.setStatus(tr("status.http.noResponse"));
                return;
            }
            openResponseInTab(ex);
        });
    }

    /** {@code tool.http}: toggle the HTTP response tool window. */
    void toggleToolWindow() {
        ifHttp(ops::toggleToolWindow);
    }

    /**
     * Updates the tool window's environment picker for the active {@code .http} buffer (called from the
     * focus/tab-switch run-gating pass when this buffer is an active, enabled {@code .http} file).
     */
    void refreshEnvironments(EditorBuffer buffer) {
        if (buffer != null && buffer.getPath() != null) {
            Path dir = buffer.getPath().toAbsolutePath().getParent();
            panel().setEnvironments(environmentNames(dir), ops.savedEnvironment());
        }
    }

    // --- internals ---

    private void startRun(String label) {
        ops.openToolWindow(false);
        panel().started(label);
        host.setStatus(tr("status.http.running", label));
    }

    private void finishRun(String label, HttpExchange ex) {
        panel().showExchanges(List.of(ex));
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

    private void saveResponse() {
        String text = panel().getResponseText();
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
        String env = panel().getSelectedEnvironment();
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
