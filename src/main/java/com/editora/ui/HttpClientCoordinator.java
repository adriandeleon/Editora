package com.editora.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.FileChooser;

import com.editora.config.HttpResponseHistory;
import com.editora.editor.EditorBuffer;
import com.editora.http.CapturedResponses;
import com.editora.http.CurlExport;
import com.editora.http.CurlImport;
import com.editora.http.HttpEnv;
import com.editora.http.HttpExchange;
import com.editora.http.HttpFile;
import com.editora.http.HttpResponseFormat;
import com.editora.http.HttpResult;
import com.editora.http.HttpVars;
import com.editora.http.ResponseRedirects;

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
 * <p>Per {@code .http} file it remembers, in the config folder ({@link HttpResponseHistory}), the environment
 * chosen for it and its last responses, so reopening the file shows what it last returned.
 *
 * <p>Beyond the shared {@link CoordinatorHost} it takes a small {@link WindowOps} extension for the few
 * http-specific window services (open a tab, re-gate the run affordance, the history store).
 */
final class HttpClientCoordinator {

    /** The http-specific window services beyond {@link CoordinatorHost}. */
    interface WindowOps {
        /** Opens {@code buffer} in a new, selected editor tab (a response / imported-curl buffer). */
        void openTab(EditorBuffer buffer);

        /** Re-gates the run affordance for the active buffer (Run tool-window availability). */
        void updateRunGating();

        /**
         * The environment this window remembered before the choice became per file (the old window-wide
         * setting). Used only for a file with no choice of its own, and only when that file defines it.
         */
        default String savedEnvironment() {
            return "";
        }

        /** Where each {@code .http} file's environment and last responses are kept; null = keep nothing. */
        default HttpResponseHistory historyStore() {
            return null;
        }

        /** The window's project root, where the search for env files stops; null when there is no project. */
        default Path projectRoot() {
            return null;
        }

        /**
         * Local File History, which records a file's content before a {@code >>!} redirect replaces it; null
         * when the window has none (the replace then proceeds unrecorded, and the response view says so).
         */
        default HistoryCoordinator history() {
            return null;
        }

        /**
         * Whether a request in {@code file} may read the secrets {@code reads} ({@code $processEnv.X},
         * {@code $dotenv.X}) and send them to {@code hosts}: true when the file's folder is trusted, or the user
         * trusts it now (the build-wrapper trust store, so one answer covers both). Blocking, on the FX thread. A
         * window without a trust store refuses, so nothing secret is sent unasked.
         */
        default boolean allowSecretReads(Path file, List<String> hosts, List<String> reads) {
            return false;
        }
    }

    private final CoordinatorHost host;
    private final WindowOps ops;
    private final com.editora.http.HttpClientService service = new com.editora.http.HttpClientService();

    /** One response panel per open {@code .http} buffer (a Node can't be shared across tabs); dropped on
     *  close ({@link #onBufferClosed}) and when the feature is switched off. */
    private final Map<EditorBuffer, HttpClientPanel> panels = new IdentityHashMap<>();

    /** The in-flight run of each buffer (FX thread only). A result is applied only while its handle is still
     *  the buffer's current one, so a cancelled or superseded run can never overwrite a newer response. */
    private final Map<EditorBuffer, com.editora.http.HttpClientService.Handle> running = new IdentityHashMap<>();

    /** Each buffer's request-chaining session: every run (single or Run All) records into it, so a request
     *  can use the response of one run earlier. Cleared when the environment changes or the buffer closes. */
    private final Map<EditorBuffer, CapturedResponses> captures = new IdentityHashMap<>();

    /** The environment chosen for each buffer's file ({@code ""} = none), once known (FX thread only). */
    private final Map<EditorBuffer, String> environments = new IdentityHashMap<>();

    /** Reads saved histories off the FX thread; idles out. */
    private static final ExecutorService LOADER = loader();

