package com.editora.ui;

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
import java.util.function.Consumer;
import java.util.function.IntFunction;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;

import com.editora.config.HttpResponseHistory;
import com.editora.editor.EditorBuffer;
import com.editora.http.CapturedResponses;
import com.editora.http.CurlImport;
import com.editora.http.HttpClientService;
import com.editora.http.HttpExchange;
import com.editora.http.HttpFile;
import com.editora.http.HttpResult;
import com.editora.http.RunEnvironment;
import org.jspecify.annotations.Nullable;

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
        default @Nullable HttpResponseHistory historyStore() {
            return null;
        }

        /**
         * The window's project root, which bounds the search for env files (see {@link HttpEnvironments}); null when
         * there is no project.
         */
        default @Nullable Path projectRoot() {
            return null;
        }

        /**
         * Local File History, which records a file's content before a {@code >>!} redirect replaces it; null
         * when the window has none (the replace then proceeds unrecorded, and the response view says so).
         */
        default @Nullable HistoryCoordinator history() {
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

        /**
         * Whether the JavaScript response handlers of {@code file} may run: true when its folder is trusted, or the
         * user trusts it now (the same trust store as build scripts and secrets). Called on the FX thread, while the
         * run's worker waits; it may block on a dialog. A window without a trust store refuses.
         */
        default boolean allowScripts(Path file) {
            return false;
        }

        /** Whether {@code file} — a response handler file, by its real path — is in a trusted folder (FX thread). */
        default boolean isTrusted(Path file) {
            return false;
        }

        /** Opens {@code file} in an editor tab (an env file, from the environment menu). */
        default void openFile(Path file) {}

        /** The folder Editora copies its response-handler runner to; null when handlers cannot run. */
        default @Nullable Path scriptCacheDir() {
            return null;
        }

        /**
         * The window's JavaScript debugger for Debug Request (it attaches to a script waiting on an inspector port),
         * or null when JavaScript debugging is not available here — not enabled, or js-debug not installed.
         */
        default com.editora.http.NodeResponseScripts.@Nullable Debugger scriptDebugger() {
            return null;
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

    /**
     * The environment chosen for each buffer's file ({@code ""} = none), once known (FX thread only): picked in the
     * pill or the status bar, or remembered in the file's saved history. The source of truth for every run, the
     * {@code {{variable}}} marks and the saved history; validated against {@link #environmentNames} when read.
     */
    private final Map<EditorBuffer, String> environments = new IdentityHashMap<>();

    /** The environment names each buffer's env files define, as last read (FX thread only). */
    private final Map<EditorBuffer, List<String>> environmentNames = new IdentityHashMap<>();

    /** Each {@code .http} buffer's environment pill, beside its Editor/Split/Preview toggle; dropped with its panel. */
    private final Map<EditorBuffer, HttpEnvPill> pills = new IdentityHashMap<>();

    /** The environment menu now open (from a pill, the status bar or the command), or null. */
    private @Nullable ContextMenu environmentMenu;

    /** Told when the active buffer's environment, or whether it has one, may have changed (the status bar). */
    private Runnable environmentShown = () -> {};

    /** Each panel's saved history: read, saved, merged across windows, and moved with its file. */
    private final HttpHistorySync history;

    /** Copy as cURL, Open in tab and Save for a response the panel shows. */
    private final HttpResponseActions actions;

    /** Each file's env files as last read, so the FX thread never reads them (a run reads them on its worker). */
    private final HttpEnvCache envCache = new HttpEnvCache();

    HttpClientCoordinator(CoordinatorHost host, WindowOps ops) {
        this.host = host;
        this.ops = ops;
        this.history = new HttpHistorySync(
                host,
                ops::historyStore,
                this::panelFor,
                this::persistedEnvironment,
                environments::putIfAbsent, // a remembered environment never replaces this file's own choice
                this::refreshEnvironments);
        history.setOnRestored(this::refreshInlineResults); // a restored history shows on the request lines too
        this.actions = new HttpResponseActions(host, ops);
        service.setRedirectGuard(new HttpRedirectGuard(host, ops::history));
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
            HttpEnvPill pill = new HttpEnvPill(() -> showEnvironmentMenu(buffer, pills.get(buffer), Side.BOTTOM));
            pills.put(buffer, pill);
            gateRun(buffer, p);
            buffer.setHttpEnvControl(pill); // the environment, beside the view toggle, before any run (U3)
            buffer.setHttpPreviewNode(p); // makes hasPreview() true → the toggle attaches (controller)
            showEnvironment(buffer);
            refreshEnvironments(buffer);
            history.attached(buffer, p);
        } else if (!want && has) {
            buffer.setHttpEnvControl(null);
            pills.remove(buffer);
            environmentNames.remove(buffer);
            buffer.setHttpPreviewNode(null); // resets the buffer to EDITOR
            buffer.setHttpResults(List.of()); // no History behind them any more
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
            gateRun(buffer, panelFor(buffer)); // …and a Save As to a remote folder blocks Run
        }
    }

    /** Disables the panel's Run for a remote file, with a tooltip saying why (adl, round 3: remote files don't run). */
    private void gateRun(EditorBuffer buffer, @Nullable HttpClientPanel p) {
        if (p != null) {
            p.setRunBlocked(host.isLocalBuffer(buffer) ? null : tr("httppanel.remoteRunTip"));
        }
    }

    /**
     * Whether {@code buffer} is a remote file, whose requests do not run from this machine (they would go to this
     * machine's {@code localhost}, not the remote host's): says so and refuses. Every run entry point asks — the ▶ was
     * hidden, but the chords, the palette and the panel's Run still sent the request.
     */
    private boolean refuseRemote(@Nullable EditorBuffer buffer) {
        if (buffer != null && !host.isLocalBuffer(buffer)) {
            host.setStatus(tr("status.http.remoteBlocked"));
            return true;
        }
        return false;
    }

    /** Re-target on tab switch: (re)evaluate the active buffer's preview attachment + environment list. */
    void refreshFor(@Nullable EditorBuffer active) {
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
        Path target = to.toAbsolutePath().normalize();
        host.forEachBuffer(b -> {
            Path path = b.getPath();
            // A remote tab's path is on another file system: comparing it with a local one throws, and it is never
            // under a local folder anyway.
            if (path != null
                    && path.getFileSystem().equals(target.getFileSystem())
                    && path.toAbsolutePath().normalize().startsWith(target)) {
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
            environmentNames.remove(closed);
            pills.remove(closed);
            envCache.forget(closed.getPath(), ops.projectRoot());
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
                actions::copyAsCurl,
                actions::openInTab,
                s.getFontFamily(),
                editorFontSize());
        p.setOnCancel(() -> cancelRun(buffer));
        p.setOnRun(entry -> runAgain(buffer, entry));
        p.setOnJumpToRequest(entry -> jumpToRequest(buffer, entry));
        p.setOnEscape(() -> {
            // In Preview the editor is hidden, so focusing it did nothing: show it beside the response first.
            if (buffer.getMarkdownViewMode() == EditorBuffer.MarkdownViewMode.PREVIEW) {
                buffer.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.SPLIT);
            }
            focusWhenShown(buffer.getArea());
        });
        p.setOnCleared(() -> {
            history.cleared(buffer);
            refreshInlineResults(buffer); // a cleared History leaves no result on the request lines
        });
        p.setOnRunLine(line -> runRequest(buffer, line)); // a row of the empty state
        HttpEmptyStateFeed.attach(buffer, p);
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
    private @Nullable HttpClientPanel panelFor(@Nullable EditorBuffer buffer) {
        return buffer == null ? null : panels.get(buffer);
    }

    /** The active buffer's response panel, or {@code null}. */
    private @Nullable HttpClientPanel activePanel() {
        return panelFor(host.activeBuffer());
    }

    /** Save response for the response {@code buffer}'s panel shows (see {@link HttpResponseActions#save}). */
    private void saveResponse(EditorBuffer buffer) {
        HttpClientPanel p = panelFor(buffer);
        actions.save(buffer, p == null ? null : p.getSelectedExchange(), p == null ? null : p.getSelectedEntry());
    }

    /** Test-harness accessor for a buffer's response panel (mirrors {@code CsvCoordinator.gridNodeFor}). */
    @Nullable
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
            refreshInlineResults(b); // the setting may have changed
        });
        String family = host.settings().getFontFamily();
        int size = editorFontSize();
        panels.values().forEach(p -> p.setEditorFont(family, size));
        ops.updateRunGating();
        environmentShown.run(); // the status bar's segment comes and goes with the feature
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

    /**
     * {@code http.debugRequest}: runs the request at the caret with its pre-request scripts and response handlers
     * under the JavaScript debugger — each script waits for it, and stops at the breakpoints set in the {@code .http}
     * file's script lines or in a script file. Says why when there is nothing to debug or no debugger to use.
     */
    void debugRequestAtCaret() {
        ifHttp(() -> {
            EditorBuffer b = host.activeBuffer();
            if (b == null || !b.isHttpFile()) {
                host.setStatus(tr("status.http.noRequest"));
                return;
            }
            debugRequest(b, b.getArea().getCurrentParagraph());
        });
    }

    /** Debug Request for the request whose section holds 0-based {@code line} of {@code b} (the editor menu). */
    void debugRequest(EditorBuffer b, int line) {
        ifHttp(() -> {
            if (!isEnabled()) {
                host.setStatus(disabledMessage());
                return;
            }
            if (refuseRemote(b)) {
                return;
            }
            int index = HttpFile.requestIndexAt(b.getContent(), line);
            if (index < 0) {
                host.setStatus(tr("status.http.noRequest"));
                return;
            }
            HttpFile.Parsed parsed =
                    HttpFile.parseRequest(HttpFile.parse(b.getContent()).get(index));
            if (parsed.handlers().isEmpty() && parsed.preScripts().isEmpty()) {
                host.setStatus(tr("status.http.debugNoScript"));
                return;
            }
            if (!host.settings().isHttpScriptsEnabled()) {
                host.setStatus(tr("status.http.debugScriptsOff"));
                return;
            }
            com.editora.http.NodeResponseScripts.Debugger debugger = ops.scriptDebugger();
            if (debugger == null) {
                host.setStatus(tr("status.http.debugUnavailable"));
                return;
            }
            runIndex(b, index, debugger);
        });
    }

    /** Runs request {@code index} (0-based, in file order) of {@code buffer}. */
    private void runIndex(EditorBuffer buffer, int index) {
        runIndex(buffer, index, null);
    }

    /** As {@link #runIndex(EditorBuffer, int)}, its scripts under {@code debugger} when it is not null. */
    private void runIndex(
            EditorBuffer buffer, int index, com.editora.http.NodeResponseScripts.@Nullable Debugger debugger) {
        if (refuseRemote(buffer)) {
            return;
        }
        String text = buffer.getContent();
        List<HttpFile.Request> requests = HttpFile.parse(text);
        if (index < 0 || index >= requests.size()) {
            host.setStatus(tr("status.http.noRequest"));
            return;
        }
        HttpFile.Request req = requests.get(index);
        HttpFile.Parsed parsed = HttpFile.parseRequest(req);
        Map<String, String> vars = shownVariables(buffer); // for the label and the save-first check only
        Path baseDir = folderOf(buffer); // null: an untitled scratch buffer
        CapturedResponses session = capturesFor(buffer);
        String env = selectedEnvironment(buffer); // what this run resolves against, recorded with its result
        if (baseDir == null) {
            List<String> needs = HttpScratch.needsFolder(parsed, vars);
            if (!needs.isEmpty()) {
                HttpExchange unsent = saveFirstResult(parsed, needs);
                revealResult(buffer); // in Editor mode the "save first" result was never seen
                finishRun(
                        buffer,
                        List.of(parsed),
                        List.of(index),
                        List.of(unsent),
                        new long[] {System.currentTimeMillis()},
                        identitySet(List.of(unsent)),
                        false,
                        0,
                        env);
                return; // a body file, >> or $dotenv has no folder to resolve against
            }
        }
        String label = HttpScratch.label(parsed, vars, baseDir, ZonedDateTime.now());
        startRun(buffer, label, HttpPanelStates.runLabel(lineOf(buffer, req.sectionStart()), req.name(), label));
        buffer.setHttpRunning(index);
        long[] startedAt = {System.currentTimeMillis()};
        HttpClientService.Handle[] run = new HttpClientService.Handle[1];
        // The trust prompts say so in the panel, and the request's time starts once they are answered.
        HttpAskingOps asking = askingOps(buffer, run, () -> startedAt[0] = System.currentTimeMillis());
        // A request that reads $processEnv/$env/$dotenv asks first (off the FX thread, from what it resolves).
        run[0] = service.run(
                parsed,
                environmentFor(buffer, env),
                baseDir,
                session,
                HttpSecretGate.forBuffer(buffer, asking),
                HttpScriptGate.forBuffer(buffer, asking, host.settings(), debugger),
                ex -> {
                    if (running.remove(buffer, run[0])) {
                        finishRun(
                                buffer,
                                List.of(parsed),
                                List.of(index),
                                List.of(ex),
                                startedAt,
                                Set.of(),
                                false,
                                0,
                                env);
                    } else if (cancelling.remove(buffer, run[0])) {
                        landCancelled(
                                buffer, List.of(parsed), List.of(index), List.of(ex), startedAt, Set.of(), 1, env);
                    }
                });
        track(buffer, run[0]);
    }

    /**
     * The window's ops for the trust gates of {@code run[0]}, a run of {@code buffer}: while a trust prompt waits the
     * panel says "Waiting for your answer…" without a clock, and once it is answered the clock starts and {@code
     * answered} runs. A run that is no longer the buffer's current one changes nothing.
     */
    private HttpAskingOps askingOps(EditorBuffer buffer, HttpClientService.Handle[] run, Runnable answered) {
        return new HttpAskingOps(ops, asking -> {
            HttpClientPanel p = panelFor(buffer);
            if (run[0] == null || running.get(buffer) != run[0]) {
                return;
            }
            if (p != null) {
                p.awaitingAnswer(asking);
            }
            if (!asking) {
                answered.run();
            }
        });
    }

    /** Line {@code line} of {@code buffer}'s text, or null. */
    private static @Nullable String lineOf(EditorBuffer buffer, int line) {
        var area = buffer.getArea();
        return line >= 0 && line < area.getParagraphs().size()
                ? area.getParagraph(line).getText()
                : null;
    }

    /** The folder a buffer's relative paths resolve against: its file's, or null for an untitled buffer. */
    private static @Nullable Path folderOf(EditorBuffer buffer) {
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
    private static HttpExchange saveFirstResult(HttpFile.Parsed parsed, List<String> needs) {
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
        if (refuseRemote(buffer)) {
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
        String instead = tr("status.http.rerunAtCaret", ex.method() + " " + ex.url());
        host.setStatus(instead);
        HttpClientPanel p = panelFor(buffer);
        if (p != null) {
            p.showRunNotice(instead); // the status bar's message was replaced by the result within milliseconds
        }
    }

    /**
     * A Run All row's Enter or double-click: puts the caret on the request {@code entry} came from (found as Run again
     * finds it, {@link HttpRerunTarget}) and focuses the editor, leaving Preview for Split so the editor shows. Says so
     * when the request is no longer in the file.
     */
    void jumpToRequest(EditorBuffer buffer, HttpHistoryEntry entry) {
        if (buffer == null || entry == null) {
            return;
        }
        List<HttpFile.Request> requests = HttpFile.parse(buffer.getContent());
        int index = HttpRerunTarget.find(requests, entry);
        if (index < 0) {
            host.setStatus(tr("status.http.requestGone"));
            return;
        }
        if (buffer.getMarkdownViewMode() == EditorBuffer.MarkdownViewMode.PREVIEW) {
            buffer.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.SPLIT);
        }
        buffer.jumpToLine(requests.get(index).startLine());
        focusWhenShown(buffer.getArea()); // right after leaving Preview the editor is not in the scene yet
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
        ifHttp(() -> {
            if (!refuseRemote(host.activeBuffer())) {
                cancelRun(host.activeBuffer());
            }
        });
    }

    /** Cancels {@code buffer}'s in-flight run (the Cancel button and the command share this). */
    void cancelRun(@Nullable EditorBuffer buffer) {
        HttpClientService.Handle run = buffer == null ? null : running.remove(buffer);
        if (buffer == null || run == null) {
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
    HttpClientService.@Nullable Handle handleForTest(EditorBuffer buffer) {
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
            if (refuseRemote(b)) {
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
            Map<String, String> vars = shownVariables(b); // for the labels and the save-first check only
            String label = b.getPath() == null
                    ? b.getDisplayName()
                    : b.getPath().getFileName().toString();
            Path baseDir = folderOf(b); // null: an untitled scratch buffer
            CapturedResponses session = capturesFor(b);
            String env = selectedEnvironment(b); // what this run resolves against, recorded with its results
            // An untitled buffer sends what it can; a request needing the file's folder reports "save first".
            Map<Integer, HttpExchange> skipped = new LinkedHashMap<>();
            List<Integer> sent = new ArrayList<>();
            for (int i = 0; i < reqs.size(); i++) {
                List<String> needs = baseDir == null ? HttpScratch.needsFolder(reqs.get(i), vars) : List.of();
                if (needs.isEmpty()) {
                    sent.add(i);
                } else {
                    skipped.put(i, saveFirstResult(reqs.get(i), needs));
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
                revealResult(b);
                finishRun(b, reqs, indexes, new ArrayList<>(skipped.values()), startedAt, unsent, true, 0, env);
                return;
            }
            startRun(b, label, label);
            String[] startText = text.split("\n", -1); // each request's line as the run saw it, for the ■
            IntFunction<String> requestLine = i -> {
                int line = blocks.get(i).startLine();
                return line >= 0 && line < startText.length ? startText[line] : null;
            };
            b.setHttpRunning(sent.get(0), requestLine.apply(sent.get(0)));
            List<HttpFile.Parsed> toSend = sent.stream().map(reqs::get).toList();
            HttpClientService.Handle[] run = new HttpClientService.Handle[1];
            HttpAskingOps asking = askingOps(b, run, () -> {}); // each request's time is taken as it starts
            run[0] = service.runAll(
                    toSend,
                    environmentFor(b, env),
                    baseDir,
                    session,
                    HttpSecretGate.forBuffer(b, asking), // asked once, before the first request, if any reads a secret
                    HttpScriptGate.forBuffer(b, asking, host.settings(), null), // so is the folder, if any has a script
                    i -> {
                        HttpClientPanel p = running.get(b) == run[0] ? panelFor(b) : null;
                        if (p != null && i < toSend.size()) {
                            int index = sent.get(i);
                            startedAt[index] = System.currentTimeMillis();
                            b.setHttpRunning(index, requestLine.apply(index)); // the ■ follows the request in flight
                            int section = blocks.get(index).sectionStart();
                            p.progress(
                                    index,
                                    reqs.size(),
                                    HttpPanelStates.runLabel(
                                            section >= 0 && section < startText.length ? startText[section] : null,
                                            reqs.get(index).name(),
                                            HttpScratch.label(reqs.get(index), vars, baseDir, ZonedDateTime.now())));
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
                                    true,
                                    0,
                                    env);
                        } else if (cancelling.remove(b, run[0])) {
                            landCancelled(
                                    b,
                                    reqs,
                                    indexes,
                                    merged(reqs.size(), sent, exchanges, skipped),
                                    startedAt,
                                    unsent,
                                    reqs.size(),
                                    env);
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

    /**
     * {@code http.selectEnvironment}: opens the active {@code .http} file's environment menu at its pill, once the env
     * files are read again (they may have changed) — in any view mode, before any run. The menu takes the arrow keys
     * and Enter; Escape closes it and leaves the caret in the editor.
     */
    void selectEnvironment() {
        ifHttp(() -> {
            EditorBuffer b = host.activeBuffer();
            HttpEnvPill pill = b == null ? null : pills.get(b);
            if (pill == null) {
                host.setStatus(tr("status.http.noRequest"));
                return;
            }
            refreshEnvironments(b, () -> {
                if (host.activeBuffer() == b && pills.get(b) == pill) {
                    showEnvironmentMenu(b, pill, Side.BOTTOM);
                }
            });
        });
    }

    /**
     * Opens {@code buffer}'s environment menu at {@code anchor} (its pill, or the status-bar segment, above which it
     * opens): the file's environments and "No environment", the selected one checked; a disabled line saying why there
     * is nothing to choose, and why an env file contributes nothing; and an entry to open each env file the file has.
     * Not shown while {@code anchor} is not on screen (the menu is still built, for tests).
     */
    void showEnvironmentMenu(@Nullable EditorBuffer buffer, @Nullable Node anchor, Side side) {
        if (buffer == null || pills.get(buffer) == null) {
            return;
        }
        if (environmentMenu != null) {
            environmentMenu.hide();
        }
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("http-env-menu");
        menu.getItems().setAll(environmentMenuItems(buffer));
        menu.setOnHidden(e -> {
            if (environmentMenu == menu) {
                environmentMenu = null;
            }
            Node area = buffer.getFocusedArea();
            if (host.activeBuffer() == buffer && area.getScene() != null) {
                area.requestFocus(); // Escape, or a pick: back to typing (in Preview there is no editor to focus)
            }
        });
        environmentMenu = menu;
        if (anchor != null
                && anchor.getScene() != null
                && anchor.getScene().getWindow() != null
                && anchor.getScene().getWindow().isShowing()) {
            menu.show(anchor, side, 0, side == Side.TOP ? -2 : 2);
        }
    }

    private List<MenuItem> environmentMenuItems(EditorBuffer buffer) {
        List<MenuItem> items = new ArrayList<>();
        ToggleGroup group = new ToggleGroup();
        String selected = selectedEnvironment(buffer);
        List<String> names = environmentNames.getOrDefault(buffer, List.of());
        for (String name : names) {
            items.add(environmentItem(buffer, group, name, name, name.equals(selected)));
        }
        items.add(environmentItem(buffer, group, tr("httpenv.none"), "", selected.isEmpty()));
        HttpEnvironments.EnvFiles files = envFilesFor(buffer);
        if (names.isEmpty()) {
            items.add(disabledItem(HttpEnvChoice.emptyHint(files)));
        }
        for (String problem : files.problems()) {
            items.add(disabledItem(problem));
        }
        if (files.dir() != null && host.isLocalBuffer(buffer)) {
            List<MenuItem> open = new ArrayList<>();
            if (files.publicJson() != null) {
                open.add(openEnvFileItem(files.dir().resolve(com.editora.http.HttpEnv.PUBLIC_FILE)));
            }
            if (files.privateJson() != null) {
                open.add(openEnvFileItem(files.dir().resolve(com.editora.http.HttpEnv.PRIVATE_FILE)));
            }
            if (!open.isEmpty()) {
                items.add(new SeparatorMenuItem());
                items.addAll(open);
            }
        }
        return items;
    }

    private MenuItem environmentItem(
            EditorBuffer buffer, ToggleGroup group, String label, String name, boolean selected) {
        RadioMenuItem item = new RadioMenuItem(label);
        item.setToggleGroup(group);
        item.setSelected(selected);
        item.setUserData(name);
        item.setOnAction(e -> chooseEnvironment(buffer, name));
        return item;
    }

    private static MenuItem disabledItem(String text) {
        MenuItem item = new MenuItem(text);
        item.setDisable(true);
        return item;
    }

    private MenuItem openEnvFileItem(Path file) {
        MenuItem item = new MenuItem(tr("httpenv.open", file.getFileName().toString()));
        item.setOnAction(e -> ops.openFile(file));
        return item;
    }

    /**
     * Selects {@code environment} ({@code ""} or null: none) for {@code buffer}'s file — from the pill's menu, the
     * status bar's, or the "Use environment" quick fix: remembered in the file's saved history, and the marks, the
     * pill and the status bar follow. The next run resolves against it.
     */
    void chooseEnvironment(EditorBuffer buffer, String environment) {
        if (buffer == null || panelFor(buffer) == null) {
            return;
        }
        String name = environment == null ? "" : environment;
        boolean changed = !name.equals(environments.get(buffer));
        environments.put(buffer, name);
        if (changed) {
            history.persist(buffer);
        }
        showEnvironment(buffer);
        environmentListener.accept(buffer); // the editor's {{variable}} marks follow the choice
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
            if (request == null) {
                host.setStatus(tr("status.http.notCurl")); // no curl word or no URL: nothing worth writing
                return;
            }
            // Every {{ of the command was written as \{{ (sent as {{, never read as a variable): say so.
            boolean escaped = clip.contains("{{");
            // Body lines the format reads as comments, handlers or a new request: said, not silently dropped.
            int altered = CurlImport.alteredBodyLines(request);
            String note = altered > 0 ? " — " + tr("status.http.curlBodyAltered", String.valueOf(altered)) : "";
            EditorBuffer b = host.activeBuffer();
            if (b != null && b.isHttpFile() && b.isEditable()) {
                String snippet = (b.getArea().getLength() == 0 ? "" : "\n") + "###\n" + request + "\n";
                b.getArea().insertText(b.getArea().getLength(), snippet);
                b.getArea().moveTo(b.getArea().getLength());
                host.setStatus(tr(escaped ? "status.http.curlImportedEscaped" : "status.http.curlImported") + note);
            } else {
                EditorBuffer nb = new EditorBuffer();
                nb.setDisplayName("requests.http");
                ops.openTab(nb);
                nb.setContent(request);
                host.setStatus(
                        tr(escaped ? "status.http.curlImportedNewEscaped" : "status.http.curlImportedNew") + note);
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
            if (HttpStatusText.notSent(ex.result())) {
                host.setStatus(tr("status.http.curlNotSent")); // its URL and headers may still be templates
                return;
            }
            actions.copyAsCurl(ex);
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
            actions.openInTab(ex);
        });
    }

    /**
     * Updates this buffer's environment names (its pill and menu) from the env files that apply to it ({@link
     * HttpEnvironments} says which folder) with the names of both files, read in the background ({@link
     * HttpEnvCache}) and applied on the FX thread. Called when the preview is attached, on a tab switch and when the
     * window regains focus.
     */
    void refreshEnvironments(@Nullable EditorBuffer buffer) {
        refreshEnvironments(buffer, null);
    }

    /** {@link #refreshEnvironments(EditorBuffer)}, then {@code then} on the FX thread once the files are read. */
    private void refreshEnvironments(@Nullable EditorBuffer buffer, @Nullable Runnable then) {
        HttpClientPanel p = panelFor(buffer);
        if (buffer == null || p == null) {
            return;
        }
        Path file = buffer.getPath();
        envCache.refresh(file, ops.projectRoot(), read -> {
            if (panelFor(buffer) != p || !java.util.Objects.equals(file, buffer.getPath())) {
                return; // closed, switched off or saved elsewhere meanwhile (a newer refresh follows a Save As)
            }
            environmentNames.put(buffer, read.files().names());
            showEnvironment(buffer);
            environmentListener.accept(buffer); // the list (or the env files) may have changed
            if (then != null) {
                then.run();
            }
        });
    }

    /** Shows {@code buffer}'s environment on its pill and, for the active buffer, in the status bar. */
    private void showEnvironment(EditorBuffer buffer) {
        HttpEnvPill pill = pills.get(buffer);
        if (pill != null) {
            pill.show(selectedEnvironment(buffer), HttpEnvChoice.sources(envFilesFor(buffer)));
        }
        if (buffer == host.activeBuffer()) {
            environmentShown.run();
        }
    }

    /** Sets who is told when the active buffer's environment may have changed (the status bar's segment). */
    void setEnvironmentShownListener(Runnable listener) {
        this.environmentShown = listener == null ? () -> {} : listener;
    }

    /**
     * The status bar's text for {@code buffer}'s environment ("Env: dev", "No environment"), or null when it shows
     * none: not an {@code .http} file, or the HTTP Client is off.
     */
    @Nullable
    String environmentLabel(@Nullable EditorBuffer buffer) {
        return buffer != null && pills.containsKey(buffer) && isEnabled()
                ? HttpEnvChoice.label(selectedEnvironment(buffer))
                : null;
    }

    /** The env files that apply to {@code buffer}, as last read (no file access). */
    private HttpEnvironments.EnvFiles envFilesFor(EditorBuffer buffer) {
        return envCache.last(buffer.getPath(), ops.projectRoot()).files();
    }

    /**
     * {@link #refreshEnvironments} for every open {@code .http} buffer: the window regained focus, so an env file may
     * have been edited in another program (the run re-reads them; the pickers and marks would otherwise wait for a
     * tab switch).
     */
    void refreshAllEnvironments() {
        if (isEnabled()) {
            host.forEachBuffer(b -> {
                if (b.isHttpFile()) {
                    refreshEnvironments(b);
                }
            });
        }
    }

    /** Told when a buffer's environment choice, or the env files it comes from, may have changed. */
    private Consumer<EditorBuffer> environmentListener = b -> {};

    /** Sets who is told about environment changes (the editor's {@code {{variable}}} hints). */
    void setEnvironmentListener(Consumer<EditorBuffer> listener) {
        this.environmentListener = listener == null ? b -> {} : listener;
    }

    /**
     * The environment selected for {@code buffer}'s file ({@code ""}: none), as a run would use it: its own choice,
     * else the window-wide one of older versions ({@link WindowOps#savedEnvironment}), as long as its env files (as
     * last read) define it.
     */
    String selectedEnvironment(@Nullable EditorBuffer buffer) {
        if (buffer == null || panelFor(buffer) == null) {
            return "";
        }
        String chosen = environments.get(buffer);
        return HttpEnvironments.choose(
                chosen != null ? chosen : ops.savedEnvironment(), environmentNames.getOrDefault(buffer, List.of()));
    }

    /** What a buffer's saved history records as its environment: its own choice as made, else what is selected. */
    private String persistedEnvironment(EditorBuffer buffer) {
        String chosen = environments.get(buffer);
        return chosen != null ? chosen : selectedEnvironment(buffer);
    }

    /** The environment names {@code buffer}'s env files define, as last read. Test accessor. */
    List<String> environmentNamesForTest(EditorBuffer buffer) {
        return List.copyOf(environmentNames.getOrDefault(buffer, List.of()));
    }

    /** {@code buffer}'s environment pill, or null. Test accessor. */
    @Nullable
    HttpEnvPill envPillForTest(EditorBuffer buffer) {
        return pills.get(buffer);
    }

    /** The environment menu now open (or last built while its anchor was off screen), or null. Test accessor. */
    @Nullable
    ContextMenu environmentMenuForTest() {
        return environmentMenu;
    }

    /**
     * The folder whose env files apply to {@code buffer} (see {@link HttpEnvironments}) as last read, or null before
     * the first read (which tells the {@link #setEnvironmentListener listener} when it lands). No file access.
     */
    @Nullable
    Path envDirFor(EditorBuffer buffer) {
        return buffer == null
                ? null
                : envCache.last(buffer.getPath(), ops.projectRoot()).dir();
    }

    // --- results on the request lines (U11) ---

    /**
     * Puts each request's last result after its method/URL line in {@code buffer}'s editor — the newest History entry
     * whose request still reads that line ({@link HttpInlineResult#newestPerRequest}) — or removes them all when the
     * setting is off, the buffer has no panel or runs nothing from here. Called when a run lands, a saved history is
     * restored, History is cleared and the settings are applied; the editor keeps them on their lines in between.
     */
    void refreshInlineResults(EditorBuffer buffer) {
        if (buffer == null) {
            return;
        }
        HttpClientPanel p = panelFor(buffer);
        List<HttpHistoryEntry> entries = p == null ? List.of() : p.history();
        if (entries.isEmpty() || !host.settings().isHttpInlineResults() || !host.isLocalBuffer(buffer)) {
            buffer.setHttpResults(List.of());
            return;
        }
        String text = buffer.getContent();
        List<HttpFile.Request> requests = HttpFile.parse(text);
        String[] lines = text.split("\n", -1);
        long now = System.currentTimeMillis();
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        List<com.editora.editor.http.HttpLineResult> results = new ArrayList<>();
        HttpInlineResult.newestPerRequest(requests, entries).forEach((index, entry) -> {
            int line = requests.get(index).startLine();
            if (line < 0 || line >= lines.length) {
                return;
            }
            HttpResult r = entry.result();
            results.add(new com.editora.editor.http.HttpLineResult(
                    line,
                    lines[line],
                    HttpInlineResult.segments(r),
                    HttpInlineResult.glyphClass(r),
                    tr("httpinline.tooltip", HttpInlineResult.ranAt(entry.at(), now, zone)),
                    () -> showInlineResult(buffer, entry)));
        });
        buffer.setHttpResults(results);
    }

    /** A click on a request line's result: the response view shows that History entry. */
    private void showInlineResult(EditorBuffer buffer, HttpHistoryEntry entry) {
        HttpClientPanel p = panelFor(buffer);
        if (p != null) {
            buffer.revealHttpPreview(); // in Editor mode the response view is hidden, as before a run
            p.showHistoryEntry(entry);
        }
    }

    // --- internals ---

    /** Shows {@code buffer}'s response view (Editor → Split), as a run that is sent does on start. */
    private void revealResult(EditorBuffer buffer) {
        if (panelFor(buffer) != null) {
            buffer.revealHttpPreview();
        }
    }

    /**
     * Shows the request as running in {@code buffer}'s response preview, revealing that preview (Editor →
     * Split) so the result is visible without the user having to open it — the in-editor equivalent of the
     * tool window this feature used to auto-open.
     */
    private void startRun(EditorBuffer buffer, @Nullable String label, @Nullable String shown) {
        HttpClientPanel p = panelFor(buffer);
        if (p != null) {
            buffer.revealHttpPreview();
            p.started(shown, label); // "Sending Get one user…", the request as resolved in its tooltip
        }
        host.setStatus(tr("status.http.running", label));
    }

    /**
     * Lands a run's {@code exchanges} (for {@code requests} at {@code indexes}) in the response view and the
     * saved history, and says in the status bar what was sent (variables resolved) and how it went. A Run All
     * ({@code all}) is listed one row per request; {@code cancelledOf} is how many requests a cancelled one had (0: it
     * finished).
     */
    private void finishRun(
            EditorBuffer buffer,
            List<HttpFile.Parsed> requests,
            List<Integer> indexes,
            List<HttpExchange> exchanges,
            long[] startedAt,
            Set<HttpExchange> unsent,
            boolean all,
            int cancelledOf,
            String environment) {
        buffer.setHttpRunning(-1); // the ■ turns back into ▶
        long now = System.currentTimeMillis();
        Map<String, List<String>> definedIn = null; // read once, and only when a variable was sent empty
        List<HttpHistoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < exchanges.size(); i++) {
            HttpFile.Parsed req = i < requests.size() ? requests.get(i) : null;
            HttpExchange ex = exchanges.get(i);
            boolean noLog = (req != null && req.directives().noLog()) || unsent.contains(ex);
            List<String> warnings = ex.result().warnings();
            if (warnings != null && !warnings.isEmpty()) {
                if (definedIn == null) {
                    definedIn = HttpEnvChoice.definedIn(envFilesFor(buffer));
                }
                List<String> explained = HttpEnvChoice.explainWarnings(warnings, environment, definedIn);
                if (explained != warnings) { // "{{token}} was sent as empty text: … (defined in dev, staging)"
                    ex = ex.withResult(ex.result().withWarnings(explained));
                }
            }
            entries.add(HttpHistoryEntry.ran(
                    ex,
                    req,
                    i < indexes.size() ? indexes.get(i) : -1,
                    startedAt != null && i < startedAt.length ? startedAt[i] : now,
                    // # @no-log, and an untitled buffer's "not sent — save first": shown now, never written to disk
                    noLog,
                    environment));
        }
        HttpClientPanel p = panelFor(buffer);
        if (p != null) {
            if (all) {
                p.showRunAll(entries, cancelledOf); // one row per request above the tabs
            } else {
                p.showEntries(entries);
            }
            history.persist(buffer);
            refreshInlineResults(buffer);
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
            int total,
            String environment) {
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
        // A cancelled Run All lists what finished under "Cancelled · 2 of 4 ran", not "all 2 succeeded".
        finishRun(
                buffer, keptRequests, keptIndexes, kept, starts, unsent, total > 1, total > 1 ? total : 0, environment);
        // One request whose answer was in before the cancel took effect: "1 of 1 requests had finished" read oddly.
        host.setStatus(
                total > 1 ? tr("status.http.cancelledAfter", kept.size(), total) : tr("status.http.cancelledTooLate"));
    }

    /**
     * The environment variables of {@code buffer}'s selected environment as last read — for what the FX thread shows
     * before a run (the "Running …" label) and the untitled save-first check. No file access; may be a moment old.
     */
    private Map<String, String> shownVariables(EditorBuffer buffer) {
        return envFilesFor(buffer).variables(selectedEnvironment(buffer));
    }

    /**
     * The environment a run of {@code buffer} resolves against: the selected environment of both env files, merged as
     * JetBrains does (public {@code $shared} < private {@code $shared} < public env < private env), read on the run's
     * worker as it starts. They are not resolved here: the run resolves them, with the {@code @var} lines above each
     * request, the file's folder for {@code $dotenv}, and the buffer's captured responses. An env file that cannot be
     * read is named first among the warnings (not only as "{{x}} sent as empty").
     */
    private RunEnvironment environmentFor(EditorBuffer buffer, String env) {
        // The environment selected for the file being run (each .http buffer has its own choice), as the run started.
        Path file = buffer.getPath();
        Path project = ops.projectRoot();
        return () -> {
            HttpEnvironments.EnvFiles files = envCache.readNow(file, project).files();
            return new RunEnvironment.Snapshot(files.variables(env), files.problems());
        };
    }

    /** The buffer's chaining session, cleared first when the selected environment changed since its last run. */
    private CapturedResponses capturesFor(EditorBuffer buffer) {
        CapturedResponses session = captures.computeIfAbsent(buffer, b -> new CapturedResponses());
        session.resetUnlessEnvironment(selectedEnvironment(buffer));
        return session;
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
