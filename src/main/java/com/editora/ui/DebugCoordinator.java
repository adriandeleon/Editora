package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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

import com.editora.config.Breakpoint;
import com.editora.config.BreakpointStore;
import com.editora.config.PathKeys;
import com.editora.config.RunConfiguration;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DapServerRegistry;
import com.editora.dap.TypeRenderer;
import com.editora.editor.EditorBuffer;
import com.editora.editor.UiZoom;
import com.editora.editor.debug.BreakpointManager;
import com.editora.lsp.LspManager;
import com.editora.run.ProgramArgs;
import com.editora.run.StackTraceLinks;
import com.editora.vfs.Vfs;
import org.jspecify.annotations.Nullable;

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

        /** Debugs a configuration that runs on a deployment: deployed, started on its host, attached here. */
        default void debugDeployed(RunConfiguration cfg) {}

        /** The active keymap's chord for {@code commandId}, formatted, or null when it is unbound. */
        default @Nullable String chordFor(String commandId) {
            return null;
        }

        /** Debug Request on the active {@code .http} file: its scripts under the debugger (HTTP Client). */
        default void debugHttpRequest() {}

        /** Opens the Run Configurations page on {@code name} — see {@code RunCoordinator.Ops}. */
        void editConfiguration(String name);

        /** What {@link #buildExecutable} asks of the disk, asked and nothing else (see {@code Looked.allKnown}). */
        default void buildExecutableFacts(com.editora.build.BuildTool tool, Path root) {}

        /**
         * The argv prefix a launch may start {@code tool} with from {@code root} (the tasks tree's choice after
         * its trust gate), or null when the user declined to trust the project's wrapper — see
         * {@code RunCoordinator.Ops#buildExecutable}.
         */
        default @Nullable List<String> buildExecutable(com.editora.build.BuildTool tool, Path root) {
            return List.of(tool.commandExample());
        }

        /**
         * Whether a build may run the code of {@code root} — a CMake or Make project, which has no build tool of its
         * own here — after asking, as a build tool's first build does ({@code items}: what will run, one line
         * each); false when the user declined.
         */
        default boolean trustBuild(Path root, List<String> items) {
            return true;
        }

        void toggleToolWindow();

        void setToolWindowAvailable(boolean available);

        /** Status-bar debug-state segment ({@code null} clears it). */
        void setStatusDebug(@Nullable String text);

        /** Status-bar indeterminate loading bar (a session is starting). */
        void setStatusDebugLoading(boolean loading);

        /** A test stand-in's save (dirty → write, untitled → Save-As), answered at once; the app uses saveThen. */
        default boolean saveBuffer(EditorBuffer buffer) {
            return false;
        }

        /** {@link #saveBuffer} without waiting: the write runs off the FX thread, {@code then} hears how it went. */
        default void saveThen(EditorBuffer buffer, Consumer<Boolean> then) {
            then.accept(saveBuffer(buffer));
        }

        /** The remembered program-arguments string for {@code path} (shared with the Run feature). */
        String programArgs(@Nullable Path path);

        /** This window's saved run configurations (none outside a project window). */
        default List<RunConfiguration> runConfigurations() {
            return List.of();
        }

        /** This window's project root (null: none): a Python program's venv and {@code PYTHONPATH}. */
        default @Nullable Path projectRoot() {
            return null;
        }

        /**
         * Opens {@code content} in a read-only tab titled {@code title}, highlighted as {@code language}: a source the
         * debugger has that is not a file. Null where the window has no tabs to open it in.
         */
        default @Nullable EditorBuffer openReadOnlyDoc(String title, String content, String language) {
            return null;
        }

        /** Brings {@code buffer}'s tab to the front; false when it has none (it was closed). */
        default boolean selectBufferTab(EditorBuffer buffer) {
            return false;
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
        void openPath(@Nullable Path file);

        /** Whether the Debug tool window is showing (it is then never closed just because the tab changed). */
        default boolean isToolWindowOpen() {
            return false;
        }

        /** The open buffer for {@code file} (canonical-tab match), or {@code null} when no tab holds it. */
        @Nullable
        EditorBuffer bufferForPath(@Nullable Path file);

        /** Runs {@code action} once an asynchronously opened buffer has received its initial document. */
        default void afterBufferLoad(EditorBuffer buffer, Runnable action) {
            action.run();
        }

        /** The persisted debug-watch expressions (workspace state). */
        List<String> debugWatches();

        /** Persists the debug-watch expressions (workspace state + durable save). */
        void persistDebugWatches(List<String> watches);

        /** The persisted paused watches (a subset of {@link #debugWatches}). */
        default List<String> debugPausedWatches() {
            return List.of();
        }

        /** Persists the paused watches. */
        default void persistDebugPausedWatches(List<String> paused) {}

        /** The persisted expressions evaluated in the evaluate fields, oldest first. */
        default List<String> debugEvaluateHistory() {
            return List.of();
        }

        /** Persists the evaluate fields' history. */
        default void persistDebugEvaluateHistory(List<String> history) {}

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

        /** This project's compounds: run configurations debugged together (workspace state). */
        default List<com.editora.config.RunCompound> runCompounds() {
            return List.of();
        }

        /** Opens the compounds' editor on compound {@code name} (null: a new one). */
        default void editCompounds(@Nullable String name) {}

        /** The addresses Attach reached in this project, most recent first (workspace state). */
        default List<String> debugAttachTargets() {
            return List.of();
        }

        /** Persists the attach addresses (workspace state + save). */
        default void persistDebugAttachTargets(List<String> targets) {}

        /** The pages Debug in Browser opened in this project, most recent first (workspace state). */
        default List<String> debugBrowserUrls() {
            return List.of();
        }

        /** Persists the pages Debug in Browser opened (workspace state + save). */
        default void persistDebugBrowserUrls(List<String> urls) {}

        /**
         * Serves an HTML buffer's page for the browser debugger, as the live preview serves it (its text live, edits
         * reloading it): {@code onPage} gets its URL and the folder served, a {@code file:} URL (no folder) while
         * the preview is off, or null after the status says why it cannot be.
         */
        default void servePage(
                EditorBuffer b, java.util.function.Consumer<HtmlPreviewCoordinator.@Nullable Served> onPage) {
            Path file = b.getPath();
            onPage.accept(
                    file == null
                            ? null
                            : new HtmlPreviewCoordinator.Served(file.toUri().toString(), null));
        }

        /** Whether Mute Breakpoints was left on for this project (workspace state). */
        default boolean debugBreakpointsMuted() {
            return false;
        }

        /** Persists Mute Breakpoints (workspace state + save). */
        default void persistDebugBreakpointsMuted(boolean muted) {}

        /** Saves Settings' type renderers ({@code debugTypeRenderers}). */
        default void persistDebugTypeRenderers(List<com.editora.dap.TypeRenderer> renderers) {}

        /** Saves Settings' Don't step into filters ({@code debugStepFilterList}). */
        default void persistDebugStepFilters(List<com.editora.dap.StepFilter> filters) {}

        /** Saves Go's Hide Runtime Goroutines and goroutine filter ({@code debugGo…}). */
        default void persistGoGoroutines(boolean hideRuntime, String filter) {}

        /** The per-file breakpoint map ({@code breakpoints.json}); path-string → that file's breakpoints. */
        Map<String, List<Breakpoint>> breakpointMap();

        /** The values pinned to {@code fileKey}'s lines ({@code breakpoints.json}). */
        default List<com.editora.config.PinnedLine> pins(String fileKey) {
            return List.of();
        }

        /** Replaces the values pinned to {@code fileKey}'s lines (none: removed) and writes {@code breakpoints.json}. */
        default void persistPins(String fileKey, List<com.editora.config.PinnedLine> pins) {}

        /** This project's method breakpoints and field watchpoints ({@code breakpoints.json}). */
        default List<com.editora.config.MemberBreakpoint> memberBreakpoints() {
            return List.of();
        }

        /** Replaces this project's method breakpoints and field watchpoints, and writes {@code breakpoints.json}. */
        default void persistMemberBreakpoints(List<com.editora.config.MemberBreakpoint> breakpoints) {}

        /** This project's exception breakpoints, in the order they were added. */
        default List<com.editora.config.ExceptionBreakpoint> exceptionBreakpoints() {
            return List.of();
        }

        /** Replaces this project's exception breakpoints, and writes {@code breakpoints.json}. */
        default void persistExceptionBreakpoints(List<com.editora.config.ExceptionBreakpoint> breakpoints) {}

        /** Writes {@code breakpoints.json}. */
        void saveBreakpoints();

        /**
         * This window rewrote {@code fileKey}'s breakpoints ({@code null}: several files) in its bucket: the
         * other windows re-read them (see {@link DebugCoordinator#breakpointsChangedElsewhere}).
         */
        default void breakpointsStored(Map<String, ?> bucket, @Nullable String fileKey) {}
    }

    /** Inline-value fetch cap — frames can hold hundreds of locals; the overlay needs a name→value map. */
    private static final int MAX_INLINE_VALUES = 100;

    final CoordinatorHost host;
    /** The window's debug sessions; the focused one is shown and acted on ({@link #s()}). */
    final DebugSessions sessions;

    final LspManager lspManager;
    final LspCoordinator lsp;
    final Ops ops;
    /** The console font to apply when the panel is built (set by every settings apply, panel or not). */
    private @Nullable String consoleFontFamily;

    private int consoleFontSize;

    /**
     * The before-launch steps of the session being handled ({@link #s()}), made on first use. Each session has its
     * own, on its own service rather than the Run console's: debugging is not refused because a program runs there,
     * and the sessions of a compound build at the same time. What a step hands back runs as its session's.
     */
    BeforeLaunchStep beforeLaunch() {
        DebugSession session = s();
        BeforeLaunchStep step = session.beforeLaunch;
        if (step == null) {
            com.editora.run.RunService service = new com.editora.run.RunService();
            session.beforeLaunchService = service;
            step = new BeforeLaunchStep(service);
            step.setContext(() -> r -> () -> inSession(session, r));
            session.beforeLaunch = step;
        }
        return step;
    }

    /** A before-launch step of the session being handled is running: its Stop stops that step. */
    boolean beforeLaunchActive() {
        BeforeLaunchStep step = s().beforeLaunch;
        return step != null && step.isActive();
    }

    /** The java-debug bundle jars last pushed to the LSP layer — restart jdtls only when this changes. */
    private List<String> appliedDebugBundles = List.of();

    /** The buffer currently carrying the execution-line highlight (cleared on resume/terminate). */
    @Nullable
    EditorBuffer execHighlightBuffer;

    /** The buffer currently carrying inline values + an active hover (cleared on resume/terminate). */
    private @Nullable EditorBuffer debugValuesBuffer;

    // Coalesce the per-edit (line-shift) breakpoint persist off the FX hot path — see schedulePersistBreakpoints.
    // Only the synchronous breakpoints.json write is debounced; the adapter update stays immediate. (#551)
    private final javafx.animation.PauseTransition persistDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
    private final Set<EditorBuffer> pendingPersist = new LinkedHashSet<>();

    /**
     * The other tabs' triggered breakpoints, looked at again once a shift made by typing settles rather than per
     * newline (perf review 2026-11-23, D9): a shift changes no id, so nothing waits for another breakpoint than
     * before; only the line a waiting breakpoint's tooltip names ("waits for Main.java:12") moves.
     */
    private final javafx.animation.PauseTransition triggersDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));

    private final Set<EditorBuffer> pendingTriggers = new LinkedHashSet<>();

    /** A breakpoint condition or log message the adapter could not evaluate, and what it said about it. */
    record EvaluationFailure(String expression, String message) {}

    /** {@link #relaunch} of a session attached to a JVM a build or test run started: it cannot be redone. */
    static final Runnable ONE_SHOT_ATTACH = () -> {};

    /**
     * {@link #relaunch} of a session attached to an HTTP Client script: redoing it would send the request again,
     * which is Debug Request's to do, not Restart's.
     */
    private static final Runnable HTTP_SCRIPT_ATTACH = () -> {};

    /** {@link #relaunch} of a subprocess's session: its program starts it again, not Restart. */
    private static final Runnable SUBPROCESS = () -> {};

    /**
     * The session whose event, reply, panel action or launch step is being handled ({@link #inSession}); else the
     * focused one, which the Debug window shows and the debug commands act on.
     */
    DebugSession s() {
        DebugSession d = dispatching;
        return d != null ? d : sessions.focused();
    }

    /** The debug manager of {@link #s()}. */
    DapManager dap() {
        return s().dap;
    }

    /** The session being handled while one is ({@link #inSession}); null: the focused one. FX thread. */
    private @Nullable DebugSession dispatching;

    /** Runs {@code r} as {@code session}'s: {@link #s()} and {@link #dap()} are that session's meanwhile. */
    void inSession(DebugSession session, Runnable r) {
        DebugSession before = dispatching;
        dispatching = session;
        try {
            r.run();
        } finally {
            dispatching = before;
        }
    }

    /** As {@link #inSession(DebugSession, Runnable)}, for an answer. */
    <T> T inSession(DebugSession session, java.util.function.Supplier<T> r) {
        DebugSession before = dispatching;
        dispatching = session;
        try {
            return r.get();
        } finally {
            dispatching = before;
        }
    }

    /** Runs {@code r} later on the FX thread, as the session being handled now ({@link #s()}). */
    void later(Runnable r) {
        DebugSession session = s();
        Platform.runLater(() -> inSession(session, r));
    }

    /**
     * Starts {@code work} on a virtual thread called {@code name}, given the session being handled now (read here, on
     * the FX thread) so that what it hands back runs as that session's ({@link #laterIn}).
     */
    void startOffFx(String name, Function<DebugSession, Runnable> work) {
        Runnable job = work.apply(s());
        Thread.ofVirtual().name(name).start(job);
    }

    /** Python launches: Run's plan for debugpy, and Debug of a Python run configuration. */
    final PythonDebugLaunch python = new PythonDebugLaunch(this);

    final InstructionBreakpoints instructionBreakpoints = new InstructionBreakpoints(this);
    private final MemoryEdits memoryEdits = new MemoryEdits(this);

    final DartDebugLaunch dart = new DartDebugLaunch(this); // Dart, and a Flutter app's Dart
    final ElixirDebugLaunch elixir = new ElixirDebugLaunch(this); // a Mix project's tests, scripts and app
    final PowerShellDebugLaunch powershell = new PowerShellDebugLaunch(this); // .ps1 on PowerShell Editor Services
    final ElectronDebugLaunch electron = new ElectronDebugLaunch(this); // an Electron app: main + renderer
    final RemoteAttachLaunch remote = new RemoteAttachLaunch(this); // a native program under a debug server elsewhere
    final RemoteDelveLaunch delve = new RemoteDelveLaunch(this); // a Go program under a headless Delve
    /** launch.json's serverReadyAction and postDebugTask ({@link LaunchFollowUps}). */
    final LaunchFollowUps followUps = new LaunchFollowUps(this);

    final BrowserDebugLaunch browser = new BrowserDebugLaunch(this); // a page in Chrome, Edge, Firefox…
    final RubyDebugLaunch ruby = new RubyDebugLaunch(this); // Ruby scripts, RSpec, Minitest, a Rails app's server
    final PhpDebugLaunch php = new PhpDebugLaunch(this); // PHP files, web apps, PHPUnit tests, Listen for Xdebug
    final DebugFileLaunch fileLaunch = new DebugFileLaunch(this); // F5, Debug File, Debug Test at Caret
    final CompiledDebugLaunch compiled = new CompiledDebugLaunch(this); // Go, Rust, C/C++, C#: built, then debugged
    final JavaDebugLaunch javaLaunch = new JavaDebugLaunch(this); // Java main classes, run configurations, build first
    final LaunchJsonSessions launchJson = new LaunchJsonSessions(this); // launch.json, compounds, own adapters
    final DebugAttach attach = new DebugAttach(this); // Attach, Reattach, Attach to Process, Open Core Dump
    final NodeDebugLaunch node = new NodeDebugLaunch(this); // a .js or .ts file, a node or npm configuration
    final MapDebugFollow mapFollow = new MapDebugFollow(this); // the Project Map: stops, card breakpoint strips
    final CaretBreakpoints caretBreakpoints = new CaretBreakpoints(this); // breakpoint commands on the caret line
    final JavaCodeChanges javaCode = new JavaCodeChanges(this); // hot code replace, Restart Frame, Smart Step Into
    final DisassemblyMemoryViews lowLevel = new DisassemblyMemoryViews(this); // Disassembly, Memory, steps
    final ExecutionLine execLine = new ExecutionLine(this); // the execution line in the editor and its exception card
    final ValueWindows valueWindows = new ValueWindows(this); // the Evaluate and View as Table windows
    final BreakpointMenus breakpointMenus =
            new BreakpointMenus(this); // breakpoints list, mute, gutter and editor menus
    final ValuePopups valuePopups = new ValuePopups(this); // expression-field completion, the value popup

    /** The build facts a launch asks: looked at off the FX thread first when not known ({@link DebugFacts}). */
    final DebugFacts facts = new DebugFacts(this::executableFacts, this::sayBusy, this::lookFailed);

    /** The scans of a document a debug command decides by, made off the FX thread (PERF-01). */
    private final DebugTextScans scans = new DebugTextScans();

    private void executableFacts(com.editora.build.BuildTool tool, Path root) {
        ops.buildExecutableFacts(tool, root);
    }

    private void lookFailed(Exception why) {
        host.setStatus(
                tr("status.debug.lookFailed", why.getMessage() == null ? String.valueOf(why) : why.getMessage()));
    }

    private void sayBusy() {
        host.setStatus(tr("status.run.busy"));
    }

    boolean factsKnown(String name, Runnable asks, Runnable again) {
        DebugSession session = s();
        return facts.known(name, session, asks, () -> inSession(session, again));
    }

    /** Runs {@code r} later on the FX thread as {@code session}'s; callable from any thread. */
    void laterIn(DebugSession session, Runnable r) {
        Platform.runLater(() -> inSession(session, r));
    }

    /**
     * {@link DebugPlanning#offFx}, its answer handled as the session being handled now: a launch's plan lands after
     * the user (or a compound's next member) moved focus, and must start the session that asked for it.
     */
    <T> void planOffFx(String name, java.util.concurrent.Callable<T> work, Consumer<DebugPlanning.Answer<T>> then) {
        DebugSession session = s();
        session.stepPending(); // until it answers (it always does), a launch of this session is getting ready
        Consumer<DebugPlanning.Answer<T>> asked = asThisSession(then);
        DebugPlanning.offFx(name, work, answer -> {
            session.stepAnswered();
            asked.accept(answer);
            session.launchStepDone(); // nothing more pending: the launch has started, or stopped
        });
    }

    /**
     * {@code c}, run as the session being handled now ({@link #s()}) whenever it is called; not at all once that
     * session is gone (its tab closed, or made room for another), when a launch it was planning has no tab to run in.
     */
    <T extends @Nullable Object> Consumer<T> asThisSession(Consumer<T> c) {
        DebugSession session = s();
        return value -> {
            if (sessions.all().contains(session)) {
                inSession(session, () -> c.accept(value));
            }
        };
    }

    /** {@code r}, run as the session being handled now whenever it runs, as {@link #asThisSession} does. */
    Runnable bound(Runnable r) {
        Consumer<Object> c = asThisSession(ignored -> r.run());
        return () -> c.accept(null);
    }

    /** {@link DebugTextScans#scan}, its retry run as the session that asked (a save, a plan may come between). */
    <T> DebugTextScans.@Nullable Known<T> scan(
            EditorBuffer b, String what, Function<String, @Nullable T> fn, Runnable retry) {
        return scans.scan(b, what, fn, bound(retry));
    }

    /** Runs {@code r} as each session's in turn, oldest first. */
    void forEachSession(Runnable r) {
        for (DebugSession session : sessions.all()) {
            inSession(session, r);
        }
    }

    /**
     * Whether the session being handled is the one the window shows: only its stop marks the editor (execution line,
     * inline values, the exception card, breakpoint states) and the status bar's debug state.
     */
    boolean shown() {
        return s() == sessions.focused();
    }

    /**
     * Ties {@code session}'s manager to this window: its events, replies, code-replace notices and its panel's actions
     * are each handled as that session's ({@link #inSession}), whichever one is focused.
     */
    private void setUp(DebugSession session) {
        DapManager.Listener events = dapListener();
        session.dap.setListener(inSessionProxy(DapManager.Listener.class, events, session));
        session.dap.setCallbackWrapper(r -> () -> inSession(session, r));
        session.dap.setCodeReplaceNotices(
                (type, message) -> inSession(session, () -> javaCode.codeReplaceNotice(type, message)));
        session.dap.setBreakpointsSupplier(this::collectBreakpoints);
    }

    /** {@code target} behind {@code type}, each of whose calls runs as {@code session}'s. */
    private <T> T inSessionProxy(Class<T> type, T target, DebugSession session) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> type.getSimpleName() + " of " + session;
                        };
                    }
                    return inSession(session, () -> {
                        try {
                            return method.invoke(target, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            if (e.getCause() instanceof RuntimeException re) {
                                throw re;
                            }
                            if (e.getCause() instanceof Error err) {
                                throw err;
                            }
                            throw new IllegalStateException(e.getCause());
                        } catch (IllegalAccessException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                }));
    }

    /** The window's debug sessions. */
    DebugSessions sessions() {
        return sessions;
    }

    DebugCoordinator(CoordinatorHost host, DapManager dapManager, LspManager lspManager, LspCoordinator lsp, Ops ops) {
        this.host = host;
        this.sessions = new DebugSessions(dapManager);
        this.lspManager = lspManager;
        this.lsp = lsp;
        this.ops = ops;
        persistDebounce.setOnFinished(e -> flushPendingPersist());
        triggersDebounce.setOnFinished(e -> {
            List<EditorBuffer> pending = List.copyOf(pendingTriggers);
            pendingTriggers.clear();
            pending.forEach(this::triggersChanged);
        });
        setUp(sessions.focused());
        memberBreakpoints = new MemberBreakpointCommands(
                host, ops, this::dap, this::allManagers, lspManager, () -> breakpointsMuted);
        memberMarks = new MemberBreakpointMarks(host, ops, lspManager);
        memberBreakpoints.setOnChanged(memberMarks::refreshAll);
        lambdaBreakpoints = new LambdaBreakpoints(host);
        loadedSources = new LoadedSourcesPicker(
                host,
                ops,
                this::dap,
                () -> debugAdapterLabel(dap().sessionLanguage()),
                source -> openReferenceSource(source, doc -> {}));
        triggered = new TriggeredBreakpoints(host, ops);
        groups = new BreakpointGroups(
                host, ops, breakpointMenus::breakpointsPicker, memberBreakpoints, breakpointMenus::sendClosed);
        stacks = new DebugStacks(this);
        // Delve's binaries of earlier sessions (it leaves them when Stop ends one) go; a recent one may still run.
        Thread.ofVirtual().name("editora-dlv-cleanup").start(com.editora.dap.GoLaunch::deleteStaleDebugBinaries);
        pinnedValues = new PinnedValues(host, ops, this::dap);
        // Each stop's pinned values are kept with it in Debug History.
        pinnedValues.setOnPainted((b, shown) -> {
            if (s().panel != null && b.getPath() != null) {
                s().panel.recordPins(b.getPath(), shown);
            }
        });
    }

    /** Values pinned to lines. */
    private final PinnedValues pinnedValues;

    /** Thread Dump and Parallel Stacks. */
    final DebugStacks stacks;

    PinnedValues pinnedValues() {
        return pinnedValues;
    }

    /** Debug: Pin Value to Line — the selection or the expression at the caret, on the caret's line. */
    void togglePinAtCaret() {
        if (debugSupportEnabled()) {
            pinnedValues.togglePinAtCaret();
        } else {
            host.setStatus(tr("statusbar.tip.debugDisabled"));
        }
    }

    /** Debug: Unpin Values on Line. */
    void unpinLine() {
        pinnedValues.unpinLine();
    }

    /** Method breakpoints and field watchpoints. */
    final MemberBreakpointCommands memberBreakpoints;

    /** Their marks on the declaration lines of the open files. */
    private final MemberBreakpointMarks memberMarks;

    /** Places the method breakpoints' and field watchpoints' marks again in every open file. */
    void memberMarksRefreshAll() {
        memberMarks.refreshAll();
    }

    /** Places them again in {@code buffer} (its text settled after an edit). */
    void memberMarksRefresh(EditorBuffer buffer) {
        memberMarks.refresh(buffer);
    }

    private final LambdaBreakpoints lambdaBreakpoints;

    /** What the debuggee loaded: its sources and modules (see {@link LoadedSourcesPicker}). */
    private final LoadedSourcesPicker loadedSources;

    LoadedSourcesPicker loadedSources() {
        return loadedSources;
    }

    /** Debug: Loaded Sources… (see {@link LoadedSourcesPicker#showSources}). */
    void showLoadedSources() {
        loadedSources.showSources();
    }

    /** Debug: Modules… (see {@link LoadedSourcesPicker#showModules}). */
    void showModules() {
        loadedSources.showModules();
    }

    /** Breakpoints that wait for another to be hit (see {@link TriggeredBreakpoints}). */
    final TriggeredBreakpoints triggered;

    /** Breakpoint groups, and breakpoints exported to a file and imported (see {@link BreakpointGroups}). */
    final BreakpointGroups groups;

    BreakpointGroups groups() {
        return groups;
    }

    MemberBreakpointCommands memberBreakpoints() {
        return memberBreakpoints;
    }

    LambdaBreakpoints lambdaBreakpoints() {
        return lambdaBreakpoints;
    }

    /** Debug: Toggle Method Breakpoint — on the method the caret is in (else asks which). */
    void toggleMethodBreakpoint() {
        if (debugSupportEnabled()) {
            memberBreakpoints.toggleMethodAtCaret();
        } else {
            host.setStatus(tr("statusbar.tip.debugDisabled"));
        }
    }

    /** Debug: Toggle Field Watchpoint — on the field declared on the caret line (else asks which). */
    void toggleFieldWatchpoint() {
        if (debugSupportEnabled()) {
            memberBreakpoints.toggleFieldAtCaret();
        } else {
            host.setStatus(tr("statusbar.tip.debugDisabled"));
        }
    }

    /**
     * The Debug tool-window content (the {@code ToolWindow} itself stays in {@code MainController}), built on
     * its first use: the window's first open, or the first session event that has something to show.
     */
    DebugPanel panel() {
        if (s().panel == null) {
            DebugSession session = s();
            DebugPanel built = new DebugPanel(
                    inSessionProxy(DebugPanel.Actions.class, debugActions(), session), valueWindows.evaluateHistory());
            built.setPrompt(host::promptText);
            built.setRemovalUndo(
                    host.removalUndo(), () -> sessions.all().stream().anyMatch(x -> x.panel == built));
            built.setExpressionPrompt(
                    valuePopups::expressionPrompt); // Add / Edit Watch: the field completes (follow-up 5)
            built.setOnLink(ops::openLink); // double-clicked stack-trace line → jump
            built.setWatches(ops.debugWatches());
            built.setPausedWatches(ops.debugPausedWatches());
            built.setOnHistoryChanged(() -> ops.persistDebugEvaluateHistory(
                    valueWindows.evaluateHistory().entries()));
            built.setChordLookup(id -> java.util.Objects.requireNonNullElse(ops.chordFor(id), ""));
            built.setSourceCheck(this::canShow);
            built.setObjectMarks(session.marks);
            built.history().setCapacity(host.settings().getDebugHistoryStops());
            built.setOnWatchesChanged(() -> {
                List<String> watches = new ArrayList<>(built.getWatches());
                List<String> paused = built.getPausedWatches();
                ops.persistDebugPausedWatches(paused); // first: persisting the watches saves the workspace
                ops.persistDebugWatches(watches);
                forEachPanel(
                        other -> { // the watches are the window's: every session evaluates the same ones
                            if (other != built) {
                                other.setWatches(watches);
                                other.setPausedWatches(paused);
                            }
                        });
            });
            if (consoleFontFamily != null) {
                built.setConsoleFont(consoleFontFamily, consoleFontSize);
            }
            built.setPlainValueColours(plainValueColours()); // values legible on mixed themes (C111)
            built.setState(dap().state()); // a session may already be running (the window opened mid-session)
            built.setOnAreaShown(area -> lowLevel.refreshLowLevelViews());
            built.disassemblyView().setOnOpenSource(lowLevel::openSourceLine);
            instructionBreakpoints.install(built.disassemblyView());
            built.memoryView().setOnGo(lowLevel::memoryGo);
            memoryEdits.install(built.memoryView());
            built.memoryView().setOnPage(direction -> {
                if (s().memoryReference != null) {
                    s().memoryOffset += (long) direction * DebugMemoryView.PAGE;
                    lowLevel.readMemory();
                }
            });
            s().panel = built;
            refreshExceptionToggles();
            built.setBreakpointsMuted(breakpointsMuted); // the state line says so from the start
            if (shown() && sessionsView != null) {
                sessionsView.setContent(built);
            }
        }
        return s().panel;
    }

    // --- several sessions at once ------------------------------------------------------------------

    /** The Debug tool window's content; null until {@link #view()} builds it. */
    private @Nullable DebugSessionsView sessionsView;

    /**
     * The Debug tool-window content (the {@code ToolWindow} itself stays in {@code MainController}): the focused
     * session's panel, under a strip of the sessions' tabs when there is more than one.
     */
    DebugSessionsView view() {
        if (sessionsView == null) {
            DebugSessionsView built = new DebugSessionsView(new DebugSessionsView.Actions() {
                @Override
                public void show(DebugSession session) {
                    focusSession(session);
                }

                @Override
                public void close(DebugSession session) {
                    closeSession(session);
                }

                @Override
                public void stopAll() {
                    DebugCoordinator.this.stopAll();
                }
            });
            sessionsView = built;
            refreshSessionTabs();
        }
        return sessionsView;
    }

    /**
     * Shows {@code target} and makes it the one the debug commands act on: the editor drops the last one's execution
     * line, inline values and exception card and marks {@code target}'s stop, its breakpoints' states and the status
     * bar's debug state instead.
     */
    void focusSession(DebugSession target) {
        DebugSession before = sessions.focused();
        if (target == before || !sessions.all().contains(target)) {
            return;
        }
        inSession(before, () -> {
            closeMainClassPicker(); // it chose for that session's launch
            javaCode.closeSmartStepPicker(DapManager.State.INACTIVE);
            clearExecHighlight();
            clearDebugEditorSurfaces();
            mapFollow.mapStop(MapDebugStop.RUNNING); // the target's own stop, if it has one, is shown next
        });
        sessions.focus(target);
        inSession(target, () -> {
            host.forEachBuffer(this::showBreakpointStates);
            updateDebugStatus(dap().state());
            DapModels.StackFrameInfo frame = s().shownFrame;
            if (dap().state() == DapManager.State.SUSPENDED && frame != null && s().quietStop != s().stopSerial) {
                s().debugFrameId = frame.id(); // the hover evaluator's frame again
                execLine.highlightFrame(frame);
                dap().scopes(frame.id(), scopes -> applyInlineValues(frame, scopes));
                pinnedValues.refresh(frame.id());
            }
        });
        refreshSessionTabs();
        updateDebugAvailability();
    }

    /**
     * A session for a launch while the focused one is live ("Debug Both"), focused from now on: the launch that asked
     * for it is its own. Past {@link DebugSessions#MAX_SESSIONS}, the oldest session that has ended makes room.
     */
    DebugSession openSession() {
        makeRoom();
        DebugSession session = new DebugSession(sessions.focused().dap.newSession());
        setUp(session);
        sessions.add(session);
        focusSession(session);
        return session;
    }

    /** Past {@link DebugSessions#MAX_SESSIONS}, forgets the oldest session that has ended (and is not shown). */
    private void makeRoom() {
        if (sessions.size() >= DebugSessions.MAX_SESSIONS) {
            for (DebugSession old : sessions.all()) {
                if (old != sessions.focused() && !inSession(old, this::sessionLive)) {
                    shutdownBeforeLaunch(old);
                    sessions.remove(old);
                    break;
                }
            }
        }
    }

    /**
     * The program of the session being handled started a subprocess the adapter can debug ({@link
     * DapManager.Listener#onChildSession}): a session of its own attaches to it, beside its parent, named as the
     * adapter names it ("Subprocess 1234"). It is shown when it stops; its tab goes when it ends unless it is shown;
     * stopping or ending the parent stops it.
     */
    private void openChildSession(java.util.Map<String, Object> configuration) {
        DebugSession parent = s();
        makeRoom();
        DebugSession child = new DebugSession(parent.dap.newSession());
        child.parent = parent;
        setUp(child);
        sessions.add(child);
        Path anchor = parent.dap.debugFile();
        String name =
                configuration.get("name") instanceof String n && !n.isBlank() ? n : tr("debug.sessions.subprocess");
        inSession(child, () -> {
            nameSession(name);
            s().relaunch = SUBPROCESS;
            dap().startPythonChild(anchor, configuration);
        });
        refreshSessionTabs();
    }

    /** Stops the sessions of the subprocesses of the session being handled that are still live. */
    private void stopChildren() {
        DebugSession self = s();
        for (DebugSession child : sessions.all()) {
            if (child.parent == self && child != self) {
                inSession(child, () -> {
                    if (sessionLive()) {
                        stopOne();
                    }
                });
            }
        }
    }

    /** Closes the tab of {@code session}, which has ended; a live one, or the window's last, stays. */
    void closeSession(DebugSession session) {
        if (sessions.size() < 2 || inSession(session, this::sessionLive)) {
            return;
        }
        if (session == sessions.focused()) {
            List<DebugSession> others = new ArrayList<>(sessions.all());
            others.remove(session);
            focusSession(others.getLast());
        }
        shutdownBeforeLaunch(session);
        sessions.remove(session);
        refreshSessionTabs();
    }

    /** Debug: Stop All Sessions — Stop, in every session that is live. */
    void stopAll() {
        forEachSession(() -> {
            if (sessionLive()) {
                stopOne();
            }
        });
    }

    /** Every session's debug manager, oldest first. */
    List<DapManager> allManagers() {
        return sessions.all().stream().map(session -> session.dap).toList();
    }

    /** Whether any session is starting, running, paused or being built for. */
    boolean anySessionLive() {
        for (DebugSession session : sessions.all()) {
            if (inSession(session, this::sessionLive)) {
                return true;
            }
        }
        return false;
    }

    /** Draws the sessions' tabs again (each one's name and state) and shows the focused one's panel. */
    private void refreshSessionTabs() {
        DebugSessionsView view = sessionsView;
        if (view == null) {
            return;
        }
        List<DebugSessionsView.Tab> tabs = new ArrayList<>();
        List<DebugSession> all = sessions.all();
        for (int i = 0; i < all.size(); i++) {
            DebugSession session = all.get(i);
            int number = i + 1;
            tabs.add(inSession(
                    session,
                    () -> new DebugSessionsView.Tab(
                            session,
                            s().sessionLabel.isEmpty() ? tr("debug.sessions.untitled", number) : s().sessionLabel,
                            sessionStateWords(),
                            sessionLive(),
                            session == sessions.focused())));
        }
        view.setTabs(tabs);
        view.setContent(inSession(sessions.focused(), this::panel));
    }

    /** What a session's tab says of its state. */
    private String sessionStateWords() {
        return switch (dap().state()) {
            case STARTING -> tr("debug.state.starting");
            case RUNNING -> tr("debug.state.running");
            case SUSPENDED -> tr("debug.state.suspended");
            case INACTIVE, TERMINATED -> sessionLive() ? tr("debug.state.starting") : tr("debug.sessions.ended");
        };
    }

    /** Runs {@code action} on each session's Debug panel that has been built. */
    void forEachPanel(Consumer<DebugPanel> action) {
        for (DebugSession session : sessions.all()) {
            DebugPanel panel = session.panel;
            if (panel != null) {
                action.accept(panel);
            }
        }
    }

    /**
     * Re-reads the UI and editor themes (the panel and the popup are built, and each settings apply). When one is light
     * and the other dark, the Variables tree and the value popup draw values in the UI's colours: the editor theme's
     * token colours vanished on the panel's background (review 2 C111).
     */
    void refreshValueColours() {
        boolean plain = plainValueColours();
        forEachPanel(panel -> panel.setPlainValueColours(plain));
        if (valuePopups.valuePopup != null && valuePopups.valuePopup.popup().getSkin() != null) {
            DebugPanel.setPlainValueColours(
                    valuePopups.valuePopup.popup().getSkin().getNode(), plain);
        }
    }

    boolean plainValueColours() {
        var s = host.settings();
        return DebugPanel.themesDisagree(
                Themes.backgroundFor(s.getTheme()), EditorThemes.editorBackgroundFor(s.getEditorTheme()));
    }

    /** Matches the console font to the editor font; remembered for a panel not built yet. */
    void setConsoleFont(String family, int size) {
        consoleFontFamily = family;
        consoleFontSize = size;
        forEachPanel(panel -> panel.setConsoleFont(family, size));
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
    boolean debugEffectiveFor(@Nullable String language) {
        if (!debugSupportEnabled() || language == null) {
            return false;
        }
        com.editora.dap.DebugLanguage profile =
                com.editora.dap.DebugLanguages.forLanguage(language); // TS: JavaScript's
        if (profile == null) {
            profile = com.editora.dap.DebugLanguages.byId(language); // an adapter's id (a user's own: not a language)
        }
        if (profile == null) {
            return false;
        }
        if (profile.availability() == com.editora.dap.DebugLanguage.Availability.JAVA_DEBUG_PLUGIN) {
            return lspEnabled()
                    && lsp.serverEnabled("java")
                    && lsp.isServerAvailable("java")
                    && dap().isAdapterAvailable();
        }
        // Its own switch, when it has one (Python, JavaScript), and its adapter found. Editora's own JVM adapter and
        // Metals' have nothing to install: the tool (groovy, kotlinc, clojure or lein), or Metals for a build's Scala
        // file, is checked when the session starts.
        DebugAdapterSettings.Binding settings = DebugAdapterSettings.of(profile.id());
        if (profile == com.editora.dap.DebugLanguages.CPP && gdbForCpp()) {
            // C and C++ on GDB: its switch is C/C++'s, its adapter GDB's own DAP mode.
            return host.settings().isCppDebugEnabled() && dap().isExternalLocated(com.editora.dap.DebugLanguages.GDB);
        }
        return (settings == null || settings.isEnabled(host.settings())) && dap().isLanguageAvailable(profile.id());
    }

    /** Whether C and C++ are debugged on GDB (Settings → Debugging), not CodeLLDB. */
    boolean gdbForCpp() {
        return "gdb".equals(host.settings().getCppDebugger());
    }

    /**
     * As {@link #debugEffectiveFor(String)}, for {@code file}: a remote file is debugged on its host, which is
     * asked for its adapter when the session starts (this machine's detection says nothing about it).
     */
    boolean debugEffectiveFor(@Nullable String language, @Nullable Path file) {
        if (file == null || com.editora.process.ExecTargets.runsHere(file)) {
            return debugEffectiveFor(language);
        }
        if (!debugSupportEnabled() || language == null || !DapManager.debuggableOnHost(language)) {
            return false;
        }
        DebugAdapterSettings.Binding settings = DebugAdapterSettings.of(DapServerRegistry.adapterLanguage(language));
        boolean enabled = settings != null && settings.toggleable()
                ? settings.isEnabled(host.settings())
                // Java: jdtls runs on the host and is handed the java-debug jar there (copied from this machine,
                // or the host's own), so the adapter is the host's jdtls's.
                : lspEnabled()
                        && lsp.serverEnabled("java")
                        && (dap().isAdapterAvailable()
                                || com.editora.process.ExecTargets.remoteTool(file, "dap.java") != null);
        return enabled && RemoteExec.runsFor(file);
    }

    /**
     * Debug ▸ Stop: {@link #stopOne()} — and, for a session of a compound that stops all, every session of it still
     * running that compound's launches (VS Code's {@code stopAll}).
     */
    void stop() {
        DebugSession current = s();
        DebugCompound group = current.compound;
        if (group == null || !group.stopAll || group.stopping || !group.holds(current)) {
            stopOne();
            return;
        }
        group.stopping = true;
        try {
            for (DebugSession member : group.sessions()) {
                if (sessions.all().contains(member)) {
                    inSession(member, () -> {
                        if (member == current || sessionLive()) {
                            stopOne();
                        }
                    });
                }
            }
        } finally {
            group.stopping = false;
        }
    }

    /** Stops the session being handled: a before-launch build still running is what gets stopped; else the session. */
    private void stopOne() {
        s().launching = false; // a launch still saving or planning is not getting ready any more
        stopChildren(); // a program's subprocesses go with it
        CsharpTestDebug.stopRun(s()); // C#'s Debug Test: the dotnet test run the session attached to
        s().closedBreakpointsRead = null; // a start still reading closed files' breakpoints is not made (review D104)
        if (beforeLaunchActive()) {
            beforeLaunch().stop();
        } else if (s().pendingBuild != null) {
            // The jdtls build before a launch: it finishes on its own, but nothing is launched after it.
            dropPendingBuild();
            host.setStatus(tr("status.debug.launchCancelled"));
        } else if (s().pendingAttachStop != null && !dap().isActive()) {
            // Debug Test waiting for its suspended JVM: there is no session yet, so Stop ends the build that
            // would fork it (review D1) — as it ends the run of an attached session below.
            Runnable cancel = s().pendingAttachStop;
            setPendingAttach(null);
            cancel.run();
        } else {
            Runnable owner = s().relaunch == ONE_SHOT_ATTACH ? s().ownerStop : null;
            s().ownerStop = null;
            s().queuedAttaches.clear(); // Stop ends the whole run, its other JVMs included
            dap().stop();
            if (owner != null) {
                owner.run();
            }
        }
    }

    /**
     * A Debug Test run is waiting for its suspended JVM ({@code cancel} ends that build), or no longer is
     * ({@code null}): the window opens with Stop usable and its spinner on, like a before-launch build.
     */
    void setPendingAttach(@Nullable Runnable cancel) {
        DebugSession waiter = attachWaiter;
        if (dispatching == null && waiter != null && sessions.all().contains(waiter)) {
            inSession(waiter, () -> setPendingAttach(cancel));
            return;
        }
        attachWaiter = cancel != null ? s() : null;
        boolean was = s().pendingAttachStop != null;
        s().pendingAttachStop = cancel;
        if (cancel != null && !was) {
            EditorBuffer active = host.activeBuffer(); // Debug Test runs the active file's test
            s().pendingAttachFile = active == null ? null : active.getPath();
            ops.openToolWindow();
        } else if (cancel == null) {
            s().pendingAttachFile = null;
        }
        // A session being debugged keeps its own buttons (Continue, Stop through ownerStop): the waiting state
        // shows once it ends, when the run's next fork is what Stop would end (see the state listener).
        if (!beforeLaunchActive() && s().pendingBuild == null && !dap().isActive()) {
            panel().setPreparing(cancel != null, tr("debugpanel.state.waitingTest"));
        }
        updateDebugAvailability();
    }

    /**
     * Debug ▸ Restart: stops the session and starts it again <em>the way it was started</em> — saving the
     * edited file, running the configuration's before-launch build, re-anchoring closed-file breakpoints.
     * Re-running only the adapter launch debugged the previous code against the edited buffer's breakpoint
     * lines. An attach has nothing to redo and is simply re-attached — except one a build or test run
     * opened ({@link DebugAttach#attachToPort}): that JVM stops listening once it is resumed, so re-attaching would
     * only let it run to its end and leave a session with no debuggee showing "Running".
     */
    void restart() {
        Runnable again = s().relaunch;
        if (again == ONE_SHOT_ATTACH) {
            host.setStatus(tr("status.debug.cannotRestartAttached"));
            return;
        }
        if (again == HTTP_SCRIPT_ATTACH) {
            host.setStatus(tr("status.debug.cannotRestartHttpScript"));
            return;
        }
        if (again == SUBPROCESS) {
            host.setStatus(tr("status.debug.cannotRestartSubprocess"));
            return;
        }
        if (again == null) {
            dap().restart();
            return;
        }
        LaunchFollowUp followUp = s().followUp;
        if (followUp != null && followUp.started) {
            // Its end is this restart's: the postDebugTask, then the launch again (as VS Code). Taken before the
            // stop, whose end can be reported at once.
            s().followUp = null;
            dap().stop();
            followUps.ended(followUp, again);
            return;
        }
        dap().stop();
        again.run();
    }

    /** Whether Restart can do anything for the session there is: not for one attached to a build or test run. */
    boolean restartAvailable() {
        return s().relaunch != ONE_SHOT_ATTACH && s().relaunch != HTTP_SCRIPT_ATTACH && s().relaunch != SUBPROCESS;
    }

    /**
     * Attaches the JavaScript debugger to an HTTP Client script waiting on its inspector {@code port} (Debug Request):
     * a session called {@code label}, stepping over the runner {@code shim}, with every breakpoint — the {@code .http}
     * file's script lines and script files alike. {@code unguardedNode} names a Node that runs it without its permission
     * model (one that cannot let a debugger in under it), which the console and status bar then say. FX thread.
     */
    void attachHttpScript(int port, String label, @Nullable Path shim, @Nullable String unguardedNode) {
        if (!debugEffectiveFor("javascript")) {
            host.setStatus(tr("status.http.debugUnavailable"));
            return;
        }
        ops.openToolWindow();
        nameSession(label);
        s().relaunch = HTTP_SCRIPT_ATTACH;
        Path dir = shim == null ? null : shim.getParent();
        List<String> skip = new java.util.ArrayList<>(List.of("<node_internals>/**"));
        if (shim != null) {
            skip.add(shim.toString());
        }
        withClosedBreakpoints(() -> dap().startJsAttach("127.0.0.1", port, label, dir, skip, true));
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
        triggersDebounce.stop();
        pendingTriggers.clear();
        flushPendingPersist(); // a breakpoint moved by an edit in the last 300 ms is not lost with the window
        for (DebugSession session : sessions.all()) {
            shutdownBeforeLaunch(session);
        }
    }

    /** Ends {@code session}'s before-launch service, if it has one (the session or the window is going). */
    private static void shutdownBeforeLaunch(DebugSession session) {
        com.editora.run.RunService service = session.beforeLaunchService;
        if (service != null) {
            service.shutdown();
        }
        CsharpTestDebug.stopRun(session); // a C# test host waiting for a debugger would wait on forever
    }

    /** The session's program is running (not paused, starting or being built for): what Pause acts on (C206). */
    boolean programRunning() {
        return dap().isActive() && dap().state() == DapManager.State.RUNNING;
    }

    /** A session is starting, running, paused or being built for — the states Stop / Restart act on. */
    boolean sessionLive() {
        return dap().state() != DapManager.State.INACTIVE
                || beforeLaunchActive()
                || CsharpTestDebug.running(s())
                || s().pendingBuild != null
                || s().pendingAttachStop != null;
    }

    /** Names the session being launched in the panel; survives the end of the session it replaces. */
    void nameSession(@Nullable String label) {
        s().sessionLabel = label == null ? "" : label;
        panel().setSessionFile(s().sessionLabel);
        refreshSessionTabs();
    }

    /** Streams a before-launch build into the Debug console and keeps Stop usable while it runs. */
    BeforeLaunchStep.Console beforeLaunchConsole() {
        return beforeLaunchConsole(true);
    }

    /** As {@link #beforeLaunchConsole()}; not {@code fresh}: a later step of the same build, which keeps the output. */
    BeforeLaunchStep.Console beforeLaunchConsole(boolean fresh) {
        s().announceStart = true; // a step's status would outlive it (review 2, A105)
        return new BeforeLaunchStep.Console() {
            @Override
            public void started(String commandLine) {
                ops.openToolWindow();
                panel().setPreparing(true);
                if (fresh) {
                    panel().clearConsole(); // a new launch: the previous session's output is not this one's
                }
                s().consoleStarted = true;
                panel().appendOutput("$ " + commandLine + "\n", "console");
            }

            @Override
            public void output(String line, boolean stderr) {
                panel().appendOutput(line + "\n", stderr ? "stderr" : "stdout");
            }

            @Override
            public void ended(int code, @Nullable String launchError) {
                panel().setPreparing(false);
                s().consoleStarted = code == 0 && launchError == null; // a failed build is not followed by a session
                if (launchError != null) {
                    panel().appendOutput(launchError + "\n", "stderr");
                }
            }
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

    /** Runs a debugged program in a Terminal tab (Settings → Debug → in the Terminal); set by the window. */
    private com.editora.dap.@Nullable ProgramTerminal programTerminal;

    void setProgramTerminal(com.editora.dap.ProgramTerminal programTerminal) {
        this.programTerminal = programTerminal;
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
        ProjectScope scope = host.projectScope(); // a trusted project's debugger paths, else the user's
        dap().setOnJavaDebugLocated(this::applySupport); // a new java-debug path, located off FX, applies again
        dap().configure(
                        on,
                        scope.debugTool("java", s.getJavaDebugPluginPath()),
                        s.isPythonDebugEnabled(),
                        scope.debugTool("python", s.getPythonDebugCommand()),
                        s.isJsDebugEnabled(),
                        scope.debugTool("javascript", s.getJsDebugPath()));
        // Delve: as configured, else `dlv` on the PATH (where `go install` puts it is on Editora's PATH).
        dap().configureExternal(
                        com.editora.dap.DebugLanguages.GO,
                        on && s.isGoDebugEnabled(),
                        scope.debugTool("go", s.getGoDebugPath()),
                        found -> applyGating());
        // CodeLLDB: as configured, else Editora's install, VS Code's extension, or the PATH.
        dap().configureExternal(
                        com.editora.dap.DebugLanguages.RUST,
                        on && s.isRustDebugEnabled(),
                        scope.debugTool("rust", s.getRustDebugPath()),
                        found -> applyGating());
        // GDB's DAP mode: C and C++'s other debugger, and launch.json's gdb / cppdbg.
        dap().configureExternal(
                        com.editora.dap.DebugLanguages.GDB,
                        on,
                        scope.debugTool("gdb", s.getGdbPath()),
                        found -> applyGating());
        launchJson.loadCustomAdapters(on);
        // netcoredbg: as configured, else Editora's install, a VS Code extension's, or the PATH.
        dap().configureExternal(
                        com.editora.dap.DebugLanguages.CSHARP,
                        on && s.isCsharpDebugEnabled(),
                        scope.debugTool("csharp", s.getCsharpDebugPath()),
                        found -> applyGating());
        // rdbg: as configured, else on the PATH or where `gem install --user-install` put it.
        dap().configureExternal(
                        com.editora.dap.DebugLanguages.RUBY,
                        on && s.isRubyDebugEnabled(),
                        scope.debugTool("ruby", s.getRubyDebugPath()),
                        found -> applyGating());
        // vscode-php-debug: as configured, else Editora's install or VS Code's extension.
        dap().configureExternal(
                        com.editora.dap.DebugLanguages.PHP,
                        on && s.isPhpDebugEnabled(),
                        scope.debugTool("php", s.getPhpDebugPath()),
                        found -> applyGating());
        // vscode-bash-debug: as configured, else Editora's install or VS Code's extension.
        dap().configureExternal(
                        com.editora.dap.DebugLanguages.BASH,
                        on && s.isBashDebugEnabled(),
                        scope.debugTool("bash", s.getBashDebugPath()),
                        found -> applyGating());
        // local-lua-debugger: as configured, else Editora's install or VS Code's extension.
        dap().configureExternal(
                        com.editora.dap.DebugLanguages.LUA,
                        on && s.isLuaDebugEnabled(),
                        scope.debugTool("lua", s.getLuaDebugPath()),
                        found -> applyGating());
        dart.configure(on, s, scope, this::applyGating); // dart, and flutter for a Flutter app's file
        elixir.configure(on, s, scope, this::applyGating); // ElixirLS
        powershell.configure(on, s, scope, this::applyGating); // PowerShell Editor Services
        browser.configure(on, s, scope, this::applyGating); // vscode-firefox-debug, for Firefox
        // C and C++: the same CodeLLDB as Rust's.
        dap().configureExternal(
                        com.editora.dap.DebugLanguages.CPP,
                        on && s.isCppDebugEnabled(),
                        scope.debugTool("rust", s.getRustDebugPath()),
                        found -> applyGating());
        int historyStops = s.getDebugHistoryStops();
        forEachPanel(panel -> panel.history().setCapacity(historyStops)); // fewer forgets the oldest now
        dap().setProgramConsole(s.isDebugProgramConsole()); // takes effect at the next launch
        dap().setPythonSubprocesses(s.isDebugPythonSubprocesses()); // shared by every session, from its next launch
        dap().setPythonTemplates(com.editora.dap.PythonTemplates.Mode.of(s.getDebugPythonTemplates()));
        dap().setEvaluationTimeLimit(s.getDebugEvaluationTimeLimit()); // told to each debugger as its session starts
        goroutines().apply(); // Go: which goroutines Delve lists, a running session's at once
        dap().setNodePath(s.getNodePath()); // the Node.js a JavaScript session runs on (and js-debug)
        dap().setProgramTerminal(s.isDebugProgramConsole() && s.isDebugProgramInTerminal() ? programTerminal : null);
        dap().setJavaDebugSettings(new com.editora.dap.JavaDebugSettings(
                s.isDebugStepFilters(),
                s.isDebugShowStatics(),
                com.editora.dap.JavaDebugSettings.parseExceptionTypes(s.getDebugExceptionTypes()),
                s.isDebugShowToString(),
                s.isDebugSuspendAllThreads()));
        List<com.editora.dap.StepFilter> skips = s.getDebugStepFilterList();
        forEachSession(() -> dap().setStepFilters(skips)); // Don't step into: a live Java session is told at once
        List<String> bundles = on ? dap().bundlePaths() : List.of();
        boolean changed = !bundles.equals(appliedDebugBundles);
        lspManager.setDebugBundles(bundles); // set before sessions start — jdtls always gets the bundle
        appliedDebugBundles = bundles;
        if (!on) {
            forEachSession(() -> dap().stop()); // every session: a background one would go on stopping unseen
        }
        if (changed) {
            lspManager.restartServer("java"); // reload a running jdtls with/without the bundle (no-op if none)
            lsp.applyGating(); // re-open the java buffers on the fresh session
        }
        applyGating();
        if (on) {
            // Probe debugpy / node off-thread; re-gate when each result lands (enables python/js gutters).
            dap().detectPython(ok -> applyGating());
            dap().detectJs(ok -> applyGating());
        }
    }

    /**
     * Re-reads whether the running jdtls itself advertises the java-debug commands and re-gates. Some
     * distributions (e.g. Homebrew's jdtls) ship and auto-load the plugin, so {@code locate()} finds no jar to
     * inject yet debugging works — capabilities are only known once a server finishes {@code initialize}, so
     * the controller calls this from the server-ready event (#711). Light: no bundle push, no restart.
     */
    void refreshJavaDebugAvailability() {
        boolean before = dap().isLanguageAvailable("java");
        dap().setServerProvidesJavaDebug(lspManager.javaDebugCommandsAvailable());
        if (dap().isLanguageAvailable("java") != before) {
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
            debugLoading(false);
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
                && (isDebuggableBuffer(host.activeBuffer()) || anySessionLive() || ops.isToolWindowOpen());
        ops.setToolWindowAvailable(available);
    }

    /** Whether a buffer's language has a registered debug adapter (java/python/javascript). */
    boolean isDebuggableBuffer(@Nullable EditorBuffer b) {
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
        if (buffer.getPath() == null) {
            referenceBreakpointsChanged(buffer); // a tab of code the debugger has only by reference, or nothing
            return;
        }
        if (buffer.getBreakpointManager().isEditDriven()) {
            schedulePersistBreakpoints(buffer); // debounced FS write (off the per-newline hot path)
        } else {
            pendingPersist.remove(buffer); // the user's own toggle / edit: written now, never left to a timer
            persistBreakpoints(buffer);
        }
        forEachSession(
                () -> { // every live session is told
                    // Adapter stays current — but only when what it is told actually changed: editing the text of a
                    // breakpoint's line is a change to persist, not one to put on the wire. A shift made by an edit
                    // waits for the mapping of the edited text (worked out off the FX thread).
                    if (linesKnown(buffer, () -> resendLines(buffer))) {
                        resendLines(buffer); // a new breakpoint is unverified until the adapter answers for it
                    }
                });
        if (buffer.getBreakpointManager().isEditDriven()) {
            scheduleTriggersChanged(buffer);
        } else {
            pendingTriggers.remove(buffer);
            triggersChanged(buffer);
        }
    }

    /**
     * {@link #triggersChanged} for a shift made by typing: nothing when none of {@code buffer}'s breakpoints is one
     * another can wait for (only a trigger has an id), else once typing settles (D9).
     */
    private void scheduleTriggersChanged(EditorBuffer buffer) {
        if (!buffer.getBreakpointManager().anyWithId()) {
            return;
        }
        pendingTriggers.add(buffer);
        triggersDebounce.playFromStart();
    }

    /**
     * A breakpoint of {@code changed} was set, changed or removed: one that others wait for may have come or gone, so
     * the other tabs' triggered breakpoints are drawn again and re-sent to each live session where that changes them
     * (one whose trigger is gone waits for none).
     */
    private void triggersChanged(EditorBuffer changed) {
        host.forEachBuffer(b -> {
            var mgr = b.getBreakpointManager();
            if (b == changed || mgr.documentSnapshot().stream().noneMatch(Breakpoint::isTriggered)) {
                return;
            }
            mgr.repaintTriggered();
            forEachSession(() -> {
                if (b.getPath() != null && dap().isActive()) {
                    DapModels.FileBreakpoints now = fileBreakpoints(b, b.getPath());
                    if (!now.equals(s().sentBreakpoints.put(b.getPath(), now))) {
                        dap().updateBreakpoints(now);
                    }
                    showBreakpointStates(b);
                }
            });
        });
    }

    /**
     * The program stopped on a breakpoint: when others wait for it, they are sent to this session from now on, and
     * stop like any other. Waits for the stopped file's buffer to load: the stop may have just opened it.
     */
    private void armTriggered(String reason, List<DapModels.StackFrameInfo> frames) {
        if (!"breakpoint".equals(reason) || frames.isEmpty()) {
            return;
        }
        DapModels.StackFrameInfo top = frames.get(0);
        Path file = top.file();
        if (file == null) {
            return;
        }
        DebugSession session = s();
        EditorBuffer b = ops.bufferForPath(file);
        if (b == null) {
            List<Breakpoint> stored = ops.breakpointMap().getOrDefault(key(file), List.of());
            stored.stream().filter(bp -> bp.line() == top.line()).findFirst().ifPresent(bp -> armBy(bp, top));
            return;
        }
        ops.afterBufferLoad(
                b,
                () -> inSession(session, () -> {
                    var mgr = b.getBreakpointManager();
                    Breakpoint bp = mgr.get(top.line() - mgr.regionFirstLine());
                    if (bp != null) {
                        armBy(bp, top);
                    }
                }));
    }

    /** {@code trigger} was hit in this session (at {@code where}): the breakpoints that wait for it are sent. */
    private void armBy(Breakpoint trigger, DapModels.StackFrameInfo where) {
        String id = trigger.id();
        if (id.isEmpty() || s().hitTriggers.contains(id) || !dap().isActive()) {
            return;
        }
        List<BreakpointsPicker.Entry> waiting = triggered.entries().stream()
                .filter(e -> id.equals(e.breakpoint().triggeredBy())
                        && e.breakpoint().enabled())
                .toList();
        s().hitTriggers.add(id);
        if (waiting.isEmpty()) {
            return;
        }
        Set<EditorBuffer> tabs = new java.util.LinkedHashSet<>();
        Map<String, List<Breakpoint>> closed = new LinkedHashMap<>();
        for (BreakpointsPicker.Entry e : waiting) {
            if (e.buffer() != null) {
                tabs.add(e.buffer());
            } else {
                closed.put(e.key(), ops.breakpointMap().getOrDefault(e.key(), List.of()));
            }
        }
        for (EditorBuffer tab : tabs) {
            Path file = tab.getPath();
            if (file != null) {
                DapModels.FileBreakpoints now = fileBreakpoints(tab, file);
                if (!now.equals(s().sentBreakpoints.put(file, now))) {
                    dap().updateBreakpoints(now);
                }
                showBreakpointStates(tab);
            }
        }
        if (!closed.isEmpty()) {
            Predicate<Breakpoint> sent = sentPredicate(s());
            startOffFx("debug-triggered", session -> () -> {
                List<DapModels.FileBreakpoints> computed = ClosedFileBreakpoints.of(
                        closed, Set.of(), RemoteExec::runsFor, ClosedFileBreakpoints::readLinesOrNull, sent);
                laterIn(session, () -> {
                    if (!dap().isActive()) {
                        return;
                    }
                    for (DapModels.FileBreakpoints fb : computed) {
                        if (ops.bufferForPath(fb.file()) == null) { // opened meanwhile: its tab sent them
                            s().closedBreakpoints.put(key(fb.file()), fb);
                            dap().updateBreakpoints(fb);
                        }
                    }
                });
            });
        }
        String place = where.file() == null || where.file().getFileName() == null
                ? String.valueOf(where.line() + 1)
                : where.file().getFileName() + ":" + (where.line() + 1);
        host.setStatus(tr("status.debug.triggerHit", place, waiting.size()));
        panel().appendNotice(tr("status.debug.triggerHit", place, waiting.size()));
    }

    // --- what the live session says about the breakpoints ------------------------------------------

    /**
     * Paints {@code buffer}'s breakpoints as the live session sees them — hollow while the adapter has not
     * bound one, flagged when it refused one — or as plain breakpoints when there is no session.
     */
    private void showBreakpointStates(EditorBuffer buffer) {
        if (!shown()) {
            return; // another session's: the editor shows the focused one's
        }
        Path file = buffer.getPath();
        if (file == null || dap().state() == DapManager.State.INACTIVE) {
            buffer.getBreakpointManager().setLive(null);
            return;
        }
        Map<Integer, DapModels.BreakpointStatus> known = s().breakpointStatus.getOrDefault(file, Map.of());
        Map<Integer, EvaluationFailure> failures = s().evaluationFailures.get(file);
        Map<Integer, BreakpointManager.Live> live = new java.util.HashMap<>();
        String pending = tr(DebugFeedback.pendingKey(known.values(), buffer.getLanguage()));
        LaunchedLines.Mapping map = lineMap(buffer);
        Set<String> ids = null;
        for (Breakpoint bp : buffer.getBreakpointManager().documentSnapshot()) {
            int launchedLine = map.toLaunched(bp.line());
            if (bp.isTriggered()) {
                ids = ids == null ? triggered.ids() : ids;
                if (TriggeredBreakpoints.waits(bp, ids, s().hitTriggers)) {
                    String trigger = triggered.labelOf(bp.triggeredBy());
                    live.put(
                            bp.line(),
                            new BreakpointManager.Live(
                                    BreakpointManager.LiveState.PENDING,
                                    tr("debug.breakpoint.waiting", trigger == null ? "" : trigger)));
                    continue;
                }
            }
            if (launchedLine < 0) {
                live.put(
                        bp.line(),
                        new BreakpointManager.Live(
                                BreakpointManager.LiveState.PENDING, tr("debug.breakpoint.addedSinceLaunch")));
                continue;
            }
            EvaluationFailure failure = failures == null ? null : failures.get(bp.line());
            if (failures != null
                    && failure != null
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
        if (!linesKnown(ops.bufferForPath(file), () -> breakpointStatusChanged(file, statuses, whole))) {
            return; // statuses name launched lines: placed once the mapping of the text now is known
        }
        Map<Integer, DapModels.BreakpointStatus> known =
                s().breakpointStatus.computeIfAbsent(file, f -> new java.util.HashMap<>());
        if (whole) {
            known.clear();
        }
        EditorBuffer buffer = ops.bufferForPath(file);
        DapModels.FileBreakpoints sent = s().sentBreakpoints.get(file);
        LaunchedLines.Mapping map = lineMap(buffer); // statuses name launched lines; the markers are the document's
        for (DapModels.BreakpointStatus status : statuses) {
            known.put(status.line(), status);
            boolean armed = sent != null && sent.breakpoints().stream().anyMatch(lb -> lb.line() == status.line());
            if (!armed) {
                continue; // a run-to-cursor stop, or an answer about a set that has since been replaced
            }
            if (status.failed() && s().reportedRejections.add(file + ":" + status.line() + ":" + status.message())) {
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
                    s().evaluationFailures
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
    void bufferPathChanged(EditorBuffer buffer, @Nullable Path oldPath) {
        Path newPath = buffer.getPath();
        buffer.setBreakpointsEnabled(debugSupportEnabled() && isDebuggableBuffer(buffer)); // the language may differ
        if (newPath == null || newPath.equals(oldPath)) {
            return;
        }
        Path from = oldPath; // whether it is gone is asked off the FX thread (PERF-01, Pauta 6.2.0)
        com.editora.perf.OffFx.then("debug-path-moved", () -> from == null || !Files.exists(from), a -> {
            boolean known = a.ok(); // a remote file whose connection is gone: nothing can be asked about it
            pathMoved(buffer, known ? from : null, newPath, known && Boolean.TRUE.equals(a.value()));
        });
    }

    private void pathMoved(EditorBuffer buffer, @Nullable Path oldPath, Path newPath, boolean oldGone) {
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
        Path renamedFrom = oldPath;
        boolean renamedAway = oldGone;
        forEachSession(() -> {
            if (dap().isActive()) {
                if (renamedFrom != null && s().sentBreakpoints.remove(renamedFrom) != null && renamedAway) {
                    dap().updateBreakpoints(new DapModels.FileBreakpoints(renamedFrom, List.of()));
                }
                DapModels.FileBreakpoints now = fileBreakpoints(buffer, newPath);
                s().sentBreakpoints.put(newPath, now);
                dap().updateBreakpoints(now);
            }
        });
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
        List<Breakpoint> snapshot = buffer.getBreakpointManager().snapshot();
        if (buffer.encryption().isEncrypted()) {
            snapshot = snapshot.stream().map(b -> b.withLineText("")).toList(); // see persistBookmarks
        }
        List<Breakpoint> bps = MarkMerge.withForeign(stored, seenInStore.get(buffer), snapshot, Breakpoint::line);
        if (bps.isEmpty()) {
            map.remove(key);
        } else {
            map.put(key, BreakpointStore.mergePreservingOrder(stored == null ? List.of() : stored, bps));
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
    void breakpointsChangedElsewhere(Map<String, ?> bucket, @Nullable String fileKey) {
        if (bucket != ops.breakpointMap()) {
            return;
        }
        host.forEachBuffer(b -> {
            Path file = b.getPath();
            if (file == null || b.isNarrowed()) {
                return; // a narrowed buffer's lines are region-relative; it merges when it next writes
            }
            String key = key(file);
            if (fileKey != null && !fileKey.equals(key)) {
                return;
            }
            List<Breakpoint> stored = ops.breakpointMap().getOrDefault(key, List.of());
            b.applyBreakpoints(stored);
            seenInStore.put(b, MarkMerge.keys(stored, Breakpoint::line));
            forEachSession(() -> {
                if (dap().isActive()) {
                    DapModels.FileBreakpoints now = fileBreakpoints(b, file);
                    if (!now.equals(s().sentBreakpoints.put(file, now))) {
                        dap().updateBreakpoints(now);
                    }
                }
            });
            showBreakpointStates(b);
        });
    }

    void restoreBreakpoints(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        forEachSession(
                () -> { // each live session's: its program was built from the file as on disk
                    if (dap().isActive()) {
                        snapshotLaunched(buffer);
                    }
                });
        List<Breakpoint> stored = ops.breakpointMap().getOrDefault(key(file), List.of());
        seenInStore.put(buffer, MarkMerge.keys(stored, Breakpoint::line));
        buffer.getBreakpointManager().setTriggerLabels(triggered::labelOf);
        if (buffer.applyBreakpoints(stored)) {
            persistBreakpoints(buffer); // self-heal re-anchored indices once
        }
        showBreakpointStates(buffer); // opened during a session: it shows what the adapter already said
        pinnedValues.restore(buffer);
        memberMarks.refresh(buffer);
        reshowFrameIn(buffer);
    }

    /**
     * {@code buffer} was just (re)opened: if it is the file of the frame on show, it gets the execution line
     * and inline values back — the buffer that carried them was disposed with its tab.
     */
    private void reshowFrameIn(EditorBuffer buffer) {
        DapModels.StackFrameInfo frame = s().shownFrame;
        if (frame == null
                || frame.file() == null
                || dap().state() != DapManager.State.SUSPENDED
                || buffer == execHighlightBuffer
                || !samePath(frame.file(), buffer.getPath())) {
            return;
        }
        clearExecHighlight();
        execLine.paintExecutionLine(buffer, frame);
        dap().scopes(frame.id(), scopes -> applyInlineValues(frame, scopes));
    }

    /**
     * The enabled breakpoints of {@code buffer} as a DAP {@code FileBreakpoints} (empty list if none), in
     * whole-file lines even while the buffer is narrowed.
     */
    DapModels.FileBreakpoints fileBreakpoints(EditorBuffer buffer, Path file) {
        List<DapModels.LineBreakpoint> lines = new ArrayList<>();
        // B1: in the numbering of the code the program runs — a breakpoint whose line was added since the
        // session started has no place in it, and waits for the next session.
        LaunchedLines.Mapping map = lineMap(buffer);
        var mgr = buffer.getBreakpointManager();
        String language = buffer.getLanguage();
        Set<String> ids = null; // the window's breakpoint ids, read once a triggered breakpoint asks
        for (Breakpoint bp : mgr.documentSnapshot()) {
            int line = map.toLaunched(bp.line());
            if (bp.isTriggered()) {
                ids = ids == null ? triggered.ids() : ids;
                if (TriggeredBreakpoints.waits(bp, ids, s().hitTriggers)) {
                    continue; // until its trigger is hit in this session
                }
            }
            if (bp.enabled() && !breakpointsMuted && line >= 0) { // Mute Breakpoints: the adapter is told none
                lines.addAll(BreakpointTargets.of(bp, line, documentLineText(buffer, bp.line()), language));
            }
        }
        return new DapModels.FileBreakpoints(file, lines);
    }

    /** The text of whole-document line {@code line} of {@code buffer}, or null while narrowing hides it. */
    private static @Nullable String documentLineText(EditorBuffer buffer, int line) {
        var area = buffer.getArea();
        int i = line - buffer.getBreakpointManager().regionFirstLine();
        return i >= 0 && i < area.getParagraphs().size() ? area.getParagraph(i).getText() : null;
    }

    // --- B1: lines of the code the program runs, against the document as it is now ---------------------

    /**
     * {@link #rebaselineLines(Path)} for each of {@code files}: after a hot code replace, the Java files saved
     * since the last successful replace (see {@link JavaCodeChanges#codeReplaced}).
     */
    void rebaselineLines(Set<Path> files) {
        if (files != null) {
            files.forEach(this::rebaselineLines);
        }
    }

    /** The mapping between {@code b}'s launched text and its document (identity when there is none). */
    private LaunchedLines.Mapping lineMap(@Nullable EditorBuffer b) {
        Path file = b == null ? null : b.getPath();
        if (b == null || file == null || b.isNarrowed() || !s().launchedLines.has(file)) {
            return LaunchedLines.Mapping.IDENTITY; // a narrowed buffer's text is its region only
        }
        // Worked out off the FX thread (PERF-01): until it lands, the last one, and the file is sent again then.
        DebugSession session = s();
        return session.launchedLines.latest(b, "resend", () -> inSession(session, () -> resendLines(b)));
    }

    /** Whether {@code b}'s mapping is known for its text now; if not, {@code again} runs as this session's once it is. */
    private boolean linesKnown(@Nullable EditorBuffer b, Runnable again) {
        if (b == null
                || b.getPath() == null
                || b.isNarrowed()
                || !s().launchedLines.has(b.getPath())) {
            return true;
        }
        DebugSession session = s();
        return session.launchedLines.knownOr(b, again, () -> inSession(session, again)) != null;
    }

    /** Whether the mapping of every file of {@code frames} is known; if not, {@code again} runs once they are. */
    private boolean framesKnown(@Nullable List<DapModels.StackFrameInfo> frames, Runnable again) {
        if (frames == null || s().launchedLines.isEmpty()) {
            return true;
        }
        Set<Path> asked = new HashSet<>(); // each file once: a recursion is one file many times (perf review D3)
        for (DapModels.StackFrameInfo f : frames) {
            if (f.file() != null && asked.add(f.file()) && !linesKnown(ops.bufferForPath(f.file()), again)) {
                return false;
            }
        }
        return true;
    }

    /** {@code b}'s breakpoints sent again where its mapping changed them, and their states shown again. */
    private void resendLines(EditorBuffer b) {
        if (b.getPath() != null && dap().isActive()) {
            DapModels.FileBreakpoints now = fileBreakpoints(b, b.getPath());
            if (!now.equals(s().sentBreakpoints.put(b.getPath(), now))) {
                dap().updateBreakpoints(now);
            }
            showBreakpointStates(b);
        }
    }

    /** Records every open file's text as the session's launched text (the files were saved for the launch). */
    private void snapshotLaunchedLines() {
        s().launchedLines.clear();
        s().changedNoticed.clear();
        host.forEachBuffer(this::snapshotLaunched);
    }

    /** {@code b}'s launched text: its document, or the file on disk when it has unsaved edits. */
    private void snapshotLaunched(EditorBuffer b) {
        Path file = b.getPath();
        if (file == null || b.isNarrowed() || !host.isLocalBuffer(b)) {
            return;
        }
        if (!b.isDirty()) {
            s().launchedLines.snapshotIfAbsent(file, LaunchedLines.textOf(b)); // O(1): copied only if mapped (D7)
            return;
        }
        DebugSession session = s(); // the file on disk is read off the FX thread (PERF-01), for this session
        planOffFx(
                "debug-launched-lines",
                () -> {
                    List<String> lines = ClosedFileBreakpoints.readLinesOrNull(file);
                    return lines == null ? null : String.join("\n", lines);
                },
                a -> {
                    if (a.value() != null) {
                        session.launchedLines.snapshotIfAbsent(file, a.value());
                    }
                });
    }

    /** {@code frames} with each line as the document now has it (a frame names the launched line). */
    private List<DapModels.StackFrameInfo> currentLines(List<DapModels.StackFrameInfo> frames) {
        if (s().launchedLines.isEmpty() || frames == null) {
            return frames;
        }
        List<DapModels.StackFrameInfo> out = new ArrayList<>(frames.size());
        Map<Path, LaunchedLines.Mapping> maps = new HashMap<>(); // each file's tab looked up once (perf review D3)
        for (DapModels.StackFrameInfo f : frames) {
            if (f.file() == null) {
                out.add(f);
                continue;
            }
            LaunchedLines.Mapping map = maps.computeIfAbsent(f.file(), file -> lineMap(ops.bufferForPath(file)));
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
        Path file = frame == null ? null : frame.file();
        EditorBuffer b = file == null ? null : ops.bufferForPath(file);
        if (file == null || b == null || !lineMap(b).changed()) {
            return;
        }
        String message = tr("status.debug.changedSinceLaunch", file.getFileName());
        if (s().changedNoticed.add(key(file))) {
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
        if (b == null || b.getPath() == null || !dap().isActive()) {
            return; // a buffer found by its path always has one
        }
        s().launchedLines.rebaseline(file, LaunchedLines.textOf(b));
        if (linesKnown(b, () -> resendLines(b))) { // its mapping is worked out again off the FX thread
            resendLines(b);
        }
    }

    /**
     * Every armed breakpoint sent to the adapter: open buffers' live breakpoints merged with
     * the session's closed-file ones (files with no open tab, re-anchored at session start). A closed file that
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
            DapModels.FileBreakpoints fb = fileBreakpoints(b, b.getPath());
            s().sentBreakpoints.put(b.getPath(), fb);
            if (!fb.breakpoints().isEmpty()) {
                out.add(fb);
            }
        });
        for (DapModels.FileBreakpoints fb : s().closedBreakpoints.values()) {
            if (fb.file() != null && !open.contains(key(fb.file())) && !breakpointsMuted) {
                out.add(fb);
            }
        }
        return out;
    }

    /**
     * Re-anchors the persisted breakpoints of files with no open tab (off the FX thread), caches them in
     * the session's closed-file breakpoints, then runs {@code then} on the FX thread. Called before every session start
     * so the initial {@code setBreakpoints} includes closed files. The open-tab set + a copy of the map are
     * snapshotted on the FX thread; the file reads happen on a daemon thread.
     */
    void withClosedBreakpoints(Runnable then) {
        Object token = new Object();
        DebugSession session = s();
        session.closedBreakpointsRead = token; // a newer start, or Stop, drops this one
        Set<String> openPaths = new HashSet<>();
        host.forEachBuffer(b -> {
            if (b.getPath() != null) {
                openPaths.add(key(b.getPath()));
            }
        });
        Map<String, List<Breakpoint>> map = new LinkedHashMap<>(ops.breakpointMap());
        Predicate<Breakpoint> sent = sentPredicate(session);
        Thread t = new Thread(
                () -> {
                    List<DapModels.FileBreakpoints> computed = ClosedFileBreakpoints.of(
                            map, openPaths, RemoteExec::runsFor, ClosedFileBreakpoints::readLinesOrNull, sent);
                    laterIn(session, () -> {
                        if (s().closedBreakpointsRead != token) {
                            return; // stopped, or replaced by another start, while the files were read
                        }
                        s().closedBreakpointsRead = null;
                        s().closedBreakpoints.clear();
                        computed.forEach(fb -> s().closedBreakpoints.put(key(fb.file()), fb));
                        then.run();
                    });
                },
                "debug-breakpoints");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Which saved breakpoints {@code session} is told of now: all but the triggered ones whose trigger it has not hit.
     * Read on the FX thread; the answer may be asked off it.
     */
    Predicate<Breakpoint> sentPredicate(DebugSession session) {
        Set<String> ids = triggered.ids();
        Set<String> hit = Set.copyOf(session.hitTriggers);
        return bp -> !TriggeredBreakpoints.waits(bp, ids, hit);
    }

    /** See {@link ClosedFileBreakpoints#of}. */
    static List<DapModels.FileBreakpoints> closedFileBreakpoints(
            Map<String, List<Breakpoint>> map,
            Set<String> openPaths,
            Predicate<Path> isLocal,
            Function<Path, @Nullable List<String>> readLines) {
        return ClosedFileBreakpoints.of(map, openPaths, isLocal, readLines);
    }

    // --- DAP event sink + panel actions ------------------------------------------------------------

    private DapManager.Listener dapListener() {
        return new DapManager.Listener() {
            @Override
            public void onState(DapManager.State state) {
                DebugSession session = s();
                followUps.state(session, state);
                if (state == DapManager.State.SUSPENDED
                        && (session.suspendHeld || session.uiState != DapManager.State.SUSPENDED)) {
                    // A new stop: shown once onStopped knows it is one the user sees (showSuspended). Filters, token
                    // logpoints, exception checks and a dump's pause decide quietly, and the window does not go
                    // Suspended and back for each of them (perf review 2026-11-23, D4).
                    session.suspendHeld = true;
                    return;
                }
                if (session.suspendHeld) {
                    session.suspendHeld = false;
                    if (state == DapManager.State.RUNNING) {
                        quietlyResumed(); // the window still shows the run the stop interrupted
                        return;
                    }
                }
                showState(state);
            }

            /** The stop held by {@link #onState} is the user's: the window shows it as suspended now. */
            private void showSuspended() {
                if (s().suspendHeld) {
                    s().suspendHeld = false;
                    showState(DapManager.State.SUSPENDED);
                }
            }

            /** A held stop went on without being shown: what a run after a stop resets, and nothing on screen. */
            private void quietlyResumed() {
                timeRun(DapManager.State.RUNNING);
                s().stopException = null;
                s().stopDeferred = false;
                s().heldExceptionInfo = null;
            }

            private void showState(DapManager.State state) {
                if (state == DapManager.State.SUSPENDED && s().uiState != DapManager.State.SUSPENDED) {
                    s().suspendsShown++;
                }
                s().uiState = state;
                javaCode.closeSmartStepPicker(state); // review E108
                timeRun(state);
                if (state != DapManager.State.SUSPENDED) {
                    execLine.hideExceptionCard(); // Continue, a step, the session's end: the stop it described is over
                    s().stopException = null;
                }
                // INACTIVE with no panel built is what startup (configure, stop) reports: a panel built later
                // starts in that state by itself, so there is nothing to show and no reason to build it now.
                boolean show = s().panel != null || state != DapManager.State.INACTIVE;
                if (state == DapManager.State.STARTING || state == DapManager.State.INACTIVE) {
                    s().runtimeGeneration++; // a probe still out answers for the session that just ended
                    if (s().panel != null) {
                        panel().setRuntime(null);
                    }
                }
                if (state == DapManager.State.STARTING) {
                    if (!s().consoleStarted) {
                        panel().clearConsole(); // each launch starts with its own output
                    }
                    s().consoleStarted = false;
                }
                if (show) {
                    panel().setRestartBlocked(
                                    restartAvailable()
                                            ? null
                                            : tr(
                                                    s().relaunch == SUBPROCESS
                                                            ? "status.debug.cannotRestartSubprocess"
                                                            : "status.debug.cannotRestartAttached"));
                    panel().setPostMortem(dap().isPostMortem());
                    panel().setBackward(dap().isGdbSession(), dap().isRecording(), dap().canRunBackward());
                    panel().setState(state);
                }
                valueWindows.evaluateWindows.forEach(
                        DebugEvaluateWindow::sessionChanged); // their results were that stop's
                stacks.sessionChanged(); // Take Again needs a live session
                valueWindows.tableWindows.removeIf(w -> !w.isShowing());
                valueWindows.tableWindows.forEach(DebugTableWindow::sessionChanged); // Refresh needs a paused program
                if (state != DapManager.State.RUNNING && state != DapManager.State.SUSPENDED) {
                    s().dumpPause = false; // the pause it waited for will not come
                    s().dumpForParallel = false;
                }
                if (state == DapManager.State.STARTING) {
                    s().launching = false; // the launch let through has its session
                    s().recordedStops = 0; // the recording breakpoints' limit is per session
                    s().filteredHits.clear(); // and a filtered breakpoint's hit count
                    s().lastExceptionFrames = List.of(); // and the exception an exception breakpoint last showed
                    s().lastExceptionDepth = 0;
                    s().lastExceptionType = "";
                    nameSession(s().sessionLabel); // ending the replaced session cleared it
                    refreshExceptionToggles(); // the session's language decides what Caught / Uncaught mean
                    s().sessionAnnounced = !s().announceStart;
                    s().announceStart = false;
                } else if ((state == DapManager.State.RUNNING || state == DapManager.State.SUSPENDED)
                        && !s().sessionAnnounced) {
                    // Once per session: replaces "Running the before-launch step…" or "Attaching the debugger on
                    // port …", which stood in the status bar while the program sat paused (review 2, A105).
                    s().sessionAnnounced = true;
                    host.setStatus(
                            s().sessionLabel.isEmpty()
                                    ? tr("status.debug.sessionStarted")
                                    : tr("status.debug.sessionStartedNamed", s().sessionLabel));
                }
                if (state != DapManager.State.SUSPENDED) {
                    s().shownFrame = null;
                    if (s().rendering != null) {
                        s().rendering.clear(); // its references were the stop's
                    }
                    if (show) {
                        panel().setStopReason(null);
                    }
                }
                updateDebugStatus(state);
                javaCode.forgetCodeReplace(state);
                javaCode.primeCodeReplace(state);
                if (state == DapManager.State.STARTING || state == DapManager.State.INACTIVE) {
                    closeMainClassPicker(); // it chose for a launch that has been replaced or has ended (A106)
                }
                if (state == DapManager.State.INACTIVE || state == DapManager.State.STARTING) {
                    if (state == DapManager.State.STARTING) {
                        s().marks.clear(); // an object id or address means nothing to a new run
                        snapshotLaunchedLines(); // B1: what the program is built from (the files were saved)
                    } else {
                        s().launchedLines.clear();
                    }
                    s().sentBreakpoints.clear(); // the next session is told everything afresh
                    s().hitTriggers.clear(); // and its triggered breakpoints wait again
                    forgetReferenceBreakpoints(s()); // and references from the last one mean nothing
                    s().breakpointStatus.clear(); // and what the last one said about them no longer holds
                    s().evaluationFailures.clear();
                    s().reportedRejections.clear();
                    host.forEachBuffer(DebugCoordinator.this::showBreakpointStates);
                }
                if (state != DapManager.State.SUSPENDED) {
                    s().stopDeferred = false; // a stop still waiting is not the program's state any more
                    s().heldExceptionInfo = null;
                    clearExecHighlight();
                    clearDebugEditorSurfaces(); // inline values + hover live only while suspended
                    if (shown()) {
                        mapFollow.mapStop(MapDebugStop.RUNNING);
                    }
                }
                if (state == DapManager.State.INACTIVE
                        && s().relaunch == ONE_SHOT_ATTACH
                        && !s().queuedAttaches.isEmpty()) {
                    later(s().queuedAttaches.poll()); // the next JVM of the same run is waiting
                } else if (state == DapManager.State.INACTIVE && attachWaiter == s() && s().pendingAttachStop == null) {
                    attachWaiter = null; // its run attached its last JVM: the next run's goes where it is started
                } else if (state == DapManager.State.INACTIVE
                        && s().pendingAttachStop != null
                        && !beforeLaunchActive()) {
                    // A Debug Test run still waits for its next fork: Stop ends it.
                    panel().setPreparing(true, tr("debugpanel.state.waitingTest"));
                }
                boolean starting = state == DapManager.State.STARTING;
                debugLoading(starting);
                updateDebugAvailability(); // keep the window available during a session; hide it once it ends
                if (starting || state == DapManager.State.SUSPENDED) {
                    // A stop shows the window again if it was closed meanwhile (review C5) — without focus: the
                    // editor keeps it, and in the panel plain letters are debugger commands (review C2).
                    ops.openToolWindow();
                }
                if (state == DapManager.State.INACTIVE) {
                    CsharpTestDebug.ended(s()); // its dotnet test run, once it has said how the tests went
                    stopChildren(); // the program ended: its subprocesses' sessions with it
                    DebugSession self = s();
                    if (self.parent != null && !shown()) {
                        later(() -> closeSession(self)); // an ended subprocess's tab goes, unless it is shown
                    }
                }
                refreshSessionTabs();
            }

            @Override
            public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> stopFrames) {
                long deferral = ++s().stopDeferral; // a newer stop, or the program going on, makes this one stale
                if (!framesKnown(stopFrames, () -> {
                    if (deferral == s().stopDeferral && dap().state() == DapManager.State.SUSPENDED) {
                        showSuspended();
                        s().stopDeferred = false;
                        onStopped(threadId, reason, stopFrames);
                        DapModels.ExceptionInfo held = s().heldExceptionInfo;
                        s().heldExceptionInfo = null;
                        if (held != null) {
                            onExceptionInfo(threadId, held); // it came while the stop waited
                        }
                    }
                })) {
                    showSuspended(); // as it was: the window says Suspended while the mappings are worked out
                    s().stopDeferred = true;
                    return; // shown once the edited files' line mappings are known
                }
                s().stopDeferred = false; // the newest stop is the one shown
                s().stopSerial++;
                s().stopException = null; // a new stop: the last one's exception is not this one's
                execLine.hideExceptionCard();
                List<DapModels.StackFrameInfo> frames = currentLines(stopFrames);
                if (s().filtersPassed) {
                    s().filtersPassed = false;
                } else if (filteredStop(threadId, reason, frames, () -> onStopped(threadId, reason, stopFrames))) {
                    return; // a breakpoint with filters: Editora checks them before the stop is shown
                } else if (exceptions().stop(threadId, reason, frames, () -> onStopped(threadId, reason, stopFrames))) {
                    return; // an exception, where exception breakpoints decide whether it is shown
                } else if (stepFilters().stop(reason, frames)) {
                    return; // a step into code the user does not step into: out of it again
                }
                javaCode.oldCodeHintFrom(stopFrames); // B209: the stack after a replace names the old-code frames
                // A recording breakpoint: the stop is kept in Debug History and the program goes on, so the editor
                // is not taken to it (recordAndContinue).
                boolean recording = recordingHit(reason, frames);
                // A logpoint with $FUNCTION, $CALLSTACK…, or any logpoint of an adapter that does not log them
                // (netcoredbg): Editora writes its message, and the program goes on.
                String logMessage = recording ? null : logpoints().toWrite(reason, frames);
                armTriggered(reason, frames); // a breakpoint others wait for: they stop from now on
                // Thread Dump paused the running program: the pause's stop is the dump's, taken quietly. Another stop
                // the user sees came first: the dump is of it.
                boolean dumpWanted = s().dumpPause && !recording && logMessage == null;
                boolean dumpStop = dumpWanted && "pause".equals(reason);
                if (dumpWanted) {
                    s().dumpPause = false;
                }
                s().quietStop = recording || logMessage != null || dumpStop ? s().stopSerial : -1;
                if (logMessage != null) {
                    logpoints().logAndContinue(threadId, logMessage, frames); // the window never shows it Suspended
                    return;
                }
                if (dumpStop) {
                    stacks.dumpAndContinue();
                    return;
                }
                // Shown (a recording breakpoint's too: Debug History keeps it through the Call Stack).
                showSuspended();
                panel().setStopReason(reason);
                panel().setStoppedThread(threadId); // Debug History keys this stop's frames by it
                // The frame that threw: its line is drawn red while it is the one shown (review C13).
                // A core dump's crash is shown where the program's own code was, below the runtime's frames that
                // raised the signal (Go's runtime.raise, libc's abort).
                List<Path> ownCode = dap().isPostMortem() ? ownCodeRoots() : List.of();
                int crashed = ownCode.isEmpty()
                        ? firstFrameWithSource(frames, DebugCoordinator.this::showsOnStop)
                        : ownFrame(frames, DebugCoordinator.this::showsOnStop, ownCode);
                s().exceptionFrameId = "exception".equals(reason) && !frames.isEmpty()
                        ? frames.get(crashed).id()
                        : -1;
                // Selects the first frame that has source to show → selectFrame highlights + loads vars. A step
                // that went into library code shows that code (B5): the user asked to go there.
                int shown =
                        ownCode.isEmpty() ? frameToShow(reason, frames, DebugCoordinator.this::showsOnStop) : crashed;
                if (dumpWanted) {
                    stacks.takeThreadDump(s(), false, null);
                }
                if (!recording) {
                    // Before the call stack is set: that shows the stop's line, and the time goes after it.
                    s().stepTime = stepTime(reason);
                    s().stepTimeFrameId =
                            frames.isEmpty() ? -1 : frames.get(shown).id();
                }
                if (!recording && !shown()) {
                    focusSession(s()); // another session stopped: it is shown now, as in VS Code and IntelliJ
                }
                DapModels.StackPage page = dap().shownStack();
                panel().setCallStack(frames, shown, page.depth(), page.more());
                if (recording) {
                    recordAndContinue(frames.getFirst());
                    return;
                }
                breakpointMenus.removeTemporaryBreakpointHit(reason, frames);
                instructionBreakpoints.stopped(reason, frames);
                dap().stopShown(); // a Run to Cursor ends at a stop the user sees
                if (shown > 0) {
                    // A JDK or library frame jdtls can show is "library code", not "no source" (review 2, B107).
                    host.setStatus(tr(
                            frames.get(0).sourceUri() != null
                                    ? "status.debug.stoppedInLibrary"
                                    : "status.debug.stoppedWithoutSource",
                            DebugPanelText.frameName(frames.get(0).name())));
                }
                dap().threads(list -> panel().setThreads(list, dap().currentThreadId()));
            }

            @Override
            public void onOutput(String text, String category) {
                panel().appendOutput(text, category);
                followUps.output(text, category);
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
            public void onChildSession(java.util.Map<String, Object> configuration) {
                openChildSession(configuration);
            }

            @Override
            public void onDebuggeeRuntime(
                    List<String> runtime, @Nullable Path where, java.util.Map<String, String> env) {
                long generation = s().runtimeGeneration;
                com.editora.run.RuntimeProbe.probe(runtime, where, env, version -> {
                    if (generation == s().runtimeGeneration && s().panel != null) {
                        panel().setRuntime(version);
                    }
                });
            }

            @Override
            public void onError(@Nullable String message) {
                if (message != null && com.editora.dap.DapErrors.multiLine(message)) {
                    // A compiler's or adapter's report does not fit the one-line status bar: the whole of it
                    // goes to the Debug console, the status bar names its first line and says where the rest is.
                    panel().appendOutput(message.endsWith("\n") ? message : message + "\n", "stderr");
                    host.setStatus(tr(
                            "status.debug.error",
                            tr("debug.error.detailsInConsole", com.editora.dap.DapErrors.firstLine(message))));
                    return;
                }
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
            public void onThreadsChanged() {
                if (s().panel != null) {
                    s().panel.threadsChanged();
                }
            }

            @Override
            public void onExceptionInfo(int threadId, DapModels.ExceptionInfo info) {
                if (s().stopDeferred) {
                    s().heldExceptionInfo = info; // its stop is not shown yet: it would be cleared by it
                    return;
                }
                if (s().quietStop == s().stopSerial) {
                    return; // a stop not shown (or not yet: exception breakpoints are deciding)
                }
                execLine.showExceptionInfo(info);
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

            @Override
            public void onOutputTrailing() {
                panel().showOutputTrailing(); // choice 27: no longer "Running" while the last output arrives
            }

            @Override
            public void onCompileFailed() { // javac refused it: the header says so, as for a failed build (A201)
                panel().setLaunchRefused(tr("debugpanel.state.buildFailed"));
            }
        };
    }

    /** The status bar's debug spinner, for the session shown. */
    void debugLoading(boolean on) {
        if (shown()) {
            ops.setStatusDebugLoading(on);
        }
    }

    private void updateDebugStatus(DapManager.State state) {
        if (!shown()) {
            return; // another session's: the editor shows the focused one's
        }
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
                return javaCode.stackMenuItems(frame, frames);
            }

            @Override
            public void pause() {
                dap().pause();
            }

            @Override
            public void runToCursor() {
                debugRunToCursor();
            }

            @Override
            public void threadStatuses(Consumer<List<DapModels.ThreadStatus>> cb) {
                dap().threadStatuses(cb);
            }

            @Override
            public void pauseThread(int threadId) {
                dap().pauseThread(threadId);
            }

            @Override
            public void resumeThread(int threadId) {
                dap().resumeThread(threadId);
            }

            @Override
            public void showThread(DapModels.ThreadStatus thread) {
                if (dap().state() == DapManager.State.SUSPENDED) {
                    panel().showThread(thread.id()); // as if picked in the dropdown
                    selectThread(thread.id());
                } else {
                    dap().showSuspendedThread(thread.id(), thread.reason());
                }
            }

            @Override
            public void moreFrames(int loaded, java.util.function.Consumer<DapModels.StackPage> cb) {
                DebugSession session = s();
                dap().moreFrames(
                                dap().currentThreadId(),
                                loaded,
                                page -> inSession(
                                        session,
                                        () -> cb.accept(new DapModels.StackPage(
                                                currentLines(page.frames()), page.depth(), page.more()))));
            }

            @Override
            public void selectThread(int threadId) {
                int previous = dap().currentThreadId();
                dap().selectThread(threadId, threadFrames -> {
                    if (!framesKnown(threadFrames, () -> selectThread(threadId))) {
                        return;
                    }
                    List<DapModels.StackFrameInfo> frames = currentLines(threadFrames);
                    if (!frames.isEmpty() || threadId == previous) {
                        DapModels.StackPage page = dap().shownStack();
                        panel().setCallStack(
                                        frames,
                                        firstFrameWithSource(frames, DebugCoordinator.this::showsOnStop),
                                        page.depth(),
                                        page.more());
                        return;
                    }
                    // A thread that is not suspended has no stack to inspect. Stay on the stopped one: showing
                    // an empty stack beside the other thread's variables and execution line — with Step now
                    // aimed at the running thread — said nothing about why.
                    host.setStatus(tr("status.debug.threadRunning"));
                    panel().showThread(previous);
                    dap().selectThread(previous, back -> {
                        if (back.isEmpty()) { // it has been resumed meanwhile: nothing is stopped to show
                            panel().setCallStack(back, 0);
                            panel().setScopes(List.of());
                            clearExecHighlight();
                            clearDebugEditorSurfaces();
                            if (shown()) {
                                mapFollow.mapStop(MapDebugStop.RUNNING);
                            }
                        }
                    });
                });
            }

            @Override
            public void stepOver() {
                lowLevel.stepOver();
            }

            @Override
            public void stepInto() {
                lowLevel.stepInto();
            }

            @Override
            public boolean canViewMemory() {
                return dap().supportsReadMemory();
            }

            @Override
            public void viewMemory(String expression) {
                ops.openToolWindow();
                panel().memoryView().setAddressText(expression);
                panel().showArea(DebugPanel.AREA_MEMORY);
                lowLevel.memoryGo(expression);
            }

            @Override
            public void stepOut() {
                dap().stepOut();
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
                if (dap().state() != DapManager.State.SUSPENDED) {
                    return; // a frame of the previous stop, clicked while the program runs: there is no "here"
                }
                s().debugFrameId = frame.id(); // the hover evaluator's frame context
                s().shownFrame = frame;
                if (s().quietStop == s().stopSerial) {
                    return; // a recording breakpoint's stop: kept, not shown (recordAndContinue)
                }
                execLine.highlightFrame(frame);
                noteChangedSinceLaunch(frame);
                dap().scopes(frame.id(), scopes -> {
                    panel().setScopes(scopes);
                    applyInlineValues(frame, scopes);
                });
                pinnedValues.refresh(frame.id()); // every pinned value, evaluated in this frame
                lowLevel.refreshLowLevelViews(); // the Disassembly view follows the frame; Memory is read again
            }

            @Override
            public void loadChildren(int ref, Consumer<List<DapModels.VariableInfo>> cb) {
                renderedVariables(ref, null, 0, 0, cb);
            }

            @Override
            public void loadChildrenPage(
                    int ref, boolean indexed, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
                renderedVariables(ref, indexed ? "indexed" : "named", start, count, cb);
            }

            @Override
            public void evaluate(String expr, int frameId, Consumer<com.editora.dap.ConsoleEvaluation> cb) {
                dap().evaluate(expr, frameId, "repl", cb);
            }

            @Override
            public void evaluateWatch(String expr, int frameId, Consumer<DapModels.EvalResult> cb) {
                renderedEvaluate(expr, frameId, "watch", cb);
            }

            @Override
            public void evaluateCode(String expr, int frameId, Consumer<DapModels.EvalResult> cb) {
                dap().evaluateFull(expr, frameId, "repl", cb);
            }

            @Override
            public void complete(String text, int caret, int frameId, Consumer<List<DapModels.CompletionTarget>> cb) {
                dap().completions(frameId, text, caret, cb);
            }

            @Override
            public void sendInput(String line) {
                dap().sendProgramInput(line);
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
                if (rendering().owns(parentRef)) {
                    host.setStatus(tr("status.debug.renderedChild", name)); // no expression to assign to
                    return;
                }
                dap().setVariable(parentRef, name, value, cb);
            }

            @Override
            public boolean canWatchField() {
                return memberBreakpoints.canWatchFields();
            }

            @Override
            public void watchField(int parentRef, String name) {
                memberBreakpoints.watchVariable(parentRef, name);
            }

            @Override
            public boolean canJumpToType() {
                Path anchor = typeAnchor();
                return anchor != null && lspManager.supportsWorkspaceSymbols(anchor);
            }

            @Override
            public void jumpToType(String type, String value) {
                jumpToTypeSource(type, value);
            }

            @Override
            public void customizeRendering(String type, String value) {
                DebugCoordinator.this.customizeRendering(type, value);
            }

            @Override
            public void viewAsTable(String name, String expression, int ref, int named, int indexed) {
                valueWindows.viewAsTable(name, expression, ref, named, indexed);
            }

            @Override
            public boolean viewsAsData(String type) {
                return PythonDataTable.viewable(dap().sessionLanguage(), type);
            }

            @Override
            public void viewAsData(String name, String expression) {
                valueWindows.viewAsData(name, expression);
            }

            @Override
            public boolean renderersMuted() {
                return renderersMuted;
            }

            @Override
            public void toggleRenderersMuted() {
                DebugCoordinator.this.toggleRenderersMuted();
            }

            @Override
            public void setVariable(
                    int parentRef, String name, String value, String assignTo, int frameId, Consumer<String> cb) {
                if (rendering().owns(parentRef)) {
                    // A renderer's child: no container of the debugger's holds it, so its expression is assigned.
                    if (assignTo != null && !assignTo.isBlank() && frameId >= 0) {
                        dap().assignValue(name, assignTo, value, frameId, cb);
                    } else {
                        host.setStatus(tr("status.debug.renderedChild", name));
                    }
                    return;
                }
                dap().setVariable(parentRef, name, value, assignTo, frameId, cb);
            }

            @Override
            public void setExpression(
                    String expression, String value, int variableRef, int frameId, Consumer<String> cb) {
                dap().setExpression(expression, value, frameId, variableRef, cb);
            }

            @Override
            public void loadValue(DapModels.VariableInfo row, Consumer<DapModels.VariableInfo> cb) {
                dap().loadValue(row, cb);
            }

            @Override
            public boolean hexValues() {
                return dap().hexValues();
            }

            @Override
            public void setHexValues(boolean on) {
                forEachSession(
                        () -> { // a setting of the window's: every session shows numbers this way
                            dap().setHexValues(on);
                            refreshValues();
                        });
                host.setStatus(tr(on ? "status.debug.hexOn" : "status.debug.hexOff"));
            }

            @Override
            public java.util.Map<String, com.editora.dap.NumberViews.Radix> valueFormats() {
                return valueFormats; // Show Value As: the window's, every session's
            }

            @Override
            public void openText(String title, String text, String language) {
                ops.openReadOnlyDoc(title, text, language);
            }

            @Override
            public String language() {
                return dap().sessionLanguage(); // the last session's while Debug History is read after it
            }

            @Override
            public void say(String message) {
                host.setStatus(message);
            }

            @Override
            public void showHistoryFrame(DebugHistory.Stop stop, int frame) {
                DebugCoordinator.this.showHistoryFrame(stop, frame);
            }

            @Override
            public void threadDump() {
                stacks.threadDump();
            }

            @Override
            public void parallelStacks() {
                stacks.parallelStacks();
            }

            @Override
            public boolean runtimeGoroutinesHidden() {
                return goroutines().runtimeHidden();
            }

            @Override
            public void toggleRuntimeGoroutines() {
                goroutines().toggleRuntime();
            }

            @Override
            public void filterGoroutines() {
                goroutines().filter();
            }

            @Override
            public void stepBack() {
                DebugCoordinator.this.stepBack();
            }

            @Override
            public void reverseContinue() {
                DebugCoordinator.this.reverseContinue();
            }

            @Override
            public void toggleRecording() {
                DebugCoordinator.this.toggleRecording();
            }

            @Override
            public void leaveHistory() {
                DebugCoordinator.this.leaveHistory();
            }

            @Override
            public void pickValueHistory(
                    String title, List<DebugHistory.Seen> seen, java.util.function.IntConsumer go) {
                DebugCoordinator.this.pickValueHistory(title, seen, go);
            }

            @Override
            public boolean refreshValues() {
                return refreshStopValues();
            }
        };
    }

    // --- editor surfaces while suspended (inline values / hover / execution line) ------------------

    /** Fetches the selected frame's local variables and paints them as inline values in the frame's
     *  file buffer; also arms the hover value tooltip there (IntelliJ's editor surfaces). */
    private void applyInlineValues(DapModels.StackFrameInfo frame, List<DapModels.ScopeInfo> scopes) {
        if (!shown()) {
            return; // another session's: the editor shows the focused one's
        }
        if (frame == null || frame.file() == null || scopes.isEmpty()) {
            return;
        }
        DapModels.ScopeInfo locals =
                scopes.stream().filter(s -> !s.expensive()).findFirst().orElse(null);
        if (locals == null) {
            return;
        }
        dap().variables(locals.variablesReference(), vars -> {
            if (dap().state() != DapManager.State.SUSPENDED || !shown()) {
                return; // resumed, or another session shown, while the request was in flight
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (DapModels.VariableInfo v : vars) {
                if (values.size() == MAX_INLINE_VALUES) {
                    break;
                }
                values.put(v.name(), DebugValues.display(v.value())); // a function: its first line (review D9)
            }
            if (s().panel != null && s().panel.viewing() != null) {
                return; // an earlier stop is shown: its own values are painted
            }
            Path file = frame.file();
            if (s().panel != null && file != null) {
                s().panel.recordInlineValues(frame, file, frame.line(), values);
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

    // --- Debug History: an earlier stop in the editor ---------------------------------------------

    /**
     * Shows frame {@code index} of earlier stop {@code stop} in the editor: its line marked grey, the inline values and
     * pinned values it showed then. Nothing is asked of the adapter, and hovering evaluates nothing.
     */
    private void showHistoryFrame(DebugHistory.Stop stop, int index) {
        DapModels.StackFrameInfo frame = stop.frames.get(index);
        clearExecHighlight();
        if (debugValuesBuffer != null) {
            debugValuesBuffer.setInlineValues(null, -1);
            debugValuesBuffer.setDebugHoverActive(false);
            debugValuesBuffer = null;
        }
        pinnedValues.showRecorded(stop.pins());
        if (frame.file() == null || !hasSource(frame)) {
            host.setStatus(tr("status.debug.noSource", frame.name()));
            return;
        }
        Node keepFocus = execLine.debugPanelFocusOwner();
        ops.openPath(frame.file());
        EditorBuffer b = ops.bufferForPath(frame.file());
        if (keepFocus != null) {
            later(keepFocus::requestFocus);
        }
        if (b == null) {
            return;
        }
        execHighlightBuffer = b;
        ops.afterBufferLoad(b, () -> {
            if (execHighlightBuffer != b || s().panel == null || s().panel.viewing() != stop) {
                return; // another stop or frame, or back to the live one, meanwhile
            }
            int first = b.getBreakpointManager().regionFirstLine();
            b.setExecutionLine(frame.line() - first);
            b.setExecutionLineHistory(true);
            DebugHistory.Inline inline = stop.inline(index);
            if (inline != null) {
                b.setInlineValues(inline.values(), inline.line() - first);
                debugValuesBuffer = b;
            }
        });
    }

    /** Back from an earlier stop: the live stop's line, inline and pinned values; nothing once the session ended. */
    private void leaveHistory() {
        clearExecHighlight();
        pinnedValues.clear();
        DapModels.StackFrameInfo frame = s().shownFrame;
        if (dap().state() == DapManager.State.SUSPENDED && frame != null) {
            execLine.highlightFrame(frame);
            dap().scopes(frame.id(), scopes -> applyInlineValues(frame, scopes));
            pinnedValues.refresh(frame.id());
        } else {
            clearDebugEditorSurfaces();
        }
    }

    /** Show Value History: a value's stops, newest first; a pick goes back to that stop. */
    private void pickValueHistory(String title, List<DebugHistory.Seen> seen, java.util.function.IntConsumer go) {
        if (seen.isEmpty()) {
            host.setStatus(tr("status.debug.noValueHistory"));
            return;
        }
        List<DebugHistory.Seen> newestFirst = new java.util.ArrayList<>(seen);
        java.util.Collections.reverse(newestFirst);
        QuickOpen<DebugHistory.Seen> picker = new QuickOpen<>(
                title,
                tr("debugpanel.history.valueHistoryPrompt"),
                () -> newestFirst,
                v -> tr("debugpanel.history.seen", v.stop(), DebugValues.display(v.value())),
                v -> v.time().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                        + (v.changed() ? " · " + tr("debugpanel.history.changed") : ""),
                v -> {
                    if (v != null) {
                        go.accept(v.stop());
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Debug: Back (History). */
    void historyBack() {
        if (s().panel != null) {
            ops.openToolWindow();
            s().panel.historyBack();
        }
    }

    /** Debug: Forward (History). */
    void historyForward() {
        if (s().panel != null) {
            s().panel.historyForward();
        }
    }

    /** Debug: Show History…: every stop kept, newest first; a pick goes back to it. */
    void showHistory() {
        List<DebugHistory.Stop> stops =
                s().panel == null ? List.of() : s().panel.history().stops();
        if (stops.isEmpty()) {
            host.setStatus(tr("status.debug.noHistory"));
            return;
        }
        List<DebugHistory.Stop> newestFirst = new java.util.ArrayList<>(stops);
        java.util.Collections.reverse(newestFirst);
        QuickOpen<DebugHistory.Stop> picker = new QuickOpen<>(
                tr("debugpanel.history.title"),
                tr("debugpanel.history.prompt"),
                () -> newestFirst,
                DebugPanelText::historyEntry,
                st -> st.time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")),
                st -> {
                    if (st != null && s().panel != null) {
                        ops.openToolWindow();
                        s().panel.viewStop(st.number, -1);
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    // --- step timing: how long the program ran to this stop (Visual Studio's PerfTips) ----------------

    /**
     * Times each run from a stop the user saw: a step or Continue starts the clock, and a stop that only records (a
     * recording breakpoint) or logs goes on without restarting it. A launch or attach is not timed.
     */
    private void timeRun(DapManager.State state) {
        DebugSession session = s();
        DapManager.State before = session.lastState;
        session.lastState = state;
        if (state == DapManager.State.RUNNING) {
            if (before == DapManager.State.SUSPENDED && session.quietStop != session.stopSerial) {
                session.runStartNanos = System.nanoTime();
            }
        } else if (state != DapManager.State.SUSPENDED) {
            session.runStartNanos = 0;
        }
        if (state != DapManager.State.SUSPENDED) {
            session.stepTime = null;
        }
    }

    /**
     * The text after the stop's line saying how long the run to it took ({@code ≤ 1 ms elapsed}); null when it was not
     * timed, the setting is off, or the stop is a Pause's or the program's first (its time is the user's or the
     * launch's, not the code's).
     */
    private @Nullable String stepTime(String reason) {
        long start = s().runStartNanos;
        if (start == 0 || !host.settings().isDebugStepTiming() || "pause".equals(reason) || "entry".equals(reason)) {
            return null;
        }
        return tr("debug.stepTime", DebugPanelText.elapsed(System.nanoTime() - start));
    }

    // --- recording breakpoints: record the stop, then go on ------------------------------------------

    /** A session records at most this many stops; the next hit stops as a plain breakpoint does, and says why. */
    static final int MAX_RECORDED_STOPS = 500;

    /**
     * Whether the program stopped ({@code reason}) on a recording breakpoint at the top frame's line, as the adapter
     * was told the breakpoints (open files' and closed ones'), and can record it: Debug History keeps stops and the
     * session has not reached {@link #MAX_RECORDED_STOPS}. Says why when it stops instead.
     */
    private boolean recordingHit(String reason, List<DapModels.StackFrameInfo> frames) {
        if (!"breakpoint".equals(reason) || frames.isEmpty()) {
            return false;
        }
        DapModels.StackFrameInfo top = frames.getFirst();
        Path file = top.file();
        if (file == null || !recordingBreakpointAt(file, top.line())) {
            return false;
        }
        if (panel().history().capacity() == 0) {
            host.setStatus(tr("status.debug.recordingHistoryOff"));
            return false;
        }
        if (s().recordedStops >= MAX_RECORDED_STOPS) {
            host.setStatus(tr("status.debug.recordingLimit", MAX_RECORDED_STOPS));
            return false;
        }
        return true;
    }

    // --- breakpoint filters: a thread, a caller, a marked object (StopFilters), checked at the stop -------------

    /**
     * When the stop is on a breakpoint with filters, checks them — its caller, then its thread's name, then whether
     * {@code this} (or {@code self}) is the object its mark names — and counts the hit for its hit count; a stop that
     * passes is shown ({@code again}: the stop handled anew), else the program goes on without it being shown. True
     * when it is such a breakpoint's stop (the check is under way, the stop held quiet meanwhile).
     *
     * <p>An instance filter whose mark no object carries in this session passes: mark the object at the first stop,
     * and the following ones are its own.
     */
    private boolean filteredStop(int threadId, String reason, List<DapModels.StackFrameInfo> frames, Runnable again) {
        if (!"breakpoint".equals(reason) || frames.isEmpty()) {
            return false;
        }
        DapModels.StackFrameInfo top = frames.getFirst();
        Path file = top.file();
        if (file == null) {
            return false;
        }
        DapModels.LineBreakpoint lb =
                sentBreakpointAt(file, top.line(), b -> !b.filters().none());
        if (lb == null) {
            return false;
        }
        DebugSession session = s();
        int serial = session.stopSerial;
        session.quietStop = serial; // not shown while it is decided
        com.editora.config.StopFilters f = lb.filters();
        Consumer<Boolean> decide = passed -> later(() -> inSession(session, () -> {
            if (serial != session.stopSerial || session.dap.state() != DapManager.State.SUSPENDED) {
                return; // the user acted first, or it stopped again
            }
            boolean stop = passed
                    && StopFilterCheck.hitMatches(
                            lb.hitCondition(),
                            session.filteredHits.merge(key(file) + ":" + top.line(), 1, Integer::sum));
            if (stop) {
                session.filtersPassed = true;
                again.run();
            } else {
                session.dap.resume();
            }
        }));
        String caller = frames.size() > 1 ? frames.get(1).name() : null;
        if (!StopFilterCheck.matches(f.caller(), StopFilterCheck.callerNames(caller))) {
            decide.accept(false);
            return true;
        }
        Runnable instance = () -> {
            String label = ObjectMarks.tidy(f.instance());
            if (label.isEmpty() || !session.marks.hasLabel(label)) {
                decide.accept(true);
                return;
            }
            session.dap.evaluateFull(
                    StopFilterCheck.selfName(session.dap.sessionLanguage()),
                    top.id(),
                    "watch",
                    r -> decide.accept(r != null && !r.failed() && label.equals(session.marks.labelOf(r.result()))));
        };
        if (f.thread().isEmpty()) {
            instance.run();
            return true;
        }
        session.dap.threadName(threadId, name -> {
            if (StopFilterCheck.matches(f.thread(), List.of(DebugPanelText.threadName(name), name))) {
                instance.run();
            } else {
                decide.accept(false);
            }
        });
        return true;
    }

    /** Whether a recording breakpoint was sent for 0-based line {@code line} of {@code file}. */
    private boolean recordingBreakpointAt(Path file, int line) {
        return sentBreakpointAt(file, line, DapModels.LineBreakpoint::recording) != null;
    }

    /**
     * The first breakpoint sent for 0-based line {@code line} of {@code file} (open files' and closed ones') that
     * {@code which} accepts, or null.
     */
    DapModels.@Nullable LineBreakpoint sentBreakpointAt(
            Path file, int line, java.util.function.Predicate<DapModels.LineBreakpoint> which) {
        String wanted = key(file);
        List<DapModels.FileBreakpoints> sent =
                new ArrayList<>(s().sentBreakpoints.values());
        sent.addAll(s().closedBreakpoints.values());
        for (DapModels.FileBreakpoints fb : sent) {
            if (fb.file() != null && key(fb.file()).equals(wanted)) {
                for (DapModels.LineBreakpoint lb : fb.breakpoints()) {
                    if (lb.line() == line && which.test(lb)) {
                        return lb;
                    }
                }
            }
        }
        return null;
    }

    /**
     * A recording breakpoint's stop: the top frame's local values, its inline values and the watches are kept in Debug
     * History (the stop itself is already recorded with its call stack), then the program goes on. A stop the user
     * acts on first (a step, Stop) or another stop meanwhile is left as it is.
     */
    private void recordAndContinue(DapModels.StackFrameInfo top) {
        s().recordedStops++;
        int serial = s().stopSerial;
        DebugPanel panel = panel();
        DebugHistory.Stop stop = panel.history().last();
        Runnable goOn = () -> {
            if (serial != s().stopSerial || dap().state() != DapManager.State.SUSPENDED) {
                return;
            }
            if (stop != null) {
                panel.appendNotice(tr("debug.console.recorded", stop.number, DebugPanelText.historyWhere(stop)));
            }
            dap().resume();
        };
        dap().scopes(top.id(), scopes -> {
            DapModels.ScopeInfo locals =
                    scopes.stream().filter(sc -> !sc.expensive()).findFirst().orElse(null);
            if (locals == null || serial != s().stopSerial) {
                recordWatches(top, serial, goOn);
                return;
            }
            dap().variables(locals.variablesReference(), vars -> {
                if (serial != s().stopSerial) {
                    return;
                }
                DebugHistory history = panel.history();
                Map<String, String> inline = new LinkedHashMap<>();
                for (DapModels.VariableInfo v : vars) {
                    history.record(
                            0,
                            List.of(locals.name(), v.name()),
                            v.value(),
                            v.type() == null ? "" : v.type(),
                            v.variablesReference() > 0);
                    if (inline.size() < MAX_INLINE_VALUES) {
                        inline.put(v.name(), DebugValues.display(v.value()));
                    }
                }
                Path file = top.file();
                if (file != null) {
                    history.recordInline(0, file, top.line(), inline);
                }
                recordWatches(top, serial, goOn);
            });
        });
    }

    /** Evaluates the watches in {@code top} one after another into the newest stop, then runs {@code then}. */
    private void recordWatches(DapModels.StackFrameInfo top, int serial, Runnable then) {
        List<String> watches = new ArrayList<>(panel().getWatches());
        recordWatch(top, serial, watches, 0, then);
    }

    private void recordWatch(DapModels.StackFrameInfo top, int serial, List<String> watches, int index, Runnable then) {
        if (index >= watches.size() || serial != s().stopSerial) {
            then.run();
            return;
        }
        String expr = watches.get(index);
        dap().evaluateFull(expr, top.id(), "watch", r -> {
            if (serial == s().stopSerial && r != null && !r.failed()) {
                panel().history()
                        .record(
                                0,
                                List.of(tr("debugpanel.watches"), expr),
                                r.result(),
                                r.type() == null ? "" : r.type(),
                                r.variablesReference() > 0);
            }
            recordWatch(top, serial, watches, index + 1, then);
        });
    }

    private void clearDebugEditorSurfaces() {
        s().debugFrameId = -1;
        if (!shown()) {
            return; // another session's: the editor shows the focused one's
        }
        pinnedValues.clear();
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
    boolean hasSource(DapModels.StackFrameInfo frame) {
        if (frame == null || frame.file() == null) {
            return false;
        }
        try {
            return ops.bufferForPath(frame.file()) != null || com.editora.dap.FrameFiles.isFile(frame.file());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether {@code frame}'s code can be shown at all: a file ({@link #hasSource}), or code the debugger has only by
     * reference, whose text the {@code source} request gives ({@link #openReferenceSource}).
     */
    private boolean canShow(DapModels.StackFrameInfo frame) {
        return hasSource(frame) || (frame != null && frame.reference() != null);
    }

    /**
     * Whether a stop selects {@code frame}: one {@link #canShow} — but not code the adapter itself de-emphasizes (Node's
     * internals js-debug skips), so pausing a program that waits in a timer still lands on the user's line.
     */
    private boolean showsOnStop(DapModels.StackFrameInfo frame) {
        return hasSource(frame)
                || (frame != null && frame.reference() != null && !DapModels.HINT_SUBTLE.equals(frame.hint()));
    }

    /**
     * Opens {@code source}, which the debugger has only by reference, in a read-only tab with the text its
     * {@code source} request gives, then runs {@code then} with that tab. Once per session: asked again, the tab is
     * brought to the front (when it is still open). Says so when the debugger cannot give the text.
     */
    void openReferenceSource(DapModels.LoadedSource source, Consumer<EditorBuffer> then) {
        DebugSession session = s();
        DebugSession.SourceDoc known = session.sourceDocs.get(source.sourceReference());
        if (known != null && ops.selectBufferTab(known.buffer())) {
            then.accept(known.buffer());
            return;
        }
        Node keepFocus = execLine.debugPanelFocusOwner();
        dap().sourceContent(
                        source,
                        text -> inSession(session, () -> {
                            if (text == null) {
                                host.setStatus(tr("status.debug.sourceUnavailable", source.name()));
                                return;
                            }
                            EditorBuffer doc =
                                    ops.openReadOnlyDoc(source.name(), text, LoadedSourcesPicker.languageOf(source));
                            if (doc == null) {
                                return;
                            }
                            session.sourceDocs.put(source.sourceReference(), new DebugSession.SourceDoc(source, doc));
                            if (keepFocus != null) {
                                later(keepFocus::requestFocus); // stepping from the Debug window keeps it there, as
                                // with files
                            }
                            doc.whenTextInstalled(() -> then.accept(doc)); // its text is made ready off FX
                        }));
    }

    /**
     * The breakpoints of {@code doc} changed: when it holds code a live session has only by reference, that session is
     * sent them (naming the reference) and the gutter shows its answer. They are not saved: a reference holds for one
     * session ({@link #forgetReferenceBreakpoints}).
     */
    private void referenceBreakpointsChanged(EditorBuffer doc) {
        forEachSession(() -> {
            DebugSession.SourceDoc known = s().sourceDocs.values().stream()
                    .filter(d -> d.buffer() == doc)
                    .findFirst()
                    .orElse(null);
            if (known == null || !dap().isActive()) {
                return;
            }
            var mgr = doc.getBreakpointManager();
            List<DapModels.LineBreakpoint> lines = new ArrayList<>();
            for (Breakpoint bp : mgr.documentSnapshot()) {
                if (bp.enabled() && !breakpointsMuted) {
                    lines.addAll(
                            BreakpointTargets.of(bp, bp.line(), documentLineText(doc, bp.line()), doc.getLanguage()));
                }
            }
            DebugSession session = s();
            dap().updateReferenceBreakpoints(
                            known.source(),
                            lines,
                            statuses -> inSession(session, () -> {
                                if (!shown()) {
                                    return;
                                }
                                Map<Integer, DapModels.BreakpointStatus> byLine = new java.util.HashMap<>();
                                statuses.forEach(st -> byLine.put(st.line(), st));
                                Map<Integer, BreakpointManager.Live> live = new java.util.HashMap<>();
                                String pending = tr(DebugFeedback.pendingKey(byLine.values(), doc.getLanguage()));
                                for (Breakpoint bp : mgr.documentSnapshot()) {
                                    live.put(
                                            bp.line(),
                                            DebugFeedback.live(
                                                    byLine.get(bp.line()),
                                                    null,
                                                    pending,
                                                    tr("debug.breakpoint.rejected")));
                                }
                                mgr.setLive(live);
                            }));
        });
    }

    /**
     * The session ended: the breakpoints set in its tabs of code it had only by reference go (they meant nothing to any
     * other session); the tabs stay open, read-only.
     */
    private void forgetReferenceBreakpoints(DebugSession session) {
        List<DebugSession.SourceDoc> docs = List.copyOf(session.sourceDocs.values());
        session.sourceDocs.clear(); // first: removing the breakpoints below then sends nothing
        for (DebugSession.SourceDoc d : docs) {
            var mgr = d.buffer().getBreakpointManager();
            mgr.setLive(null);
            for (int line : new ArrayList<>(mgr.lines())) {
                mgr.remove(line);
            }
            d.buffer().refreshGutter();
        }
    }

    /** Pure: the frame a stop selects ({@link DebugFrameChoice#frameToShow}). */
    static int frameToShow(
            String reason, List<DapModels.StackFrameInfo> frames, Predicate<DapModels.StackFrameInfo> has) {
        return DebugFrameChoice.frameToShow(reason, frames, has);
    }

    /** Pure: the frame a core dump's stop selects ({@link DebugFrameChoice#ownFrame}). */
    static int ownFrame(
            List<DapModels.StackFrameInfo> frames, Predicate<DapModels.StackFrameInfo> has, List<Path> own) {
        return DebugFrameChoice.ownFrame(frames, has, own);
    }

    /** Where a core dump's program's own code is: the project, and the folder of the program that dumped it. */
    private List<Path> ownCodeRoots() {
        List<Path> roots = new ArrayList<>();
        Path root = ops.projectRoot();
        if (root != null) {
            roots.add(root.toAbsolutePath());
        }
        Path program = s().coreProgram;
        Path folder = program == null ? null : program.getParent();
        if (folder != null) {
            roots.add(folder.toAbsolutePath());
        }
        return roots;
    }

    /** Pure: the topmost frame with source ({@link DebugFrameChoice#firstFrameWithSource}). */
    static int firstFrameWithSource(List<DapModels.StackFrameInfo> frames, Predicate<DapModels.StackFrameInfo> has) {
        return DebugFrameChoice.firstFrameWithSource(frames, has);
    }

    // --- launch.json and the user's own adapters (the rest in LaunchJsonSessions) ---------------------

    /** Show Value As: the radix chosen for each value, by language and expression, while the window is open. */
    private final java.util.Map<String, com.editora.dap.NumberViews.Radix> valueFormats = new java.util.HashMap<>();

    /** Starts {@code request} on external adapter {@code profile}, anchored at {@code anchor}; Restart repeats it. */
    void startExternalSession(
            Path anchor, com.editora.dap.DebugLanguage profile, java.util.Map<String, Object> request, String label) {
        ops.openToolWindow();
        nameSession(label);
        Runnable start = () -> withClosedBreakpoints(() -> dap().startExternal(anchor, profile, request));
        s().relaunch = start;
        start.run();
    }

    /** A project's launch.json, read: where it is, the folder it is for, what it holds. */
    record LaunchFile(Path path, Path workspace, com.editora.dap.LaunchJson.File parsed) {}

    /**
     * The session a launch asked for now goes into: the one being handled when it is free, else a new one beside it,
     * shown from now on — a configuration started while another session runs is debugged next to it, as in VS Code.
     */
    DebugSession sessionForLaunch() {
        return sessionLive() ? openSession() : s();
    }

    /** Takes the execution-line highlight off ({@link ExecutionLine#clearExecHighlight}). */
    private void clearExecHighlight() {
        execLine.clearExecHighlight();
    }

    // --- debug commands ----------------------------------------------------------------------------

    /** Program arguments read as Run reads them; null after saying why when a quote is never closed. */
    @Nullable
    List<String> debugArgs(String text) {
        try {
            return ProgramArgs.parse(text);
        } catch (ProgramArgs.UnclosedQuoteException e) {
            host.setStatus(tr("status.run.argsUnclosedQuote", String.valueOf(e.quote())));
            return null;
        }
    }

    /** A launch's plan could not be read (the disk, a remote host gone): says so, instead of doing nothing. */
    void planFailed(Path file, @Nullable Exception why) {
        String reason = why == null || why.getMessage() == null ? String.valueOf(why) : why.getMessage();
        host.setStatus(tr("status.debug.planFailed", file.getFileName(), reason));
    }

    /**
     * What a run configuration adds to a file's launch: variables for the program's environment, the folder it runs
     * in (null: where Debug File runs it), and whether a before-launch step of its own has already written to the
     * Debug console, which the build's output then follows instead of replacing.
     */
    record LaunchExtras(
            java.util.Map<String, String> env, @Nullable Path cwd, boolean afterStep, boolean config) {
        static final LaunchExtras NONE = new LaunchExtras(java.util.Map.of(), null, false, false);
    }

    /** How long Cargo has to list what it just built. */
    static final java.time.Duration CARGO_LIST_TIMEOUT = java.time.Duration.ofMinutes(2);

    /** The edited buffers of {@code language} (null: of any) under {@code root}: a build reads them from disk. */
    List<EditorBuffer> buffersUnder(@Nullable Path root, @Nullable String language) {
        List<EditorBuffer> out = new ArrayList<>();
        host.forEachBuffer(other -> {
            Path p = other.getPath();
            if (other.isDirty()
                    && p != null
                    && (language == null || language.equals(other.getLanguage()))
                    && root != null
                    && p.toAbsolutePath().startsWith(root)) {
                out.add(other);
            }
        });
        return out;
    }

    /** {@link SaveChain#savedOrThen} through this window's save path: no nested wait (PERF-01, Pauta 6.2.0). */
    boolean savedOrThen(List<EditorBuffer> buffers, Runnable again) {
        DebugSession session = s();
        Runnable asked = bound(again); // the saves answer later
        boolean saved = SaveChain.savedOrThen(
                buffers,
                ops::saveThen,
                () -> {
                    session.stepAnswered();
                    asked.run();
                    session.launchStepDone();
                },
                () -> { // refused, or edited again meanwhile: the launch is not getting ready
                    session.stepAnswered();
                    session.launchStepDone();
                });
        if (!saved) {
            session.stepPending();
        }
        return saved;
    }

    /** The 0-based document line of {@code b}'s caret. */
    static int caretLine(EditorBuffer b) {
        return b.getFocusedArea().getCurrentParagraph()
                + b.getBreakpointManager().regionFirstLine();
    }

    /** {@link DebugFileLaunch#debugStart}: F5. */
    void debugStart() {
        fileLaunch.debugStart();
    }

    /** {@link DebugFileLaunch#debugFile}: Debug File. */
    void debugFile(EditorBuffer b) {
        fileLaunch.debugFile(b);
    }

    /** {@link DebugFileLaunch#launchLiveFor}: a launch of {@code file} is live or getting ready. */
    boolean launchLiveFor(Path file) {
        return fileLaunch.launchLiveFor(file);
    }

    /** {@link DebugFileLaunch#launchBuffer}: saves {@code b} and launches a debug session for it. */
    void launchBuffer(@Nullable EditorBuffer b) {
        fileLaunch.launchBuffer(b);
    }

    /** {@link CompiledDebugLaunch#debugGo}. */
    void debugGo(Path path, String text, int caret, List<String> args, LaunchExtras extras) {
        compiled.debugGo(path, text, caret, args, extras);
    }

    /** {@link CompiledDebugLaunch#debugRust}. */
    void debugRust(Path path, String text, int caret, List<String> args, LaunchExtras extras) {
        compiled.debugRust(path, text, caret, args, extras);
    }

    /** {@link CompiledDebugLaunch#debugNative}. */
    void debugNative(Path path, boolean cpp, List<String> args, LaunchExtras extras) {
        compiled.debugNative(path, cpp, args, extras);
    }

    // --- Java launch preparation: in JavaDebugLaunch; these are called from elsewhere in this class ---

    private String presentJdk(String home) {
        return javaLaunch.presentJdk(home);
    }

    private static String looseJava(String selected) {
        return JavaDebugLaunch.looseJava(selected);
    }

    private void looseBuildThenLaunch(Path routing, Runnable launch) {
        javaLaunch.looseBuildThenLaunch(routing, launch);
    }

    private void dropPendingBuild() {
        javaLaunch.dropPendingBuild();
    }

    private List<EditorBuffer> javaBuffers(Path launched, @Nullable Path project, boolean includeLaunched) {
        return javaLaunch.javaBuffers(launched, project, includeLaunched);
    }

    private void buildThenLaunch(Path routing, Runnable launch) {
        javaLaunch.buildThenLaunch(routing, launch);
    }

    private void relaunchFor(Path file, Consumer<EditorBuffer> launch) {
        javaLaunch.relaunchFor(file, launch);
    }

    void debugConfig(RunConfiguration cfg) {
        javaLaunch.debugConfig(cfg);
    }

    private void debugKotlinProgram(Path source, @Nullable Path cwd, String java, java.util.Map<String, String> env) {
        javaLaunch.debugKotlinProgram(source, cwd, java, env);
    }

    private String configuredJdkHome(@Nullable Path root, @Nullable RunConfiguration cfg) {
        return javaLaunch.configuredJdkHome(root, cfg);
    }

    void debugMainClassNamed(String fqn) {
        javaLaunch.debugMainClassNamed(fqn);
    }

    /**
     * Whether a debug launch that loads {@code gate}'s project may start: that tool's tasks tree asks for workspace
     * trust as it would before its own build ({@link Ops#buildExecutable}), as Run does
     * ({@code RunCoordinator.trusted}). True when there is no gate. Declining starts nothing; the gate said why.
     */
    boolean trusted(com.editora.run.RunTrust.@Nullable Gate gate) {
        return gate == null || ops.buildExecutable(gate.tool(), gate.root()) != null;
    }

    /**
     * Why {@code language} cannot be debugged here, as the user can act on it: Python or JavaScript debugging
     * turned off, or its adapter not found (with the command that installs it). One message used to cover all
     * of these, and every other reason too.
     */
    String unavailableMessage(@Nullable String language, @Nullable Path file) {
        boolean local = file == null || com.editora.process.ExecTargets.runsHere(file);
        if (!local || !debugSupportEnabled()) {
            return tr("status.debug.unavailable");
        }
        String id = language == null ? "" : DapServerRegistry.adapterLanguage(language);
        if ("java".equals(id)) {
            return javaUnavailableMessage();
        }
        if ("cpp".equals(id) && gdbForCpp() && host.settings().isCppDebugEnabled()) {
            return tr("status.debug.gdbMissing");
        }
        DebugAdapterSettings.Binding settings = DebugAdapterSettings.of(id);
        if (settings == null || settings.offKey() == null || settings.missing() == null) {
            return tr("status.debug.unavailable");
        }
        var s = host.settings();
        return !settings.isEnabled(s)
                ? tr(settings.offKey())
                : settings.missing().apply(s, host.projectScope());
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
        if (!dap().isAdapterAvailable()) {
            return tr("status.debug.javaDebugMissing");
        }
        return tr("status.debug.unavailable");
    }

    /**
     * The language of {@code file} when Editora's own JVM adapter debugs it (Kotlin, Groovy) — an attach for it
     * goes there, since java-debug never binds a breakpoint outside a {@code .java} file; null otherwise. Pure.
     */
    static @Nullable String jvmAdapterLanguage(Path file) {
        if (file == null || file.getFileName() == null) {
            return null;
        }
        String language =
                com.editora.text.LanguageRegistry.forFileName(file.getFileName().toString());
        DapServerRegistry.DapServerSpec spec = DapServerRegistry.specFor(language);
        return spec != null && spec.kind() == DapServerRegistry.Kind.JVM ? language : null;
    }

    /** The saved configuration called {@code name} as it is now, else {@code fallback} (it was deleted). */
    RunConfiguration currentConfiguration(String name, RunConfiguration fallback) {
        for (RunConfiguration c : ops.runConfigurations()) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return fallback;
    }

    /** Whether two paths refer to the same file (normalized absolute comparison; null-safe). */
    static boolean samePath(@Nullable Path a, @Nullable Path b) {
        return a != null && b != null && PathKeys.sameNormalized(a, b);
    }

    /** Resumes and stops at the active buffer's caret line via a one-shot temporary breakpoint. */
    void debugRunToCursor() {
        EditorBuffer b = host.activeBuffer();
        if (b != null) {
            runToLine(b, fileLineAtCaret(b), false);
        }
    }

    /**
     * Run to Cursor at 0-based file line {@code fileLine} of {@code b} — the caret's, or the line whose number was
     * clicked in the gutter; {@code force}: every breakpoint on the way is ignored (Force Run to Cursor).
     */
    void runToLine(EditorBuffer b, int fileLine, boolean force) {
        Path path = b.getPath();
        if (path == null || dap().state() != DapManager.State.SUSPENDED) {
            return;
        }
        if (!linesKnown(b, () -> runToLine(b, fileLine, force))) {
            return; // run once the mapping of the edited text is known
        }
        int line = lineMap(b).toLaunched(fileLine);
        if (line < 0) {
            host.setStatus(tr("status.debug.lineAddedSinceLaunch"));
            return;
        }
        if (force) {
            dap().forceRunToCursor(path, line);
        } else {
            dap().runToCursor(path, line);
        }
    }

    /**
     * The file a type is looked up from: the shown frame's (its language server knows the code that stopped), else
     * the file the session debugs; {@code null} when neither is a file.
     */
    private @Nullable Path typeAnchor() {
        DapModels.StackFrameInfo frame = s().shownFrame;
        if (frame != null && frame.file() != null && hasSource(frame)) {
            return frame.file();
        }
        return dap().debugFile();
    }

    /**
     * IntelliJ's Jump to Type Source: opens the class, struct or interface a value has — its own type, which may be a
     * subclass of the variable's declared one — found by the language server's workspace symbols, and for Java among
     * the JDK's and the dependencies' classes too. Says so when nothing declares it.
     */
    void jumpToTypeSource(String type, String value) {
        DebugTypeSource.TypeName name = DebugTypeSource.parse(type, value);
        String shown = type == null || type.isBlank() ? value : type;
        Path anchor = typeAnchor();
        if (name == null || anchor == null || !lspManager.supportsWorkspaceSymbols(anchor)) {
            host.setStatus(tr("status.debug.typeNotFound", DebugPanelText.oneLine(shown, 60)));
            return;
        }
        lspManager.workspaceSymbols(anchor, name.simple(), matches -> {
            LspManager.WorkspaceSymbolMatch found = DebugTypeSource.best(name, matches);
            // The project's own class, or the one its qualifier names, is the answer; a library namesake may not be
            // (Arrays.ArrayList for ArrayList), so the library's classes are asked too.
            if (found != null
                    && (DebugTypeSource.sameQualifier(name.qualifier(), found.container())
                            || (name.qualifier().isEmpty() && found.file() != null))) {
                lsp.navigation.openWorkspaceSymbol(anchor, found);
                return;
            }
            // Not the project's own (java.util.ArrayList): jdtls finds the JDK's and the dependencies' classes apart.
            lsp.libraryTypes(name.simple(), library -> {
                List<LspManager.WorkspaceSymbolMatch> all = new ArrayList<>(matches);
                all.addAll(library);
                LspManager.WorkspaceSymbolMatch best = DebugTypeSource.best(name, all);
                if (best == null) {
                    host.setStatus(tr("status.debug.typeNotFound", DebugPanelText.oneLine(shown, 60)));
                } else {
                    lsp.navigation.openWorkspaceSymbol(anchor, best);
                }
            });
        });
    }

    /** Debug: Show Numbers as Hexadecimal — on or off, in Variables, watches, hover and inline values. */
    void toggleHex() {
        panel().toggleHexValues();
    }

    /** Debug: Force Run to Cursor — Run to Cursor with every breakpoint on the way ignored. */
    void debugForceRunToCursor() {
        EditorBuffer b = host.activeBuffer();
        if (b != null) {
            runToLine(b, fileLineAtCaret(b), true);
        }
    }

    /** Jump to Line: move the execution pointer to the caret line without executing in-between code.
     *  Capability-gated — debugpy supports it; java-debug/js-debug report "not supported". */
    void debugJumpToLine() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || dap().state() != DapManager.State.SUSPENDED) {
            return;
        }
        if (!dap().supportsJumpToLine()) {
            host.setStatus(tr("status.debug.jumpUnsupported"));
            return;
        }
        if (!linesKnown(b, onSameLine(b, b.getFocusedArea().getCurrentParagraph(), this::debugJumpToLine))) {
            return;
        }
        int line = lineMap(b).toLaunched(fileLineAtCaret(b));
        if (line < 0) {
            host.setStatus(tr("status.debug.lineAddedSinceLaunch"));
            return;
        }
        dap().jumpToLine(
                        b.getPath(),
                        line,
                        err -> host.setStatus(err.isEmpty() ? tr("status.debug.jumpNoTarget") : err));
    }

    /** The caret's line in the whole file (a narrowed buffer's paragraphs are region-relative). */
    private static int fileLineAtCaret(EditorBuffer b) {
        return b.getFocusedArea().getCurrentParagraph()
                + b.getBreakpointManager().regionFirstLine();
    }

    /**
     * The session a Debug Test run is waiting for its JVM in ({@link #setPendingAttach}): the run's callbacks come
     * from the test runner, while the user may have focused another session meanwhile — its attach goes here.
     */
    @Nullable
    DebugSession attachWaiter;

    /** Main-class chooser (QuickOpen) when jdtls finds several. */
    void pickMainClass(
            List<DapManager.MainClassOption> options, Consumer<DapManager.@Nullable MainClassOption> chosen) {
        javaLaunch.pickMainClass(options, chosen);
    }

    private void closeMainClassPicker() {
        javaLaunch.closeMainClassPicker();
    }

    // The shared caret-line checks ({@link CaretBreakpoints}), used by many breakpoint commands here.
    Runnable onSameLine(EditorBuffer b, int line, Runnable command) {
        return caretBreakpoints.onSameLine(b, line, command);
    }

    boolean breakpointAllowed(EditorBuffer b, int line, Runnable retry) {
        return caretBreakpoints.breakpointAllowed(b, line, retry);
    }

    @Nullable
    EditorBuffer breakpointBuffer() {
        return caretBreakpoints.breakpointBuffer();
    }

    /**
     * Debug: Exception Breakpoints — a picker over the filters the adapter offers (the live session's
     * {@code exceptionBreakpointFilters}, else those that language's adapter is known to offer), each marked when on,
     * then the language's exception breakpoints and Add ({@link ExceptionBreakpointCommands}).
     */
    void toggleExceptionBreakpoints() {
        exceptions().pick();
    }

    /** Debug: Add Exception Breakpoint ({@code debug.addExceptionBreakpoint}). */
    void addExceptionBreakpoint() {
        exceptions().add();
    }

    /** Tells every session the exception filters this project's exception breakpoints need. */
    void pushExceptionBreakpoints() {
        exceptions().push();
    }

    private @Nullable ExceptionBreakpointCommands exceptionCommands;

    private @Nullable TokenLogpoints tokenLogpoints;

    /** Logpoints whose message Editora writes. */
    TokenLogpoints logpoints() {
        TokenLogpoints t = tokenLogpoints;
        if (t == null) {
            t = new TokenLogpoints(this);
            tokenLogpoints = t;
        }
        return t;
    }

    /** Exception breakpoints: their stop check, picker rows and form. */
    ExceptionBreakpointCommands exceptions() {
        ExceptionBreakpointCommands e = exceptionCommands;
        if (e == null) {
            e = new ExceptionBreakpointCommands(this, host, ops);
            exceptionCommands = e;
        }
        return e;
    }

    /** A picker row: the filter's label, marked when it is on for {@code language}. */
    String exceptionRow(String language, DapModels.ExceptionFilterOption o) {
        return (dap().exceptionFiltersFor(language).contains(o.filter()) ? "✓ " : "    ")
                + com.editora.dap.ExceptionFilterCatalog.displayLabel(o);
    }

    /** Turns {@code language}'s exception filter {@code filter} on or off (the picker's choice), and says so. */
    void toggleExceptionFilter(String language, @Nullable String filter) {
        restoreExceptionFilters();
        List<DapModels.ExceptionFilterOption> options = dap().exceptionFilterOptions(language);
        List<String> next = toggledFilter(dap().exceptionFiltersFor(language), filter, options);
        forEachSession(() -> dap().setExceptionFilters(language, next)); // each live session of that language
        ops.persistDebugExceptionFilters(dap().exceptionChoices()); // C12: kept across restarts, per project
        refreshExceptionToggles();
        host.setStatus(exceptionStatus(language, next, options));
    }

    /** The adapter language the exception picker is about: the live session's, else the active file's. */
    @Nullable
    String exceptionLanguage() {
        if (dap().isActive()) {
            return dap().sessionLanguage();
        }
        EditorBuffer b = host.activeBuffer();
        String language = b == null ? null : b.getLanguage();
        return language != null && DapServerRegistry.isDebuggable(language)
                ? DapServerRegistry.adapterLanguage(language)
                : null;
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
                dap().setExceptionFilters(language, filters);
            }
        });
        if (ops.debugBreakpointsMuted() && !breakpointsMuted) {
            breakpointsMuted = true; // Mute Breakpoints is kept per project, like the exception choices
            dap().setExceptionsMuted(true);
            breakpointMenus.showBreakpointsMuted();
        }
        memberBreakpoints.push(); // the method breakpoints and field watchpoints this project keeps
        pushExceptionBreakpoints(); // and the exception filters its exception breakpoints need
    }

    /**
     * The language the Caught / Uncaught toggles act on: the live session's, else the active file's — and Java
     * when neither says (the toggles are always there to set up the next Java session).
     */
    String exceptionToggleLanguage() {
        String language = exceptionLanguage();
        return language == null || com.editora.dap.ExceptionFilterCatalog.caughtFilter(language) == null
                ? "java"
                : language;
    }

    /** Whether the Caught ({@code caught}) or Uncaught toggle is on for {@link #exceptionToggleLanguage()}. */
    boolean exceptionToggleOn(boolean caught) {
        restoreExceptionFilters();
        String language = exceptionToggleLanguage();
        String filter = caught
                ? com.editora.dap.ExceptionFilterCatalog.caughtFilter(language)
                : com.editora.dap.ExceptionFilterCatalog.uncaughtFilter(language);
        return filter != null && dap().exceptionFiltersFor(language).contains(filter);
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
        forEachSession(() -> {
            if (s().panel != null) { // each session's own language decides what they mean
                s().panel.setExceptionToggles(exceptionToggleOn(true), exceptionToggleOn(false));
            }
        });
    }

    /** Pure: {@code current} with {@code filter} turned on or off, in the order the adapter lists its filters. */
    static List<String> toggledFilter(
            List<String> current, @Nullable String filter, List<DapModels.ExceptionFilterOption> options) {
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
        // While muted, the choice is kept but stops nothing: it must not read as active (review 3, note on Mute).
        return tr(
                breakpointsMuted ? "status.debug.exceptionFiltersMuted" : "status.debug.exceptionFilters",
                debugAdapterLabel(language),
                String.join(", ", labels));
    }

    // --- adapter pickers ---------------------------------------------------------------------------

    static String debugAdapterLabel(String id) {
        return DebugAdapterPickers.label(id);
    }

    /** Picker over the python/javascript debug adapters: toggles the chosen one's enable. */
    void chooseAdapterToggle() {
        new DebugAdapterPickers(host, this::applySupport).chooseToggle();
    }

    /** Picker over the java/python/javascript debug adapters, then prompts for that adapter's path. */
    void chooseAdapterPath() {
        new DebugAdapterPickers(host, this::applySupport).choosePath();
    }

    // --- buffer wiring + panel-action delegates ----------------------------------------------------

    /**
     * A gutter click's breakpoint, once whether the line has code is known: added, unless one is there by then. The
     * text may change again before that is known: asked again, not dropped.
     */
    private void addGutterBreakpoint(EditorBuffer b, int line) {
        if (!b.isDisposed()
                && !b.getBreakpointManager().isBreakpoint(line)
                && breakpointAllowed(b, line, () -> addGutterBreakpoint(b, line))) {
            b.toggleBreakpoint(line);
        }
    }

    /** Wires the debug hooks onto a freshly-added buffer (breakpoint gutter gate + change/hover hooks +
     *  restore persisted breakpoints). Called from {@code MainController.addBuffer}. */
    void wireBuffer(EditorBuffer buffer) {
        buffer.setBreakpointsEnabled(debugSupportEnabled() && isDebuggableBuffer(buffer));
        buffer.setOnBreakpointRemoved((b, bp) -> BreakpointRemovalUndo.removedInTab(host, b, bp));
        buffer.getBreakpointManager().setMuted(breakpointsMuted ? tr("debug.breakpoint.muted") : "");
        buffer.setGutterBreakpointClick((b, line) -> {
            var mark = b.getBreakpointManager().isBreakpoint(line)
                    ? null
                    : b.getBreakpointManager().memberMark(line);
            if (mark != null) {
                mark.members().forEach(memberBreakpoints::toggle); // a method breakpoint's mark: removes it
            } else if (breakpointAllowed(b, line, () -> addGutterBreakpoint(b, line))) {
                b.toggleBreakpoint(line);
            }
        });
        buffer.setGutterLogpointClick((b, line) -> {
            if (b.getBreakpointManager().isBreakpoint(line)
                    || b.getBreakpointManager().memberMark(line) == null) {
                // IntelliJ's Shift+click: a breakpoint that logs and does not suspend
                caretBreakpoints.addLogpoint(b, line);
            }
        });
        buffer.setGutterRunToHere(new EditorBuffer.GutterRunToHere() {
            @Override
            public boolean offeredAt(EditorBuffer b, int line) {
                return b.getPath() != null
                        && dap().state() == DapManager.State.SUSPENDED
                        && isDebuggableBuffer(b)
                        && (s().panel == null || s().panel.viewing() == null); // not while an earlier stop is shown
            }

            @Override
            public String tooltip(EditorBuffer b, int line) {
                return tr(
                        "debug.runToHere.tooltip",
                        line + b.getBreakpointManager().regionFirstLine() + 1);
            }

            @Override
            public void run(EditorBuffer b, int line, boolean force) {
                runToLine(b, line + b.getBreakpointManager().regionFirstLine(), force);
            }
        });
        buffer.setOnBreakpointsChanged(() -> onBreakpointsChanged(buffer));
        // Hover value tooltip while suspended: evaluate the hovered identifier in the selected frame.
        buffer.setDebugHoverEvaluator((expr, cb) -> dap().evaluateHover(expr, s().debugFrameId, cb));
        buffer.setDebugValuePresenter(
                valuePopups.valuePresenter); // an object's fields unfold in the popup (follow-up 6)
        // "Debug File" beside "Run File" in the editor's menu: Debug was only in the Run menu and the palette.
        // Through F5's check: a live launch of another file is replaced only after asking (review 3, C202).
        // Not while this very file is being debugged or built for it, as in editorMenuItems (review 2 C110): a
        // runnable file's Debug File sits here, and a classic Java program became runnable (single-file Run).
        buffer.setDebugFileHandler(
                () -> debugFile(buffer),
                () -> debugSupportEnabled()
                        && isDebuggableBuffer(buffer)
                        && (buffer.getPath() == null || !launchLiveFor(buffer.getPath())));
        buffer.setGutterBreakpointMenu(
                breakpointMenus::showGutterMenu); // right-click a breakpoint: Edit / Disable / Remove
        buffer.setDebugMenuContributor(() -> breakpointMenus.editorMenuItems(buffer));
    }

    // --- type renderers: a value of a type shown as the user's expressions say (TypeRendering) ----------

    /** Mute Renderers ({@code debug.muteRenderers}): every value as the debugger shows it. For every session. */
    private boolean renderersMuted;

    /** The focused session's type renderers at work, built on first use. */
    TypeRendering rendering() {
        DebugSession session = s();
        TypeRendering rendering = session.rendering;
        if (rendering == null) {
            rendering = new TypeRendering(
                    () -> host.settings().getDebugTypeRenderers(),
                    () -> session.dap.sessionLanguage(),
                    () -> renderersMuted,
                    (expression, frameId, cb) -> session.dap.evaluateFull(expression, frameId, "watch", cb));
            session.rendering = rendering;
        }
        return rendering;
    }

    /**
     * The children of {@code ref} as the Variables tree, the Evaluate window and the value popup show them: a rendered
     * value's own (Editora's reference), else the debugger's — {@code filter}, {@code start} and {@code count} page
     * them as DAP does ({@code null} and 0: all) — each with its type's renderer applied.
     */
    void renderedVariables(
            int ref, @Nullable String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
        TypeRendering rendering = rendering();
        if (rendering.owns(ref)) {
            rendering.children(ref, cb);
            return;
        }
        int frameId = s().debugFrameId;
        Consumer<List<DapModels.VariableInfo>> render = vars -> rendering.apply(vars, frameId, cb);
        if (filter == null && count == 0) {
            dap().variables(ref, render);
        } else {
            dap().variables(ref, filter, start, count, render);
        }
    }

    /**
     * Evaluates {@code expression} ({@code context}: {@code watch}, {@code hover} or {@code repl}) and renders its
     * value when its type has a renderer — only when evaluating it again cannot change anything (no call, no
     * assignment): the renderer's expressions are the value's expression with more after it.
     */
    void renderedEvaluate(String expression, int frameId, String context, Consumer<DapModels.EvalResult> cb) {
        TypeRendering rendering = rendering();
        dap().evaluateFull(expression, frameId, context, r -> {
            TypeRenderer renderer = r == null || r.failed() || !TypeRenderers.safeToRepeat(expression)
                    ? null
                    : rendering.rendererFor(r.type(), r.result());
            if (renderer == null) {
                cb.accept(r);
                return;
            }
            rendering.render(
                    renderer,
                    expression,
                    frameId,
                    r.result(),
                    r.variablesReference(),
                    (value, ref, named) -> cb.accept(new DapModels.EvalResult(
                            value,
                            ref,
                            r.type(),
                            ref == r.variablesReference() ? r.namedVariables() : named,
                            ref == r.variablesReference() ? r.indexedVariables() : 0,
                            false)));
        });
    }

    /**
     * Customize Rendering… (the Variables menu, IntelliJ's Customize Data Views): the renderer of the type a value has,
     * of the session's language — a new one named after the type when there is none — in its form; saved to Settings
     * and the values shown again.
     */
    void customizeRendering(String type, String value) {
        DebugTypeSource.TypeName name = DebugTypeSource.parse(type, value);
        if (name == null) {
            return;
        }
        String language = dap().sessionLanguage();
        List<TypeRenderer> all = new ArrayList<>();
        host.settings().getDebugTypeRenderers().forEach(r -> all.add(r.copy()));
        int at = TypeRenderers.indexOf(all, language, type, value);
        TypeRenderer initial =
                at >= 0 ? all.get(at) : new TypeRenderer(true, language, TypeRenderers.typeName(name, type), "", "");
        (rendererEditor != null
                        ? rendererEditor
                        : (RendererEditor) (r0, isNew) -> TypeRendererDialog.edit(host.window(), r0, isNew))
                .edit(initial, at < 0)
                .ifPresent(r -> {
                    if (at >= 0) {
                        all.set(at, r);
                    } else {
                        all.add(r);
                    }
                    ops.persistDebugTypeRenderers(all);
                    host.setStatus(tr("status.debug.rendererSaved", TypeRenderers.label(r)));
                    refreshStopValues();
                });
    }

    /** Shows the renderer form; a test answers it without a window. */
    interface RendererEditor {
        java.util.Optional<TypeRenderer> edit(TypeRenderer initial, boolean isNew);
    }

    /** The renderer form; null: the dialog (a test sets one that answers without a window). */
    @Nullable
    RendererEditor rendererEditor;

    /** Mute Renderers / Unmute Renderers: the values shown again, as the debugger or the renderers give them. */
    void toggleRenderersMuted() {
        renderersMuted = !renderersMuted;
        host.setStatus(tr(renderersMuted ? "status.debug.renderersMuted" : "status.debug.renderersOn"));
        refreshStopValues();
    }

    boolean renderersMuted() {
        return renderersMuted;
    }

    /**
     * Debug: Threads — opens the Debug window on its Threads view: every thread of the program, which are suspended,
     * and Pause / Resume / Show Frames for each.
     */
    void showThreads() {
        ops.openToolWindow();
        panel().showThreadsView(true);
    }

    // --- reverse debugging (Step Back, Reverse Continue, Record Execution), values fetched again ----------

    /**
     * Debug: Step Back ({@code debug.stepBack}): the program goes back over the last statement — where it can run
     * backward: an adapter on rr (Delve, CodeLLDB), or GDB recording it (Record Execution). Else it says how.
     */
    void stepBack() {
        dap().stepBack();
    }

    /** Debug: Reverse Continue ({@code debug.reverseContinue}): back to the previous breakpoint, or the record's start. */
    void reverseContinue() {
        dap().reverseContinue();
    }

    /**
     * Debug: Record Execution ({@code debug.toggleRecording}), in a GDB session paused at a stop: GDB records the
     * program from here so Step Back and Reverse Continue can go back over it; again, it stops recording.
     */
    void toggleRecording() {
        DapManager manager = dap();
        manager.toggleRecording(failure -> host.setStatus(
                failure != null
                        ? tr("status.debug.recordFailed", failure)
                        : tr(manager.isRecording() ? "status.debug.recordingOn" : "status.debug.recordingOff")));
    }

    /**
     * Fetches the selected frame's variables, watches and inline values again: an evaluation or Set Value may have
     * changed them (B205). False when there is nothing to fetch them from.
     */
    boolean refreshStopValues() {
        DapModels.StackFrameInfo frame = s().shownFrame;
        if (frame == null || dap().state() != DapManager.State.SUSPENDED) {
            return false;
        }
        dap().forgetValues(); // B205: what the program now says, not this stop's first answers
        rendering().clear(); // the tree is fetched again, and so are the rendered values
        dap().scopes(frame.id(), scopes -> {
            panel().setScopes(scopes);
            applyInlineValues(frame, scopes);
        });
        return true;
    }

    /**
     * Ends the debugged program's standard input ({@code debug.endProgramInput}, and Ctrl+D in the console's
     * input field): whatever reads it sees end of file. Only a program Editora started itself has an input
     * to end — see {@link DapManager#setProgramConsole}.
     */
    void endProgramInput() {
        if (dap().closeProgramInput()) {
            panel().showInputEnded();
        } else {
            host.setStatus(tr("status.debug.noProgramInput"));
        }
    }

    void addWatch() {
        ops.openToolWindow(); // also while idle (review 2 C109): the watch is added where the user sees it
        panel().addWatch();
    }

    void setSelectedValue() {
        panel().setSelectedValue();
    }

    private @Nullable StepFilterCommands stepFilterCommands;

    private @Nullable GoroutineCommands goroutineCommands;

    /** Go's goroutines in the Threads view. */
    GoroutineCommands goroutines() {
        GoroutineCommands c = goroutineCommands;
        if (c == null) {
            c = new GoroutineCommands(this, host, ops);
            goroutineCommands = c;
        }
        return c;
    }

    /** Lists the session's threads again in its Threads view. */
    void refreshThreads() {
        DebugSession session = s();
        dap().threads(list -> inSession(session, () -> panel().setThreads(list, dap().currentThreadId())));
    }

    StepFilterCommands stepFilters() {
        StepFilterCommands c = stepFilterCommands;
        if (c == null) {
            c = new StepFilterCommands(this, host, ops);
            stepFilterCommands = c;
        }
        return c;
    }

    // --- review C: breakpoints list, mute, gutter and editor menus, Show Value -----------------------------

    /**
     * Mute Breakpoints: the adapter is told no breakpoints until it is turned off again. Kept per project in the
     * workspace state and restored when the window starts; every breakpoint glyph is drawn faded meanwhile.
     */
    boolean breakpointsMuted;

    boolean breakpointsMuted() {
        return breakpointsMuted;
    }
}
