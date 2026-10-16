package com.editora.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.FileChooser;

import com.editora.config.HttpResponseHistory;
import com.editora.editor.EditorBuffer;
import com.editora.http.CapturedResponses;
import com.editora.http.CurlExport;
import com.editora.http.CurlImport;
import com.editora.http.HttpClientService;
import com.editora.http.HttpExchange;
import com.editora.http.HttpFile;
import com.editora.http.HttpResponseFormat;
import com.editora.http.HttpResult;
import com.editora.http.ResponseRedirects;
import com.editora.io.StagedExport;

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
         * Whether a request in {@code file} may read the secrets {@code reads} — the secret sources it resolves, as
         * written ({@code $processEnv X}, {@code $env.X}, {@code $dotenv X}; see {@link HttpSecretGate}) — and send
         * them to {@code hosts}: true when the file's folder is trusted, or the user trusts it now (the build-wrapper
         * trust store, so one answer covers both). Called on the FX thread, while the run's worker waits; it may
         * block on a dialog. A window without a trust store refuses, so nothing secret is sent unasked.
         */
        default boolean allowSecretReads(Path file, List<String> hosts, List<String> reads) {
            return false;
        }
    }

    private final CoordinatorHost host;
    private final WindowOps ops;
    private final HttpClientService service = new HttpClientService();

    /** One response panel per open {@code .http} buffer (a Node can't be shared across tabs); dropped on
     *  close ({@link #onBufferClosed}) and when the feature is switched off. */
    private final Map<EditorBuffer, HttpClientPanel> panels = new IdentityHashMap<>();

    /** The in-flight run of each buffer (FX thread only). A result is applied only while its handle is still
     *  the buffer's current one, so a cancelled or superseded run can never overwrite a newer response. */
    private final Map<EditorBuffer, HttpClientService.Handle> running = new IdentityHashMap<>();

    /** The run of each buffer the user cancelled and whose results have not arrived yet: what it finished before
     *  the cancel (a Run All's earlier requests) still lands in History. Dropped when a newer run starts. */
    private final Map<EditorBuffer, HttpClientService.Handle> cancelling = new IdentityHashMap<>();

    /** Each buffer's request-chaining session: every run (single or Run All) records into it, so a request
     *  can use the response of one run earlier. Cleared when the environment changes or the buffer closes. */
    private final Map<EditorBuffer, CapturedResponses> captures = new IdentityHashMap<>();

    /** The environment chosen for each buffer's file ({@code ""} = none), once known (FX thread only). */
    private final Map<EditorBuffer, String> environments = new IdentityHashMap<>();

    /** Each panel's saved history: read, saved, merged across windows, and moved with its file. */
    private final HttpHistorySync history;

    HttpClientCoordinator(CoordinatorHost host, WindowOps ops) {
        this.host = host;
        this.ops = ops;
        this.history =
                new HttpHistorySync(host, ops::historyStore, this::panelFor, environments, this::refreshEnvironments);
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
        CompletableFuture<ResponseRedirects.Decision> decision = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                decideRedirectOverwrite(target, decision::complete);
            } catch (RuntimeException failure) {
                decision.complete(ResponseRedirects.Decision.of(ResponseRedirects.Verdict.REFUSED_NOT_PRESERVED));
            }
        });
        try {
            return decision.get(REDIRECT_CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException unanswered) {
            // fall through: not preserved
        }
        return ResponseRedirects.Decision.of(ResponseRedirects.Verdict.REFUSED_NOT_PRESERVED);
    }

    /** FX thread: refuses a target that is open with unsaved changes, else records it in Local History. */
    private void decideRedirectOverwrite(Path target, Consumer<ResponseRedirects.Decision> completion) {
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
            history.attached(buffer, p);
        } else if (!want && has) {
            buffer.setHttpPreviewNode(null); // resets the buffer to EDITOR
            HttpClientPanel gone = panels.remove(buffer);
            history.detached(buffer);
            HttpClientService.Handle run = running.remove(buffer);
            if (run != null) {
                run.cancel(); // the feature went off mid-run: nothing is left to show the response
                buffer.setHttpRunning(-1);
            }
            if (gone != null) {
                gone.dispose(); // stops its elapsed-time timer, which otherwise kept the panel alive
            }
        } else if (want) {
            history.followPath(buffer); // a first Save of an untitled buffer, a Save As, a rename: history follows
        }
    }

    /** Re-target on tab switch: (re)evaluate the active buffer's preview attachment + environment list. */
    void refreshFor(EditorBuffer active) {
        if (active != null) {
            ensureHttpPreview(active);
        }
    }

    /**
     * {@code from} (a file or a folder) was renamed or moved to {@code to} — the Project tree, another window, a
     * refactoring: the saved histories under it move along, and each open buffer now under {@code to} re-points its
     * panel's history (call after the buffers' paths changed).
     */
    void onFileMoved(Path from, Path to) {
        history.moved(from, to);
        host.forEachBuffer(b -> {
            if (b.getPath() != null
                    && b.getPath()
                            .toAbsolutePath()
                            .normalize()
                            .startsWith(to.toAbsolutePath().normalize())) {
                ensureHttpPreview(b);
            }
        });
    }

    /** Drops the closed buffer's panel (the map is strongly keyed, so this hook is required). */
    void onBufferClosed(EditorBuffer closed) {
        if (closed != null) {
            HttpClientPanel gone = panels.remove(closed);
            if (gone != null) {
                gone.dispose(); // a run in flight left its timer going for ever, pinning the panel and the buffer
            }
            history.closed(closed);
            captures.remove(closed);
            environments.remove(closed);
            cancelling.remove(closed); // a cancelled run's late results have no panel to land in
            HttpClientService.Handle run = running.remove(closed);
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
            history.persist(buffer);
            environmentListener.accept(buffer); // the editor's {{variable}} marks follow the choice
        });
        p.setOnCancel(() -> cancelRun(buffer));
        p.setOnRun(entry -> runAgain(buffer, entry));
        p.setOnEscape(() -> {
            // In Preview the editor is hidden, so focusing it did nothing: show it beside the response first.
            if (buffer.getMarkdownViewMode() == EditorBuffer.MarkdownViewMode.PREVIEW) {
                buffer.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.SPLIT);
            }
            focusWhenShown(buffer.getArea());
        });
        p.setOnCleared(() -> history.cleared(buffer));
        return p;
    }

    /**
     * Focuses {@code node} now, or as soon as it is back in a scene: a Split's SplitPane adds the editor to the
     * scene only when its skin lays it out, so right after leaving Preview a focus request would go nowhere.
     */
    private static void focusWhenShown(Node node) {
        if (node.getScene() != null) {
            node.requestFocus();
            return;
        }
        node.sceneProperty().addListener(new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends Scene> o, Scene was, Scene now) {
                if (now != null) {
                    o.removeListener(this);
                    node.requestFocus();
                }
            }
        });
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
        if (buffer == null) {
            host.setStatus(tr("status.http.noRequest"));
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
        Path baseDir = folderOf(buffer); // null: an untitled scratch buffer
        CapturedResponses session = capturesFor(buffer);
        if (baseDir == null) {
            List<String> needs = HttpScratch.needsFolder(parsed, vars);
            if (!needs.isEmpty()) {
                HttpExchange unsent = notSent(parsed, needs);
                finishRun(
                        buffer,
                        List.of(parsed),
                        List.of(index),
                        List.of(unsent),
                        new long[] {System.currentTimeMillis()},
                        identitySet(List.of(unsent)),
                        false);
                return; // a body file, >> or $dotenv has no folder to resolve against
            }
        }
        String label = HttpScratch.label(parsed, vars, session, baseDir, ZonedDateTime.now());
        startRun(buffer, label);
        buffer.setHttpRunning(index);
        long startedAt = System.currentTimeMillis();
        HttpClientService.Handle[] run = new HttpClientService.Handle[1];
        // A request that reads $processEnv/$env/$dotenv asks first (off the FX thread, from what it resolves).
        run[0] = service.run(parsed, vars, baseDir, session, HttpSecretGate.forBuffer(buffer, ops), ex -> {
            if (running.remove(buffer, run[0])) {
                finishRun(
                        buffer, List.of(parsed), List.of(index), List.of(ex), new long[] {startedAt}, Set.of(), false);
            } else if (cancelling.remove(buffer, run[0])) {
                landCancelled(
                        buffer, List.of(parsed), List.of(index), List.of(ex), new long[] {startedAt}, Set.of(), 1);
            }
        });
        track(buffer, run[0]);
    }

    /** The folder a buffer's relative paths resolve against: its file's, or null for an untitled buffer. */
    private static Path folderOf(EditorBuffer buffer) {
        Path file = buffer == null ? null : buffer.getPath();
        return file == null ? null : file.toAbsolutePath().getParent();
    }

    /** An identity set of {@code exchanges} (records compare by value; two "not sent" results may be equal). */
    private static Set<HttpExchange> identitySet(Collection<HttpExchange> exchanges) {
        Set<HttpExchange> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(exchanges);
        return set;
    }

    /** What a request an untitled buffer cannot send reports instead: not sent, save the file first. */
    private static HttpExchange notSent(HttpFile.Parsed parsed, List<String> needs) {
        String line = parsed.method() + " " + parsed.url();
        return new HttpExchange(
                line,
                parsed.method(),
                parsed.url(),
                parsed.headers(),
                "",
                HttpResult.failure(tr("httppanel.saveFirstToUse", String.join(", ", needs)), List.of()),
                null);
    }

    /**
     * The response view's Run button: re-sends the request the selected history entry came from — found by its
     * name, else by its method and URL template ({@link HttpRerunTarget}), never by its old position alone,
     * since requests move — else, saying so, the request at the caret. With nothing selected: the caret's.
     */
    private void runAgain(EditorBuffer buffer, HttpHistoryEntry entry) {
        if (!isEnabled()) {
            host.setStatus(disabledMessage());
            return;
        }
        if (entry == null) {
            runRequest(buffer, buffer.getArea().getCurrentParagraph());
            return;
        }
        HttpExchange ex = entry.exchange();
        int index = HttpRerunTarget.find(HttpFile.parse(buffer.getContent()), entry);
        if (index >= 0) {
            runIndex(buffer, index);
            return;
        }
        int caret = buffer.getArea().getCurrentParagraph();
        if (HttpFile.requestIndexAt(buffer.getContent(), caret) < 0) {
            host.setStatus(tr("status.http.rerunGone"));
            return;
        }
        runRequest(buffer, caret);
        host.setStatus(tr("status.http.rerunAtCaret", ex.method() + " " + ex.url()));
    }

    /** Makes {@code run} the buffer's current run, cancelling the one it replaces. */
    private void track(EditorBuffer buffer, HttpClientService.Handle run) {
        HttpClientService.Handle previous = running.put(buffer, run);
        if (previous != null) {
            previous.cancel();
        }
        cancelling.remove(buffer); // superseded: a cancelled run's late results must not land over this one
    }

    /** {@code http.cancelRequest}: cancel the active {@code .http} buffer's in-flight request(s). */
    void cancelActiveRequest() {
        ifHttp(() -> cancelRun(host.activeBuffer()));
    }

    /** Cancels {@code buffer}'s in-flight run (the Cancel button and the command share this). */
    void cancelRun(EditorBuffer buffer) {
        HttpClientService.Handle run = buffer == null ? null : running.remove(buffer);
        if (run == null) {
            host.setStatus(tr("status.http.nothingRunning"));
            return;
        }
        run.cancel();
        cancelling.put(buffer, run); // what it finished before the cancel still lands (landCancelled)
        buffer.setHttpRunning(-1);
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

    /** {@code buffer}'s in-flight run, or null. Test accessor. */
    HttpClientService.Handle handleForTest(EditorBuffer buffer) {
        return running.get(buffer);
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
            String text = b.getContent();
            List<HttpFile.Request> blocks = HttpFile.parse(text);
            List<HttpFile.Parsed> reqs =
                    blocks.stream().map(HttpFile::parseRequest).toList();
            if (reqs.isEmpty()) {
                host.setStatus(tr("status.http.noRequest"));
                return;
            }
            Map<String, String> vars = variables(b, text);
            String label = b.getPath() == null
                    ? b.getDisplayName()
                    : b.getPath().getFileName().toString();
            Path baseDir = folderOf(b); // null: an untitled scratch buffer
            CapturedResponses session = capturesFor(b);
            // An untitled buffer sends what it can; a request needing the file's folder reports "save first".
            Map<Integer, HttpExchange> skipped = new LinkedHashMap<>();
            List<Integer> sent = new ArrayList<>();
            for (int i = 0; i < reqs.size(); i++) {
                List<String> needs = baseDir == null ? HttpScratch.needsFolder(reqs.get(i), vars) : List.of();
                if (needs.isEmpty()) {
                    sent.add(i);
                } else {
                    skipped.put(i, notSent(reqs.get(i), needs));
                }
            }
            List<Integer> indexes = new ArrayList<>();
            for (int i = 0; i < reqs.size(); i++) {
                indexes.add(i);
            }
            // When each request started (by file position), for its History time; the ones not sent: now.
            long[] startedAt = new long[reqs.size()];
            Arrays.fill(startedAt, System.currentTimeMillis());
            Set<HttpExchange> unsent = identitySet(skipped.values());
            if (sent.isEmpty()) {
                finishRun(b, reqs, indexes, new ArrayList<>(skipped.values()), startedAt, unsent, true);
                return;
            }
            startRun(b, label);
            String[] startText = text.split("\n", -1); // each request's line as the run saw it, for the ■
            IntFunction<String> requestLine = i -> {
                int line = blocks.get(i).startLine();
                return line >= 0 && line < startText.length ? startText[line] : null;
            };
            b.setHttpRunning(sent.get(0), requestLine.apply(sent.get(0)));
            List<HttpFile.Parsed> toSend = sent.stream().map(reqs::get).toList();
            HttpClientService.Handle[] run = new HttpClientService.Handle[1];
            run[0] = service.runAll(
                    toSend,
                    vars,
                    baseDir,
                    session,
                    HttpSecretGate.forBuffer(b, ops), // asked once, before the first request, if any reads a secret
                    i -> {
                        HttpClientPanel p = running.get(b) == run[0] ? panelFor(b) : null;
                        if (p != null && i < toSend.size()) {
                            int index = sent.get(i);
                            startedAt[index] = System.currentTimeMillis();
                            b.setHttpRunning(index, requestLine.apply(index)); // the ■ follows the request in flight
                            p.progress(
                                    index,
                                    reqs.size(),
                                    HttpScratch.label(reqs.get(index), vars, session, baseDir, ZonedDateTime.now()));
                        }
                    },
                    exchanges -> {
                        if (running.remove(b, run[0])) {
                            finishRun(
                                    b,
                                    reqs,
                                    indexes,
                                    merged(reqs.size(), sent, exchanges, skipped),
                                    startedAt,
                                    unsent,
                                    true);
                        } else if (cancelling.remove(b, run[0])) {
                            landCancelled(
                                    b,
                                    reqs,
                                    indexes,
                                    merged(reqs.size(), sent, exchanges, skipped),
                                    startedAt,
                                    unsent,
                                    reqs.size());
                        }
                    });
            track(b, run[0]);
        });
    }

    /**
     * A Run All's results in file order: the exchanges of the requests that were sent ({@code sent} holds their
     * indexes, in order) and the "save first" results of the ones that were not. A cancelled run stops at the
     * last request it reached. Pure.
     */
    static List<HttpExchange> merged(
            int total, List<Integer> sent, List<HttpExchange> exchanges, Map<Integer, HttpExchange> skipped) {
        List<HttpExchange> out = new ArrayList<>();
        int next = 0;
        for (int i = 0; i < total; i++) {
            HttpExchange notSent = skipped.get(i);
            if (notSent != null) {
                out.add(notSent);
            } else if (next < exchanges.size() && next < sent.size() && sent.get(next) == i) {
                out.add(exchanges.get(next++));
            } else {
                break; // cancelled before this request was reached
            }
        }
        return out;
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
     * active {@code .http} file, else opened as a new {@code requests.http} tab, which runs like a scratch file.
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
        if (p != null) {
            List<String> names = HttpEnvironments.read(envDir(buffer)).names();
            String chosen = environments.get(buffer);
            p.setEnvironments(names, chosen != null ? chosen : ops.savedEnvironment());
            environmentListener.accept(buffer); // the list (or the env files) may have changed
        }
    }

    /** Told when a buffer's environment choice, or the env files it comes from, may have changed. */
    private Consumer<EditorBuffer> environmentListener = b -> {};

    /** Sets who is told about environment changes (the editor's {@code {{variable}}} hints). */
    void setEnvironmentListener(Consumer<EditorBuffer> listener) {
        this.environmentListener = listener == null ? b -> {} : listener;
    }

    /** The environment selected for {@code buffer}'s file ({@code ""}: none), as a run would use it. */
    String selectedEnvironment(EditorBuffer buffer) {
        HttpClientPanel p = panelFor(buffer);
        return p == null ? "" : p.getSelectedEnvironment();
    }

    /** The folder whose env files apply to {@code buffer} (see {@link HttpEnvironments}), or null. */
    Path envDirFor(EditorBuffer buffer) {
        return buffer == null ? null : envDir(buffer);
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
            long[] startedAt,
            Set<HttpExchange> unsent,
            boolean all) {
        buffer.setHttpRunning(-1); // the ■ turns back into ▶
        long now = System.currentTimeMillis();
        List<HttpHistoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < exchanges.size(); i++) {
            HttpFile.Parsed req = i < requests.size() ? requests.get(i) : null;
            HttpExchange ex = exchanges.get(i);
            entries.add(HttpHistoryEntry.ran(
                    ex,
                    req,
                    i < indexes.size() ? indexes.get(i) : -1,
                    startedAt != null && i < startedAt.length ? startedAt[i] : now,
                    // # @no-log, and an untitled buffer's "not sent — save first": shown now, never written to disk
                    (req != null && req.directives().noLog()) || unsent.contains(ex)));
        }
        HttpClientPanel p = panelFor(buffer);
        if (p != null) {
            p.showEntries(entries);
            p.showRunSummary(all ? HttpStatusText.runAllSummary(exchanges) : null);
            history.persist(buffer);
        }
        if (!exchanges.isEmpty()) {
            host.setStatus(all ? HttpStatusText.finishedAll(exchanges) : HttpStatusText.finished(exchanges.get(0)));
        }
    }

    /**
     * The results of a run the user cancelled: the requests that finished before the cancel (a Run All's first
     * ones, whose effects on the server are real) go into History like any others; the cancelled one does not.
     * The view stays as the cancel left it when nothing had finished.
     */
    private void landCancelled(
            EditorBuffer buffer,
            List<HttpFile.Parsed> requests,
            List<Integer> indexes,
            List<HttpExchange> exchanges,
            long[] startedAt,
            Set<HttpExchange> unsent,
            int total) {
        List<HttpFile.Parsed> keptRequests = new ArrayList<>();
        List<Integer> keptIndexes = new ArrayList<>();
        List<HttpExchange> kept = new ArrayList<>();
        List<Long> keptStarts = new ArrayList<>();
        for (int i = 0; i < exchanges.size(); i++) {
            HttpExchange ex = exchanges.get(i);
            if (ex.result().failed()
                    && HttpClientService.CANCELLED.equals(ex.result().error())) {
                continue;
            }
            keptRequests.add(i < requests.size() ? requests.get(i) : null);
            keptIndexes.add(i < indexes.size() ? indexes.get(i) : -1);
            kept.add(ex);
            keptStarts.add(startedAt != null && i < startedAt.length ? startedAt[i] : 0L);
        }
        if (kept.isEmpty()) {
            return;
        }
        long[] starts = keptStarts.stream().mapToLong(Long::longValue).toArray();
        finishRun(buffer, keptRequests, keptIndexes, kept, starts, unsent, total > 1);
        // One request whose answer was in before the cancel took effect: "1 of 1 requests had finished" read oddly.
        host.setStatus(
                total > 1 ? tr("status.http.cancelledAfter", kept.size(), total) : tr("status.http.cancelledTooLate"));
    }

    private void copyAsCurl(HttpExchange ex) {
        HttpExchange.Details how = ex.details();
        if (HttpExchange.isBodyPlaceholder(ex.requestBody())
                && how.bodyFile() == null
                && how.formParts().isEmpty()) {
            // Saved by an older version, which kept only the body's summary: a command would upload that text.
            host.setStatus(tr("status.http.curlUnavailable"));
            return;
        }
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
        Path dir = folderOf(buffer) != null ? folderOf(buffer) : ops.projectRoot(); // untitled: the project
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
                StagedExport.writeString(chosen.toPath(), HttpResponseFormat.render(r));
            } else {
                byte[] bytes = r.rawBody() != null && r.rawBody().length > 0
                        ? r.rawBody()
                        : r.body().getBytes(StandardCharsets.UTF_8);
                StagedExport.write(chosen.toPath(), bytes);
            }
            HttpHistoryEntry entry = p.getSelectedEntry();
            boolean partial = entry != null && entry.bodyCut();
            host.setStatus(tr(
                    partial ? "status.http.savedPartial" : "status.http.saved",
                    chosen.toPath().getFileName()));
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
        return HttpEnvironments.read(envDir(buffer)).variables(env);
    }

    /** The buffer's chaining session, cleared first when the selected environment changed since its last run. */
    private CapturedResponses capturesFor(EditorBuffer buffer) {
        CapturedResponses session = captures.computeIfAbsent(buffer, b -> new CapturedResponses());
        HttpClientPanel p = panelFor(buffer);
        session.resetUnlessEnvironment(p == null ? "" : p.getSelectedEnvironment());
        return session;
    }

    /** The folder whose env files apply to {@code buffer}'s file (see {@link HttpEnvironments}), or null. */
    private Path envDir(EditorBuffer buffer) {
        return HttpEnvironments.forFile(buffer == null ? null : buffer.getPath(), ops.projectRoot());
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