    private static ExecutorService loader() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "http-history-load");
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    HttpClientCoordinator(CoordinatorHost host, WindowOps ops) {
        this.host = host;
        this.ops = ops;
        service.setRedirectGuard(this::beforeRedirectOverwrite);
    }

    /** How long a run waits for the previous content of a redirect target to become a durable revision. */
    private static final long REDIRECT_CAPTURE_TIMEOUT_SECONDS = 30;

    /**
     * {@link ResponseRedirects.Guard}: called on the request's worker thread before {@code >>!} replaces an
     * existing file. The decision is made on the FX thread (buffers and history live there) and awaited here;
     * if it cannot be had — the window is closing, the history store does not answer — the file is left alone.
     */
    private ResponseRedirects.Decision beforeRedirectOverwrite(Path target) {
        java.util.concurrent.CompletableFuture<ResponseRedirects.Decision> decision =
                new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            try {
                decideRedirectOverwrite(target, decision::complete);
            } catch (RuntimeException failure) {
                decision.complete(ResponseRedirects.Decision.of(ResponseRedirects.Verdict.REFUSED_NOT_PRESERVED));
            }
        });
        try {
            return decision.get(REDIRECT_CAPTURE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unanswered) {
            // fall through: not preserved
        }
        return ResponseRedirects.Decision.of(ResponseRedirects.Verdict.REFUSED_NOT_PRESERVED);
    }

    /** FX thread: refuses a target that is open with unsaved changes, else records it in Local History. */
    private void decideRedirectOverwrite(
            Path target, java.util.function.Consumer<ResponseRedirects.Decision> completion) {
        Path wanted = target.toAbsolutePath().normalize();
        boolean[] unsaved = new boolean[1];
        host.forEachBuffer(b -> {
            if (b.getPath() != null
                    && b.isDirty()
                    && b.getPath().toAbsolutePath().normalize().equals(wanted)) {
                unsaved[0] = true;
            }
        });
        if (unsaved[0]) {
            completion.accept(ResponseRedirects.Decision.of(ResponseRedirects.Verdict.REFUSED_UNSAVED_CHANGES));
            return;
        }
        HistoryCoordinator history = ops.history();
        if (history == null) {
            completion.accept(ResponseRedirects.Decision.of(ResponseRedirects.Verdict.NOT_KEPT));
            return;
        }
        history.captureBeforeOverwriteDurably(target, capture -> {
            ResponseRedirects.Verdict verdict = !capture.safe()
                    ? ResponseRedirects.Verdict.REFUSED_NOT_PRESERVED
                    : capture.recorded()
                            ? ResponseRedirects.Verdict.KEPT_IN_HISTORY
                            : ResponseRedirects.Verdict.NOT_KEPT;
            completion.accept(new ResponseRedirects.Decision(verdict, capture.expectedBytes()));
        });
    }

    /** Whether the HTTP Client is enabled (the setting, suppressed in Simple UI mode). */
    boolean isEnabled() {
        return host.settings().isHttpClientSupport() && !host.simpleModeActive();
    }

    /** Why the HTTP Client cannot run: Simple UI mode, or the setting (and where to find it). */
    private String disabledMessage() {
        return host.simpleModeActive() ? tr("status.http.simpleMode") : tr("statusbar.tip.httpDisabled");
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
            loadSaved(buffer, p);
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
            captures.remove(closed);
            environments.remove(closed);
            com.editora.http.HttpClientService.Handle run = running.remove(closed);
            if (run != null) {
                run.cancel(); // nobody is left to read the response
            }
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
        p.setOnEnvironmentChanged(env -> {
            environments.put(buffer, env);
            persist(buffer);
        });
        p.setOnCancel(() -> cancelRun(buffer));
        p.setOnRun(entry -> runAgain(buffer, entry));
        p.setOnEscape(() -> buffer.getArea().requestFocus());
        p.setOnCleared(() -> persist(buffer));
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
     * Reconciles the feature with its setting: gates the request ▶ glyphs on every buffer (local files only —
     * a remote file cannot run its requests from this machine), (de)attaches the in-editor preview, refreshes
     * each panel's font, and re-gates the run affordance. Runs at startup and on every settings apply.
     */
    void applySupport() {
        boolean on = isEnabled();
        host.forEachBuffer(b -> {
            b.setHttpEnabled(on && host.isLocalBuffer(b));
            ensureHttpPreview(b);
        });
        String family = host.settings().getFontFamily();
        int size = editorFontSize();
        panels.values().forEach(p -> p.setEditorFont(family, size));
        ops.updateRunGating();
    }

    /** Runs the request whose block contains {@code line} of {@code buffer} (run glyph, editor menu, command). */
    void runRequest(EditorBuffer buffer, int line) {
        if (!isEnabled()) {
            host.setStatus(disabledMessage());
            return;
        }
        if (buffer == null || buffer.getPath() == null) {
            host.setStatus(tr("status.http.saveFirst"));
            return;
        }
        int index = HttpFile.requestIndexAt(buffer.getContent(), line);
        if (index < 0) {
            host.setStatus(tr("status.http.noRequest"));
            return;
        }
        runIndex(buffer, index);
    }

    /** Runs request {@code index} (0-based, in file order) of {@code buffer}. */
    private void runIndex(EditorBuffer buffer, int index) {
        String text = buffer.getContent();
        List<HttpFile.Request> requests = HttpFile.parse(text);
        if (index < 0 || index >= requests.size()) {
            host.setStatus(tr("status.http.noRequest"));
            return;
        }
        HttpFile.Request req = requests.get(index);
        HttpFile.Parsed parsed = HttpFile.parseRequest(req);
        Map<String, String> vars = variables(buffer, text);
        if (!secretReadsAllowed(buffer, text, List.of(req), List.of(parsed), vars)) {
            return; // the folder is not trusted and the user declined: nothing is sent
        }
        String label = parsed.method() + " " + parsed.url();
        startRun(buffer, label);
        Path baseDir = buffer.getPath().toAbsolutePath().getParent();
        com.editora.http.HttpClientService.Handle[] run = new com.editora.http.HttpClientService.Handle[1];
        run[0] = service.run(parsed, vars, baseDir, capturesFor(buffer), ex -> {
            if (running.remove(buffer, run[0])) {
                finishRun(buffer, List.of(parsed), List.of(index), List.of(ex), false);
            }
        });
        track(buffer, run[0]);
    }

    /**
     * The response view's Run button: re-sends the request the selected history entry came from (when the
     * file still has a request at that position), else the request at the caret.
     */
    private void runAgain(EditorBuffer buffer, HttpHistoryEntry entry) {
        if (!isEnabled()) {
            host.setStatus(disabledMessage());
            return;
        }
        if (buffer.getPath() == null) {
            host.setStatus(tr("status.http.saveFirst"));
            return;
        }
        int index = entry == null ? -1 : entry.requestIndex();
        if (index >= 0 && index < HttpFile.parse(buffer.getContent()).size()) {
            runIndex(buffer, index);
        } else {
            runRequest(buffer, buffer.getArea().getCurrentParagraph());
        }
    }

    /** Makes {@code run} the buffer's current run, cancelling the one it replaces. */
    private void track(EditorBuffer buffer, com.editora.http.HttpClientService.Handle run) {
        com.editora.http.HttpClientService.Handle previous = running.put(buffer, run);
        if (previous != null) {
            previous.cancel();
        }
    }

    /** {@code http.cancelRequest}: cancel the active {@code .http} buffer's in-flight request(s). */
    void cancelActiveRequest() {
        ifHttp(() -> cancelRun(host.activeBuffer()));
    }

    /** Cancels {@code buffer}'s in-flight run (the Cancel button and the command share this). */
    void cancelRun(EditorBuffer buffer) {
        com.editora.http.HttpClientService.Handle run = buffer == null ? null : running.remove(buffer);
        if (run == null) {
            host.setStatus(tr("status.http.nothingRunning"));
            return;
        }
        run.cancel();
        HttpClientPanel p = panelFor(buffer);
        if (p != null) {
            p.cancelled();
        }
        host.setStatus(tr("status.http.cancelled"));
    }

    /** Whether {@code buffer} has a request in flight. Test accessor. */
    boolean isRunningForTest(EditorBuffer buffer) {
        return running.containsKey(buffer);
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
            List<HttpFile.Request> blocks = HttpFile.parse(text);
            List<HttpFile.Parsed> reqs =
                    blocks.stream().map(HttpFile::parseRequest).toList();
            if (reqs.isEmpty()) {
                host.setStatus(tr("status.http.noRequest"));
                return;
            }
            Map<String, String> vars = variables(b, text);
            if (!secretReadsAllowed(b, text, blocks, reqs, vars)) {
                return; // the folder is not trusted and the user declined: nothing is sent
            }
            String label = b.getPath().getFileName().toString();
            startRun(b, label);
            Path baseDir = b.getPath().toAbsolutePath().getParent();
            List<Integer> indexes = new ArrayList<>();
            for (int i = 0; i < reqs.size(); i++) {
                indexes.add(i);
            }
            com.editora.http.HttpClientService.Handle[] run = new com.editora.http.HttpClientService.Handle[1];
            run[0] = service.runAll(
                    reqs,
                    vars,
                    baseDir,
                    capturesFor(b),
                    i -> {
                        HttpClientPanel p = running.get(b) == run[0] ? panelFor(b) : null;
                        if (p != null && i < reqs.size()) {
                            HttpFile.Parsed r = reqs.get(i);
                            p.progress(i, reqs.size(), r.method() + " " + r.url());
                        }
                    },
                    exchanges -> {
                        if (running.remove(b, run[0])) {
                            finishRun(b, reqs, indexes, exchanges, true);
                        }
                    });
            track(b, run[0]);
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

    /**
     * {@code http.importCurl}: turn a clipboard {@code curl} command into a request block — appended to the
     * active {@code .http} file, else opened as a new {@code requests.http} tab (save it to run it).
     */
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
                host.setStatus(tr("status.http.curlImported"));
            } else {
                EditorBuffer nb = new EditorBuffer();
                nb.setDisplayName("requests.http");
                ops.openTab(nb);
                nb.setContent(request);
                host.setStatus(tr("status.http.curlImportedNew"));
            }
        });
    }

    /** {@code http.copyAsCurl}: copy the response viewer's selected request as a {@code curl} command. */
    void copyActiveAsCurl() {
        ifHttp(() -> {
            HttpClientPanel p = activePanel();
            HttpExchange ex = p == null ? null : p.getSelectedExchange();
            if (ex == null) {
                host.setStatus(tr("status.http.noResponseSelected"));
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
                host.setStatus(tr("status.http.noResponseSelected"));
                return;
            }
            openResponseInTab(ex);
        });
    }

    /**
     * Updates this buffer's environment picker from the env files that apply to it — the nearest folder, from
     * the file's own up to the project root, holding {@code http-client.env.json} or its private twin — with
     * the names of both files. Called when the preview is attached and from the focus/tab-switch pass.
     */
    void refreshEnvironments(EditorBuffer buffer) {
        HttpClientPanel p = panelFor(buffer);
        if (p != null && buffer.getPath() != null) {
            List<String> names = environmentNames(envDir(buffer));
            String chosen = environments.get(buffer);
            p.setEnvironments(names, chosen != null ? chosen : ops.savedEnvironment());
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

    /**
     * Lands a run's {@code exchanges} (for {@code requests} at {@code indexes}) in the response view and the
     * saved history, and says in the status bar what was sent (variables resolved) and how it went.
     */
    private void finishRun(
            EditorBuffer buffer,
            List<HttpFile.Parsed> requests,
            List<Integer> indexes,
            List<HttpExchange> exchanges,
            boolean all) {
        long now = System.currentTimeMillis();
        List<HttpHistoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < exchanges.size(); i++) {
            HttpFile.Parsed req = i < requests.size() ? requests.get(i) : null;
            entries.add(new HttpHistoryEntry(
                    exchanges.get(i),
                    req == null ? null : req.name(),
                    i < indexes.size() ? indexes.get(i) : -1,
                    now,
                    false,
                    false,
                    req != null && req.directives().noLog())); // # @no-log: shown now, never written to disk
        }
        HttpClientPanel p = panelFor(buffer);
        if (p != null) {
            p.showEntries(entries);
            persist(buffer);
        }
        if (!exchanges.isEmpty()) {
            host.setStatus(all ? HttpStatusText.finishedAll(exchanges) : HttpStatusText.finished(exchanges.get(0)));
        }
    }

    private void copyAsCurl(HttpExchange ex) {
        String curl = CurlExport.toCurl(ex); // from how it was sent: file upload, form fields, digest, -L
        ClipboardContent cc = new ClipboardContent();
        cc.putString(curl);
        Clipboard.getSystemClipboard().setContent(cc);
        host.setStatus(tr("status.http.curlCopied"));
    }

    private void openResponseInTab(HttpExchange ex) {
        HttpResult r = ex.result();
        if (r.failed()) {
            host.setStatus(tr("status.http.noResponseSelected"));
            return;
        }
        if (r.binary()) {
            host.setStatus(tr("status.http.binaryResponse")); // no text form — Save keeps the bytes
            return;
        }
        EditorBuffer buffer = new EditorBuffer();
        buffer.setDisplayName("response" + HttpResponseFormat.extensionFor(r.contentType()));
        ops.openTab(buffer);
        buffer.setContent(r.body()); // the body as received; the viewer's pretty-printing is display-only
    }

    /**
     * Save response: the body exactly as received (the default — the bytes, so an image or an archive is the
     * real file), or the whole response with its status line and headers as text.
     */
    private void saveResponse(EditorBuffer buffer) {
        HttpClientPanel p = panelFor(buffer);
        HttpExchange ex = p == null ? null : p.getSelectedExchange();
        if (ex == null || ex.result().failed()) {
            host.setStatus(tr("status.http.noResponseSelected"));
            return;
        }
        HttpResult r = ex.result();
        String ext = HttpResponseFormat.extensionFor(r.contentType());
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("dialog.saveResponse.title"));
        FileChooser.ExtensionFilter bodyOnly =
                new FileChooser.ExtensionFilter(tr("dialog.saveResponse.body"), "*" + (ext.isEmpty() ? ".txt" : ext));
        FileChooser.ExtensionFilter report =
                new FileChooser.ExtensionFilter(tr("dialog.saveResponse.withHeaders"), "*.txt");
        chooser.getExtensionFilters().setAll(bodyOnly, report);
        chooser.setSelectedExtensionFilter(bodyOnly);
        chooser.setInitialFileName(saveName(ex.url(), ext));
        Path dir = buffer.getPath() == null
                ? null
                : buffer.getPath().toAbsolutePath().getParent();
        if (dir != null && Files.isDirectory(dir)) {
            chooser.setInitialDirectory(dir.toFile());
        }
        File chosen = chooser.showSaveDialog(host.window());
        if (chosen == null) {
            return;
        }
        try {
            // Staged: the Save dialog may have confirmed replacing a file, which a failed write must not empty.
            if (chooser.getSelectedExtensionFilter() == report) {
                com.editora.io.StagedExport.writeString(chosen.toPath(), HttpResponseFormat.render(r));
            } else {
                byte[] bytes = r.rawBody() != null && r.rawBody().length > 0
                        ? r.rawBody()
                        : r.body().getBytes(StandardCharsets.UTF_8);
                com.editora.io.StagedExport.write(chosen.toPath(), bytes);
            }
            host.setStatus(tr("status.http.saved", chosen.toPath().getFileName()));
        } catch (IOException e) {
            host.setStatus(tr("status.http.saveFailed", e.getMessage()));
        }
    }

    /**
     * The file name Save offers: the URL's last path segment when it already names a file ({@code logo.png}),
     * else {@code response} plus the extension of the content type ({@code response.json}). Pure.
     */
    static String saveName(String url, String ext) {
        String u = url == null ? "" : url;
        int cut = u.length();
        for (char c : new char[] {'?', '#'}) {
            int at = u.indexOf(c);
            if (at >= 0) {
                cut = Math.min(cut, at);
            }
        }
        String path = u.substring(0, cut);
        int scheme = path.indexOf("://");
        int slash = path.lastIndexOf('/');
        String last = slash < 0 || slash <= scheme + 2 ? "" : path.substring(slash + 1);
        if (last.matches("[\\w-]+(\\.[\\w-]+)*\\.[A-Za-z0-9]{1,8}")) {
            return last;
        }
        return "response" + (ext == null || ext.isEmpty() ? ".txt" : ext);
    }

    /**
     * The environment variables for a {@code .http} buffer: the selected environment of both env files, merged
     * as JetBrains does (public {@code $shared} < private {@code $shared} < public env < private env). They are
     * not resolved here: the run resolves them, with the {@code @var} lines above each request, the file's
     * folder for {@code $dotenv}, and the buffer's captured responses — and reports what does not resolve.
     */
    private Map<String, String> variables(EditorBuffer buffer, String text) {
        // This buffer's own picker — each .http file has its own panel, so a run always resolves {{vars}}
        // against the environment selected for the file being run.
        HttpClientPanel p = panelFor(buffer);
        String env = p == null ? "" : p.getSelectedEnvironment();
        Path envDir = envDir(buffer); // the file's own folder, or the nearest one above it with env files
        return HttpEnv.merged(
                readOrNull(envDir == null ? null : envDir.resolve(HttpEnv.PUBLIC_FILE)),
                readOrNull(envDir == null ? null : envDir.resolve(HttpEnv.PRIVATE_FILE)),
                env);
    }

    /** The buffer's chaining session, cleared first when the selected environment changed since its last run. */
    private CapturedResponses capturesFor(EditorBuffer buffer) {
        CapturedResponses session = captures.computeIfAbsent(buffer, b -> new CapturedResponses());
        HttpClientPanel p = panelFor(buffer);
        session.resetUnlessEnvironment(p == null ? "" : p.getSelectedEnvironment());
        return session;
    }

    private static String readOrNull(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return null;
        }
    }

    /** The folder whose env files apply to {@code buffer}'s file (its own, or the nearest one above it). */
    private Path envDir(EditorBuffer buffer) {
        Path file = buffer.getPath();
        Path dir = file == null ? null : file.toAbsolutePath().getParent();
        return dir == null ? null : HttpEnvFiles.envDir(dir, ops.projectRoot());
    }

    /** The environment names declared in {@code dir}'s env files, public first, then private-only ones. */
    private static List<String> environmentNames(Path dir) {
        if (dir == null) {
            return List.of();
        }
        return HttpEnv.environmentNames(
                readOrNull(dir.resolve(HttpEnv.PUBLIC_FILE)), readOrNull(dir.resolve(HttpEnv.PRIVATE_FILE)));
    }

    // --- secrets trust gate --------------------------------------------------------------------------

    /**
     * The trust gate for secrets: a request that reads {@code $processEnv} / {@code $dotenv} — directly, through a
     * {@code @var} line or an environment value — runs only from a trusted folder, or once the user trusts it.
     * A {@code .http} file from a cloned repository could otherwise send {@code AWS_SECRET_ACCESS_KEY} to any host
     * on one click. Requests that read no secrets are never asked about.
     */
    private boolean secretReadsAllowed(
            EditorBuffer buffer,
            String text,
            List<HttpFile.Request> blocks,
            List<HttpFile.Parsed> requests,
            Map<String, String> vars) {
        Path dir = buffer.getPath().toAbsolutePath().getParent();
        StringBuilder sent = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            sent.append(blocks.get(i).block()).append('\n');
            HttpFile.BodyRef ref = requests.get(i).bodyRef();
            if (ref != null && ref.substitute()) {
                sent.append(substitutedBodyFile(dir, ref)).append('\n'); // "<@ file" fills in its {{vars}} too
            }
        }
        List<String> reads = com.editora.http.SecretReads.find(sent.toString(), rawDefinitions(text, envDir(buffer)));
        if (reads.isEmpty()) {
            return true;
        }
        java.util.Set<String> hosts = new java.util.LinkedHashSet<>();
        for (HttpFile.Parsed p : requests) {
            hosts.add(hostOf(HttpVars.substitute(p.url(), vars, LocalDateTime.now())));
        }
        if (ops.allowSecretReads(buffer.getPath(), List.copyOf(hosts), reads)) {
            return true;
        }
        host.setStatus(tr("status.http.secretsRefused", String.join(", ", reads)));
        return false;
    }

    /** What {@code {{name}}} can stand for, unsubstituted: the file's {@code @var} lines and the environment. */
    private Map<String, List<String>> rawDefinitions(String text, Path dir) {
        Map<String, List<String>> defs = new LinkedHashMap<>();
        for (String[] pair : HttpFile.fileVariablePairs(text)) {
            defs.computeIfAbsent(pair[0], k -> new java.util.ArrayList<>()).add(pair[1]);
        }
        rawEnvironment(dir)
                .forEach((k, v) -> defs.computeIfAbsent(k, x -> new java.util.ArrayList<>())
                        .add(v));
        return defs;
    }

    /** Every value of every environment in the folder's env files (any could be the one picked next). */
    private Map<String, String> rawEnvironment(Path dir) {
        Map<String, String> out = new LinkedHashMap<>();
        if (dir == null) {
            return out;
        }
        for (String name : List.of("http-client.env.json", "http-client.private.env.json")) {
            Path file = dir.resolve(name);
            if (!Files.exists(file)) {
                continue;
            }
            try {
                String json = Files.readString(file);
                for (String env : HttpEnv.environmentNames(json)) {
                    HttpEnv.variables(json, env).forEach((k, v) -> out.merge(k, v, (a, b) -> a + "\n" + b));
                }
            } catch (IOException | RuntimeException unreadable) {
                // an unreadable env file contributes nothing to resolve either
            }
        }
        return out;
    }

    private static String substitutedBodyFile(Path dir, HttpFile.BodyRef ref) {
        try {
            Path file = com.editora.http.HttpPaths.contained(dir, ref.path());
            // {{ and }} are ASCII in any encoding a body file may declare, so Latin-1 never fails to decode
            return file == null || !Files.isRegularFile(file)
                    ? ""
                    : new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1);
        } catch (IOException | RuntimeException unreadable) {
            return ""; // the run reports an unreadable body file itself
        }
    }

    private static String hostOf(String url) {
        try {
            java.net.URI u = java.net.URI.create(url.strip());
            return u.getHost() == null ? url.strip() : u.getHost() + (u.getPort() >= 0 ? ":" + u.getPort() : "");
        } catch (RuntimeException e) {
            return url.strip();
        }
    }

    // --- saved history ---------------------------------------------------------------------------------

    /** Reads {@code buffer}'s saved environment + responses off the FX thread, then puts them in its panel. */
    private void loadSaved(EditorBuffer buffer, HttpClientPanel panel) {
        HttpResponseHistory store = ops.historyStore();
        Path file = buffer.getPath();
        if (store == null || file == null) {
            return;
        }
        LOADER.execute(() -> {
            HttpResponseHistory.Stored stored;
            try {
                stored = store.load(file);
            } catch (RuntimeException e) {
                return; // an unreadable history is the same as none
            }
            List<HttpHistoryEntry> entries = new ArrayList<>();
            for (HttpResponseHistory.Response r : stored.responses) {
                try {
                    entries.add(HttpHistoryEntry.fromStored(r));
                } catch (RuntimeException ignored) {
                    // skip a damaged entry
                }
            }
            String env = stored.environment;
            javafx.application.Platform.runLater(() -> {
                if (panels.get(buffer) != panel) {
                    return; // closed, or the feature was switched off, while reading
                }
                if (!stored.file.isEmpty()) {
                    environments.putIfAbsent(buffer, env); // a file never saved keeps the window's old choice
                }
                refreshEnvironments(buffer);
                panel.restore(entries);
            });
        });
    }

    /** Saves {@code buffer}'s environment choice and its last responses (never {@code @no-log} ones). */
    private void persist(EditorBuffer buffer) {
        HttpResponseHistory store = ops.historyStore();
        HttpClientPanel p = panelFor(buffer);
        Path file = buffer.getPath();
        if (store == null || p == null || file == null) {
            return;
        }
        HttpResponseHistory.Stored state = new HttpResponseHistory.Stored();
        String env = environments.get(buffer);
        state.environment = env == null ? p.getSelectedEnvironment() : env;
        for (HttpHistoryEntry e : p.history()) {
            if (state.responses.size() >= HttpResponseHistory.MAX_RESPONSES) {
                break;
            }
            if (!e.noLog()) {
                state.responses.add(e.toStored());
            }
        }
        store.save(file, state);
    }

    private void ifHttp(Runnable action) {
        if (isEnabled()) {
            action.run();
        } else {
            host.setStatus(disabledMessage());
        }
    }

    /** Stops the HTTP-client worker thread (window close). */
    public void shutdown() {
        service.shutdown();
    }
}
