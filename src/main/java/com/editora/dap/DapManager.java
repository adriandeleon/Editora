package com.editora.dap;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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

    static final Logger LOG = Logger.getLogger(DapManager.class.getName());

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

    final LspManager lsp;
    /** The settings this window's managers share ({@link #newSession}). */
    final DapSettings shared;

    /** Where each adapter is and whether it runs: configure, detect and probe ({@link AdapterDetection}). */
    final AdapterDetection detection = new AdapterDetection(this);
    /** Java sessions through jdtls: main classes, the classpath, compiling a loose file ({@link JavaLaunches}). */
    final JavaLaunches javaLaunch = new JavaLaunches(this);
    /** Kotlin, Scala, Clojure, Groovy and hosted JVM sessions ({@link JvmSessions}). */
    final JvmSessions jvmSessions = new JvmSessions(this);
    /** Adapters Editora starts as programs of their own: Delve, CodeLLDB, GDB, rdbg and the rest ({@link ExternalLaunches}). */
    final ExternalLaunches externalLaunches = new ExternalLaunches(this);
    /** debugpy sessions: launches, attaches, subprocesses and remote hosts ({@link PythonSessions}). */
    final PythonSessions pythonSessions = new PythonSessions(this);
    /** vscode-js-debug sessions: Node.js, Deno, browsers and remote hosts ({@link JsSessions}). */
    final JsSessions jsSessions = new JsSessions(this);
    /** Continue, Pause, the steps, Run to Cursor and the Threads view's requests ({@link ExecutionControl}). */
    final ExecutionControl execution = new ExecutionControl(this);
    /** Instructions, disassembly and memory ({@link LowLevelRequests}). */
    final LowLevelRequests lowLevel = new LowLevelRequests(this);
    /** Stacks, scopes, variables, evaluation, loaded sources and modules ({@link InspectionRequests}). */
    final InspectionRequests inspection = new InspectionRequests(this);
    /** What the manager does with each adapter event: stops, output, the end of a session ({@link SessionEvents}). */
    final SessionEvents sessionEvents = new SessionEvents(this);
    /** Programs Editora starts for the adapter, and their exit codes ({@link DebuggeeProcesses}). */
    final DebuggeeProcesses debuggees = new DebuggeeProcesses(this);
    /** Applying code changes to a running Java program ({@link HotCodeReplace}). */
    final HotCodeReplace codeReplace = new HotCodeReplace(this);

    Listener listener = noopListener();

    final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dap-connect");
        t.setDaemon(true);
        return t;
    });

    /** The window's project root for the next JavaScript launch (its package root and workspace folder). */
    volatile @Nullable Path jsProjectRoot;
    /** The current session is vscode-js-debug's (read on adapter threads to clean up what it prints). */
    volatile boolean jsSession;
    /**
     * The JavaScript session's program was started by Editora ({@code runInTerminal}): its output arrives from its
     * own pipes, so js-debug's copies of it (console.log through the inspector) are not shown a second time.
     */
    volatile boolean jsClientConsole;
    /** A session's adapter's standard error, with the session it belongs to. */
    private record SessionStderr(long epoch, AdapterStderr stderr) {}

    /**
     * The current session's adapter's stderr (any adapter Editora starts and drains), for the message when it dies
     * while starting. Tagged with its session: a remote session has none, and must not show the last local one's.
     */
    volatile @Nullable SessionStderr adapterStderr;

    /** Records {@code stderr} as the adapter's of session {@code epoch}; returns it. */
    AdapterStderr adapterStderr(long epoch, AdapterStderr stderr) {
        adapterStderr = new SessionStderr(epoch, stderr);
        return stderr;
    }

    /** The stderr of session {@code epoch}'s adapter, or null when it has none (or another session's). */
    @Nullable
    AdapterStderr adapterStderr(long epoch) {
        SessionStderr kept = adapterStderr;
        return kept != null && kept.epoch() == epoch ? kept.stderr() : null;
    }

    /** Condition errors js-debug already reported this session (it repeats each one per script it loads). */
    final java.util.Set<String> reportedConditionErrors = java.util.concurrent.ConcurrentHashMap.newKeySet();

    volatile @Nullable DapClient client;
    volatile long sessionEpoch;
    volatile boolean managerClosed;

    /** The session opened a core dump ({@link CoreDumps}): it is looked at, never run. */
    volatile boolean postMortem;

    /**
     * A session attached to a debug server ({@link RemoteNativeAttach}) that has not yet stopped in code with a source:
     * the server holds the program before its first instruction, and the debugger reports that (GDB: reason
     * {@code attach}; CodeLLDB: a {@code SIGTRAP}, sometimes twice) as a stop. Those stops are passed over, as a program
     * started under any other debugger runs to its first breakpoint.
     */
    volatile boolean remoteEntryPending;

    /**
     * GDB records the program's run ({@code record full}): it can go back over what it recorded. Only a GDB session
     * records; a new session starts without.
     */
    private volatile boolean recording;

    volatile @Nullable Future<?> startupTask;
    final Object sessionLock = new Object();
    State state = State.INACTIVE;
    int currentThreadId;
    /**
     * The threads known to be stopped, with each one's stop reason, oldest first (FX thread). java-debug
     * suspends and resumes <em>per thread</em>: two workers can sit on the same breakpoint, and resuming the
     * one on screen leaves the other suspended with no further event to say so.
     */
    final java.util.LinkedHashMap<Integer, String> stoppedThreads = new java.util.LinkedHashMap<>();
    /**
     * java-debug suspended the whole program (Pause, or a stop with {@code allThreadsStopped} under "Suspend all
     * threads"), and steps since have not resumed it: Continue must resume every thread, not just the one shown
     * (FX thread).
     */
    boolean allSuspended;
    /**
     * The threads resumed one by one (Threads view → Resume Thread) since the whole program was suspended: while
     * {@link #allSuspended}, every other thread is still suspended (FX thread).
     */
    final java.util.Set<Integer> resumedSinceSuspendAll = new java.util.HashSet<>();
    /** Counts stops shown, so a late step acknowledgement can tell the step has already ended. */
    int stopCount;
    /** A step was acknowledged and its stop has not arrived yet (FX thread). */
    boolean stepInFlight;
    /** The session epoch of a step request the adapter has not answered yet, else -1 (FX thread). */
    long stepPendingEpoch = -1;

    @Nullable
    Path debugFile;

    volatile @Nullable Path compilationDirectory;

    /**
     * TEST SEAM — run on the compile thread once a loose file's classes are compiled and kept, before the launch goes
     * on to its adapter: a test holds the launch there instead of spinning on the FX thread (QUAL-07, Pauta 6.2.0).
     */
    volatile @Nullable Runnable compiledForTest;

    record SourceAlias(long epoch, Path original, Path compiled) {}

    /** The program of the current session when Editora started it, else null (FX thread). */
    @Nullable
    Debuggee debuggee;

    /** The adapter language of the current (or last) session: {@code java}, {@code python} or {@code javascript}. */
    String sessionLanguage = "java";

    @Nullable
    Runnable restartAction;

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
    void executeCommand(
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
    void executeCommand(
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
    void buildJavaWorkspace(Path file, boolean full, Consumer<LspManager.BuildOutcome> cb) {
        lsp.buildJavaWorkspace(
                file,
                full,
                outcome -> callbackWrapper.apply(() -> cb.accept(outcome)).run());
    }

    /** Runs {@code r} later on the FX thread, through {@link #callbackWrapper}. */
    void fx(Runnable r) {
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
    @Nullable
    List<String> filtersFor(String language) {
        if (DebugLanguages.FLUTTER.id().equals(language)) {
            language = DebugLanguages.DART.id(); // a Flutter app's session is Dart's: its exception choices, its stops
        }
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
            client.setExtraExceptionFilters(exceptionExtras(), exceptionTypes());
        }
    }

    /**
     * The exception filters each language's exception breakpoints need ({@code language → filters}), for every session
     * of the window: they are sent besides the user's choice. None while breakpoints are muted.
     */
    public void setExceptionExtras(Map<String, List<String>> extras) {
        setExceptionExtras(extras, Map.of());
    }

    /**
     * As {@link #setExceptionExtras(Map)}, with the exception types ({@code language → types}) an adapter that can be
     * told them stops on in place of the extra filters (debugpy's {@code exceptionOptions}: {@link
     * PythonExceptionTypes}).
     */
    public void setExceptionExtras(Map<String, List<String>> extras, Map<String, List<String>> types) {
        shared.exceptionExtras.clear();
        extras.forEach((language, filters) -> shared.exceptionExtras.put(language, List.copyOf(filters)));
        shared.exceptionTypes.clear();
        types.forEach((language, named) -> shared.exceptionTypes.put(language, List.copyOf(named)));
        DapClient c = client;
        if (c != null) {
            c.setExtraExceptionFilters(exceptionExtras(), exceptionTypes());
        }
    }

    /** The exception types the session's adapter may be told for its language's exception breakpoints. */
    private List<String> exceptionTypes() {
        return shared.exceptionsMuted ? List.of() : shared.exceptionTypes.getOrDefault(sessionLanguage, List.of());
    }

    /** The extra exception filters for the session's language: none while breakpoints are muted. */
    private List<String> exceptionExtras() {
        return shared.exceptionsMuted ? List.of() : shared.exceptionExtras.getOrDefault(sessionLanguage, List.of());
    }

    /**
     * Which exception {@code threadId} stopped on, asked now (the stop's own report comes on its own): {@code cb}
     * gets it on the FX thread, or null when the adapter cannot say or the session moved on.
     */
    public void exceptionInfo(int threadId, Consumer<DapModels.@Nullable ExceptionInfo> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept(null);
            return;
        }
        long epoch = sessionEpoch;
        c.exceptionInfo(threadId)
                .whenComplete((info, e) -> fx(() ->
                        cb.accept(e == null && isCurrent(epoch, c) && info != null && !info.isEmpty() ? info : null)));
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

    /** Template breakpoints for the Python sessions started from now on ({@link PythonTemplates}). */
    public void setPythonTemplates(PythonTemplates.Mode mode) {
        shared.pythonTemplates = mode == null ? PythonTemplates.Mode.AUTO : mode;
    }

    /**
     * What the project in {@code dir} uses, read from its files: none for a folder not on this machine's file system (a
     * remote host's, over SSH — a dozen reads a level there would hold up the launch; Settings' On still applies).
     */
    static PythonTemplates.Found templatesIn(@Nullable Path dir) {
        if (dir == null || dir.getFileSystem() != java.nio.file.FileSystems.getDefault()) {
            return PythonTemplates.Found.NONE;
        }
        return PythonTemplates.detect(
                dir,
                file -> {
                    try {
                        return Files.isRegularFile(file) && Files.size(file) < 512_000 ? Files.readString(file) : null;
                    } catch (Exception e) {
                        return null;
                    }
                },
                Files::isRegularFile);
    }

    /** Whether a Python launch debugs its subprocesses too, each in a session of its own; from the next launch on. */
    public void setPythonSubprocesses(boolean on) {
        shared.pythonSubprocesses = on;
    }

    /**
     * The evaluation time limit in seconds, 0 for none ({@link EvaluationLimits}): a session started from now on tells
     * its debugger (GDB's command line, debugpy's launch), and waits a little longer for an answer.
     */
    public void setEvaluationTimeLimit(int seconds) {
        shared.evaluationTimeLimit = Math.max(0, seconds);
    }

    /** The goroutine options this session's Delve was last told (at launch, or since through its console). */
    private GoGoroutines.@Nullable Options liveGoGoroutines;

    /** The Go goroutine options sessions are told ({@link GoGoroutines}). */
    public GoGoroutines.Options goGoroutines() {
        return shared.goGoroutines;
    }

    /**
     * Go: which goroutines the Threads view lists and what it names them by ({@link GoGoroutines}). A Go session
     * started from now on is told in its launch; a running one's Delve is told through its console ({@code dlv
     * config}), and {@code done} runs once it has answered (at once when there is none to tell).
     */
    public void setGoGoroutines(GoGoroutines.Options options, Runnable done) {
        shared.goGoroutines = options;
        DapClient c = client;
        if (c == null
                || state == State.INACTIVE
                || !DebugLanguages.GO.id().equals(sessionLanguage)
                || options.equals(liveGoGoroutines)) {
            done.run();
            return;
        }
        liveGoGoroutines = options;
        java.util.concurrent.CompletableFuture<?> chain = java.util.concurrent.CompletableFuture.completedFuture(null);
        for (String command : GoGoroutines.liveCommands(options)) {
            chain = chain.thenCompose(x -> c.evaluate(command, 0, "repl").exceptionally(e -> {
                LOG.log(Level.FINE, "Delve did not take " + command, e);
                return null;
            }));
        }
        chain.whenComplete((x, e) -> fx(done));
    }

    /** The Debugging setting's Node.js for JavaScript sessions ("" = automatic). */
    public void setNodePath(String nodePath) {
        shared.nodePath = nodePath == null ? "" : nodePath.strip();
    }

    /** Node.js arguments before the program for the next JavaScript launch ({@code --test} for a test file). */
    volatile List<String> jsNodeArgs = List.of();

    /** Sets {@link #jsNodeArgs}; sticky across {@link #restart()}, like the program arguments. */
    public void setJsNodeArgs(List<String> args) {
        this.jsNodeArgs = com.editora.run.DenoRun.withoutFilter(args); // a Deno test's name is not for Node
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

    /** See {@link AdapterDetection#configure}. */
    public void configure(
            boolean enabled,
            String javaPluginPath,
            boolean pythonEnabled,
            String pythonCommand,
            boolean jsEnabled,
            String jsPath) {
        detection.configure(enabled, javaPluginPath, pythonEnabled, pythonCommand, jsEnabled, jsPath);
    }

    /** Run on the FX thread when a java-debug path {@link #configure} could not answer from memory was located. */
    volatile @Nullable Runnable onJavaDebugLocated;

    /** Who applies the support again once a newly configured java-debug path has been looked at off FX. */
    public void setOnJavaDebugLocated(@Nullable Runnable then) {
        this.onJavaDebugLocated = then;
    }

    /** The java-debug path {@link #configure} was last given (a look off the FX thread may ask for it). */
    public String javaDebugPluginPath() {
        return shared.pluginPath;
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
        return shared.serverProvidesJavaDebug || !detection.locate().isEmpty();
    }

    /** Whether Java debugging can run: we located a bundle to inject, <b>or</b> jdtls already provides it. */
    public boolean isAdapterAvailable() {
        return detection.javaDebugUsable();
    }

    /** See {@link AdapterDetection#isLanguageAvailable}. */
    public boolean isLanguageAvailable(String language) {
        return detection.isLanguageAvailable(language);
    }

    /** See {@link AdapterDetection#detect}. */
    public void detect(Consumer<Boolean> onResult) {
        detection.detect(onResult);
    }

    /**
     * Off-thread: relocates debugpy and probes {@code <python> -c "import debugpy"} (with the located dir
     * on {@code PYTHONPATH}); delivers availability + caches it, on the FX thread.
     */
    public void detectPython(Consumer<Boolean> onResult) {
        detection.detectPython(onResult, false);
    }

    /**
     * As {@link #detectPython}, but delivering whether debugpy is <em>installed</em> (importable by the
     * configured interpreter) whatever the switches say — what Settings → Debugging shows. With debugging or
     * Python debugging turned off it said "not found" about a debugpy that was there.
     */
    public void detectPythonInstalled(Consumer<Boolean> onResult) {
        detection.detectPython(onResult, true);
    }

    /** See {@link AdapterDetection#detectJs}. */
    public void detectJs(Consumer<Boolean> onResult) {
        detection.detectJs(onResult);
    }

    /** Looks the java-debug plugin up for {@code configuredPath} now, on the calling thread: the launch's warm-up. */
    public static void prepareLocate(String configuredPath) {
        DebugAdapterLocator.prepareLocate(configuredPath, AdapterDetection.home());
    }

    /** Test seam: debugpy found in {@code dir} (a folder holding a {@code debugpy} package), detection done. */
    void setPythonAdapterForTest(Path dir) {
        shared.debugpyDir = dir;
        shared.pythonAvailable = true;
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

    /** See {@link AdapterDetection#detectJsInstalled}. */
    public void detectJsInstalled(Consumer<JsDebugInstall> onResult) {
        detection.detectJsInstalled(onResult);
    }

    /** See {@link AdapterDetection#nodeVersion}. */
    static @Nullable String nodeVersion(String node) {
        return AdapterDetection.nodeVersion(node);
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
    volatile @Nullable Path jvmCwd;

    /** See {@link JvmSessions#startJvmScript}. */
    public void startJvmScript(Path file, String language, @Nullable Path cwd) {
        jvmSessions.startJvmScript(file, language, cwd);
    }

    /** A compiled program's jar for the next JVM launch, run as {@code java -jar}; null runs the file's tool. */
    volatile @Nullable Path jvmJar;

    /** See {@link JvmSessions#startJvmProgram}. */
    public void startJvmProgram(Path file, String language, @Nullable Path cwd, Path jar, String java) {
        jvmSessions.startJvmProgram(file, language, cwd, jar, java);
    }

    /** See {@link JvmSessions#startJvmAttach}. */
    public void startJvmAttach(Path file, String language, String host, int port) {
        jvmSessions.startJvmAttach(file, language, host, port);
    }

    /** Program arguments for the next launch (shared with the Run feature's per-file args). */
    volatile List<String> programArgs = List.of();

    /** JVM arguments for the next launch (from a saved run configuration; "" otherwise). */
    volatile String vmArgs = "";

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
    volatile @Nullable List<String> vmArgList;

    /** The JVM arguments of the next launch as java-debug's {@code vmArgs} string, for an adapter on {@code target}. */
    String vmArgsFor(LaunchConfig.Target target) {
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
    @Nullable
    String javaExecFor(Path file, String javaExecOverride, @Nullable String resolved) {
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
    volatile @Nullable Path pythonProjectRoot;

    /** The interpreter picked for that project ("" when none was), relative paths taken from its root. */
    volatile String pythonProjectChoice = "";

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
    volatile com.editora.run.PythonRunPlan.@Nullable Request pythonRequest;

    /** The machine the next Python program runs on, as Run plans it, or null (decided from the file). */
    volatile com.editora.run.PythonRunPlan.@Nullable Host pythonHost;

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
    volatile java.util.Map<String, String> env = java.util.Map.of();

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

    /** See {@link JavaLaunches#startLaunch}. */
    public void startLaunch(
            Path file,
            MainClassPicker picker,
            String javaExecOverride,
            @Nullable Path projectRoot,
            @Nullable Path looseCwd) {
        javaLaunch.startLaunch(file, picker, javaExecOverride, projectRoot, looseCwd);
    }

    /**
     * The epoch of the session that runs classes javac compiled (a compact source file, or a loose file or plain
     * folder jdtls could not build); -1 when none. A replace there that jdtls can't build is not the user's error.
     */
    volatile long javacEpoch = -1;

    /** Whether jdtls built a file without a build project for the launch now starting; set by the caller. */
    volatile boolean looseBuilt;

    /**
     * Says that jdtls's build of the next launch's file (a loose file or a plain-folder project, which have no
     * build tool) has just succeeded, so its output may be launched; read once by that launch. Without it the
     * launch compiles the file itself.
     */
    public void setLooseClassesBuilt(boolean built) {
        this.looseBuilt = built;
    }

    /** See {@link JavaLaunches#ownMainClass}. */
    static @Nullable String ownMainClass(Path file) {
        return JavaLaunches.ownMainClass(file);
    }

    /** See {@link JavaLaunches#startCompactSource}. */
    public void startCompactSource(Path file, String javaExecOverride) {
        javaLaunch.startCompactSource(file, javaExecOverride);
    }

    /** See {@link JavaLaunches#startCompactShebang}. */
    public void startCompactShebang(Path file, int sourceVersion, String javaExecOverride) {
        javaLaunch.startCompactShebang(file, sourceVersion, javaExecOverride);
    }

    /** See {@link JavaLaunches#startAttach}. */
    public void startAttach(@Nullable Path file, String host, int port) {
        javaLaunch.startAttach(file, host, port);
    }

    /** See {@link JavaLaunches#resolveLaunch}. */
    public void resolveLaunch(Path routingFile, MainClassOption opt, Consumer<ResolvedLaunch> cb) {
        javaLaunch.resolveLaunch(routingFile, opt, cb);
    }

    /** See {@link JavaLaunches#resolveMainClasses}. */
    public void resolveMainClasses(Path routingFile, Consumer<List<MainClassOption>> cb) {
        javaLaunch.resolveMainClasses(routingFile, cb);
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

    /** See {@link JavaLaunches#startLaunchMainClass}. */
    public void startLaunchMainClass(
            Path routingFile, MainClassOption opt, Path cwd, String javaExecOverride, boolean plainFolder) {
        javaLaunch.startLaunchMainClass(routingFile, opt, cwd, javaExecOverride, plainFolder);
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

    static @Nullable String firstOrNull(List<String> l) {
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    /** Sets the java-debug settings the next Java session starts with (FX thread). */
    public void setJavaDebugSettings(JavaDebugSettings settings) {
        shared.javaDebugSettings = settings == null ? JavaDebugSettings.defaults() : settings;
    }

    /** The java-debug settings to send: Settings' with the Java Don't step into classes. */
    String javaSettingsJson() {
        return shared.javaDebugSettings.toJson(
                StepFilters.classPatterns("java", StepFilters.patterns(shared.stepFilters, "java")));
    }

    /**
     * Don't step into ({@link StepFilters}): every language's filters. A session launched from now on gets its
     * debugger's own options; a live Java session's java-debug is told at once (it reads them at each step), and the
     * rest are caught as their steps end ({@link #stepFilterPatterns}).
     */
    public void setStepFilters(List<StepFilter> filters) {
        List<StepFilter> copies = new ArrayList<>();
        for (StepFilter f : filters == null ? List.<StepFilter>of() : filters) {
            copies.add(f.copy());
        }
        shared.stepFilters = List.copyOf(copies);
        if (copies.equals(liveStepFilters)) {
            return;
        }
        liveStepFilters = List.copyOf(copies);
        if (client != null && state != State.INACTIVE && javaDebugSession()) {
            executeCommand(debugFile, JavaDebugSettings.COMMAND, List.of(javaSettingsJson()), (r, e) -> {
                if (e != null) {
                    LOG.log(Level.FINE, "java-debug step filters were not updated", e);
                }
            });
        }
    }

    /** The filters this session's java-debug was last told of (each session tells its own). */
    private List<StepFilter> liveStepFilters = List.of();

    /** The patterns of the Don't step into filters that are on for this session's language. */
    public List<String> stepFilterPatterns() {
        return StepFilters.patterns(shared.stepFilters, sessionLanguage);
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
    void startProgram(Path file, String language) {
        startProgram(file, language, null);
    }

    /**
     * As {@link #startProgram(Path, String)}; for Python, {@code module} (non-null) runs {@code python -m} that
     * module with its own arguments and folder instead of the file — a test under pytest. {@code file} still
     * names the session, picks the interpreter and anchors the breakpoints.
     */
    void startProgram(Path file, String language, PythonLaunch.@Nullable Target module) {
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

    void startProgram(
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
                        pythonSessions.startRemoteStdio(file, spec, bps, filters, epoch, module);
                    } else {
                        pythonSessions.startStdio(file, spec, bps, filters, epoch, module, configured);
                    }
                } else if (spec.kind() == DapServerRegistry.Kind.JVM && !remote) {
                    jvmSessions.startJvm(file, spec, bps, filters, epoch);
                } else if (spec.kind() == DapServerRegistry.Kind.LANGUAGE_SERVER && !remote) {
                    if (serverDebugs(file, language)) {
                        jvmSessions.startHosted(file, spec, bps, filters, epoch);
                    } else if (DapServerRegistry.fallbackKind(language) == DapServerRegistry.Kind.JVM) {
                        jvmSessions.startJvm(file, spec, bps, filters, epoch);
                    } else {
                        fail(epoch, tr("debug.error.transport", language));
                    }
                } else if (spec.kind() == DapServerRegistry.Kind.SOCKET) {
                    if (remote) {
                        jsSessions.startRemoteSocket(file, spec, bps, filters, epoch);
                    } else {
                        jsSessions.startSocket(file, spec, bps, filters, epoch, configured);
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

    /** See {@link ExternalLaunches#configureExternal}. */
    public void configureExternal(
            DebugLanguage language, boolean on, String command, java.util.function.@Nullable Consumer<Boolean> onDone) {
        externalLaunches.configureExternal(language, on, command, onDone);
    }

    /**
     * Puts {@code dirs} first on the PATH of adapter language {@code languageId}'s adapter from its next session on
     * (Go: the folder of the {@code go} Settings names, so that Delve builds with it).
     */
    public void setExternalPathDirs(String languageId, List<String> dirs) {
        shared.externalPathDirs.put(languageId, List.copyOf(dirs));
    }

    /** See {@link ExternalLaunches#pathWithFirst}. */
    static String pathWithFirst(List<String> first, @Nullable String path, String separator) {
        return ExternalLaunches.pathWithFirst(first, path, separator);
    }

    /** See {@link ExternalLaunches#locateExternal}. */
    public static @Nullable String locateExternal(DebugLanguage language, @Nullable String command) {
        return ExternalLaunches.locateExternal(language, command);
    }

    /** See {@link ExternalLaunches#installedAdapter}. */
    static @Nullable InstalledAdapter installedAdapter(DebugLanguage language) {
        return ExternalLaunches.installedAdapter(language);
    }

    /** See {@link ExternalLaunches#goBinDirs}. */
    static List<Path> goBinDirs(Map<String, String> env, Path home) {
        return ExternalLaunches.goBinDirs(env, home);
    }

    /** See {@link ExternalLaunches#gemUserBinDirs}. */
    static List<Path> gemUserBinDirs(Path home) {
        return ExternalLaunches.gemUserBinDirs(home);
    }

    /** Whether {@link #configureExternal} found {@code language}'s adapter (GDB's, a user's own). */
    public boolean isExternalLocated(DebugLanguage language) {
        return shared.enabled && shared.externalLocated.containsKey(language.id());
    }

    /** See {@link ExternalLaunches#locateExecutable}. */
    static @Nullable String locateExecutable(String command) {
        return ExternalLaunches.locateExecutable(command);
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

    /** See {@link ExternalLaunches#startExternal}. */
    public void startExternal(Path file, DebugLanguage language, Map<String, Object> launch, List<String> adapterTail) {
        externalLaunches.startExternal(file, language, launch, adapterTail);
    }

    /** See {@link ExternalLaunches#launchWait}. */
    static java.time.Duration launchWait(DebugLanguage language) {
        return ExternalLaunches.launchWait(language);
    }

    /** See {@link ExternalLaunches#adapterFolder}. */
    static @Nullable Path adapterFolder(Path file, Map<String, Object> launch, boolean programRuns) {
        return ExternalLaunches.adapterFolder(file, launch, programRuns);
    }

    /**
     * Attaches to a program that is its own debug adapter and listens on this machine's {@code port} (Ruby under
     * {@code rdbg --open}): the client talks DAP to it directly and sends {@code attach}. Stop detaches; the program
     * runs on.
     */
    public void startPortAttach(Path anchor, DebugLanguage language, int port, Map<String, Object> attach) {
        externalLaunches.startSocketAttach(anchor, language, "127.0.0.1", port, attach, false);
    }

    /**
     * Attaches to a Go program under Delve's headless server at {@code host:port} ({@code dlv debug --headless
     * --listen=…}, here or on another machine; {@link GoLaunch#remoteAttach}): that server talks DAP itself, so no
     * Delve runs here. Stop takes the session's breakpoints out and resumes the program before it detaches, which
     * leaves a multi-client server ({@code --accept-multiclient}) and its program running for the next attach; a
     * single-client one ends, and ends the program it started.
     */
    public void startRemoteDelve(Path anchor, String host, int port, Map<String, Object> attach) {
        externalLaunches.startSocketAttach(anchor, DebugLanguages.GO, host, port, attach, true);
    }

    /** See {@link PythonSessions#startPythonAttach}. */
    public void startPythonAttach(Path anchor, String host, int port) {
        pythonSessions.startPythonAttach(anchor, host, port);
    }

    /** See {@link PythonSessions#startPythonChild}. */
    public void startPythonChild(@Nullable Path anchor, Map<String, Object> configuration) {
        pythonSessions.startPythonChild(anchor, configuration);
    }

    /** See {@link PythonSessions#startPythonTest}. */
    public void startPythonTest(Path anchor, List<String> pytestArgs, List<String> unittestArgs, Path cwd) {
        pythonSessions.startPythonTest(anchor, pytestArgs, unittestArgs, cwd);
    }

    /** See {@link PythonSessions#startPythonModule}. */
    public void startPythonModule(Path anchor, String module, List<String> args, Path cwd) {
        pythonSessions.startPythonModule(anchor, module, args, cwd);
    }

    /** See {@link PythonSessions#startPythonScript}. */
    public void startPythonScript(Path script, List<String> args, Path cwd) {
        pythonSessions.startPythonScript(script, args, cwd);
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

    /** See {@link PythonSessions#launchFailure}. */
    static String launchFailure(Throwable error, Map<String, Object> launch) {
        return PythonSessions.launchFailure(error, launch);
    }

    /** Finds the tool a JVM language's program runs with ({@code groovy}, {@code scala-cli}); a test supplies one. */
    volatile java.util.function.Function<String, @Nullable String> jvmToolLocator = com.editora.run.JvmTools::locate;

    /** Test seam for {@link #jvmToolLocator}: a stand-in tool, so the launch can be checked without one installed. */
    void setJvmToolLocatorForTest(java.util.function.Function<String, @Nullable String> locator) {
        this.jvmToolLocator = locator == null ? com.editora.run.JvmTools::locate : locator;
    }

    static String readQuietly(Path file) {
        try {
            return java.nio.file.Files.readString(file);
        } catch (java.io.IOException | RuntimeException e) {
            return "";
        }
    }

    /** See {@link JvmSessions#serverDebugs}. */
    static boolean serverDebugs(Path file, String language) {
        return JvmSessions.serverDebugs(file, language);
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
    volatile @Nullable JsProgram jsProgram;

    /** Sets {@link #jsProgram}; {@code null} for an ordinary launch of a file. */
    public void setJsProgram(@Nullable JsProgram program) {
        this.jsProgram = program;
    }

    /** Where js-debug's Node.js is looked for when the chosen one is too old; a test supplies its own. */
    volatile Supplier<List<String>> adapterNodeFallbacks = JsAdapterNode::localFallbacks;

    /** Test seam for {@link #adapterNodeFallbacks}. */
    void setAdapterNodeFallbacksForTest(Supplier<List<String>> fallbacks) {
        this.adapterNodeFallbacks = fallbacks;
    }

    /**
     * Attaches the JavaScript debugger to a Node.js waiting for one on its inspector {@code port} on this machine —
     * an HTTP Client script started with {@code --inspect-brk} — which js-debug resumes once the breakpoints are set.
     * See {@link #startJsAttach(String, int, String, Path, List, boolean)}.
     */
    public void startJsAttach(int port, String name, Path cwd, List<String> skipFiles) {
        startJsAttach("127.0.0.1", port, name, cwd, skipFiles, true);
    }

    /** See {@link JsSessions#startJsAttach}. */
    public void startJsAttach(
            String host, int port, String name, @Nullable Path cwd, List<String> skipFiles, boolean continueOnAttach) {
        jsSessions.startJsAttach(host, port, name, cwd, skipFiles, continueOnAttach);
    }

    /**
     * Debugs a web page in a browser: js-debug is started as for Node.js and sent {@code request}, a
     * {@link BrowserLaunch} {@code launch} (it opens the browser, which Stop closes) or {@code attach} (to a browser
     * listening on its debugging port, which Stop leaves open). Each page is a child session js-debug asks for
     * ({@code startDebugging}), as a Node.js worker is. {@code anchor} names the session's folder or page; Restart
     * sends the same request again.
     */
    public void startBrowser(@Nullable Path anchor, Map<String, Object> request) {
        Map<String, Object> copy = Map.copyOf(request);
        jsSessions.startJsRequest(anchor, () -> new java.util.LinkedHashMap<>(copy), () -> startBrowser(anchor, copy));
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

    /** See {@link JsSessions#listeningPort}. */
    static int listeningPort(java.io.InputStream out, java.time.Duration timeout) {
        return JsSessions.listeningPort(out, timeout);
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** See {@link PythonSessions#webOrScript}. */
    static PythonLaunch.Target webOrScript(Path file, @Nullable String content, List<String> args, boolean local) {
        return PythonSessions.webOrScript(file, content, args, local);
    }

    /** See {@link PythonSessions#servesWeb}. */
    static boolean servesWeb(
            PythonLaunch.Target target, @Nullable String content, @Nullable Map<String, Object> configured) {
        return PythonSessions.servesWeb(target, content, configured);
    }

    /** See {@link PythonSessions#withoutReload}. */
    static PythonLaunch.Target withoutReload(PythonLaunch.Target target) {
        return PythonSessions.withoutReload(target);
    }

    static @Nullable String cwdOf(Path file) {
        return file.getParent() == null ? null : file.getParent().toString();
    }

    /** Where a debuggee's runtime is asked its version: {@code file}'s folder, so its host; null is here. */
    static @Nullable Path runtimeWhere(@Nullable Path file) {
        return file == null ? null : file.getParent();
    }

    /** See {@link PythonSessions#pythonRuntime}. */
    static List<String> pythonRuntime(Map<String, Object> launch) {
        return PythonSessions.pythonRuntime(launch);
    }

    /** Where a debuggee's runtime is asked its version: its launch folder, named as the adapter's machine names
     *  it (so its host); null is here. */
    static @Nullable Path runtimeWhere(DapPaths paths, @Nullable String cwd) {
        return cwd == null || cwd.isBlank() ? null : paths.fromAdapter(cwd);
    }

    // --- Controls -------------------------------------------------------------------------------

    /** See {@link ExecutionControl#resume}. */
    public void resume() {
        execution.resume();
    }

    /** See {@link ExecutionControl#pause}. */
    public void pause() {
        execution.pause();
    }

    /** See {@link ExecutionControl#threadStatuses}. */
    public void threadStatuses(Consumer<List<DapModels.ThreadStatus>> cb) {
        execution.threadStatuses(cb);
    }

    /** See {@link ExecutionControl#threadStatuses}. */
    static List<DapModels.ThreadStatus> threadStatuses(
            List<DapModels.ThreadInfo> threads,
            Map<Integer, String> stopped,
            boolean allSuspended,
            java.util.Set<Integer> resumedSinceSuspendAll,
            int shownId) {
        return ExecutionControl.threadStatuses(threads, stopped, allSuspended, resumedSinceSuspendAll, shownId);
    }

    /** See {@link ExecutionControl#pauseThread}. */
    public void pauseThread(int threadId) {
        execution.pauseThread(threadId);
    }

    /** See {@link ExecutionControl#resumeThread}. */
    public void resumeThread(int threadId) {
        execution.resumeThread(threadId);
    }

    /** See {@link ExecutionControl#showSuspendedThread}. */
    public boolean showSuspendedThread(int threadId, String reason) {
        return execution.showSuspendedThread(threadId, reason);
    }

    /** See {@link ExecutionControl#threads}. */
    public void threads(Consumer<List<DapModels.ThreadInfo>> cb) {
        execution.threads(cb);
    }

    /** The threads' names from the last {@code threads} reply, until a thread starts or ends (FX thread). */
    final Map<Integer, String> threadNames = new HashMap<>();

    /** See {@link ExecutionControl#threadName}. */
    public void threadName(int threadId, Consumer<String> cb) {
        execution.threadName(threadId, cb);
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

    /** See {@link InspectionRequests#loadedSources}. */
    public void loadedSources(Consumer<List<DapModels.LoadedSource>> cb) {
        inspection.loadedSources(cb);
    }

    /** See {@link InspectionRequests#modules}. */
    public void modules(Consumer<List<DapModels.ModuleInfo>> cb) {
        inspection.modules(cb);
    }

    /** See {@link InspectionRequests#updateReferenceBreakpoints}. */
    public void updateReferenceBreakpoints(
            DapModels.LoadedSource source,
            List<DapModels.LineBreakpoint> lines,
            Consumer<List<DapModels.BreakpointStatus>> cb) {
        inspection.updateReferenceBreakpoints(source, lines, cb);
    }

    /** See {@link InspectionRequests#sourceContent}. */
    public void sourceContent(DapModels.LoadedSource source, Consumer<@Nullable String> cb) {
        inspection.sourceContent(source, cb);
    }

    /** Switches the inspected thread and loads its call stack (callback on the FX thread). */
    public void selectThread(int threadId, Consumer<List<DapModels.StackFrameInfo>> cb) {
        currentThreadId = threadId;
        stackTrace(threadId, cb);
    }

    /** While a run-to-cursor temp breakpoint is planted: the file holding it (re-sent clean on stop). */
    @Nullable
    Path tempBreakpointFile;

    /** The 0-based line of the run-to-cursor temp breakpoint in {@link #tempBreakpointFile}. */
    int tempBreakpointLine = -1;

    /**
     * The program was resumed toward the run-to-cursor line, by Run to Cursor itself or by a Resume from a stop on the
     * way (FX thread). A stop elsewhere then keeps the temp breakpoint for the next resume: the window resumes some
     * stops without showing them — a logpoint it writes itself, a recording breakpoint, a breakpoint whose filters or
     * hit count did not match, Thread Dump's pause — and clearing it at those left Run to Cursor never arriving. A stop
     * reached any other way (a step) ends the trip.
     */
    boolean tempTripResumed;

    /** See {@link ExecutionControl#runToCursor}. */
    public void runToCursor(Path file, int line) {
        execution.runToCursor(file, line);
    }

    /** Files whose breakpoints Force Run to Cursor took away for the trip; restored at the next stop (FX thread). */
    List<Path> forceRunMuted = List.of();

    /** Whether Force Run to Cursor took the method breakpoints and field watchpoints away for the trip (FX thread). */
    boolean forceRunMutedMembers;

    /** See {@link ExecutionControl#forceRunToCursor}. */
    public void forceRunToCursor(Path file, int line) {
        execution.forceRunToCursor(file, line);
    }

    /**
     * Whether the connected adapter logs logpoints itself; when it does not (netcoredbg), the window logs the message
     * of a logpoint the program stopped on and goes on. True with no session.
     */
    public boolean supportsLogPoints() {
        DapClient c = client;
        return c == null || c.supportsLogPoints();
    }

    /** Whether the connected adapter supports Jump to Line ({@code gotoTargets}/{@code goto}) —
     *  debugpy does; java-debug and vscode-js-debug currently do not. */
    public boolean supportsJumpToLine() {
        DapClient c = client;
        return c != null && c.supportsGotoTargets();
    }

    /** See {@link ExecutionControl#jumpToLine}. */
    public void jumpToLine(Path file, int line, Consumer<String> onError) {
        execution.jumpToLine(file, line, onError);
    }

    /** See {@link ExecutionControl#withTempLine}. */
    static DapModels.FileBreakpoints withTempLine(List<DapModels.FileBreakpoints> all, Path file, int line) {
        return ExecutionControl.withTempLine(all, file, line);
    }

    /** See {@link ExecutionControl#stopShown}. */
    public void stopShown() {
        execution.stopShown();
    }

    public void stepOver() {
        execution.step(DapClient::next);
    }

    public void stepInto() {
        execution.step(DapClient::stepIn);
    }

    public void stepOut() {
        execution.step(DapClient::stepOut);
    }

    /** Step Over one machine instruction (the Disassembly view's step), where the adapter steps that finely. */
    public void stepOverInstruction() {
        execution.step((c, threadId) -> c.next(threadId, true));
    }

    /** Step Into one machine instruction (into a call), where the adapter steps that finely. */
    public void stepIntoInstruction() {
        execution.step((c, threadId) -> c.stepIn(threadId, true));
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

    /** Whether the stopped session's adapter takes instruction breakpoints (CodeLLDB, GDB, Delve). */
    public boolean supportsInstructionBreakpoints() {
        DapClient c = client;
        return c != null && c.supportsInstructionBreakpoints();
    }

    /** The shown session's instruction breakpoints: their addresses, as the Disassembly view gave them. */
    public List<String> instructionBreakpoints() {
        DapClient c = client;
        return c == null ? List.of() : c.instructionBreakpoints();
    }

    /** The conditions of the shown session's instruction breakpoints, by address (none: not there). */
    public Map<String, DapModels.InstructionConditions> instructionConditions() {
        DapClient c = client;
        return c == null ? Map.of() : c.instructionConditions();
    }

    /** Whether the shown session's adapter takes a condition, and a hit count, on a breakpoint: {@code [cond, hit]}. */
    public boolean[] instructionConditionSupport() {
        DapClient c = client;
        return new boolean[] {c != null && c.supportsConditions(), c != null && c.supportsHitConditions()};
    }

    /** See {@link LowLevelRequests#setInstructionBreakpoints}. */
    public void setInstructionBreakpoints(
            List<String> addresses, Consumer<List<DapModels.InstructionBreakpoint>> cb, Consumer<String> failed) {
        lowLevel.setInstructionBreakpoints(addresses, cb, failed);
    }

    /** See {@link LowLevelRequests#setInstructionBreakpoints}. */
    public void setInstructionBreakpoints(
            List<String> addresses,
            Map<String, DapModels.InstructionConditions> conditions,
            Consumer<List<DapModels.InstructionBreakpoint>> cb,
            Consumer<String> failed) {
        lowLevel.setInstructionBreakpoints(addresses, conditions, cb, failed);
    }

    /** See {@link LowLevelRequests#disassemble}. */
    public void disassemble(
            String memoryReference, int instructionOffset, int count, Consumer<List<DapModels.Instruction>> cb) {
        lowLevel.disassemble(memoryReference, instructionOffset, count, cb);
    }

    /** Bytes before the instruction GDB is asked to disassemble from, per instruction wanted before it. */
    public static final int GDB_BYTES_PER_INSTRUCTION = 4;

    /** Start bytes tried, one byte further back each time, before GDB's listing is taken from the address on. */
    public static final int GDB_SYNC_TRIES = 8;

    /** See {@link LowLevelRequests#aroundAddress}. */
    static @Nullable List<DapModels.Instruction> aroundAddress(
            List<DapModels.Instruction> listing, String address, int before, int count) {
        return LowLevelRequests.aroundAddress(listing, address, before, count);
    }

    /** See {@link LowLevelRequests#parseAddress}. */
    public static @Nullable Long parseAddress(@Nullable String address) {
        return LowLevelRequests.parseAddress(address);
    }

    /** See {@link LowLevelRequests#readMemory}. */
    public void readMemory(
            String memoryReference,
            long offset,
            int count,
            Consumer<DapModels.MemoryBlock> cb,
            Consumer<String> failed) {
        lowLevel.readMemory(memoryReference, offset, count, cb, failed);
    }

    /** Whether the session changes the program's memory ({@code writeMemory}: CodeLLDB, GDB, Delve). */
    public boolean supportsWriteMemory() {
        DapClient c = client;
        return c != null && c.supportsWriteMemory();
    }

    /** See {@link LowLevelRequests#writeMemory}. */
    public void writeMemory(
            String memoryReference, long offset, byte[] data, Consumer<Integer> cb, Consumer<String> failed) {
        lowLevel.writeMemory(memoryReference, offset, data, cb, failed);
    }

    /** See {@link LowLevelRequests#memoryReferenceOf}. */
    public void memoryReferenceOf(String expression, int frameId, Consumer<@Nullable String> cb) {
        lowLevel.memoryReferenceOf(expression, frameId, cb);
    }

    /**
     * The expression that asks for {@code expression}'s address, {@code &(expression)}, for when its value has no
     * memory reference: GDB gives a C array none (only a pointer one), and the native languages that read memory
     * (C, C++, Rust, Go) all take {@code &}. Null when it already asks for an address. Pure.
     */
    static @Nullable String addressOf(String expression) {
        String e = expression.strip();
        return e.isEmpty() || e.startsWith("&") ? null : "&(" + e + ")";
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
        debuggees.killDebuggee(); // disconnect(terminateDebuggee) does not end a program the adapter did not start
        if (c != null) {
            // Ended for us now; the wait for the adapter's answer and the close happen off the FX thread.
            c.disposeInBackground();
        }
        sessionEvents.killAdapterProgram(); // an adapter that is gone cannot end the program it launched
        javaLaunch.releaseCompilationDirectory();
        debuggees.releaseExitWrapper();
        debuggees.releaseGoDebugBinary();
        setState(State.INACTIVE);
    }

    // --- the program the adapter launched (java-debug's processid) ------------------------------------

    /** The session whose adapter announced it was done ({@code terminated}); its transport closing is normal. */
    volatile long terminatedEpoch = -1;

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

    /** See {@link InspectionRequests#threadStacks}. */
    public void threadStacks(Consumer<List<DapModels.ThreadStack>> cb) {
        inspection.threadStacks(cb);
    }

    /** The frame-file looks of the stack replies so far, in the order the replies came (see {@link #fxAfterLooks}). */
    private CompletableFuture<?> frameLooks = CompletableFuture.completedFuture(null);

    /**
     * {@link #fx(Runnable)} once {@code frames}' files have been looked at, off the FX thread and off the adapter's
     * reader thread (PERF-01; perf review 2026-11-23, D2), before the FX thread picks one. Replies reach the FX thread
     * in the order they came, though one waited for its looks and the next did not.
     */
    synchronized void fxAfterLooks(@Nullable List<DapModels.StackFrameInfo> frames, Runnable r) {
        frameLooks = frameLooks
                .thenCompose(v -> FrameFiles.warmAsync(frames))
                .handle((v, e) -> null)
                .thenRun(() -> fx(r))
                .exceptionally(e -> null); // the next reply's turn comes all the same
    }

    /** See {@link InspectionRequests#stackTrace}. */
    public void stackTrace(int threadId, Consumer<List<DapModels.StackFrameInfo>> cb) {
        inspection.stackTrace(threadId, cb);
    }

    /**
     * The page of the stack the last stop or thread switch asked for (FX thread): how deep the stack is and whether
     * the Call Stack can list more of it ({@link #moreFrames}).
     */
    DapModels.StackPage shownStack = DapModels.StackPage.EMPTY;

    /** See {@link #shownStack}. */
    public DapModels.StackPage shownStack() {
        return shownStack;
    }

    /** See {@link InspectionRequests#moreFrames}. */
    public void moreFrames(int threadId, int loaded, Consumer<DapModels.StackPage> cb) {
        inspection.moreFrames(threadId, loaded, cb);
    }

    /** See {@link InspectionRequests#scopes}. */
    public void scopes(int frameId, Consumer<List<DapModels.ScopeInfo>> cb) {
        inspection.scopes(frameId, cb);
    }

    public void variables(int ref, Consumer<List<DapModels.VariableInfo>> cb) {
        variables(ref, null, 0, 0, cb);
    }

    /** See {@link InspectionRequests#variables}. */
    public void variables(
            int ref, @Nullable String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
        inspection.variables(ref, filter, start, count, cb);
    }

    /** See {@link InspectionRequests#loadValue}. */
    public void loadValue(DapModels.VariableInfo row, Consumer<DapModels.VariableInfo> cb) {
        inspection.loadValue(row, cb);
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

    /** See {@link InspectionRequests#setHexValues}. */
    public void setHexValues(boolean on) {
        inspection.setHexValues(on);
    }

    /** Lazy rows resolved during the current stop (B101, B201); forgotten whenever the program runs. */
    final LazyValues lazyValues = new LazyValues();

    // --- strings java-debug garbles (follow-up 4: emoji arrive as two wrong characters) -------------------

    /**
     * Whether the session is java-debug's: its string values may need {@link JdwpStringRepair}, and it pauses and
     * resumes threads one at a time unless asked for all of them.
     */
    boolean javaDebugSession() {
        return "java".equals(sessionLanguage);
    }

    /** See {@link InspectionRequests#evaluate}. */
    public void evaluate(String expression, int frameId, String context, Consumer<ConsoleEvaluation> cb) {
        inspection.evaluate(expression, frameId, context, cb);
    }

    /** Console evaluations the adapter has not answered yet (FX thread); reset with each session. */
    int consoleEvaluations;

    /**
     * Whether an expression typed in the Debug console is still being evaluated. The program runs it on the
     * stopped thread, and the adapter takes the next request only when it is done: a Step pressed meanwhile was
     * queued there and ran many seconds later, with nothing shown in between.
     */
    public boolean evaluationRunning() {
        return consoleEvaluations > 0;
    }

    /** Whether the session opened a core dump, which is looked at and never run (Continue, Step, Run to Cursor…). */
    public boolean isPostMortem() {
        return postMortem && state != State.INACTIVE;
    }

    /** In a core dump's session: says it cannot be run, and true. */
    boolean refusePostMortem() {
        if (!isPostMortem()) {
            return false;
        }
        listener.onStatus(tr("status.debug.coreCannotRun"));
        return true;
    }

    /** Refuses a control while a console evaluation runs, saying why (FX thread); true when refused. */
    boolean refuseWhileEvaluating() {
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

    /** What a console evaluation that timed out says: it has not failed, the program is still running it. */
    static String stillEvaluating() {
        return stillEvaluating(DapClient.requestTimeoutSeconds());
    }

    /** {@link #stillEvaluating()} after {@code seconds} of waiting (the session's {@link EvaluationLimits#wait}). */
    static String stillEvaluating(long seconds) {
        return tr("debug.eval.stillRunning", seconds);
    }

    /** See {@link InspectionRequests#evaluateFull}. */
    public void evaluateFull(String expression, int frameId, String context, Consumer<DapModels.EvalResult> cb) {
        inspection.evaluateFull(expression, frameId, context, cb);
    }

    /** See {@link InspectionRequests#evaluateHover}. */
    public void evaluateHover(String expression, int frameId, Consumer<String> cb) {
        inspection.evaluateHover(expression, frameId, cb);
    }

    /**
     * Sets a variable. {@code cb} gets the value the adapter reports back — only when the adapter accepted it;
     * a refusal (wrong type, a final field, an unparsable value) goes to {@link Listener#onError} and leaves
     * {@code cb} uncalled, so the caller never shows the typed text as if it were the variable's new value.
     */
    public void setVariable(int ref, String name, String value, Consumer<String> cb) {
        setVariable(ref, name, value, null, -1, cb);
    }

    /** See {@link InspectionRequests#setVariable}. */
    public void setVariable(
            int ref, String name, String value, @Nullable String assignTo, int frameId, Consumer<String> cb) {
        inspection.setVariable(ref, name, value, assignTo, frameId, cb);
    }

    /** See {@link InspectionRequests#setExpression}. */
    public void setExpression(String expression, String value, int frameId, int variableRef, Consumer<String> cb) {
        inspection.setExpression(expression, value, frameId, variableRef, cb);
    }

    /** See {@link InspectionRequests#assignValue}. */
    public void assignValue(String name, String assignTo, String value, int frameId, Consumer<String> cb) {
        inspection.assignValue(name, assignTo, value, frameId, cb);
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

    /** TEST SEAM — {@code setBreakpoints} requests the live session has not had answered yet (0 without one). */
    public int unansweredBreakpointsForTest() {
        DapClient c = client;
        return c == null ? 0 : c.unansweredBreakpointsForTest();
    }

    /** (Re)sends a file's breakpoints to the live adapter (after the user toggled one while running). */
    public void updateBreakpoints(DapModels.FileBreakpoints fb) {
        if (client != null && state != State.INACTIVE) {
            client.sendSetBreakpoints(javaLaunch.adapterBreakpoint(fb));
        }
    }

    // --- DAP events (launcher reader thread → FX), reached only through sessionHost(epoch) -------

    /**
     * {@code expression} as the session's debugger reads it: in a Bash session, a variable written as in a script
     * ({@code $total}, {@code ${total}}) is named bare, as bashdb's {@code examine} wants it (with a {@code $} it is
     * expanded in the debugger's own shell, where the script's variables are not set). Else as it is.
     */
    String adapterExpression(String expression) {
        return DebugLanguages.BASH.id().equals(sessionLanguage) ? ScriptLaunch.bashName(expression) : expression;
    }

    /** Whether this Bash session has had its first stop (bashdb stops at the script's first line). */
    boolean bashEntryPassed;

    /** See {@link SessionEvents#plainBreakpointAt}. */
    static boolean plainBreakpointAt(List<DapModels.FileBreakpoints> all, Path file, int line) {
        return SessionEvents.plainBreakpointAt(all, file, line);
    }

    /** Batches adapter output onto the FX thread (see {@link DapOutputPump}); the sink runs on FX. */
    final DapOutputPump outputPump = new DapOutputPump(Platform::runLater, (epoch, text, category) -> {
        if (isCurrent(epoch)) {
            listener.onOutput(text, category);
        }
    });

    /** When the adapter last sent program output, and for which session (see {@link OutputTail}). */
    record OutputStamp(long epoch, long nanos) {}

    volatile OutputStamp lastOutput = new OutputStamp(-1, 0);

    /**
     * TEST SEAM — makes the last program output {@code millis} older, so a test sees output that has stopped without
     * waiting for it to (QUAL-07, Pauta 6.2.0).
     */
    void ageLastOutputForTest(long millis) {
        OutputStamp last = lastOutput;
        lastOutput = new OutputStamp(last.epoch(), last.nanos() - TimeUnit.MILLISECONDS.toNanos(millis));
    }

    /** The session whose exit report waits for its trailing output; -1 for none. */
    long exitAwaitingOutput = -1;

    /** How long a program's output may trail its end before the console says so (choice 27). */
    static final long TRAILING_NOTICE_MILLIS = 1000;

    /** The inspector's lines dropped from this session's output (reset per session). */
    volatile JsLaunch.InspectorNoise jsNoise = new JsLaunch.InspectorNoise();

    /** An external adapter that is the debugged program itself (Ruby's rdbg), for session {@code epoch}. */
    record ProgramAdapter(long epoch, Process process) {}

    volatile @Nullable ProgramAdapter programAdapter;

    /** How long a program that is its own adapter has to exit after it says it terminated. */
    static final long PROGRAM_ADAPTER_EXIT_GRACE_MILLIS = 3_000;

    /** How long an adapter's launcher's end waits for the adapter's {@code exited} event before ending the session. */
    static final long LAUNCHER_EXIT_GRACE_MILLIS = 2_000;

    /** See {@link DebuggeeProcesses#isAdapterLauncher}. */
    static boolean isAdapterLauncher(List<String> argv) {
        return DebuggeeProcesses.isAdapterLauncher(argv);
    }

    /** How a file's modification time is read for the compiled-JavaScript check (a test holds it). */
    volatile java.util.function.Function<Path, Optional<java.time.Instant>> fileTimes = DebuggeeProcesses::modifiedTime;

    /** Test seam for {@link #fileTimes}. */
    void setFileTimesForTest(java.util.function.Function<Path, Optional<java.time.Instant>> times) {
        this.fileTimes = times;
    }

    /** The session served by java-debug (whose {@code exited} event carries no real exit code). */
    long javaAdapterEpoch = -1;

    /** The session whose program Editora started itself (its exit is reported by its own process). */
    long programStartedEpoch = -1;

    /** See {@link DebuggeeProcesses#prepareExitWrapper}. */
    static void prepareExitWrapper(@Nullable String java) {
        DebuggeeProcesses.prepareExitWrapper(java);
    }

    /** The {@code output} of the launch request of the session started last (Delve's program), or null. */
    volatile @Nullable Object goDebugBinary;

    // --- Internals ------------------------------------------------------------------------------

    /** Keeps the jdtls hosting a Java debug session from being evicted as idle; released when it ends. */
    Runnable lspLease = () -> {};

    void setState(State s) {
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
            threadNames.clear(); // the next session's thread ids are its own
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
    long beginSession() {
        jsNoise = new JsLaunch.InspectorNoise();
        postMortem = false;
        recording = false;
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
        debuggees.killDebuggee();
        if (previous != null) {
            previous.disposeInBackground();
        }
        sessionEvents.killAdapterProgram();
        lspLease.run(); // the old session's jdtls pin; a Java session takes its own once it has an adapter
        lspLease = () -> {};
        // A launch still waiting for its adapter has no client for ready() to stop, but it may already hold
        // its compiled classes: the next launch would overwrite the only reference to them (review D6).
        javaLaunch.releaseCompilationDirectory();
        debuggees.releaseExitWrapper();
        debuggees.releaseGoDebugBinary();
        return epoch;
    }

    boolean isCurrent(long epoch) {
        return !managerClosed && sessionEpoch == epoch;
    }

    boolean isCurrent(long epoch, @Nullable DapClient expected) {
        return isCurrent(epoch) && client == expected;
    }

    /** Atomically publishes a client only while the start operation that created it still owns the session. */
    boolean publishClient(long epoch, DapClient candidate) {
        synchronized (sessionLock) {
            if (!isCurrent(epoch)) {
                return false;
            }
            bashEntryPassed = false; // a new session: bashdb stops at its script's first line again
            candidate.setMemberBreakpoints(shared.memberBreakpoints); // sent on its initialized event
            candidate.setExtraExceptionFilters(
                    exceptionExtras(), exceptionTypes()); // its language's exception breakpoints need
            String language = sessionLanguage;
            List<String> skips = StepFilters.patterns(shared.stepFilters, language);
            int limit = shared.evaluationTimeLimit;
            GoGoroutines.Options goroutines = shared.goGoroutines;
            liveGoGoroutines = goroutines;
            PythonTemplates.Mode templates = shared.pythonTemplates;
            DapPaths paths = candidate.paths(); // a remote host's request names its host's folders
            candidate.setLaunchDecorator( // Don't step into, the evaluation time limit, Go's goroutines, templates
                    args -> PythonTemplates.decorate(
                            GoGoroutines.decorate(
                                    EvaluationLimits.decorate(StepFilters.decorate(language, args, skips), limit),
                                    goroutines),
                            templates,
                            paths,
                            DapManager::templatesIn));
            candidate.setEvaluationWait(EvaluationLimits.wait(limit));
            candidate.setHexValues(shared.hexValues);
            client = candidate;
            return true;
        }
    }

    DapClient.Host sessionHost(long epoch) {
        return new DapClient.Host() {
            @Override
            public void onStopped(int threadId, String reason) {
                sessionEvents.onStopped(epoch, threadId, reason, false);
            }

            @Override
            public void onStopped(int threadId, String reason, boolean allThreadsStopped) {
                sessionEvents.onStopped(epoch, threadId, reason, allThreadsStopped);
            }

            @Override
            public void onContinued() {
                sessionEvents.onContinued(epoch, 0, true);
            }

            @Override
            public void onContinued(int threadId, boolean allThreadsContinued) {
                sessionEvents.onContinued(epoch, threadId, allThreadsContinued);
            }

            @Override
            public void onOutput(String text, String category) {
                sessionEvents.onOutput(epoch, text, category);
            }

            @Override
            public void onTerminated() {
                terminatedEpoch = epoch; // a plain long written once per session: read on the FX thread later
                sessionEvents.onTerminated(epoch);
            }

            @Override
            public void onError(String message) {
                sessionEvents.onError(epoch, message);
            }

            @Override
            public void onProcessId(long pid) {
                fx(() -> sessionEvents.onProcessId(epoch, pid));
            }

            @Override
            public void onBreakpointStatus(Path file, List<DapModels.BreakpointStatus> statuses, boolean whole) {
                sessionEvents.onBreakpointStatus(epoch, file, statuses, whole);
            }

            @Override
            public void onNotice(String message, boolean error) {
                sessionEvents.onNotice(epoch, message, error);
            }

            @Override
            public void onExited(int exitCode) {
                debuggees.onExited(epoch, exitCode);
            }

            @Override
            public void onThreadsChanged() {
                fx(() -> {
                    threadNames.clear(); // a thread started or ended: names are asked for again
                    if (isCurrent(epoch)) {
                        listener.onThreadsChanged();
                    }
                });
            }

            @Override
            public void onHotCodeReplace(String changeType, String message) {
                codeReplace.onHotCodeReplace(epoch, changeType, message);
            }

            @Override
            public CompletableFuture<Long> onRunInTerminal(String cwd, List<String> argv, Map<String, String> env) {
                CompletableFuture<Long> started = new CompletableFuture<>();
                fx(() -> debuggees.startDebuggee(epoch, cwd, argv, env, started));
                return started;
            }

            @Override
            public void onTransportClosed(@Nullable Throwable error) {
                if (error != null) {
                    LOG.log(Level.FINE, "Debug adapter transport closed", error);
                }
                sessionEvents.onTransportClosed(epoch);
            }
        };
    }

    /**
     * Ends the starting session of {@code epoch} because of a failure with a report: {@code console} goes to
     * the Debug console (as error output, links and all) and {@code status} to the status line.
     */
    void failWithConsole(long epoch, String console, String status) {
        failWithConsole(epoch, console, status, false);
    }

    /** As above; {@code compileFailed}: the launched file did not compile, so nothing was started (review 3, A201). */
    void failWithConsole(long epoch, String console, String status, boolean compileFailed) {
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

    void fail(long epoch, @Nullable String message) {
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
            // "java.util.concurrent.TimeoutException" told the user nothing; a launch names its own (longer) wait
            return tr(
                    "status.debug.noAnswer",
                    t instanceof DapClient.NoAnswer waited ? waited.seconds : DapClient.requestTimeoutSeconds());
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
    java.util.function.BiConsumer<String, String> codeReplaceNotices = (type, message) -> {};

    /** Who is told java-debug's hot code replace events ({@code changeType}, message), on the FX thread. */
    public void setCodeReplaceNotices(java.util.function.BiConsumer<String, String> notices) {
        this.codeReplaceNotices = notices == null ? (type, message) -> {} : notices;
    }

    /** Whether a Java session is running or paused, so that saved code changes can be loaded into it. */
    public boolean canReplaceCode() {
        return client != null && "java".equals(sessionLanguage) && (state == State.RUNNING || state == State.SUSPENDED);
    }

    /** See {@link HotCodeReplace#primeCodeReplace}. */
    public void primeCodeReplace() {
        codeReplace.primeCodeReplace();
    }

    /** The file the session was started for (it routes a build to that session's jdtls); null when none. */
    public @Nullable Path sessionFile() {
        return debugFile;
    }

    /** See {@link HotCodeReplace#applyCodeChanges}. */
    public void applyCodeChanges(@Nullable Path file, Consumer<CodeReplaceOutcome> cb) {
        codeReplace.applyCodeChanges(file, cb);
    }

    /** See {@link HotCodeReplace#codeReplaceOutcome}. */
    static CodeReplaceOutcome codeReplaceOutcome(DapModels.CodeReplace reply, @Nullable String error) {
        return HotCodeReplace.codeReplaceOutcome(reply, error);
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
        execution.step((c, threadId) -> c.restartFrame(frameId));
    }

    // --- running backward ------------------------------------------------------------------------------------

    /** Whether the session is on GDB ({@code gdb -i dap}), which can record the program to go back over it. */
    public boolean isGdbSession() {
        return client != null
                && state != State.INACTIVE
                && DebugLanguages.GDB.id().equals(sessionLanguage);
    }

    /** Whether GDB is recording the program (Record Execution). */
    public boolean isRecording() {
        return recording && isGdbSession();
    }

    /**
     * Whether the program can run backward now: its adapter steps back (Delve on rr, CodeLLDB on rr), or GDB is
     * recording it. A core dump's session never runs.
     */
    public boolean canRunBackward() {
        DapClient c = client;
        return c != null && !isPostMortem() && (c.supportsStepBack() || isRecording());
    }

    /**
     * Record Execution, for a GDB session paused at a stop: GDB records every instruction from here ({@code record
     * full}), so Step Back and Reverse Continue can go back over them; again, it stops recording and forgets them.
     * {@code done} gets null when it worked, else what GDB said.
     */
    public void toggleRecording(Consumer<@Nullable String> done) {
        DapClient c = client;
        if (c == null || !isGdbSession() || state != State.SUSPENDED) {
            done.accept(tr("status.debug.recordNeedsGdbStop"));
            return;
        }
        boolean start = !recording;
        long epoch = sessionEpoch;
        c.consoleCommand(start ? "record full" : "record stop")
                .whenComplete((v, e) -> fx(() -> {
                    if (!isCurrent(epoch, c)) {
                        return;
                    }
                    if (e != null) {
                        done.accept(msg(
                                e instanceof java.util.concurrent.CompletionException && e.getCause() != null
                                        ? e.getCause()
                                        : e));
                        return;
                    }
                    recording = start;
                    listener.onState(state); // the controls that run backward appear (or go)
                    done.accept(null);
                }));
    }

    /** Step Back: back over the last statement — the adapter's {@code stepBack}, or GDB's {@code reverse-next}. */
    public void stepBack() {
        backward(DapClient::stepBack, "reverse-next");
    }

    /** Reverse Continue: back to the previous breakpoint, or as far as the recording goes. */
    public void reverseContinue() {
        backward(DapClient::reverseContinue, "reverse-continue");
    }

    private void backward(
            java.util.function.BiFunction<DapClient, Integer, java.util.concurrent.CompletableFuture<Void>> request,
            String gdbCommand) {
        DapClient c = client;
        if (c == null || state != State.SUSPENDED) {
            return;
        }
        if (refusePostMortem()) {
            return;
        }
        if (!canRunBackward()) {
            listener.onStatus(tr(isGdbSession() ? "status.debug.recordFirst" : "status.debug.cannotRunBackward"));
            return;
        }
        if (c.supportsStepBack()) {
            execution.step(request);
        } else {
            // GDB's DAP mode has no stepBack request: its own command, through its console, stops as a step does.
            execution.step(
                    (client, threadId) -> client.consoleCommand(gdbCommand).thenApply(r -> null));
        }
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
        c.stackPage(threadId, true, 0, 1)
                .thenApply(DapModels.StackPage::frames)
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
        execution.step((c, threadId) -> c.stepIn(threadId, targetId));
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
