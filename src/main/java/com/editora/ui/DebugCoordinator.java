package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import com.editora.config.Breakpoint;
import com.editora.config.BreakpointStore;
import com.editora.config.PathKeys;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DapServerRegistry;
import com.editora.editor.BreakpointManager;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import com.editora.run.ProgramArgs;
import com.editora.run.StackTraceLinks;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * Multi-language debugging (Debug Adapter Protocol) for Java / Python / JavaScript, extracted from
 * {@link MainController} via the {@link CoordinatorHost} pattern. Owns the {@link DebugPanel}, the
 * breakpoint persistence + gutter gating, the DAP event sink + panel actions, the inline-values / hover /
 * execution-line editor surfaces, the start/attach/step/run-to-cursor/jump flows, and the
 * {@code debug.toggleAdapter}/{@code debug.setAdapterPath} pickers.
 *
 * <p>The {@link DapManager} is <em>not</em> owned here: it stays a {@code MainController} field (it's built
 * on the LSP {@code lspManager}, and {@code SettingsWindow} + window-dispose reach it) and is passed in,
 * mirroring how {@link LspCoordinator} takes the {@code lspManager}. Java debugging layers on the jdtls LSP
 * session, so the coordinator also takes the {@code lspManager} + {@link LspCoordinator} to push the
 * java-debug bundle and re-gate the java buffers. {@code MainController} keeps the Debug {@code ToolWindow}
 * (built with {@link #panel()}), the shared {@code programArgs}/{@code openRunLink}/{@code save} helpers, and
 * the {@code debug.*} command registrations (which delegate here).
 */
final class DebugCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} that the debug flows need. */
    interface Ops {
        void openToolWindow();

        void toggleToolWindow();

        void setToolWindowAvailable(boolean available);

        /** Status-bar debug-state segment ({@code null} clears it). */
        void setStatusDebug(String text);

        /** Status-bar indeterminate loading bar (a session is starting). */
        void setStatusDebugLoading(boolean loading);

        /** Saves {@code buffer} (dirty → write, untitled → Save-As); {@code false} if cancelled/failed. */
        boolean saveBuffer(EditorBuffer buffer);

        /** The remembered program-arguments string for {@code path} (shared with the Run feature). */
        String programArgs(Path path);

        /** A stack-trace location double-clicked in the Debug console: resolve + jump (shared resolver). */
        void openLink(StackTraceLinks.Link link);

        /** Opens (or focuses) the tab for {@code file} (the frame's file, for the execution highlight). */
        void openPath(Path file);

        /** The open buffer for {@code file} (canonical-tab match), or {@code null} when no tab holds it. */
        EditorBuffer bufferForPath(Path file);

        /** The persisted debug-watch expressions (workspace state). */
        List<String> debugWatches();

        /** Persists the debug-watch expressions (workspace state + durable save). */
        void persistDebugWatches(List<String> watches);

        /** The per-file breakpoint map ({@code breakpoints.json}); path-string → that file's breakpoints. */
        Map<String, List<Breakpoint>> breakpointMap();

        /** Writes {@code breakpoints.json}. */
        void saveBreakpoints();
    }

    /** Debug adapters whose enable can be toggled ("java" has no enable — gated by the Java LSP server). */
    private static final String[] DEBUG_TOGGLEABLE_ADAPTERS = {"python", "javascript"};

    /** Debug adapters with a configurable command/path. */
    private static final String[] DEBUG_PATH_ADAPTERS = {"java", "python", "javascript"};

    /** Inline-value fetch cap — frames can hold hundreds of locals; the overlay needs a name→value map. */
    private static final int MAX_INLINE_VALUES = 100;

    /** Don't slurp a huge file just to re-anchor its breakpoints (fall back to the stored line indices). */
    private static final long MAX_BREAKPOINT_FILE_BYTES = 20L * 1024 * 1024;

    private final CoordinatorHost host;
    private final DapManager dapManager;
    private final LspManager lspManager;
    private final LspCoordinator lsp;
    private final Ops ops;
    private final DebugPanel debugPanel;

    private final Set<String> exceptionFilters = new LinkedHashSet<>();

    /** The java-debug bundle jars last pushed to the LSP layer — restart jdtls only when this changes. */
    private List<String> appliedDebugBundles = List.of();

    /** The buffer currently carrying the execution-line highlight (cleared on resume/terminate). */
    private EditorBuffer execHighlightBuffer;

    /** The selected stack frame's id (the hover evaluator's context); -1 while not suspended. */
    private int debugFrameId = -1;

    /** The buffer currently carrying inline values + an active hover (cleared on resume/terminate). */
    private EditorBuffer debugValuesBuffer;

    /**
     * Re-anchored breakpoints of files with <em>no</em> open tab, computed off the FX thread at each session
     * start (see {@link #withClosedBreakpoints}). Merged into {@link #collectBreakpoints()} so a breakpoint
     * in a closed file is armed like VS Code / IntelliJ, not silently inert. {@code volatile}: written on the
     * FX thread, read by the DAP supplier (also the FX thread, but keep it safe).
     */
    private volatile List<DapModels.FileBreakpoints> closedBreakpoints = List.of();

    // Coalesce the per-edit (line-shift) breakpoint persist off the FX hot path — see schedulePersistBreakpoints.
    // Only the synchronous breakpoints.json write is debounced; the adapter update stays immediate. (#551)
    private final javafx.animation.PauseTransition persistDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
    private EditorBuffer pendingPersist;

    DebugCoordinator(CoordinatorHost host, DapManager dapManager, LspManager lspManager, LspCoordinator lsp, Ops ops) {
        this.host = host;
        this.dapManager = dapManager;
        this.lspManager = lspManager;
        this.lsp = lsp;
        this.ops = ops;
        persistDebounce.setOnFinished(e -> flushPendingPersist());
        this.debugPanel = new DebugPanel(debugActions());
        debugPanel.setPrompt(host::promptText);
        debugPanel.setOnLink(ops::openLink); // double-clicked stack-trace line → jump
        debugPanel.setWatches(ops.debugWatches());
        debugPanel.setOnWatchesChanged(() -> ops.persistDebugWatches(new ArrayList<>(debugPanel.getWatches())));
        dapManager.setListener(dapListener());
        dapManager.setBreakpointsSupplier(this::collectBreakpoints);
    }

    /** The Debug tool-window content (the {@code ToolWindow} itself stays in {@code MainController}). */
    DebugPanel panel() {
        return debugPanel;
    }

    // --- gating / effectiveness --------------------------------------------------------------------

    boolean debugSupportEnabled() {
        // Simple UI mode disables debugging (+ the breakpoint gutter) entirely; saved setting unchanged.
        return host.settings().isDebugSupport() && !host.simpleModeActive();
    }

    /** LSP-on-and-not-simple — the precondition Java debugging layers on (mirrors {@code MainController.lspEnabled}). */
    private boolean lspEnabled() {
        return host.settings().isLspSupport() && !host.simpleModeActive();
    }

    /** Debugging is <em>effective</em> for Java only when LSP + the java server are on and detected, and
     *  the java-debug plugin jar was located. */
    boolean debugEffective() {
        return debugEffectiveFor("java");
    }

    /**
     * Whether debugging is available for {@code language} (the editor/LSP language id): Java layers on the
     * jdtls LSP server + the java-debug plugin; Python needs {@code pythonDebugEnabled} + debugpy detected;
     * JavaScript needs {@code jsDebugEnabled} + the js-debug server + node detected.
     */
    boolean debugEffectiveFor(String language) {
        if (!debugSupportEnabled() || language == null) {
            return false;
        }
        var s = host.settings();
        return switch (language) {
            case "java" ->
                lspEnabled()
                        && lsp.serverEnabled("java")
                        && lsp.isServerAvailable("java")
                        && dapManager.isAdapterAvailable();
            case "python" -> s.isPythonDebugEnabled() && dapManager.isLanguageAvailable("python");
            case "javascript" -> s.isJsDebugEnabled() && dapManager.isLanguageAvailable("javascript");
            default -> false;
        };
    }

    /** Runs {@code action} only when the Debug feature is enabled; otherwise reports it. */
    void ifDebug(Runnable action) {
        if (debugSupportEnabled()) {
            action.run();
        } else {
            host.setStatus(tr("statusbar.tip.debugDisabled"));
        }
    }

    /**
     * Reconciles the Debug feature with its setting (mirrors {@link LspCoordinator#applySupport}). The plugin
     * jar is located synchronously and pushed into the LSP layer BEFORE any jdtls session can start — doing it
     * in an async callback raced the session-restore, so jdtls could come up without the debug bundle. When the
     * bundle set changes, a running jdtls is restarted so it reloads with (or without) the plugin. Gates the
     * breakpoint gutter + Debug window. Runs at init + every settings apply.
     */
    void applySupport() {
        var s = host.settings();
        boolean on = debugSupportEnabled(); // effective: off in Simple UI mode
        dapManager.configure(
                on,
                s.getJavaDebugPluginPath(),
                s.isPythonDebugEnabled(),
                s.getPythonDebugCommand(),
                s.isJsDebugEnabled(),
                s.getJsDebugPath());
        List<String> bundles = on ? dapManager.bundlePaths() : List.of();
        boolean changed = !bundles.equals(appliedDebugBundles);
        lspManager.setDebugBundles(bundles); // set before sessions start — jdtls always gets the bundle
        appliedDebugBundles = bundles;
        if (!on) {
            dapManager.stop();
        }
        if (changed) {
            lspManager.restartServer("java"); // reload a running jdtls with/without the bundle (no-op if none)
            lsp.applyGating(); // re-open the java buffers on the fresh session
        }
        applyGating();
        if (on) {
            // Probe debugpy / node off-thread; re-gate when each result lands (enables python/js gutters).
            dapManager.detectPython(ok -> applyGating());
            dapManager.detectJs(ok -> applyGating());
        }
    }

    /** Per-buffer breakpoint-gutter gate (only for debuggable languages) + Debug tool-window availability. */
    void applyGating() {
        boolean on = debugSupportEnabled();
        host.forEachBuffer(b -> b.setBreakpointsEnabled(on && isDebuggableBuffer(b)));
        updateDebugAvailability();
        if (!on) {
            ops.setStatusDebug(null);
            ops.setStatusDebugLoading(false);
        }
    }

    /**
     * The Debug tool-window stripe button is shown only when the active file is debuggable (Java/Python/JS) —
     * or whenever a debug session is live, so it never disappears mid-session if you peek at another file.
     */
    void updateDebugAvailability() {
        boolean available = debugSupportEnabled() && (isDebuggableBuffer(host.activeBuffer()) || dapManager.isActive());
        ops.setToolWindowAvailable(available);
    }

    /** Whether a buffer's language has a registered debug adapter (java/python/javascript). */
    boolean isDebuggableBuffer(EditorBuffer b) {
        return b != null && host.isLocalBuffer(b) && DapServerRegistry.isDebuggable(b.getLanguage());
    }

    // --- breakpoints -------------------------------------------------------------------------------

    /** Persists a buffer's breakpoints + (if a session is live) re-sends that file's set to the adapter. */
    void onBreakpointsChanged(EditorBuffer buffer) {
        schedulePersistBreakpoints(buffer); // debounced FS write (off the per-newline hot path)
        if (buffer.getPath() != null && dapManager.isActive()) {
            dapManager.updateBreakpoints(fileBreakpoints(buffer)); // adapter stays current immediately
        }
    }

    /**
     * Coalesces the synchronous breakpoints.json write fired per line-shifting edit (holding Enter above a
     * breakpoint) so it lands once, ~300 ms after editing settles, instead of blocking the FX thread per newline.
     * reanchor-on-open recovers indices lost to a crash before the write. (#551)
     */
    private void schedulePersistBreakpoints(EditorBuffer buffer) {
        pendingPersist = buffer;
        persistDebounce.playFromStart();
    }

    private void flushPendingPersist() {
        EditorBuffer b = pendingPersist;
        pendingPersist = null;
        if (b != null) {
            persistBreakpoints(b);
        }
    }

    private void persistBreakpoints(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        List<Breakpoint> bps = buffer.getBreakpointManager().snapshot();
        var map = ops.breakpointMap();
        if (bps.isEmpty()) {
            map.remove(file.toString());
        } else {
            map.put(file.toString(), BreakpointStore.mergePreservingOrder(map.get(file.toString()), bps));
        }
        ops.saveBreakpoints();
    }

    void restoreBreakpoints(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        if (buffer.applyBreakpoints(ops.breakpointMap().get(file.toString()))) {
            persistBreakpoints(buffer); // self-heal re-anchored indices once
        }
    }

    /** The enabled breakpoints of {@code buffer} as a DAP {@code FileBreakpoints} (empty list if none). */
    private DapModels.FileBreakpoints fileBreakpoints(EditorBuffer buffer) {
        List<DapModels.LineBreakpoint> lines = new ArrayList<>();
        for (Breakpoint bp : buffer.getBreakpointManager().snapshot()) {
            if (bp.enabled()) {
                lines.add(new DapModels.LineBreakpoint(bp.line(), bp.condition(), bp.logMessage()));
            }
        }
        return new DapModels.FileBreakpoints(buffer.getPath(), lines);
    }

    /**
     * Every armed breakpoint sent to the adapter: open buffers' live breakpoints merged with
     * {@link #closedBreakpoints} (files with no open tab, re-anchored at session start). A closed file that
     * has since been opened is taken from its live buffer, not the cached snapshot.
     */
    private List<DapModels.FileBreakpoints> collectBreakpoints() {
        List<DapModels.FileBreakpoints> out = new ArrayList<>();
        Set<String> open = new HashSet<>();
        host.forEachBuffer(b -> {
            if (b.getPath() == null) {
                return;
            }
            open.add(b.getPath().toString());
            DapModels.FileBreakpoints fb = fileBreakpoints(b);
            if (!fb.breakpoints().isEmpty()) {
                out.add(fb);
            }
        });
        for (DapModels.FileBreakpoints fb : closedBreakpoints) {
            if (fb.file() != null && !open.contains(fb.file().toString())) {
                out.add(fb);
            }
        }
        return out;
    }

    /**
     * Re-anchors the persisted breakpoints of files with no open tab (off the FX thread), caches them in
     * {@link #closedBreakpoints}, then runs {@code then} on the FX thread. Called before every session start
     * so the initial {@code setBreakpoints} includes closed files. The open-tab set + a copy of the map are
     * snapshotted on the FX thread; the file reads happen on a daemon thread.
     */
    private void withClosedBreakpoints(Runnable then) {
        Set<String> openPaths = new HashSet<>();
        host.forEachBuffer(b -> {
            if (b.getPath() != null) {
                openPaths.add(b.getPath().toString());
            }
        });
        Map<String, List<Breakpoint>> map = new LinkedHashMap<>(ops.breakpointMap());
        Thread t = new Thread(
                () -> {
                    List<DapModels.FileBreakpoints> computed =
                            closedFileBreakpoints(map, openPaths, Vfs::isLocal, DebugCoordinator::readLinesOrNull);
                    Platform.runLater(() -> {
                        closedBreakpoints = computed;
                        then.run();
                    });
                },
                "debug-breakpoints");
        t.setDaemon(true);
        t.start();
    }

    /** Reads a local file's lines for re-anchoring, or {@code null} when it can't be used as-is (unreadable,
     *  too large, or non-UTF-8) — the caller then falls back to the stored line indices. */
    private static List<String> readLinesOrNull(Path p) {
        try {
            if (!Files.isReadable(p) || Files.size(p) > MAX_BREAKPOINT_FILE_BYTES) {
                return null;
            }
            return Files.readAllLines(p);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The DAP breakpoints for files that have no open tab, each re-anchored against the file's current text.
     * Pure/unit-testable: {@code isLocal} skips remote paths, {@code readLines} returns a file's lines (or
     * {@code null} to keep the stored indices). Open-tab files are excluded — their live buffer supplies them.
     * A file whose lines are read is re-anchored via {@link BreakpointManager#reanchor} (so an external edit
     * that shifted lines still hits the right code); an unreadable one keeps its persisted indices rather than
     * being dropped. Only enabled breakpoints are emitted.
     */
    static List<DapModels.FileBreakpoints> closedFileBreakpoints(
            Map<String, List<Breakpoint>> map,
            Set<String> openPaths,
            Predicate<Path> isLocal,
            Function<Path, List<String>> readLines) {
        List<DapModels.FileBreakpoints> out = new ArrayList<>();
        if (map == null) {
            return out;
        }
        for (Map.Entry<String, List<Breakpoint>> e : map.entrySet()) {
            String key = e.getKey();
            if (key == null || (openPaths != null && openPaths.contains(key))) {
                continue;
            }
            List<Breakpoint> bps = e.getValue();
            if (bps == null || bps.isEmpty()) {
                continue;
            }
            Path p;
            try {
                p = Path.of(key);
            } catch (RuntimeException ex) {
                continue;
            }
            if (!isLocal.test(p)) {
                continue; // remote/SFTP breakpoints aren't sent (the whole debug feature is local-only)
            }
            List<String> lines = readLines.apply(p);
            List<DapModels.LineBreakpoint> out2 = new ArrayList<>();
            if (lines != null && !lines.isEmpty()) {
                var anchored = BreakpointManager.reanchor(
                        bps,
                        lines.size(),
                        i -> i >= 0 && i < lines.size() ? lines.get(i) : "",
                        BreakpointManager.MAX_REANCHOR_SCAN);
                for (Breakpoint bp : anchored.values()) {
                    if (bp.enabled()) {
                        out2.add(new DapModels.LineBreakpoint(bp.line(), bp.condition(), bp.logMessage()));
                    }
                }
            } else {
                for (Breakpoint bp : bps) {
                    if (bp.enabled()) {
                        out2.add(new DapModels.LineBreakpoint(bp.line(), bp.condition(), bp.logMessage()));
                    }
                }
            }
            if (!out2.isEmpty()) {
                out.add(new DapModels.FileBreakpoints(p, out2));
            }
        }
        return out;
    }

    // --- DAP event sink + panel actions ------------------------------------------------------------

    private DapManager.Listener dapListener() {
        return new DapManager.Listener() {
            @Override
            public void onState(DapManager.State state) {
                debugPanel.setState(state);
                updateDebugStatus(state);
                if (state != DapManager.State.SUSPENDED) {
                    clearExecHighlight();
                    clearDebugEditorSurfaces(); // inline values + hover live only while suspended
                }
                boolean starting = state == DapManager.State.STARTING;
                ops.setStatusDebugLoading(starting);
                updateDebugAvailability(); // keep the window available during a session; hide it once it ends
                if (starting) {
                    ops.openToolWindow();
                }
            }

            @Override
            public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {
                debugPanel.setCallStack(frames); // auto-selects the top frame → selectFrame highlights + loads vars
                dapManager.threads(list -> debugPanel.setThreads(list, dapManager.currentThreadId()));
            }

            @Override
            public void onOutput(String text, String category) {
                debugPanel.appendOutput(text, category);
            }

            @Override
            public void onError(String message) {
                host.setStatus(tr("status.debug.error", message));
            }
        };
    }

    private void updateDebugStatus(DapManager.State state) {
        switch (state) {
            case INACTIVE, TERMINATED -> ops.setStatusDebug(null);
            case STARTING -> ops.setStatusDebug(tr("debug.state.starting"));
            case RUNNING -> ops.setStatusDebug(tr("debug.state.running"));
            case SUSPENDED -> ops.setStatusDebug(tr("debug.state.suspended"));
        }
    }

    private DebugPanel.Actions debugActions() {
        return new DebugPanel.Actions() {
            @Override
            public void start() {
                debugStart(); // start a session when idle, or continue when suspended
            }

            @Override
            public void pause() {
                dapManager.pause();
            }

            @Override
            public void runToCursor() {
                debugRunToCursor();
            }

            @Override
            public void selectThread(int threadId) {
                dapManager.selectThread(threadId, frames -> debugPanel.setCallStack(frames));
            }

            @Override
            public void stepOver() {
                dapManager.stepOver();
            }

            @Override
            public void stepInto() {
                dapManager.stepInto();
            }

            @Override
            public void stepOut() {
                dapManager.stepOut();
            }

            @Override
            public void stop() {
                dapManager.stop();
            }

            @Override
            public void restart() {
                dapManager.restart();
            }

            @Override
            public void selectFrame(DapModels.StackFrameInfo frame) {
                debugFrameId = frame.id(); // the hover evaluator's frame context
                highlightFrame(frame);
                dapManager.scopes(frame.id(), scopes -> {
                    debugPanel.setScopes(scopes);
                    applyInlineValues(frame, scopes);
                });
            }

            @Override
            public void loadChildren(int ref, Consumer<List<DapModels.VariableInfo>> cb) {
                dapManager.variables(ref, cb);
            }

            @Override
            public void evaluate(String expr, int frameId, Consumer<String> cb) {
                dapManager.evaluate(expr, frameId, "repl", cb);
            }

            @Override
            public void evaluateWatch(String expr, int frameId, Consumer<DapModels.EvalResult> cb) {
                dapManager.evaluateFull(expr, frameId, "watch", cb);
            }

            @Override
            public void setVariable(int parentRef, String name, String value, Consumer<String> cb) {
                dapManager.setVariable(parentRef, name, value, cb);
            }
        };
    }

    // --- editor surfaces while suspended (inline values / hover / execution line) ------------------

    /** Fetches the selected frame's local variables and paints them as inline values in the frame's
     *  file buffer; also arms the hover value tooltip there (IntelliJ's editor surfaces). */
    private void applyInlineValues(DapModels.StackFrameInfo frame, List<DapModels.ScopeInfo> scopes) {
        if (frame == null || frame.file() == null || scopes.isEmpty()) {
            return;
        }
        DapModels.ScopeInfo locals =
                scopes.stream().filter(s -> !s.expensive()).findFirst().orElse(null);
        if (locals == null) {
            return;
        }
        dapManager.variables(locals.variablesReference(), vars -> {
            if (dapManager.state() != DapManager.State.SUSPENDED) {
                return; // resumed while the request was in flight
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (DapModels.VariableInfo v : vars) {
                if (values.size() == MAX_INLINE_VALUES) {
                    break;
                }
                values.put(v.name(), v.value());
            }
            EditorBuffer b = ops.bufferForPath(frame.file());
            if (b == null) {
                return;
            }
            if (debugValuesBuffer != null && debugValuesBuffer != b) {
                debugValuesBuffer.setInlineValues(null); // frame moved to another file
                debugValuesBuffer.setDebugHoverActive(false);
            }
            debugValuesBuffer = b;
            b.setInlineValues(values);
            b.setDebugHoverActive(true);
        });
    }

    private void clearDebugEditorSurfaces() {
        debugFrameId = -1;
        if (debugValuesBuffer != null) {
            debugValuesBuffer.setInlineValues(null);
            debugValuesBuffer.setDebugHoverActive(false);
            debugValuesBuffer = null;
        }
    }

    /** Opens the frame's file and paints the execution-line highlight there. */
    private void highlightFrame(DapModels.StackFrameInfo frame) {
        if (frame == null || frame.file() == null) {
            return;
        }
        clearExecHighlight();
        // Revealing the line focuses the editor (openPath calls requestFocus on the buffer). When the user
        // is driving the session from the Debug panel — the single-key step shortcuts — that yanks focus to
        // the code after every step, so the next key press is lost and they must re-focus the panel each
        // time. Preserve the panel's focus across the reveal in that case only; a fresh breakpoint hit while
        // editing (focus not in the panel) still lands in the code at the stopped line, as before.
        Node keepFocus = debugPanelFocusOwner();
        ops.openPath(frame.file()); // opens or focuses the tab
        // Take the frame's OWN buffer, not whatever is active: openPath opens nothing when the source
        // isn't on disk (a frame in a dependency, or a path baked in by a build on another machine — it
        // just echoes "failed to open"), which would otherwise paint the "you are here" line onto an
        // arbitrary line of the unrelated file the user happens to be looking at. Mirrors applyInlineValues.
        EditorBuffer b = ops.bufferForPath(frame.file());
        if (b != null) {
            execHighlightBuffer = b;
            b.setExecutionLine(frame.line());
        }
        if (keepFocus != null) {
            Platform.runLater(keepFocus::requestFocus); // after openPath's own requestFocus, so the panel wins
        }
    }

    /** The Debug-panel descendant that currently owns keyboard focus (so it can be restored across an
     *  editor-focusing reveal), or {@code null} when focus is elsewhere. */
    private Node debugPanelFocusOwner() {
        var window = host.window();
        var scene = window == null ? null : window.getScene();
        Node owner = scene == null ? null : scene.getFocusOwner();
        for (Node n = owner; n != null; n = n.getParent()) {
            if (n == debugPanel) {
                return owner;
            }
        }
        return null;
    }

    private void clearExecHighlight() {
        if (execHighlightBuffer != null) {
            execHighlightBuffer.clearExecutionLine();
            execHighlightBuffer = null;
        }
    }

    // --- debug commands ----------------------------------------------------------------------------

    /** Starts a launch debug session for the active file (saving first, like Run). */
    void debugStart() {
        EditorBuffer b = host.activeBuffer();
        if (dapManager.isActive()) {
            // A session is live. If the user switched to a DIFFERENT debuggable file and the old session is
            // merely running (not paused mid-step), retarget: stop it and launch the new file. While
            // SUSPENDED the green button keeps its Continue semantics (never yank a paused session).
            boolean differentFile = b != null && b.getPath() != null && !samePath(b.getPath(), dapManager.debugFile());
            if (dapManager.state() == DapManager.State.SUSPENDED
                    || !differentFile
                    || !debugEffectiveFor(b.getLanguage())) {
                dapManager.resume(); // F5-style continue (no-op unless suspended)
                return;
            }
            dapManager.stop(); // retarget to the newly active file below
        }
        if (b == null || b.getPath() == null && !ops.saveBuffer(b)) {
            host.setStatus(tr("status.debug.saveFirst"));
            return;
        }
        String language = b.getLanguage();
        if (!debugEffectiveFor(language)) {
            host.setStatus(tr("status.debug.unavailable"));
            return;
        }
        if ((b.isDirty() || b.getPath() == null) && !ops.saveBuffer(b)) {
            return;
        }
        ops.openToolWindow();
        debugPanel.setSessionFile(b.getPath().getFileName().toString());
        // The debuggee gets the same per-file program arguments the Run feature uses.
        dapManager.setProgramArgs(ProgramArgs.tokenize(ops.programArgs(b.getPath())));
        // Re-anchor closed files' breakpoints first (off-thread) so the initial setBreakpoints arms them too.
        withClosedBreakpoints(() -> dapManager.startLaunch(b.getPath(), language, this::pickMainClass));
    }

    /** Whether two paths refer to the same file (normalized absolute comparison; null-safe). */
    private static boolean samePath(Path a, Path b) {
        return PathKeys.sameNormalized(a, b);
    }

    /** Resumes and stops at the active buffer's caret line via a one-shot temporary breakpoint. */
    void debugRunToCursor() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || dapManager.state() != DapManager.State.SUSPENDED) {
            return;
        }
        dapManager.runToCursor(b.getPath(), b.getArea().getCurrentParagraph());
    }

    /** Jump to Line: move the execution pointer to the caret line without executing in-between code.
     *  Capability-gated — debugpy supports it; java-debug/js-debug report "not supported". */
    void debugJumpToLine() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || dapManager.state() != DapManager.State.SUSPENDED) {
            return;
        }
        if (!dapManager.supportsJumpToLine()) {
            host.setStatus(tr("status.debug.jumpUnsupported"));
            return;
        }
        dapManager.jumpToLine(
                b.getPath(),
                b.getArea().getCurrentParagraph(),
                err -> host.setStatus(err.isEmpty() ? tr("status.debug.jumpNoTarget") : err));
    }

    /** Attaches to a running JVM (asks for {@code host:port}). */
    void debugAttach() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null) {
            host.setStatus(tr("status.debug.saveFirst"));
            return;
        }
        if (!debugEffective()) {
            host.setStatus(tr("status.debug.unavailable"));
            return;
        }
        host.promptText(tr("dialog.debug.attachTitle"), tr("dialog.debug.attachContent"), "localhost:5005", input -> {
            String text = input == null ? "" : input.trim();
            String hostName = "localhost";
            String portStr = text;
            int colon = text.lastIndexOf(':');
            if (colon >= 0) {
                hostName = text.substring(0, colon);
                portStr = text.substring(colon + 1);
            }
            try {
                int port = Integer.parseInt(portStr.trim());
                ops.openToolWindow();
                debugPanel.setSessionFile(b.getPath().getFileName().toString());
                String attachHost = hostName;
                withClosedBreakpoints(() -> dapManager.startAttach(b.getPath(), attachHost, port));
            } catch (NumberFormatException e) {
                host.setStatus(tr("status.debug.badAddress", text));
            }
        });
    }

    /** Main-class chooser (QuickOpen) when jdtls finds several. */
    private void pickMainClass(List<DapManager.MainClassOption> options, Consumer<DapManager.MainClassOption> chosen) {
        QuickOpen<DapManager.MainClassOption> picker = new QuickOpen<>(
                tr("debug.pickMainTitle"),
                tr("debug.pickMainPrompt"),
                () -> options,
                DapManager.MainClassOption::mainClass,
                o -> o.projectName() == null ? "" : o.projectName(),
                chosen);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Toggles a breakpoint on the active buffer's caret line. */
    void toggleBreakpointAtCaret() {
        EditorBuffer b = host.activeBuffer();
        if (b != null) {
            b.toggleBreakpoint(b.getArea().getCurrentParagraph());
        }
    }

    /**
     * Edits the caret line's breakpoint (creating one if absent): its condition, its log message — which
     * makes it a logpoint: the adapter logs and does not suspend — and whether it is enabled. All three
     * already persist, re-anchor and reach the adapter; this form is what reaches them.
     */
    void editBreakpointAtCaret() {
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            return;
        }
        int line = b.getArea().getCurrentParagraph();
        var mgr = b.getBreakpointManager();
        if (!mgr.isBreakpoint(line)) {
            b.toggleBreakpoint(line);
        }
        Breakpoint bp = mgr.get(line);
        if (bp == null) {
            return; // the line is gone (a concurrent edit) — nothing to edit
        }

        TextField condition = new TextField(bp.condition());
        condition.setPrefColumnCount(32);
        TextField logMessage = new TextField(bp.logMessage());
        logMessage.setPrefColumnCount(32);
        CheckBox enabled = new CheckBox(tr("dialog.debug.breakpointEnabled"));
        enabled.setSelected(bp.enabled());

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.add(new Label(tr("dialog.debug.conditionLabel")), 0, 0);
        grid.add(condition, 1, 0);
        grid.add(new Label(tr("dialog.debug.logMessageLabel")), 0, 1);
        grid.add(logMessage, 1, 1);
        grid.add(enabled, 1, 2);
        grid.add(note(tr("dialog.debug.logMessageHint")), 1, 3);
        GridPane.setHgrow(condition, Priority.ALWAYS);
        GridPane.setHgrow(logMessage, Priority.ALWAYS);

        OverlayInput.show(
                host.overlayHost(),
                tr("dialog.debug.breakpointTitle"),
                grid,
                condition,
                tr("dialog.ok"),
                null,
                () -> {
                    mgr.setCondition(line, condition.getText().trim());
                    mgr.setLogMessage(line, logMessage.getText().trim());
                    mgr.setEnabled(line, enabled.isSelected());
                },
                null,
                false);
    }

    /** A small muted hint label under a form field. */
    private static Label note(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-note");
        l.setWrapText(true);
        return l;
    }

    /** Toggles the "uncaught exceptions" breakpoint filter. */
    void toggleExceptionBreakpoints() {
        if (exceptionFilters.contains("uncaught")) {
            exceptionFilters.remove("uncaught");
        } else {
            exceptionFilters.add("uncaught");
        }
        dapManager.setExceptionFilters(new ArrayList<>(exceptionFilters));
        host.setStatus(
                tr(exceptionFilters.contains("uncaught") ? "status.debug.exceptionsOn" : "status.debug.exceptionsOff"));
    }

    // --- adapter pickers ---------------------------------------------------------------------------

    private static String debugAdapterLabel(String id) {
        return switch (id) {
            case "python" -> "Python";
            case "javascript" -> "JavaScript";
            default -> "Java";
        };
    }

    private boolean debugAdapterEnabled(String id) {
        var s = host.settings();
        return switch (id) {
            case "python" -> s.isPythonDebugEnabled();
            case "javascript" -> s.isJsDebugEnabled();
            default -> true;
        };
    }

    private String debugAdapterPath(String id) {
        var s = host.settings();
        return switch (id) {
            case "python" -> s.getPythonDebugCommand();
            case "javascript" -> s.getJsDebugPath();
            default -> s.getJavaDebugPluginPath();
        };
    }

    /** Picker over the python/javascript debug adapters: toggles the chosen one's enable. */
    void chooseAdapterToggle() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.debug.toggleAdapter"),
                tr("palette.setting.pick"),
                () -> List.of(DEBUG_TOGGLEABLE_ADAPTERS),
                id -> debugAdapterLabel(id) + "  —  " + tr(debugAdapterEnabled(id) ? "common.on" : "common.off"),
                id -> "",
                id -> {
                    if (id == null) {
                        return;
                    }
                    var s = host.settings();
                    boolean next = !debugAdapterEnabled(id);
                    if ("python".equals(id)) {
                        s.setPythonDebugEnabled(next);
                    } else {
                        s.setJsDebugEnabled(next);
                    }
                    host.requestSave();
                    applySupport();
                    host.syncSettingsWindow();
                    host.setStatus(
                            tr("status.settingToggled", debugAdapterLabel(id), tr(next ? "common.on" : "common.off")));
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Picker over the java/python/javascript debug adapters, then prompts for that adapter's path. */
    void chooseAdapterPath() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.debug.setAdapterPath"),
                tr("palette.setting.pick"),
                () -> List.of(DEBUG_PATH_ADAPTERS),
                DebugCoordinator::debugAdapterLabel,
                this::debugAdapterPath,
                id -> {
                    if (id == null) {
                        return;
                    }
                    host.promptText(debugAdapterLabel(id), tr("palette.setting.value"), debugAdapterPath(id), v -> {
                        String value = v.trim();
                        var s = host.settings();
                        switch (id) {
                            case "python" -> s.setPythonDebugCommand(value);
                            case "javascript" -> s.setJsDebugPath(value);
                            default -> s.setJavaDebugPluginPath(value);
                        }
                        host.requestSave();
                        applySupport();
                        host.syncSettingsWindow();
                        host.setStatus(tr("status.settingChanged", debugAdapterLabel(id), value));
                    });
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    // --- buffer wiring + panel-action delegates ----------------------------------------------------

    /** Wires the debug hooks onto a freshly-added buffer (breakpoint gutter gate + change/hover hooks +
     *  restore persisted breakpoints). Called from {@code MainController.addBuffer}. */
    void wireBuffer(EditorBuffer buffer) {
        buffer.setBreakpointsEnabled(debugSupportEnabled() && isDebuggableBuffer(buffer));
        buffer.setOnBreakpointsChanged(() -> onBreakpointsChanged(buffer));
        // Hover value tooltip while suspended: evaluate the hovered identifier in the selected frame.
        buffer.setDebugHoverEvaluator((expr, cb) -> dapManager.evaluateHover(expr, debugFrameId, cb));
    }

    /** Focuses the Debug console's evaluate field ({@code debug.evaluate} command). */
    void focusEvaluate() {
        ops.openToolWindow();
        debugPanel.focusEvaluate();
    }

    void addWatch() {
        debugPanel.addWatch();
    }

    void setSelectedValue() {
        debugPanel.setSelectedValue();
    }
}
