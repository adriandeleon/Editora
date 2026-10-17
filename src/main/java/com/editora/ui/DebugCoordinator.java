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
        /** Shows the Debug window without moving keyboard focus into it (review C2). */
        void openToolWindow();

        /** The active keymap's chord for {@code commandId}, formatted, or null when it is unbound. */
        default String chordFor(String commandId) {
            return null;
        }

        /** Debug Request on the active {@code .http} file: its scripts under the debugger (HTTP Client). */
        default void debugHttpRequest() {}

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

        /** This window's saved run configurations (none outside a project window). */
        default List<RunConfiguration> runConfigurations() {
            return List.of();
        }

        /** This window's project root (null: none): a Python program's venv and {@code PYTHONPATH}. */
        default Path projectRoot() {
            return null;
        }

        /** The Python interpreter picked for this window's project ("" when none was). */
        default String pythonInterpreterChoice() {
            return "";
        }

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

        /**
         * Opens the read-only source jdtls has for {@code uri} (a {@code jdt://} class file) at {@code line0},
         * routed through {@code anchor}'s language server, and hands the tab to {@code opened}.
         */
        default void openLibraryFrame(Path anchor, String uri, int line0, Consumer<EditorBuffer> opened) {}

        /** The persisted exception-breakpoint choices, per debug-adapter language (workspace state). */
        default Map<String, List<String>> debugExceptionFilters() {
            return Map.of();
        }

        /** Persists the exception-breakpoint choices (workspace state + save). */
        default void persistDebugExceptionFilters(Map<String, List<String>> filters) {}

        /** The per-file breakpoint map ({@code breakpoints.json}); path-string → that file's breakpoints. */
        Map<String, List<Breakpoint>> breakpointMap();

        /** Writes {@code breakpoints.json}. */
        void saveBreakpoints();

        /**
         * This window rewrote {@code fileKey}'s breakpoints ({@code null}: several files) in its bucket: the
         * other windows re-read them (see {@link DebugCoordinator#breakpointsChangedElsewhere}).
         */
        default void breakpointsStored(Map<String, ?> bucket, String fileKey) {}
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
    /** The Debug panel; null until {@link #panel()} builds it. */
    private DebugPanel debugPanel;
    /** The console font to apply when the panel is built (set by every settings apply, panel or not). */
    private String consoleFontFamily;

    private int consoleFontSize;

    /**
     * The before-launch build of a debug launch. On its own service rather than the Run console's, so
     * debugging a configuration is not refused merely because another program is running there.
     */
    private final com.editora.run.RunService beforeLaunchService = new com.editora.run.RunService();

    private final BeforeLaunchStep beforeLaunch = new BeforeLaunchStep(beforeLaunchService);

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

    /**
     * {@link #relaunch} of a session attached to an HTTP Client script: redoing it would send the request again,
     * which is Debug Request's to do, not Restart's.
     */
    private static final Runnable HTTP_SCRIPT_ATTACH = () -> {};

    DebugCoordinator(CoordinatorHost host, DapManager dapManager, LspManager lspManager, LspCoordinator lsp, Ops ops) {
        this.host = host;
        this.dapManager = dapManager;
        this.lspManager = lspManager;
        this.lsp = lsp;
        this.ops = ops;
        persistDebounce.setOnFinished(e -> flushPendingPersist());
        dapManager.setListener(dapListener());
        dapManager.setCodeReplaceNotices(this::codeReplaceNotice);
        dapManager.setBreakpointsSupplier(this::collectBreakpoints);
    }

    /**
     * The Debug tool-window content (the {@code ToolWindow} itself stays in {@code MainController}), built on
     * its first use: the window's first open, or the first session event that has something to show.
     */
    DebugPanel panel() {
        if (debugPanel == null) {
            DebugPanel built = new DebugPanel(debugActions());
            built.setPrompt(host::promptText);
            built.setOnLink(ops::openLink); // double-clicked stack-trace line → jump
            built.setWatches(ops.debugWatches());
            built.setChordLookup(ops::chordFor);
            built.setSourceCheck(this::hasSource);
            built.setOnWatchesChanged(() -> ops.persistDebugWatches(new ArrayList<>(built.getWatches())));
            if (consoleFontFamily != null) {
                built.setConsoleFont(consoleFontFamily, consoleFontSize);
            }
            built.setState(dapManager.state()); // a session may already be running (the window opened mid-session)
            debugPanel = built;
            refreshExceptionToggles();
        }
        return debugPanel;
    }

    /** Matches the console font to the editor font; remembered for a panel not built yet. */
    void setConsoleFont(String family, int size) {
        consoleFontFamily = family;
        consoleFontSize = size;
        if (debugPanel != null) {
            debugPanel.setConsoleFont(family, size);
        }
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

    /**
     * As {@link #debugEffectiveFor(String)}, for {@code file}: a remote file is debugged on its host, which is
     * asked for its adapter when the session starts (this machine's detection says nothing about it).
     */
    boolean debugEffectiveFor(String language, Path file) {
        if (file == null || com.editora.process.ExecTargets.runsHere(file)) {
            return debugEffectiveFor(language);
        }
        if (!debugSupportEnabled() || language == null || !DapManager.debuggableOnHost(language)) {
            return false;
        }
        var s = host.settings();
        boolean enabled =
                switch (language) {
                    case "python" -> s.isPythonDebugEnabled();
                    case "javascript" -> s.isJsDebugEnabled();
                    // jdtls runs on the host and is handed the java-debug jar there (copied from this machine,
                    // or the host's own), so the adapter is the host's jdtls's.
                    default ->
                        lspEnabled()
                                && lsp.serverEnabled("java")
                                && (dapManager.isAdapterAvailable()
                                        || com.editora.process.ExecTargets.remoteTool(file, "dap.java") != null);
                };
        return enabled && RemoteExec.runsFor(file);
    }

    /** Runs {@code action} only when the Debug feature is enabled; otherwise reports it. */
    /** Debug ▸ Stop: a before-launch build still running is what gets stopped; else the session. */
    void stop() {
        if (beforeLaunch.isActive()) {
            beforeLaunch.stop();
        } else if (pendingBuild != null) {
            // The jdtls build before a launch: it finishes on its own, but nothing is launched after it.
            pendingBuild = null;
            panel().setPreparing(false);
            ops.setStatusDebugLoading(false);
            host.setStatus(tr("status.debug.launchCancelled"));
            updateDebugAvailability();
        } else {
            Runnable owner = relaunch == ONE_SHOT_ATTACH ? ownerStop : null;
            ownerStop = null;
            queuedAttaches.clear(); // Stop ends the whole run, its other JVMs included
            dapManager.stop();
            if (owner != null) {
                owner.run();
            }
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
        if (again == HTTP_SCRIPT_ATTACH) {
            host.setStatus(tr("status.debug.cannotRestartHttpScript"));
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
        return relaunch != ONE_SHOT_ATTACH && relaunch != HTTP_SCRIPT_ATTACH;
    }

    /**
     * Attaches the JavaScript debugger to an HTTP Client script waiting on its inspector {@code port} (Debug Request):
     * a session called {@code label}, stepping over the runner {@code shim}, with every breakpoint — the {@code .http}
     * file's script lines and script files alike. {@code unguardedNode} names a Node that runs it without its permission
     * model (one that cannot let a debugger in under it), which the console and status bar then say. FX thread.
     */
    void attachHttpScript(int port, String label, Path shim, String unguardedNode) {
        if (!debugEffectiveFor("javascript")) {
            host.setStatus(tr("status.http.debugUnavailable"));
            return;
        }
        ops.openToolWindow();
        nameSession(label);
        relaunch = HTTP_SCRIPT_ATTACH;
        Path dir = shim == null ? null : shim.getParent();
        List<String> skip = new java.util.ArrayList<>(List.of("<node_internals>/**"));
        if (shim != null) {
            skip.add(shim.toString());
        }
        withClosedBreakpoints(() -> dapManager.startJsAttach(port, label, dir, skip));
        if (unguardedNode != null) {
            // Said after the session starts (which clears the console), where the user is looking.
            String why = tr("status.http.debugUnguarded", unguardedNode);
            panel().appendOutput(why + System.lineSeparator(), "stderr");
            host.setStatus(why);
        }
    }

    /** Window close: the before-launch build must not outlive the window that started it. */
    void shutdown() {
        persistDebounce.stop();
        flushPendingPersist(); // a breakpoint moved by an edit in the last 300 ms is not lost with the window
        beforeLaunchService.shutdown();
    }

    /** A session is starting, running, paused or being built for — the states Stop / Restart act on. */
    boolean sessionLive() {
        return dapManager.state() != DapManager.State.INACTIVE || beforeLaunch.isActive() || pendingBuild != null;
    }

    /** Names the session being launched in the panel; survives the end of the session it replaces. */
    private void nameSession(String label) {
        sessionLabel = label == null ? "" : label;
        panel().setSessionFile(sessionLabel);
    }

    /** Streams a before-launch build into the Debug console and keeps Stop usable while it runs. */
    private BeforeLaunchStep.Console beforeLaunchConsole() {
        return new BeforeLaunchStep.Console() {
            @Override
            public void started(String commandLine) {
                ops.openToolWindow();
                panel().setPreparing(true);
                panel().clearConsole(); // a new launch: the previous session's output is not this one's
                consoleStarted = true;
                panel().appendOutput("$ " + commandLine + "\n", "console");
            }

            @Override
            public void output(String line, boolean stderr) {
                panel().appendOutput(line + "\n", stderr ? "stderr" : "stdout");
            }

            @Override
            public void ended(int code, String launchError) {
                panel().setPreparing(false);
                consoleStarted = code == 0 && launchError == null; // a failed build is not followed by a session
                if (launchError != null) {
                    panel().appendOutput(launchError + "\n", "stderr");
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
        restoreExceptionFilters();
        var s = host.settings();
        boolean on = debugSupportEnabled(); // effective: off in Simple UI mode
        dapManager.configure(
                on,
                s.getJavaDebugPluginPath(),
                s.isPythonDebugEnabled(),
                s.getPythonDebugCommand(),
                s.isJsDebugEnabled(),
                s.getJsDebugPath());
        dapManager.setProgramConsole(s.isDebugProgramConsole()); // takes effect at the next launch
        dapManager.setJavaDebugSettings(new com.editora.dap.JavaDebugSettings(
                s.isDebugStepFilters(),
                s.isDebugShowStatics(),
                com.editora.dap.JavaDebugSettings.parseExceptionTypes(s.getDebugExceptionTypes())));
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
        if (b != null && b.isHttpFile()) {
            // Its scripts are debugged with Debug Request, on this machine only (remote .http files do not run).
            return host.settings().isJsDebugEnabled() && host.isLocalBuffer(b);
        }
        if (b == null || !DapServerRegistry.isDebuggable(b.getLanguage())) {
            return false;
        }
        if (host.isLocalBuffer(b) && (b.getPath() == null || com.editora.process.ExecTargets.runsHere(b.getPath()))) {
            return true;
        }
        // A file whose tools run elsewhere (a remote host, a container, WSL) is debugged there, while that may
        // run commands.
        return b.getPath() != null && DapManager.debuggableOnHost(b.getLanguage()) && RemoteExec.runsFor(b.getPath());
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
        String pending = tr(DebugFeedback.pendingKey(known.values(), buffer.getLanguage()));
        LaunchedLines.Mapping map = lineMap(buffer);
        for (Breakpoint bp : buffer.getBreakpointManager().documentSnapshot()) {
            int launchedLine = map.toLaunched(bp.line());
            if (launchedLine < 0) {
                live.put(
                        bp.line(),
                        new BreakpointManager.Live(
                                BreakpointManager.LiveState.PENDING, tr("debug.breakpoint.addedSinceLaunch")));
                continue;
            }
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
                            known.get(launchedLine),
                            failure == null ? null : failure.message(),
                            pending,
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
        LaunchedLines.Mapping map = lineMap(buffer); // statuses name launched lines; the markers are the document's
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
                    && buffer.getBreakpointManager()
                            .moveDocumentLine(map.toCurrent(status.line()), map.toCurrent(status.actualLine()))) {
                known.remove(status.line());
                known.put(
                        status.actualLine(),
                        new DapModels.BreakpointStatus(status.actualLine(), true, false, "", status.actualLine()));
                host.setStatus(tr(
                        "status.debug.breakpointMoved",
                        map.toCurrent(status.line()) + 1,
                        map.toCurrent(status.actualLine()) + 1));
            } else if (status.verified()
                    && status.actualLine() >= 0
                    && status.actualLine() != status.line()
                    && buffer != null
                    && buffer.getBreakpointManager()
                            .mergeDocumentLine(map.toCurrent(status.line()), map.toCurrent(status.actualLine()))) {
                // Bound onto a line that has a breakpoint already: one breakpoint, the one there (the re-send
                // the removal causes leaves the adapter just that one, with its condition).
                known.remove(status.line());
                host.setStatus(tr("status.debug.breakpointMerged", status.line() + 1, status.actualLine() + 1));
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
        panel().appendOutput(message + System.lineSeparator(), error ? "stderr" : "console");
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
        boolean oldGone = false;
        if (oldPath != null) {
            try {
                oldGone = !Files.exists(oldPath);
            } catch (RuntimeException closedConnection) {
                oldPath = null; // a remote file whose connection is gone: nothing can be asked about it
            }
        }
        var map = ops.breakpointMap();
        List<Breakpoint> stored = null;
        if (oldPath != null && oldGone) {
            stored = map.remove(key(oldPath)); // renamed away; a Save As copy leaves the original its own
        } else if (oldPath != null) {
            stored = map.get(key(oldPath));
        }
        pendingPersist.remove(buffer);
        if (buffer.isNarrowed()) {
            if (stored != null) {
                map.put(key(newPath), new ArrayList<>(stored)); // region-relative lines cannot be snapshotted
            }
            ops.saveBreakpoints();
        } else {
            // The buffer now IS this file: breakpoints stored for a file it overwrote are replaced, not merged.
            seenInStore.put(buffer, MarkMerge.keys(map.get(key(newPath)), Breakpoint::line));
            persistBreakpoints(buffer); // writes the file, also when the buffer has none (the old key is gone)
        }
        if (dapManager.isActive()) {
            if (oldPath != null && sentBreakpoints.remove(oldPath) != null && oldGone) {
                dapManager.updateBreakpoints(new DapModels.FileBreakpoints(oldPath, List.of()));
            }
            DapModels.FileBreakpoints now = fileBreakpoints(buffer);
            sentBreakpoints.put(newPath, now);
            dapManager.updateBreakpoints(now);
        }
    }

    /**
     * Breakpoints stored for files that are not open — a closed file renamed from the Project tree, the
     * files below a renamed folder — follow the rename too. Open buffers moved theirs in
     * {@link #bufferPathChanged}, so there is nothing left under the old path for those.
     */
    void pathRenamed(Path old, Path target) {
        String sep = old.getFileSystem().getSeparator();
        if (RenamedFileState.rekey(ops.breakpointMap(), key(old), key(target), sep)) {
            ops.saveBreakpoints();
            ops.breakpointsStored(ops.breakpointMap(), null);
        }
    }

    /**
     * The breakpoint store's key for {@code file}: its path for a local file, as it always was, and its
     * {@code sftp://} URI for a remote one — which names the host (the bare path would be a local file's key)
     * and survives a reconnect, whose new connection gives the same file a new {@link Path}.
     */
    static String key(Path file) {
        return Vfs.toStorableString(file);
    }

    private void persistBreakpoints(EditorBuffer buffer) {
        if (buffer.isNarrowed()) {
            return; // region-relative line numbers while narrowed — see BookmarkCoordinator.persistBookmarks
        }
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        var map = ops.breakpointMap();
        String key = key(file);
        List<Breakpoint> stored = map.get(key);
        // Not the snapshot alone: a second window on this file has its own copy of the breakpoints, and
        // writing this one as the whole list deleted every breakpoint the other had set.
        List<Breakpoint> bps = MarkMerge.withForeign(
                stored, seenInStore.get(buffer), buffer.getBreakpointManager().snapshot(), Breakpoint::line);
        if (bps.isEmpty()) {
            map.remove(key);
        } else {
            map.put(key, BreakpointStore.mergePreservingOrder(stored, bps));
        }
        seenInStore.put(buffer, MarkMerge.keys(map.get(key), Breakpoint::line));
        ops.saveBreakpoints();
        ops.breakpointsStored(map, key);
    }

    /**
     * The breakpoint lines each buffer last saw in the store for its file (when it loaded or wrote them):
     * what {@link MarkMerge} needs to tell "this buffer removed it" from "another window set it".
     */
    private final Map<EditorBuffer, Set<Integer>> seenInStore = new java.util.WeakHashMap<>();

    /**
     * Another window rewrote {@code fileKey}'s breakpoints ({@code null}: several files) in {@code bucket}.
     * A buffer of this window on that file shows them now — it would otherwise keep its stale copy and
     * write it back — and a live session of this window is told the file's new set.
     */
    void breakpointsChangedElsewhere(Map<String, ?> bucket, String fileKey) {
        if (bucket != ops.breakpointMap()) {
            return;
        }
        host.forEachBuffer(b -> {
            if (b.getPath() == null || b.isNarrowed()) {
                return; // a narrowed buffer's lines are region-relative; it merges when it next writes
            }
            String key = key(b.getPath());
            if (fileKey != null && !fileKey.equals(key)) {
                return;
            }
            List<Breakpoint> stored = ops.breakpointMap().get(key);
            b.applyBreakpoints(stored);
            seenInStore.put(b, MarkMerge.keys(stored, Breakpoint::line));
            if (dapManager.isActive()) {
                DapModels.FileBreakpoints now = fileBreakpoints(b);
                if (!now.equals(sentBreakpoints.put(b.getPath(), now))) {
                    dapManager.updateBreakpoints(now);
                }
            }
            showBreakpointStates(b);
        });
    }

    void restoreBreakpoints(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        if (dapManager.isActive()) {
            snapshotLaunched(buffer); // opened during a session: as on disk, which is what the program was built from
        }
        List<Breakpoint> stored = ops.breakpointMap().get(key(file));
        seenInStore.put(buffer, MarkMerge.keys(stored, Breakpoint::line));
        if (buffer.applyBreakpoints(stored)) {
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
        // B1: in the numbering of the code the program runs — a breakpoint whose line was added since the
        // session started has no place in it, and waits for the next session.
        LaunchedLines.Mapping map = lineMap(buffer);
        for (Breakpoint bp : buffer.getBreakpointManager().documentSnapshot()) {
            int line = map.toLaunched(bp.line());
            if (bp.enabled() && !breakpointsMuted && line >= 0) { // Mute Breakpoints: the adapter is told none
                lines.add(new DapModels.LineBreakpoint(line, bp.condition(), bp.logMessage(), bp.hitCondition()));
            }
        }
        return new DapModels.FileBreakpoints(buffer.getPath(), lines);
    }

    // --- B1: lines of the code the program runs, against the document as it is now ---------------------

    /** The text each file of the session was launched from (see {@link LaunchedLines}). */
    private final LaunchedLines launchedLines = new LaunchedLines();

    /** Files the console already said were changed since the session started. */
    private final Set<String> changedNoticed = new HashSet<>();

    /**
     * {@link #rebaselineLines(Path)} for each of {@code files}: after a hot code replace, the Java files saved
     * since the last successful replace (see {@link #codeReplaced}).
     */
    void rebaselineLines(Set<Path> files) {
        if (files != null) {
            files.forEach(this::rebaselineLines);
        }
    }

    /** The mapping between {@code b}'s launched text and its document (identity when there is none). */
    private LaunchedLines.Mapping lineMap(EditorBuffer b) {
        Path file = b == null ? null : b.getPath();
        if (file == null || b.isNarrowed() || !launchedLines.has(file)) {
            return LaunchedLines.Mapping.IDENTITY; // a narrowed buffer's text is its region only
        }
        // The area's own text, not getContent(): that is cached per document version, which has not moved yet
        // when a breakpoint shift is reported in the middle of the edit.
        return launchedLines.mapping(file, b.getArea().getText());
    }

    /** Records every open file's text as the session's launched text (the files were saved for the launch). */
    private void snapshotLaunchedLines() {
        launchedLines.clear();
        changedNoticed.clear();
        host.forEachBuffer(this::snapshotLaunched);
    }

    /** {@code b}'s launched text: its document, or the file on disk when it has unsaved edits. */
    private void snapshotLaunched(EditorBuffer b) {
        Path file = b.getPath();
        if (file == null || b.isNarrowed() || !host.isLocalBuffer(b)) {
            return;
        }
        if (!b.isDirty()) {
            launchedLines.snapshotIfAbsent(file, b.getContent());
            return;
        }
        List<String> disk = readLinesOrNull(file);
        if (disk != null) {
            launchedLines.snapshotIfAbsent(file, String.join("\n", disk));
        }
    }

    /** {@code frames} with each line as the document now has it (a frame names the launched line). */
    private List<DapModels.StackFrameInfo> currentLines(List<DapModels.StackFrameInfo> frames) {
        if (launchedLines.isEmpty() || frames == null) {
            return frames;
        }
        List<DapModels.StackFrameInfo> out = new ArrayList<>(frames.size());
        for (DapModels.StackFrameInfo f : frames) {
            EditorBuffer b = f.file() == null ? null : ops.bufferForPath(f.file());
            LaunchedLines.Mapping map = lineMap(b);
            int line = map.changed() ? map.toCurrent(f.line()) : f.line();
            out.add(line >= 0 && line != f.line() ? f.atLine(line) : f);
        }
        return out;
    }

    /**
     * At a stop in a file edited since the session started, says the program still runs the code it was
     * started with — the console once per file, the status bar every time.
     */
    private void noteChangedSinceLaunch(DapModels.StackFrameInfo frame) {
        EditorBuffer b = frame == null || frame.file() == null ? null : ops.bufferForPath(frame.file());
        if (b == null || !lineMap(b).changed()) {
            return;
        }
        String message = tr("status.debug.changedSinceLaunch", frame.file().getFileName());
        if (changedNoticed.add(key(frame.file()))) {
            panel().appendNotice(message);
        }
        host.setStatus(message);
    }

    /**
     * The program now runs {@code file}'s current text — a hot code replace redefined its classes: breakpoint and
     * frame lines are mapped from it from now on, and its breakpoints are re-sent in that numbering.
     */
    void rebaselineLines(Path file) {
        EditorBuffer b = file == null ? null : ops.bufferForPath(file);
        if (b == null || !dapManager.isActive()) {
            return;
        }
        launchedLines.rebaseline(file, b.getContent());
        DapModels.FileBreakpoints now = fileBreakpoints(b);
        if (!now.equals(sentBreakpoints.put(file, now))) {
            dapManager.updateBreakpoints(now);
        }
        showBreakpointStates(b);
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
            open.add(key(b.getPath()));
            DapModels.FileBreakpoints fb = fileBreakpoints(b);
            sentBreakpoints.put(b.getPath(), fb);
            if (!fb.breakpoints().isEmpty()) {
                out.add(fb);
            }
        });
        for (DapModels.FileBreakpoints fb : closedBreakpoints) {
            if (fb.file() != null && !open.contains(key(fb.file())) && !breakpointsMuted) {
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
                openPaths.add(key(b.getPath()));
            }
        });
        Map<String, List<Breakpoint>> map = new LinkedHashMap<>(ops.breakpointMap());
        Thread t = new Thread(
                () -> {
                    List<DapModels.FileBreakpoints> computed = closedFileBreakpoints(
                            map, openPaths, RemoteExec::runsFor, DebugCoordinator::readLinesOrNull);
                    Platform.runLater(() -> {
                        closedBreakpoints = computed;
                        then.run();
                    });
                },
                "debug-breakpoints");
        t.setDaemon(true);
        t.start();
    }

    /** Reads a file's lines (over SFTP for a remote one; this runs off the FX thread) for re-anchoring, or {@code null} when it can't be used as-is (unreadable,
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
     * Pure/unit-testable: {@code isLocal} skips files whose machine cannot debug (a remote host that may not
     * run commands), {@code readLines} returns a file's lines (or
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
                p = Vfs.parseStorable(key); // a remote file's key is its URI: null while its host is not connected
            } catch (RuntimeException ex) {
                continue;
            }
            if (p == null || !isLocal.test(p)) {
                continue; // a file whose host cannot run its debugger has nothing to send
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
                        out2.add(new DapModels.LineBreakpoint(
                                bp.line(), bp.condition(), bp.logMessage(), bp.hitCondition()));
                    }
                }
            } else {
                for (Breakpoint bp : bps) {
                    if (bp.enabled()) {
                        out2.add(new DapModels.LineBreakpoint(
                                bp.line(), bp.condition(), bp.logMessage(), bp.hitCondition()));
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
                // INACTIVE with no panel built is what startup (configure, stop) reports: a panel built later
                // starts in that state by itself, so there is nothing to show and no reason to build it now.
                boolean show = debugPanel != null || state != DapManager.State.INACTIVE;
                if (state == DapManager.State.STARTING) {
                    if (!consoleStarted) {
                        panel().clearConsole(); // each launch starts with its own output
                    }
                    consoleStarted = false;
                }
                if (show) {
                    panel().setRestartBlocked(restartAvailable() ? null : tr("status.debug.cannotRestartAttached"));
                    panel().setState(state);
                }
                if (state == DapManager.State.STARTING) {
                    nameSession(sessionLabel); // ending the replaced session cleared it
                    refreshExceptionToggles(); // the session's language decides what Caught / Uncaught mean
                }
                if (state != DapManager.State.SUSPENDED) {
                    shownFrame = null;
                    if (show) {
                        panel().setStopReason(null);
                    }
                }
                updateDebugStatus(state);
                primeCodeReplace(state);
                if (state == DapManager.State.INACTIVE || state == DapManager.State.STARTING) {
                    if (state == DapManager.State.STARTING) {
                        snapshotLaunchedLines(); // B1: what the program is built from (the files were saved)
                    } else {
                        launchedLines.clear();
                    }
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
                if (state == DapManager.State.INACTIVE && relaunch == ONE_SHOT_ATTACH && !queuedAttaches.isEmpty()) {
                    Platform.runLater(queuedAttaches.poll()); // the next JVM of the same run is waiting
                }
                boolean starting = state == DapManager.State.STARTING;
                ops.setStatusDebugLoading(starting);
                updateDebugAvailability(); // keep the window available during a session; hide it once it ends
                if (starting || state == DapManager.State.SUSPENDED) {
                    // A stop shows the window again if it was closed meanwhile (review C5) — without focus: the
                    // editor keeps it, and in the panel plain letters are debugger commands (review C2).
                    ops.openToolWindow();
                }
            }

            @Override
            public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> stopFrames) {
                List<DapModels.StackFrameInfo> frames = currentLines(stopFrames);
                panel().setStopReason(reason);
                // The frame that threw: its line is drawn red while it is the one shown (review C13).
                exceptionFrameId = "exception".equals(reason) && !frames.isEmpty()
                        ? frames.get(firstFrameWithSource(frames, DebugCoordinator.this::hasSource))
                                .id()
                        : -1;
                // Selects the first frame that has source to show → selectFrame highlights + loads vars. A step
                // that went into library code shows that code (B5): the user asked to go there.
                int shown = frameToShow(reason, frames, DebugCoordinator.this::hasSource);
                panel().setCallStack(frames, shown);
                if (shown > 0) {
                    host.setStatus(tr(
                            "status.debug.stoppedWithoutSource", frames.get(0).name()));
                }
                dapManager.threads(list -> panel().setThreads(list, dapManager.currentThreadId()));
            }

            @Override
            public void onOutput(String text, String category) {
                panel().appendOutput(text, category);
            }

            @Override
            public void onProgramInput(boolean available) {
                panel().setProgramInput(available);
            }

            @Override
            public void onProgramExit(int code) {
                panel().appendNotice(tr("run.exited", code));
                panel().setExitCode(code);
                // What the status bar says now: an earlier "Stopped on <exception>" no longer holds (review C19).
                host.setStatus(tr("run.exited", code));
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
                panel().setStoppedException(DebugFeedback.exceptionSummary(info, true));
                panel().setExceptionInfo(info);
                host.setStatus(tr("status.debug.stoppedOnException", DebugFeedback.exceptionSummary(info, false)));
            }

            @Override
            public void onAdapterLost() {
                String message = tr("status.debug.adapterLost");
                panel().appendNotice(message);
                host.setStatus(message);
            }

            @Override
            public void onStatus(String message) {
                host.setStatus(message);
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
            public List<javafx.scene.control.MenuItem> stackMenuItems(
                    DapModels.StackFrameInfo frame, List<DapModels.StackFrameInfo> frames) {
                return DebugCoordinator.this.stackMenuItems(frame, frames);
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
                dapManager.selectThread(threadId, threadFrames -> {
                    List<DapModels.StackFrameInfo> frames = currentLines(threadFrames);
                    if (!frames.isEmpty() || threadId == previous) {
                        panel().setCallStack(frames, firstFrameWithSource(frames, DebugCoordinator.this::hasSource));
                        return;
                    }
                    // A thread that is not suspended has no stack to inspect. Stay on the stopped one: showing
                    // an empty stack beside the other thread's variables and execution line — with Step now
                    // aimed at the running thread — said nothing about why.
                    host.setStatus(tr("status.debug.threadRunning"));
                    panel().showThread(previous);
                    dapManager.selectThread(previous, back -> {
                        if (back.isEmpty()) { // it has been resumed meanwhile: nothing is stopped to show
                            panel().setCallStack(back, 0);
                            panel().setScopes(List.of());
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
                noteChangedSinceLaunch(frame);
                dapManager.scopes(frame.id(), scopes -> {
                    panel().setScopes(scopes);
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
            public void sendInput(String line) {
                dapManager.sendProgramInput(line);
            }

            @Override
            public void endInput() {
                endProgramInput();
            }

            @Override
            public void toggleExceptions(boolean caught) {
                toggleExceptionKind(caught);
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
    /**
     * Pure: the frame a stop selects. After a step into a JDK or library class whose text jdtls can supply (a
     * {@code jdt://} source), that frame: the user asked to go there, and showing the caller instead made Step
     * Into look as if it did nothing (B5). Any other stop: {@link #firstFrameWithSource}.
     */
    static int frameToShow(
            String reason, List<DapModels.StackFrameInfo> frames, Predicate<DapModels.StackFrameInfo> has) {
        if ("step".equals(reason) && !frames.isEmpty() && frames.get(0).sourceUri() != null) {
            return 0;
        }
        return firstFrameWithSource(frames, has);
    }

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
            if (frame.sourceUri() != null && dapManager.debugFile() != null) {
                // B5: a JDK or library class jdtls can supply the text of (java/classFileContents) — shown in its
                // read-only library tab, as go-to-definition and the console's stack-trace links already do.
                ops.openLibraryFrame(dapManager.debugFile(), frame.sourceUri(), frame.line(), lib -> {
                    if (shownFrame == frame && dapManager.state() == DapManager.State.SUSPENDED) {
                        clearExecHighlight();
                        paintExecutionLine(lib, frame);
                    }
                });
                return;
            }
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

    /** The id of the frame an exception stop threw in (its line is drawn red), or -1. */
    private int exceptionFrameId = -1;

    private void paintExecutionLine(EditorBuffer b, DapModels.StackFrameInfo frame) {
        execHighlightBuffer = b;
        ops.afterBufferLoad(b, () -> {
            // The user may resume or select another frame while this file is still loading. Never let
            // that obsolete completion repaint an execution marker that clearExecHighlight removed.
            if (execHighlightBuffer == b) {
                // A narrowed buffer shows only its region: the frame's file line is region-relative
                // there (a stop outside the region has no line to show and paints nothing).
                b.setExecutionLine(frame.line() - b.getBreakpointManager().regionFirstLine());
                b.setExecutionLineException(frame.id() == exceptionFrameId);
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
            if (debugPanel != null && n == debugPanel) {
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
    /** Program arguments read as Run reads them; null after saying why when a quote is never closed. */
    private List<String> debugArgs(String text) {
        try {
            return ProgramArgs.parse(text);
        } catch (ProgramArgs.UnclosedQuoteException e) {
            host.setStatus(tr("status.run.argsUnclosedQuote", String.valueOf(e.quote())));
            return null;
        }
    }

    void debugStart() {
        EditorBuffer b = host.activeBuffer();
        if (dapManager.isActive()) {
            // A session is live. While SUSPENDED (or mid-step) the green button keeps its Continue semantics:
            // never yank a paused session. While it runs, the same file is already being debugged (said so),
            // and another debuggable file replaces it — after asking, since that ends the running program.
            boolean differentFile = b != null && b.getPath() != null && !samePath(b.getPath(), dapManager.debugFile());
            boolean paused = dapManager.state() == DapManager.State.SUSPENDED || dapManager.isStepping();
            if (paused) {
                dapManager.resume(); // F5-style continue
                return;
            }
            if (!differentFile || !debugEffectiveFor(b.getLanguage(), b.getPath())) {
                host.setStatus(tr("status.debug.alreadyRunning", sessionLabel));
                return;
            }
            if (!confirmRetarget(
                    tr("dialog.debug.retargetContent", sessionLabel, b.getPath().getFileName()))) {
                return;
            }
            dapManager.stop(); // retarget to the newly active file below
        }
        launchBuffer(b);
    }

    /**
     * Asks before F5 in another tab ends the running session (review A13); a test replaces it. The answer is
     * the user's: a running server is not stopped by a key pressed in the wrong tab.
     */
    Predicate<String> confirmRetarget;

    private boolean confirmRetarget(String question) {
        if (confirmRetarget != null) {
            return confirmRetarget.test(question);
        }
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                question,
                javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);
        alert.setTitle(tr("dialog.debug.retargetTitle"));
        alert.setHeaderText(null);
        if (host.window() != null) {
            alert.initOwner(host.window());
        }
        return alert.showAndWait()
                .filter(r -> r == javafx.scene.control.ButtonType.OK)
                .isPresent();
    }

    /** Saves {@code b} and launches a debug session for it; also what Restart repeats for such a session. */
    private void launchBuffer(EditorBuffer b) {
        if (b != null && b.isHttpFile()) {
            ops.debugHttpRequest(); // its scripts, around the request at the caret
            return;
        }
        // A file no adapter can debug is told so first: an untitled one used to get a Save As dialog, and then
        // a shell script "Debugging is unavailable for this file (check Settings → Debugging)", which no
        // setting can change.
        if (b != null && b.isShell()) {
            host.setStatus(tr("status.debug.shellNotDebuggable"));
            return;
        }
        if (b != null && b.getPath() == null && !DapServerRegistry.isDebuggable(b.getLanguage())) {
            host.setStatus(tr("status.debug.languageNotDebuggable"));
            return;
        }
        if (b == null || b.getPath() == null && !ops.saveBuffer(b)) {
            host.setStatus(tr("status.debug.saveFirst"));
            return;
        }
        String language = b.getLanguage();
        if (!debugEffectiveFor(language, b.getPath())) {
            host.setStatus(unavailableMessage(language, b.getPath()));
            return;
        }
        if ((b.isDirty() || b.getPath() == null) && !ops.saveBuffer(b)) {
            return;
        }
        b.settleRunScan(); // compact-source detection is the load's off-FX scan; decide from its result
        Integer shebangSource = b.getShebangJavaSource();
        if ("java".equals(language) && b.isCompactSource() && shebangSource != null && shebangSource < 25) {
            host.setStatus(tr("status.debug.needSource25", shebangSource));
            return;
        }
        // The debuggee gets the same per-file program arguments the Run feature uses, read the same way.
        List<String> fileArgs = debugArgs(ops.programArgs(b.getPath()));
        if (fileArgs == null) {
            return;
        }
        ops.openToolWindow();
        nameSession(b.getPath().getFileName().toString());
        dapManager.setProgramArgs(fileArgs);
        dapManager.setVmArgs(""); // no user VM args on a plain debug
        dapManager.setHostJavaExec("");
        if (b.isPython()) {
            planPython(b.getPath(), firstLine(b.getContent()));
        }
        // Re-anchor closed files' breakpoints first (off-thread) so the initial setBreakpoints arms them too.
        boolean java = "java".equals(language);
        Path projectRoot = java ? JavaProjectRoot.find(b.getPath()) : null;
        boolean compactSource = java && b.isCompactSource();
        // A plain-folder project window: its program runs in the project root, like Run's (review A16).
        Path project = java ? JavaLaunchPrep.projectOf(b.getPath(), projectRoot, ops.projectRoot()) : null;
        if (java && !saveJavaBuffers(b.getPath(), project, false)) { // the launched file was saved above
            return;
        }
        if (projectRoot != null && !compactSource) {
            // A saved configuration for this class is how the project runs it — its before-launch build,
            // arguments, environment, working directory — exactly as Run ▶ uses it (review D1).
            RunConfiguration configured = com.editora.run.JavaLaunchPlan.configurationFor(
                    ownMainClass(b.getContent()), ops.runConfigurations());
            if (configured != null) {
                debugConfig(configured);
                return;
            }
        }
        // A remote file's program runs on its host: none of this machine's JDK setting, PATH or JAVA_HOME.
        String jdkHome = !java || !com.editora.process.ExecTargets.runsHere(b.getPath())
                ? ""
                : compactSource ? host.settings().getMavenJdkHome() : configuredJdkHome(projectRoot, null);
        String javaExec = com.editora.run.JdkToolchain.javaExecutable(jdkHome);
        dapManager.setEnv(
                com.editora.run.JdkToolchain.environment(jdkHome, com.editora.process.ProcessRunner.augmentedPath()));
        Path launched = b.getPath();
        relaunch = () -> relaunchFor(launched, this::launchBuffer);
        Path looseCwd = projectRoot == null ? project : null;
        Runnable start = () -> withClosedBreakpoints(() -> {
            if (compactSource && shebangSource != null) {
                dapManager.startCompactShebang(b.getPath(), shebangSource, javaExec);
            } else if (compactSource && b.getPath().getFileName().toString().endsWith(".java")) {
                dapManager.startCompactSource(b.getPath(), javaExec);
            } else if (java) {
                dapManager.startLaunch(b.getPath(), this::pickMainClass, javaExec, projectRoot, looseCwd);
            } else {
                dapManager.startLaunch(b.getPath(), language, this::pickMainClass, javaExec, projectRoot);
            }
        });
        if (projectRoot != null && !compactSource) {
            buildThenLaunch(b.getPath(), start);
        } else {
            start.run();
        }
    }

    // --- Java launch preparation (review A2, B3, D1) ------------------------------------------------------

    /** The build before a project launch in flight; replaced or cleared to drop its result (Stop). */
    private Object pendingBuild;

    /** The fully-qualified name of the first {@code main} in {@code source}, or null. */
    private static String ownMainClass(String source) {
        List<com.editora.run.MainMethodScanner.MainMethod> mains =
                com.editora.run.MainMethodScanner.scan(source == null ? "" : source);
        return mains.isEmpty() ? null : mains.get(0).fqn();
    }

    /**
     * Saves every unsaved Java file of {@code project} (the launched file's folder tree without one) before a
     * Java launch: the program is compiled from what is on disk, and a breakpoint in an unsaved file would sit
     * on lines the program does not have (review B3). False when a save failed or was cancelled.
     */
    private boolean saveJavaBuffers(Path launched, Path project, boolean includeLaunched) {
        List<EditorBuffer> dirty = new ArrayList<>();
        host.forEachBuffer(buf -> {
            if (buf.isDirty()
                    && (includeLaunched || !samePath(buf.getPath(), launched))
                    && JavaLaunchPrep.saveBeforeLaunch(buf.getPath(), buf.getLanguage(), launched, project)) {
                dirty.add(buf);
            }
        });
        for (EditorBuffer buf : dirty) {
            if (!ops.saveBuffer(buf)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds {@code project} with jdtls ({@code java/buildWorkspace}, what VS Code's build before launch does)
     * and runs {@code launch} when it compiled. Editora runs jdtls with autobuild off, so nothing else writes
     * the class files a project launch runs: a fresh project ended in ClassNotFoundException and an edited one
     * ran its old code under breakpoints placed on the new lines (review A2). Compile errors in the project are
     * listed in the Debug console (each a link to its line) and nothing is launched. A build that cannot run
     * launches anyway; the launch then checks the class files itself (missing: refused; older: a warning).
     */
    private void buildThenLaunch(Path routing, Runnable launch) {
        Object token = new Object();
        pendingBuild = token;
        lsp.ensureManaged(routing);
        ops.openToolWindow();
        panel().setPreparing(true);
        ops.setStatusDebugLoading(true);
        updateDebugAvailability();
        lspManager.buildJavaWorkspaceForLaunch(routing, result -> {
            if (pendingBuild != token) {
                return; // stopped, or replaced by another launch
            }
            pendingBuild = null;
            panel().setPreparing(false);
            ops.setStatusDebugLoading(false);
            if (result.outcome() == LspManager.BuildOutcome.WITH_ERRORS) {
                showBuildErrors(result.errors(), BUILD_ERROR_WAITS);
                return;
            }
            launch.run();
        });
    }

    /**
     * Lists a failed build's compile errors in the Debug console and says so; nothing is launched. jdtls's
     * workspace is this project. Its build reports the errors of files that are not open as it finds them; an
     * open file's come from the editor's copy (the Problems view has them, and the copy on disk is the same:
     * every Java file of the project was saved first) — and may still be on their way, so with none known yet
     * this looks again a few times before saying only that there are errors.
     */
    private void showBuildErrors(Map<Path, List<com.editora.editor.LspDiagnostic>> published, int waits) {
        Map<Path, List<com.editora.editor.LspDiagnostic>> found = new java.util.TreeMap<>(lsp.problems());
        found.putAll(published);
        int errors = JavaLaunchPrep.errorCount(found, null);
        if (errors == 0 && waits > 0) {
            javafx.animation.PauseTransition later =
                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));
            later.setOnFinished(e -> showBuildErrors(published, waits - 1));
            later.play();
            return;
        }
        panel().clearConsole();
        consoleStarted = false;
        List<String> lines = JavaLaunchPrep.errorLines(found, null, MAX_BUILD_ERROR_LINES);
        for (String line : lines) {
            panel().appendOutput(line + "\n", "stderr");
        }
        if (errors > lines.size()) {
            panel().appendNotice(tr("debug.console.moreBuildErrors", errors - lines.size()));
        }
        panel().appendNotice(tr("debug.console.buildFailed"));
        host.setStatus(errors > 0 ? tr("status.debug.buildErrors", errors) : tr("status.debug.buildErrorsUnlisted"));
        updateDebugAvailability();
    }

    /** How many times a failed build's errors are looked for again before the console says only that there are some. */
    private static final int BUILD_ERROR_WAITS = 8;

    /** How many compile errors the Debug console lists before saying how many more there are. */
    private static final int MAX_BUILD_ERROR_LINES = 50;

    /**
     * Why {@code language} cannot be debugged here, as the user can act on it: Python or JavaScript debugging
     * turned off, or its adapter not found (with the command that installs it). One message used to cover all
     * of these, and every other reason too.
     */
    String unavailableMessage(String language, Path file) {
        boolean local = file == null || com.editora.process.ExecTargets.runsHere(file);
        if (!local || !debugSupportEnabled()) {
            return tr("status.debug.unavailable");
        }
        var s = host.settings();
        return switch (language == null ? "" : language) {
            case "python" ->
                !s.isPythonDebugEnabled()
                        ? tr("status.debug.pythonOff")
                        : tr(
                                "status.debug.debugpyMissing",
                                s.getPythonDebugCommand().isBlank()
                                        ? com.editora.dap.DapServerRegistry.DEFAULT_PYTHON_INTERPRETER
                                        : s.getPythonDebugCommand());
            case "javascript" -> !s.isJsDebugEnabled() ? tr("status.debug.jsOff") : tr("status.debug.jsDebugMissing");
            case "java" -> javaUnavailableMessage();
            default -> tr("status.debug.unavailable");
        };
    }

    /**
     * Which piece of Java debugging is missing, as the user can act on it (review A7): it runs through the Java
     * language server, so LSP being off, the Java server being off or not installed, and the java-debug plugin
     * missing each have their own message. "Check Settings → Debugging" covered them all, and that page shows
     * none of the first three.
     */
    private String javaUnavailableMessage() {
        if (!lspEnabled()) {
            return tr("status.debug.javaNeedsLsp");
        }
        if (!lsp.serverEnabled("java")) {
            return tr("status.debug.javaServerOff");
        }
        if (!lsp.isServerAvailable("java")) {
            return tr("status.debug.jdtlsMissing");
        }
        if (!dapManager.isAdapterAvailable()) {
            return tr("status.debug.javaDebugMissing");
        }
        return tr("status.debug.unavailable");
    }

    /**
     * Debug Test at Caret on a pytest file ({@code test_*.py}, {@code *_test.py}): the test at the caret (its class
     * with {@code classLevel}, the whole file outside any test) runs under pytest in debugpy. False — nothing
     * done — for any other buffer, which the caller then handles as before.
     */
    boolean debugPythonTestAtCaret(EditorBuffer b, boolean classLevel) {
        if (b == null
                || b.getPath() == null
                || !b.isPython()
                || !com.editora.test.PytestScanner.isTestFile(
                        b.getPath().getFileName().toString())) {
            return false;
        }
        int caret = b.getFocusedArea().getCurrentParagraph()
                + b.getBreakpointManager().regionFirstLine();
        com.editora.test.PytestScanner.TestTarget target = com.editora.test.PytestScanner.atLine(b.getContent(), caret);
        if (classLevel && target != null && target.className() != null) {
            target = new com.editora.test.PytestScanner.TestTarget(
                    target.line(), target.endLine(), target.className(), null);
        }
        debugPythonTest(b, target);
        return true;
    }

    /**
     * Debugs {@code target} of pytest file {@code b} ({@code null}: the whole file): {@code python -m pytest
     * <node id>} under debugpy, from the project root (the file's folder with no project), under the file's
     * interpreter. Without pytest, a {@code unittest.TestCase} test runs under {@code python -m unittest}
     * instead. Public for the per-test gutter ▶'s Debug entry (LEAD-WIRE: Fixer C's gutter calls this).
     */
    public void debugPythonTest(EditorBuffer b, com.editora.test.PytestScanner.TestTarget target) {
        Path file = b == null ? null : b.getPath();
        if (file == null) {
            host.setStatus(tr("status.debug.saveFirst"));
            return;
        }
        Path root = pythonRootOf(file);
        Path cwd = root != null ? root : file.getParent();
        String relative = cwd.relativize(file).toString().replace('\\', '/');
        debugPythonTests(
                file,
                cwd,
                List.of(com.editora.test.PytestScanner.nodeId(relative, target)),
                unittestFallback(b.getContent(), relative, target),
                file.getFileName() + (target == null ? "" : " :: " + target.displayName()));
    }

    /**
     * Pure: the pytest test a Python test glyph stands for. The glyphs carry {@link
     * com.editora.test.JavaTestScanner.TestTarget}s ({@code RunScan.pythonTestLines}): a class (method null), a
     * module-level function (class {@code ""}), or — class {@code ""} and no method — the whole file (null here).
     */
    static com.editora.test.PytestScanner.TestTarget pytestTarget(
            String source, com.editora.test.JavaTestScanner.TestTarget glyph) {
        if (glyph == null || (glyph.className().isEmpty() && glyph.methodName() == null)) {
            return null;
        }
        com.editora.test.PytestScanner.TestTarget found = com.editora.test.PytestScanner.atLine(source, glyph.line());
        int end = found == null ? glyph.line() : found.endLine();
        return new com.editora.test.PytestScanner.TestTarget(
                glyph.line(), end, glyph.className().isEmpty() ? null : glyph.className(), glyph.methodName());
    }

    /**
     * Debugs Python tests by id in {@code cwd}, under {@code anchor}'s interpreter (the test file, or the folder
     * a run was in): pytest node ids — with {@code unittestIds} to fall back on when the interpreter has no
     * pytest — or, with no pytest ids, {@code python -m unittest} on {@code unittestIds} (a run that was
     * unittest's). What a Test Results row's Debug and a test ▶'s Debug Test come to.
     */
    void debugPythonTests(Path anchor, Path cwd, List<String> pytestIds, List<String> unittestIds, String label) {
        if (!debugEffectiveFor("python", anchor)) {
            host.setStatus(unavailableMessage("python", anchor));
            return;
        }
        EditorBuffer open = ops.bufferForPath(anchor);
        if (open != null && open.isDirty() && !ops.saveBuffer(open)) {
            return;
        }
        List<String> fallback = unittestIds == null ? List.of() : unittestIds;
        ops.openToolWindow();
        nameSession(label);
        dapManager.setProgramArgs(List.of());
        planPython(anchor, open == null ? null : firstLine(open.getContent()));
        relaunch = () -> debugPythonTests(anchor, cwd, pytestIds, unittestIds, label);
        withClosedBreakpoints(() -> {
            if (pytestIds == null || pytestIds.isEmpty()) {
                dapManager.startPythonModule(anchor, "unittest", fallback, cwd);
            } else {
                dapManager.startPythonTest(anchor, pytestIds, fallback, cwd);
            }
        });
    }

    /**
     * Hands the next Python launch Run's plan inputs ({@link com.editora.run.PythonRunPlan}) for {@code file}:
     * its project root, the interpreter picked for the project, the interpreter setting where it runs (a remote
     * host's {@code python} tool), its first line (a shebang) — and that machine. Debug and Run then start the
     * same Python with the same {@code PYTHONPATH}, here, on a host, in WSL or in a container.
     */
    private void planPython(Path file, String firstLine) {
        Path dir = file.getParent() == null ? file : file.getParent();
        dapManager.setPythonPlan(
                com.editora.run.PythonRunPlan.Request.file(
                        file,
                        pythonRootOf(file),
                        com.editora.process.ExecTargets.forPath(file).toTargetPath(file),
                        List.of(),
                        firstLine,
                        ops.pythonInterpreterChoice(),
                        PythonRunLauncher.setting(dir, host.settings())),
                PythonRunLauncher.hostFor(dir));
    }

    private static String firstLine(String text) {
        if (text == null) {
            return null;
        }
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    /** This window's project root when {@code file} is inside it, else null. */
    private Path pythonRootOf(Path file) {
        Path root = ops.projectRoot();
        return root != null
                        && file.toAbsolutePath()
                                .normalize()
                                .startsWith(root.toAbsolutePath().normalize())
                ? root
                : null;
    }

    /**
     * Pure: the {@code python -m unittest} arguments that run {@code target} when pytest is missing — only for a
     * {@code unittest.TestCase} class (or a method of one), named as a dotted path from the folder it runs in;
     * empty when unittest cannot run it.
     */
    static List<String> unittestFallback(
            String source, String relative, com.editora.test.PytestScanner.TestTarget target) {
        if (target == null || target.className() == null || !relative.endsWith(".py")) {
            return List.of();
        }
        String[] lines = source.split("\n", -1);
        String header = target.line() < lines.length ? lines[target.line()] : "";
        if (!header.contains("TestCase")) {
            // the method's class header: the nearest "class Name" above
            header = "";
            for (int i = Math.min(target.line(), lines.length - 1); i >= 0; i--) {
                if (lines[i].stripLeading().startsWith("class " + target.className())) {
                    header = lines[i];
                    break;
                }
            }
        }
        if (!header.contains("TestCase")) {
            return List.of();
        }
        String module = relative.substring(0, relative.length() - 3).replace('/', '.');
        return List.of(
                module + "." + target.className() + (target.functionName() == null ? "" : "." + target.functionName()));
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
            host.setStatus(
                    com.editora.run.ScriptRunCommand.SHELL.equals(cfg.type())
                            ? tr("status.debug.shellNotDebuggable")
                            : tr("status.debug.configTypeUnsupported", tr("settings.runConfig.type." + cfg.type())));
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
        // Same reasoning as RunCoordinator.runConfig: a named configuration must not depend on which tab is
        // in front, only on a Java file being open somewhere in its project.
        Path routing = RunCoordinator.routingFor(host, cfg);
        if (!debugEffectiveFor("java", routing)) { // a remote project is debugged on its host (review A9)
            host.setStatus(unavailableMessage("java", routing));
            return;
        }
        if (routing == null) {
            host.setStatus(tr("status.run.configNeedsJavaFile"));
            return;
        }
        Path buildRoot = JavaProjectRoot.find(routing);
        Path root = JavaLaunchPrep.projectOf(routing, buildRoot, ops.projectRoot());
        if (root == null) {
            host.setStatus(tr("status.debug.noProject"));
            return;
        }
        if (!saveJavaBuffers(routing, root, true)) {
            return; // every unsaved Java file of the project is written first (review B3)
        }
        // One plan for Run and Debug (review D9): working directory, JDK, environment, arguments.
        com.editora.run.JavaLaunchPlan.Result planned = com.editora.run.JavaLaunchPlan.forConfiguration(
                cfg,
                com.editora.run.JavaLaunchPlan.Inputs.of(root, host.settings().getMavenJdkHome()));
        if (!planned.ok()) {
            String quote = String.valueOf(planned.problem().quote());
            host.setStatus(
                    planned.problem().field() == com.editora.run.JavaLaunchPlan.Field.ENV
                            ? tr("status.run.envUnclosedQuote", cfg.name(), quote)
                            : tr("status.run.argsUnclosedQuote", quote));
            ops.editConfiguration(cfg.name());
            return;
        }
        com.editora.run.JavaLaunchPlan plan = planned.plan();
        // The same gate the Run path has always had: a failed build aborts the launch rather than debugging
        // the previous class files, where every breakpoint would sit on a stale line number.
        //
        // Placed after the guards above rather than around the whole method — unlike Run, which wraps its
        // dispatch — because those guards reject a configuration that cannot launch at all (a script type, a
        // blank main class), and spending a multi-minute build on one before saying so is worse than not
        // building.
        if (beforeLaunch.isActive() || pendingBuild != null) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        // Restart reads the configuration again: an edit made since the first launch applies (review A14).
        String name = cfg.name();
        relaunch = () -> debugConfig(currentConfiguration(name, cfg));
        boolean plainFolder = buildRoot == null;
        Runnable launch = () -> {
            // routing may be a background tab whose server start was deferred; open it on jdtls first, or the
            // resolve below comes back "no language server for file" while jdtls is running perfectly.
            lsp.ensureManaged(routing);
            dapManager.resolveMainClasses(routing, options -> {
                DapManager.MainClassOption match = matchConfiguredClass(options, cfg.mainClass(), plainFolder);
                if (match == null) {
                    host.setStatus(
                            options.isEmpty()
                                    ? tr("status.debug.noMainClassYet")
                                    : tr("status.debug.configClassNotFound", cfg.name(), cfg.mainClass()));
                    return;
                }
                ops.openToolWindow();
                nameSession(shortName(match.mainClass()));
                dapManager.setProgramArgs(plan.args());
                dapManager.setVmArgs(plan.vmArgs());
                dapManager.setEnv(plan.environment());
                // On a remote host the plan names the host's configured JDK, as Run uses it (review D9).
                boolean onHost = !com.editora.process.ExecTargets.runsHere(routing);
                dapManager.setHostJavaExec(onHost ? plan.javaExecutable() : "");
                withClosedBreakpoints(() -> dapManager.startLaunchMainClass(
                        routing, match, plan.cwd(), onHost ? "" : plan.javaExecutable(), plainFolder));
            });
        };
        // A before-launch step builds the project the user's way, in the project root (review A12); without
        // one, jdtls builds it (review A2). A plain folder has nothing to build: its class is compiled at launch.
        boolean hasStep = cfg.beforeLaunch() != null && !cfg.beforeLaunch().isBlank();
        beforeLaunch.run(host, cfg, root, plan.jdkEnvironment(), beforeLaunchConsole(), () -> {
            if (!hasStep && !plainFolder) {
                buildThenLaunch(routing, launch);
            } else {
                launch.run();
            }
        });
    }

    /** The saved configuration called {@code name} as it is now, else {@code fallback} (it was deleted). */
    private RunConfiguration currentConfiguration(String name, RunConfiguration fallback) {
        for (RunConfiguration c : ops.runConfigurations()) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return fallback;
    }

    /**
     * jdtls's entry for a configuration's {@code mainClass}. In a plain folder jdtls may know a packaged class
     * without its package (it guesses the source folder — see DapManager): the simple name then matches.
     */
    static DapManager.MainClassOption matchConfiguredClass(
            List<DapManager.MainClassOption> options, String mainClass, boolean plainFolder) {
        for (DapManager.MainClassOption o : options) {
            if (mainClass.equals(o.className())) {
                return o;
            }
        }
        if (!plainFolder) {
            return null;
        }
        String simple = mainClass.substring(mainClass.lastIndexOf('.') + 1);
        for (DapManager.MainClassOption o : options) {
            String cls = o.className();
            if (cls != null && simple.equals(cls.substring(cls.lastIndexOf('.') + 1))) {
                return o;
            }
        }
        return null;
    }

    private void startMainClassDebug(String targetFqn) {
        startMainClassDebug(host.activeBuffer(), targetFqn);
    }

    private void startMainClassDebug(EditorBuffer b, String targetFqn) {
        if (!debugEffectiveFor("java", b == null ? null : b.getPath())) {
            host.setStatus(unavailableMessage("java", b == null ? null : b.getPath()));
            return;
        }
        if (b == null || b.getPath() == null || !"java".equals(b.getLanguage())) {
            host.setStatus(tr("status.debug.needJavaFile"));
            return;
        }
        Path routing = b.getPath();
        Path buildRoot = JavaProjectRoot.find(routing);
        // A plain-folder project window has main classes too (review A16).
        Path root = JavaLaunchPrep.projectOf(routing, buildRoot, ops.projectRoot());
        if (root == null) {
            host.setStatus(tr("status.debug.noProject"));
            return;
        }
        if (!saveJavaBuffers(routing, root, true)) {
            return;
        }
        boolean plainFolder = buildRoot == null;
        dapManager.resolveMainClasses(routing, options -> {
            if (options.isEmpty()) {
                host.setStatus(tr("status.debug.noMainClass"));
                return;
            }
            Consumer<DapManager.MainClassOption> launch = opt -> {
                if (opt == null) {
                    return;
                }
                // The configuration for this class is how the project runs it, as for Run ▶ (review D1).
                RunConfiguration configured = plainFolder
                        ? null
                        : com.editora.run.JavaLaunchPlan.configurationFor(opt.className(), ops.runConfigurations());
                if (configured != null) {
                    debugConfig(configured);
                    return;
                }
                List<String> mainArgs = debugArgs(programArgsForMain(opt, routing));
                if (mainArgs == null) {
                    return;
                }
                ops.openToolWindow();
                nameSession(shortName(opt.mainClass()));
                dapManager.setProgramArgs(mainArgs);
                dapManager.setVmArgs(""); // the gutter/command debug carries no VM args/env
                dapManager.setHostJavaExec("");
                dapManager.setEnv(configuredJdkEnvironment(buildRoot, null));
                lsp.ensureManaged(routing); // see above
                relaunch = () -> relaunchFor(routing, again -> startMainClassDebug(again, opt.className()));
                String javaExec = configuredJavaExecutable(buildRoot, null);
                Runnable go = () -> withClosedBreakpoints(
                        () -> dapManager.startLaunchMainClass(routing, opt, root, javaExec, plainFolder));
                if (plainFolder) {
                    go.run();
                } else {
                    buildThenLaunch(routing, go);
                }
            };
            if (targetFqn != null) {
                DapManager.MainClassOption match = matchConfiguredClass(options, targetFqn, plainFolder);
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
    private String programArgsForMain(DapManager.MainClassOption opt, Path routing) {
        if (opt.filePath() == null || opt.filePath().isBlank()) {
            return "";
        }
        try {
            // jdtls names the file as the machine it runs on does (a remote host's, a container's path).
            return ops.programArgs(
                    com.editora.process.ExecTargets.forPath(routing).toPath(opt.filePath()));
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The default JDK for a loose file or Maven project; a saved configuration may override it. */
    private String configuredJdkHome(Path root, RunConfiguration cfg) {
        if (root != null && !com.editora.process.ExecTargets.runsHere(root)) {
            return ""; // a JDK setting names a folder on this machine; the jdtls where it runs resolves its own
        }
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
        int line = lineMap(b).toLaunched(fileLineAtCaret(b));
        if (line < 0) {
            host.setStatus(tr("status.debug.lineAddedSinceLaunch"));
            return;
        }
        dapManager.runToCursor(b.getPath(), line);
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
        int line = lineMap(b).toLaunched(fileLineAtCaret(b));
        if (line < 0) {
            host.setStatus(tr("status.debug.lineAddedSinceLaunch"));
            return;
        }
        dapManager.jumpToLine(
                b.getPath(), line, err -> host.setStatus(err.isEmpty() ? tr("status.debug.jumpNoTarget") : err));
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
        attachToPort(anchorFile, attachHost, port, null);
    }

    /** The test or build run that started the JVM of the attached session; Stop ends it too (review A4). */
    private Runnable ownerStop;

    /**
     * JVMs of the same run that printed their banner while one of them was being debugged (several surefire
     * forks, a multi-module build), attached in turn as each session ends (review D11). Each waits suspended.
     */
    private final java.util.ArrayDeque<Runnable> queuedAttaches = new java.util.ArrayDeque<>();

    /**
     * As above; {@code stopOwner} ends the test or build run that started the JVM: Stop on this session runs
     * it after detaching, so Stop ends the program as it does every other session's — detaching alone left
     * the test or app running on (review A4).
     */
    void attachToPort(Path anchorFile, String attachHost, int port, Runnable stopOwner) {
        if (relaunch == ONE_SHOT_ATTACH && dapManager.isActive()) {
            queuedAttaches.add(() -> attachToPort(anchorFile, attachHost, port, stopOwner));
            host.setStatus(tr("status.debug.attachQueued", port));
            return;
        }
        ownerStop = stopOwner;
        // On a remote anchor's host the adapter attaches from there: "localhost" is the host itself.
        if (!debugEffectiveFor("java", anchorFile)) {
            host.setStatus(unavailableMessage("java", anchorFile));
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
            // Java files whose tools run where the anchor's do: the attach goes through that machine's jdtls.
            if (b.getPath() != null
                    && "java".equals(b.getLanguage())
                    && (anchor == null
                            ? host.isLocalBuffer(b) && com.editora.process.ExecTargets.runsHere(b.getPath())
                            : com.editora.process.ExecTargets.forPath(b.getPath())
                                    .id()
                                    .equals(com.editora.process.ExecTargets.forPath(anchor)
                                            .id()))) {
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
        if (!debugEffectiveFor("java", b.getPath())) { // a remote file attaches from its host (review A9)
            host.setStatus(unavailableMessage("java", b.getPath()));
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
            int line = b.getFocusedArea().getCurrentParagraph();
            if (breakpointAllowed(b, line)) {
                b.toggleBreakpoint(line);
            }
        }
    }

    /**
     * Whether a breakpoint may go on {@code line} of {@code b}: anywhere a debugger can stop, which in a {@code .http}
     * file is only a script line (adl, 2026-10-16) — else says so. Removing one is always allowed.
     */
    private boolean breakpointAllowed(EditorBuffer b, int line) {
        if (b.getBreakpointManager().isBreakpoint(line)) {
            return true;
        }
        if (b.isHttpFile()) {
            if (com.editora.http.HttpScriptLines.isScriptLine(b.getContent(), line)) {
                return true;
            }
            host.setStatus(tr("status.debug.httpScriptLinesOnly"));
            return false;
        }
        // B8: a blank or comment-only line has no code to stop on. java-debug even called such a breakpoint
        // verified, and it never stopped.
        if (com.editora.editor.CodeLessLines.isCodeLess(b.getContent(), line, b.getLanguage())) {
            host.setStatus(tr("status.debug.noCodeOnLine"));
            return false;
        }
        return true;
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
        if (!breakpointAllowed(b, line)) {
            return;
        }
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
        TextField hitCondition = new TextField(bp.hitCondition());
        hitCondition.setPrefColumnCount(32);
        hitCondition.setPromptText(tr("dialog.debug.hitConditionPrompt"));
        CheckBox enabled = new CheckBox(tr("dialog.debug.breakpointEnabled"));
        enabled.setSelected(bp.enabled());

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.add(new Label(tr("dialog.debug.conditionLabel")), 0, 0);
        grid.add(condition, 1, 0);
        grid.add(new Label(tr("dialog.debug.logMessageLabel")), 0, 1);
        grid.add(logMessage, 1, 1);
        grid.add(new Label(tr("dialog.debug.hitConditionLabel")), 0, 2);
        grid.add(hitCondition, 1, 2);
        grid.add(enabled, 1, 3);
        grid.add(note(tr("dialog.debug.logMessageHint")), 1, 4);
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
                    mgr.setHitCondition(line, hitCondition.getText().trim());
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

    /**
     * Debug: Exception Breakpoints — a picker over the filters the adapter offers (the live session's
     * {@code exceptionBreakpointFilters}, else those that language's adapter is known to offer), each marked
     * when on; choosing one turns it on or off. A language starts with its adapter's defaults (debugpy: uncaught
     * exceptions), and a choice holds for that language's sessions from then on.
     */
    void toggleExceptionBreakpoints() {
        String language = exceptionLanguage();
        List<DapModels.ExceptionFilterOption> options =
                language == null ? List.of() : dapManager.exceptionFilterOptions(language);
        if (options.isEmpty()) {
            host.setStatus(tr("status.debug.exceptionsNoLanguage"));
            return;
        }
        QuickOpen<DapModels.ExceptionFilterOption> picker = new QuickOpen<>(
                tr("command.debug.toggleExceptionBreakpoints"),
                tr("debug.exceptions.prompt", debugAdapterLabel(language)),
                () -> options,
                o -> exceptionRow(language, o),
                DapModels.ExceptionFilterOption::filter,
                o -> {
                    if (o != null) {
                        toggleExceptionFilter(language, o.filter());
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** A picker row: the filter's label, marked when it is on for {@code language}. */
    String exceptionRow(String language, DapModels.ExceptionFilterOption o) {
        return (dapManager.exceptionFiltersFor(language).contains(o.filter()) ? "✓ " : "    ") + o.label();
    }

    /** Turns {@code language}'s exception filter {@code filter} on or off (the picker's choice), and says so. */
    void toggleExceptionFilter(String language, String filter) {
        restoreExceptionFilters();
        List<DapModels.ExceptionFilterOption> options = dapManager.exceptionFilterOptions(language);
        List<String> next = toggledFilter(dapManager.exceptionFiltersFor(language), filter, options);
        dapManager.setExceptionFilters(language, next);
        ops.persistDebugExceptionFilters(dapManager.exceptionChoices()); // C12: kept across restarts, per project
        refreshExceptionToggles();
        host.setStatus(exceptionStatus(language, next, options));
    }

    /** The adapter language the exception picker is about: the live session's, else the active file's. */
    private String exceptionLanguage() {
        if (dapManager.isActive()) {
            return dapManager.sessionLanguage();
        }
        EditorBuffer b = host.activeBuffer();
        String language = b == null ? null : b.getLanguage();
        return language != null && DapServerRegistry.isDebuggable(language) ? language : null;
    }

    // --- Caught / Uncaught exception toggles (Run menu + Debug window) ---------------------------------

    /** Whether the persisted exception choices have been handed to the debug manager. */
    private boolean exceptionFiltersRestored;

    /** Hands the exception choices this project saved to the debug manager, once (B10 / C12). */
    void restoreExceptionFilters() {
        if (exceptionFiltersRestored) {
            return;
        }
        exceptionFiltersRestored = true;
        ops.debugExceptionFilters().forEach((language, filters) -> {
            if (language != null && filters != null) {
                dapManager.setExceptionFilters(language, filters);
            }
        });
    }

    /**
     * The language the Caught / Uncaught toggles act on: the live session's, else the active file's — and Java
     * when neither says (the toggles are always there to set up the next Java session).
     */
    String exceptionToggleLanguage() {
        String language = exceptionLanguage();
        return com.editora.dap.ExceptionFilterCatalog.caughtFilter(language) == null ? "java" : language;
    }

    /** Whether the Caught ({@code caught}) or Uncaught toggle is on for {@link #exceptionToggleLanguage()}. */
    boolean exceptionToggleOn(boolean caught) {
        restoreExceptionFilters();
        String language = exceptionToggleLanguage();
        String filter = caught
                ? com.editora.dap.ExceptionFilterCatalog.caughtFilter(language)
                : com.editora.dap.ExceptionFilterCatalog.uncaughtFilter(language);
        return filter != null && dapManager.exceptionFiltersFor(language).contains(filter);
    }

    /** The Caught or Uncaught toggle (commands {@code debug.exceptions.caught} / {@code .uncaught}, panel). */
    void toggleExceptionKind(boolean caught) {
        String language = exceptionToggleLanguage();
        String filter = caught
                ? com.editora.dap.ExceptionFilterCatalog.caughtFilter(language)
                : com.editora.dap.ExceptionFilterCatalog.uncaughtFilter(language);
        toggleExceptionFilter(language, filter);
    }

    /** Shows the toggles' state in the Debug window (when it has been built). */
    private void refreshExceptionToggles() {
        if (debugPanel != null) {
            debugPanel.setExceptionToggles(exceptionToggleOn(true), exceptionToggleOn(false));
        }
    }

    /** Pure: {@code current} with {@code filter} turned on or off, in the order the adapter lists its filters. */
    static List<String> toggledFilter(
            List<String> current, String filter, List<DapModels.ExceptionFilterOption> options) {
        Set<String> on = new LinkedHashSet<>(current);
        if (!on.remove(filter)) {
            on.add(filter);
        }
        List<String> out = new ArrayList<>();
        for (DapModels.ExceptionFilterOption o : options) {
            if (on.remove(o.filter())) {
                out.add(o.filter());
            }
        }
        out.addAll(on); // anything the adapter no longer lists stays, last
        return out;
    }

    private String exceptionStatus(
            String language, List<String> filters, List<DapModels.ExceptionFilterOption> options) {
        if (filters.isEmpty()) {
            return tr("status.debug.exceptionFiltersNone", debugAdapterLabel(language));
        }
        List<String> labels = new ArrayList<>();
        for (String f : filters) {
            labels.add(options.stream()
                    .filter(o -> o.filter().equals(f))
                    .map(DapModels.ExceptionFilterOption::label)
                    .findFirst()
                    .orElse(f));
        }
        return tr("status.debug.exceptionFilters", debugAdapterLabel(language), String.join(", ", labels));
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
                    // Python's "path" is the interpreter that runs debugpy (and, when the project has none of
                    // its own, the program): say so rather than ask for a debugpy path it is not.
                    String label =
                            "python".equals(id) ? tr("settings.debug.pythonCommand") : tr("palette.setting.value");
                    host.promptText(debugAdapterLabel(id), label, debugAdapterPath(id), v -> {
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
        buffer.setGutterBreakpointClick((b, line) -> {
            if (breakpointAllowed(b, line)) {
                b.toggleBreakpoint(line);
            }
        });
        buffer.setOnBreakpointsChanged(() -> onBreakpointsChanged(buffer));
        // Hover value tooltip while suspended: evaluate the hovered identifier in the selected frame.
        buffer.setDebugHoverEvaluator((expr, cb) -> dapManager.evaluateHover(expr, debugFrameId, cb));
        // "Debug File" beside "Run File" in the editor's menu: Debug was only in the Run menu and the palette.
        buffer.setDebugFileHandler(
                () -> launchBuffer(buffer), () -> debugSupportEnabled() && isDebuggableBuffer(buffer));
        buffer.setGutterBreakpointMenu(this::showGutterMenu); // right-click a breakpoint: Edit / Disable / Remove
        buffer.setDebugMenuContributor(() -> editorMenuItems(buffer));
    }

    /** Focuses the Debug console's evaluate field ({@code debug.evaluate} command). */
    void focusEvaluate() {
        ops.openToolWindow();
        panel().focusEvaluate();
        Platform.runLater(panel()::focusEvaluate); // a window opened just now is laid out on the next pulse
    }

    /**
     * Ends the debugged program's standard input ({@code debug.endProgramInput}, and Ctrl+D in the console's
     * input field): whatever reads it sees end of file. Only a program Editora started itself has an input
     * to end — see {@link DapManager#setProgramConsole}.
     */
    void endProgramInput() {
        if (dapManager.closeProgramInput()) {
            panel().showInputEnded();
        } else {
            host.setStatus(tr("status.debug.noProgramInput"));
        }
    }

    void addWatch() {
        panel().addWatch();
    }

    void setSelectedValue() {
        panel().setSelectedValue();
    }

    // --- Java code changes and frame control: hot code replace, restart frame, smart step into -----------

    /** An Apply Code Changes is building or replacing; a save meanwhile is folded into one more round. */
    private boolean codeReplaceRunning;
    /** Another save arrived during {@link #codeReplaceRunning}: apply once more when it ends. */
    private Path codeReplaceAgain;
    /** Java files saved since the running code last matched them (re-baselined by a successful replace). */
    private final Set<Path> savedSinceReplace = new LinkedHashSet<>();
    /** java-debug's last ERROR notice, so a failed replace does not print the same reason twice. */
    private String lastCodeReplaceError = "";

    private DapManager.CodeReplaceMode codeReplaceMode() {
        return DapManager.CodeReplaceMode.of(host.settings().getDebugHotCodeReplace());
    }

    private static boolean isJavaSource(Path file) {
        return file != null
                && file.getFileName() != null
                && file.getFileName()
                        .toString()
                        .toLowerCase(java.util.Locale.ROOT)
                        .endsWith(".java");
    }

    /**
     * A user save (not an autosave) reached the language server. During a Java session in {@code auto} mode
     * the change goes into the running program at once and the console says what happened; in {@code manual}
     * mode the status bar says how to apply it. FX thread.
     */
    void sourceSaved(EditorBuffer buffer) {
        Path file = buffer == null ? null : buffer.getPath();
        if (!isJavaSource(file) || !dapManager.canReplaceCode()) {
            return;
        }
        savedSinceReplace.add(file);
        switch (codeReplaceMode()) {
            case AUTO -> requestCodeReplace(file, false);
            case MANUAL -> host.setStatus(tr("debug.hcr.manualHint"));
            case OFF -> {
                // the user chose to keep the running code as it is
            }
        }
    }

    /** A Java session started running: let jdtls build what it will compare saved changes with. */
    private void primeCodeReplace(DapManager.State state) {
        if (state == DapManager.State.RUNNING && codeReplaceMode() != DapManager.CodeReplaceMode.OFF) {
            dapManager.primeCodeReplace();
        }
    }

    /** {@code debug.applyCodeChanges}: loads what is saved into the running program (auto and manual modes). */
    void applyCodeChanges() {
        if (!dapManager.canReplaceCode()) {
            host.setStatus(tr("debug.hcr.noSession"));
            return;
        }
        if (codeReplaceMode() == DapManager.CodeReplaceMode.OFF) {
            host.setStatus(tr("debug.hcr.off"));
            return;
        }
        EditorBuffer active = host.activeBuffer();
        Path file = active != null && isJavaSource(active.getPath()) ? active.getPath() : dapManager.sessionFile();
        if (active != null && active.isDirty() && isJavaSource(active.getPath())) {
            panel().appendNotice(tr("debug.hcr.unsavedNote")); // only saved files are compiled
        }
        requestCodeReplace(file, true);
    }

    /** Starts a replace, or folds this request into the one running (one more round once it ends). */
    private void requestCodeReplace(Path file, boolean asked) {
        if (codeReplaceRunning) {
            codeReplaceAgain = file;
            return;
        }
        codeReplaceRunning = true;
        lastCodeReplaceError = "";
        Set<Path> covered = new LinkedHashSet<>(savedSinceReplace);
        host.setStatus(tr("debug.hcr.applying"));
        dapManager.applyCodeChanges(file, outcome -> {
            codeReplaceRunning = false;
            reportCodeReplace(outcome, covered, asked);
            Path again = codeReplaceAgain;
            codeReplaceAgain = null;
            if (again != null && dapManager.canReplaceCode()) {
                requestCodeReplace(again, false);
            }
        });
    }

    /** Says how a replace ended: status bar always, Debug console for anything the user must act on. */
    private void reportCodeReplace(DapManager.CodeReplaceOutcome outcome, Set<Path> covered, boolean asked) {
        switch (outcome.result()) {
            case APPLIED -> {
                String names = CodeReplaceText.classList(outcome.classes());
                panel().appendNotice(tr("debug.hcr.applied", names));
                host.setStatus(tr("debug.hcr.appliedStatus", names));
                savedSinceReplace.removeAll(covered);
                codeReplaced(covered);
            }
            case NOTHING_CHANGED -> {
                savedSinceReplace.removeAll(covered); // the program already runs what these files say
                codeReplaced(covered);
                host.setStatus(tr(asked ? "debug.hcr.nothing" : "debug.hcr.nothingQuiet"));
            }
            case COMPILE_ERRORS -> {
                panel().appendNotice(tr("debug.hcr.compileErrors"));
                host.setStatus(tr("debug.hcr.compileErrors"));
            }
            case FAILED -> {
                if (CodeReplaceText.saidAlready(outcome.message(), lastCodeReplaceError)) {
                    panel().appendNotice(tr("debug.hcr.restartNeeded")); // the adapter's event gave the reason
                } else {
                    panel().appendNotice(tr("debug.hcr.failed", outcome.message()));
                }
                host.setStatus(tr("debug.hcr.failedStatus"));
            }
            case BUILD_FAILED -> {
                panel().appendNotice(tr("debug.hcr.buildFailed"));
                host.setStatus(tr("debug.hcr.buildFailed"));
            }
            case NO_SESSION -> host.setStatus(tr("debug.hcr.noSession"));
        }
    }

    /**
     * The running program now runs what {@code files} say on disk: a successful replace, or one that found
     * nothing to change. Breakpoint and frame lines of these files no longer need mapping to launch-time lines.
     */
    private void codeReplaced(Set<Path> files) {
        rebaselineLines(files); // B1: the code that runs matches the saved file again
    }

    /**
     * java-debug's own hot code replace events. A replace it could not finish leaves the program on the old code
     * somewhere: that is said in the console. Progress events need no words — Editora reports the outcome.
     */
    private void codeReplaceNotice(String changeType, String message) {
        String text = CodeReplaceText.notice(changeType, message);
        if ("ERROR".equals(changeType)) {
            lastCodeReplaceError = message == null ? "" : message.strip();
        }
        if (text != null) {
            panel().appendNotice(tr("debug.hcr.notice", text));
        }
    }

    /**
     * {@code debug.restartFrame}: runs the selected call-stack frame's method again from its start (the top frame
     * when none is selected). Paused Java sessions only; the adapter says when a frame cannot be restarted.
     */
    void restartFrame() {
        restartFrame(shownFrame);
    }

    private void restartFrame(DapModels.StackFrameInfo frame) {
        if (dapManager.state() != DapManager.State.SUSPENDED || frame == null) {
            host.setStatus(tr("debug.frame.notPaused"));
            return;
        }
        if (!dapManager.supportsRestartFrame()) {
            host.setStatus(tr("debug.restartFrame.unsupported"));
            return;
        }
        dapManager.restartFrame(frame.id());
    }

    /**
     * Whether the call-stack menu offers Restart Frame for {@code frame} of {@code frames}: a paused session whose
     * adapter restarts frames, and not the bottom frame of a complete stack (nothing called it, so nothing can
     * call it again).
     */
    static boolean canRestartFrame(
            boolean suspended,
            boolean supported,
            DapModels.StackFrameInfo frame,
            List<DapModels.StackFrameInfo> frames) {
        if (!suspended || !supported || frame == null || frames == null || frames.isEmpty()) {
            return false;
        }
        int index = frames.indexOf(frame);
        return index >= 0 && index < frames.size() - 1;
    }

    /** The call-stack context menu for {@code frame} (Restart Frame). */
    List<javafx.scene.control.MenuItem> stackMenuItems(
            DapModels.StackFrameInfo frame, List<DapModels.StackFrameInfo> frames) {
        javafx.scene.control.MenuItem restart = new javafx.scene.control.MenuItem(tr("debugpanel.restartFrame"));
        restart.setDisable(!canRestartFrame(
                dapManager.state() == DapManager.State.SUSPENDED, dapManager.supportsRestartFrame(), frame, frames));
        restart.setOnAction(e -> restartFrame(frame));
        return List.of(restart);
    }

    /** The last Smart Step Into picker shown (null before the first). */
    private QuickOpen<DapModels.StepTarget> smartStepPicker;

    /**
     * {@code debug.smartStepInto}: lists the calls on the current line and steps into the one picked — straight
     * into it when there is only one. Plain Step Into is unchanged.
     */
    void smartStepInto() {
        if (dapManager.state() != DapManager.State.SUSPENDED) {
            host.setStatus(tr("debug.frame.notPaused"));
            return;
        }
        if (!dapManager.supportsStepInTargets()) {
            host.setStatus(tr("debug.smartStep.unsupported"));
            return;
        }
        dapManager.stepInTargets(targets -> {
            if (targets.isEmpty()) {
                host.setStatus(tr("debug.smartStep.none"));
            } else if (targets.size() == 1) {
                dapManager.stepIntoTarget(targets.get(0).id());
            } else {
                QuickOpen<DapModels.StepTarget> picker = smartStepPicker = new QuickOpen<>(
                        tr("debug.smartStep.title"),
                        tr("debug.smartStep.prompt"),
                        () -> targets,
                        DapModels.StepTarget::label,
                        t -> t.line() >= 0 ? tr("debug.smartStep.line", t.line() + 1) : "",
                        t -> dapManager.stepIntoTarget(t.id()));
                picker.setOverlayHost(host.overlayHost());
                picker.show(host.window());
            }
        });
    }

    // --- review C: breakpoints list, mute, gutter and editor menus, Show Value -----------------------------

    /** Mute Breakpoints: the adapter is told no breakpoints until it is turned off again (this window only). */
    private boolean breakpointsMuted;

    boolean breakpointsMuted() {
        return breakpointsMuted;
    }

    /** Debug: Breakpoints — the palette-style list of every breakpoint (see {@link BreakpointsPicker}). */
    void showBreakpoints() {
        new BreakpointsPicker(host, ops, dapManager, this::toggleMuteBreakpoints, () -> breakpointsMuted).show();
    }

    /**
     * Debug: Mute Breakpoints — none of them stops the program until muted again; a live session is told at once
     * (every file it knows is sent again: empty while muted, its enabled breakpoints after).
     */
    void toggleMuteBreakpoints() {
        breakpointsMuted = !breakpointsMuted;
        if (dapManager.isActive()) {
            Set<String> open = new HashSet<>();
            host.forEachBuffer(b -> {
                if (b.getPath() != null && open.add(key(b.getPath()))) {
                    DapModels.FileBreakpoints fb = fileBreakpoints(b);
                    sentBreakpoints.put(b.getPath(), fb);
                    dapManager.updateBreakpoints(fb);
                }
            });
            for (DapModels.FileBreakpoints fb : closedBreakpoints) {
                if (fb.file() != null && !open.contains(key(fb.file()))) {
                    dapManager.updateBreakpoints(
                            breakpointsMuted ? new DapModels.FileBreakpoints(fb.file(), List.of()) : fb);
                }
            }
        }
        panel().setBreakpointsMuted(breakpointsMuted);
        host.setStatus(tr(breakpointsMuted ? "status.debug.breakpointsMuted" : "status.debug.breakpointsUnmuted"));
    }

    /** Debug: Enable / Disable Breakpoint — flips the caret line's breakpoint without removing it. */
    void toggleBreakpointEnabledAtCaret() {
        EditorBuffer b = breakpointBuffer();
        if (b == null) {
            return;
        }
        int line = b.getFocusedArea().getCurrentParagraph();
        var mgr = b.getBreakpointManager();
        if (!mgr.isBreakpoint(line)) {
            host.setStatus(tr("status.debug.noBreakpointHere"));
            return;
        }
        mgr.setEnabled(line, !mgr.get(line).enabled());
        b.refreshGutterLine(line);
    }

    /** Debug: Show Value at Caret — the value popup for the name at the caret, from the keyboard. */
    void showValueAtCaret() {
        EditorBuffer b = host.activeBuffer();
        if (dapManager.state() != DapManager.State.SUSPENDED) {
            host.setStatus(tr("palette.disabled.needsSuspended"));
        } else if (b == null || !b.showDebugValueAtCaret()) {
            host.setStatus(tr("status.debug.noValueAtCaret"));
        }
    }

    /**
     * F11 in the CUA and Sublime keymaps: Step Into while the program is paused, Full Screen otherwise — what F11
     * does in VS Code, whose debugging keys those keymaps now carry (review C8).
     */
    void stepIntoOrElse(Runnable otherwise) {
        if (debugSupportEnabled() && dapManager.state() == DapManager.State.SUSPENDED) {
            dapManager.stepInto();
        } else {
            otherwise.run();
        }
    }

    /** A right-click on a breakpoint glyph: Edit…, Enable / Disable, Remove (review C11). */
    private void showGutterMenu(EditorBuffer b, int line, javafx.scene.input.ContextMenuEvent e) {
        var mgr = b.getBreakpointManager();
        if (!mgr.isBreakpoint(line)) {
            return;
        }
        javafx.scene.control.MenuItem edit = new javafx.scene.control.MenuItem(tr("command.debug.editBreakpoint"));
        edit.setOnAction(ev -> {
            b.getFocusedArea().moveTo(line, 0);
            editBreakpointAtCaret();
        });
        boolean enabled = mgr.get(line).enabled();
        javafx.scene.control.MenuItem flip =
                new javafx.scene.control.MenuItem(tr(enabled ? "debug.gutter.disable" : "debug.gutter.enable"));
        flip.setOnAction(ev -> {
            mgr.setEnabled(line, !enabled);
            b.refreshGutterLine(line);
        });
        javafx.scene.control.MenuItem remove = new javafx.scene.control.MenuItem(tr("debug.gutter.remove"));
        remove.setOnAction(ev -> {
            if (mgr.isBreakpoint(line)) {
                b.toggleBreakpoint(line);
            }
        });
        javafx.scene.control.MenuItem list = new javafx.scene.control.MenuItem(tr("command.debug.breakpoints"));
        list.setOnAction(ev -> showBreakpoints());
        gutterMenu.getItems().setAll(edit, flip, remove, new javafx.scene.control.SeparatorMenuItem(), list);
        Node source = e.getSource() instanceof Node n ? n : null;
        if (source != null && source.getScene() != null && source.getScene().getWindow() != null) {
            gutterMenu.show(source, e.getScreenX(), e.getScreenY());
        } else if (host.window() != null) {
            gutterMenu.show(host.window(), e.getScreenX(), e.getScreenY());
        }
    }

    private final javafx.scene.control.ContextMenu gutterMenu = new javafx.scene.control.ContextMenu();

    /**
     * The editor's right-click debugger items (review C10): Toggle / Edit Breakpoint on a debuggable file, and while
     * paused Run to Cursor, Show Value and Add to Watches for the name at the click. Empty when debugging is off.
     */
    List<javafx.scene.control.MenuItem> editorMenuItems(EditorBuffer b) {
        List<javafx.scene.control.MenuItem> items = new ArrayList<>();
        if (!debugSupportEnabled() || !isDebuggableBuffer(b)) {
            return items;
        }
        if (!b.isRunnable()
                && b.getPath() != null
                && "java".equals(b.getLanguage())
                && JavaProjectRoot.find(b.getPath()) == null) {
            // A classic main class in a plain folder has no Run File / Debug File entry of its own (those are
            // for runnable files); Debug was then only in the Run menu and the palette.
            javafx.scene.control.MenuItem debugFile = new javafx.scene.control.MenuItem(tr("editmenu.debugFile"));
            debugFile.setOnAction(e -> launchBuffer(b));
            items.add(debugFile);
        }
        items.add(menuItem("debug.toggleBreakpoint", this::toggleBreakpointAtCaret));
        items.add(menuItem("debug.editBreakpoint", this::editBreakpointAtCaret));
        if (dapManager.state() == DapManager.State.SUSPENDED) {
            items.add(menuItem("debug.runToCursor", this::debugRunToCursor));
            items.add(menuItem("debug.showValue", this::showValueAtCaret));
            var area = b.getFocusedArea();
            String selected = area.getSelectedText();
            String expression = selected != null && !selected.isBlank() && !selected.contains("\n")
                    ? selected.strip()
                    : com.editora.editor.DebugExpressions.atCaret(area);
            if (expression != null) {
                javafx.scene.control.MenuItem watch =
                        new javafx.scene.control.MenuItem(tr("debugpanel.addToWatches", expression));
                watch.setOnAction(ev -> {
                    ops.openToolWindow();
                    panel().addWatchPrompt(expression);
                });
                items.add(watch);
            }
        }
        return items;
    }

    private static javafx.scene.control.MenuItem menuItem(String commandId, Runnable action) {
        javafx.scene.control.MenuItem item =
                new javafx.scene.control.MenuItem(DebugPanelText.bareTitle(tr("command." + commandId)));
        item.setOnAction(e -> action.run());
        return item;
    }
}
