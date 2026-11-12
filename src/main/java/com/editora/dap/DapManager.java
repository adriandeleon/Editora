package com.editora.dap;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Platform;

import com.editora.lsp.LspManager;
import com.editora.process.BusyExecutable;
import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * UI-facing facade for Java debugging over DAP (mirrors {@code LspManager} + {@code RunService}). Owns the
 * single active {@link DapClient} (one debug session at a time, like Run), locates the java-debug plugin
 * jar (via {@link DebugAdapterLocator}), and drives the full launch sequence against jdtls — resolve main
 * class / classpath / java executable, start the debug session (which returns a socket port via
 * {@code workspace/executeCommand}), connect the DAP socket, then {@code launch}/{@code attach}. All public
 * methods are safe to call from the FX thread; socket I/O and event callbacks run off it and results are
 * marshaled back via {@link Platform#runLater}. Each session's DAP events arrive through its own
 * host ({@code sessionHost(epoch)}), bound to the session epoch, so an event of an ended session never reaches the next.
 */
public final class DapManager {

    private static final Logger LOG = Logger.getLogger(DapManager.class.getName());

    /** Session lifecycle states. */
    public enum State {
        INACTIVE,
        STARTING,
        RUNNING,
        SUSPENDED,
        TERMINATED
    }

    /** A candidate main class returned by jdtls's {@code vscode.java.resolveMainClass}. */
    public record MainClassOption(
            @Nullable String mainClass,
            @Nullable String projectName,
            @Nullable String filePath) {
        /**
         * The plain fully-qualified class name. For a class of a named module jdtls answers {@code
         * <module>/<class>} in {@link #mainClass} (what the launch needs); a saved configuration and the
         * gutter name the class alone, so that is what a main class is matched by.
         */
        public @Nullable String className() {
            return JavaLaunchSupport.className(mainClass);
        }
    }

    /**
     * The resolved launch inputs for a main class (from jdtls {@code resolveClasspath}/{@code
     * resolveJavaExecutable}): the java executable and the module/class paths, or an {@code error} message
     * when resolution failed. Reused by the non-debug Run path (which turns it into a {@code java} argv).
     */
    public record ResolvedLaunch(
            @Nullable String javaExec,
            List<String> modulePaths,
            List<String> classPaths,
            @Nullable String error,
            boolean enablePreview) {
        static ResolvedLaunch failed(String error) {
            return new ResolvedLaunch(null, List.of(), List.of(), error, false);
        }

        public boolean ok() {
            return error == null;
        }
    }

    /** Lets the controller present a chooser when several main classes are found (QuickOpen). */
    public interface MainClassPicker {
        /** {@code chosen} gets null when the user dismissed the chooser. */
        void pick(List<MainClassOption> options, Consumer<@Nullable MainClassOption> chosen);
    }

    /** UI sink (implemented by the controller / Debug panel); all calls arrive on the FX thread. */
    public interface Listener {
        void onState(State state);

        /** Execution suspended: the (already-fetched) call stack of {@code threadId}; top frame = where it stopped. */
        void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames);

        void onOutput(String text, String category);

        void onError(@Nullable String message);

        /**
         * What the adapter says about {@code file}'s breakpoints (see {@link DapModels.BreakpointStatus}):
         * with {@code whole}, its answer for all of them — a breakpoint it does not list has no answer yet;
         * otherwise a later change to the ones listed. Only arrives while a session is live.
         */
        default void onBreakpointStatus(Path file, List<DapModels.BreakpointStatus> statuses, boolean whole) {}

        /** The exception {@code threadId} is stopped on; follows the {@link #onStopped} of an exception stop. */
        default void onExceptionInfo(int threadId, DapModels.ExceptionInfo info) {}

        /** A thread of the program started or ended: a Threads view lists them again. */
        default void onThreadsChanged() {}

        /** A message from the adapter for the user that is not program output — java-debug reports a
         *  breakpoint condition or logpoint message it could not evaluate this way. */
        default void onNotice(String message, boolean error) {}

        /**
         * Whether lines can be typed to the debugged program's standard input changed: true once a program
         * Editora started itself is running (see {@link #setProgramConsole}), false when it has ended or its
         * input was closed. A session that ends says nothing — no session, no input.
         */
        default void onProgramInput(boolean available) {}

        /** The program Editora started for this session ended by itself with {@code code}; the session
         *  ends right after. Not reported for a program that was stopped. */
        default void onProgramExit(int code) {}

        /**
         * The connection to the debug adapter was lost while its program was still running (jdtls, which hosts
         * java-debug, crashed or was ended): the program has been ended and the session ends right after.
         */
        default void onAdapterLost() {}

        /** A short message for the status bar about a control that cannot act now (not an error). */
        default void onStatus(String message) {}

        /** The launched file did not compile (javac's report is already in the console): no program was started. */
        default void onCompileFailed() {}

        /**
         * The program has ended, and the session still waits for its output to stop arriving (the program console
         * off: java-debug's copy of a flood trails the exit by seconds). The session ends when the output does.
         */
        default void onOutputTrailing() {}

        /**
         * The session launched its program with {@code runtime} — the interpreter or {@code java} argv the
         * adapter starts it with, before the program's own arguments — in {@code where} (its folder, so its
         * host) with {@code env} on top of the user's environment. Not reported for an attach: the program
         * was already running, under whatever started it.
         */
        default void onDebuggeeRuntime(List<String> runtime, @Nullable Path where, Map<String, String> env) {}

        /**
         * The program started a subprocess that the adapter can debug in a session of its own (debugpy's
         * {@code startDebugging}): {@code configuration} is what to attach that session with
         * ({@link #startPythonChild}). Only for a launch that asked for its subprocesses.
         */
        default void onChildSession(Map<String, Object> configuration) {}
    }

    private final LspManager lsp;
    /** The settings this window's managers share ({@link #newSession}). */
    private final DapSettings shared;

    private Listener listener = noopListener();

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dap-connect");
        t.setDaemon(true);
        return t;
    });

    /** The window's project root for the next JavaScript launch (its package root and workspace folder). */
    private volatile @Nullable Path jsProjectRoot;
    /** The current session is vscode-js-debug's (read on adapter threads to clean up what it prints). */
    private volatile boolean jsSession;
    /**
     * The JavaScript session's program was started by Editora ({@code runInTerminal}): its output arrives from its
     * own pipes, so js-debug's copies of it (console.log through the inspector) are not shown a second time.
     */
    private volatile boolean jsClientConsole;
    /** js-debug's stderr for the starting JavaScript session, for the message when it dies while starting. */
    private volatile @Nullable AdapterStderr jsAdapterStderr;
    /** Condition errors js-debug already reported this session (it repeats each one per script it loads). */
    private final java.util.Set<String> reportedConditionErrors = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile @Nullable DapClient client;
    private volatile long sessionEpoch;
    private volatile boolean managerClosed;
    private volatile @Nullable Future<?> startupTask;
    private final Object sessionLock = new Object();
    private State state = State.INACTIVE;
    private int currentThreadId;
    /**
     * The threads known to be stopped, with each one's stop reason, oldest first (FX thread). java-debug
     * suspends and resumes <em>per thread</em>: two workers can sit on the same breakpoint, and resuming the
     * one on screen leaves the other suspended with no further event to say so.
     */
    private final java.util.LinkedHashMap<Integer, String> stoppedThreads = new java.util.LinkedHashMap<>();
    /**
     * java-debug suspended the whole program (Pause, or a stop with {@code allThreadsStopped} under "Suspend all
     * threads"), and steps since have not resumed it: Continue must resume every thread, not just the one shown
     * (FX thread).
     */
    private boolean allSuspended;
    /**
     * The threads resumed one by one (Threads view → Resume Thread) since the whole program was suspended: while
     * {@link #allSuspended}, every other thread is still suspended (FX thread).
     */
    private final java.util.Set<Integer> resumedSinceSuspendAll = new java.util.HashSet<>();
    /** Counts stops shown, so a late step acknowledgement can tell the step has already ended. */
    private int stopCount;
    /** A step was acknowledged and its stop has not arrived yet (FX thread). */
    private boolean stepInFlight;
    /** The session epoch of a step request the adapter has not answered yet, else -1 (FX thread). */
    private long stepPendingEpoch = -1;

    private @Nullable Path debugFile;
    /** Temporary .java copy used to compile an extensionless shebang, scoped to its launch epoch. */
    private volatile @Nullable SourceAlias sourceAlias;

    private volatile @Nullable Path compilationDirectory;
    /** Removes {@link #compilationDirectory} on its target ({@code null}: a folder of this machine). */
    private volatile @Nullable Runnable compilationRemoval;

    private record SourceAlias(long epoch, Path original, Path compiled) {}

    /** The program of the current session when Editora started it, else null (FX thread). */
    private @Nullable Debuggee debuggee;
    /** How long a program may take to exit once the adapter has reported the session over, before it is killed. */
    private static final long DEBUGGEE_EXIT_GRACE_MILLIS = 3_000;

    /** The adapter language of the current (or last) session: {@code java}, {@code python} or {@code javascript}. */
    private String sessionLanguage = "java";

    private @Nullable Runnable restartAction;

    public DapManager(LspManager lsp) {
        this(lsp, new DapSettings());
    }

    private DapManager(LspManager lsp, DapSettings shared) {
        this.lsp = lsp;
        this.shared = shared;
    }

    /**
     * A manager for another session of the same window, beside this one: it shares this one's settings, adapter
     * locations, exception choices and breakpoints, and starts with no session, no listener and no launch
     * settings of its own (program arguments, environment and the like are set for each launch).
     */
    public DapManager newSession() {
        return new DapManager(lsp, shared);
    }

    /** Whether {@code other} is a manager of the same window ({@link #newSession}), sharing these settings. */
    public boolean sharesSettingsWith(DapManager other) {
        return other.shared == shared;
    }

    /**
     * Wraps every reply and event this manager hands the FX thread; the window's debug sessions run each one as the
     * session of the manager it came from. Identity by default.
     */
    private java.util.function.UnaryOperator<Runnable> callbackWrapper = java.util.function.UnaryOperator.identity();

    /** Sets {@link #callbackWrapper} ({@code null}: none). */
    public void setCallbackWrapper(java.util.function.@Nullable UnaryOperator<Runnable> wrapper) {
        this.callbackWrapper = wrapper == null ? java.util.function.UnaryOperator.identity() : wrapper;
    }

    /** {@link LspManager#executeCommand}, its answer handed back through {@link #callbackWrapper}. */
    private void executeCommand(
            @Nullable Path file,
            String command,
            List<Object> args,
            java.util.function.BiConsumer<Object, Throwable> cb) {
        lsp.executeCommand(
                file,
                command,
                args,
                (r, e) -> callbackWrapper.apply(() -> cb.accept(r, e)).run());
    }

    /** As above, waiting up to {@code timeout}. */
    private void executeCommand(
            @Nullable Path file,
            String command,
            List<Object> args,
            java.time.Duration timeout,
            java.util.function.BiConsumer<Object, Throwable> cb) {
        lsp.executeCommand(
                file,
                command,
                args,
                timeout,
                (r, e) -> callbackWrapper.apply(() -> cb.accept(r, e)).run());
    }

    /** {@link LspManager#buildJavaWorkspace}, its answer handed back through {@link #callbackWrapper}. */
    private void buildJavaWorkspace(Path file, boolean full, Consumer<LspManager.BuildOutcome> cb) {
        lsp.buildJavaWorkspace(
                file,
                full,
                outcome -> callbackWrapper.apply(() -> cb.accept(outcome)).run());
    }

    /** Runs {@code r} later on the FX thread, through {@link #callbackWrapper}. */
    private void fx(Runnable r) {
        Runnable wrapped = callbackWrapper.apply(r);
        Platform.runLater(wrapped);
    }

    public void setListener(Listener listener) {
        this.listener = listener == null ? noopListener() : listener;
    }

    /** Supplies all open buffers' breakpoints (sent to the adapter on the {@code initialized} event). */
    public void setBreakpointsSupplier(Supplier<List<DapModels.FileBreakpoints>> supplier) {
        shared.breakpointsSupplier = supplier == null ? List::of : supplier;
    }

    /**
     * Chooses {@code language}'s exception filters ({@code null}: back to the adapter's defaults). A live session
     * of that language gets them at once; the next one starts with them.
     */
    public void setExceptionFilters(String language, List<String> filters) {
        if (filters == null) {
            shared.exceptionChoices.remove(language);
        } else {
            shared.exceptionChoices.put(language, List.copyOf(filters));
        }
        if (client != null && language.equals(sessionLanguage) && !shared.exceptionsMuted) {
            client.setExceptionFilters(filters == null ? null : List.copyOf(filters));
        }
    }

    /** The current session's exception filters ({@code null}: its adapter's defaults) — kept for callers that do
     *  not name a language. */
    public void setExceptionFilters(List<String> filters) {
        setExceptionFilters(sessionLanguage, filters);
    }

    /** The filters chosen for {@code language}, or null when the adapter's defaults apply; none while muted. */
    private @Nullable List<String> filtersFor(String language) {
        sessionLanguage = language;
        jsSession = "javascript".equals(language);
        reportedConditionErrors.clear();
        if (shared.exceptionsMuted) {
            return List.of();
        }
        List<String> chosen = shared.exceptionChoices.get(language);
        DebugLanguage l = DebugLanguages.byId(language);
        if (chosen == null && l != null && l.kind() == DapServerRegistry.Kind.EXTERNAL) {
            // An external adapter may name its filters only once it knows the program's language — after
            // initialize (CodeLLDB's rust_panic): its profile's defaults are sent, as the adapter's own would be.
            return ExternalAdapterFilters.defaults(l);
        }
        return chosen;
    }

    /**
     * Mutes or restores the exception stops: a live session is told at once (no filters while muted, the chosen
     * ones — or the adapter's defaults — after); the next session starts that way. The choices themselves stay.
     */
    public void setExceptionsMuted(boolean muted) {
        // No early return when the shared flag already says so: each of a window's managers tells its own session.
        shared.exceptionsMuted = muted;
        if (client != null) {
            List<String> chosen = shared.exceptionChoices.get(sessionLanguage);
            client.setExceptionFilters(muted ? List.of() : chosen);
        }
    }

    /** The language of the live (or last) session. */
    public String sessionLanguage() {
        return sessionLanguage;
    }

    /**
     * The exception filters {@code language}'s adapter offers: the live session's own list when it is that
     * language's, else what that adapter is known to offer ({@link ExceptionFilterCatalog}).
     */
    public List<DapModels.ExceptionFilterOption> exceptionFilterOptions(String language) {
        DapClient c = client;
        if (c != null && language.equals(sessionLanguage)) {
            List<DapModels.ExceptionFilterOption> live = c.exceptionFilterOptions();
            if (!live.isEmpty()) {
                return live;
            }
        }
        return ExceptionFilterCatalog.known(language);
    }

    /** The exception filters chosen per language (a language not listed uses its adapter's defaults). */
    public Map<String, List<String>> exceptionChoices() {
        return new java.util.LinkedHashMap<>(shared.exceptionChoices);
    }

    /** The exception filters in force for {@code language}: the chosen ones, else the adapter's defaults. */
    public List<String> exceptionFiltersFor(String language) {
        List<String> chosen = shared.exceptionChoices.get(language);
        return chosen != null ? chosen : DapClient.defaultExceptionFilters(exceptionFilterOptions(language));
    }

    /** Takes effect at the next launch; see {@link ProgramTerminal}. */
    public void setProgramTerminal(@Nullable ProgramTerminal programTerminal) {
        shared.programTerminal = programTerminal;
    }

    /**
     * Whether the next Java <em>launch</em> runs the program as Editora's own child process, so that it has
     * a standard input ({@link #sendProgramInput}): the launch says {@code console: integratedTerminal} and
     * the adapter hands the command line back ({@code runInTerminal}) instead of starting it inside jdtls,
     * where nothing can be typed to it. Editora then owns the process: it pumps its output to
     * {@link Listener#onOutput}, kills it whenever the session ends, and ends the session when it exits. Off
     * (the default here; the window turns it on from the setting), the adapter starts the program as before.
     * An attach, and the Python and JavaScript adapters, are not affected either way.
     */
    public void setProgramConsole(boolean programConsole) {
        shared.programConsole = programConsole;
    }

    /** Whether a Python launch debugs its subprocesses too, each in a session of its own; from the next launch on. */
    public void setPythonSubprocesses(boolean on) {
        shared.pythonSubprocesses = on;
    }

    /** The Debugging setting's Node.js for JavaScript sessions ("" = automatic). */
    public void setNodePath(String nodePath) {
        shared.nodePath = nodePath == null ? "" : nodePath.strip();
    }

    /** Node.js arguments before the program for the next JavaScript launch ({@code --test} for a test file). */
    private volatile List<String> jsNodeArgs = List.of();

    /** Sets {@link #jsNodeArgs}; sticky across {@link #restart()}, like the program arguments. */
    public void setJsNodeArgs(List<String> args) {
        this.jsNodeArgs = args == null ? List.of() : List.copyOf(args);
    }

    /** The project root a JavaScript launch resolves its Node.js, package root and workspace folder from. */
    public void setJsProjectRoot(@Nullable Path projectRoot) {
        this.jsProjectRoot = projectRoot;
    }

    /** Whether a line typed now would reach the debugged program's standard input. */
    public boolean programInputAvailable() {
        return debuggee != null && debuggee.acceptsInput();
    }

    /** Sends {@code line} (a line terminator is added) to the debugged program's standard input; false when
     *  there is no such program to send it to. */
    public boolean sendProgramInput(String line) {
        Debuggee program = debuggee;
        if (program == null || !program.acceptsInput() || line == null) {
            return false;
        }
        program.sendInput(line);
        return true;
    }

    /** Ends the debugged program's standard input (end of file); false when there is none to end. */
    public boolean closeProgramInput() {
        Debuggee program = debuggee;
        if (program == null || !program.acceptsInput()) {
            return false;
        }
        program.closeInput();
        listener.onProgramInput(false);
        return true;
    }

    /** Java-only configure (kept for back-compat); delegates with python/js disabled. */
    public void configure(boolean enabled, String pluginPath) {
        configure(enabled, pluginPath, false, "", false, "");
    }

    /**
     * Stores the master enable flag + every language's enable/command and re-resolves the java-debug bundle
     * jar(s) — that one synchronously, because it must reach the LSP layer before any jdtls session starts.
     * The python/js adapter <em>locations</em> and their availability are both resolved later, off-thread, by
     * {@link #detectPython}/{@link #detectJs}.
     */
    public void configure(
            boolean enabled,
            String javaPluginPath,
            boolean pythonEnabled,
            String pythonCommand,
            boolean jsEnabled,
            String jsPath) {
        shared.enabled = enabled;
        shared.pluginPath = javaPluginPath == null ? "" : javaPluginPath;
        shared.bundlePaths = enabled ? locate() : List.of();
        shared.pythonEnabled = pythonEnabled;
        shared.pythonCommand = pythonCommand == null ? "" : pythonCommand;
        shared.jsEnabled = jsEnabled;
        shared.jsPath = jsPath == null ? "" : jsPath;
        // The python/js adapter locations are deliberately NOT resolved here. Locating them walks the VS
        // Code / mason / plugin directories, and the only caller (DebugCoordinator.applySupport) follows
        // this immediately with detectPython/detectJs, which relocate them off-thread and assign these very
        // fields. Resolving here as well duplicated that walk on the FX thread during startup — measured at
        // ~30-50 ms on the path to first paint, for values not read until a debug session actually launches.
        // Only the clearing has to happen synchronously: with the feature off, detect* never runs to null it.
        if (!shared.enabled || !shared.pythonEnabled) {
            shared.debugpyDir = null;
            shared.pythonAvailable = false;
        }
        if (!shared.enabled || !shared.jsEnabled) {
            shared.jsServer = null;
            shared.jsAvailable = false;
        }
    }

    /** The located java-debug plugin jar(s) — for {@code LspManager.setDebugBundles}. Empty when not found. */
    public List<String> bundlePaths() {
        return shared.bundlePaths;
    }

    /** Records whether the running Java server itself provides the debug commands (see the field doc). */
    public void setServerProvidesJavaDebug(boolean provided) {
        shared.serverProvidesJavaDebug = provided;
    }

    /**
     * Whether the java-debug plugin is installed — a jar is found, or the running jdtls provides it — whether
     * or not debugging is switched on. What Install and Settings ask (review A6, A7): asked through
     * {@link #isAdapterAvailable}, every Install re-downloaded a plugin that was there all along while
     * debugging was off, and Settings said "not found" for a jdtls that ships it.
     */
    public boolean isJavaDebugInstalled() {
        return shared.serverProvidesJavaDebug || !locate().isEmpty();
    }

    /** Whether Java debugging can run: we located a bundle to inject, <b>or</b> jdtls already provides it. */
    public boolean isAdapterAvailable() {
        return javaDebugUsable();
    }

    private boolean javaDebugUsable() {
        return shared.enabled && (!shared.bundlePaths.isEmpty() || shared.serverProvidesJavaDebug);
    }

    /** Whether a usable debug adapter is available for {@code language} (after detection). */
    public boolean isLanguageAvailable(String language) {
        DebugLanguage l = DebugLanguages.byId(language);
        if (l == null) {
            return false;
        }
        return switch (l.availability()) {
            case JAVA_DEBUG_PLUGIN -> javaDebugUsable();
            case LOCATED -> located(l);
            // Editora's own adapter, or Metals': nothing to install, only the debugging switch (the tool, or Metals
            // running, is checked when the session starts).
            case BUILT_IN -> shared.enabled;
        };
    }

    /** Whether detection found {@code l}'s adapter (debugpy, vscode-js-debug, an external adapter's command). */
    private boolean located(DebugLanguage l) {
        return switch (l.id()) {
            case "python" -> shared.pythonAvailable;
            case "javascript" -> shared.jsAvailable;
            default -> shared.enabled && shared.externalLocated.containsKey(l.id());
        };
    }

    /** Re-runs the (filesystem) locate and delivers whether the plugin jar was found, on the FX thread. */
    public void detect(Consumer<Boolean> onResult) {
        io.submit(() -> {
            List<String> found = locate();
            fx(() -> {
                shared.bundlePaths = shared.enabled ? found : List.of();
                onResult.accept(!found.isEmpty());
            });
        });
    }

    /**
     * Off-thread: relocates debugpy and probes {@code <python> -c "import debugpy"} (with the located dir
     * on {@code PYTHONPATH}); delivers availability + caches it, on the FX thread.
     */
    public void detectPython(Consumer<Boolean> onResult) {
        detectPython(onResult, false);
    }

    /**
     * As {@link #detectPython}, but delivering whether debugpy is <em>installed</em> (importable by the
     * configured interpreter) whatever the switches say — what Settings → Debugging shows. With debugging or
     * Python debugging turned off it said "not found" about a debugpy that was there.
     */
    public void detectPythonInstalled(Consumer<Boolean> onResult) {
        detectPython(onResult, true);
    }

    private void detectPython(Consumer<Boolean> onResult, boolean installedOnly) {
        io.submit(() -> {
            Path dir = DebugAdapterLocator.locateDebugpy("", home()).orElse(null);
            boolean on = shared.enabled && shared.pythonEnabled;
            boolean installed = (on || installedOnly) && probeDebugpy(dir);
            fx(() -> {
                shared.debugpyDir = dir;
                shared.pythonAvailable = on && installed;
                onResult.accept(installedOnly ? installed : on && installed);
            });
        });
    }

    /**
     * Off-thread: relocates the vscode-js-debug server and probes {@code node --version}; delivers
     * availability (server found AND node runnable) + caches it, on the FX thread.
     */
    public void detectJs(Consumer<Boolean> onResult) {
        io.submit(() -> {
            Path server = DebugAdapterLocator.locateJsDebugServer(shared.jsPath, home())
                    .orElse(null);
            boolean ok = shared.enabled && shared.jsEnabled && server != null && probeNode(adapterNode());
            fx(() -> {
                shared.jsServer = server;
                shared.jsAvailable = ok;
                onResult.accept(ok);
            });
        });
    }

    private List<String> locate() {
        return DebugAdapterLocator.locate(shared.pluginPath, home())
                .map(p -> List.of(p.toString()))
                .orElse(List.of());
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home", ""));
    }

    /** Test seam: debugpy found in {@code dir} (a folder holding a {@code debugpy} package), detection done. */
    void setPythonAdapterForTest(Path dir) {
        shared.debugpyDir = dir;
        shared.pythonAvailable = true;
    }

    /** Runs {@code <python> -c "import debugpy"} (debugpyDir on PYTHONPATH); true on a clean exit. */
    private boolean probeDebugpy(@Nullable Path dir) {
        List<String> interp = DapServerRegistry.interpreterArgv("python", shared.pythonCommand);
        if (interp.isEmpty()) {
            return false;
        }
        List<String> argv = new ArrayList<>(interp);
        argv.add("-c");
        argv.add("import debugpy");
        return probe(argv, dir);
    }

    /**
     * What the Settings card shows about js-debug, whatever the enable flags say (review D13, D108): the server
     * (null when not found), its version (null when its bundle does not say), and the version of the Node.js that
     * runs it (null when none runs).
     */
    public record JsDebugInstall(
            @Nullable Path server,
            @Nullable String serverVersion,
            @Nullable String nodeVersion) {}

    /** Finds {@link JsDebugInstall} off the FX thread and delivers it on the FX thread. */
    public void detectJsInstalled(Consumer<JsDebugInstall> onResult) {
        String configured = shared.jsPath;
        io.submit(() -> {
            Path server =
                    DebugAdapterLocator.locateJsDebugServer(configured, home()).orElse(null);
            String serverVersion =
                    server == null ? null : com.editora.install.PinnedVersions.jsDebugServerVersion(server);
            String node = adapterNode();
            String nodeVersion = nodeVersion(node);
            String runner = adapterNodeFor(node, nodeVersion);
            String runnerVersion = runner == null || runner.equals(node) ? nodeVersion : nodeVersion(runner);
            fx(() -> onResult.accept(new JsDebugInstall(server, serverVersion, runnerVersion)));
        });
    }

    /** The Node.js the settings name (or {@code node} on PATH) — what runs js-debug when no file is involved. */
    private String adapterNode() {
        return com.editora.run.NodeRuntimes.resolveLocal(null, null, "", shared.nodePath)
                .executable();
    }

    /** Runs {@code <node> --version}; true on a clean exit. */
    private boolean probeNode(String node) {
        return probe(List.of(node, "--version"), null);
    }

    /** What {@code <node> --version} prints ({@code v24.18.1}), or null when it does not run. Off the FX thread. */
    static @Nullable String nodeVersion(String node) {
        try {
            ProcessRunner.Result r =
                    ProcessRunner.run(null, java.time.Duration.ofSeconds(5), List.of(node, "--version"));
            return r.exit() == 0 ? JsLaunch.version(r.out()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Launches {@code argv} (PATH-augmented; {@code pythonPathDir} prepended to PYTHONPATH when non-null),
     *  discards its output, and returns whether it exits 0 within 5s. */
    private static boolean probe(List<String> argv, @Nullable Path pythonPathDir) {
        return probe(argv, pythonPathDir, Map.of());
    }

    /** As {@link #probe(List, Path)}, with {@code env} (a command's own {@code env NAME=value} prefix) applied. */
    private static boolean probe(List<String> argv, @Nullable Path pythonPathDir, Map<String, String> env) {
        try {
            List<String> cmd = ProcessRunner.resolveExecutable(argv);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            ProcessRunner.applyStandardEnv(pb);
            pb.environment().putAll(env);
            if (pythonPathDir != null) {
                prependPythonPath(pb, pythonPathDir);
            }
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = BusyExecutable.start(pb);
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void prependPythonPath(ProcessBuilder pb, Path dir) {
        String existing = pb.environment().getOrDefault("PYTHONPATH", "");
        pb.environment().put("PYTHONPATH", existing.isEmpty() ? dir.toString() : dir + File.pathSeparator + existing);
    }

    public State state() {
        return state;
    }

    public boolean isActive() {
        return client != null;
    }

    /** The file the current (or last) session was started for; null before any session. */
    public @Nullable Path debugFile() {
        return debugFile;
    }

    public int currentThreadId() {
        return currentThreadId;
    }

    /**
     * Whether a Step Over/Into/Out is under way: requested, and its stop not yet reported. The session reads
     * RUNNING for most of that time, but to the user it is still the paused session they are stepping
     * through — not one to be replaced by a new launch.
     */
    public boolean isStepping() {
        return stepInFlight || (client != null && stepPendingEpoch == sessionEpoch);
    }

    // --- Start (launch / attach) ----------------------------------------------------------------

    /**
     * Launches a debug session for {@code file} in {@code language}: dispatches to the jdtls flow for
     * {@code java} (the {@code picker} resolves an ambiguous main class), or to the standalone-adapter
     * flow ({@link #startProgram}) for {@code python}/{@code javascript}. No-op (with an error) for an
     * unsupported language.
     */
    public void startLaunch(Path file, String language, MainClassPicker picker) {
        startLaunch(file, language, picker, "");
    }

    /** As above, with an explicit Java executable for Maven-project toolchain selection. */
    public void startLaunch(Path file, String language, MainClassPicker picker, String javaExecOverride) {
        startLaunch(file, language, picker, javaExecOverride, null);
    }

    /**
     * As above, with the Maven/Gradle project {@code file} belongs to ({@code null} for a loose file). A
     * project's main class runs in the project root, like Run and the gutter; a loose file runs in its own
     * folder and is compiled here when jdtls has no class file for it.
     */
    public void startLaunch(
            Path file, String language, MainClassPicker picker, String javaExecOverride, @Nullable Path projectRoot) {
        jvmCwd = null; // a file's own debug runs in its folder
        jvmJar = null;
        if ("java".equals(language)) {
            startLaunch(file, picker, javaExecOverride, projectRoot);
        } else if (DapServerRegistry.isDebuggable(language)) {
            startProgram(file, DapServerRegistry.adapterLanguage(language));
        } else {
            listener.onError(tr("debug.error.notSupported"));
        }
    }

    /** The folder a JVM-language program runs in; null for its file's folder. */
    private volatile @Nullable Path jvmCwd;

    /**
     * Debugs JVM-language script {@code file} ({@code groovy}, {@code clojure}) in {@code cwd} — a run
     * configuration's folder, or null for the one the tool runs it in —
     * with the arguments and environment set beforehand. Restart repeats it.
     */
    public void startJvmScript(Path file, String language, @Nullable Path cwd) {
        jvmCwd = cwd;
        jvmJar = null;
        startProgram(file, language);
        restartAction = () -> startJvmScript(file, language, cwd);
    }

    /** A compiled program's jar for the next JVM launch, run as {@code java -jar}; null runs the file's tool. */
    private volatile @Nullable Path jvmJar;

    /** The {@code java} that runs {@link #jvmJar}: the session JDK's. */
    private volatile String jvmJava = "java";

    /**
     * Debugs compiled Kotlin program {@code file} — its {@code jar}, built beforehand — as
     * {@code java [VM arguments] -jar <jar> args…} on {@code java}, in {@code cwd} (null: the file's folder), with
     * the arguments, VM arguments and environment set beforehand. The debugger's agent goes on that command line.
     * Restart repeats it with the same jar; the coordinator's own restart compiles an edited file again.
     */
    public void startJvmProgram(Path file, String language, @Nullable Path cwd, Path jar, String java) {
        jvmCwd = cwd;
        jvmJar = jar;
        jvmJava = java == null || java.isBlank() ? "java" : java;
        startProgram(file, language);
        restartAction = () -> startJvmProgram(file, language, cwd, jar, java);
    }

    /**
     * Attaches Editora's JVM adapter to a JVM listening at {@code host:port} — one a build tool started under a
     * suspended JDWP agent — for a program in a language java-debug cannot map ({@code language}), anchored at
     * {@code file}. Ending the session detaches; the JVM's owner stops it. Restart attaches again.
     */
    public void startJvmAttach(Path file, String language, String host, int port) {
        DapServerRegistry.DapServerSpec spec = DapServerRegistry.specFor(language);
        if (spec == null || spec.kind() != DapServerRegistry.Kind.JVM) {
            listener.onError(tr("debug.error.noAdapter"));
            return;
        }
        restartAction = () -> startJvmAttach(file, language, host, port);
        debugFile = file;
        long epoch = beginSession();
        setState(State.STARTING);
        List<DapModels.FileBreakpoints> bps = shared.breakpointsSupplier.get();
        List<String> filters = filtersFor(language);
        startupTask = io.submit(() -> {
            try {
                Map<String, Object> attach = new java.util.LinkedHashMap<>();
                attach.put("hostName", host == null || host.isBlank() ? "localhost" : host);
                attach.put("port", port);
                attach.put("language", language);
                Path root = file == null ? null : jvmSourceRoot(file);
                if (root != null) {
                    attach.put("cwd", root.toString());
                }
                if (file != null) {
                    attach.put("sourceFiles", List.of(file.toString()));
                }
                int adapterPort = com.editora.dap.jvm.JvmDebugServer.start(new com.editora.dap.jvm.JvmDebugAdapter());
                DapClient c = new DapClient(sessionHost(epoch));
                c.setPaths(DapPaths.LOCAL);
                c.setBreakpoints(bps);
                c.setExceptionFilters(filters);
                if (!publishClient(epoch, c)) {
                    c.dispose();
                    return;
                }
                c.connect(adapterPort, spec.adapterId()).whenComplete((caps, e) -> {
                    if (e != null) {
                        fail(epoch, tr("debug.error.connectFailed", msg(e)));
                        return;
                    }
                    if (!isCurrent(epoch)) {
                        c.dispose();
                        return;
                    }
                    c.attach(attach).whenComplete((v, ae) -> {
                        if (ae != null) {
                            fail(epoch, tr("debug.error.launchFailed", msg(ae)));
                        }
                    });
                    fx(() -> {
                        if (isCurrent(epoch, c)) {
                            setState(State.RUNNING);
                        }
                    });
                });
            } catch (Exception e) {
                fail(epoch, tr("debug.error.startFailed", msg(e)));
            }
        });
    }

    /** The folder an attached program's sources are looked up under: the file's build root, else its folder. */
    private static @Nullable Path jvmSourceRoot(Path file) {
        Path dir = file.toAbsolutePath().getParent();
        for (Path p = dir; p != null; p = p.getParent()) {
            for (String marker :
                    List.of("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle", "pom.xml")) {
                if (java.nio.file.Files.isRegularFile(p.resolve(marker))) {
                    return p;
                }
            }
        }
        return dir;
    }

    /** Program arguments for the next launch (shared with the Run feature's per-file args). */
    private volatile List<String> programArgs = List.of();

    /** JVM arguments for the next launch (from a saved run configuration; "" otherwise). */
    private volatile String vmArgs = "";

    /** Sets the debuggee's program arguments; call before {@link #startLaunch}. Sticky across
     *  {@link #restart()} (the restart re-runs the same launch). */
    public void setProgramArgs(List<String> args) {
        this.programArgs = args == null ? List.of() : List.copyOf(args);
    }

    /** Sets the debuggee's JVM arguments; call before {@link #startLaunchMainClass}. "" clears them. */
    public void setVmArgs(String vmArgs) {
        this.vmArgs = vmArgs == null ? "" : vmArgs;
        this.vmArgList = null;
    }

    /**
     * Sets the debuggee's JVM arguments as already split (a saved configuration's, split as Run splits them):
     * they are encoded for the adapter's own tokenizer at launch, by the OS of the machine it runs on.
     */
    public void setVmArgs(List<String> vmArgs) {
        this.vmArgList = vmArgs == null ? List.of() : List.copyOf(vmArgs);
        this.vmArgs = "";
    }

    /** {@link #setVmArgs(List)}'s arguments; null when the raw {@link #vmArgs} text applies. */
    private volatile @Nullable List<String> vmArgList;

    /** The JVM arguments of the next launch as java-debug's {@code vmArgs} string, for an adapter on {@code target}. */
    private String vmArgsFor(LaunchConfig.Target target) {
        List<String> list = vmArgList;
        return list == null ? vmArgs : LaunchConfig.javaArgs(list, target.windows());
    }

    /**
     * The {@code java} of a remote host's configured JDK for the next launch ({@code ""}: what jdtls resolves
     * there) — a saved configuration's, as Run uses it (review D9). Set before {@link #startLaunchMainClass}.
     */
    private volatile String hostJavaExec = "";

    /** See {@link #hostJavaExec}; {@code null} or blank clears it. */
    public void setHostJavaExec(String java) {
        this.hostJavaExec = java == null ? "" : java.strip();
    }

    /**
     * The {@code java} a project launch for {@code file} runs: this machine's chosen JDK (an override is a JDK on
     * this machine, never used for a remote file), else for a remote file the host's configured one, else the
     * one jdtls resolved.
     */
    private @Nullable String javaExecFor(Path file, String javaExecOverride, @Nullable String resolved) {
        if (!com.editora.process.ExecTargets.runsHere(file)) {
            return hostJavaExec.isBlank() ? resolved : hostJavaExec;
        }
        return javaExecOverride == null || javaExecOverride.isBlank() ? resolved : javaExecOverride;
    }

    /** The machine the adapter of a session for {@code file} runs on (review D8). */
    static LaunchConfig.Target targetFor(Path file) {
        return file == null || com.editora.process.ExecTargets.runsHere(file)
                ? LaunchConfig.Target.LOCAL
                : LaunchConfig.Target.REMOTE_POSIX;
    }

    /** The output category of Editora's own line saying what a launch runs (drawn muted by the Debug panel). */
    public static final String COMMAND_CATEGORY = "editora.command";

    /** The project a Python launch belongs to (null: none) — its venv, and its root on PYTHONPATH. */
    private volatile @Nullable Path pythonProjectRoot;

    /** The interpreter picked for that project ("" when none was), relative paths taken from its root. */
    private volatile String pythonProjectChoice = "";

    /**
     * Sets the project the next Python launch belongs to and the interpreter picked for it; call before
     * {@link #startLaunch}. The program's interpreter is then resolved off the FX thread
     * ({@link com.editora.run.PythonInterpreters}), and the project root (with {@code src/}) goes first on
     * its {@code PYTHONPATH}.
     */
    public void setPythonProject(Path projectRoot, String projectChoice) {
        this.pythonProjectRoot = projectRoot;
        this.pythonProjectChoice = projectChoice == null ? "" : projectChoice;
        this.pythonRequest = null;
        this.pythonHost = null;
    }

    /** Run's plan request for the next Python launch (its root, choice, setting, shebang), or null. */
    private volatile com.editora.run.PythonRunPlan.@Nullable Request pythonRequest;

    /** The machine the next Python program runs on, as Run plans it, or null (decided from the file). */
    private volatile com.editora.run.PythonRunPlan.@Nullable Host pythonHost;

    /**
     * Plans the next Python launch the way Run plans one: {@code request}'s project root, picked interpreter,
     * interpreter setting (a host's {@code python} tool elsewhere) and first line, on {@code host}. Only the
     * interpreter and the environment are taken from the plan; the target and its arguments are the session's.
     */
    public void setPythonPlan(com.editora.run.PythonRunPlan.Request request, com.editora.run.PythonRunPlan.Host host) {
        this.pythonProjectRoot = request == null ? null : request.projectRoot();
        this.pythonProjectChoice = request == null ? "" : request.projectChoice();
        this.pythonRequest = request;
        this.pythonHost = host;
    }

    /** Environment variables for the next launch (from a saved run configuration; empty otherwise). */
    private volatile java.util.Map<String, String> env = java.util.Map.of();

    /** Sets the debuggee's environment variables; call before {@link #startLaunchMainClass}. Empty clears them. */
    public void setEnv(java.util.Map<String, String> env) {
        this.env = env == null ? java.util.Map.of() : java.util.Map.copyOf(env);
    }

    /**
     * Launches a Java debug session for {@code file}: resolves the main class (asking {@code picker} if
     * several are found), its classpath + java executable, starts the adapter, and connects. No-op (with an
     * error to the listener) when debugging is unavailable.
     */
    public void startLaunch(Path file, MainClassPicker picker) {
        startLaunch(file, picker, "");
    }

    /** Java launch with an optional selected-JDK executable overriding jdtls's resolved executable. */
    public void startLaunch(Path file, MainClassPicker picker, String javaExecOverride) {
        startLaunch(file, picker, javaExecOverride, null);
    }

    /** Java launch for a file of the build project at {@code projectRoot} ({@code null}: a loose file). */
    public void startLaunch(Path file, MainClassPicker picker, String javaExecOverride, @Nullable Path projectRoot) {
        startLaunch(file, picker, javaExecOverride, projectRoot, null);
    }

    /**
     * As above, with the folder a loose file runs in ({@code looseCwd}, null: its own folder) — the project
     * root of a plain-folder project window, where a program reads its files as it would from a terminal there.
     */
    public void startLaunch(
            Path file,
            MainClassPicker picker,
            String javaExecOverride,
            @Nullable Path projectRoot,
            @Nullable Path looseCwd) {
        if (!ready(file)) {
            return;
        }
        restartAction = () -> startLaunch(file, picker, javaExecOverride, projectRoot, looseCwd);
        debugFile = file;
        long epoch = beginSession();
        setState(State.STARTING);
        Runnable resolve =
                () -> resolveMainClassAndLaunch(file, picker, javaExecOverride, projectRoot, looseCwd, epoch);
        if (projectRoot == null) {
            correctSourceRoot(file, epoch, resolve);
        } else {
            resolve.run();
        }
    }

    private void resolveMainClassAndLaunch(
            Path file,
            MainClassPicker picker,
            String javaExecOverride,
            @Nullable Path projectRoot,
            @Nullable Path looseCwd,
            long epoch) {
        executeCommand(file, "vscode.java.resolveMainClass", List.of(), (res, err) -> {
            if (!isCurrent(epoch)) {
                return;
            }
            if (err != null) {
                fail(epoch, tr("debug.error.resolveMainClassFailed", msg(err)));
                return;
            }
            List<MainClassOption> options = JdtlsReplies.parseMainClasses(res);
            DapPaths paths = pathsFor(file);
            // A project's main class runs in the project root — what Run, the gutter and a saved
            // configuration use — and a loose file in the window's project folder, else its own folder.
            Path dir = projectRoot != null ? projectRoot : looseCwd != null ? looseCwd : file.getParent();
            String cwd = dir == null ? null : paths.toAdapter(dir);
            String own = ownMainClass(file);
            if (options.isEmpty()) {
                // jdtls enumerated no project main class (a standalone file, or the project is still
                // importing). Derive the fully-qualified class name from the file (package + type name)
                // and resolve its classpath — the adapter needs a real class name + classPaths, not a path.
                String fqn = own != null ? own : JavaCompilation.mainClassFromFile(file);
                if (fqn == null) {
                    fail(epoch, tr("debug.error.noMainClass"));
                    return;
                }
                compileAndLaunch(file, fqn, javaExecOverride, epoch, null, dir);
                return;
            }
            MainClassOption match = options.stream()
                    .filter(o -> JavaLaunchSupport.sameFile(o.filePath(), file, paths))
                    .findFirst()
                    .orElse(null);
            if (match == null && own != null) {
                // The file declares a main that jdtls does not list: it is outside the project's source
                // folders (a script beside the project), or jdtls has not seen it yet. Debug the file — never
                // the project's other main class, which is what used to run instead.
                compileAndLaunch(file, own, javaExecOverride, epoch, null, dir);
                return;
            }
            if (match != null) {
                launchOption(file, match, cwd, javaExecOverride, projectRoot, epoch);
            } else if (options.size() == 1) {
                // No main here: the project's only one is what Debug means, as in VS Code — said so, because
                // the program that runs is not the file in front of the user.
                MainClassOption only = options.get(0);
                notice(
                        epoch,
                        com.editora.i18n.Messages.tr(
                                "debug.console.noMainInFile", file.getFileName(), String.valueOf(only.className())));
                launchOption(file, only, cwd, javaExecOverride, projectRoot, epoch);
            } else {
                picker.pick(options, chosen -> {
                    if (chosen != null) {
                        launchOption(file, chosen, cwd, javaExecOverride, projectRoot, epoch);
                    } else {
                        if (isCurrent(epoch)) {
                            setState(State.INACTIVE);
                        }
                    }
                });
            }
        });
    }

    /**
     * Launches {@code opt}. Outside a build project the classes come from jdtls's "invisible project" only when
     * the build before this launch brought them up to date; otherwise the file is compiled here, from its own
     * source ({@link LooseJavaLaunch#classesFor}): jdtls builds that output once, at import, and an old class
     * file there ran old code after every edit (review 2, A101).
     */
    private void launchOption(
            Path file,
            MainClassOption opt,
            @Nullable String cwd,
            String javaExecOverride,
            @Nullable Path projectRoot,
            long epoch) {
        if (projectRoot != null) {
            resolveAndLaunch(file, opt, cwd, javaExecOverride, epoch);
            return;
        }
        Path source = optionSource(file, opt);
        Path dir = cwd == null ? null : pathsFor(file).fromAdapter(cwd);
        Runnable compile = () -> compileAndLaunch(
                source,
                JavaLaunchSupport.qualified(opt.className(), JavaLaunchSupport.declaredPackage(source)),
                javaExecOverride,
                epoch,
                null,
                dir,
                file);
        boolean built = takeLooseBuild();
        if (!built) {
            compile.run(); // no build ran for this launch: whatever jdtls's output holds may be from any earlier one
            return;
        }
        resolveAndLaunch(file, opt, cwd, javaExecOverride, epoch, compile);
    }

    /**
     * The epoch of the session that runs classes javac compiled (a compact source file, or a loose file or plain
     * folder jdtls could not build); -1 when none. A replace there that jdtls can't build is not the user's error.
     */
    private volatile long javacEpoch = -1;

    /** Whether jdtls built a file without a build project for the launch now starting; set by the caller. */
    private volatile boolean looseBuilt;

    /**
     * Says that jdtls's build of the next launch's file (a loose file or a plain-folder project, which have no
     * build tool) has just succeeded, so its output may be launched; read once by that launch. Without it the
     * launch compiles the file itself.
     */
    public void setLooseClassesBuilt(boolean built) {
        this.looseBuilt = built;
    }

    private boolean takeLooseBuild() {
        boolean built = looseBuilt;
        looseBuilt = false;
        return built;
    }

    /** The source file of {@code opt} as Editora reaches it ({@code file} when jdtls named none). */
    private static Path optionSource(Path file, MainClassOption opt) {
        if (opt.filePath() == null || opt.filePath().isBlank()) {
            return file;
        }
        try {
            Path named = pathsFor(file).fromAdapter(opt.filePath());
            return named == null ? file : named;
        } catch (RuntimeException e) {
            return file;
        }
    }

    /** The fully-qualified name of the first {@code main} {@code file} declares itself, or null (bounded read). */
    static @Nullable String ownMainClass(Path file) {
        try {
            if (file == null || Files.size(file) > 2_000_000) {
                return null;
            }
            List<com.editora.run.MainMethodScanner.MainMethod> mains =
                    com.editora.run.MainMethodScanner.scan(Files.readString(file));
            return mains.isEmpty() ? null : mains.get(0).fqn();
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** A line of Editora's own in the Debug console for the session of {@code epoch} (FX thread). */
    private void notice(long epoch, String text) {
        if (isCurrent(epoch)) {
            listener.onOutput(text + "\n", "console");
        }
    }

    /** {@link JavaSourceRootFix} before a launch from a folder with no build file, then {@code then}. */
    private void correctSourceRoot(Path file, long epoch, Runnable then) {
        JavaSourceRootFix.correct(
                lsp, file, JavaLaunchSupport.declaredPackage(file), () -> isCurrent(epoch), fixed -> then.run());
    }

    /** Debug a compact {@code .java} file as the implicit class named after the file. */
    public void startCompactSource(Path file, String javaExecOverride) {
        if (file == null || !file.getFileName().toString().endsWith(".java")) {
            throw new IllegalArgumentException("Compact source debugging needs a .java file");
        }
        if (!ready(file)) {
            return;
        }
        restartAction = () -> startCompactSource(file, javaExecOverride);
        debugFile = file;
        long epoch = beginSession();
        setState(State.STARTING);
        compileAndLaunch(file, JavaCompilation.compactMainClassFromFile(file), javaExecOverride, epoch, null);
    }

    /** Debug an extensionless Java script via a line-preserving temporary {@code .java} copy. */
    public void startCompactShebang(Path file, int sourceVersion, String javaExecOverride) {
        if (sourceVersion < 25) {
            throw new IllegalArgumentException("Compact source debugging requires --source 25 or newer");
        }
        if (!ready(file)) {
            return;
        }
        restartAction = () -> startCompactShebang(file, sourceVersion, javaExecOverride);
        debugFile = file;
        long epoch = beginSession();
        setState(State.STARTING);
        compileAndLaunch(file, file.getFileName().toString(), javaExecOverride, epoch, sourceVersion);
    }

    /** Attaches to a running JVM at {@code host:port} (the file provides the jdtls session for the adapter). */
    public void startAttach(@Nullable Path file, String host, int port) {
        if (!ready(file) || file == null) { // ready() reports a missing file
            return;
        }
        restartAction = () -> startAttach(file, host, port);
        debugFile = file;
        long epoch = beginSession();
        setState(State.STARTING);
        // java-debug compiles conditions, logpoints and evaluated expressions against a JDT project and an
        // attach names no main class to infer one from: without the name every evaluation fails, and a
        // condition that cannot be evaluated counts as a hit.
        projectNameOf(file, project -> {
            if (isCurrent(epoch)) {
                startDebugSessionAndConnect(file, LaunchConfig.attach(host, port, project), true, epoch);
            }
        });
    }

    /**
     * Names the jdtls project {@code file} belongs to (null when jdtls cannot say), on the FX thread. Asks
     * for the file's main method first — the only form that covers a compact source, which declares no type
     * — then for the element at its first type declaration, which covers a class with no {@code main} (a
     * test class, the anchor of Debug Test).
     */
    private void projectNameOf(Path file, Consumer<String> cb) {
        String uri = lsp.serverUri(file);
        executeCommand(file, "vscode.java.resolveMainMethod", List.of(uri), (res, err) -> {
            String name = err == null ? JavaLaunchSupport.projectName(res) : null;
            int[] at = name == null ? JavaLaunchSupport.typeNamePosition(file) : null;
            if (at == null) {
                cb.accept(name);
                return;
            }
            executeCommand(
                    file,
                    "vscode.java.resolveElementAtSelection",
                    List.of(uri, at[0], at[1]),
                    (el, e2) -> cb.accept(e2 == null ? JavaLaunchSupport.projectName(el) : null));
        });
    }

    private boolean ready(@Nullable Path file) {
        if (managerClosed) {
            return false;
        }
        boolean hostPlugin = file != null
                && !com.editora.process.ExecTargets.runsHere(file)
                && shared.enabled
                && com.editora.process.ExecTargets.remoteTool(file, "dap.java") != null;
        if (!javaDebugUsable() && !hostPlugin) {
            listener.onError(tr("debug.error.javaUnavailable"));
            return false;
        }
        if (file == null) {
            listener.onError(tr("debug.error.openJavaFile"));
            return false;
        }
        if (client != null) {
            stop(); // one session at a time
        }
        return true;
    }

    private void resolveAndLaunch(
            Path file, MainClassOption opt, @Nullable String cwd, String javaExecOverride, long epoch) {
        resolveAndLaunch(file, opt, cwd, javaExecOverride, epoch, null);
    }

    /**
     * Resolves {@code opt}'s classpath + java executable via jdtls, then launches the debug session with the
     * given working directory. {@code file} routes the jdtls {@code executeCommand}s (must be an open,
     * LSP-managed document in the same project); {@code cwd} is the debuggee's working directory (the project
     * root for a project main class, else the file's own folder). {@code ifNoClassFile}, when given, runs
     * instead of the launch if jdtls's classpath does not hold the main class's class file.
     */
    private void resolveAndLaunch(
            Path file,
            MainClassOption opt,
            @Nullable String cwd,
            String javaExecOverride,
            long epoch,
            @Nullable Runnable ifNoClassFile) {
        String proj = opt.projectName() == null ? "" : opt.projectName();
        resolveLaunch(file, opt, r -> {
            if (!isCurrent(epoch)) {
                return;
            }
            if (ifNoClassFile != null
                    && LooseJavaLaunch.classesFor(
                                    true,
                                    JavaLaunchSupport.classFilePresent(r.classPaths(), opt.mainClass())
                                            || JavaLaunchSupport.classFilePresent(r.modulePaths(), opt.mainClass()))
                            == LooseJavaLaunch.Classes.COMPILE_HERE) {
                ifNoClassFile.run();
                return;
            }
            if (!r.ok()) {
                fail(epoch, r.error());
                return;
            }
            warnAboutClasses(file, opt, r, epoch); // jdtls's output too: it is warned about, never trusted blindly
            startDebugSessionAndConnect(
                    file,
                    LaunchConfig.launch(
                            opt.mainClass(),
                            proj,
                            r.classPaths(),
                            r.modulePaths(),
                            javaExecFor(file, javaExecOverride, r.javaExec()),
                            cwd,
                            programArgs,
                            JavaLaunchSupport.withPreview(vmArgsFor(targetFor(file)), r.enablePreview()),
                            env,
                            false,
                            targetFor(file)),
                    false,
                    epoch);
        });
    }

    /**
     * The last word on a project launch's class files, whatever built them (the jdtls build before the launch,
     * a before-launch step — or nothing, when no build could run): a main class with no class file, or one
     * older than its source, is launched with a warning in the console that says to build the project first —
     * the ClassNotFoundException or the old code that follows then has its explanation (review A2). A remote
     * project's files are the host's and are not read from here.
     */
    private void warnAboutClasses(Path file, MainClassOption opt, ResolvedLaunch r, long epoch) {
        if (!com.editora.process.ExecTargets.runsHere(file)) {
            return;
        }
        List<String> paths = new ArrayList<>(r.classPaths());
        paths.addAll(r.modulePaths());
        String cls = opt.className();
        if (cls == null) {
            return; // classState has nothing to judge by either
        }
        switch (JavaLaunchSupport.classState(paths, opt.mainClass(), optionSource(file, opt))) {
            case MISSING -> notice(epoch, com.editora.i18n.Messages.tr("debug.console.classesMissing", cls));
            case STALE -> notice(epoch, com.editora.i18n.Messages.tr("debug.console.classesStale", cls));
            default -> {
                // current, or nothing to judge by
            }
        }
    }

    /**
     * Resolves {@code opt}'s classpath + java executable via jdtls (no debug session), delivering a
     * {@link ResolvedLaunch} (with {@link ResolvedLaunch#error()} set on failure) on the FX thread. Shared by
     * the debug launch and the non-debug Run path; {@code routingFile} must be an open, LSP-managed Java file
     * in the same project.
     */
    public void resolveLaunch(Path routingFile, MainClassOption opt, Consumer<ResolvedLaunch> cb) {
        // The chokepoint guard, so no future caller can reproduce the class of bug: jdtls builds an Eclipse
        // SearchPattern from the main class, and createPattern("") returns null, surfacing as an internal
        // NPE from its search engine instead of anything actionable. Callers check first and say something
        // useful; this makes the unchecked path fail cleanly rather than incomprehensibly.
        String mainClass = opt.mainClass();
        if (mainClass == null || mainClass.isBlank()) {
            cb.accept(ResolvedLaunch.failed(tr("debug.error.configNeedsMainClass")));
            return;
        }
        String proj = opt.projectName() == null ? "" : opt.projectName();
        executeCommand(routingFile, "vscode.java.resolveClasspath", List.of(mainClass, proj), (res, err) -> {
            if (err != null) {
                cb.accept(ResolvedLaunch.failed(tr("debug.error.classpathFailed", msg(err))));
                return;
            }
            List<String> modulepaths = new ArrayList<>();
            List<String> classpaths = new ArrayList<>();
            JdtlsReplies.parseClasspath(res, modulepaths, classpaths);
            if (classpaths.isEmpty() && modulepaths.isEmpty()) {
                // Log what the server ACTUALLY said. An empty classpath has two very different causes —
                // the project genuinely isn't importable, or we failed to read a perfectly good reply — and
                // the message below can only guess at the first. Without this the two are indistinguishable
                // from the outside, which cost a long debugging session: jdtls was returning
                // [[], ["…/target/classes"]] while the user was told the project hadn't imported.
                LOG.log(
                        Level.WARNING,
                        () -> "resolveClasspath returned no entries for " + mainClass + " (project='"
                                + proj + "'); raw result type="
                                + (res == null ? "null" : res.getClass().getName())
                                + " value=" + JdtlsReplies.abbreviate(String.valueOf(res)));
                cb.accept(ResolvedLaunch.failed(tr("debug.error.classpathEmpty", mainClass)));
                return;
            }
            executeCommand(
                    routingFile,
                    "vscode.java.resolveJavaExecutable",
                    List.of(mainClass, proj),
                    (jx, e2) -> executeCommand(
                            routingFile,
                            "vscode.java.checkProjectSettings",
                            List.of(JavaLaunchSupport.previewSettingsQuery(mainClass, proj)),
                            // A project compiled with --enable-preview needs the flag to load its classes;
                            // a failed check (an older java-debug) leaves the launch as it was.
                            (preview, e3) -> cb.accept(new ResolvedLaunch(
                                    JdtlsReplies.asString(jx),
                                    modulepaths,
                                    classpaths,
                                    null,
                                    e3 == null && JavaLaunchSupport.isTrue(preview)))));
        });
    }

    /**
     * Enumerates <b>every</b> main class in {@code routingFile}'s jdtls project (not just the file's own),
     * for a project-level "Run/Debug Main Class…" picker. {@code routingFile} must be an open, LSP-managed
     * Java document (jdtls's {@code executeCommand} routes by open-document URI). Delivers an empty list on
     * any error / when debugging isn't available. Callback runs on the FX thread.
     */
    public void resolveMainClasses(Path routingFile, Consumer<List<MainClassOption>> cb) {
        if (!javaDebugUsable() || routingFile == null) {
            cb.accept(List.of());
            return;
        }
        executeCommand(routingFile, "vscode.java.resolveMainClass", List.of(), (res, err) -> {
            if (err != null) {
                // The caller cannot tell this from "the project has none" — it reports the same thing either
                // way — so the log is the only place the difference can survive.
                LOG.log(Level.WARNING, err, () -> "vscode.java.resolveMainClass failed for " + routingFile);
                cb.accept(List.of());
                return;
            }
            cb.accept(JdtlsReplies.parseMainClasses(res));
        });
    }

    /**
     * Launches a Java debug session for an explicitly chosen {@code opt} (a project main class picked by the
     * user), with {@code cwd} as the working directory. Unlike {@link #startLaunch(Path, MainClassPicker)}
     * this is not tied to the active file: {@code routingFile} only routes the jdtls commands (any open,
     * LSP-managed Java file in the same project).
     */
    public void startLaunchMainClass(Path routingFile, MainClassOption opt, Path cwd) {
        startLaunchMainClass(routingFile, opt, cwd, "");
    }

    /** Main-class launch with an optional selected-JDK executable. */
    public void startLaunchMainClass(Path routingFile, MainClassOption opt, Path cwd, String javaExecOverride) {
        startLaunchMainClass(routingFile, opt, cwd, javaExecOverride, false);
    }

    /**
     * As above; {@code plainFolder} for a project with no build file, whose classes jdtls never compiles —
     * the chosen class is then compiled from its own source when its class file is missing (review A16).
     */
    public void startLaunchMainClass(
            Path routingFile, MainClassOption opt, Path cwd, String javaExecOverride, boolean plainFolder) {
        if (!ready(routingFile)) {
            return;
        }
        restartAction = () -> startLaunchMainClass(routingFile, opt, cwd, javaExecOverride, plainFolder);
        debugFile = routingFile;
        long epoch = beginSession();
        setState(State.STARTING);
        String adapterCwd = cwd == null ? null : pathsFor(routingFile).toAdapter(cwd);
        if (plainFolder) {
            correctSourceRoot(
                    routingFile,
                    epoch,
                    () -> launchOption(routingFile, opt, adapterCwd, javaExecOverride, null, epoch));
        } else {
            resolveAndLaunch(routingFile, opt, adapterCwd, javaExecOverride, epoch);
        }
    }

    /**
     * Standalone-file fallback: jdtls enumerated no project main class (a loose file with no build
     * project, where jdtls's invisible-project classpath resolution is unreliable). Compile the file
     * ourselves with {@code javac -g} (debug info) to a temp dir, then launch with that dir as the
     * classpath — self-contained, like the Run feature. jdtls is still used only to start the adapter.
     * Runs off the FX thread (javac is a subprocess).
     */
    private void compileAndLaunch(
            Path file, String fqn, String javaExecOverride, long epoch, @Nullable Integer sourceVersion) {
        compileAndLaunch(file, fqn, javaExecOverride, epoch, sourceVersion, null);
    }

    /**
     * As above, running the program in {@code workingDir} (null: the file's own folder). The file is compiled
     * with its package's source folder as javac's source path, so the other classes of that package compile
     * with it, and the class is launched by the package the file declares.
     */
    private void compileAndLaunch(
            Path file,
            String fqn,
            String javaExecOverride,
            long epoch,
            @Nullable Integer sourceVersion,
            @Nullable Path workingDir) {
        compileAndLaunch(file, fqn, javaExecOverride, epoch, sourceVersion, workingDir, file);
    }

    /**
     * As above, for a {@code file} that may not be open in the editor: {@code routing} — an open Java file of
     * the same jdtls project — carries the jdtls commands that start the adapter.
     */
    private void compileAndLaunch(
            Path file,
            @Nullable String fqn,
            String javaExecOverride,
            long epoch,
            @Nullable Integer sourceVersion,
            @Nullable Path workingDir,
            Path routing) {
        javacEpoch = epoch; // this session runs javac's classes, not jdtls's output (review 3 verify, A201)
        // A remote file is compiled on its host, with its javac, into a folder of the host's cache.
        boolean remote = !com.editora.process.ExecTargets.runsHere(file);
        DapPaths paths = pathsFor(file);
        Map<String, String> launchEnvironment = env;
        startupTask = io.submit(() -> {
            Path out = null;
            Runnable removal = null;
            boolean retained = false;
            try {
                Path compileFile = file;
                if (remote) {
                    JavaCompilation.HostCompilation host = JavaCompilation.hostCompilation(
                            file,
                            sourceVersion == null ? null : JavaCompilation.withoutShebang(Files.readString(file)));
                    out = host.directory();
                    removal = host.removal();
                    if (host.source() != null) {
                        compileFile = host.source();
                    }
                } else {
                    out = Files.createTempDirectory("editora-dap-");
                    if (sourceVersion != null) {
                        compileFile = out.resolve(file.getFileName() + ".java");
                        Files.writeString(compileFile, JavaCompilation.withoutShebang(Files.readString(file)));
                    }
                }
                String javaExec = remote
                        ? JavaCompilation.hostJava(file)
                        : javaExecOverride == null || javaExecOverride.isBlank()
                                ? firstOrNull(ProcessRunner.resolveExecutable(List.of("java")))
                                : javaExecOverride;
                // Route through ProcessRunner.run so the compile is bounded by a hard timeout (a hung javac
                // would otherwise pin the single-threaded `io` executor and block every later DAP start/detect)
                // and both pipes are drained concurrently (no fill-the-buffer deadlock).
                Path sourceRoot = sourceVersion != null
                        ? null
                        : JavaLaunchSupport.sourceRoot(file, JavaLaunchSupport.declaredPackage(file));
                ProcessRunner.Result r = remote
                        ? JavaCompilation.compileOnHost(
                                file, compileFile, out, javaExec, launchEnvironment, sourceVersion, sourceRoot)
                        : JavaCompilation.compileStandalone(
                                file, compileFile, out, javaExec, launchEnvironment, sourceVersion, sourceRoot);
                if (!r.ok()) {
                    // javac's own report — file, line, the caret under the mistake — belongs in the Debug
                    // console, where its "File.java:3:" lines open the file; the status line only says so.
                    failWithConsole(
                            epoch,
                            JavaLaunchSupport.compilerReport(r.out(), r.err(), compileFile, file),
                            com.editora.i18n.Messages.tr("status.debug.compileFailed", file.getFileName()),
                            true);
                    return;
                }
                synchronized (sessionLock) {
                    if (!isCurrent(epoch)) {
                        return;
                    }
                    compilationDirectory = out;
                    compilationRemoval = removal;
                    // A script is compiled from a copy, and a loose file's class may come back with no source
                    // path (jdtls cannot always place it): either way its frames are this file's.
                    sourceAlias = new SourceAlias(epoch, file, compileFile);
                    retained = true;
                }
                Path runIn = workingDir != null ? workingDir : file.getParent();
                String cwd = runIn == null ? null : paths.toAdapter(runIn);
                String classes = paths.toAdapter(out);
                // The classes come from a temp dir, so java-debug cannot infer the project it compiles
                // conditions, logpoints and evaluated expressions against: name the one jdtls keeps the
                // source in (its invisible project for a loose file).
                fx(() -> projectNameOf(
                        routing,
                        project -> startDebugSessionAndConnect(
                                routing,
                                LaunchConfig.launch(
                                        fqn,
                                        project,
                                        List.of(classes),
                                        List.of(),
                                        javaExec,
                                        cwd,
                                        programArgs,
                                        vmArgsFor(targetFor(file)),
                                        launchEnvironment,
                                        false,
                                        targetFor(file)),
                                false,
                                epoch)));
            } catch (Exception e) {
                fail(epoch, tr("debug.error.compileLaunchFailed", fqn, msg(e)));
            } finally {
                if (!retained && out != null) {
                    JavaCompilation.removeCompilation(out, removal);
                }
            }
        });
    }

    private @Nullable SourceAlias activeSourceAlias() {
        SourceAlias alias = sourceAlias;
        return alias != null && alias.epoch() == sessionEpoch ? alias : null;
    }

    /** Remove both the temporary source and compiled classes after the adapter releases them. */
    private void releaseCompilationDirectory() {
        Path directory;
        Runnable removal;
        synchronized (sessionLock) { // the compile thread retains a folder under this lock
            directory = compilationDirectory;
            removal = compilationRemoval;
            compilationDirectory = null;
            compilationRemoval = null;
            sourceAlias = null;
        }
        if (directory != null) {
            java.util.concurrent.CompletableFuture.runAsync(
                    () -> JavaCompilation.removeCompilation(directory, removal),
                    com.editora.process.BlockingTasks.named("dap-remove-compilation"));
        }
    }

    private List<DapModels.FileBreakpoints> adapterBreakpoints(List<DapModels.FileBreakpoints> breakpoints) {
        return breakpoints.stream().map(this::adapterBreakpoint).toList();
    }

    private DapModels.FileBreakpoints adapterBreakpoint(DapModels.FileBreakpoints breakpoint) {
        SourceAlias alias = activeSourceAlias();
        return alias != null && alias.original().equals(breakpoint.file())
                ? new DapModels.FileBreakpoints(alias.compiled(), breakpoint.breakpoints())
                : breakpoint;
    }

    private List<DapModels.StackFrameInfo> originalFrames(List<DapModels.StackFrameInfo> frames) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }
        // java-debug finds a class's source through jdtls, which knows a Java script by the .java stand-in
        // it is shown (LspManager.scriptForStandIn): the frame is the script's.
        frames = frames.stream()
                .map(frame -> {
                    Path script = lsp == null ? null : lsp.scriptForStandIn(frame.file());
                    return script == null
                            ? frame
                            : new DapModels.StackFrameInfo(
                                    frame.id(), frame.name(), script, frame.line(), frame.column());
                })
                .toList();
        SourceAlias alias = activeSourceAlias();
        if (alias == null) {
            return frames;
        }
        return frames.stream()
                .map(frame -> isAliasedFrame(alias, frame)
                        ? new DapModels.StackFrameInfo(
                                frame.id(), frame.name(), alias.original(), frame.line(), frame.column())
                        : frame)
                .toList();
    }

    private static boolean isAliasedFrame(SourceAlias alias, DapModels.StackFrameInfo frame) {
        if (alias.compiled().equals(frame.file())) {
            return true;
        }
        // java-debug can omit Source.path for a class compiled from a temporary source file. Its
        // frame name still identifies the implicit class; leave unrelated library frames untouched.
        String fileName = alias.original().getFileName().toString();
        String className =
                fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - ".java".length()) : fileName;
        return frame.file() == null
                && frame.name() != null
                && (frame.name().startsWith(className + ".") || frame.name().startsWith(className + "$"));
    }

    // Moved to JavaCompilation; kept here for the callers that name them on DapManager.

    /** See {@link JavaCompilation#compileStandalone(Path, Path, String, Map)}. */
    static ProcessRunner.Result compileStandalone(
            Path file, Path outputDir, String javaExecOverride, Map<String, String> environment) {
        return JavaCompilation.compileStandalone(file, outputDir, javaExecOverride, environment);
    }

    /** See {@link JavaCompilation#hostJava}. */
    static String hostJava(Path file) {
        return JavaCompilation.hostJava(file);
    }

    /** See {@link JavaCompilation#hostJavac}. */
    static String hostJavac(String java) {
        return JavaCompilation.hostJavac(java);
    }

    /** See {@link JavaCompilation#withoutShebang}. */
    static String withoutShebang(String source) {
        return JavaCompilation.withoutShebang(source);
    }

    /** See {@link JavaCompilation#removeCompilationDirectory}. */
    static void removeCompilationDirectory(Path directory) {
        JavaCompilation.removeCompilationDirectory(directory);
    }

    /** See {@link JavaCompilation#compactMainClassFromFile}. */
    static String compactMainClassFromFile(Path file) {
        return JavaCompilation.compactMainClassFromFile(file);
    }

    /** See {@link JavaCompilation#mainClassFromFile}. */
    static @Nullable String mainClassFromFile(Path file) {
        return JavaCompilation.mainClassFromFile(file);
    }

    private static @Nullable String firstOrNull(List<String> l) {
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    /** Sets the java-debug settings the next Java session starts with (FX thread). */
    public void setJavaDebugSettings(JavaDebugSettings settings) {
        shared.javaDebugSettings = settings == null ? JavaDebugSettings.defaults() : settings;
    }

    private void startDebugSessionAndConnect(
            @Nullable Path file, java.util.Map<String, Object> args, boolean attach, long epoch) {
        if (!isCurrent(epoch)) {
            return;
        }
        // java-debug keeps one set of settings per jdtls: give it this session's before the session starts. A
        // failure (an older java-debug) is no reason not to debug, on its defaults as before.
        executeCommand(file, JavaDebugSettings.COMMAND, List.of(shared.javaDebugSettings.toJson()), (r, e) -> {
            if (e != null) {
                LOG.log(Level.FINE, "java-debug settings were not applied", e);
            }
            startDebugSession(file, args, attach, epoch);
        });
    }

    private void startDebugSession(
            @Nullable Path file, java.util.Map<String, Object> args, boolean attach, long epoch) {
        if (!isCurrent(epoch)) {
            return;
        }
        javaAdapterEpoch = epoch;
        executeCommand(file, "vscode.java.startDebugSession", List.of(), (res, err) -> {
            if (!isCurrent(epoch)) {
                return;
            }
            if (err != null) {
                fail(epoch, tr("debug.error.sessionFailed", msg(err)));
                return;
            }
            int port = JdtlsReplies.asInt(res);
            if (port <= 0) {
                fail(epoch, tr("debug.error.noPort"));
                return;
            }
            // The adapter (and the debuggee it launches) live inside that jdtls: pin it for this session.
            lspLease.run();
            lspLease = lsp.retain(file);
            DapClient c = new DapClient(sessionHost(epoch));
            c.setPaths(pathsFor(file));
            if (!attach && shared.programConsole) {
                c.setRunsDebuggee(true);
                LaunchConfig.inClientConsole(args);
            } else if (!attach) {
                wrapForExitCode(file, args, epoch);
            }
            c.setBreakpoints(adapterBreakpoints(shared.breakpointsSupplier.get())); // snapshot on the FX thread
            c.setExceptionFilters(filtersFor("java"));
            if (!publishClient(epoch, c)) {
                c.dispose();
                return;
            }
            // Connect off the FX thread (the socket open blocks with retries), then launch/attach. The
            // connect future completes on the DAP reader thread, so every state change here must be
            // marshaled back to the FX thread (it touches the Debug panel + status bar).
            io.submit(() -> connectJava(c, file, port).whenComplete((caps, e) -> {
                if (e != null) {
                    fail(epoch, tr("debug.error.connectFailed", msg(e)));
                    return;
                }
                if (!isCurrent(epoch, c)) {
                    c.dispose();
                    return;
                }
                (attach ? c.attach(args) : c.launch(args)).whenComplete((v, le) -> {
                    if (le != null) {
                        String refused = attach ? DapErrors.refusedAttach(msg(le)) : null;
                        if (refused != null) {
                            // Nothing listens there: say so, and how a JVM is made to listen (review 2, A109).
                            failWithConsole(
                                    epoch,
                                    tr("debug.console.attachRefused", refused),
                                    tr("status.debug.attachRefused", refused));
                            return;
                        }
                        fail(epoch, tr(attach ? "debug.error.attachFailed" : "debug.error.launchFailed", msg(le)));
                        return;
                    }
                    // Running once the adapter has the program, not when the request left: until then
                    // java-debug is still starting the JVM. A stop that came first has already said SUSPENDED.
                    fx(() -> {
                        if (!isCurrent(epoch, c)) {
                            return;
                        }
                        if (state == State.STARTING) {
                            setState(State.RUNNING);
                        }
                        if (!attach && args.get("javaExec") instanceof String javaExec && !javaExec.isBlank()) {
                            listener.onDebuggeeRuntime(
                                    List.of(realJavaExec(epoch, javaExec)), runtimeWhere(file), Map.of());
                        }
                    });
                });
            }));
        });
    }

    /**
     * Connects to the java-debug adapter jdtls opened on {@code port}: on this machine directly, on a remote
     * file's host through a tunnel that closes with the session.
     */
    private CompletableFuture<org.eclipse.lsp4j.debug.Capabilities> connectJava(
            DapClient c, @Nullable Path file, int port) {
        if (com.editora.process.ExecTargets.runsHere(file)) {
            return c.connect(port, "java");
        }
        try {
            com.editora.process.PortForward forward =
                    com.editora.process.ExecTargets.forPath(file).forwardLocal("127.0.0.1", port);
            c.closeOnDispose(forward);
            return c.connect(forward.localPort(), "java");
        } catch (java.io.IOException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** How the adapter of a session for {@code file} names files: its host's paths for a remote file. */
    static DapPaths pathsFor(@Nullable Path file) {
        return file != null && !com.editora.process.ExecTargets.runsHere(file)
                ? DapPaths.on(com.editora.process.ExecTargets.forPath(file), file.getFileSystem())
                : DapPaths.LOCAL;
    }

    // --- Standalone adapters (python / javascript) ----------------------------------------------

    /**
     * Launches a debug session for a standalone-adapter language (python/javascript). Snapshots the
     * breakpoints on the FX thread, then spawns the adapter + connects + sends {@code launch} off it.
     */
    private void startProgram(Path file, String language) {
        startProgram(file, language, null);
    }

    /**
     * As {@link #startProgram(Path, String)}; for Python, {@code module} (non-null) runs {@code python -m} that
     * module with its own arguments and folder instead of the file — a test under pytest. {@code file} still
     * names the session, picks the interpreter and anchors the breakpoints.
     */
    private void startProgram(Path file, String language, PythonLaunch.@Nullable Target module) {
        startProgram(file, language, module, null);
    }

    /**
     * Debugs a launch.json configuration of debugpy or vscode-js-debug for {@code file} (its program, or the file it
     * was started from): the launch Editora plans for the file — interpreter or Node.js, folder, console — with
     * {@code configured} laid over it, so what the configuration says wins. Restart repeats it.
     */
    public void startConfigured(Path file, String language, Map<String, Object> configured) {
        startProgram(file, language, null, Map.copyOf(configured));
    }

    private void startProgram(
            Path file,
            String language,
            PythonLaunch.@Nullable Target module,
            @Nullable Map<String, Object> configured) {
        if (!readyProgram(file, language)) {
            return;
        }
        DapServerRegistry.DapServerSpec spec = DapServerRegistry.specFor(language);
        if (spec == null) {
            listener.onError(tr("debug.error.noAdapter"));
            return;
        }
        restartAction = () -> startProgram(file, language, module, configured);
        debugFile = file;
        long epoch = beginSession();
        setState(State.STARTING);
        // Snapshot UI state on the FX thread before going off it.
        List<DapModels.FileBreakpoints> bps = shared.breakpointsSupplier.get();
        List<String> filters = filtersFor(language);
        startupTask = io.submit(() -> {
            try {
                boolean remote = !com.editora.process.ExecTargets.runsHere(file);
                if (spec.kind() == DapServerRegistry.Kind.STDIO) {
                    if (remote) {
                        startRemoteStdio(file, spec, bps, filters, epoch, module);
                    } else {
                        startStdio(file, spec, bps, filters, epoch, module, configured);
                    }
                } else if (spec.kind() == DapServerRegistry.Kind.JVM && !remote) {
                    startJvm(file, spec, bps, filters, epoch);
                } else if (spec.kind() == DapServerRegistry.Kind.LANGUAGE_SERVER && !remote) {
                    if (serverDebugs(file, language)) {
                        startHosted(file, spec, bps, filters, epoch);
                    } else if (DapServerRegistry.fallbackKind(language) == DapServerRegistry.Kind.JVM) {
                        startJvm(file, spec, bps, filters, epoch);
                    } else {
                        fail(epoch, tr("debug.error.transport", language));
                    }
                } else if (spec.kind() == DapServerRegistry.Kind.SOCKET) {
                    if (remote) {
                        startRemoteSocket(file, spec, bps, filters, epoch);
                    } else {
                        startSocket(file, spec, bps, filters, epoch, configured);
                    }
                } else {
                    fail(epoch, tr("debug.error.transport", language));
                }
            } catch (Exception e) {
                fail(epoch, tr("debug.error.startFailed", msg(e)));
            }
        });
    }

    // --- An external adapter: its own program, started by its command ----------------------------------

    /**
     * {@code planned} with a launch.json configuration laid over it ({@code configured} wins), but for how the
     * program's console is reached — Editora's ({@code console}, {@code outputCapture}) — and its {@code type},
     * which the adapter Editora started has. {@code planned} as it is when nothing is configured. Pure.
     */
    static Map<String, Object> overlay(Map<String, Object> planned, @Nullable Map<String, Object> configured) {
        if (configured == null) {
            return planned;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>(planned);
        configured.forEach((k, v) -> {
            if (!java.util.Set.of("type", "console", "outputCapture").contains(k)) {
                out.put(k, v);
            }
        });
        return out;
    }

    /** How long an {@link ExternalAdapter.Transport#ANNOUNCED_PORT} adapter has to say where it listens. */
    static final java.time.Duration ANNOUNCE_TIMEOUT = java.time.Duration.ofSeconds(15);

    /**
     * Finds external adapter language {@code language}'s adapter: {@code command} (a configured executable; blank:
     * the profile's own command, looked up on the PATH) when {@code on}. Off the FX thread; {@code onDone} hears
     * whether it was found, on the FX thread.
     */
    public void configureExternal(
            DebugLanguage language, boolean on, String command, java.util.function.@Nullable Consumer<Boolean> onDone) {
        ExternalAdapter external = language.external();
        if (external == null) {
            throw new IllegalArgumentException(language.id() + " is not an external adapter");
        }
        io.submit(() -> {
            String found = on ? locateExternal(language, command) : null;
            if (found == null) {
                shared.externalLocated.remove(language.id());
            } else {
                shared.externalLocated.put(language.id(), found);
            }
            if (onDone != null) {
                fx(() -> onDone.accept(found != null));
            }
        });
    }

    /**
     * Puts {@code dirs} first on the PATH of adapter language {@code languageId}'s adapter from its next session on
     * (Go: the folder of the {@code go} Settings names, so that Delve builds with it).
     */
    public void setExternalPathDirs(String languageId, List<String> dirs) {
        shared.externalPathDirs.put(languageId, List.copyOf(dirs));
    }

    /** {@code path} with {@code first} before its own folders, none twice. Pure. */
    static String pathWithFirst(List<String> first, @Nullable String path, String separator) {
        java.util.LinkedHashSet<String> dirs = new java.util.LinkedHashSet<>();
        for (String d : first) {
            if (d != null && !d.isBlank()) {
                dirs.add(d);
            }
        }
        if (path != null && !path.isEmpty()) {
            dirs.addAll(List.of(path.split(java.util.regex.Pattern.quote(separator))));
        }
        return String.join(separator, dirs);
    }

    /**
     * External adapter language {@code language}'s adapter as an executable file: {@code command} (a configured path
     * or command), or when blank the profile's own command ({@code dlv}), found on the PATH; null when not found.
     * Blocking: off the FX thread.
     */
    public static @Nullable String locateExternal(DebugLanguage language, @Nullable String command) {
        ExternalAdapter external = language.external();
        if (external == null) {
            return null;
        }
        if (DebugLanguages.CODELLDB.equals(external)) {
            // CodeLLDB lives in its extension's folder (Editora's install, VS Code's), or is named by its path.
            Path home = Path.of(System.getProperty("user.home", ""));
            String onPath = command == null || command.isBlank() ? locateExecutable("codelldb") : null;
            return DebugAdapterLocator.locateCodelldb(command, home)
                    .map(Path::toString)
                    .orElse(onPath);
        }
        InstalledAdapter installed = installedAdapter(language);
        if (installed != null) {
            // In Editora's install, a VS Code extension, or named by its path; else the program on the PATH.
            Path home = Path.of(System.getProperty("user.home", ""));
            String onPath = installed.program() && (command == null || command.isBlank())
                    ? locateExecutable(installed.file())
                    : null;
            return installed.locate(command, home).map(Path::toString).orElse(onPath);
        }
        String asked = command == null || command.isBlank() ? external.command().getFirst() : command.strip();
        String found = locateExecutable(asked);
        if (found != null || asked.indexOf('/') >= 0 || asked.indexOf('\\') >= 0) {
            return found; // a path is that file, or nothing
        }
        // `go install` puts Delve in the Go bin folder, which may not have been there when Editora read its PATH.
        boolean windows = java.io.File.pathSeparatorChar == ';';
        Path home = Path.of(System.getProperty("user.home", ""));
        List<Path> toolDirs = DebugLanguages.GO.equals(language)
                ? goBinDirs(System.getenv(), home)
                : DebugLanguages.RUBY.equals(language) ? gemUserBinDirs(home) : List.of();
        for (Path dir : toolDirs) {
            String inDir = locateExecutable(
                    dir.resolve(windows ? asked + ".exe" : asked).toString());
            if (inDir != null) {
                return inDir;
            }
        }
        return null;
    }

    /** The installable adapter {@code language} runs on (C#'s netcoredbg), or null when it has none. */
    static @Nullable InstalledAdapter installedAdapter(DebugLanguage language) {
        if (DebugLanguages.CSHARP.equals(language)) {
            return InstalledAdapter.NETCOREDBG;
        }
        return DebugLanguages.PHP.equals(language) ? InstalledAdapter.PHP_DEBUG : null;
    }

    /**
     * Where {@code go install} puts programs: {@code $GOBIN}, else each {@code $GOPATH} folder's {@code bin}, else
     * {@code ~/go/bin}. Pure.
     */
    static List<Path> goBinDirs(Map<String, String> env, Path home) {
        List<Path> out = new ArrayList<>();
        String gobin = env.get("GOBIN");
        if (gobin != null && !gobin.isBlank()) {
            out.add(Path.of(gobin.strip()));
        }
        String gopath = env.get("GOPATH");
        if (gopath != null && !gopath.isBlank()) {
            for (String dir : gopath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (!dir.isBlank()) {
                    out.add(Path.of(dir.strip(), "bin"));
                }
            }
        }
        out.add(home.resolve("go").resolve("bin"));
        return out;
    }

    /**
     * Where {@code gem install --user-install} puts programs: {@code ~/.local/share/gem/ruby/<version>/bin} (Ruby 3.3's
     * XDG folder) and {@code ~/.gem/ruby/<version>/bin}, the newest version first. Lists folders.
     */
    static List<Path> gemUserBinDirs(Path home) {
        List<Path> out = new ArrayList<>();
        for (Path base : List.of(
                home.resolve(".local").resolve("share").resolve("gem").resolve("ruby"),
                home.resolve(".gem").resolve("ruby"))) {
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (java.util.stream.Stream<Path> versions = Files.list(base)) {
                versions.filter(Files::isDirectory)
                        .sorted(java.util.Comparator.comparing(
                                        (Path v) -> v.getFileName().toString(),
                                        (a, b) -> DebugAdapterLocator.compareVersions(a, b))
                                .reversed())
                        .forEach(v -> out.add(v.resolve("bin")));
            } catch (java.io.IOException | RuntimeException e) {
                // unreadable: skip
            }
        }
        return out;
    }

    /** Whether {@link #configureExternal} found {@code language}'s adapter (GDB's, a user's own). */
    public boolean isExternalLocated(DebugLanguage language) {
        return shared.enabled && shared.externalLocated.containsKey(language.id());
    }

    /** {@code command} as an executable file: itself when it is a path, else found on the PATH; null when neither. */
    static @Nullable String locateExecutable(String command) {
        Path p;
        try {
            p = Path.of(ProcessRunner.resolveExecutable(List.of(command)).getFirst());
        } catch (java.nio.file.InvalidPathException e) {
            return null; // not a name a file can have
        }
        return p.isAbsolute() && Files.isRegularFile(p) && Files.isExecutable(p) ? p.toString() : null;
    }

    /**
     * Debugs {@code file}'s program on {@code language}'s external adapter ({@link DebugLanguage#external}): starts the
     * adapter's command (the executable detection found, else the profile's own), reaches it as its transport says,
     * then initializes and sends {@code launch} — shaped by the language ({@link ExternalAdapter#launchRequest}) — or
     * {@code attach} when the request says so ({@code "request": "attach"}: Stop then detaches, and the program runs
     * on). Restart starts it all again; Stop ends the adapter with the program it ran.
     */
    public void startExternal(Path file, DebugLanguage language, Map<String, Object> launch) {
        startExternal(file, language, launch, List.of());
    }

    /**
     * As {@link #startExternal(Path, DebugLanguage, Map)}, with {@code adapterTail} after the adapter's command: the
     * program, for an adapter that is the debugged program itself (Ruby's {@code rdbg … -- ruby script.rb}), which
     * then runs in the request's {@code cwd}.
     */
    public void startExternal(Path file, DebugLanguage language, Map<String, Object> launch, List<String> adapterTail) {
        if (managerClosed) {
            return;
        }
        if (!shared.enabled) {
            listener.onError(tr("debug.error.disabled"));
            return;
        }
        ExternalAdapter external = language.external();
        if (external == null) {
            listener.onError(tr(language.unavailableKey()));
            return;
        }
        if (client != null) {
            stop(); // one session at a time
        }
        List<String> tail = List.copyOf(adapterTail);
        restartAction = () -> startExternal(file, language, launch, tail);
        debugFile = file;
        long epoch = beginSession();
        setState(State.STARTING);
        List<DapModels.FileBreakpoints> bps = shared.breakpointsSupplier.get();
        List<String> filters = filtersFor(language.id());
        String executable = shared.externalLocated.get(language.id());
        startupTask = io.submit(() -> {
            try {
                runExternal(file, language, external, executable, bps, filters, epoch, launch, tail);
            } catch (Exception e) {
                fail(epoch, tr("debug.error.startFailed", msg(e)));
            }
        });
    }

    private void runExternal(
            Path file,
            DebugLanguage language,
            ExternalAdapter external,
            @Nullable String executable,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch,
            Map<String, Object> launch,
            List<String> programTail)
            throws Exception {
        int port = external.transport() == ExternalAdapter.Transport.PORT_ARGUMENT ? freePort() : -1;
        List<String> argv = new ArrayList<>(external.argv(executable, port));
        argv.addAll(programTail);
        if (argv.getFirst().endsWith(".js")) {
            argv.addFirst(adapterNode()); // a Node.js adapter (vscode-php-debug), on the Node.js Settings names
        }
        ProcessBuilder pb = new ProcessBuilder(ProcessRunner.resolveExecutable(argv));
        ProcessRunner.applyUserEnv(pb);
        if (!programTail.isEmpty() && launch.get("env") instanceof Map<?, ?> env) {
            // the adapter is the program: the configuration's environment is its own
            env.forEach((k, v) -> pb.environment().put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }
        List<String> first = shared.externalPathDirs.getOrDefault(language.id(), List.of());
        if (!first.isEmpty()) {
            Map<String, String> env = pb.environment();
            String key = env.keySet().stream()
                    .filter(k -> k.equalsIgnoreCase("PATH"))
                    .findFirst()
                    .orElse("PATH");
            env.put(key, pathWithFirst(first, env.get(key), java.io.File.pathSeparator));
        }
        Path cwd = adapterFolder(file, launch, !programTail.isEmpty());
        if (cwd != null && Files.isDirectory(cwd)) {
            pb.directory(cwd.toFile());
        }
        onOutput(epoch, String.join(" ", pb.command()) + "\n", COMMAND_CATEGORY);
        if (external.transport() == ExternalAdapter.Transport.ANNOUNCED_PORT) {
            pb.redirectErrorStream(true); // the announcement may come on either stream
        }
        Process proc = BusyExecutable.start(pb);
        if (!isCurrent(epoch)) {
            com.editora.process.ProcessRegistry.killTree(proc);
            return;
        }
        programAdapter = programTail.isEmpty() ? null : new ProgramAdapter(epoch, proc);
        boolean attach = "attach".equals(launch.get("request"));
        DapClient c = new DapClient(sessionHost(epoch));
        c.setPaths(DapPaths.LOCAL);
        c.setBreakpoints(bps);
        c.setExceptionFilters(filters);
        c.setRunsDebuggee(shared.programConsole);
        if (attach) {
            c.setTerminateOnDisconnect(false); // Stop detaches; the program runs on
        }
        AdapterStderr stderr = null;
        java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.debug.Capabilities> connected;
        switch (external.transport()) {
            case STDIO -> {
                stderr = AdapterStderr.drain(proc); // DAP is on stdin/stdout; an undrained PIPE deadlocks
                if (!publishClient(epoch, c)) {
                    com.editora.process.ProcessRegistry.killTree(proc);
                    c.dispose();
                    return;
                }
                connected = c.connectStdio(proc, language.adapterId());
            }
            case PORT_ARGUMENT -> {
                stderr = AdapterStderr.drain(proc);
                drainToConsole(proc.getInputStream(), epoch);
                c.setAdapterProcess(proc); // killed on dispose: it serves the socket
                if (!publishClient(epoch, c)) {
                    c.dispose();
                    return;
                }
                connected = c.connect(port, language.adapterId());
            }
            case ANNOUNCED_PORT -> {
                c.setAdapterProcess(proc);
                int announced = awaitAnnouncement(proc, external, epoch);
                if (announced < 0) {
                    c.dispose();
                    fail(
                            epoch,
                            tr(
                                    "debug.error.adapterNoPort",
                                    String.join(" ", pb.command()),
                                    ANNOUNCE_TIMEOUT.toSeconds()));
                    return;
                }
                if (!publishClient(epoch, c)) {
                    c.dispose();
                    return;
                }
                connected = c.connect(announced, language.adapterId());
            }
            default -> throw new IllegalStateException(external.transport().toString());
        }
        AdapterStderr tail = stderr;
        connected.whenComplete((caps, e) -> {
            if (e != null) {
                fail(
                        epoch,
                        tr("debug.error.adapterConnect", language.displayName(), msg(e)) + AdapterStderr.suffix(tail));
                return;
            }
            if (!isCurrent(epoch)) {
                c.dispose();
                return;
            }
            (attach ? c.attach(launch) : c.launch(launch)).whenComplete((v, le) -> {
                if (le != null) {
                    String key = attach ? "debug.error.attachFailed" : "debug.error.launchFailed";
                    fail(epoch, tr(key, msg(le)) + AdapterStderr.suffix(tail));
                }
            });
            fx(() -> {
                if (isCurrent(epoch, c)) {
                    setState(State.RUNNING);
                }
            });
        });
    }

    /**
     * The folder an external adapter starts in: the request's {@code cwd} when the adapter is the program itself
     * ({@code programRuns}), else {@code file}'s folder (or {@code file}, a folder). Pure but for the folder check.
     */
    static @Nullable Path adapterFolder(Path file, Map<String, Object> launch, boolean programRuns) {
        if (programRuns && launch.get("cwd") instanceof String c && !c.isBlank()) {
            try {
                Path dir = Path.of(c);
                if (Files.isDirectory(dir)) {
                    return dir;
                }
            } catch (java.nio.file.InvalidPathException e) {
                // not a folder: the file's
            }
        }
        return Files.isDirectory(file) ? file : file.getParent();
    }

    /**
     * Reads {@code proc}'s output until it announces its port (passing the lines before it to the console), then
     * passes the rest on as the program's output — Delve writes its debuggee's there. -1 when it ends, or says
     * nothing, within {@link #ANNOUNCE_TIMEOUT}.
     */
    private int awaitAnnouncement(Process proc, ExternalAdapter external, long epoch) {
        java.util.concurrent.CompletableFuture<Integer> port = new java.util.concurrent.CompletableFuture<>();
        Thread reader = new Thread(
                () -> {
                    try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                            proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = in.readLine()) != null) {
                            int found = port.isDone() ? -1 : external.announcedPort(line);
                            if (found > 0) {
                                port.complete(found);
                            } else {
                                onOutput(epoch, line + "\n", port.isDone() ? "stdout" : COMMAND_CATEGORY);
                            }
                        }
                    } catch (java.io.IOException e) {
                        // the adapter ended: whoever waits hears -1
                    }
                    port.complete(-1);
                },
                "dap-external-adapter-output");
        reader.setDaemon(true);
        reader.start();
        try {
            return port.get(ANNOUNCE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Passes {@code out} (a socket adapter's standard output) to the console as program output, on a daemon thread. */
    private void drainToConsole(java.io.InputStream out, long epoch) {
        Thread t = new Thread(
                () -> {
                    try (java.io.BufferedReader in = new java.io.BufferedReader(
                            new java.io.InputStreamReader(out, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = in.readLine()) != null) {
                            onOutput(epoch, line + "\n", "stdout");
                        }
                    } catch (java.io.IOException e) {
                        // the adapter ended
                    }
                },
                "dap-external-adapter-stdout");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Attaches to a program that is its own debug adapter and listens on this machine's {@code port} (Ruby under
     * {@code rdbg --open}): the client talks DAP to it directly and sends {@code attach}. Stop detaches; the program
     * runs on.
     */
    public void startPortAttach(Path anchor, DebugLanguage language, int port, Map<String, Object> attach) {
        if (managerClosed) {
            return;
        }
        if (!shared.enabled) {
            listener.onError(tr("debug.error.disabled"));
            return;
        }
        if (client != null) {
            stop(); // one session at a time
        }
        restartAction = () -> startPortAttach(anchor, language, port, attach);
        debugFile = anchor;
        long epoch = beginSession();
        setState(State.STARTING);
        List<DapModels.FileBreakpoints> bps = shared.breakpointsSupplier.get();
        List<String> filters = filtersFor(language.id());
        startupTask = io.submit(() -> {
            try {
                if (!isCurrent(epoch)) {
                    return;
                }
                DapClient c = new DapClient(sessionHost(epoch));
                c.setPaths(DapPaths.LOCAL);
                c.setBreakpoints(bps);
                c.setExceptionFilters(filters);
                c.setTerminateOnDisconnect(false); // Stop detaches; the program runs on
                if (!publishClient(epoch, c)) {
                    c.dispose();
                    return;
                }
                onOutput(epoch, tr("debug.console.attaching", "127.0.0.1:" + port) + "\n", COMMAND_CATEGORY);
                c.connect(port, language.adapterId()).whenComplete((caps, e) -> {
                    if (e != null) {
                        fail(epoch, tr("debug.error.adapterConnect", language.displayName(), msg(e)));
                        return;
                    }
                    if (!isCurrent(epoch)) {
                        c.dispose();
                        return;
                    }
                    c.attach(attach).whenComplete((v, ae) -> {
                        if (ae != null) {
                            fail(epoch, tr("debug.error.attachFailed", msg(ae)));
                        }
                    });
                    fx(() -> {
                        if (isCurrent(epoch, c)) {
                            setState(State.RUNNING);
                        }
                    });
                });
            } catch (Exception e) {
                fail(epoch, tr("debug.error.startFailed", msg(e)));
            }
        });
    }

    /**
     * Attaches to a Python listening for a debugger ({@code python -m debugpy --listen host:port …}, or one that
     * called {@code debugpy.listen}): that debugpy runs its own adapter on the port, so the session connects to it
     * over the socket and sends {@code attach}, as VS Code's {@code connect} configuration does. {@code anchor} names the session (a file or folder of this
     * machine). Restart attaches again; Stop detaches and leaves the program running.
     */
    public void startPythonAttach(Path anchor, String host, int port) {
        // Not readyProgram: the program brings its own debugpy, so none need be installed here.
        if (managerClosed) {
            return;
        }
        if (!shared.enabled) {
            listener.onError(tr("debug.error.disabled"));
            return;
        }
        if (client != null) {
            stop(); // one session at a time
        }
        DapServerRegistry.DapServerSpec spec = DapServerRegistry.specFor("python");
        if (spec == null) {
            listener.onError(tr("debug.error.noAdapter"));
            return;
        }
        restartAction = () -> startPythonAttach(anchor, host, port);
        debugFile = anchor;
        long epoch = beginSession();
        setState(State.STARTING);
        List<DapModels.FileBreakpoints> bps = shared.breakpointsSupplier.get();
        List<String> filters = filtersFor("python");
        startupTask = io.submit(() -> {
            try {
                if (!isCurrent(epoch)) {
                    return;
                }
                DapClient c = new DapClient(sessionHost(epoch));
                c.setPaths(DapPaths.LOCAL);
                c.setBreakpoints(bps);
                c.setExceptionFilters(filters);
                c.setTerminateOnDisconnect(false); // Stop detaches; the program runs on
                if (!publishClient(epoch, c)) {
                    c.dispose();
                    return;
                }
                onOutput(epoch, tr("debug.console.attaching", host + ":" + port) + "\n", COMMAND_CATEGORY);
                // `debugpy --listen` runs debugpy's own adapter on that port: the client talks DAP to it directly.
                c.connect(port, spec.adapterId()).whenComplete((caps, e) -> {
                    if (e != null) {
                        fail(epoch, tr("debug.error.debugpyConnect", msg(e)));
                        return;
                    }
                    if (!isCurrent(epoch)) {
                        c.dispose();
                        return;
                    }
                    c.attach(LaunchConfig.pythonAttach(host, port)).whenComplete((v, ae) -> {
                        if (ae != null) {
                            fail(epoch, tr("debug.error.attachFailed", msg(ae)));
                        }
                    });
                    fx(() -> {
                        if (isCurrent(epoch, c)) {
                            setState(State.RUNNING);
                        }
                    });
                });
            } catch (Exception e) {
                fail(epoch, tr("debug.error.startFailed", msg(e)));
            }
        });
    }

    /** Hands each subprocess debugpy asks for to the window ({@link Listener#onChildSession}), while it is current. */
    private java.util.function.Function<Map<String, Object>, java.util.concurrent.CompletableFuture<Void>>
            childSessions(long epoch, DapClient c) {
        return configuration -> {
            fx(() -> {
                if (isCurrent(epoch, c)) {
                    listener.onChildSession(configuration);
                }
            });
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Debugs a subprocess of a Python session in this (new) manager: attaches to the port debugpy named for it in
     * {@code configuration} ({@link Listener#onChildSession}), anchored at the parent's file {@code anchor}. Stop
     * detaches: the subprocess runs on, and ends with its program.
     */
    public void startPythonChild(@Nullable Path anchor, Map<String, Object> configuration) {
        if (managerClosed) {
            return;
        }
        int port = configuration.get("connect") instanceof Map<?, ?> connect && connect.get("port") instanceof Number n
                ? n.intValue()
                : -1;
        DapServerRegistry.DapServerSpec spec = DapServerRegistry.specFor("python");
        if (spec == null || port <= 0) {
            listener.onError(tr("debug.error.noAdapter"));
            return;
        }
        if (client != null) {
            stop(); // one session at a time
        }
        restartAction = null; // a subprocess is the program's to start again, not this session's
        debugFile = anchor;
        long epoch = beginSession();
        setState(State.STARTING);
        List<DapModels.FileBreakpoints> bps = shared.breakpointsSupplier.get();
        List<String> filters = filtersFor("python");
        Map<String, Object> attach = new java.util.LinkedHashMap<>(configuration);
        startupTask = io.submit(() -> {
            try {
                if (!isCurrent(epoch)) {
                    return;
                }
                DapClient c = new DapClient(sessionHost(epoch));
                c.setPaths(DapPaths.LOCAL);
                c.setBreakpoints(bps);
                c.setExceptionFilters(filters);
                c.setTerminateOnDisconnect(false);
                // A subprocess's own subprocesses (multiprocessing's fork server forks the workers) are sessions of
                // their own too, not connections folded into this one.
                c.setChildSessions(childSessions(epoch, c));
                if (!publishClient(epoch, c)) {
                    c.dispose();
                    return;
                }
                c.connect(port, spec.adapterId()).whenComplete((caps, e) -> {
                    if (e != null) {
                        fail(epoch, tr("debug.error.debugpyConnect", msg(e)));
                        return;
                    }
                    if (!isCurrent(epoch)) {
                        c.dispose();
                        return;
                    }
                    c.attach(attach).whenComplete((v, ae) -> {
                        if (ae != null) {
                            fail(epoch, tr("debug.error.attachFailed", msg(ae)));
                        }
                    });
                    fx(() -> {
                        if (isCurrent(epoch, c)) {
                            setState(State.RUNNING);
                        }
                    });
                });
            } catch (Exception e) {
                fail(epoch, tr("debug.error.startFailed", msg(e)));
            }
        });
    }

    /**
     * Debugs a pytest run ({@code python -m pytest args}) in {@code cwd}, anchored at test file {@code anchor}.
     * When the interpreter has no pytest, {@code unittestArgs} (non-empty) run under {@code python -m unittest}
     * instead; otherwise the session ends saying pytest is missing for that interpreter.
     */
    public void startPythonTest(Path anchor, List<String> pytestArgs, List<String> unittestArgs, Path cwd) {
        Path dir = cwd != null ? cwd : anchor.getParent();
        String where = dir == null
                ? null
                : com.editora.process.ExecTargets.forPath(dir).toTargetPath(dir);
        PythonLaunch.Target unittest = unittestArgs == null || unittestArgs.isEmpty()
                ? null
                : PythonLaunch.Target.module("unittest", unittestArgs, where);
        startProgram(
                anchor,
                "python",
                PythonLaunch.Target.module("pytest", pytestArgs, where).orElse(unittest));
    }

    /**
     * Debugs {@code python -m module args} in {@code cwd} — a pytest test, say — under {@code anchor}'s
     * interpreter, with {@code anchor} naming the session. Restart repeats it.
     */
    public void startPythonModule(Path anchor, String module, List<String> args, Path cwd) {
        Path dir = cwd != null ? cwd : anchor.getParent();
        String where = dir == null
                ? null
                : com.editora.process.ExecTargets.forPath(dir).toTargetPath(dir);
        startProgram(anchor, "python", PythonLaunch.Target.module(module, args, where));
    }

    private boolean readyProgram(Path file, String language) {
        if (managerClosed) {
            return false;
        }
        if (!shared.enabled) {
            listener.onError(tr("debug.error.disabled"));
            return false;
        }
        if (file == null) {
            listener.onError(tr("debug.error.openFile"));
            return false;
        }
        if (!com.editora.process.ExecTargets.runsHere(file)) {
            String problem = remoteProblem(file, language);
            if (problem != null) {
                listener.onError(problem);
                return false;
            }
        } else if (!isLanguageAvailable(language)) {
            DebugLanguage l = DebugLanguages.byId(language);
            listener.onError(tr(l == null ? "status.debug.unavailable" : l.unavailableKey()));
            return false;
        }
        if (client != null) {
            stop(); // one session at a time
        }
        return true;
    }

    /**
     * debugpy over stdio: spawn {@code <python> -m debugpy.adapter}, wire DAP to its streams, launch. The
     * program runs under the interpreter resolved for {@code file} (the project's venv, the picked one, …);
     * the adapter runs under that one too when it can import debugpy, else under the Debugging setting's.
     */
    private void startStdio(
            Path file,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch,
            PythonLaunch.@Nullable Target module,
            @Nullable Map<String, Object> configured)
            throws Exception {
        HostPython program = planProgram(file, com.editora.process.ExecTargets.forPath(file), true);
        HostPython adapter = probe(program.argv(List.of("-c", "import debugpy")), shared.debugpyDir, program.env())
                ? program
                : HostPython.parse(DapServerRegistry.interpreterArgv("python", shared.pythonCommand));
        List<String> cmd = ProcessRunner.resolveExecutable(adapter.argv(spec.adapterArgs())); // -m debugpy.adapter
        ProcessBuilder pb = new ProcessBuilder(cmd);
        ProcessRunner.applyUserEnv(pb);
        pb.environment().putAll(adapter.env());
        if (shared.debugpyDir != null) {
            prependPythonPath(pb, shared.debugpyDir);
        }
        if (module != null && "pytest".equals(module.module())) {
            module = pytestOrFallback(module, program, epoch, argv -> probe(argv, null, program.env()));
            if (module == null) {
                return; // reported
            }
        }
        Process proc = BusyExecutable.start(pb);
        AdapterStderr stderr = AdapterStderr.drain(proc); // DAP is on stdin/stdout; an undrained PIPE deadlocks
        boolean console = shared.programConsole;
        PythonLaunch.Target target =
                module != null ? module : PythonLaunch.Target.script(file.toString(), programArgs, cwdOf(file));
        // What runs, first in the console (muted): which Python, in which venv, with which arguments.
        onOutput(epoch, PythonLaunch.commandLine(program, target) + "\n", COMMAND_CATEGORY);
        // The plan's PYTHONPATH (project root, src/, the user's) when it set one; else the user's own, or "" —
        // never the adapter's, which carries Editora's debugpy folder.
        String inherited = System.getenv("PYTHONPATH");
        Map<String, Object> planned = PythonLaunch.request(
                target, program, List.of(), inherited == null ? "" : inherited, File.pathSeparator, console);
        if (shared.pythonSubprocesses) {
            planned.put("subProcess", true); // each subprocess a session of its own (onChildSession)
        }
        Map<String, Object> launch = overlay(planned, configured);
        attachStdio(proc, spec, bps, filters, epoch, DapPaths.LOCAL, launch, console, stderr);
    }

    /**
     * A pytest run when {@code program} can import pytest; else its unittest fallback; else null, with the
     * session ended saying which interpreter lacks pytest.
     */
    private PythonLaunch.@Nullable Target pytestOrFallback(
            PythonLaunch.Target pytest,
            HostPython program,
            long epoch,
            java.util.function.Predicate<List<String>> runs) {
        if (!"pytest".equals(pytest.module()) || runs.test(program.argv(List.of("-c", "import pytest")))) {
            return pytest;
        }
        if (pytest.fallback() != null) {
            return pytest.fallback();
        }
        fail(epoch, tr("debug.error.pytestMissing", program.executable()));
        return null;
    }

    /**
     * The program's interpreter and environment for a Python session on {@code file}, planned by Run's planner
     * ({@link com.editora.run.PythonRunPlan}) so Run and Debug start the same Python the same way: the
     * resolved interpreter (a project venv found on the target, named as the target names it; a picked one; a
     * host's {@code python} tool), the file's shebang, and the project root with {@code src/} on {@code
     * PYTHONPATH}. Taken apart as debugpy takes it ({@code python}, {@code pythonArgs}, {@code env}). Off the FX
     * thread: elsewhere the lookups are network round trips.
     */
    private HostPython planProgram(Path file, com.editora.process.ExecTarget target, boolean local) {
        com.editora.run.PythonRunPlan.Request asked = pythonRequest;
        String firstLine = asked != null ? asked.firstLine() : null;
        if (com.editora.run.PythonLaunch.isRunner(com.editora.run.PythonLaunch.shebang(firstLine))) {
            firstLine = null; // debugpy needs an interpreter, not `uv run --script`: the project's Python runs it
        }
        com.editora.run.PythonRunPlan.Request request = com.editora.run.PythonRunPlan.Request.file(
                file,
                asked != null ? asked.projectRoot() : pythonRootFor(file),
                target.toTargetPath(file),
                List.of(),
                firstLine,
                asked != null ? asked.projectChoice() : pythonProjectChoice,
                asked != null ? asked.setting() : local ? shared.pythonCommand : "");
        com.editora.run.PythonRunPlan.Host host = pythonHost != null
                ? pythonHost
                : local
                        ? com.editora.run.PythonRunPlan.Host.here()
                        : com.editora.run.PythonRunPlan.Host.elsewhere(target::toTargetPath);
        com.editora.run.PythonRunPlan plan;
        try {
            plan = com.editora.run.PythonRunPlan.plan(request, host);
        } catch (RuntimeException e) {
            if (host.local()) {
                throw e;
            }
            // A lookup over a dropped connection: the host's own python3, as Run does.
            plan = com.editora.run.PythonRunPlan.plan(
                    request,
                    com.editora.run.PythonRunPlan.Host.elsewhere(host.toTarget())
                            .withoutLookups());
        }
        List<String> command = plan.argv().subList(0, plan.argv().size() - 1); // the plan's argv ends in the script
        // debugpy is handed a local interpreter by path: the adapter's PATH is not the one it was found on.
        String exe = host.local()
                ? ProcessRunner.resolveExecutable(List.of(command.get(0))).getFirst()
                : command.get(0);
        return new HostPython(exe, List.copyOf(command.subList(1, command.size())), plan.env());
    }

    /** The project root {@code file} belongs to (the window's project, when the file is inside it), else null. */
    private @Nullable Path pythonRootFor(Path file) {
        Path root = pythonProjectRoot;
        if (root == null || file == null) {
            return null;
        }
        try {
            return file.toAbsolutePath()
                            .normalize()
                            .startsWith(root.toAbsolutePath().normalize())
                    ? root
                    : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Wires a started stdio adapter ({@code proc}) to a new session and sends it {@code launch}, whose paths are
     * named as the adapter's machine names them. With {@code runsDebuggee}, the adapter hands the program's
     * command line back and Editora starts it (its standard input is then the Debug console's).
     */
    private void attachStdio(
            Process proc,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch,
            DapPaths paths,
            Map<String, Object> launch,
            boolean runsDebuggee,
            @Nullable AdapterStderr stderr) {
        if (!isCurrent(epoch)) {
            com.editora.process.ProcessRegistry.killTree(proc);
            return;
        }
        DapClient c = new DapClient(sessionHost(epoch));
        c.setPaths(paths);
        c.setBreakpoints(bps);
        c.setExceptionFilters(filters);
        c.setRunsDebuggee(runsDebuggee);
        if (Boolean.TRUE.equals(launch.get("subProcess")) && paths == DapPaths.LOCAL) {
            // debugpy asks for each subprocess with startDebugging, naming a port of its own to attach a session to.
            c.setChildSessions(childSessions(epoch, c));
        }
        if (!publishClient(epoch, c)) {
            com.editora.process.ProcessRegistry.killTree(proc);
            c.dispose();
            return;
        }
        c.connectStdio(proc, spec.adapterId()).whenComplete((caps, e) -> {
            if (e != null) {
                fail(epoch, tr("debug.error.debugpyConnect", msg(e)) + AdapterStderr.suffix(stderr));
                return;
            }
            if (!isCurrent(epoch)) {
                c.dispose();
                return;
            }
            c.launch(launch).whenComplete((v, le) -> {
                if (le != null) {
                    fail(epoch, launchFailure(le, launch) + AdapterStderr.suffix(stderr));
                }
            });
            fx(() -> {
                if (isCurrent(epoch, c)) {
                    setState(State.RUNNING);
                    List<String> runtime = pythonRuntime(launch);
                    if (!runtime.isEmpty()) {
                        listener.onDebuggeeRuntime(
                                runtime,
                                runtimeWhere(paths, launch.get("cwd") instanceof String cwd ? cwd : null),
                                stringMap(launch.get("env")));
                    }
                }
            });
        });
    }

    /**
     * What a failed Python launch says. A timeout is debugpy waiting for a program it could not start — an
     * interpreter that does not run — rather than anything the user can read in a Java exception's name.
     */
    static String launchFailure(Throwable error, Map<String, Object> launch) {
        Throwable t = error;
        while (t.getCause() != null && !(t instanceof java.util.concurrent.TimeoutException)) {
            t = t.getCause();
        }
        if (t instanceof java.util.concurrent.TimeoutException) {
            return tr(
                    "debug.error.debugpyNoStart",
                    String.valueOf(DapClient.requestTimeoutSeconds()),
                    String.valueOf(launch.get("python")));
        }
        return tr("debug.error.launchFailed", msg(error));
    }

    /**
     * vscode-js-debug for a file on this machine: the Node.js {@link com.editora.run.NodeRuntimes} resolves for it
     * runs the program (and js-debug, when it is new enough), from the file's package root; then connect, launch.
     */
    private void startSocket(
            Path file,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch,
            @Nullable Map<String, Object> configured)
            throws Exception {
        Path server = shared.jsServer;
        if (server == null) {
            fail(epoch, tr("debug.error.jsNotFound"));
            return;
        }
        JsProgram planned = jsProgram;
        List<String> runtime;
        String program;
        List<String> args;
        Path cwd;
        Path workspace;
        Map<String, String> env;
        String adapterNode;
        Path projectRoot = jsProjectRoot;
        if (planned != null) {
            // A run configuration's launch, planned as Run plans it (NodeRunPlan / the package manager).
            runtime = planned.runtime();
            program = planned.program();
            args = planned.args();
            cwd = planned.cwd();
            workspace = projectRoot != null && cwd.toAbsolutePath().startsWith(projectRoot.toAbsolutePath())
                    ? projectRoot
                    : cwd;
            env = planned.env();
            String exe = runtime.get(0);
            boolean node = com.editora.run.NodeRunPlan.isNodeName(exe);
            String version = node ? nodeVersion(exe) : null;
            adapterNode =
                    node ? adapterNodeFor(exe, version) : adapterNodeFor(adapterNode(), nodeVersion(adapterNode()));
            if (adapterNode == null) {
                String used = node ? exe : adapterNode();
                fail(
                        epoch,
                        tr(
                                "debug.error.jsNodeTooOld",
                                String.valueOf(node ? version : nodeVersion(used)),
                                used,
                                JsLaunch.MIN_ADAPTER_NODE_MAJOR));
                return;
            }
        } else {
            com.editora.run.NodeRuntimes.NodeRuntime resolved =
                    com.editora.run.NodeRuntimes.resolveLocal(file, projectRoot, "", shared.nodePath);
            String version = nodeVersion(resolved.executable());
            String problem = JsLaunch.launchProblemKey(file.getFileName().toString(), version);
            if (problem != null) {
                fail(epoch, tr(problem, version == null ? "?" : version));
                return;
            }
            adapterNode = adapterNodeFor(resolved.executable(), version);
            if (adapterNode == null) {
                fail(
                        epoch,
                        tr(
                                "debug.error.jsNodeTooOld",
                                String.valueOf(version),
                                resolved.executable(),
                                JsLaunch.MIN_ADAPTER_NODE_MAJOR));
                return;
            }
            runtime = JsLaunch.withNodeArgs(resolved.command(), jsNodeArgs);
            program = file.toString();
            args = programArgs;
            cwd = com.editora.run.NodeRuntimes.workingDirectory(file, projectRoot);
            // The workspace folder js-debug resolves ${workspaceFolder} from (source maps): the project when the
            // file is in it, else its package root.
            workspace = projectRoot != null && file.toAbsolutePath().startsWith(projectRoot.toAbsolutePath())
                    ? projectRoot
                    : cwd;
            env = JsLaunch.colourEnvironment(System.getenv("NO_COLOR")); // as Run (review D104)
        }
        boolean clientConsole = shared.programConsole;
        Map<String, Object> launch = overlay(
                JsLaunch.request(
                        spec.launchType(),
                        program,
                        cwd.toString(),
                        workspace.toString(),
                        runtime,
                        args,
                        clientConsole,
                        env),
                configured);
        jsClientConsole = clientConsole;
        // The command line that runs is printed when js-debug asks Editora to start it (startDebuggee): js-debug may
        // run another file than the one planned — a .ts source's compiled output (review D106).
        List<String> chip = runtime;
        JsAdapter adapter = startJsAdapter(server, adapterNode, epoch);
        if (adapter == null) {
            return; // reported
        }
        attachSocket(
                adapter.process(),
                null,
                adapter.port(),
                spec,
                bps,
                filters,
                epoch,
                DapPaths.LOCAL,
                launch,
                clientConsole,
                chip);
    }

    /**
     * Editora's own JVM adapter ({@link com.editora.dap.jvm.JvmDebugAdapter}), served in this process on a
     * loopback port: the program runs as Run runs it ({@code groovy Script.groovy args…} on the session's JDK),
     * with the JDWP agent in the variable its launcher hands the JVM, started by this client
     * ({@code runInTerminal}) so its output, input and exit code are the Debug console's.
     */
    private void startJvm(
            Path file,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch)
            throws java.io.IOException {
        String language = spec.language();
        if ("clojure".equals(language)
                && file.getFileName() != null
                && !com.editora.run.ClojureRun.runnable(file.getFileName().toString())) {
            fail(
                    epoch,
                    tr(
                            "debug.error.notDebuggable.clojureFile",
                            file.getFileName().toString()));
            return;
        }
        String toolName = com.editora.run.JvmScriptRun.tool(language, file);
        if (!com.editora.run.JvmScriptRun.debuggable(toolName)) {
            // Babashka is a native program: there is no JVM for a debugger to attach to.
            fail(epoch, tr("debug.error.notDebuggable." + toolName));
            return;
        }
        String tool = toolName == null ? null : jvmToolLocator.apply(toolName);
        if (toolName == null || tool == null) {
            fail(epoch, tr(com.editora.run.JvmScriptRun.missingToolKeyFor(toolName)));
            return;
        }
        Map<String, Object> launch = new java.util.LinkedHashMap<>();
        Map<String, String> launchEnv = env;
        Path directory = null;
        Path jar = jvmJar;
        List<String> argv;
        if (jar != null) {
            // A Kotlin program: `java` is its own launcher, its VM arguments on that command line.
            argv = com.editora.run.KotlinRun.programArgv(jvmJava, jvmOptions(), jar.toString(), programArgs);
            launch.put("jvmOptionsInArgv", true); // the agent goes first
        } else if (com.editora.run.JvmScriptRun.takesJvmOptionsInArgv(language)) {
            // Scala CLI: the agent (and the VM arguments) as --java-opt; the file, or its project folder with
            // the file's main class. It compiles before the JVM starts — a first run fetches the compiler too.
            com.editora.run.ScalaRun.Owner owner =
                    com.editora.run.ScalaRun.ownerOf(file, null, java.nio.file.Files::isRegularFile);
            List<String> inputs = com.editora.run.ScalaRun.cliInputs(
                    file.toString(),
                    owner,
                    owner.root() == null ? null : owner.root().toString(),
                    com.editora.run.ScalaRun.mainClass(readQuietly(file)));
            argv = com.editora.run.JvmScriptRun.scalaCliDebug(tool, inputs, jvmOptions(), programArgs);
            launch.put("agentArgument", com.editora.run.JvmScriptRun.AGENT_ARGUMENT);
            launch.put("connectTimeoutMs", SCALA_CLI_CONNECT_TIMEOUT_MS);
            if (owner.root() != null) {
                launch.put("sourceRoots", List.of(owner.root().toString()));
            }
        } else {
            // The configuration's VM arguments go where the tool hands them to its JVM, beside the debugger's agent.
            com.editora.run.JvmScriptRun.Launch how = com.editora.run.JvmScriptRun.launch(
                    language,
                    toolName,
                    tool,
                    file,
                    file.toString(),
                    programArgs,
                    vmArgsFor(LaunchConfig.Target.LOCAL),
                    true,
                    env,
                    System.getenv());
            argv = how.argv();
            launchEnv = how.env();
            directory = how.directory();
            launch.put("jvmOptionsVariable", com.editora.run.JvmScriptRun.jvmOptionsVariable(language, toolName));
            String prefix = com.editora.run.JvmScriptRun.jvmOptionPrefix(toolName);
            if (prefix != null) {
                launch.put("jvmOptionPrefix", prefix);
            }
            if (directory != null) {
                launch.put("sourceRoots", List.of(directory.toString()));
            }
        }
        Map<String, String> programEnv = launchEnv;
        launch.put("argv", argv);
        Path folder = jvmCwd != null ? jvmCwd : directory;
        launch.put("cwd", folder != null ? folder.toString() : cwdOf(file));
        launch.put("env", programEnv);
        launch.put("language", language);
        launch.put("sourceFiles", List.of(file.toString())); // its frames find it whatever folder it sits in
        onOutput(epoch, com.editora.run.RunService.commandLine(argv) + "\n", COMMAND_CATEGORY);
        int port = com.editora.dap.jvm.JvmDebugServer.start(new com.editora.dap.jvm.JvmDebugAdapter());
        DapClient c = new DapClient(sessionHost(epoch));
        c.setPaths(DapPaths.LOCAL);
        c.setBreakpoints(bps);
        c.setExceptionFilters(filters);
        c.setRunsDebuggee(true); // the program is this client's: the Debug console's output, input and exit code
        if (!publishClient(epoch, c)) {
            c.dispose();
            return;
        }
        c.connect(port, spec.adapterId()).whenComplete((caps, e) -> {
            if (e != null) {
                fail(epoch, tr("debug.error.connectFailed", msg(e)));
                return;
            }
            if (!isCurrent(epoch)) {
                c.dispose();
                return;
            }
            c.launch(launch).whenComplete((v, le) -> {
                if (le != null) {
                    fail(epoch, tr("debug.error.launchFailed", msg(le)));
                }
            });
            fx(() -> {
                if (isCurrent(epoch, c)) {
                    setState(State.RUNNING);
                    listener.onDebuggeeRuntime(List.of(tool), runtimeWhere(file), programEnv);
                }
            });
        });
    }

    /** Finds the tool a JVM language's program runs with ({@code groovy}, {@code scala-cli}); a test supplies one. */
    private volatile java.util.function.Function<String, @Nullable String> jvmToolLocator =
            com.editora.run.JvmTools::locate;

    /** Test seam for {@link #jvmToolLocator}: a stand-in tool, so the launch can be checked without one installed. */
    void setJvmToolLocatorForTest(java.util.function.Function<String, @Nullable String> locator) {
        this.jvmToolLocator = locator == null ? com.editora.run.JvmTools::locate : locator;
    }

    /** How long a Scala CLI program may take to compile and connect back (a first run downloads the compiler). */
    private static final long SCALA_CLI_CONNECT_TIMEOUT_MS = 5 * 60_000;

    /** How long a language server may take to start a debug adapter: Metals compiles the build target first. */
    private static final java.time.Duration HOSTED_START_TIMEOUT = java.time.Duration.ofMinutes(10);

    /** The VM arguments set for the next launch, as arguments. */
    private List<String> jvmOptions() {
        List<String> list = vmArgList;
        return list != null ? list : com.editora.run.ProgramArgs.tokenize(vmArgs);
    }

    private static String readQuietly(Path file) {
        try {
            return java.nio.file.Files.readString(file);
        } catch (java.io.IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * Whether {@code file} is debugged by its language server's own adapter ({@link DapServerRegistry.Kind
     * #LANGUAGE_SERVER}) rather than the fallback: for Scala, a file of an sbt, Mill, Gradle or Maven build —
     * Metals has imported it and knows its classpath. A script, or a Scala CLI project, runs under Scala CLI.
     */
    static boolean serverDebugs(Path file, String language) {
        if (!"scala".equals(language)) {
            return true;
        }
        com.editora.run.ScalaRun.Build build = com.editora.run.ScalaRun.ownerOf(
                        file, null, java.nio.file.Files::isRegularFile)
                .build();
        return build == com.editora.run.ScalaRun.Build.SBT
                || build == com.editora.run.ScalaRun.Build.MILL
                || build == com.editora.run.ScalaRun.Build.OTHER;
    }

    /**
     * A debug adapter the file's language server starts ({@link HostedDebugAdapter}): Metals'
     * {@code debug-adapter-start} with the file, its arguments, VM arguments and environment compiles the build
     * target, starts scala-debug-adapter and answers its socket. The adapter runs the program and reports its
     * output; this client only connects, sends {@code launch} and the breakpoints.
     */
    private void startHosted(
            Path file,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch) {
        String language = spec.language();
        String command = HostedDebugAdapter.startCommand(language);
        Map<String, Object> params =
                HostedDebugAdapter.discovery(file.toUri().toString(), false, programArgs, jvmOptions(), env);
        fx(() -> {
            if (!isCurrent(epoch)) {
                return;
            }
            if (command == null || !lsp.isManaged(file)) {
                fail(epoch, tr("debug.error.hostedNoServer", file.getFileName().toString()));
                return;
            }
            onOutput(epoch, tr("debug.hosted.starting") + "\n", COMMAND_CATEGORY);
            executeCommand(file, command, List.of(params), HOSTED_START_TIMEOUT, (res, err) -> {
                if (!isCurrent(epoch)) {
                    return;
                }
                if (err != null) {
                    fail(epoch, tr("debug.error.sessionFailed", msg(err)));
                    return;
                }
                HostedDebugAdapter.Address address = HostedDebugAdapter.address(res);
                if (address == null) {
                    fail(epoch, tr("debug.error.noPort"));
                    return;
                }
                // The adapter (and the program it starts) live inside that server: pin it for the session.
                lspLease.run();
                lspLease = lsp.retain(file);
                DapClient c = new DapClient(sessionHost(epoch));
                c.setPaths(DapPaths.LOCAL);
                c.setBreakpoints(bps);
                c.setExceptionFilters(filters);
                if (!publishClient(epoch, c)) {
                    c.dispose();
                    return;
                }
                Map<String, Object> launch = new java.util.LinkedHashMap<>();
                launch.put("name", address.name().isBlank() ? file.getFileName().toString() : address.name());
                launch.put("noDebug", false);
                io.submit(() -> c.connect(address.port(), spec.adapterId()).whenComplete((caps, e) -> {
                    if (e != null) {
                        fail(epoch, tr("debug.error.connectFailed", msg(e)));
                        return;
                    }
                    if (!isCurrent(epoch, c)) {
                        c.dispose();
                        return;
                    }
                    c.launch(launch).whenComplete((v, le) -> {
                        if (le != null) {
                            fail(epoch, tr("debug.error.launchFailed", msg(le)));
                            return;
                        }
                        fx(() -> {
                            if (isCurrent(epoch, c) && state == State.STARTING) {
                                setState(State.RUNNING);
                            }
                        });
                    });
                }));
            });
        });
    }

    /**
     * A JavaScript launch planned by its caller instead of from the file alone — a {@code node} run configuration
     * (as {@code NodeRunPlan} runs it) or an npm script ({@code runtime} = the package manager, {@code program}
     * blank, js-debug attaching to the Node processes it starts).
     *
     * @param runtime the executable and its arguments before the program
     * @param program the script ({@code ""} for a package-manager launch)
     * @param args the program's arguments
     * @param cwd the folder it runs in
     * @param env variables set on top of the inherited ones
     */
    public record JsProgram(
            List<String> runtime, String program, List<String> args, Path cwd, Map<String, String> env) {
        public JsProgram {
            runtime = List.copyOf(runtime);
            program = program == null ? "" : program;
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(env));
        }
    }

    /** The planned launch for the next JavaScript session (null: plan it from the file); sticky across restart. */
    private volatile @Nullable JsProgram jsProgram;

    /** Sets {@link #jsProgram}; {@code null} for an ordinary launch of a file. */
    public void setJsProgram(@Nullable JsProgram program) {
        this.jsProgram = program;
    }

    /**
     * The Node.js that runs js-debug for a program on {@code programNode} ({@code version}): that one when it is
     * new enough, else the Node.js setting's when that is, else the newest nvm one or {@code node} on PATH — found
     * without the setting, so an old Node.js chosen in Settings does not stop debugging (review D101); null when
     * none is new enough. The program still runs on its own Node.
     */
    private @Nullable String adapterNodeFor(String programNode, @Nullable String version) {
        if (JsLaunch.runsAdapter(version)) {
            return programNode;
        }
        List<String> fallbacks = new ArrayList<>();
        fallbacks.add(adapterNode());
        fallbacks.addAll(adapterNodeFallbacks.get());
        return JsAdapterNode.choose(programNode, version, fallbacks, DapManager::nodeVersion);
    }

    /** Where js-debug's Node.js is looked for when the chosen one is too old; a test supplies its own. */
    private volatile Supplier<List<String>> adapterNodeFallbacks = JsAdapterNode::localFallbacks;

    /** Test seam for {@link #adapterNodeFallbacks}. */
    void setAdapterNodeFallbacksForTest(Supplier<List<String>> fallbacks) {
        this.adapterNodeFallbacks = fallbacks;
    }

    /** A started js-debug server: its process, the port it announced on this machine, and its stderr. */
    private record JsAdapter(Process process, int port, AdapterStderr stderr) {}

    /**
     * Starts js-debug on a port it picks itself and waits for the line announcing it ({@link #listeningPort}) —
     * as on a remote host. A guessed free port, then connect retries for about two seconds, lost to any slower
     * start (review D15). A server that dies first is reported with what it wrote to stderr; null then.
     */
    private @Nullable JsAdapter startJsAdapter(Path server, String node, long epoch) throws java.io.IOException {
        List<String> cmd = ProcessRunner.resolveExecutable(List.of(node, server.toString(), "0", "127.0.0.1"));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        ProcessRunner.applyUserEnv(pb);
        Process proc = BusyExecutable.start(pb);
        AdapterStderr stderr = AdapterStderr.drain(proc);
        jsAdapterStderr = stderr;
        int port = listeningPort(proc.getInputStream(), java.time.Duration.ofSeconds(20));
        if (port < 0) {
            boolean ended = false;
            try {
                ended = proc.waitFor(500, TimeUnit.MILLISECONDS); // let its last stderr lines arrive
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            com.editora.process.ProcessRegistry.killTree(proc);
            fail(
                    epoch,
                    tr(ended ? "debug.error.jsAdapterExited" : "debug.error.jsAdapterNoStart", node)
                            + AdapterStderr.suffix(stderr));
            return null;
        }
        return new JsAdapter(proc, port, stderr);
    }

    /**
     * Attaches the JavaScript debugger to a Node.js waiting for one on its inspector {@code port} on this machine —
     * an HTTP Client script started with {@code --inspect-brk} — which js-debug resumes once the breakpoints are set.
     * See {@link #startJsAttach(String, int, String, Path, List, boolean)}.
     */
    public void startJsAttach(int port, String name, Path cwd, List<String> skipFiles) {
        startJsAttach("127.0.0.1", port, name, cwd, skipFiles, true);
    }

    /**
     * Attaches the JavaScript debugger to the Node.js inspector at {@code host:port} (Debug ▸ Attach on a JavaScript
     * file, the HTTP Client's Debug Request). js-debug is started as for a launch and asked to {@code attach}
     * ({@link LaunchConfig#nodeAttach}); {@code continueOnAttach} resumes a program held by {@code --inspect-brk}
     * once the breakpoints are set. The session is called {@code name}; Stop detaches and leaves the program
     * running unless it was waiting for this debugger.
     */
    public void startJsAttach(
            String host, int port, String name, @Nullable Path cwd, List<String> skipFiles, boolean continueOnAttach) {
        if (managerClosed) {
            return;
        }
        if (!shared.enabled) {
            listener.onError(tr("debug.error.disabled"));
            return;
        }
        DapServerRegistry.DapServerSpec spec = DapServerRegistry.specFor("javascript");
        if (spec == null || !isLanguageAvailable("javascript")) {
            listener.onError(tr("debug.error.jsUnavailable"));
            return;
        }
        if (client != null) {
            stop(); // one session at a time
        }
        restartAction = () -> startJsAttach(host, port, name, cwd, skipFiles, continueOnAttach);
        debugFile = cwd;
        long epoch = beginSession();
        setState(State.STARTING);
        List<DapModels.FileBreakpoints> bps = shared.breakpointsSupplier.get();
        List<String> filters = filtersFor("javascript");
        Map<String, Object> attach =
                LaunchConfig.nodeAttach(spec.launchType(), name, host, port, skipFiles, continueOnAttach);
        jsClientConsole = false;
        startupTask = io.submit(() -> {
            try {
                Path server = shared.jsServer;
                if (server == null) {
                    fail(epoch, tr("debug.error.jsNotFound"));
                    return;
                }
                String attachNode = adapterNode();
                String runner = adapterNodeFor(attachNode, nodeVersion(attachNode));
                if (runner == null) {
                    fail(
                            epoch,
                            tr(
                                    "debug.error.jsNodeTooOld",
                                    String.valueOf(nodeVersion(attachNode)),
                                    attachNode,
                                    JsLaunch.MIN_ADAPTER_NODE_MAJOR));
                    return;
                }
                JsAdapter adapter = startJsAdapter(server, runner, epoch);
                if (adapter == null) {
                    return; // reported
                }
                attachSocket(
                        adapter.process(),
                        null,
                        adapter.port(),
                        spec,
                        bps,
                        filters,
                        epoch,
                        DapPaths.LOCAL,
                        attach,
                        false,
                        null);
            } catch (Exception e) {
                fail(epoch, tr("debug.error.startFailed", msg(e)));
            }
        });
    }

    /**
     * Wires a started socket adapter ({@code proc}, listening on this machine's {@code port} — through
     * {@code forward} for one on a remote host) to a new session and sends it {@code request} (a {@code launch} or
     * an {@code attach}). With {@code runsDebuggee} the adapter hands the program's command line back and Editora
     * starts it (standard input, all output, exit code). {@code runtime} is the launched program's Node command,
     * for the runtime chip (null for an attach).
     */
    private void attachSocket(
            Process proc,
            com.editora.process.@Nullable PortForward forward,
            int port,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch,
            DapPaths paths,
            Map<String, Object> request,
            boolean runsDebuggee,
            @Nullable List<String> runtime) {
        if (!isCurrent(epoch)) {
            com.editora.process.ProcessRegistry.killTree(proc);
            if (forward != null) {
                forward.close();
            }
            return;
        }
        boolean attach = "attach".equals(request.get("request"));
        DapClient c = new DapClient(sessionHost(epoch));
        c.setPaths(paths);
        c.setBreakpoints(bps);
        c.setExceptionFilters(filters);
        c.setRunsDebuggee(runsDebuggee);
        // Stop detaches from a program it attached to: vscode-js-debug ends it on terminateDebuggee (seen with 1.117).
        c.setTerminateOnDisconnect(!attach);
        c.setAdapterProcess(proc); // killed on dispose (it serves the socket)
        if (forward != null) {
            c.closeOnDispose(forward);
        }
        if (!publishClient(epoch, c)) {
            c.dispose();
            return;
        }
        c.connect(port, spec.adapterId()).whenComplete((caps, e) -> {
            if (e != null) {
                fail(epoch, tr("debug.error.jsConnect", msg(e)) + AdapterStderr.suffix(jsAdapterStderr));
                return;
            }
            if (!isCurrent(epoch)) {
                c.dispose();
                return;
            }
            (attach ? c.attach(request) : c.launch(request)).whenComplete((v, le) -> {
                if (le != null) {
                    fail(epoch, tr(attach ? "debug.error.attachFailed" : "debug.error.launchFailed", msg(le)));
                } else if (attach && Boolean.FALSE.equals(request.get("continueOnAttach"))) {
                    // A user's Node.js attach: nothing else says it worked, a running server prints nothing (D103).
                    onOutput(
                            epoch,
                            tr(
                                            "debug.js.attached",
                                            request.getOrDefault("address", "127.0.0.1") + ":" + request.get("port"))
                                    + "\n",
                            COMMAND_CATEGORY);
                }
            });
            fx(() -> {
                if (isCurrent(epoch, c)) {
                    setState(State.RUNNING);
                    if (runtime != null && !runtime.isEmpty()) {
                        Object cwd = request.get("cwd");
                        listener.onDebuggeeRuntime(
                                runtime, runtimeWhere(paths, cwd instanceof String s ? s : null), Map.of());
                    }
                }
            });
        });
    }

    // --- On a remote host ------------------------------------------------------------------------

    /** Languages a remote file is debugged in on its own host (Java through the jdtls running there). */
    public static boolean debuggableOnHost(String language) {
        DebugLanguage l = DebugLanguages.forLanguage(language); // TypeScript is debugged as JavaScript
        return l != null && l.debuggableOnHost();
    }

    /** Why {@code language} cannot be debugged on remote {@code file}'s host, or null when it can be tried. */
    private @Nullable String remoteProblem(Path file, String language) {
        if (!debuggableOnHost(language)) {
            return tr("debug.error.remoteLanguage");
        }
        if ("python".equals(language) ? !shared.pythonEnabled : !shared.jsEnabled) {
            return "python".equals(language) ? tr("debug.error.remotePythonOff") : tr("debug.error.remoteJsOff");
        }
        if (!com.editora.process.ExecTargets.forPath(file).isAvailable()) {
            return tr("debug.error.remoteNoCommands");
        }
        return null;
    }

    /**
     * The Python that runs debugpy on {@code file}'s host: the host's {@code dap.python} tool, else the
     * Debugging setting when it is a bare command name (a path names a file on this machine), else
     * {@code python3}.
     */
    private List<String> remotePython(Path file) {
        String tool = com.editora.process.ExecTargets.remoteTool(file, "dap.python");
        if (tool != null && !tool.isBlank()) {
            return DapServerRegistry.tokenize(tool);
        }
        List<String> configured = DapServerRegistry.interpreterArgv("python", shared.pythonCommand);
        if (!configured.isEmpty()
                && configured.get(0).indexOf('/') < 0
                && configured.get(0).indexOf('\\') < 0) {
            return configured;
        }
        return List.of(DapServerRegistry.DEFAULT_PYTHON_INTERPRETER);
    }

    /**
     * A configured interpreter command taken apart: a leading {@code env NAME=value …} becomes variables, the
     * next word the interpreter, the rest its own arguments. debugpy takes these three separately ({@code env},
     * {@code python}, {@code pythonArgs}); handed the whole command, it ran the program with {@code env}.
     */
    record HostPython(String executable, List<String> args, Map<String, String> env) {
        static HostPython parse(List<String> command) {
            int i = 0;
            Map<String, String> env = new java.util.LinkedHashMap<>();
            if (!command.isEmpty() && "env".equals(command.get(0))) {
                i = 1;
                while (i < command.size() && command.get(i).matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
                    String assignment = command.get(i++);
                    int eq = assignment.indexOf('=');
                    env.put(assignment.substring(0, eq), assignment.substring(eq + 1));
                }
            }
            String executable = i < command.size() ? command.get(i) : DapServerRegistry.DEFAULT_PYTHON_INTERPRETER;
            List<String> args =
                    i + 1 < command.size() ? List.copyOf(command.subList(i + 1, command.size())) : List.of();
            return new HostPython(executable, args, Map.copyOf(env));
        }

        /** The interpreter and its arguments, then {@code more}. */
        List<String> argv(List<String> more) {
            List<String> out = new ArrayList<>();
            out.add(executable);
            out.addAll(args);
            out.addAll(more);
            return out;
        }
    }

    /** debugpy on the host of {@code file}: asked for first, then run over an SSH channel as its stdio adapter. */
    private void startRemoteStdio(
            Path file,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch,
            PythonLaunch.@Nullable Target module)
            throws Exception {
        var target = com.editora.process.ExecTargets.forPath(file);
        Path dir = file.getParent();
        HostPython python = HostPython.parse(remotePython(file));
        List<String> probe = python.argv(List.of("-c", "import debugpy"));
        if (!ProcessRunner.run(dir, java.time.Duration.ofSeconds(20), probe, python.env())
                .ok()) {
            fail(epoch, tr("debug.error.remoteDebugpyMissing", python.executable(), target.id()));
            return;
        }
        if (!isCurrent(epoch)) {
            return;
        }
        // The program runs as Run runs it there (the project's venv on the host, its python tool, PYTHONPATH);
        // the adapter stays on the host's debugpy-capable Python.
        HostPython program = planProgram(file, target, false);
        if (module != null && "pytest".equals(module.module())) {
            module = pytestOrFallback(
                    module,
                    program,
                    epoch,
                    run -> ProcessRunner.run(dir, java.time.Duration.ofSeconds(20), run, program.env())
                            .ok());
            if (module == null) {
                return; // reported
            }
        }
        if (!isCurrent(epoch)) {
            return;
        }
        List<String> argv = python.argv(spec.adapterArgs()); // -m debugpy.adapter
        Process proc = target.start(
                com.editora.process.ExecSpec.of(argv, dir, python.env(), com.editora.process.ExecSpec.Locale.USER)
                        .withLongLived(true)
                        .withDiscardedOutput(false, true));
        PythonLaunch.Target what = module != null
                ? module
                : PythonLaunch.Target.script(target.toTargetPath(file), programArgs, target.toTargetPath(dir));
        onOutput(epoch, PythonLaunch.commandLine(program, what) + "\n", COMMAND_CATEGORY);
        // The host's own PYTHONPATH is not known here (null): the plan's is used, else it is left as the host has it.
        Map<String, Object> launch = PythonLaunch.request(what, program, List.of(), null, ":", false);
        attachStdio(proc, spec, bps, filters, epoch, DapPaths.on(target, file.getFileSystem()), launch, false, null);
    }

    /**
     * Finds {@code dapDebugServer.js} on the host: the {@code dap.js} tool ($1: the file, or a folder to
     * search), else where Editora's installer and mason put it (VS Code's js-debug has no DAP server entry). Prints the path; exits 1
     * when there is none, 2 when {@code node} is missing.
     */
    static final String FIND_JS_DEBUG = """
            command -v "$2" >/dev/null 2>&1 || exit 2
            if [ -n "$1" ]; then
              case "$1" in */dapDebugServer.js) [ -f "$1" ] && { printf '%s\n' "$1"; exit 0; } ;; esac
              d=$1; [ -f "$d" ] && d=${d%/*}
              set -- "$d"
            else
              set -- "$HOME/.editora/plugins/dap/javascript" "$HOME/.local/share/nvim/mason/packages/js-debug-adapter"
            fi
            for r in "$@"; do
              [ -d "$r" ] || continue
              f=$(find "$r" -maxdepth 6 -name dapDebugServer.js -type f 2>/dev/null | head -n 1)
              [ -n "$f" ] && { printf '%s\n' "$f"; exit 0; }
            done
            exit 1
            """;

    /** vscode-js-debug on the host: started on a free port there, reached through a tunnel from this machine. */
    private void startRemoteSocket(
            Path file,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            @Nullable List<String> filters,
            long epoch)
            throws Exception {
        jsClientConsole = false; // js-debug starts the program on the host
        var target = com.editora.process.ExecTargets.forPath(file);
        Path dir = file.getParent();
        String configured = com.editora.process.ExecTargets.remoteTool(file, "dap.js");
        String nodeTool = com.editora.process.ExecTargets.remoteTool(file, "node");
        String node = nodeTool == null || nodeTool.isBlank() ? spec.defaultInterpreter() : nodeTool.strip();
        ProcessRunner.Result found = ProcessRunner.run(
                dir,
                java.time.Duration.ofSeconds(20),
                List.of("sh", "-c", FIND_JS_DEBUG, "sh", configured == null ? "" : configured.strip(), node));
        String server = found.ok() ? found.out().strip() : "";
        if (server.isEmpty()) {
            fail(
                    epoch,
                    found.exit() == 2
                            ? tr("debug.error.remoteNodeMissing", target.id())
                            : tr("debug.error.remoteJsMissing", target.id()));
            return;
        }
        if (!isCurrent(epoch)) {
            return;
        }
        // The package root as the working folder and workspace, as on this machine (review D110): a compiled
        // project's source maps map, and process.cwd() is what a local Debug gets. Looked up on the host's files.
        Path projectRoot = jsProjectRoot;
        Path root = projectRoot != null && projectRoot.getFileSystem() == file.getFileSystem() ? projectRoot : null;
        Path remoteCwd = com.editora.run.NodeRuntimes.packageRoot(file, root, null, Files::isRegularFile)
                .orElse(dir);
        Path remoteWorkspace =
                root != null && file.toAbsolutePath().startsWith(root.toAbsolutePath()) ? root : remoteCwd;
        // Port 0: the host picks a free one and the server prints it; 127.0.0.1, so the tunnel's end is known.
        Process proc = target.start(com.editora.process.ExecSpec.of(
                        List.of(node, server, "0", "127.0.0.1"),
                        dir,
                        java.util.Map.of(),
                        com.editora.process.ExecSpec.Locale.USER)
                .withLongLived(true)
                .withDiscardedOutput(false, true));
        int remotePort = listeningPort(proc.getInputStream(), java.time.Duration.ofSeconds(20));
        if (remotePort < 0) {
            com.editora.process.ProcessRegistry.killTree(proc);
            fail(epoch, tr("debug.error.remoteJsNoStart", target.id()));
            return;
        }
        com.editora.process.PortForward forward;
        try {
            forward = target.forwardLocal("127.0.0.1", remotePort);
        } catch (java.io.IOException e) {
            com.editora.process.ProcessRegistry.killTree(proc);
            throw e;
        }
        attachSocket(
                proc,
                forward,
                forward.localPort(),
                spec,
                bps,
                filters,
                epoch,
                DapPaths.on(target, file.getFileSystem()),
                // js-debug starts the program on the host (Editora cannot start it there), so its output is read
                // with outputCapture "std"; its folder is the file's, which is what the host can be asked for.
                JsLaunch.request(
                        spec.launchType(),
                        target.toTargetPath(file),
                        target.toTargetPath(remoteCwd),
                        target.toTargetPath(remoteWorkspace),
                        List.of(node),
                        programArgs,
                        false),
                false,
                List.of(node));
    }

    /** Matches the line vscode-js-debug's server prints once it listens: {@code … listening at host:port}. */
    private static final java.util.regex.Pattern LISTENING =
            java.util.regex.Pattern.compile("listening at .*:(\\d+)\\s*$");

    /**
     * The port an adapter announces on {@code out} ({@link #LISTENING}), or -1 when it ends or says nothing
     * in {@code timeout}. Whatever it prints afterwards is read and dropped, so its channel never fills.
     */
    static int listeningPort(java.io.InputStream out, java.time.Duration timeout) {
        CompletableFuture<Integer> port = new CompletableFuture<>();
        Thread.ofVirtual().name("dap-adapter-stdout").start(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(out, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    var m = LISTENING.matcher(line);
                    if (!port.isDone() && m.find()) {
                        port.complete(Integer.parseInt(m.group(1)));
                    }
                }
            } catch (java.io.IOException | RuntimeException ended) {
                // the adapter is gone
            } finally {
                port.complete(-1);
            }
        });
        try {
            return port.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return -1;
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static @Nullable String cwdOf(Path file) {
        return file.getParent() == null ? null : file.getParent().toString();
    }

    /** Where a debuggee's runtime is asked its version: {@code file}'s folder, so its host; null is here. */
    private static @Nullable Path runtimeWhere(@Nullable Path file) {
        return file == null ? null : file.getParent();
    }

    /** The interpreter argv a debugpy {@code launch} runs the program with ({@code python} + {@code pythonArgs});
     *  empty when it names none and debugpy uses its own. */
    static List<String> pythonRuntime(Map<String, Object> launch) {
        if (!(launch.get("python") instanceof String python) || python.isBlank()) {
            return List.of();
        }
        List<String> argv = new ArrayList<>(List.of(python));
        if (launch.get("pythonArgs") instanceof List<?> args) {
            args.forEach(a -> argv.add(String.valueOf(a)));
        }
        return argv;
    }

    /** A launch body's {@code env} as strings (a null value, which unsets, stays null); empty when absent. */
    private static Map<String, String> stringMap(@Nullable Object env) {
        if (!(env instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return out;
    }

    /** Where a debuggee's runtime is asked its version: its launch folder, named as the adapter's machine names
     *  it (so its host); null is here. */
    private static @Nullable Path runtimeWhere(DapPaths paths, @Nullable String cwd) {
        return cwd == null || cwd.isBlank() ? null : paths.fromAdapter(cwd);
    }

    // --- Controls -------------------------------------------------------------------------------

    public void resume() {
        DapClient c = client;
        if (c == null) {
            return;
        }
        if (state == State.SUSPENDED && refuseWhileEvaluating()) {
            return;
        }
        long epoch = sessionEpoch;
        int threadId = currentThreadId;
        stepInFlight = false;
        stoppedThreads.remove(threadId);
        boolean wholeProgram = allSuspended && javaDebugSession();
        allSuspended = false;
        resumedSinceSuspendAll.clear();
        (wholeProgram ? c.resumeAll(threadId) : c.resume(threadId))
                .whenComplete((all, e) -> fx(() -> {
                    if (e == null && isCurrent(epoch, c)) {
                        afterThreadResumed(epoch, c, all == null || all);
                    }
                }));
        setState(State.RUNNING);
    }

    /**
     * One thread was resumed. When the adapter says the others were not ({@code allThreadsContinued=false})
     * and one of them is still stopped, show that one instead of reporting a running session: nothing else
     * would ever bring it back, and the program waits on it for good.
     */
    private void afterThreadResumed(long epoch, DapClient c, boolean allThreads) {
        if (allThreads) {
            stoppedThreads.clear();
            return;
        }
        if (state != State.RUNNING || stoppedThreads.isEmpty()) {
            return;
        }
        var next = stoppedThreads.entrySet().iterator().next();
        presentStop(epoch, c, next.getKey(), next.getValue(), false, false);
    }

    /** Pauses a running session: targets the current thread if the adapter reports it, else the first. A Java
     *  session suspends every thread (java-debug's {@code pauseAll}); debugpy pauses all threads by itself. The
     *  adapter answers with a {@code stopped(reason=pause)} event, which flows through onStopped. */
    public void pause() {
        DapClient c = client;
        if (c == null || state != State.RUNNING) {
            return;
        }
        c.threads().whenComplete((list, e) -> {
            if (client != c || e != null || list == null || list.isEmpty()) {
                return;
            }
            int id = list.get(0).id();
            for (DapModels.ThreadInfo t : list) {
                if (t.id() == currentThreadId) {
                    id = t.id();
                    break;
                }
            }
            if (javaDebugSession()) {
                c.pauseAll(id);
            } else {
                c.pause(id);
            }
        });
    }

    /**
     * The session's threads with whether each is suspended (callback on the FX thread): the Threads view. Empty with
     * no session.
     */
    public void threadStatuses(Consumer<List<DapModels.ThreadStatus>> cb) {
        threads(list -> cb.accept(threadStatuses(
                list,
                stoppedThreads,
                allSuspended,
                resumedSinceSuspendAll,
                state == State.SUSPENDED ? currentThreadId : -1)));
    }

    /**
     * Which of {@code threads} are suspended: those stopped since the last continue ({@code stopped}, with their
     * reason), the one shown ({@code shownId}, -1 when none is), and — the whole program suspended — every thread not
     * resumed on its own since. Pure.
     */
    static List<DapModels.ThreadStatus> threadStatuses(
            List<DapModels.ThreadInfo> threads,
            Map<Integer, String> stopped,
            boolean allSuspended,
            java.util.Set<Integer> resumedSinceSuspendAll,
            int shownId) {
        List<DapModels.ThreadStatus> out = new ArrayList<>();
        for (DapModels.ThreadInfo t : threads) {
            String stoppedFor = stopped.get(t.id());
            boolean paused = stoppedFor != null
                    || t.id() == shownId
                    || (allSuspended && !resumedSinceSuspendAll.contains(t.id()));
            String reason = stoppedFor != null ? stoppedFor : paused && t.id() != shownId ? "pause" : "";
            out.add(new DapModels.ThreadStatus(t.id(), t.name(), paused, reason, t.id() == shownId));
        }
        return out;
    }

    /**
     * Threads view → Pause Thread: suspends {@code threadId} alone (java-debug; debugpy suspends the whole program).
     * The adapter's {@code stopped} event then shows it like any stop.
     */
    public void pauseThread(int threadId) {
        DapClient c = client;
        if (c != null && state != State.INACTIVE && state != State.STARTING) {
            c.pause(threadId);
        }
    }

    /**
     * Threads view → Resume Thread: resumes {@code threadId} alone, the others staying as they are. When it is the
     * thread shown, the next still-suspended one is brought forward, as after Continue; when the adapter resumed the
     * whole program instead (debugpy), the session runs.
     */
    public void resumeThread(int threadId) {
        DapClient c = client;
        if (c == null || state == State.INACTIVE || state == State.STARTING) {
            return;
        }
        if (state == State.SUSPENDED && threadId == currentThreadId && refuseWhileEvaluating()) {
            return;
        }
        long epoch = sessionEpoch;
        boolean shown = state == State.SUSPENDED && threadId == currentThreadId;
        stoppedThreads.remove(threadId);
        if (allSuspended) {
            resumedSinceSuspendAll.add(threadId);
        }
        if (shown) {
            stepInFlight = false;
            setState(State.RUNNING);
        }
        c.resume(threadId)
                .whenComplete((all, e) -> fx(() -> {
                    if (e != null || !isCurrent(epoch, c)) {
                        return;
                    }
                    if (all == null || all) {
                        stoppedThreads.clear();
                        allSuspended = false;
                        resumedSinceSuspendAll.clear();
                        if (state == State.SUSPENDED) {
                            setState(State.RUNNING);
                        }
                    } else if (shown) {
                        afterThreadResumed(epoch, c, false);
                    }
                    listener.onThreadsChanged();
                }));
    }

    /**
     * Threads view → Show Frames on a suspended thread while no thread is shown (the one on screen was resumed on
     * its own): shows it as the stopped thread. A thread already shown, or with the session suspended, is selected
     * as from the thread dropdown instead (the caller's part). False when nothing was done.
     */
    public boolean showSuspendedThread(int threadId, String reason) {
        DapClient c = client;
        if (c == null || state != State.RUNNING) {
            return false;
        }
        stoppedThreads.put(threadId, reason == null ? "" : reason);
        presentStop(sessionEpoch, c, threadId, reason == null ? "" : reason, false, false);
        return true;
    }

    /** Lists the session's threads (callback on the FX thread). */
    public void threads(Consumer<List<DapModels.ThreadInfo>> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        c.threads()
                .whenComplete((list, e) -> fx(() -> {
                    if (client == c) {
                        cb.accept(list == null ? List.of() : list);
                    }
                }));
    }

    /** Whether the live session's adapter lists the sources its debuggee loaded (js-debug does). */
    public boolean listsLoadedSources() {
        DapClient c = client;
        return c != null && c.listsLoadedSources();
    }

    /** Whether the live session's adapter lists the modules its debuggee loaded (CodeLLDB, netcoredbg, debugpy). */
    public boolean listsModules() {
        DapClient c = client;
        return c != null && c.listsModules();
    }

    /** The sources the debuggee has loaded (callback on the FX thread; empty with no session, or on failure). */
    public void loadedSources(Consumer<List<DapModels.LoadedSource>> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        c.loadedSources()
                .whenComplete((list, e) -> fx(() -> {
                    if (client == c) {
                        cb.accept(list == null ? List.of() : list);
                    }
                }));
    }

    /** The modules the debuggee has loaded (callback on the FX thread; empty with no session, or on failure). */
    public void modules(Consumer<List<DapModels.ModuleInfo>> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        c.modules()
                .whenComplete((list, e) -> fx(() -> {
                    if (client == c) {
                        cb.accept(list == null ? List.of() : list);
                    }
                }));
    }

    /**
     * Sets the breakpoints of {@code source}, code the debugger has only by reference, for this session (callback on the
     * FX thread with one status per line; empty with no session).
     */
    public void updateReferenceBreakpoints(
            DapModels.LoadedSource source,
            List<DapModels.LineBreakpoint> lines,
            Consumer<List<DapModels.BreakpointStatus>> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        c.setReferenceBreakpoints(source, lines)
                .whenComplete((statuses, e) -> fx(() -> {
                    if (client == c) {
                        cb.accept(statuses == null ? List.of() : statuses);
                    }
                }));
    }

    /**
     * The text of a loaded source that is not a file, as the adapter gives it (callback on the FX thread: the text,
     * or null when the adapter could not give it).
     */
    public void sourceContent(DapModels.LoadedSource source, Consumer<@Nullable String> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept(null);
            return;
        }
        c.sourceContent(source).whenComplete((text, e) -> fx(() -> cb.accept(e == null ? text : null)));
    }

    /** Switches the inspected thread and loads its call stack (callback on the FX thread). */
    public void selectThread(int threadId, Consumer<List<DapModels.StackFrameInfo>> cb) {
        currentThreadId = threadId;
        stackTrace(threadId, cb);
    }

    /** While a run-to-cursor temp breakpoint is planted: the file holding it (re-sent clean on stop). */
    private @Nullable Path tempBreakpointFile;

    /** Resumes and stops at {@code line} (0-based) in {@code file} via a temporary breakpoint that is
     *  removed on the next stop (or on termination). Call on the FX thread while SUSPENDED. */
    public void runToCursor(Path file, int line) {
        DapClient c = client;
        if (c == null || state != State.SUSPENDED || file == null) {
            return;
        }
        tempBreakpointFile = file;
        c.sendSetBreakpoints(adapterBreakpoint(withTempLine(shared.breakpointsSupplier.get(), file, line)));
        resume();
    }

    /** Files whose breakpoints Force Run to Cursor took away for the trip; restored at the next stop (FX thread). */
    private List<Path> forceRunMuted = List.of();

    /** Whether Force Run to Cursor took the method breakpoints and field watchpoints away for the trip (FX thread). */
    private boolean forceRunMutedMembers;

    /**
     * Force Run to Cursor (IntelliJ, Visual Studio): runs to {@code line} (0-based) of {@code file} with every
     * breakpoint set aside, so none on the way stops the program. Exception breakpoints still stop it. The next stop,
     * wherever it is, puts the breakpoints back. FX thread, while SUSPENDED.
     */
    public void forceRunToCursor(Path file, int line) {
        DapClient c = client;
        if (c == null || state != State.SUSPENDED || file == null) {
            return;
        }
        List<Path> muted = new ArrayList<>();
        for (DapModels.FileBreakpoints fb : shared.breakpointsSupplier.get()) {
            if (fb.file() != null
                    && !fb.breakpoints().isEmpty()
                    && !com.editora.config.PathKeys.samePath(fb.file(), file)) {
                muted.add(fb.file());
                c.sendSetBreakpoints(adapterBreakpoint(new DapModels.FileBreakpoints(fb.file(), List.of())));
            }
        }
        forceRunMuted = List.copyOf(muted);
        if (!shared.memberBreakpoints.isEmpty()) {
            forceRunMutedMembers = true; // method entries and field writes on the way must not stop it either
            c.setMemberBreakpoints(List.of());
        }
        tempBreakpointFile = file;
        c.sendSetBreakpoints(adapterBreakpoint(
                new DapModels.FileBreakpoints(file, List.of(new DapModels.LineBreakpoint(line, null, null)))));
        resume();
    }

    /** Whether the connected adapter supports Jump to Line ({@code gotoTargets}/{@code goto}) —
     *  debugpy does; java-debug and vscode-js-debug currently do not. */
    public boolean supportsJumpToLine() {
        DapClient c = client;
        return c != null && c.supportsGotoTargets();
    }

    /**
     * Jump to Line (the IntelliJ plugin's move-the-execution-pointer): teleports the suspended thread
     * to {@code line} (0-based) of {@code file} <em>without executing</em> the code in between. The
     * adapter's {@code stopped(reason=goto)} event then refreshes the UI like any stop. {@code onError}
     * is called on the FX thread with "" when the line has no valid target, else the failure message.
     */
    public void jumpToLine(Path file, int line, Consumer<String> onError) {
        DapClient c = client;
        if (c == null || state != State.SUSPENDED || file == null) {
            return;
        }
        int threadId = currentThreadId;
        c.gotoTargets(file, line)
                .whenComplete((ids, e) -> fx(() -> {
                    if (client != c) {
                        return;
                    }
                    if (e != null) {
                        onError.accept(msg(e));
                        return;
                    }
                    if (ids == null || ids.isEmpty()) {
                        onError.accept(""); // no jump target on that line
                        return;
                    }
                    c.gotoTarget(threadId, ids.get(0)).whenComplete((v, e2) -> {
                        if (e2 != null) {
                            fx(() -> {
                                if (client == c) {
                                    onError.accept(msg(e2));
                                }
                            });
                        }
                    });
                }));
    }

    /**
     * The file's existing breakpoints plus a temporary plain one at {@code line}. A breakpoint already on that
     * line is made plain for the trip: left as it was, a condition that was false or a logpoint (which never
     * suspends) let the program run past the cursor. {@link #clearTempBreakpoint} restores it. Pure — tested.
     */
    static DapModels.FileBreakpoints withTempLine(List<DapModels.FileBreakpoints> all, Path file, int line) {
        List<DapModels.LineBreakpoint> lines = new ArrayList<>();
        for (DapModels.FileBreakpoints fb : all == null ? List.<DapModels.FileBreakpoints>of() : all) {
            if (com.editora.config.PathKeys.samePath(fb.file(), file)) {
                for (DapModels.LineBreakpoint lb : fb.breakpoints()) {
                    if (lb.line() != line) {
                        lines.add(lb);
                    }
                }
            }
        }
        lines.add(new DapModels.LineBreakpoint(line, null, null));
        return new DapModels.FileBreakpoints(file, lines);
    }

    /** Restores the real breakpoint set after a run-to-cursor temp breakpoint did its job (FX thread). */
    private void clearTempBreakpoint() {
        Path f = tempBreakpointFile;
        tempBreakpointFile = null;
        List<Path> muted = forceRunMuted;
        forceRunMuted = List.of();
        DapClient c = client;
        if (c != null && forceRunMutedMembers) {
            c.setMemberBreakpoints(shared.memberBreakpoints);
        }
        forceRunMutedMembers = false;
        if (c != null && !muted.isEmpty()) {
            for (Path file : muted) {
                List<DapModels.LineBreakpoint> real = new ArrayList<>();
                for (DapModels.FileBreakpoints fb : shared.breakpointsSupplier.get()) {
                    if (com.editora.config.PathKeys.samePath(fb.file(), file)) {
                        real.addAll(fb.breakpoints());
                    }
                }
                c.sendSetBreakpoints(adapterBreakpoint(new DapModels.FileBreakpoints(file, real)));
            }
        }
        if (f == null || c == null) {
            return;
        }
        List<DapModels.LineBreakpoint> real = new ArrayList<>();
        for (DapModels.FileBreakpoints fb : shared.breakpointsSupplier.get()) {
            if (com.editora.config.PathKeys.samePath(fb.file(), f)) {
                real.addAll(fb.breakpoints());
            }
        }
        c.sendSetBreakpoints(adapterBreakpoint(new DapModels.FileBreakpoints(f, real)));
    }

    public void stepOver() {
        step(DapClient::next);
    }

    public void stepInto() {
        step(DapClient::stepIn);
    }

    public void stepOut() {
        step(DapClient::stepOut);
    }

    /** Step Over one machine instruction (the Disassembly view's step), where the adapter steps that finely. */
    public void stepOverInstruction() {
        step((c, threadId) -> c.next(threadId, true));
    }

    /** Step Into one machine instruction (into a call), where the adapter steps that finely. */
    public void stepIntoInstruction() {
        step((c, threadId) -> c.stepIn(threadId, true));
    }

    /** Whether the session's adapter steps one machine instruction at a time. */
    public boolean supportsInstructionStepping() {
        DapClient c = client;
        return c != null && c.supportsInstructionStepping();
    }

    /** Whether the session's adapter disassembles (CodeLLDB, Delve). */
    public boolean supportsDisassemble() {
        DapClient c = client;
        return c != null && c.supportsDisassemble();
    }

    /** Whether the session's adapter reads the program's memory. */
    public boolean supportsReadMemory() {
        DapClient c = client;
        return c != null && c.supportsReadMemory();
    }

    /**
     * {@code count} instructions around {@code memoryReference} from {@code instructionOffset} on (to {@code cb} on the
     * FX thread; empty when the adapter could not, or the session ended).
     */
    public void disassemble(
            String memoryReference, int instructionOffset, int count, Consumer<List<DapModels.Instruction>> cb) {
        DapClient c = client;
        if (c == null || !c.supportsDisassemble()) {
            cb.accept(List.of());
            return;
        }
        c.disassemble(memoryReference, instructionOffset, count)
                .whenComplete((list, e) -> fx(() -> {
                    if (client == c) {
                        cb.accept(e != null || list == null ? List.of() : list);
                    }
                }));
    }

    /**
     * {@code count} bytes at {@code memoryReference} + {@code offset} (to {@code cb} on the FX thread; null when they
     * could not be read, with why to {@code failed}).
     */
    public void readMemory(
            String memoryReference,
            long offset,
            int count,
            Consumer<DapModels.MemoryBlock> cb,
            Consumer<String> failed) {
        DapClient c = client;
        if (c == null || !c.supportsReadMemory()) {
            failed.accept("");
            return;
        }
        c.readMemory(memoryReference, offset, count)
                .whenComplete((block, e) -> fx(() -> {
                    if (client != c) {
                        return;
                    }
                    if (e != null || block == null) {
                        failed.accept(e == null ? "" : msg(e));
                    } else {
                        cb.accept(block);
                    }
                }));
    }

    /**
     * The memory reference of {@code expression}'s value in frame {@code frameId} (to {@code cb} on the FX thread; null
     * when it has none or could not be evaluated).
     */
    public void memoryReferenceOf(String expression, int frameId, Consumer<@Nullable String> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept(null);
            return;
        }
        c.memoryReferenceOf(expression, frameId)
                .whenComplete((ref, e) -> fx(() -> {
                    if (client == c) {
                        cb.accept(e != null ? null : ref);
                    }
                }));
    }

    /**
     * Sends a step for the current thread. Once the adapter <em>acknowledges</em> it the thread is running
     * again until the next stop, exactly as after {@link #resume()}: leaving the state SUSPENDED kept the
     * execution line, inline values and frame list of the previous stop on screen — and offered
     * Step/Evaluate against frames that no longer exist — for as long as the step took, which for a step
     * over a blocking call is indefinitely. It also made {@link #pause()} a no-op during that time.
     *
     * <p>A step the adapter <em>refuses</em> (java-debug: "the thread is not suspended", when another thread
     * was picked in the selector) changes nothing: the debuggee is still where it was, so the session stays
     * SUSPENDED with its controls usable and the adapter's message is reported.
     */
    private void step(
            java.util.function.BiFunction<DapClient, Integer, java.util.concurrent.CompletableFuture<Void>> request) {
        DapClient c = client;
        if (c == null || state != State.SUSPENDED || stepPendingEpoch == sessionEpoch) {
            return;
        }
        if (refuseWhileEvaluating()) {
            return;
        }
        long epoch = sessionEpoch;
        int threadId = currentThreadId;
        int stops = stopCount;
        stepPendingEpoch = epoch;
        request.apply(c, threadId)
                .whenComplete((v, e) -> fx(() -> {
                    if (stepPendingEpoch == epoch) {
                        stepPendingEpoch = -1;
                    }
                    if (!isCurrent(epoch, c)) {
                        return;
                    }
                    if (e != null) {
                        listener.onError(msg(
                                e instanceof java.util.concurrent.CompletionException && e.getCause() != null
                                        ? e.getCause()
                                        : e));
                        return;
                    }
                    if (stopCount != stops || state != State.SUSPENDED) {
                        return; // the step has already ended in a new stop (or the session moved on)
                    }
                    stoppedThreads.remove(threadId);
                    stepInFlight = true;
                    setState(State.RUNNING);
                }));
    }

    public void stop() {
        Future<?> startup = startupTask;
        startupTask = null;
        if (startup != null) {
            startup.cancel(true);
        }
        DapClient c;
        synchronized (sessionLock) {
            sessionEpoch++;
            c = client;
            client = null;
        }
        killDebuggee(); // disconnect(terminateDebuggee) does not end a program the adapter did not start
        if (c != null) {
            // Ended for us now; the wait for the adapter's answer and the close happen off the FX thread.
            c.disposeInBackground();
        }
        killAdapterProgram(); // an adapter that is gone cannot end the program it launched
        releaseCompilationDirectory();
        releaseExitWrapper();
        setState(State.INACTIVE);
    }

    // --- the program the adapter launched (java-debug's processid) ------------------------------------

    /**
     * The program java-debug launched itself (program console off), from its {@code processid} event; null when
     * Editora started the program ({@link #debuggee}), for an attach, or on another machine. Ended with the
     * session: when the adapter is still there its {@code disconnect} has already done so and this finds
     * nothing alive, but when jdtls crashed the program was left running with no way to stop it.
     */
    private @Nullable ProcessHandle adapterProgram;

    /** Records the program the adapter of session {@code epoch} launched (FX thread). */
    private void onProcessId(long epoch, long pid) {
        if (!isCurrent(epoch) || debugFile == null || !com.editora.process.ExecTargets.runsHere(debugFile)) {
            return; // a pid on another machine names some other process here
        }
        adapterProgram = ProcessHandle.of(pid).orElse(null);
    }

    /** Ends the program the adapter launched, with its children, if it is still running (FX thread). */
    private void killAdapterProgram() {
        ProcessHandle program = adapterProgram;
        adapterProgram = null;
        if (program != null && program.isAlive()) { // isAlive also checks the start time: never a reused pid
            program.descendants().forEach(ProcessHandle::destroyForcibly);
            program.destroyForcibly();
        }
    }

    /** The session whose adapter announced it was done ({@code terminated}); its transport closing is normal. */
    private volatile long terminatedEpoch = -1;

    /**
     * The adapter's transport closed. After a {@code terminated} event that is how a session ends. Without one,
     * the adapter is gone while its program may still run — jdtls crashed: the program resumed the moment its
     * debugger connection dropped, and used to run on for the 3 s grace an ordinary end gets (or for good, when
     * the adapter had launched it). End it at once and say why the session ended.
     */
    private void onTransportClosed(long epoch) {
        fx(() -> {
            if (!isCurrent(epoch)) {
                return;
            }
            if (terminatedEpoch == epoch) {
                return; // the terminated event is ending it (or has ended it) already
            }
            Debuggee program = debuggee;
            ProcessHandle launched = adapterProgram;
            boolean programRunning =
                    (program != null && program.isRunning()) || (launched != null && launched.isAlive());
            killDebuggee();
            killAdapterProgram();
            if (programRunning) {
                outputPump.flush();
                listener.onAdapterLost();
            } else if (state == State.STARTING && jsSession) {
                // js-debug died before the program ran: ending quietly showed "Starting…" then "Not debugging"
                // and nothing else (review D4) — say so, with what it wrote to stderr.
                listener.onError(tr("debug.error.jsAdapterDied") + AdapterStderr.suffix(jsAdapterStderr));
            }
            endSession(epoch);
        });
    }

    /** Final per-window shutdown: invalidates the session, stops transports, and releases the connect worker. */
    public void shutdown() {
        if (managerClosed) {
            return;
        }
        managerClosed = true;
        stop();
        listener = noopListener();
        io.shutdownNow();
    }

    public void restart() {
        Runnable r = restartAction;
        stop();
        if (r != null) {
            r.run();
        }
    }

    // --- Inspection (results marshaled to FX) ---------------------------------------------------

    public void stackTrace(int threadId, Consumer<List<DapModels.StackFrameInfo>> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        c.stackTrace(threadId)
                .whenComplete((frames, e) -> fx(() -> {
                    if (client == c) {
                        cb.accept(originalFrames(frames));
                    }
                }));
    }

    public void scopes(int frameId, Consumer<List<DapModels.ScopeInfo>> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        c.scopes(frameId)
                .whenComplete((scopes, e) -> fx(() -> {
                    if (client == c) {
                        cb.accept(scopes == null ? List.of() : scopes);
                    }
                }));
    }

    public void variables(int ref, Consumer<List<DapModels.VariableInfo>> cb) {
        variables(ref, null, 0, 0, cb);
    }

    /** One page of a container's children — see {@link DapClient#variables(int, String, int, int)}. */
    public void variables(
            int ref, @Nullable String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        // One request per stop however many views ask (the tree and the inline values both read Locals: B207).
        lazyValues
                .reply(ref + "/" + filter + "/" + start + "/" + count, () -> c.variables(ref, filter, start, count))
                .thenCompose(vars -> lazyValues.resolveAll(vars, child -> c.variables(child, null, 0, 0)))
                .thenCompose(vars -> repairStrings(c, vars))
                .whenComplete((vars, e) -> fx(() -> {
                    if (client == c) {
                        cb.accept(vars == null ? List.of() : vars);
                    }
                }));
    }

    /**
     * Loads one row the stop's budget left unloaded ({@link DapModels.ValueNote#NOT_LOADED}): a click on it. {@code
     * cb} gets the resolved row (FX thread), or the row as it was when the adapter did not answer.
     */
    public void loadValue(DapModels.VariableInfo row, Consumer<DapModels.VariableInfo> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept(row);
            return;
        }
        lazyValues
                .resolveNow(row, child -> c.variables(child, null, 0, 0))
                .thenCompose(r -> repairStrings(c, List.of(r)))
                .whenComplete((rows, e) -> fx(() -> {
                    if (client == c) {
                        cb.accept(rows == null || rows.isEmpty() ? row : rows.get(0));
                    }
                }));
    }

    /**
     * Forgets the values this stop has fetched, so the next look asks again: a console evaluation or Set Value
     * may have changed them (B205).
     */
    public void forgetValues() {
        lazyValues.clear();
    }

    public boolean hexValues() {
        return shared.hexValues;
    }

    /**
     * Shows numbers in hexadecimal (or not) from the next request on, in this session and the next ones; the caller
     * fetches the values again. FX thread.
     */
    public void setHexValues(boolean on) {
        shared.hexValues = on;
        DapClient c = client;
        if (c != null) {
            c.setHexValues(on);
        }
        lazyValues.clear(); // the answers so far are in the other format
    }

    /** Lazy rows resolved during the current stop (B101, B201); forgotten whenever the program runs. */
    private final LazyValues lazyValues = new LazyValues();

    // --- strings java-debug garbles (follow-up 4: emoji arrive as two wrong characters) -------------------

    /**
     * Whether the session is java-debug's: its string values may need {@link JdwpStringRepair}, and it pauses and
     * resumes threads one at a time unless asked for all of them.
     */
    private boolean javaDebugSession() {
        return "java".equals(sessionLanguage);
    }

    private CompletableFuture<List<DapModels.VariableInfo>> repairStrings(
            DapClient c, List<DapModels.VariableInfo> vars) {
        return javaDebugSession()
                ? JdwpStringRepair.repairAll(c, vars).thenApply(JavaValueText::tidyAll) // B108: lambdas
                : CompletableFuture.completedFuture(vars);
    }

    private CompletableFuture<DapModels.EvalResult> repairString(DapClient c, DapModels.EvalResult result) {
        return javaDebugSession()
                ? JdwpStringRepair.repair(c, result).thenApply(JavaValueText::tidy) // B108: lambdas
                : CompletableFuture.completedFuture(result);
    }

    public void evaluate(String expression, int frameId, String context, Consumer<ConsoleEvaluation> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept(ConsoleEvaluation.value(""));
            return;
        }
        boolean console = "repl".equals(context);
        if (console) {
            consoleEvaluations++;
        }
        c.evaluateFull(expression, frameId, context) // the full answer: a string result can be repaired from it
                .thenCompose(full -> repairString(c, full))
                .whenComplete((full, e) -> fx(() -> {
                    if (console && client == c) {
                        consoleEvaluations = Math.max(0, consoleEvaluations - 1);
                    }
                    if (client == c) {
                        String r = full == null ? null : full.result();
                        cb.accept(
                                e != null
                                        ? ConsoleEvaluation.failure(isTimeout(e) ? stillEvaluating() : msg(e))
                                        : ConsoleEvaluation.value(r == null ? "" : r));
                    }
                }));
    }

    /** Console evaluations the adapter has not answered yet (FX thread); reset with each session. */
    private int consoleEvaluations;

    /**
     * Whether an expression typed in the Debug console is still being evaluated. The program runs it on the
     * stopped thread, and the adapter takes the next request only when it is done: a Step pressed meanwhile was
     * queued there and ran many seconds later, with nothing shown in between.
     */
    public boolean evaluationRunning() {
        return consoleEvaluations > 0;
    }

    /** Refuses a control while a console evaluation runs, saying why (FX thread); true when refused. */
    private boolean refuseWhileEvaluating() {
        String toString = lazyValues.stuck();
        if (toString != null) {
            // java-debug runs a toString() on the stopped thread and takes no other request until it returns: a
            // step sent now would wait for it, and one that never returns would never step (review 3, B201).
            listener.onStatus(tr("status.debug.toStringRunning", toString));
            return true;
        }
        if (!evaluationRunning()) {
            return false;
        }
        listener.onStatus(tr("status.debug.evaluationRunning"));
        return true;
    }

    private static boolean isTimeout(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** What a console evaluation that timed out says: it has not failed, the program is still running it. */
    static String stillEvaluating() {
        return tr("debug.eval.stillRunning", DapClient.requestTimeoutSeconds());
    }

    /** Full evaluate (result + expandable reference + type) for watches and the hover value popup.
     *  Failures deliver an {@code EvalResult} with the error text and no children. */
    public void evaluateFull(String expression, int frameId, String context, Consumer<DapModels.EvalResult> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept(new DapModels.EvalResult("", 0, null));
            return;
        }
        // Code the user asked to run (the evaluate field, the Evaluate window, Quick Evaluate) holds the stopped
        // thread as a console evaluation does: a Step meanwhile is refused, saying why, rather than queued.
        boolean code = "repl".equals(context);
        if (code) {
            consoleEvaluations++;
        }
        c.evaluateFull(expression, frameId, context)
                .thenCompose(r -> repairString(c, r))
                .whenComplete((r, e) -> fx(() -> {
                    if (code && client == c) {
                        consoleEvaluations = Math.max(0, consoleEvaluations - 1);
                    }
                    if (client == c) {
                        cb.accept(
                                e != null
                                        ? DapModels.EvalResult.failure(
                                                code && isTimeout(e) ? stillEvaluating() : msg(e))
                                        : (r == null ? new DapModels.EvalResult("", 0, null) : r));
                    }
                }));
    }

    /** Hover evaluate (context "hover"): failures deliver {@code null} — hovering a non-variable
     *  identifier (keyword, type name) is normal and must show nothing, not an error. */
    public void evaluateHover(String expression, int frameId, Consumer<String> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept(null);
            return;
        }
        c.evaluateFull(expression, frameId, "hover")
                .thenCompose(r -> repairString(c, r))
                .whenComplete((r, e) -> fx(() -> {
                    if (client == c) {
                        cb.accept(e != null || r == null ? null : r.result());
                    }
                }));
    }

    /**
     * Sets a variable. {@code cb} gets the value the adapter reports back — only when the adapter accepted it;
     * a refusal (wrong type, a final field, an unparsable value) goes to {@link Listener#onError} and leaves
     * {@code cb} uncalled, so the caller never shows the typed text as if it were the variable's new value.
     */
    public void setVariable(int ref, String name, String value, Consumer<String> cb) {
        setVariable(ref, name, value, null, -1, cb);
    }

    /**
     * {@link #setVariable(int, String, String, Consumer)}, and when the adapter cannot set a variable of that type
     * (java-debug sets only primitives and Strings: an {@code Integer}, a record or an enum failed with "Set value
     * is not supported yet for type java.lang.Integer"), assigns it instead by evaluating {@code assignTo = value}
     * in frame {@code frameId}, as IntelliJ does (review 3, B208). {@code assignTo} null: no such fallback.
     */
    public void setVariable(
            int ref, String name, String value, @Nullable String assignTo, int frameId, Consumer<String> cb) {
        DapClient c = client;
        if (c == null) {
            return; // no session: nothing was set
        }
        c.setVariable(ref, name, value)
                .whenComplete((r, e) -> fx(() -> {
                    if (client != c) {
                        return;
                    }
                    if (e == null) {
                        cb.accept(r == null ? value : r);
                    } else if (SetValueText.unsupportedType(msg(e)) && assignTo != null && frameId >= 0) {
                        assign(c, name, SetValueText.assignment(assignTo, value), frameId, cb);
                    } else {
                        listener.onError(
                                SetValueText.unsupportedType(msg(e)) ? tr("debug.setValue.unsupported", name) : msg(e));
                    }
                }));
    }

    private void assign(DapClient c, String name, String assignment, int frameId, Consumer<String> cb) {
        c.evaluateFull(assignment, frameId, "repl")
                .thenCompose(r -> repairString(c, r))
                .whenComplete((r, e) -> fx(() -> {
                    if (client != c) {
                        return;
                    }
                    if (e != null || r == null) {
                        listener.onError(tr("debug.setValue.failed", name, e == null ? "" : msg(e)));
                    } else {
                        cb.accept(r.result());
                    }
                }));
    }

    /**
     * The method breakpoints and field watchpoints in force: the next session starts with them, and a live one is
     * sent them at once. Empty while breakpoints are muted.
     */
    public void setMemberBreakpoints(List<DapModels.MemberBreakpoint> breakpoints) {
        shared.memberBreakpoints = breakpoints == null ? List.of() : List.copyOf(breakpoints);
        DapClient c = client;
        if (c != null && state != State.INACTIVE) {
            c.setMemberBreakpoints(shared.memberBreakpoints);
        }
    }

    /** Whether the live session's adapter can watch a field (a DAP data breakpoint). */
    public boolean supportsDataBreakpoints() {
        DapClient c = client;
        return c != null && state != State.INACTIVE && c.supportsDataBreakpoints();
    }

    /**
     * What a field watchpoint on variable {@code name} of container {@code variablesReference} would watch, to
     * {@code cb} on the FX thread: empty when the adapter can watch nothing there.
     */
    public void dataBreakpointInfo(
            int variablesReference,
            String name,
            java.util.function.Consumer<java.util.Optional<DapModels.DataBreakpointTarget>> cb) {
        DapClient c = client;
        if (c == null || state == State.INACTIVE || variablesReference <= 0) {
            cb.accept(java.util.Optional.empty());
            return;
        }
        c.dataBreakpointInfo(variablesReference, name)
                .whenComplete((r, e) -> fx(() -> {
                    if (client == c) {
                        cb.accept(e != null || r == null ? java.util.Optional.empty() : r);
                    }
                }));
    }

    /** (Re)sends a file's breakpoints to the live adapter (after the user toggled one while running). */
    public void updateBreakpoints(DapModels.FileBreakpoints fb) {
        if (client != null && state != State.INACTIVE) {
            client.sendSetBreakpoints(adapterBreakpoint(fb));
        }
    }

    // --- DAP events (launcher reader thread → FX), reached only through sessionHost(epoch) -------

    private void onStopped(long epoch, int threadId, String reason, boolean allThreadsStopped) {
        DapClient c = client;
        if (c == null || !isCurrent(epoch, c)) {
            return;
        }
        presentStop(epoch, c, threadId, reason, true, allThreadsStopped);
    }

    /**
     * Shows {@code threadId} as the stopped thread. {@code event} is a stop the adapter just reported;
     * otherwise it is a thread that was already stopped and is being brought forward because the one on
     * screen was resumed — which is dropped if a real stop arrives first, and must not consume a
     * run-to-cursor temp breakpoint that the resumed thread has yet to reach.
     */
    private void presentStop(
            long epoch, DapClient c, int threadId, String reason, boolean event, boolean allThreadsStopped) {
        c.stackTrace(threadId)
                .whenComplete((frames, e) -> fx(() -> {
                    if (!isCurrent(epoch, c)) {
                        return;
                    }
                    if (e != null) {
                        LOG.log(Level.WARNING, "stackTrace failed for thread " + threadId, e);
                    }
                    if (event) {
                        if (allThreadsStopped) {
                            stoppedThreads.clear(); // one stop stands for all of them, and so will one continue
                            allSuspended = true;
                            resumedSinceSuspendAll.clear();
                        }
                        resumedSinceSuspendAll.remove(threadId);
                        stoppedThreads.put(threadId, reason == null ? "" : reason);
                        clearTempBreakpoint(); // a run-to-cursor temp breakpoint is one-shot
                    } else if (state != State.RUNNING || !stoppedThreads.containsKey(threadId)) {
                        return;
                    }
                    stopCount++;
                    stepInFlight = false;
                    currentThreadId = threadId;
                    lazyValues.newStop("pause".equals(stoppedThreads.get(threadId))); // B202
                    state = State.SUSPENDED;
                    listener.onState(State.SUSPENDED);
                    listener.onStopped(threadId, reason, originalFrames(frames));
                    if ("exception".equals(reason)) {
                        reportException(epoch, c, threadId, stopCount);
                    }
                }));
    }

    /** Asks which exception {@code threadId} stopped on and reports it, unless the stop has ended meanwhile. */
    private void reportException(long epoch, DapClient c, int threadId, int stop) {
        c.exceptionInfo(threadId)
                .whenComplete((info, e) -> fx(() -> {
                    if (info != null
                            && !info.isEmpty()
                            && isCurrent(epoch, c)
                            && state == State.SUSPENDED
                            && stopCount == stop
                            && currentThreadId == threadId) {
                        listener.onExceptionInfo(threadId, info);
                    }
                }));
    }

    private void onBreakpointStatus(long epoch, Path file, List<DapModels.BreakpointStatus> statuses, boolean whole) {
        fx(() -> {
            if (!isCurrent(epoch) || file == null) {
                return;
            }
            SourceAlias alias = activeSourceAlias(); // a compiled copy stands in for the file the user sees
            listener.onBreakpointStatus(
                    alias != null && alias.compiled().equals(file) ? alias.original() : file, statuses, whole);
        });
    }

    private void onNotice(long epoch, String message, boolean error) {
        fx(() -> {
            if (isCurrent(epoch)) {
                outputPump.flush(); // keep it after the program output that preceded it
                listener.onNotice(message, error);
            }
        });
    }

    private void onContinued(long epoch, int threadId, boolean allThreads) {
        fx(() -> {
            if (!isCurrent(epoch)) {
                return;
            }
            if (allThreads) {
                stoppedThreads.clear();
                allSuspended = false;
                resumedSinceSuspendAll.clear();
            } else {
                stoppedThreads.remove(threadId);
                if (state == State.SUSPENDED && threadId != currentThreadId) {
                    return; // another thread resumed; the one on screen is still stopped
                }
            }
            setState(State.RUNNING);
        });
    }

    /** Batches adapter output onto the FX thread (see {@link DapOutputPump}); the sink runs on FX. */
    private final DapOutputPump outputPump = new DapOutputPump(Platform::runLater, (epoch, text, category) -> {
        if (isCurrent(epoch)) {
            listener.onOutput(text, category);
        }
    });

    private void onOutput(long epoch, String text, String category) {
        if (jsSession) {
            text = jsOutput(epoch, text, category);
            if (text == null || text.isEmpty()) {
                return;
            }
        }
        String original = text;
        String shown = JavaLaunchSupport.withoutJvmVersionWarning(text);
        if (shown != text) {
            LOG.fine(() -> "java-debug: " + original.strip());
        }
        if (!shown.isEmpty()) {
            lastOutput = new OutputStamp(epoch, System.nanoTime());
            outputPump.offer(epoch, shown, category);
        }
    }

    /** When the adapter last sent program output, and for which session (see {@link OutputTail}). */
    private record OutputStamp(long epoch, long nanos) {}

    private volatile OutputStamp lastOutput = new OutputStamp(-1, 0);

    /** A program java-debug started itself (the program console off): its output trails its exit (E104). */
    private boolean outputTrails(long epoch) {
        return javaAdapterEpoch == epoch && programStartedEpoch != epoch;
    }

    /**
     * Runs {@code then} on the FX thread once the session's output has gone quiet ({@link OutputTail}), so the
     * program's last lines are shown before the session ends; dropped if the session ends meanwhile (Stop).
     */
    private void whenOutputQuiet(long epoch, long doneNanos, Runnable then) {
        OutputStamp last = lastOutput;
        long wait = OutputTail.waitMillis(System.nanoTime(), last.epoch() == epoch ? last.nanos() : 0, doneNanos);
        if (wait <= 0) {
            then.run();
            return;
        }
        CompletableFuture.delayedExecutor(wait, TimeUnit.MILLISECONDS)
                .execute(() -> fx(() -> {
                    if (isCurrent(epoch)) {
                        whenOutputQuiet(epoch, doneNanos, then);
                    }
                }));
    }

    /** The session whose exit report waits for its trailing output; -1 for none. */
    private long exitAwaitingOutput = -1;

    /** How long a program's output may trail its end before the console says so (choice 27). */
    static final long TRAILING_NOTICE_MILLIS = 1000;

    /** The session whose program ended while its output still arrives; -1 for none (see {@link #outputTrailing}). */
    private long trailingEpoch = -1;

    /**
     * The program of {@code epoch} ended and its output is still arriving: if the session still waits a moment
     * later, the listener is told once, so the state stops saying Running (review 3, choice 27). FX thread.
     */
    private void outputTrailing(long epoch) {
        if (trailingEpoch == epoch) {
            return;
        }
        trailingEpoch = epoch;
        CompletableFuture.delayedExecutor(TRAILING_NOTICE_MILLIS, TimeUnit.MILLISECONDS)
                .execute(() -> fx(() -> {
                    if (isCurrent(epoch) && state != State.INACTIVE) {
                        outputPump.flush(); // after the output that came before it
                        listener.onOutputTrailing();
                    }
                }));
    }

    /** Reports the exit of {@code epoch} if it is still waiting for the output before it (FX thread). */
    private void reportAwaitedExit(long epoch) {
        if (exitAwaitingOutput == epoch) {
            exitAwaitingOutput = -1;
            outputPump.flush();
            reportJavaExit(epoch);
        }
    }

    /**
     * What js-debug sends as program output, as the console should show it: Node's inspector lines
     * ("Debugger attached.") removed, and a breakpoint condition it could not compile turned into a notice that
     * marks that breakpoint rejected — once, though js-debug repeats it for every script it loads (review D12).
     */
    private @Nullable String jsOutput(long epoch, String text, String category) {
        var error = JsLaunch.conditionError(text);
        if (error.isPresent()) {
            JsLaunch.ConditionError ce = error.get();
            if (reportedConditionErrors.add(ce.condition() + "\u0000" + ce.line())) {
                onNotice(epoch, tr("debug.notice.jsCondition", ce.condition(), ce.line(), ce.reason()), true);
            }
            return null;
        }
        if (jsClientConsole && ("stdout".equals(category) || "stderr".equals(category))) {
            return null; // the program's own pipes carry it (startDebuggee); this is js-debug's copy
        }
        return jsNoise.filter(text);
    }

    /** The inspector's lines dropped from this session's output (reset per session). */
    private volatile JsLaunch.InspectorNoise jsNoise = new JsLaunch.InspectorNoise();

    /** An external adapter that is the debugged program itself (Ruby's rdbg), for session {@code epoch}. */
    private record ProgramAdapter(long epoch, Process process) {}

    private volatile @Nullable ProgramAdapter programAdapter;

    /** How long a program that is its own adapter has to exit after it says it terminated. */
    static final long PROGRAM_ADAPTER_EXIT_GRACE_MILLIS = 3_000;

    private void onTerminated(long epoch) {
        ProgramAdapter self = programAdapter;
        if (self != null && self.epoch() == epoch && self.process().isAlive()) {
            // rdbg says terminated before Ruby has flushed its standard output and exited: the session ends once
            // the program has (bounded), and its last output is in, rather than killing it with that output unread.
            self.process()
                    .onExit()
                    .completeOnTimeout(self.process(), PROGRAM_ADAPTER_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)
                    .thenRun(() -> fx(() -> {
                        if (isCurrent(epoch)) {
                            whenOutputQuiet(epoch, System.nanoTime(), () -> endSession(epoch));
                        }
                    }));
            return;
        }
        fx(() -> {
            Debuggee program = debuggee;
            if (isCurrent(epoch) && program != null && program.isRunning()) {
                // The adapter is done with a program Editora started. Its last output is still in our pipe
                // and the process may not be gone yet: the session ends when its exit is delivered
                // (startDebuggee's sink), and a program that outlives its debugger is ended, not left running.
                CompletableFuture.delayedExecutor(DEBUGGEE_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)
                        .execute(() -> fx(() -> {
                            if (debuggee == program) {
                                program.terminate();
                            }
                        }));
                return;
            }
            if (isCurrent(epoch) && outputTrails(epoch)) {
                outputTrailing(epoch);
                whenOutputQuiet(epoch, System.nanoTime(), () -> {
                    reportAwaitedExit(epoch);
                    endSession(epoch);
                });
                return;
            }
            endSession(epoch);
        });
    }

    /** Ends the session of {@code epoch}, if it is still the current one (FX thread). */
    private void endSession(long epoch) {
        if (!isCurrent(epoch)) {
            return;
        }
        outputPump.flush(); // the program's last lines arrived before this event — show them first
        tempBreakpointFile = null; // session over — nothing to restore
        forceRunMuted = List.of();
        forceRunMutedMembers = false;
        DapClient c;
        synchronized (sessionLock) {
            if (!isCurrent(epoch)) {
                return;
            }
            sessionEpoch++;
            c = client;
            client = null;
        }
        killDebuggee();
        if (c != null) {
            c.disposeInBackground(); // as in stop(): never hold the FX thread on the adapter
        }
        killAdapterProgram();
        releaseCompilationDirectory();
        releaseExitWrapper(); // no exited event came for it
        setState(State.INACTIVE);
    }

    /**
     * The adapter's {@code runInTerminal}: starts the program of session {@code epoch} as Editora's child and
     * completes {@code started} with its process id. The command, working directory and environment are the
     * adapter's, untouched; the environment goes over the user's own with the augmented PATH, as for Run.
     */
    private void startDebuggee(
            long epoch, String cwd, List<String> argv, Map<String, String> env, CompletableFuture<Long> started) {
        if (!isCurrent(epoch)) {
            started.completeExceptionally(new java.util.concurrent.CancellationException("the session has ended"));
            return;
        }
        killDebuggee(); // a session has one program
        programStartedEpoch = epoch;
        // A Node.js program's stdout and stderr share one pipe: two pipes read by two threads reordered its lines
        // (review D102). Java and Python keep their red stderr.
        Debuggee program = new Debuggee(shared.programTerminal, programTitle(), jsSession);
        try {
            // The adapter names the folder as its machine does: on a remote file's host, a path there.
            Path file = debugFile;
            DapPaths paths = pathsFor(file);
            Path dir = cwd == null || cwd.isBlank() ? null : paths.fromAdapter(cwd);
            if (dir == null && file != null && paths != DapPaths.LOCAL) {
                dir = file.getParent(); // never this machine's folder of the same name
            }
            if (jsSession && !program.inTerminal()) {
                // What runs, first in the console (muted), as for Python: exactly what js-debug asked for, which
                // for a compiled project's .ts source is its built .js (review D106).
                onOutput(epoch, com.editora.run.RunService.commandLine(argv) + "\n", COMMAND_CATEGORY);
                Path source = debugFile;
                Path built = JsLaunch.compiledInstead(source, dir, argv);
                if (built != null && source != null && JsLaunch.builtIsStale(source, built, DapManager::modifiedTime)) {
                    // A notice, not stderr: js-debug's copies of the program's streams are dropped (jsOutput).
                    onNotice(
                            epoch,
                            tr("debug.js.builtStale", String.valueOf(built.getFileName()), source.getFileName()),
                            true);
                }
            }
            long pid = program.start(dir, argv, env, new Debuggee.Sink() {
                @Override
                public void output(String text, boolean stderr) {
                    String shown = jsSession ? jsNoise.filter(text) : text;
                    if (debuggee == program && isCurrent(epoch) && !shown.isEmpty()) {
                        listener.onOutput(shown, stderr ? "stderr" : "stdout");
                    }
                }

                @Override
                public void exited(int code) {
                    if (debuggee != program) {
                        return; // stopped: whoever stopped it has ended, or is ending, the session
                    }
                    boolean inputWasOpen = program.inputOpen();
                    debuggee = null;
                    if (isCurrent(epoch)) {
                        if (inputWasOpen) {
                            listener.onProgramInput(false);
                        }
                        listener.onProgramExit(code);
                        endSession(epoch);
                    }
                }
            });
            debuggee = program;
            started.complete(pid);
            if (!program.inTerminal()) {
                listener.onProgramInput(true); // in a terminal, the terminal takes the input
            } else {
                // The Debug console stays empty: say where the program's output and input are (review E106).
                onOutput(epoch, tr("debug.console.inTerminal", programTitle()) + "\n", COMMAND_CATEGORY);
            }
        } catch (java.io.IOException | RuntimeException e) {
            program.kill();
            started.completeExceptionally(e); // the adapter fails the launch with this, which reports it
        }
    }

    private static Optional<java.time.Instant> modifiedTime(Path file) {
        try {
            return Optional.of(java.nio.file.Files.getLastModifiedTime(file).toInstant());
        } catch (java.io.IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** A Terminal tab's title for the debugged program: the file being debugged. */
    private String programTitle() {
        Path file = debugFile;
        String name = file == null || file.getFileName() == null
                ? ""
                : file.getFileName().toString();
        return com.editora.i18n.Messages.tr("terminal.debugTitle", name);
    }

    /** The session served by java-debug (whose {@code exited} event carries no real exit code). */
    private long javaAdapterEpoch = -1;

    /** The session whose program Editora started itself (its exit is reported by its own process). */
    private long programStartedEpoch = -1;

    /**
     * The adapter reports the program exited: the console says with which code, after the program's last
     * output. A program Editora started itself reports its own exit instead (see {@link #startDebuggee}).
     */
    private void onExited(long epoch, int exitCode) {
        fx(() -> {
            if (!isCurrent(epoch) || programStartedEpoch == epoch || exitReportedEpoch == epoch) {
                return;
            }
            exitReportedEpoch = epoch;
            outputPump.flush();
            if (javaAdapterEpoch == epoch) {
                // java-debug reports 0 whatever the program exited with (System.exit(3) included, seen live).
                // The wrapper the program was started through recorded its real code; without one the console
                // says only that the program ended, not a code that is not its own (review A10). It waits for
                // the output still on its way, which would otherwise come after it (E104).
                exitAwaitingOutput = epoch;
                outputTrailing(epoch);
                whenOutputQuiet(epoch, System.nanoTime(), () -> reportAwaitedExit(epoch));
                return;
            }
            listener.onProgramExit(exitCode);
        });
    }

    /** A session's exit-code wrapper ({@link JavaExitCodeWrapper}) and the session it belongs to. */
    private record ExitWrapper(long epoch, JavaExitCodeWrapper wrapper, String realJava) {}

    /**
     * The {@code java} a launch of {@code epoch} really runs: its {@code javaExec}, or the one behind the
     * exit-code wrapper. Anything that runs it itself (the runtime chip's {@code -version}) must run this one: a
     * run of the wrapper would record that run's exit code as the program's.
     */
    private String realJavaExec(long epoch, String javaExec) {
        ExitWrapper own = exitWrapper;
        return own != null && own.epoch() == epoch ? own.realJava() : javaExec;
    }

    /** The current session's exit-code wrapper, or null (the program console is on, a remote file, Windows). */
    private volatile @Nullable ExitWrapper exitWrapper;

    /**
     * Starts the program of a launch java-debug runs itself (the program console off) through a wrapper that
     * records its exit code: {@code args}' {@code javaExec} becomes the wrapper, which runs the real one. Only for
     * a program on this machine and not on Windows (see {@link JavaExitCodeWrapper}); and only when the launch
     * names its {@code java} — without one java-debug picks its own, which the wrapper cannot know.
     */
    private void wrapForExitCode(@Nullable Path file, Map<String, Object> args, long epoch) {
        Object java = args.get("javaExec");
        if (!(java instanceof String real)
                || real.isBlank()
                || !com.editora.process.ExecTargets.runsHere(file)
                || LaunchConfig.Target.LOCAL.windows()) {
            return;
        }
        try {
            JavaExitCodeWrapper wrapper = JavaExitCodeWrapper.create(real);
            releaseExitWrapper();
            exitWrapper = new ExitWrapper(epoch, wrapper, real);
            args.put("javaExec", wrapper.script().toString());
        } catch (java.io.IOException | RuntimeException e) {
            LOG.log(Level.FINE, "No exit-code wrapper; the program runs as before", e);
        }
    }

    /**
     * Says how the program of {@code epoch} ended: its real exit code when its wrapper recorded one — written a
     * moment after java-debug reports the VM's death, so it is waited for off the FX thread — else that it
     * ended. Nothing when another session has started meanwhile (its console is not this program's).
     */
    private void reportJavaExit(long epoch) {
        ExitWrapper own = exitWrapper;
        if (own == null || own.epoch() != epoch) {
            listener.onOutput(com.editora.i18n.Messages.tr("debug.console.programEnded") + "\n", "console");
            return;
        }
        exitWrapper = null;
        // On a thread of its own, not the common pool: the wait polls a file for up to three seconds. Not the
        // connect thread either, which the next session's start-up would then queue behind.
        CompletableFuture.supplyAsync(
                        () -> own.wrapper().awaitExitCode(java.time.Duration.ofSeconds(3)),
                        com.editora.process.BlockingTasks.named("dap-java-exit-code"))
                .whenComplete((code, e) -> fx(() -> {
                    own.wrapper().delete();
                    if (managerClosed || sessionEpoch - epoch > 1) {
                        return; // a new session (or a Stop after the end) owns the console now
                    }
                    outputPump.flush();
                    if (code != null) {
                        listener.onProgramExit(code);
                    } else {
                        listener.onOutput(com.editora.i18n.Messages.tr("debug.console.programEnded") + "\n", "console");
                    }
                }));
    }

    /** Removes the exit-code wrapper of a session that will not report its exit (stopped, replaced). */
    private void releaseExitWrapper() {
        ExitWrapper old = exitWrapper;
        exitWrapper = null;
        if (old != null) {
            // After the stop: java-debug may still be ending the program the wrapper runs.
            CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(old.wrapper()::delete);
        }
    }

    /** The session whose exit code was already reported (a child session may report it again). */
    private long exitReportedEpoch = -1;

    /** Ends the session's own program, if it has one, and stops listening to it (FX thread). */
    private void killDebuggee() {
        Debuggee program = debuggee;
        debuggee = null;
        if (program != null) {
            program.kill();
        }
    }

    private void onError(long epoch, String message) {
        fx(() -> {
            if (isCurrent(epoch)) {
                listener.onError(message);
            }
        });
    }

    // --- Internals ------------------------------------------------------------------------------

    /** Keeps the jdtls hosting a Java debug session from being evicted as idle; released when it ends. */
    private Runnable lspLease = () -> {};

    private void setState(State s) {
        if (s == State.INACTIVE) {
            lspLease.run();
            lspLease = () -> {};
        }
        if (s != State.RUNNING) {
            stepInFlight = false;
        }
        if (s != State.SUSPENDED) {
            lazyValues.clear(); // the program ran (or ended): references and values are stale
        }
        if (s == State.INACTIVE || s == State.STARTING) {
            lazyValues.reset(); // nor can a toString() the last session never finished
            consoleEvaluations = 0; // an answer that never comes cannot hold the next session's controls
            stoppedThreads.clear();
            allSuspended = false;
            resumedSinceSuspendAll.clear();
        }
        this.state = s;
        listener.onState(s);
    }

    /**
     * Starts a new session: ends whatever the previous one still holds — its connection, the program it
     * launched, its compiled classes, its exit-code wrapper — and returns the new session's epoch. Every start
     * path goes through here, so none can leave the session before it running (review D102: an attach of
     * Editora's JVM adapter used to replace a live Java session's connection without ending it).
     */
    private long beginSession() {
        jsNoise = new JsLaunch.InspectorNoise();
        Future<?> startup = startupTask;
        startupTask = null;
        if (startup != null) {
            startup.cancel(true);
        }
        long epoch;
        DapClient previous;
        synchronized (sessionLock) {
            // The epoch moves first: a compile of the old session that finishes now no longer keeps its folder.
            epoch = ++sessionEpoch;
            previous = client;
            client = null;
        }
        tempBreakpointFile = null;
        killDebuggee();
        if (previous != null) {
            previous.disposeInBackground();
        }
        killAdapterProgram();
        lspLease.run(); // the old session's jdtls pin; a Java session takes its own once it has an adapter
        lspLease = () -> {};
        // A launch still waiting for its adapter has no client for ready() to stop, but it may already hold
        // its compiled classes: the next launch would overwrite the only reference to them (review D6).
        releaseCompilationDirectory();
        releaseExitWrapper();
        return epoch;
    }

    private boolean isCurrent(long epoch) {
        return !managerClosed && sessionEpoch == epoch;
    }

    private boolean isCurrent(long epoch, @Nullable DapClient expected) {
        return isCurrent(epoch) && client == expected;
    }

    /** Atomically publishes a client only while the start operation that created it still owns the session. */
    private boolean publishClient(long epoch, DapClient candidate) {
        synchronized (sessionLock) {
            if (!isCurrent(epoch)) {
                return false;
            }
            candidate.setMemberBreakpoints(shared.memberBreakpoints); // sent on its initialized event
            candidate.setHexValues(shared.hexValues);
            client = candidate;
            return true;
        }
    }

    private DapClient.Host sessionHost(long epoch) {
        return new DapClient.Host() {
            @Override
            public void onStopped(int threadId, String reason) {
                DapManager.this.onStopped(epoch, threadId, reason, false);
            }

            @Override
            public void onStopped(int threadId, String reason, boolean allThreadsStopped) {
                DapManager.this.onStopped(epoch, threadId, reason, allThreadsStopped);
            }

            @Override
            public void onContinued() {
                DapManager.this.onContinued(epoch, 0, true);
            }

            @Override
            public void onContinued(int threadId, boolean allThreadsContinued) {
                DapManager.this.onContinued(epoch, threadId, allThreadsContinued);
            }

            @Override
            public void onOutput(String text, String category) {
                DapManager.this.onOutput(epoch, text, category);
            }

            @Override
            public void onTerminated() {
                terminatedEpoch = epoch; // a plain long written once per session: read on the FX thread later
                DapManager.this.onTerminated(epoch);
            }

            @Override
            public void onError(String message) {
                DapManager.this.onError(epoch, message);
            }

            @Override
            public void onProcessId(long pid) {
                fx(() -> DapManager.this.onProcessId(epoch, pid));
            }

            @Override
            public void onBreakpointStatus(Path file, List<DapModels.BreakpointStatus> statuses, boolean whole) {
                DapManager.this.onBreakpointStatus(epoch, file, statuses, whole);
            }

            @Override
            public void onNotice(String message, boolean error) {
                DapManager.this.onNotice(epoch, message, error);
            }

            @Override
            public void onExited(int exitCode) {
                DapManager.this.onExited(epoch, exitCode);
            }

            @Override
            public void onThreadsChanged() {
                fx(() -> {
                    if (isCurrent(epoch)) {
                        listener.onThreadsChanged();
                    }
                });
            }

            @Override
            public void onHotCodeReplace(String changeType, String message) {
                DapManager.this.onHotCodeReplace(epoch, changeType, message);
            }

            @Override
            public CompletableFuture<Long> onRunInTerminal(String cwd, List<String> argv, Map<String, String> env) {
                CompletableFuture<Long> started = new CompletableFuture<>();
                fx(() -> startDebuggee(epoch, cwd, argv, env, started));
                return started;
            }

            @Override
            public void onTransportClosed(@Nullable Throwable error) {
                if (error != null) {
                    LOG.log(Level.FINE, "Debug adapter transport closed", error);
                }
                DapManager.this.onTransportClosed(epoch);
            }
        };
    }

    /**
     * Ends the starting session of {@code epoch} because of a failure with a report: {@code console} goes to
     * the Debug console (as error output, links and all) and {@code status} to the status line.
     */
    private void failWithConsole(long epoch, String console, String status) {
        failWithConsole(epoch, console, status, false);
    }

    /** As above; {@code compileFailed}: the launched file did not compile, so nothing was started (review 3, A201). */
    private void failWithConsole(long epoch, String console, String status, boolean compileFailed) {
        fx(() -> {
            if (!isCurrent(epoch)) {
                return;
            }
            if (console != null && !console.isBlank()) {
                listener.onOutput(console.endsWith("\n") ? console : console + "\n", "stderr");
            }
            listener.onError(status);
            if (compileFailed) {
                listener.onCompileFailed();
            }
            stop();
        });
    }

    private void fail(long epoch, @Nullable String message) {
        fx(() -> {
            if (!isCurrent(epoch)) {
                return;
            }
            listener.onError(message);
            stop();
        });
    }

    static String msg(Throwable t) {
        // A failed request arrives wrapped (CompletionException / ExecutionException), whose own message is
        // "<exception class>: <adapter message>" — show what the adapter said, not the Java class name.
        while ((t instanceof java.util.concurrent.CompletionException
                        || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof java.util.concurrent.TimeoutException) {
            // "java.util.concurrent.TimeoutException" told the user nothing
            return tr("status.debug.noAnswer", DapClient.requestTimeoutSeconds());
        }
        // java-debug words a refusal as the Java exception behind it: "org.eclipse.debug.core.DebugException:
        // com.sun.jdi.…: what happened". Only what happened is for the user.
        if (t instanceof org.eclipse.lsp4j.jsonrpc.ResponseErrorException refused
                && refused.getResponseError() != null) {
            // js-debug words a failed evaluation in the body only (review D6): "Unset error message." otherwise.
            String said = DapErrors.responseText(
                    refused.getResponseError().getMessage(),
                    refused.getResponseError().getData());
            if (said != null) {
                return DapErrors.userText(said);
            }
        }
        return t == null ? "" : DapErrors.userText(t.getMessage() == null ? t.toString() : t.getMessage());
    }

    // --- jdtls replies: read by JdtlsReplies (these three names are what tests reach by reflection) ----------

    private static List<MainClassOption> parseMainClasses(Object res) {
        return JdtlsReplies.parseMainClasses(res);
    }

    private static void parseClasspath(Object res, List<String> modulepaths, List<String> classpaths) {
        JdtlsReplies.parseClasspath(res, modulepaths, classpaths);
    }

    private static @Nullable String asString(Object res) {
        return JdtlsReplies.asString(res);
    }

    // --- Java code changes and frame control (hot code replace, restart frame, smart step into) ------------

    /** When saved Java code reaches a running program: on save, on {@code debug.applyCodeChanges}, or never. */
    public enum CodeReplaceMode {
        AUTO,
        MANUAL,
        OFF;

        /** The setting's value ({@code auto} / {@code manual} / {@code off}); anything else is the default. */
        public static CodeReplaceMode of(String value) {
            if (value != null) {
                for (CodeReplaceMode m : values()) {
                    if (m.name().equalsIgnoreCase(value.strip())) {
                        return m;
                    }
                }
            }
            return AUTO;
        }
    }

    /** How an Apply Code Changes ended. */
    public enum CodeReplaceResult {
        /** Classes were replaced in the running program ({@link CodeReplaceOutcome#classes}). */
        APPLIED,
        /** The build and the replace found nothing the program runs that had changed. */
        NOTHING_CHANGED,
        /** The project has compile errors: nothing was replaced (a broken method would throw when it runs). */
        COMPILE_ERRORS,
        /**
         * The session runs classes javac compiled because jdtls could not build the folder (its guessed source roots
         * are wrong), and jdtls still can't: there is nothing to load. Restarting compiles the edits with javac.
         */
        JAVAC_CLASSES,
        /** java-debug refused (an added or removed method or field, a JVM without redefinition…): restart. */
        FAILED,
        /** jdtls could not build: no Java server for the file, or the build failed or was cancelled. */
        BUILD_FAILED,
        /** No running Java session to apply to. */
        NO_SESSION
    }

    /** {@code message} is java-debug's reason for {@link CodeReplaceResult#FAILED}, else empty. */
    public record CodeReplaceOutcome(
            CodeReplaceResult result,
            List<String> classes,
            @Nullable String message) {

        public CodeReplaceOutcome {
            classes = classes == null ? List.of() : List.copyOf(classes);
            message = message == null ? "" : message.strip();
        }
    }

    /** Receives java-debug's {@code hotcodereplace} progress and problems (FX thread); see {@link
     *  #setCodeReplaceNotices}. */
    private java.util.function.BiConsumer<String, String> codeReplaceNotices = (type, message) -> {};

    /** Who is told java-debug's hot code replace events ({@code changeType}, message), on the FX thread. */
    public void setCodeReplaceNotices(java.util.function.BiConsumer<String, String> notices) {
        this.codeReplaceNotices = notices == null ? (type, message) -> {} : notices;
    }

    private void onHotCodeReplace(long epoch, String changeType, String message) {
        fx(() -> {
            if (isCurrent(epoch)) {
                outputPump.flush(); // after the program output that preceded it
                codeReplaceNotices.accept(changeType, message);
            }
        });
    }

    /** Whether a Java session is running or paused, so that saved code changes can be loaded into it. */
    public boolean canReplaceCode() {
        return client != null && "java".equals(sessionLanguage) && (state == State.RUNNING || state == State.SUSPENDED);
    }

    /** The session whose jdtls build {@link #primeCodeReplace} has started; -1 for none. */
    private long codeReplacePrimedEpoch = -1;

    /**
     * Gives jdtls a build to compare saved changes with. A program Editora compiled itself (a Java file outside
     * a build project) runs classes jdtls never built: without an earlier build, jdtls's first one reports every
     * class as new rather than changed, and java-debug then has nothing to replace. Building once at session
     * start fixes that. Only then: that build writes to jdtls's own output in its workspace, not the user's
     * folder (a build project's output is built before its launch, see the launch path). Once per session.
     */
    public void primeCodeReplace() {
        if (compilationDirectory == null
                || debugFile == null
                || !canReplaceCode()
                || codeReplacePrimedEpoch == sessionEpoch) {
            return;
        }
        codeReplacePrimedEpoch = sessionEpoch;
        buildJavaWorkspace(debugFile, false, built -> {});
    }

    /** The file the session was started for (it routes a build to that session's jdtls); null when none. */
    public @Nullable Path sessionFile() {
        return debugFile;
    }

    /**
     * Apply Code Changes: has jdtls build {@code file}'s workspace (incrementally — Editora runs it without
     * autobuild, so nothing else compiles a saved file), then asks java-debug to load the rebuilt classes into
     * the running program. A build with compile errors replaces nothing. {@code cb} runs on the FX thread,
     * exactly once — with {@link CodeReplaceResult#NO_SESSION} when the session ended meanwhile.
     */
    public void applyCodeChanges(@Nullable Path file, Consumer<CodeReplaceOutcome> cb) {
        DapClient c = client;
        if (c == null || !canReplaceCode() || file == null) {
            cb.accept(new CodeReplaceOutcome(CodeReplaceResult.NO_SESSION, List.of(), ""));
            return;
        }
        long epoch = sessionEpoch;
        buildJavaWorkspace(file, false, built -> {
            if (!isCurrent(epoch, c)) {
                cb.accept(ended()); // the caller waits for an answer (review D101)
                return;
            }
            switch (built) {
                case WITH_ERRORS ->
                    cb.accept(new CodeReplaceOutcome(
                            javacEpoch == epoch ? CodeReplaceResult.JAVAC_CLASSES : CodeReplaceResult.COMPILE_ERRORS,
                            List.of(),
                            ""));
                case SUCCEEDED ->
                    c.redefineClasses()
                            .whenComplete((r, e) -> fx(() -> {
                                cb.accept(
                                        isCurrent(epoch, c)
                                                ? codeReplaceOutcome(r, e == null ? null : msg(e))
                                                : ended());
                            }));
                default -> cb.accept(new CodeReplaceOutcome(CodeReplaceResult.BUILD_FAILED, List.of(), ""));
            }
        });
    }

    /** The outcome of a replace whose session ended before it finished. */
    private static CodeReplaceOutcome ended() {
        return new CodeReplaceOutcome(CodeReplaceResult.NO_SESSION, List.of(), "");
    }

    /** Pure: the outcome of a {@code redefineClasses} answer, or of the request failing with {@code error}. */
    static CodeReplaceOutcome codeReplaceOutcome(DapModels.CodeReplace reply, @Nullable String error) {
        if (error != null) {
            return new CodeReplaceOutcome(CodeReplaceResult.FAILED, List.of(), error);
        }
        if (reply == null || (!reply.failed() && reply.changedClasses().isEmpty())) {
            return new CodeReplaceOutcome(CodeReplaceResult.NOTHING_CHANGED, List.of(), "");
        }
        // java-debug words "JVM contains obsolete methods" as an error although the classes were replaced: a method
        // that was running when the code changed (main's loop, typically) goes on with its old code. That is a
        // replace that worked, with a note (review 2, B103) — it used to read "not applied, restart".
        if (!reply.failed() || !reply.changedClasses().isEmpty() || reply.obsoleteMethods()) {
            return new CodeReplaceOutcome(CodeReplaceResult.APPLIED, reply.changedClasses(), reply.error());
        }
        return new CodeReplaceOutcome(CodeReplaceResult.FAILED, reply.changedClasses(), reply.error());
    }

    /** Whether the session's adapter can restart a frame ({@code restartFrame}). */
    public boolean supportsRestartFrame() {
        DapClient c = client;
        return c != null && c.supportsRestartFrame();
    }

    /**
     * Restart Frame: runs {@code frameId}'s method again from its start (frames above it are dropped). Behaves
     * like a step: the session runs until the adapter's next stop; a refusal (the bottom frame, a native one)
     * is reported and leaves the session paused where it was. FX thread, while SUSPENDED.
     */
    public void restartFrame(int frameId) {
        step((c, threadId) -> c.restartFrame(frameId));
    }

    /** Whether the session's adapter can list the calls on a line ({@code stepInTargets}). */
    public boolean supportsStepInTargets() {
        DapClient c = client;
        return c != null && c.supportsStepInTargets();
    }

    /**
     * The calls on the current thread's line that Smart Step Into can enter (callback on the FX thread; empty
     * when there are none, the session is not paused, or the adapter could not say).
     */
    public void stepInTargets(Consumer<List<DapModels.StepTarget>> cb) {
        DapClient c = client;
        if (c == null || state != State.SUSPENDED) {
            cb.accept(List.of());
            return;
        }
        long epoch = sessionEpoch;
        int threadId = currentThreadId;
        c.stackTrace(threadId)
                .thenCompose(frames -> frames == null || frames.isEmpty()
                        ? CompletableFuture.completedFuture(List.<DapModels.StepTarget>of())
                        : c.stepInTargets(frames.get(0).id()))
                .whenComplete((targets, e) -> fx(() -> {
                    if (!isCurrent(epoch, c) || state != State.SUSPENDED) {
                        return;
                    }
                    if (e != null) {
                        listener.onError(msg(e));
                    }
                    cb.accept(targets == null ? List.of() : targets);
                }));
    }

    /** Smart Step Into: steps the current thread into the call {@code targetId} (see {@link #stepInTargets}). */
    public void stepIntoTarget(int targetId) {
        step((c, threadId) -> c.stepIn(threadId, targetId));
    }

    private static Listener noopListener() {
        return new Listener() {
            @Override
            public void onState(State state) {}

            @Override
            public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {}

            @Override
            public void onOutput(String text, String category) {}

            @Override
            public void onError(@Nullable String message) {}
        };
    }

    // --- completions (follow-up 5) -------------------------------------------------------------------------

    /** Whether the session's adapter completes expressions (DAP {@code completions}). */
    public boolean supportsCompletions() {
        DapClient c = client;
        return c != null && c.supportsCompletions();
    }

    /**
     * Completions of {@code text} at 0-based {@code column} in frame {@code frameId}, on the FX thread; an empty
     * list when there is no session, the adapter cannot, or it failed (a completion is never worth an error).
     */
    public void completions(int frameId, String text, int column, Consumer<List<DapModels.CompletionTarget>> cb) {
        DapClient c = client;
        if (c == null || !c.supportsCompletions() || frameId < 0) {
            cb.accept(List.of());
            return;
        }
        c.completions(frameId, text, column)
                .whenComplete((r, e) -> fx(() -> {
                    if (client == c) {
                        cb.accept(e != null || r == null ? List.of() : r);
                    }
                }));
    }
}
