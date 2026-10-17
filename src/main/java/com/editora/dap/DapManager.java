package com.editora.dap;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    public record MainClassOption(String mainClass, String projectName, String filePath) {
        /**
         * The plain fully-qualified class name. For a class of a named module jdtls answers {@code
         * <module>/<class>} in {@link #mainClass} (what the launch needs); a saved configuration and the
         * gutter name the class alone, so that is what a main class is matched by.
         */
        public String className() {
            return JavaLaunchSupport.className(mainClass);
        }
    }

    /**
     * The resolved launch inputs for a main class (from jdtls {@code resolveClasspath}/{@code
     * resolveJavaExecutable}): the java executable and the module/class paths, or an {@code error} message
     * when resolution failed. Reused by the non-debug Run path (which turns it into a {@code java} argv).
     */
    public record ResolvedLaunch(
            String javaExec, List<String> modulePaths, List<String> classPaths, String error, boolean enablePreview) {
        static ResolvedLaunch failed(String error) {
            return new ResolvedLaunch(null, List.of(), List.of(), error, false);
        }

        public boolean ok() {
            return error == null;
        }
    }

    /** Lets the controller present a chooser when several main classes are found (QuickOpen). */
    public interface MainClassPicker {
        void pick(List<MainClassOption> options, Consumer<MainClassOption> chosen);
    }

    /** UI sink (implemented by the controller / Debug panel); all calls arrive on the FX thread. */
    public interface Listener {
        void onState(State state);

        /** Execution suspended: the (already-fetched) call stack of {@code threadId}; top frame = where it stopped. */
        void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames);

        void onOutput(String text, String category);

        void onError(String message);

        /**
         * What the adapter says about {@code file}'s breakpoints (see {@link DapModels.BreakpointStatus}):
         * with {@code whole}, its answer for all of them — a breakpoint it does not list has no answer yet;
         * otherwise a later change to the ones listed. Only arrives while a session is live.
         */
        default void onBreakpointStatus(Path file, List<DapModels.BreakpointStatus> statuses, boolean whole) {}

        /** The exception {@code threadId} is stopped on; follows the {@link #onStopped} of an exception stop. */
        default void onExceptionInfo(int threadId, DapModels.ExceptionInfo info) {}

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

        /**
         * The session launched its program with {@code runtime} — the interpreter or {@code java} argv the
         * adapter starts it with, before the program's own arguments — in {@code where} (its folder, so its
         * host) with {@code env} on top of the user's environment. Not reported for an attach: the program
         * was already running, under whatever started it.
         */
        default void onDebuggeeRuntime(List<String> runtime, Path where, Map<String, String> env) {}
    }

    private final LspManager lsp;
    private Listener listener = noopListener();

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dap-connect");
        t.setDaemon(true);
        return t;
    });

    private boolean enabled;
    private String pluginPath = "";
    private List<String> bundlePaths = List.of();

    // Python (debugpy, stdio) + JavaScript (vscode-js-debug, socket) — the standalone adapters.
    private boolean pythonEnabled;
    private String pythonCommand = ""; // interpreter exe (blank ⇒ python3 on PATH)
    private Path debugpyDir; // resolved PYTHONPATH dir for debugpy (null ⇒ rely on interpreter)
    private boolean pythonAvailable;
    private boolean jsEnabled;
    private String jsPath = ""; // configured dapDebugServer.js path/dir (blank ⇒ auto-detect)
    private Path jsServer; // resolved dapDebugServer.js (null ⇒ not found)
    private boolean jsAvailable;

    private volatile DapClient client;
    private volatile long sessionEpoch;
    private volatile boolean managerClosed;
    private volatile Future<?> startupTask;
    private final Object sessionLock = new Object();
    private State state = State.INACTIVE;
    private int currentThreadId;
    /**
     * The threads known to be stopped, with each one's stop reason, oldest first (FX thread). java-debug
     * suspends and resumes <em>per thread</em>: two workers can sit on the same breakpoint, and resuming the
     * one on screen leaves the other suspended with no further event to say so.
     */
    private final java.util.LinkedHashMap<Integer, String> stoppedThreads = new java.util.LinkedHashMap<>();
    /** Counts stops shown, so a late step acknowledgement can tell the step has already ended. */
    private int stopCount;
    /** A step was acknowledged and its stop has not arrived yet (FX thread). */
    private boolean stepInFlight;
    /** The session epoch of a step request the adapter has not answered yet, else -1 (FX thread). */
    private long stepPendingEpoch = -1;

    private Path debugFile;
    /** Temporary .java copy used to compile an extensionless shebang, scoped to its launch epoch. */
    private volatile SourceAlias sourceAlias;

    private volatile Path compilationDirectory;
    /** Removes {@link #compilationDirectory} on its target ({@code null}: a folder of this machine). */
    private volatile Runnable compilationRemoval;

    private record SourceAlias(long epoch, Path original, Path compiled) {}

    /** Whether a Java launch asks the adapter to let Editora start the program (see {@link #setProgramConsole}). */
    private boolean programConsole;
    /** The program of the current session when Editora started it, else null (FX thread). */
    private Debuggee debuggee;
    /** How long a program may take to exit once the adapter has reported the session over, before it is killed. */
    private static final long DEBUGGEE_EXIT_GRACE_MILLIS = 3_000;

    private Supplier<List<DapModels.FileBreakpoints>> breakpointsSupplier = List::of;
    /** The exception filters chosen per adapter language; a language with none starts with the adapter's defaults. */
    private final Map<String, List<String>> exceptionChoices = new java.util.HashMap<>();
    /** The adapter language of the current (or last) session: {@code java}, {@code python} or {@code javascript}. */
    private String sessionLanguage = "java";

    private Runnable restartAction;

    public DapManager(LspManager lsp) {
        this.lsp = lsp;
    }

    public void setListener(Listener listener) {
        this.listener = listener == null ? noopListener() : listener;
    }

    /** Supplies all open buffers' breakpoints (sent to the adapter on the {@code initialized} event). */
    public void setBreakpointsSupplier(Supplier<List<DapModels.FileBreakpoints>> supplier) {
        this.breakpointsSupplier = supplier == null ? List::of : supplier;
    }

    /**
     * Chooses {@code language}'s exception filters ({@code null}: back to the adapter's defaults). A live session
     * of that language gets them at once; the next one starts with them.
     */
    public void setExceptionFilters(String language, List<String> filters) {
        if (filters == null) {
            exceptionChoices.remove(language);
        } else {
            exceptionChoices.put(language, List.copyOf(filters));
        }
        if (client != null && language.equals(sessionLanguage)) {
            client.setExceptionFilters(filters == null ? null : List.copyOf(filters));
        }
    }

    /** The current session's exception filters ({@code null}: its adapter's defaults) — kept for callers that do
     *  not name a language. */
    public void setExceptionFilters(List<String> filters) {
        setExceptionFilters(sessionLanguage, filters);
    }

    /** The filters chosen for {@code language}, or null when the adapter's defaults apply. */
    private List<String> filtersFor(String language) {
        sessionLanguage = language;
        return exceptionChoices.get(language);
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
        return new java.util.LinkedHashMap<>(exceptionChoices);
    }

    /** The exception filters in force for {@code language}: the chosen ones, else the adapter's defaults. */
    public List<String> exceptionFiltersFor(String language) {
        List<String> chosen = exceptionChoices.get(language);
        return chosen != null ? chosen : DapClient.defaultExceptionFilters(exceptionFilterOptions(language));
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
        this.programConsole = programConsole;
    }

    /** Whether a line typed now would reach the debugged program's standard input. */
    public boolean programInputAvailable() {
        return debuggee != null && debuggee.acceptsInput();
    }

    /** Sends {@code line} (a line terminator is added) to the debugged program's standard input; false when
     *  there is no such program to send it to. */
    public boolean sendProgramInput(String line) {
        if (!programInputAvailable() || line == null) {
            return false;
        }
        debuggee.sendInput(line);
        return true;
    }

    /** Ends the debugged program's standard input (end of file); false when there is none to end. */
    public boolean closeProgramInput() {
        if (!programInputAvailable()) {
            return false;
        }
        debuggee.closeInput();
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
        this.enabled = enabled;
        this.pluginPath = javaPluginPath == null ? "" : javaPluginPath;
        this.bundlePaths = enabled ? locate() : List.of();
        this.pythonEnabled = pythonEnabled;
        this.pythonCommand = pythonCommand == null ? "" : pythonCommand;
        this.jsEnabled = jsEnabled;
        this.jsPath = jsPath == null ? "" : jsPath;
        // The python/js adapter locations are deliberately NOT resolved here. Locating them walks the VS
        // Code / mason / plugin directories, and the only caller (DebugCoordinator.applySupport) follows
        // this immediately with detectPython/detectJs, which relocate them off-thread and assign these very
        // fields. Resolving here as well duplicated that walk on the FX thread during startup — measured at
        // ~30-50 ms on the path to first paint, for values not read until a debug session actually launches.
        // Only the clearing has to happen synchronously: with the feature off, detect* never runs to null it.
        if (!enabled || !pythonEnabled) {
            this.debugpyDir = null;
            this.pythonAvailable = false;
        }
        if (!enabled || !jsEnabled) {
            this.jsServer = null;
            this.jsAvailable = false;
        }
    }

    /** The located java-debug plugin jar(s) — for {@code LspManager.setDebugBundles}. Empty when not found. */
    public List<String> bundlePaths() {
        return bundlePaths;
    }

    /**
     * Set when the running jdtls already advertises the java-debug commands — some distributions (e.g.
     * Homebrew's jdtls) ship and auto-load the plugin, so there is no jar for {@link #locate()} to find and
     * nothing to inject, yet debugging works. Pushed by the controller from the LSP capabilities (#711).
     */
    private volatile boolean serverProvidesJavaDebug;

    /** Records whether the running Java server itself provides the debug commands (see the field doc). */
    public void setServerProvidesJavaDebug(boolean provided) {
        this.serverProvidesJavaDebug = provided;
    }

    /**
     * Whether the java-debug plugin is installed — a jar is found, or the running jdtls provides it — whether
     * or not debugging is switched on. What Install and Settings ask (review A6, A7): asked through
     * {@link #isAdapterAvailable}, every Install re-downloaded a plugin that was there all along while
     * debugging was off, and Settings said "not found" for a jdtls that ships it.
     */
    public boolean isJavaDebugInstalled() {
        return serverProvidesJavaDebug || !locate().isEmpty();
    }

    /** Whether Java debugging can run: we located a bundle to inject, <b>or</b> jdtls already provides it. */
    public boolean isAdapterAvailable() {
        return javaDebugUsable();
    }

    private boolean javaDebugUsable() {
        return enabled && (!bundlePaths.isEmpty() || serverProvidesJavaDebug);
    }

    /** Whether a usable debug adapter is available for {@code language} (after detection). */
    public boolean isLanguageAvailable(String language) {
        if (language == null) {
            return false;
        }
        return switch (language) {
            case "java" -> javaDebugUsable();
            case "python" -> pythonAvailable;
            case "javascript" -> jsAvailable;
            default -> false;
        };
    }

    /** Re-runs the (filesystem) locate and delivers whether the plugin jar was found, on the FX thread. */
    public void detect(Consumer<Boolean> onResult) {
        io.submit(() -> {
            List<String> found = locate();
            Platform.runLater(() -> {
                this.bundlePaths = enabled ? found : List.of();
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
            boolean on = enabled && pythonEnabled;
            boolean installed = (on || installedOnly) && probeDebugpy(dir);
            Platform.runLater(() -> {
                this.debugpyDir = dir;
                this.pythonAvailable = on && installed;
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
            Path server =
                    DebugAdapterLocator.locateJsDebugServer(jsPath, home()).orElse(null);
            boolean ok = enabled && jsEnabled && server != null && probeNode();
            Platform.runLater(() -> {
                this.jsServer = server;
                this.jsAvailable = ok;
                onResult.accept(ok);
            });
        });
    }

    private List<String> locate() {
        return DebugAdapterLocator.locate(pluginPath, home())
                .map(p -> List.of(p.toString()))
                .orElse(List.of());
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home", ""));
    }

    /** Test seam: debugpy found in {@code dir} (a folder holding a {@code debugpy} package), detection done. */
    void setPythonAdapterForTest(Path dir) {
        this.debugpyDir = dir;
        this.pythonAvailable = true;
    }

    /** Runs {@code <python> -c "import debugpy"} (debugpyDir on PYTHONPATH); true on a clean exit. */
    private boolean probeDebugpy(Path dir) {
        List<String> interp = DapServerRegistry.interpreterArgv("python", pythonCommand);
        if (interp.isEmpty()) {
            return false;
        }
        List<String> argv = new ArrayList<>(interp);
        argv.add("-c");
        argv.add("import debugpy");
        return probe(argv, dir);
    }

    /** Runs {@code node --version}; true on a clean exit. */
    private boolean probeNode() {
        return probe(List.of("node", "--version"), null);
    }

    /** Launches {@code argv} (PATH-augmented; {@code pythonPathDir} prepended to PYTHONPATH when non-null),
     *  discards its output, and returns whether it exits 0 within 5s. */
    private static boolean probe(List<String> argv, Path pythonPathDir) {
        return probe(argv, pythonPathDir, Map.of());
    }

    /** As {@link #probe(List, Path)}, with {@code env} (a command's own {@code env NAME=value} prefix) applied. */
    private static boolean probe(List<String> argv, Path pythonPathDir, Map<String, String> env) {
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
    public Path debugFile() {
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
            Path file, String language, MainClassPicker picker, String javaExecOverride, Path projectRoot) {
        if ("java".equals(language)) {
            startLaunch(file, picker, javaExecOverride, projectRoot);
        } else if (DapServerRegistry.isDebuggable(language)) {
            startProgram(file, language);
        } else {
            listener.onError(tr("debug.error.notSupported"));
        }
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
    private volatile List<String> vmArgList;

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
    private String javaExecFor(Path file, String javaExecOverride, String resolved) {
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
    private volatile Path pythonProjectRoot;

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
    private volatile com.editora.run.PythonRunPlan.Request pythonRequest;

    /** The machine the next Python program runs on, as Run plans it, or null (decided from the file). */
    private volatile com.editora.run.PythonRunPlan.Host pythonHost;

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
    public void startLaunch(Path file, MainClassPicker picker, String javaExecOverride, Path projectRoot) {
        startLaunch(file, picker, javaExecOverride, projectRoot, null);
    }

    /**
     * As above, with the folder a loose file runs in ({@code looseCwd}, null: its own folder) — the project
     * root of a plain-folder project window, where a program reads its files as it would from a terminal there.
     */
    public void startLaunch(
            Path file, MainClassPicker picker, String javaExecOverride, Path projectRoot, Path looseCwd) {
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
            Path file, MainClassPicker picker, String javaExecOverride, Path projectRoot, Path looseCwd, long epoch) {
        lsp.executeCommand(file, "vscode.java.resolveMainClass", List.of(), (res, err) -> {
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
                                "debug.console.noMainInFile", file.getFileName(), only.className()));
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
     * Launches {@code opt}. Outside a build project jdtls's "invisible project" never compiles anything into its
     * output folder (autobuild is off), so a class missing there is compiled here, from its own source file.
     */
    private void launchOption(
            Path file, MainClassOption opt, String cwd, String javaExecOverride, Path projectRoot, long epoch) {
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
        resolveAndLaunch(file, opt, cwd, javaExecOverride, epoch, compile);
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
    static String ownMainClass(Path file) {
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

    /**
     * In a folder with no build file jdtls keeps a Java file in an "invisible project" whose source folder it
     * guesses — and it guesses the file's own folder, so {@code src/demo/Main.java} declaring {@code package
     * demo} is class {@code Main} to it: the launch named a class that does not exist, its package line was
     * flagged as wrong, and no stop could be shown in the file. When the declared package says the source
     * folder is another one, this tells jdtls so (adding the right folder, then removing the wrong ones —
     * Editora's jdtls workspace changes, never the user's files) and then runs {@code then}. Anything that
     * cannot be corrected (no package, a misplaced file, a folder outside the server's workspace) runs
     * {@code then} as it is: the compile below names the class from the file either way.
     */
    private void correctSourceRoot(Path file, long epoch, Runnable then) {
        String pkg = JavaLaunchSupport.declaredPackage(file);
        Path root = pkg.isEmpty() || !com.editora.process.ExecTargets.runsHere(file)
                ? null
                : JavaLaunchSupport.sourceRoot(file, pkg);
        if (root == null) {
            then.run();
            return;
        }
        lsp.executeCommand(file, "java.project.listSourcePaths", List.of(), (res, err) -> {
            if (!isCurrent(epoch)) {
                return;
            }
            List<Path> current = err == null ? JavaLaunchSupport.sourcePaths(res) : List.of();
            List<Path> wrong = JavaLaunchSupport.wrongSourceRoots(current, file, root);
            if (wrong.isEmpty()) {
                then.run();
                return;
            }
            lsp.executeCommand(
                    file, "java.project.addToSourcePath", List.of(root.toUri().toString()), (added, e2) -> {
                        if (!isCurrent(epoch)) {
                            return;
                        }
                        if (e2 != null || !JavaLaunchSupport.statusOk(added)) {
                            then.run(); // the right folder is outside the server's workspace: leave it be
                            return;
                        }
                        removeSourceRoots(file, new ArrayList<>(wrong), epoch, then);
                    });
        });
    }

    private void removeSourceRoots(Path file, List<Path> wrong, long epoch, Runnable then) {
        if (wrong.isEmpty()) {
            then.run();
            return;
        }
        Path next = wrong.remove(0);
        lsp.executeCommand(
                file, "java.project.removeFromSourcePath", List.of(next.toUri().toString()), (r, e) -> {
                    if (isCurrent(epoch)) {
                        removeSourceRoots(file, wrong, epoch, then);
                    }
                });
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
    public void startAttach(Path file, String host, int port) {
        if (!ready(file)) {
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
        lsp.executeCommand(file, "vscode.java.resolveMainMethod", List.of(uri), (res, err) -> {
            String name = err == null ? JavaLaunchSupport.projectName(res) : null;
            int[] at = name == null ? JavaLaunchSupport.typeNamePosition(file) : null;
            if (at == null) {
                cb.accept(name);
                return;
            }
            lsp.executeCommand(
                    file,
                    "vscode.java.resolveElementAtSelection",
                    List.of(uri, at[0], at[1]),
                    (el, e2) -> cb.accept(e2 == null ? JavaLaunchSupport.projectName(el) : null));
        });
    }

    private boolean ready(Path file) {
        if (managerClosed) {
            return false;
        }
        boolean hostPlugin = file != null
                && !com.editora.process.ExecTargets.runsHere(file)
                && enabled
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

    private void resolveAndLaunch(Path file, MainClassOption opt, String cwd, String javaExecOverride, long epoch) {
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
            Path file, MainClassOption opt, String cwd, String javaExecOverride, long epoch, Runnable ifNoClassFile) {
        String proj = opt.projectName() == null ? "" : opt.projectName();
        resolveLaunch(file, opt, r -> {
            if (!isCurrent(epoch)) {
                return;
            }
            if (ifNoClassFile != null
                    && !(JavaLaunchSupport.classFilePresent(r.classPaths(), opt.mainClass())
                            || JavaLaunchSupport.classFilePresent(r.modulePaths(), opt.mainClass()))) {
                ifNoClassFile.run();
                return;
            }
            if (!r.ok()) {
                fail(epoch, r.error());
                return;
            }
            if (ifNoClassFile == null) {
                warnAboutClasses(file, opt, r, epoch);
            }
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
        if (opt.mainClass() == null || opt.mainClass().isBlank()) {
            cb.accept(ResolvedLaunch.failed(tr("debug.error.configNeedsMainClass")));
            return;
        }
        String proj = opt.projectName() == null ? "" : opt.projectName();
        lsp.executeCommand(routingFile, "vscode.java.resolveClasspath", List.of(opt.mainClass(), proj), (res, err) -> {
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
                        () -> "resolveClasspath returned no entries for " + opt.mainClass() + " (project='"
                                + proj + "'); raw result type="
                                + (res == null ? "null" : res.getClass().getName())
                                + " value=" + JdtlsReplies.abbreviate(String.valueOf(res)));
                cb.accept(ResolvedLaunch.failed(tr("debug.error.classpathEmpty", opt.mainClass())));
                return;
            }
            lsp.executeCommand(
                    routingFile,
                    "vscode.java.resolveJavaExecutable",
                    List.of(opt.mainClass(), proj),
                    (jx, e2) -> lsp.executeCommand(
                            routingFile,
                            "vscode.java.checkProjectSettings",
                            List.of(JavaLaunchSupport.previewSettingsQuery(opt.mainClass(), proj)),
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
        lsp.executeCommand(routingFile, "vscode.java.resolveMainClass", List.of(), (res, err) -> {
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
    private void compileAndLaunch(Path file, String fqn, String javaExecOverride, long epoch, Integer sourceVersion) {
        compileAndLaunch(file, fqn, javaExecOverride, epoch, sourceVersion, null);
    }

    /**
     * As above, running the program in {@code workingDir} (null: the file's own folder). The file is compiled
     * with its package's source folder as javac's source path, so the other classes of that package compile
     * with it, and the class is launched by the package the file declares.
     */
    private void compileAndLaunch(
            Path file, String fqn, String javaExecOverride, long epoch, Integer sourceVersion, Path workingDir) {
        compileAndLaunch(file, fqn, javaExecOverride, epoch, sourceVersion, workingDir, file);
    }

    /**
     * As above, for a {@code file} that may not be open in the editor: {@code routing} — an open Java file of
     * the same jdtls project — carries the jdtls commands that start the adapter.
     */
    private void compileAndLaunch(
            Path file,
            String fqn,
            String javaExecOverride,
            long epoch,
            Integer sourceVersion,
            Path workingDir,
            Path routing) {
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
                            com.editora.i18n.Messages.tr("status.debug.compileFailed", file.getFileName()));
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
                Platform.runLater(() -> projectNameOf(
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

    private SourceAlias activeSourceAlias() {
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
                    () -> JavaCompilation.removeCompilation(directory, removal));
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
    static String mainClassFromFile(Path file) {
        return JavaCompilation.mainClassFromFile(file);
    }

    private static String firstOrNull(List<String> l) {
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    /** What java-debug is told before each Java session (step filters, statics, exception types). */
    private JavaDebugSettings javaDebugSettings = JavaDebugSettings.defaults();

    /** Sets the java-debug settings the next Java session starts with (FX thread). */
    public void setJavaDebugSettings(JavaDebugSettings settings) {
        this.javaDebugSettings = settings == null ? JavaDebugSettings.defaults() : settings;
    }

    private void startDebugSessionAndConnect(
            Path file, java.util.Map<String, Object> args, boolean attach, long epoch) {
        if (!isCurrent(epoch)) {
            return;
        }
        // java-debug keeps one set of settings per jdtls: give it this session's before the session starts. A
        // failure (an older java-debug) is no reason not to debug, on its defaults as before.
        lsp.executeCommand(file, JavaDebugSettings.COMMAND, List.of(javaDebugSettings.toJson()), (r, e) -> {
            if (e != null) {
                LOG.log(Level.FINE, "java-debug settings were not applied", e);
            }
            startDebugSession(file, args, attach, epoch);
        });
    }

    private void startDebugSession(Path file, java.util.Map<String, Object> args, boolean attach, long epoch) {
        if (!isCurrent(epoch)) {
            return;
        }
        javaAdapterEpoch = epoch;
        lsp.executeCommand(file, "vscode.java.startDebugSession", List.of(), (res, err) -> {
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
            if (!attach && programConsole) {
                c.setRunsDebuggee(true);
                LaunchConfig.inClientConsole(args);
            } else if (!attach) {
                wrapForExitCode(file, args, epoch);
            }
            c.setBreakpoints(adapterBreakpoints(breakpointsSupplier.get())); // snapshot on the FX thread
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
                        fail(epoch, tr(attach ? "debug.error.attachFailed" : "debug.error.launchFailed", msg(le)));
                        return;
                    }
                    // Running once the adapter has the program, not when the request left: until then
                    // java-debug is still starting the JVM. A stop that came first has already said SUSPENDED.
                    Platform.runLater(() -> {
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
    private CompletableFuture<org.eclipse.lsp4j.debug.Capabilities> connectJava(DapClient c, Path file, int port) {
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
    static DapPaths pathsFor(Path file) {
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
    private void startProgram(Path file, String language, PythonLaunch.Target module) {
        if (!readyProgram(file, language)) {
            return;
        }
        DapServerRegistry.DapServerSpec spec = DapServerRegistry.specFor(language);
        if (spec == null) {
            listener.onError(tr("debug.error.noAdapter"));
            return;
        }
        restartAction = () -> startProgram(file, language, module);
        debugFile = file;
        long epoch = beginSession();
        setState(State.STARTING);
        // Snapshot UI state on the FX thread before going off it.
        List<DapModels.FileBreakpoints> bps = breakpointsSupplier.get();
        List<String> filters = filtersFor(language);
        startupTask = io.submit(() -> {
            try {
                boolean remote = !com.editora.process.ExecTargets.runsHere(file);
                if (spec.kind() == DapServerRegistry.Kind.STDIO) {
                    if (remote) {
                        startRemoteStdio(file, spec, bps, filters, epoch, module);
                    } else {
                        startStdio(file, spec, bps, filters, epoch, module);
                    }
                } else if (spec.kind() == DapServerRegistry.Kind.SOCKET) {
                    if (remote) {
                        startRemoteSocket(file, spec, bps, filters, epoch);
                    } else {
                        startSocket(file, spec, bps, filters, epoch);
                    }
                } else {
                    fail(epoch, tr("debug.error.transport", language));
                }
            } catch (Exception e) {
                fail(epoch, tr("debug.error.startFailed", msg(e)));
            }
        });
    }

    /**
     * Debugs {@code python -m module args} in {@code cwd} — a pytest test, say — under {@code anchor}'s
     * interpreter, with {@code anchor} naming the session. Restart repeats it.
     */
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
        if (!enabled) {
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
            listener.onError(
                    "python".equals(language) ? tr("debug.error.pythonUnavailable") : tr("debug.error.jsUnavailable"));
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
            List<String> filters,
            long epoch,
            PythonLaunch.Target module)
            throws Exception {
        HostPython program = planProgram(file, com.editora.process.ExecTargets.forPath(file), true);
        HostPython adapter = probe(program.argv(List.of("-c", "import debugpy")), debugpyDir, program.env())
                ? program
                : HostPython.parse(DapServerRegistry.interpreterArgv("python", pythonCommand));
        List<String> cmd = ProcessRunner.resolveExecutable(adapter.argv(spec.adapterArgs())); // -m debugpy.adapter
        ProcessBuilder pb = new ProcessBuilder(cmd);
        ProcessRunner.applyUserEnv(pb);
        pb.environment().putAll(adapter.env());
        if (debugpyDir != null) {
            prependPythonPath(pb, debugpyDir);
        }
        if (module != null && "pytest".equals(module.module())) {
            module = pytestOrFallback(module, program, epoch, argv -> probe(argv, null, program.env()));
            if (module == null) {
                return; // reported
            }
        }
        Process proc = BusyExecutable.start(pb);
        AdapterStderr stderr = AdapterStderr.drain(proc); // DAP is on stdin/stdout; an undrained PIPE deadlocks
        boolean console = programConsole;
        PythonLaunch.Target target =
                module != null ? module : PythonLaunch.Target.script(file.toString(), programArgs, cwdOf(file));
        // What runs, first in the console (muted): which Python, in which venv, with which arguments.
        onOutput(epoch, PythonLaunch.commandLine(program, target) + "\n", COMMAND_CATEGORY);
        // The plan's PYTHONPATH (project root, src/, the user's) when it set one; else the user's own, or "" —
        // never the adapter's, which carries Editora's debugpy folder.
        String inherited = System.getenv("PYTHONPATH");
        Map<String, Object> launch = PythonLaunch.request(
                target, program, List.of(), inherited == null ? "" : inherited, File.pathSeparator, console);
        attachStdio(proc, spec, bps, filters, epoch, DapPaths.LOCAL, launch, console, stderr);
    }

    /**
     * A pytest run when {@code program} can import pytest; else its unittest fallback; else null, with the
     * session ended saying which interpreter lacks pytest.
     */
    private PythonLaunch.Target pytestOrFallback(
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
                asked != null ? asked.setting() : local ? pythonCommand : "");
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
    private Path pythonRootFor(Path file) {
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
            List<String> filters,
            long epoch,
            DapPaths paths,
            Map<String, Object> launch,
            boolean runsDebuggee,
            AdapterStderr stderr) {
        if (!isCurrent(epoch)) {
            com.editora.process.ProcessRegistry.killTree(proc);
            return;
        }
        DapClient c = new DapClient(sessionHost(epoch));
        c.setPaths(paths);
        c.setBreakpoints(bps);
        c.setExceptionFilters(filters);
        c.setRunsDebuggee(runsDebuggee);
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
            Platform.runLater(() -> {
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

    /** vscode-js-debug over a socket: spawn {@code node dapDebugServer.js <port>}, connect, launch. */
    private void startSocket(
            Path file,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            List<String> filters,
            long epoch)
            throws Exception {
        if (jsServer == null) {
            fail(epoch, tr("debug.error.jsNotFound"));
            return;
        }
        int port = freePort();
        List<String> cmd = ProcessRunner.resolveExecutable(
                List.of(spec.defaultInterpreter(), jsServer.toString(), String.valueOf(port)));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        ProcessRunner.applyUserEnv(pb);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD); // DAP is on the socket; stdout/stderr are logs
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process proc = BusyExecutable.start(pb);
        String nodeExe = cmd.isEmpty() ? null : cmd.get(0);
        attachSocket(
                proc,
                null,
                port,
                spec,
                bps,
                filters,
                epoch,
                DapPaths.LOCAL,
                file.toString(),
                cwdOf(file),
                nodeExe,
                null);
    }

    /**
     * Attaches the JavaScript debugger to a Node.js on this machine that waits for one on its inspector {@code port}
     * — an HTTP Client script started with {@code --inspect-brk}. js-debug is started as for a JavaScript file and
     * asked to {@code attach} ({@link LaunchConfig#nodeAttach}); it sets the breakpoints and resumes the script, and
     * steps over {@code skipFiles} (the shim around it). The session is called {@code name} and ends with the script.
     * There is nothing to restart: Restart only stops it.
     */
    public void startJsAttach(int port, String name, Path cwd, List<String> skipFiles) {
        if (managerClosed) {
            return;
        }
        if (!enabled) {
            listener.onError(tr("debug.error.disabled"));
            return;
        }
        if (!isLanguageAvailable("javascript")) {
            listener.onError(tr("debug.error.jsUnavailable"));
            return;
        }
        if (client != null) {
            stop(); // one session at a time
        }
        DapServerRegistry.DapServerSpec spec = DapServerRegistry.specFor("javascript");
        restartAction = null;
        debugFile = cwd;
        long epoch = beginSession();
        setState(State.STARTING);
        List<DapModels.FileBreakpoints> bps = breakpointsSupplier.get();
        List<String> filters = filtersFor("javascript");
        Map<String, Object> attach = LaunchConfig.nodeAttach(spec.launchType(), name, port, skipFiles);
        startupTask = io.submit(() -> {
            try {
                if (jsServer == null) {
                    fail(epoch, tr("debug.error.jsNotFound"));
                    return;
                }
                int serverPort = freePort();
                List<String> cmd = ProcessRunner.resolveExecutable(
                        List.of(spec.defaultInterpreter(), jsServer.toString(), String.valueOf(serverPort)));
                ProcessBuilder pb = new ProcessBuilder(cmd);
                ProcessRunner.applyUserEnv(pb);
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process proc = BusyExecutable.start(pb);
                attachSocket(
                        proc,
                        null,
                        serverPort,
                        spec,
                        bps,
                        filters,
                        epoch,
                        DapPaths.LOCAL,
                        null,
                        cwd == null ? null : cwd.toString(),
                        null,
                        attach);
            } catch (Exception e) {
                fail(epoch, tr("debug.error.startFailed", msg(e)));
            }
        });
    }

    /**
     * Wires a started socket adapter ({@code proc}, listening on this machine's {@code port} — through
     * {@code forward} for one on a remote host) to a new session and launches {@code program} — or, given
     * {@code attachArgs}, sends that {@code attach} instead.
     */
    private void attachSocket(
            Process proc,
            com.editora.process.PortForward forward,
            int port,
            DapServerRegistry.DapServerSpec spec,
            List<DapModels.FileBreakpoints> bps,
            List<String> filters,
            long epoch,
            DapPaths paths,
            String program,
            String cwd,
            String nodeExe,
            Map<String, Object> attachArgs) {
        if (!isCurrent(epoch)) {
            com.editora.process.ProcessRegistry.killTree(proc);
            if (forward != null) {
                forward.close();
            }
            return;
        }
        DapClient c = new DapClient(sessionHost(epoch));
        c.setPaths(paths);
        c.setBreakpoints(bps);
        c.setExceptionFilters(filters);
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
                fail(epoch, tr("debug.error.jsConnect", msg(e)));
                return;
            }
            if (!isCurrent(epoch)) {
                c.dispose();
                return;
            }
            (attachArgs != null
                            ? c.attach(attachArgs)
                            : c.launch(
                                    LaunchConfig.program(spec.launchType(), program, cwd, nodeExe, programArgs, false)))
                    .whenComplete((v, le) -> {
                        if (le != null) {
                            fail(
                                    epoch,
                                    tr(
                                            attachArgs != null
                                                    ? "debug.error.attachFailed"
                                                    : "debug.error.launchFailed",
                                            msg(le)));
                        }
                    });
            Platform.runLater(() -> {
                if (isCurrent(epoch, c)) {
                    setState(State.RUNNING);
                    if (attachArgs == null) {
                        listener.onDebuggeeRuntime(
                                List.of(nodeExe == null || nodeExe.isBlank() ? spec.defaultInterpreter() : nodeExe),
                                runtimeWhere(paths, cwd),
                                Map.of());
                    }
                }
            });
        });
    }

    // --- On a remote host ------------------------------------------------------------------------

    /** Languages a remote file is debugged in on its own host (Java through the jdtls running there). */
    public static boolean debuggableOnHost(String language) {
        return "python".equals(language) || "javascript".equals(language) || "java".equals(language);
    }

    /** Why {@code language} cannot be debugged on remote {@code file}'s host, or null when it can be tried. */
    private String remoteProblem(Path file, String language) {
        if (!debuggableOnHost(language)) {
            return tr("debug.error.remoteLanguage");
        }
        if ("python".equals(language) ? !pythonEnabled : !jsEnabled) {
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
        List<String> configured = DapServerRegistry.interpreterArgv("python", pythonCommand);
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
            List<String> filters,
            long epoch,
            PythonLaunch.Target module)
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
     * search), else where Editora's installer, mason and VS Code's server put it. Prints the path; exits 1
     * when there is none, 2 when {@code node} is missing.
     */
    static final String FIND_JS_DEBUG = """
            command -v "$2" >/dev/null 2>&1 || exit 2
            if [ -n "$1" ]; then
              case "$1" in */dapDebugServer.js) [ -f "$1" ] && { printf '%s\n' "$1"; exit 0; } ;; esac
              d=$1; [ -f "$d" ] && d=${d%/*}
              set -- "$d"
            else
              set -- "$HOME/.editora/plugins/dap/javascript" "$HOME/.local/share/nvim/mason/packages/js-debug-adapter" \
                "$HOME"/.vscode-server/extensions/ms-vscode.js-debug* "$HOME"/.vscode/extensions/ms-vscode.js-debug*
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
            List<String> filters,
            long epoch)
            throws Exception {
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
                target.toTargetPath(file),
                target.toTargetPath(dir),
                node,
                null);
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

    private static String cwdOf(Path file) {
        return file.getParent() == null ? null : file.getParent().toString();
    }

    /** Where a debuggee's runtime is asked its version: {@code file}'s folder, so its host; null is here. */
    private static Path runtimeWhere(Path file) {
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
    private static Map<String, String> stringMap(Object env) {
        if (!(env instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return out;
    }

    /** Where a debuggee's runtime is asked its version: its launch folder, named as the adapter's machine names
     *  it (so its host); null is here. */
    private static Path runtimeWhere(DapPaths paths, String cwd) {
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
        c.resume(threadId)
                .whenComplete((all, e) -> Platform.runLater(() -> {
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

    /** Pauses a running session: targets the current thread if the adapter reports it, else the first.
     *  The adapter answers with a {@code stopped(reason=pause)} event, which flows through onStopped. */
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
            c.pause(id);
        });
    }

    /** Lists the session's threads (callback on the FX thread). */
    public void threads(Consumer<List<DapModels.ThreadInfo>> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        c.threads()
                .whenComplete((list, e) -> Platform.runLater(() -> {
                    if (client == c) {
                        cb.accept(list == null ? List.of() : list);
                    }
                }));
    }

    /** Switches the inspected thread and loads its call stack (callback on the FX thread). */
    public void selectThread(int threadId, Consumer<List<DapModels.StackFrameInfo>> cb) {
        currentThreadId = threadId;
        stackTrace(threadId, cb);
    }

    /** While a run-to-cursor temp breakpoint is planted: the file holding it (re-sent clean on stop). */
    private Path tempBreakpointFile;

    /** Resumes and stops at {@code line} (0-based) in {@code file} via a temporary breakpoint that is
     *  removed on the next stop (or on termination). Call on the FX thread while SUSPENDED. */
    public void runToCursor(Path file, int line) {
        DapClient c = client;
        if (c == null || state != State.SUSPENDED || file == null) {
            return;
        }
        tempBreakpointFile = file;
        c.sendSetBreakpoints(adapterBreakpoint(withTempLine(breakpointsSupplier.get(), file, line)));
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
                .whenComplete((ids, e) -> Platform.runLater(() -> {
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
                            Platform.runLater(() -> {
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
        DapClient c = client;
        if (f == null || c == null) {
            return;
        }
        List<DapModels.LineBreakpoint> real = new ArrayList<>();
        for (DapModels.FileBreakpoints fb : breakpointsSupplier.get()) {
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
                .whenComplete((v, e) -> Platform.runLater(() -> {
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
    private ProcessHandle adapterProgram;

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
        Platform.runLater(() -> {
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
                .whenComplete((frames, e) -> Platform.runLater(() -> {
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
                .whenComplete((scopes, e) -> Platform.runLater(() -> {
                    if (client == c) {
                        cb.accept(scopes == null ? List.of() : scopes);
                    }
                }));
    }

    public void variables(int ref, Consumer<List<DapModels.VariableInfo>> cb) {
        variables(ref, null, 0, 0, cb);
    }

    /** One page of a container's children — see {@link DapClient#variables(int, String, int, int)}. */
    public void variables(int ref, String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        c.variables(ref, filter, start, count)
                .thenCompose(vars -> repairStrings(c, vars))
                .whenComplete((vars, e) -> Platform.runLater(() -> {
                    if (client == c) {
                        cb.accept(vars == null ? List.of() : vars);
                    }
                }));
    }

    // --- strings java-debug garbles (follow-up 4: emoji arrive as two wrong characters) -------------------

    /** Whether the session is java-debug's, whose string values may need {@link JdwpStringRepair}. */
    private boolean javaDebugSession() {
        return "java".equals(sessionLanguage);
    }

    private CompletableFuture<List<DapModels.VariableInfo>> repairStrings(
            DapClient c, List<DapModels.VariableInfo> vars) {
        return javaDebugSession() ? JdwpStringRepair.repairAll(c, vars) : CompletableFuture.completedFuture(vars);
    }

    private CompletableFuture<DapModels.EvalResult> repairString(DapClient c, DapModels.EvalResult result) {
        return javaDebugSession() ? JdwpStringRepair.repair(c, result) : CompletableFuture.completedFuture(result);
    }

    public void evaluate(String expression, int frameId, String context, Consumer<String> cb) {
        DapClient c = client;
        if (c == null) {
            cb.accept("");
            return;
        }
        boolean console = "repl".equals(context);
        if (console) {
            consoleEvaluations++;
        }
        c.evaluateFull(expression, frameId, context) // the full answer: a string result can be repaired from it
                .thenCompose(full -> repairString(c, full))
                .whenComplete((full, e) -> Platform.runLater(() -> {
                    if (console && client == c) {
                        consoleEvaluations = Math.max(0, consoleEvaluations - 1);
                    }
                    if (client == c) {
                        String r = full == null ? null : full.result();
                        cb.accept(
                                e != null
                                        ? "error: " + (isTimeout(e) ? stillEvaluating() : msg(e))
                                        : (r == null ? "" : r));
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
        c.evaluateFull(expression, frameId, context)
                .thenCompose(r -> repairString(c, r))
                .whenComplete((r, e) -> Platform.runLater(() -> {
                    if (client == c) {
                        cb.accept(
                                e != null
                                        ? DapModels.EvalResult.failure(msg(e))
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
                .whenComplete((r, e) -> Platform.runLater(() -> {
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
        DapClient c = client;
        if (c == null) {
            return; // no session: nothing was set
        }
        c.setVariable(ref, name, value)
                .whenComplete((r, e) -> Platform.runLater(() -> {
                    if (client != c) {
                        return;
                    }
                    if (e != null) {
                        listener.onError(msg(e));
                    } else {
                        cb.accept(r == null ? value : r);
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
        if (!isCurrent(epoch, c)) {
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
                .whenComplete((frames, e) -> Platform.runLater(() -> {
                    if (!isCurrent(epoch, c)) {
                        return;
                    }
                    if (e != null) {
                        LOG.log(Level.WARNING, "stackTrace failed for thread " + threadId, e);
                    }
                    if (event) {
                        if (allThreadsStopped) {
                            stoppedThreads.clear(); // one stop stands for all of them, and so will one continue
                        }
                        stoppedThreads.put(threadId, reason == null ? "" : reason);
                        clearTempBreakpoint(); // a run-to-cursor temp breakpoint is one-shot
                    } else if (state != State.RUNNING || !stoppedThreads.containsKey(threadId)) {
                        return;
                    }
                    stopCount++;
                    stepInFlight = false;
                    currentThreadId = threadId;
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
                .whenComplete((info, e) -> Platform.runLater(() -> {
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
        Platform.runLater(() -> {
            if (!isCurrent(epoch) || file == null) {
                return;
            }
            SourceAlias alias = activeSourceAlias(); // a compiled copy stands in for the file the user sees
            listener.onBreakpointStatus(
                    alias != null && alias.compiled().equals(file) ? alias.original() : file, statuses, whole);
        });
    }

    private void onNotice(long epoch, String message, boolean error) {
        Platform.runLater(() -> {
            if (isCurrent(epoch)) {
                outputPump.flush(); // keep it after the program output that preceded it
                listener.onNotice(message, error);
            }
        });
    }

    private void onContinued(long epoch, int threadId, boolean allThreads) {
        Platform.runLater(() -> {
            if (!isCurrent(epoch)) {
                return;
            }
            if (allThreads) {
                stoppedThreads.clear();
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
        String shown = JavaLaunchSupport.withoutJvmVersionWarning(text);
        if (shown != text) {
            LOG.fine(() -> "java-debug: " + text.strip());
        }
        if (!shown.isEmpty()) {
            outputPump.offer(epoch, shown, category);
        }
    }

    private void onTerminated(long epoch) {
        Platform.runLater(() -> {
            Debuggee program = debuggee;
            if (isCurrent(epoch) && program != null && program.isRunning()) {
                // The adapter is done with a program Editora started. Its last output is still in our pipe
                // and the process may not be gone yet: the session ends when its exit is delivered
                // (startDebuggee's sink), and a program that outlives its debugger is ended, not left running.
                CompletableFuture.delayedExecutor(DEBUGGEE_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)
                        .execute(() -> Platform.runLater(() -> {
                            if (debuggee == program) {
                                program.terminate();
                            }
                        }));
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
        Debuggee program = new Debuggee();
        try {
            // The adapter names the folder as its machine does: on a remote file's host, a path there.
            DapPaths paths = pathsFor(debugFile);
            Path dir = cwd == null || cwd.isBlank() ? null : paths.fromAdapter(cwd);
            if (dir == null && paths != DapPaths.LOCAL) {
                dir = debugFile.getParent(); // never this machine's folder of the same name
            }
            long pid = program.start(dir, argv, env, new Debuggee.Sink() {
                @Override
                public void output(String text, boolean stderr) {
                    if (debuggee == program && isCurrent(epoch)) {
                        listener.onOutput(text, stderr ? "stderr" : "stdout");
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
            listener.onProgramInput(true);
        } catch (java.io.IOException | RuntimeException e) {
            program.kill();
            started.completeExceptionally(e); // the adapter fails the launch with this, which reports it
        }
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
        Platform.runLater(() -> {
            if (!isCurrent(epoch) || programStartedEpoch == epoch || exitReportedEpoch == epoch) {
                return;
            }
            exitReportedEpoch = epoch;
            outputPump.flush();
            if (javaAdapterEpoch == epoch) {
                // java-debug reports 0 whatever the program exited with (System.exit(3) included, seen live).
                // The wrapper the program was started through recorded its real code; without one the console
                // says only that the program ended, not a code that is not its own (review A10).
                reportJavaExit(epoch);
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
    private volatile ExitWrapper exitWrapper;

    /**
     * Starts the program of a launch java-debug runs itself (the program console off) through a wrapper that
     * records its exit code: {@code args}' {@code javaExec} becomes the wrapper, which runs the real one. Only for
     * a program on this machine and not on Windows (see {@link JavaExitCodeWrapper}); and only when the launch
     * names its {@code java} — without one java-debug picks its own, which the wrapper cannot know.
     */
    private void wrapForExitCode(Path file, Map<String, Object> args, long epoch) {
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
        CompletableFuture.supplyAsync(() -> own.wrapper().awaitExitCode(java.time.Duration.ofSeconds(3)))
                .whenComplete((code, e) -> Platform.runLater(() -> {
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
        Platform.runLater(() -> {
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
        if (s == State.INACTIVE || s == State.STARTING) {
            consoleEvaluations = 0; // an answer that never comes cannot hold the next session's controls
            stoppedThreads.clear();
        }
        this.state = s;
        listener.onState(s);
    }

    private long beginSession() {
        Future<?> startup = startupTask;
        startupTask = null;
        if (startup != null) {
            startup.cancel(true);
        }
        killDebuggee();
        // A launch still waiting for its adapter has no client for ready() to stop, but it may already hold
        // its compiled classes: the next launch would overwrite the only reference to them (review D6).
        releaseCompilationDirectory();
        releaseExitWrapper();
        synchronized (sessionLock) {
            return ++sessionEpoch;
        }
    }

    private boolean isCurrent(long epoch) {
        return !managerClosed && sessionEpoch == epoch;
    }

    private boolean isCurrent(long epoch, DapClient expected) {
        return isCurrent(epoch) && client == expected;
    }

    /** Atomically publishes a client only while the start operation that created it still owns the session. */
    private boolean publishClient(long epoch, DapClient candidate) {
        synchronized (sessionLock) {
            if (!isCurrent(epoch)) {
                return false;
            }
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
                Platform.runLater(() -> DapManager.this.onProcessId(epoch, pid));
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
            public void onHotCodeReplace(String changeType, String message) {
                DapManager.this.onHotCodeReplace(epoch, changeType, message);
            }

            @Override
            public CompletableFuture<Long> onRunInTerminal(String cwd, List<String> argv, Map<String, String> env) {
                CompletableFuture<Long> started = new CompletableFuture<>();
                Platform.runLater(() -> startDebuggee(epoch, cwd, argv, env, started));
                return started;
            }

            @Override
            public void onTransportClosed(Throwable error) {
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
        Platform.runLater(() -> {
            if (!isCurrent(epoch)) {
                return;
            }
            if (console != null && !console.isBlank()) {
                listener.onOutput(console.endsWith("\n") ? console : console + "\n", "stderr");
            }
            listener.onError(status);
            stop();
        });
    }

    private void fail(long epoch, String message) {
        Platform.runLater(() -> {
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
        return t == null ? "" : DapErrors.userText(t.getMessage() == null ? t.toString() : t.getMessage());
    }

    // --- jdtls replies: read by JdtlsReplies (these three names are what tests reach by reflection) ----------

    private static List<MainClassOption> parseMainClasses(Object res) {
        return JdtlsReplies.parseMainClasses(res);
    }

    private static void parseClasspath(Object res, List<String> modulepaths, List<String> classpaths) {
        JdtlsReplies.parseClasspath(res, modulepaths, classpaths);
    }

    private static String asString(Object res) {
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
        /** java-debug refused (an added or removed method or field, a JVM without redefinition…): restart. */
        FAILED,
        /** jdtls could not build: no Java server for the file, or the build failed or was cancelled. */
        BUILD_FAILED,
        /** No running Java session to apply to. */
        NO_SESSION
    }

    /** {@code message} is java-debug's reason for {@link CodeReplaceResult#FAILED}, else empty. */
    public record CodeReplaceOutcome(CodeReplaceResult result, List<String> classes, String message) {

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
        Platform.runLater(() -> {
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
        lsp.buildJavaWorkspace(debugFile, false, built -> {});
    }

    /** The file the session was started for (it routes a build to that session's jdtls); null when none. */
    public Path sessionFile() {
        return debugFile;
    }

    /**
     * Apply Code Changes: has jdtls build {@code file}'s workspace (incrementally — Editora runs it without
     * autobuild, so nothing else compiles a saved file), then asks java-debug to load the rebuilt classes into
     * the running program. A build with compile errors replaces nothing. {@code cb} runs on the FX thread,
     * and not at all when the session ended meanwhile.
     */
    public void applyCodeChanges(Path file, Consumer<CodeReplaceOutcome> cb) {
        DapClient c = client;
        if (!canReplaceCode() || file == null) {
            cb.accept(new CodeReplaceOutcome(CodeReplaceResult.NO_SESSION, List.of(), ""));
            return;
        }
        long epoch = sessionEpoch;
        lsp.buildJavaWorkspace(file, false, built -> {
            if (!isCurrent(epoch, c)) {
                return;
            }
            switch (built) {
                case WITH_ERRORS -> cb.accept(new CodeReplaceOutcome(CodeReplaceResult.COMPILE_ERRORS, List.of(), ""));
                case SUCCEEDED ->
                    c.redefineClasses()
                            .whenComplete((r, e) -> Platform.runLater(() -> {
                                if (isCurrent(epoch, c)) {
                                    cb.accept(codeReplaceOutcome(r, e == null ? null : msg(e)));
                                }
                            }));
                default -> cb.accept(new CodeReplaceOutcome(CodeReplaceResult.BUILD_FAILED, List.of(), ""));
            }
        });
    }

    /** Pure: the outcome of a {@code redefineClasses} answer, or of the request failing with {@code error}. */
    static CodeReplaceOutcome codeReplaceOutcome(DapModels.CodeReplace reply, String error) {
        if (error != null) {
            return new CodeReplaceOutcome(CodeReplaceResult.FAILED, List.of(), error);
        }
        if (reply == null || (!reply.failed() && reply.changedClasses().isEmpty())) {
            return new CodeReplaceOutcome(CodeReplaceResult.NOTHING_CHANGED, List.of(), "");
        }
        return reply.failed()
                ? new CodeReplaceOutcome(CodeReplaceResult.FAILED, reply.changedClasses(), reply.error())
                : new CodeReplaceOutcome(CodeReplaceResult.APPLIED, reply.changedClasses(), "");
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
                .whenComplete((targets, e) -> Platform.runLater(() -> {
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
            public void onError(String message) {}
        };
    }
}
