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
import com.editora.config.RunConfiguration;
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

        /** Opens the Run Configurations page on {@code name} — see {@code RunCoordinator.Ops}. */
        void editConfiguration(String name);

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

        /**
         * Opens (or focuses) the tab for {@code file} (the frame's file, for the execution highlight) —
         * quietly: a step that stays in the same file is not news for the status bar.
         */
        void openPath(Path file);

        /** Whether the Debug tool window is showing (it is then never closed just because the tab changed). */
        default boolean isToolWindowOpen() {
            return false;
        }

        /** The open buffer for {@code file} (canonical-tab match), or {@code null} when no tab holds it. */
        EditorBuffer bufferForPath(Path file);

        /** Runs {@code action} once an asynchronously opened buffer has received its initial document. */
        default void afterBufferLoad(EditorBuffer buffer, Runnable action) {
            action.run();
        }

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

    /**
     * The before-launch build of a debug launch. On its own service rather than the Run console's, so
     * debugging a configuration is not refused merely because another program is running there.
     */
    private final com.editora.run.RunService beforeLaunchService = new com.editora.run.RunService();

    private final BeforeLaunchStep beforeLaunch = new BeforeLaunchStep(beforeLaunchService);

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
    private final Set<EditorBuffer> pendingPersist = new LinkedHashSet<>();

    /** The frame whose line is highlighted (re-applied when its file is reopened); null unless suspended. */
    private DapModels.StackFrameInfo shownFrame;

    /** The session label of the launch in progress, re-applied once the session it replaces has ended. */
    private String sessionLabel = "";

    /** The console already belongs to this launch (its before-launch build wrote to it): do not clear again. */
    private boolean consoleStarted;

    /** What each open file's breakpoints were when last sent to the live session (cleared between sessions). */
    private final Map<Path, DapModels.FileBreakpoints> sentBreakpoints = new java.util.HashMap<>();

    /** What the live session said about each file's breakpoints, by document line (cleared between sessions). */
    private final Map<Path, Map<Integer, DapModels.BreakpointStatus>> breakpointStatus = new java.util.HashMap<>();

    /** A breakpoint condition or log message the adapter could not evaluate, and what it said about it. */
    private record EvaluationFailure(String expression, String message) {}

    /** Evaluation failures of this session, by file and document line; one ends when its expression is edited. */
    private final Map<Path, Map<Integer, EvaluationFailure>> evaluationFailures = new java.util.HashMap<>();

    /** Rejections already reported in the status bar this session, so a re-sent file does not repeat them. */
    private final Set<String> reportedRejections = new HashSet<>();

    /** Repeats the last coordinator-level start (save, before-launch build, closed-file breakpoints, launch). */
    private Runnable relaunch;

    /** {@link #relaunch} of a session attached to a JVM a build or test run started: it cannot be redone. */
    private static final Runnable ONE_SHOT_ATTACH = () -> {};

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
    /** Debug ▸ Stop: a before-launch build still running is what gets stopped; else the session. */
    void stop() {
        if (beforeLaunch.isActive()) {
            beforeLaunch.stop();
        } else {
            dapManager.stop();
        }
    }

    /**
     * Debug ▸ Restart: stops the session and starts it again <em>the way it was started</em> — saving the
     * edited file, running the configuration's before-launch build, re-anchoring closed-file breakpoints.
     * Re-running only the adapter launch debugged the previous code against the edited buffer's breakpoint
     * lines. An attach has nothing to redo and is simply re-attached — except one a build or test run
     * opened ({@link #attachToPort}): that JVM stops listening once it is resumed, so re-attaching would
     * only let it run to its end and leave a session with no debuggee showing "Running".
     */
    void restart() {
        Runnable again = relaunch;
        if (again == ONE_SHOT_ATTACH) {
            host.setStatus(tr("status.debug.cannotRestartAttached"));
            return;
        }
        if (again == null) {
            dapManager.restart();
            return;
        }
        dapManager.stop();
        again.run();
    }

    /** Whether Restart can do anything for the session there is: not for one attached to a build or test run. */
    boolean restartAvailable() {
        return relaunch != ONE_SHOT_ATTACH;
    }

    /** Window close: the before-launch build must not outlive the window that started it. */
    void shutdown() {
        persistDebounce.stop();
        flushPendingPersist(); // a breakpoint moved by an edit in the last 300 ms is not lost with the window
        beforeLaunchService.shutdown();
    }

    /** A session is starting, running, paused or being built for — the states Stop / Restart act on. */
    boolean sessionLive() {
        return dapManager.state() != DapManager.State.INACTIVE || beforeLaunch.isActive();
    }

    /** Names the session being launched in the panel; survives the end of the session it replaces. */
    private void nameSession(String label) {
        sessionLabel = label == null ? "" : label;
        debugPanel.setSessionFile(sessionLabel);
    }

    /** Streams a before-launch build into the Debug console and keeps Stop usable while it runs. */
    private BeforeLaunchStep.Console beforeLaunchConsole() {
        return new BeforeLaunchStep.Console() {
            @Override
            public void started(String commandLine) {
                ops.openToolWindow();
                debugPanel.setPreparing(true);
                debugPanel.clearConsole(); // a new launch: the previous session's output is not this one's
                consoleStarted = true;
                debugPanel.appendOutput("$ " + commandLine + "\n", "console");
            }

            @Override
            public void output(String line, boolean stderr) {
                debugPanel.appendOutput(line + "\n", stderr ? "stderr" : "stdout");
            }

            @Override
            public void ended(int code, String launchError) {
                debugPanel.setPreparing(false);
                consoleStarted = code == 0 && launchError == null; // a failed build is not followed by a session
                if (launchError != null) {
                    debugPanel.appendOutput(launchError + "\n", "stderr");
                }
            }
        };
    }

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

    /**
     * Re-reads whether the running jdtls itself advertises the java-debug commands and re-gates. Some
     * distributions (e.g. Homebrew's jdtls) ship and auto-load the plugin, so {@code locate()} finds no jar to
     * inject yet debugging works — capabilities are only known once a server finishes {@code initialize}, so
     * the controller calls this from the server-ready event (#711). Light: no bundle push, no restart.
     */
    void refreshJavaDebugAvailability() {
        boolean before = dapManager.isLanguageAvailable("java");
        dapManager.setServerProvidesJavaDebug(lspManager.javaDebugCommandsAvailable());
        if (dapManager.isLanguageAvailable("java") != before) {
            applyGating(); // the breakpoint gutter + Debug window just became (un)available
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
     * "Live" includes the seconds a session takes to start and its before-launch build, when there is no
     * adapter connection yet; and a window that is open stays open — making it unavailable closes it, which
     * hid the build output, the only Stop button and a finished session's last output behind a tab switch.
     */
    void updateDebugAvailability() {
        boolean available = debugSupportEnabled()
                && (isDebuggableBuffer(host.activeBuffer()) || sessionLive() || ops.isToolWindowOpen());
        ops.setToolWindowAvailable(available);
    }

    /** Whether a buffer's language has a registered debug adapter (java/python/javascript). */
    boolean isDebuggableBuffer(EditorBuffer b) {
        return b != null && host.isLocalBuffer(b) && DapServerRegistry.isDebuggable(b.getLanguage());
    }

    // --- breakpoints -------------------------------------------------------------------------------

    /** Persists a buffer's breakpoints + (if a session is live) re-sends that file's set to the adapter. */
    void onBreakpointsChanged(EditorBuffer buffer) {
        if (buffer.getBreakpointManager().isEditDriven()) {
            schedulePersistBreakpoints(buffer); // debounced FS write (off the per-newline hot path)
        } else {
            pendingPersist.remove(buffer); // the user's own toggle / edit: written now, never left to a timer
            persistBreakpoints(buffer);
        }
        if (buffer.getPath() != null && dapManager.isActive()) {
            // Adapter stays current immediately — but only when what it is told actually changed: editing
            // the text of a breakpoint's line is a change to persist, not one to put on the wire.
            DapModels.FileBreakpoints now = fileBreakpoints(buffer);
            if (!now.equals(sentBreakpoints.put(buffer.getPath(), now))) {
                dapManager.updateBreakpoints(now);
            }
            showBreakpointStates(buffer); // a new breakpoint is unverified until the adapter answers for it
        }
    }

    // --- what the live session says about the breakpoints ------------------------------------------

    /**
     * Paints {@code buffer}'s breakpoints as the live session sees them — hollow while the adapter has not
     * bound one, flagged when it refused one — or as plain breakpoints when there is no session.
     */
    private void showBreakpointStates(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null || dapManager.state() == DapManager.State.INACTIVE) {
            buffer.getBreakpointManager().setLive(null);
            return;
        }
        Map<Integer, DapModels.BreakpointStatus> known = breakpointStatus.getOrDefault(file, Map.of());
        Map<Integer, EvaluationFailure> failures = evaluationFailures.get(file);
        Map<Integer, BreakpointManager.Live> live = new java.util.HashMap<>();
        for (Breakpoint bp : buffer.getBreakpointManager().documentSnapshot()) {
            EvaluationFailure failure = failures == null ? null : failures.get(bp.line());
            if (failure != null
                    && !failure.expression().equals(bp.condition())
                    && !failure.expression().equals(bp.logMessage())) {
                failures.remove(bp.line()); // the expression it was about has been edited
                failure = null;
            }
            live.put(
                    bp.line(),
                    DebugFeedback.live(
                            known.get(bp.line()),
                            failure == null ? null : failure.message(),
                            tr("debug.breakpoint.pending"),
                            tr("debug.breakpoint.rejected")));
        }
        buffer.getBreakpointManager().setLive(live);
    }

    /** The adapter answered for {@code file}'s breakpoints, or changed its mind about some of them. */
    private void breakpointStatusChanged(Path file, List<DapModels.BreakpointStatus> statuses, boolean whole) {
        Map<Integer, DapModels.BreakpointStatus> known =
                breakpointStatus.computeIfAbsent(file, f -> new java.util.HashMap<>());
        if (whole) {
            known.clear();
        }
        EditorBuffer buffer = ops.bufferForPath(file);
        DapModels.FileBreakpoints sent = sentBreakpoints.get(file);
        for (DapModels.BreakpointStatus status : statuses) {
            known.put(status.line(), status);
            boolean armed = sent != null && sent.breakpoints().stream().anyMatch(lb -> lb.line() == status.line());
            if (!armed) {
                continue; // a run-to-cursor stop, or an answer about a set that has since been replaced
            }
            if (status.failed() && reportedRejections.add(file + ":" + status.line() + ":" + status.message())) {
                host.setStatus(tr(
                        "status.debug.breakpointInvalid",
                        file.getFileName() + ":" + (status.line() + 1),
                        status.message().isEmpty() ? tr("debug.breakpoint.rejected") : status.message()));
            }
            // The adapter bound it to another line (the next one with code): the breakpoint follows, so the
            // dot sits where the program will actually stop. Moving it re-sends the file, now at that line.
            if (status.verified()
                    && status.actualLine() >= 0
                    && status.actualLine() != status.line()
                    && buffer != null
                    && buffer.getBreakpointManager().moveDocumentLine(status.line(), status.actualLine())) {
                known.remove(status.line());
                known.put(
                        status.actualLine(),
                        new DapModels.BreakpointStatus(status.actualLine(), true, false, "", status.actualLine()));
                host.setStatus(tr("status.debug.breakpointMoved", status.line() + 1, status.actualLine() + 1));
            }
        }
        if (buffer != null) {
            showBreakpointStates(buffer);
        }
    }

    /**
     * A notice from the adapter. java-debug reports a breakpoint condition or logpoint message it could not
     * evaluate this way — and then stops on every hit — so the breakpoints carrying that expression are
     * flagged, and the text is shown in the console and the status bar either way.
     */
    private void adapterNotice(String message, boolean error) {
        debugPanel.appendOutput(message + System.lineSeparator(), error ? "stderr" : "console");
        host.setStatus(tr("status.debug.error", message));
        host.forEachBuffer(b -> {
            if (b.getPath() == null) {
                return;
            }
            boolean flagged = false;
            for (Breakpoint bp : b.getBreakpointManager().documentSnapshot()) {
                String expression = DebugFeedback.noticeNames(message, bp.condition())
                        ? bp.condition()
                        : DebugFeedback.noticeNames(message, bp.logMessage()) ? bp.logMessage() : null;
                if (bp.enabled() && expression != null) {
                    evaluationFailures
                            .computeIfAbsent(b.getPath(), f -> new java.util.HashMap<>())
                            .put(bp.line(), new EvaluationFailure(expression, message));
                    flagged = true;
                }
            }
            if (flagged) {
                showBreakpointStates(b);
            }
        });
    }

    /**
     * Coalesces the synchronous breakpoints.json write fired per line-shifting edit (holding Enter above a
     * breakpoint) so it lands once, ~300 ms after editing settles, instead of blocking the FX thread per newline.
     * reanchor-on-open recovers indices lost to a crash before the write. (#551)
     */
    private void schedulePersistBreakpoints(EditorBuffer buffer) {
        // A set, not one buffer: an edit that shifts lines in several files (a rename refactoring, format on
        // save-all) reports each of them inside one debounce window, and every one of them must be written.
        pendingPersist.add(buffer);
        persistDebounce.playFromStart();
    }

    private void flushPendingPersist() {
        List<EditorBuffer> pending = List.copyOf(pendingPersist);
        pendingPersist.clear();
        pending.forEach(this::persistBreakpoints);
    }

    /**
     * {@code buffer} now belongs to another file (renamed, moved with its folder, or saved under a new name):
     * its breakpoints follow it. They are keyed by path, so without this they stayed under the old one — gone
     * from the file on its next open, and still sent to the adapter for a path that may no longer exist.
     */
    void bufferPathChanged(EditorBuffer buffer, Path oldPath) {
        Path newPath = buffer.getPath();
        buffer.setBreakpointsEnabled(debugSupportEnabled() && isDebuggableBuffer(buffer)); // the language may differ
        if (newPath == null || newPath.equals(oldPath)) {
            return;
        }
        var map = ops.breakpointMap();
        List<Breakpoint> stored = null;
        if (oldPath != null && !Files.exists(oldPath)) {
            stored = map.remove(oldPath.toString()); // renamed away; a Save As copy leaves the original its own
        } else if (oldPath != null) {
            stored = map.get(oldPath.toString());
        }
        pendingPersist.remove(buffer);
        if (buffer.isNarrowed()) {
            if (stored != null) {
                map.put(newPath.toString(), new ArrayList<>(stored)); // region-relative lines cannot be snapshotted
            }
            ops.saveBreakpoints();
        } else {
            persistBreakpoints(buffer); // writes the file, also when the buffer has none (the old key is gone)
        }
        if (dapManager.isActive()) {
            if (oldPath != null && sentBreakpoints.remove(oldPath) != null && !Files.exists(oldPath)) {
                dapManager.updateBreakpoints(new DapModels.FileBreakpoints(oldPath, List.of()));
            }
            DapModels.FileBreakpoints now = fileBreakpoints(buffer);
            sentBreakpoints.put(newPath, now);
            dapManager.updateBreakpoints(now);
        }
    }

    private void persistBreakpoints(EditorBuffer buffer) {
        if (buffer.isNarrowed()) {
            return; // region-relative line numbers while narrowed — see BookmarkCoordinator.persistBookmarks
        }
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
        showBreakpointStates(buffer); // opened during a session: it shows what the adapter already said
        reshowFrameIn(buffer);
    }

    /**
     * {@code buffer} was just (re)opened: if it is the file of the frame on show, it gets the execution line
     * and inline values back — the buffer that carried them was disposed with its tab.
     */
    private void reshowFrameIn(EditorBuffer buffer) {
        DapModels.StackFrameInfo frame = shownFrame;
        if (frame == null
                || frame.file() == null
                || dapManager.state() != DapManager.State.SUSPENDED
                || buffer == execHighlightBuffer
                || !samePath(frame.file(), buffer.getPath())) {
            return;
        }
        clearExecHighlight();
        paintExecutionLine(buffer, frame);
        dapManager.scopes(frame.id(), scopes -> applyInlineValues(frame, scopes));
    }

    /**
     * The enabled breakpoints of {@code buffer} as a DAP {@code FileBreakpoints} (empty list if none), in
     * whole-file lines even while the buffer is narrowed.
     */
    private DapModels.FileBreakpoints fileBreakpoints(EditorBuffer buffer) {
        List<DapModels.LineBreakpoint> lines = new ArrayList<>();
        for (Breakpoint bp : buffer.getBreakpointManager().documentSnapshot()) {
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
            sentBreakpoints.put(b.getPath(), fb);
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
                if (state == DapManager.State.STARTING) {
                    if (!consoleStarted) {
                        debugPanel.clearConsole(); // each launch starts with its own output
                    }
                    consoleStarted = false;
                }
                debugPanel.setRestartBlocked(restartAvailable() ? null : tr("status.debug.cannotRestartAttached"));
                debugPanel.setState(state);
                if (state == DapManager.State.STARTING) {
                    nameSession(sessionLabel); // ending the replaced session cleared it
                }
                if (state != DapManager.State.SUSPENDED) {
                    shownFrame = null;
                    debugPanel.setStopReason(null);
                }
                updateDebugStatus(state);
                if (state == DapManager.State.INACTIVE || state == DapManager.State.STARTING) {
                    sentBreakpoints.clear(); // the next session is told everything afresh
                    breakpointStatus.clear(); // and what the last one said about them no longer holds
                    evaluationFailures.clear();
                    reportedRejections.clear();
                    host.forEachBuffer(DebugCoordinator.this::showBreakpointStates);
                }
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
                debugPanel.setStopReason(reason);
                // Selects the first frame that has source to show → selectFrame highlights + loads vars.
                int shown = firstFrameWithSource(frames, DebugCoordinator.this::hasSource);
                debugPanel.setCallStack(frames, shown);
                if (shown > 0) {
                    host.setStatus(tr(
                            "status.debug.stoppedWithoutSource", frames.get(0).name()));
                }
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

            @Override
            public void onBreakpointStatus(Path file, List<DapModels.BreakpointStatus> statuses, boolean whole) {
                breakpointStatusChanged(file, statuses, whole);
            }

            @Override
            public void onNotice(String message, boolean error) {
                adapterNotice(message, error);
            }

            @Override
            public void onExceptionInfo(int threadId, DapModels.ExceptionInfo info) {
                // Which exception: the one thing an exception stop has to say, and the stop event does not.
                debugPanel.setStoppedException(DebugFeedback.exceptionSummary(info, true));
                host.setStatus(tr("status.debug.stoppedOnException", DebugFeedback.exceptionSummary(info, false)));
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
                int previous = dapManager.currentThreadId();
                dapManager.selectThread(threadId, frames -> {
                    if (!frames.isEmpty() || threadId == previous) {
                        debugPanel.setCallStack(frames, firstFrameWithSource(frames, DebugCoordinator.this::hasSource));
                        return;
                    }
                    // A thread that is not suspended has no stack to inspect. Stay on the stopped one: showing
                    // an empty stack beside the other thread's variables and execution line — with Step now
                    // aimed at the running thread — said nothing about why.
                    host.setStatus(tr("status.debug.threadRunning"));
                    debugPanel.showThread(previous);
                    dapManager.selectThread(previous, back -> {
                        if (back.isEmpty()) { // it has been resumed meanwhile: nothing is stopped to show
                            debugPanel.setCallStack(back, 0);
                            debugPanel.setScopes(List.of());
                            clearExecHighlight();
                            clearDebugEditorSurfaces();
                        }
                    });
                });
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
                DebugCoordinator.this.stop();
            }

            @Override
            public void restart() {
                DebugCoordinator.this.restart();
            }

            @Override
            public void selectFrame(DapModels.StackFrameInfo frame) {
                if (dapManager.state() != DapManager.State.SUSPENDED) {
                    return; // a frame of the previous stop, clicked while the program runs: there is no "here"
                }
                debugFrameId = frame.id(); // the hover evaluator's frame context
                shownFrame = frame;
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
            public void loadChildrenPage(
                    int ref, boolean indexed, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
                dapManager.variables(ref, indexed ? "indexed" : "named", start, count, cb);
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
                debugValuesBuffer.setInlineValues(null, -1); // frame moved to another file
                debugValuesBuffer.setDebugHoverActive(false);
            }
            debugValuesBuffer = b;
            // Region-relative in a narrowed buffer, like the execution line.
            b.setInlineValues(values, frame.line() - b.getBreakpointManager().regionFirstLine());
            b.setDebugHoverActive(true);
        });
    }

    private void clearDebugEditorSurfaces() {
        debugFrameId = -1;
        if (debugValuesBuffer != null) {
            debugValuesBuffer.setInlineValues(null, -1);
            debugValuesBuffer.setDebugHoverActive(false);
            debugValuesBuffer = null;
        }
    }

    /**
     * Whether {@code frame}'s source can be shown: a file with an open tab, or one that exists on disk. A frame
     * in the JDK or a dependency names a {@code jdt:} URI, a jar entry, {@code <frozen importlib>} or a path
     * from the machine that built it — none of which can be opened as a tab.
     */
    private boolean hasSource(DapModels.StackFrameInfo frame) {
        if (frame == null || frame.file() == null) {
            return false;
        }
        try {
            return ops.bufferForPath(frame.file()) != null || Files.isRegularFile(frame.file());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** How many frames from the top a stop looks for one with source before settling for the top frame. */
    private static final int MAX_SOURCE_SEARCH = 64;

    /**
     * Pure: the frame a new stop selects — the topmost one whose source can be shown, so pausing a thread that
     * sits in {@code Thread.sleep} lands on the caller's line rather than nowhere; the top frame when none can.
     */
    static int firstFrameWithSource(List<DapModels.StackFrameInfo> frames, Predicate<DapModels.StackFrameInfo> has) {
        for (int i = 0; i < frames.size() && i < MAX_SOURCE_SEARCH; i++) {
            if (has.test(frames.get(i))) {
                return i;
            }
        }
        return 0;
    }

    /** Opens the frame's file and paints the execution-line highlight there. */
    private void highlightFrame(DapModels.StackFrameInfo frame) {
        clearExecHighlight();
        if (frame == null) {
            return;
        }
        if (!hasSource(frame)) {
            // Asking for it anyway opened a loading tab, failed, removed the tab again and reported "Failed to
            // open" as an error — on every stop and step inside library code. Say what is the case instead;
            // the frame stays selected with its variables, and the other frames stay a click away.
            host.setStatus(tr("status.debug.noSource", frame.name()));
            return;
        }
        // Revealing the line focuses the editor (openPath calls requestFocus on the buffer). When the user
        // is driving the session from the Debug panel — the single-key step shortcuts — that yanks focus to
        // the code after every step, so the next key press is lost and they must re-focus the panel each
        // time. Preserve the panel's focus across the reveal in that case only; a fresh breakpoint hit while
        // editing (focus not in the panel) still lands in the code at the stopped line, as before.
        Node keepFocus = debugPanelFocusOwner();
        ops.openPath(frame.file()); // opens or focuses the tab
        // Take the frame's OWN buffer, not whatever is active — mirrors applyInlineValues.
        EditorBuffer b = ops.bufferForPath(frame.file());
        if (b != null) {
            paintExecutionLine(b, frame);
        }
        if (keepFocus != null) {
            Platform.runLater(keepFocus::requestFocus); // after openPath's own requestFocus, so the panel wins
        }
    }

    private void paintExecutionLine(EditorBuffer b, DapModels.StackFrameInfo frame) {
        execHighlightBuffer = b;
        ops.afterBufferLoad(b, () -> {
            // The user may resume or select another frame while this file is still loading. Never let
            // that obsolete completion repaint an execution marker that clearExecHighlight removed.
            if (execHighlightBuffer == b) {
                // A narrowed buffer shows only its region: the frame's file line is region-relative
                // there (a stop outside the region has no line to show and paints nothing).
                b.setExecutionLine(frame.line() - b.getBreakpointManager().regionFirstLine());
            }
        });
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
                    || dapManager.isStepping() // a step in flight reads RUNNING, but it is the paused session
                    || !differentFile
                    || !debugEffectiveFor(b.getLanguage())) {
                dapManager.resume(); // F5-style continue (no-op unless suspended)
                return;
            }
            dapManager.stop(); // retarget to the newly active file below
        }
        launchBuffer(b);
    }

    /** Saves {@code b} and launches a debug session for it; also what Restart repeats for such a session. */
    private void launchBuffer(EditorBuffer b) {
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
        Integer shebangSource = b.getShebangJavaSource();
        if ("java".equals(language) && b.isCompactSource() && shebangSource != null && shebangSource < 25) {
            host.setStatus(tr("status.debug.needSource25", shebangSource));
            return;
        }
        ops.openToolWindow();
        nameSession(b.getPath().getFileName().toString());
        // The debuggee gets the same per-file program arguments the Run feature uses.
        dapManager.setProgramArgs(ProgramArgs.tokenize(ops.programArgs(b.getPath())));
        dapManager.setVmArgs(""); // no user VM args on a plain debug
        // Re-anchor closed files' breakpoints first (off-thread) so the initial setBreakpoints arms them too.
        Path projectRoot = "java".equals(language) ? JavaProjectRoot.find(b.getPath()) : null;
        boolean compactSource = "java".equals(language) && b.isCompactSource();
        String jdkHome = !"java".equals(language)
                ? ""
                : compactSource ? host.settings().getMavenJdkHome() : configuredJdkHome(projectRoot, null);
        String javaExec = com.editora.run.JdkToolchain.javaExecutable(jdkHome);
        dapManager.setEnv(
                com.editora.run.JdkToolchain.environment(jdkHome, com.editora.process.ProcessRunner.augmentedPath()));
        Path launched = b.getPath();
        relaunch = () -> relaunchFor(launched, this::launchBuffer);
        withClosedBreakpoints(() -> {
            if (compactSource && shebangSource != null) {
                dapManager.startCompactShebang(b.getPath(), shebangSource, javaExec);
            } else if (compactSource && b.getPath().getFileName().toString().endsWith(".java")) {
                dapManager.startCompactSource(b.getPath(), javaExec);
            } else {
                dapManager.startLaunch(b.getPath(), language, this::pickMainClass, javaExec, projectRoot);
            }
        });
    }

    /** Restart of a session started from {@code file}'s tab: repeat it there, or plainly if the tab is gone. */
    private void relaunchFor(Path file, Consumer<EditorBuffer> launch) {
        EditorBuffer b = ops.bufferForPath(file);
        if (b != null) {
            launch.accept(b);
        } else {
            dapManager.restart();
        }
    }

    /**
     * {@code debug.mainClass}: debug an arbitrary main class in the active file's Maven/Gradle project (not
     * just the active file's own). Requires a Java file open in a Maven/Gradle project to route jdtls; jdtls
     * enumerates every project main class, the user picks one (when several), and it launches with the project
     * root as the working directory.
     */
    void debugMainClass() {
        startMainClassDebug(null);
    }

    /** Debugs a specific main class by fully-qualified name (the gutter ▶ / editor menu on a {@code main}). */
    void debugMainClassNamed(String fqn) {
        startMainClassDebug(fqn);
    }

    /**
     * Debugs a saved {@link RunConfiguration} — its main class, program args, VM args, environment and
     * working directory, and its before-launch step.
     *
     * <p>Java only: the script types run but do not debug yet.
     */
    void debugConfig(RunConfiguration cfg) {
        if (!cfg.isJava()) {
            // Script types run but do not debug yet. Say so plainly: falling through would resolve a Java
            // main class that a Python configuration does not have and report a confusing Java error.
            host.setStatus(tr("status.debug.configTypeUnsupported", cfg.type()));
            return;
        }
        // Same guard as the Run path: a blank main class is an internal NPE from jdtls, not an error.
        if (cfg.missingMainClass()) {
            host.setStatus(tr("status.run.configNeedsMainClass", cfg.name()));
            ops.editConfiguration(cfg.name()); // take them to the field that would make it run
            return;
        }
        if (cfg.mainClassLooksLikeAFile()) { // see the Run path: an empty classpath, not an error
            host.setStatus(tr("status.run.mainClassIsAFile", cfg.mainClass()));
            ops.editConfiguration(cfg.name());
            return;
        }
        if (!debugEffectiveFor("java")) {
            host.setStatus(tr("status.debug.unavailable"));
            return;
        }
        // Same reasoning as RunCoordinator.runConfig: a named configuration must not depend on which tab is
        // in front, only on a Java file being open somewhere in its project.
        Path routing = RunCoordinator.routingFor(host, cfg);
        if (routing == null) {
            host.setStatus(tr("status.run.configNeedsJavaFile"));
            return;
        }
        Path root = JavaProjectRoot.find(routing);
        if (root == null) {
            host.setStatus(tr("status.debug.noProject"));
            return;
        }
        EditorBuffer b = host.activeBuffer();
        if (b != null && b.isDirty() && host.isLocalBuffer(b) && !ops.saveBuffer(b)) {
            return; // save whatever the user was editing before launching, as before
        }
        Path cwd = cfg.workingDir().isBlank() ? root : Path.of(cfg.workingDir());
        String jdkHome = configuredJdkHome(root, cfg);
        String javaExec = com.editora.run.JdkToolchain.javaExecutable(jdkHome);
        java.util.LinkedHashMap<String, String> launchEnv = new java.util.LinkedHashMap<>(
                com.editora.run.JdkToolchain.environment(jdkHome, com.editora.process.ProcessRunner.augmentedPath()));
        launchEnv.putAll(com.editora.run.EnvVars.parse(cfg.env()));
        // The same gate the Run path has always had: a failed build aborts the launch rather than debugging
        // the previous class files, where every breakpoint would sit on a stale line number.
        //
        // Placed after the guards above rather than around the whole method — unlike Run, which wraps its
        // dispatch — because those guards reject a configuration that cannot launch at all (a script type, a
        // blank main class), and spending a multi-minute build on one before saying so is worse than not
        // building.
        if (beforeLaunch.isActive()) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        relaunch = () -> debugConfig(cfg);
        beforeLaunch.run(
                host,
                cfg,
                cwd,
                com.editora.run.JdkToolchain.environment(jdkHome, com.editora.process.ProcessRunner.augmentedPath()),
                beforeLaunchConsole(),
                () -> {
                    // routing may be a background tab whose server start was deferred; open it on jdtls first, or the
                    // resolve below comes back "no language server for file" while jdtls is running perfectly.
                    lsp.ensureManaged(routing);
                    dapManager.resolveMainClasses(routing, options -> {
                        DapManager.MainClassOption match = options.stream()
                                .filter(o -> cfg.mainClass().equals(o.className()))
                                .findFirst()
                                .orElse(null);
                        if (match == null) {
                            host.setStatus(tr("status.debug.noMainClass"));
                            return;
                        }
                        ops.openToolWindow();
                        nameSession(shortName(match.mainClass()));
                        dapManager.setProgramArgs(ProgramArgs.tokenize(cfg.args()));
                        dapManager.setVmArgs(cfg.vmArgs());
                        dapManager.setEnv(launchEnv);
                        withClosedBreakpoints(() -> dapManager.startLaunchMainClass(routing, match, cwd, javaExec));
                    });
                });
    }

    private void startMainClassDebug(String targetFqn) {
        startMainClassDebug(host.activeBuffer(), targetFqn);
    }

    private void startMainClassDebug(EditorBuffer b, String targetFqn) {
        if (!debugEffectiveFor("java")) {
            host.setStatus(tr("status.debug.unavailable"));
            return;
        }
        if (b == null || b.getPath() == null || !host.isLocalBuffer(b) || !"java".equals(b.getLanguage())) {
            host.setStatus(tr("status.debug.needJavaFile"));
            return;
        }
        Path routing = b.getPath();
        Path root = JavaProjectRoot.find(routing);
        if (root == null) {
            host.setStatus(tr("status.debug.noProject"));
            return;
        }
        if (b.isDirty() && !ops.saveBuffer(b)) {
            return;
        }
        dapManager.resolveMainClasses(routing, options -> {
            if (options.isEmpty()) {
                host.setStatus(tr("status.debug.noMainClass"));
                return;
            }
            Consumer<DapManager.MainClassOption> launch = opt -> {
                if (opt == null) {
                    return;
                }
                ops.openToolWindow();
                nameSession(shortName(opt.mainClass()));
                dapManager.setProgramArgs(ProgramArgs.tokenize(programArgsForMain(opt)));
                dapManager.setVmArgs(""); // the gutter/command debug carries no VM args/env
                dapManager.setEnv(configuredJdkEnvironment(root, null));
                lsp.ensureManaged(routing); // see above
                relaunch = () -> relaunchFor(routing, again -> startMainClassDebug(again, opt.className()));
                String javaExec = configuredJavaExecutable(root, null);
                withClosedBreakpoints(() -> dapManager.startLaunchMainClass(routing, opt, root, javaExec));
            };
            if (targetFqn != null) {
                DapManager.MainClassOption match = options.stream()
                        .filter(o -> targetFqn.equals(o.className()))
                        .findFirst()
                        .orElse(null);
                if (match == null) {
                    host.setStatus(tr("status.debug.noMainClass"));
                    return;
                }
                launch.accept(match);
            } else if (options.size() == 1) {
                launch.accept(options.get(0));
            } else {
                pickMainClass(options, launch);
            }
        });
    }

    /** Per-main-class program args, reusing the per-file store keyed by the main class's own source file. */
    private String programArgsForMain(DapManager.MainClassOption opt) {
        if (opt.filePath() == null || opt.filePath().isBlank()) {
            return "";
        }
        try {
            return ops.programArgs(Path.of(opt.filePath()));
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The default JDK for a loose file or Maven project; a saved configuration may override it. */
    private String configuredJdkHome(Path root, RunConfiguration cfg) {
        if (root != null && !java.nio.file.Files.isRegularFile(root.resolve("pom.xml"))) {
            return "";
        }
        return com.editora.run.JdkToolchain.effectiveHome(
                cfg == null ? "" : cfg.jdkHome(), host.settings().getMavenJdkHome());
    }

    private String configuredJavaExecutable(Path root, RunConfiguration cfg) {
        return com.editora.run.JdkToolchain.javaExecutable(configuredJdkHome(root, cfg));
    }

    private java.util.Map<String, String> configuredJdkEnvironment(Path root, RunConfiguration cfg) {
        return com.editora.run.JdkToolchain.environment(
                configuredJdkHome(root, cfg), com.editora.process.ProcessRunner.augmentedPath());
    }

    /** The simple class name of a fully-qualified main class (for the session label). */
    private static String shortName(String fqn) {
        if (fqn == null) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
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
        dapManager.runToCursor(b.getPath(), fileLineAtCaret(b));
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
                fileLineAtCaret(b),
                err -> host.setStatus(err.isEmpty() ? tr("status.debug.jumpNoTarget") : err));
    }

    /** The caret's line in the whole file (a narrowed buffer's paragraphs are region-relative). */
    private static int fileLineAtCaret(EditorBuffer b) {
        return b.getFocusedArea().getCurrentParagraph()
                + b.getBreakpointManager().regionFirstLine();
    }

    /**
     * Attaches to an already-suspended JVM on {@code host:port} without prompting — used by the Test Results
     * "Debug Test" action, where Surefire/Gradle forked the test JVM suspended and printed its JDWP port.
     * {@code anchorFile} names the debug session and anchors breakpoint resolution.
     */
    void attachToPort(Path anchorFile, String attachHost, int port) {
        if (!debugEffective()) {
            host.setStatus(tr("status.debug.unavailable"));
            return;
        }
        ops.openToolWindow();
        nameSession(anchorFile == null ? "" : anchorFile.getFileName().toString());
        // The anchor names the session, but the adapter is started through jdtls, which only answers for a
        // document it has open: a test class found on disk (Debug Test with no tab for it) has no session.
        Path routing = attachRouting(anchorFile);
        lsp.ensureManaged(routing); // an open tab whose server start was deferred
        relaunch = ONE_SHOT_ATTACH; // the run that owns this JVM has to be started again instead
        withClosedBreakpoints(() -> dapManager.startAttach(routing, attachHost, port));
    }

    /** The open Java file an attach anchored at {@code anchor} is routed through — see {@link #attachRouting}. */
    private Path attachRouting(Path anchor) {
        List<Path> openJava = new ArrayList<>();
        host.forEachBuffer(b -> {
            if (b.getPath() != null && host.isLocalBuffer(b) && "java".equals(b.getLanguage())) {
                openJava.add(b.getPath());
            }
        });
        return attachRouting(anchor, openJava, JavaProjectRoot.find(anchor), host::isLspManaged);
    }

    /**
     * Pure: the file whose jdtls session starts the debug adapter for an attach. The anchor itself when it is
     * open in a tab; otherwise an open Java file of the anchor's project (one already on the server first),
     * else any open Java file already on the server, else the anchor — which then fails with the precise
     * "no language server for file" rather than attaching through an unrelated project.
     */
    static Path attachRouting(Path anchor, List<Path> openJavaFiles, Path projectRoot, Predicate<Path> managed) {
        if (anchor == null) {
            return openJavaFiles.stream().filter(managed).findFirst().orElse(null);
        }
        Path sameProject = null;
        Path anyManaged = null;
        for (Path open : openJavaFiles) {
            if (PathKeys.sameNormalized(open, anchor)) {
                return anchor;
            }
            boolean onServer = managed.test(open);
            if (projectRoot != null && open.toAbsolutePath().normalize().startsWith(projectRoot)) {
                if (sameProject == null || (onServer && !managed.test(sameProject))) {
                    sameProject = open;
                }
            } else if (onServer && anyManaged == null) {
                anyManaged = open;
            }
        }
        return sameProject != null ? sameProject : anyManaged != null ? anyManaged : anchor;
    }

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
                nameSession(b.getPath().getFileName().toString());
                String attachHost = hostName;
                relaunch = null; // an attach is re-attached as it was
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
        EditorBuffer b = breakpointBuffer();
        if (b != null) {
            b.toggleBreakpoint(b.getFocusedArea().getCurrentParagraph());
        }
    }

    /**
     * The active buffer if breakpoints can be set in it, else {@code null} after saying why. A file with no
     * debug adapter has no breakpoint gutter: a breakpoint toggled there by key was invisible, could only be
     * removed by pressing the key again on the same line, and was still sent to the adapter in every session.
     */
    private EditorBuffer breakpointBuffer() {
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            return null;
        }
        if (!debugSupportEnabled()) {
            host.setStatus(tr("statusbar.tip.debugDisabled"));
            return null;
        }
        if (!isDebuggableBuffer(b)) {
            host.setStatus(tr("status.debug.noBreakpointsHere"));
            return null;
        }
        return b;
    }

    /**
     * Edits the caret line's breakpoint (creating one if absent): its condition, its log message — which
     * makes it a logpoint: the adapter logs and does not suspend — and whether it is enabled. All three
     * already persist, re-anchor and reach the adapter; this form is what reaches them.
     */
    void editBreakpointAtCaret() {
        EditorBuffer b = breakpointBuffer();
        if (b == null) {
            return;
        }
        int line = b.getFocusedArea().getCurrentParagraph();
        var mgr = b.getBreakpointManager();
        // A line without a breakpoint gets one only when the form is accepted: creating it up front left it
        // behind — saved, and sent to a live session — when the form was cancelled.
        boolean existed = mgr.isBreakpoint(line);
        Breakpoint bp = existed
                ? mgr.get(line)
                : Breakpoint.plain(
                        line, b.getArea().getParagraph(line).getText().strip());

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
                    if (!existed && !mgr.isBreakpoint(line)) {
                        b.toggleBreakpoint(line);
                    }
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
