package com.editora.dap;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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
import com.editora.run.JdkToolchain;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import static com.editora.i18n.Messages.tr;

/**
 * UI-facing facade for Java debugging over DAP (mirrors {@code LspManager} + {@code RunService}). Owns the
 * single active {@link DapClient} (one debug session at a time, like Run), locates the java-debug plugin
 * jar (via {@link DebugAdapterLocator}), and drives the full launch sequence against jdtls — resolve main
 * class / classpath / java executable, start the debug session (which returns a socket port via
 * {@code workspace/executeCommand}), connect the DAP socket, then {@code launch}/{@code attach}. All public
 * methods are safe to call from the FX thread; socket I/O and event callbacks run off it and results are
 * marshaled back via {@link Platform#runLater}. Implements {@link DapClient.Host} to receive DAP events.
 */
public final class DapManager implements DapClient.Host {

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
            listener.onError("Debugging is not supported for this file type.");
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
        if (!ready(file)) {
            return;
        }
        restartAction = () -> startLaunch(file, picker, javaExecOverride, projectRoot);
        debugFile = file;
        long epoch = beginSession();
        setState(State.STARTING);
        lsp.executeCommand(file, "vscode.java.resolveMainClass", List.of(), (res, err) -> {
            if (!isCurrent(epoch)) {
                return;
            }
            if (err != null) {
                fail(epoch, "resolveMainClass failed: " + msg(err));
                return;
            }
            List<MainClassOption> options = parseMainClasses(res);
            if (options.isEmpty()) {
                // jdtls enumerated no project main class (a standalone file, or the project is still
                // importing). Derive the fully-qualified class name from the file (package + type name)
                // and resolve its classpath — the adapter needs a real class name + classPaths, not a path.
                String fqn = mainClassFromFile(file);
                if (fqn == null) {
                    fail(epoch, "No main class could be determined for this file.");
                    return;
                }
                compileAndLaunch(file, fqn, javaExecOverride, epoch, null);
                return;
            }
            DapPaths paths = pathsFor(file);
            MainClassOption match = options.stream()
                    .filter(o -> JavaLaunchSupport.sameFile(o.filePath(), file, paths))
                    .findFirst()
                    .orElse(null);
            // A project's main class runs in the project root — what Run, the gutter and a saved
            // configuration use — and only a loose file in its own folder.
            Path dir = projectRoot != null ? projectRoot : file.getParent();
            String cwd = dir == null ? null : paths.toAdapter(dir);
            if (match != null && projectRoot == null) {
                // The file's own main class, in no build project: jdtls lists it from an "invisible
                // project" whose output folder nothing ever compiles into (autobuild is off), so the
                // resolved classpath holds no class file to run. Compile the file ourselves then.
                Runnable compile = () -> compileAndLaunch(file, match.className(), javaExecOverride, epoch, null);
                resolveAndLaunch(file, match, cwd, javaExecOverride, epoch, compile);
            } else if (match != null) {
                resolveAndLaunch(file, match, cwd, javaExecOverride, epoch);
            } else if (options.size() == 1) {
                resolveAndLaunch(file, options.get(0), cwd, javaExecOverride, epoch);
            } else {
                picker.pick(options, chosen -> {
                    if (chosen != null) {
                        resolveAndLaunch(file, chosen, cwd, javaExecOverride, epoch);
                    } else {
                        if (isCurrent(epoch)) {
                            setState(State.INACTIVE);
                        }
                    }
                });
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
        compileAndLaunch(file, compactMainClassFromFile(file), javaExecOverride, epoch, null);
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
            listener.onError("Java debugging is not available (enable it and install the java-debug plugin).");
            return false;
        }
        if (file == null) {
            listener.onError("Open a Java file to debug first.");
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
            startDebugSessionAndConnect(
                    file,
                    LaunchConfig.launch(
                            opt.mainClass(),
                            proj,
                            r.classPaths(),
                            r.modulePaths(),
                            // A remote file runs on its host's JDK, as jdtls there resolves it: the override is
                            // a JDK on this machine.
                            javaExecOverride == null
                                            || javaExecOverride.isBlank()
                                            || !com.editora.process.ExecTargets.runsHere(file)
                                    ? r.javaExec()
                                    : javaExecOverride,
                            cwd,
                            programArgs,
                            JavaLaunchSupport.withPreview(vmArgs, r.enablePreview()),
                            env,
                            false),
                    false,
                    epoch);
        });
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
            cb.accept(ResolvedLaunch.failed("No main class to run — set one on the run configuration."));
            return;
        }
        String proj = opt.projectName() == null ? "" : opt.projectName();
        lsp.executeCommand(routingFile, "vscode.java.resolveClasspath", List.of(opt.mainClass(), proj), (res, err) -> {
            if (err != null) {
                cb.accept(ResolvedLaunch.failed("Could not resolve the classpath: " + msg(err)));
                return;
            }
            List<String> modulepaths = new ArrayList<>();
            List<String> classpaths = new ArrayList<>();
            parseClasspath(res, modulepaths, classpaths);
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
                                + " value=" + abbreviate(String.valueOf(res)));
                cb.accept(ResolvedLaunch.failed("Could not resolve the classpath for " + opt.mainClass()
                        + " — make sure the Java project has finished importing (watch the LSP status), "
                        + "then try again."));
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
                                    asString(jx),
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
            cb.accept(parseMainClasses(res));
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
        if (!ready(routingFile)) {
            return;
        }
        restartAction = () -> startLaunchMainClass(routingFile, opt, cwd, javaExecOverride);
        debugFile = routingFile;
        long epoch = beginSession();
        setState(State.STARTING);
        resolveAndLaunch(
                routingFile, opt, cwd == null ? null : pathsFor(routingFile).toAdapter(cwd), javaExecOverride, epoch);
    }

    /**
     * Standalone-file fallback: jdtls enumerated no project main class (a loose file with no build
     * project, where jdtls's invisible-project classpath resolution is unreliable). Compile the file
     * ourselves with {@code javac -g} (debug info) to a temp dir, then launch with that dir as the
     * classpath — self-contained, like the Run feature. jdtls is still used only to start the adapter.
     * Runs off the FX thread (javac is a subprocess).
     */
    private void compileAndLaunch(Path file, String fqn, String javaExecOverride, long epoch, Integer sourceVersion) {
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
                    HostCompilation host = hostCompilation(
                            file, sourceVersion == null ? null : withoutShebang(Files.readString(file)));
                    out = host.directory();
                    removal = host.removal();
                    if (host.source() != null) {
                        compileFile = host.source();
                    }
                } else {
                    out = Files.createTempDirectory("editora-dap-");
                    if (sourceVersion != null) {
                        compileFile = out.resolve(file.getFileName() + ".java");
                        Files.writeString(compileFile, withoutShebang(Files.readString(file)));
                    }
                }
                String javaExec = remote
                        ? hostJava(file)
                        : javaExecOverride == null || javaExecOverride.isBlank()
                                ? firstOrNull(ProcessRunner.resolveExecutable(List.of("java")))
                                : javaExecOverride;
                // Route through ProcessRunner.run so the compile is bounded by a hard timeout (a hung javac
                // would otherwise pin the single-threaded `io` executor and block every later DAP start/detect)
                // and both pipes are drained concurrently (no fill-the-buffer deadlock).
                ProcessRunner.Result r = remote
                        ? compileOnHost(file, compileFile, out, javaExec, launchEnvironment, sourceVersion)
                        : compileStandalone(file, compileFile, out, javaExec, launchEnvironment, sourceVersion);
                if (!r.ok()) {
                    fail(epoch, "Compilation failed:\n" + (r.out() + "\n" + r.err()).strip());
                    return;
                }
                synchronized (sessionLock) {
                    if (!isCurrent(epoch)) {
                        return;
                    }
                    compilationDirectory = out;
                    compilationRemoval = removal;
                    if (sourceVersion != null) {
                        sourceAlias = new SourceAlias(epoch, file, compileFile);
                    }
                    retained = true;
                }
                String cwd = file.getParent() == null ? null : paths.toAdapter(file.getParent());
                String classes = paths.toAdapter(out);
                // The classes come from a temp dir, so java-debug cannot infer the project it compiles
                // conditions, logpoints and evaluated expressions against: name the one jdtls keeps the
                // source in (its invisible project for a loose file).
                Platform.runLater(() -> projectNameOf(
                        file,
                        project -> startDebugSessionAndConnect(
                                file,
                                LaunchConfig.launch(
                                        fqn,
                                        project,
                                        List.of(classes),
                                        List.of(),
                                        javaExec,
                                        cwd,
                                        programArgs,
                                        vmArgs,
                                        launchEnvironment,
                                        false),
                                false,
                                epoch)));
            } catch (Exception e) {
                fail(epoch, "Could not compile/launch " + fqn + ": " + msg(e));
            } finally {
                if (!retained && out != null) {
                    removeCompilation(out, removal);
                }
            }
        });
    }

    /** Compiles with the JDK that will run the debuggee, retaining local variables for the debugger. */
    static ProcessRunner.Result compileStandalone(
            Path file, Path outputDir, String javaExecOverride, Map<String, String> environment) {
        return compileStandalone(file, file, outputDir, javaExecOverride, environment, null);
    }

    private static ProcessRunner.Result compileStandalone(
            Path original,
            Path source,
            Path outputDir,
            String javaExecOverride,
            Map<String, String> environment,
            Integer sourceVersion) {
        List<String> command = new ArrayList<>(List.of(JdkToolchain.compilerForJavaExecutable(javaExecOverride), "-g"));
        if (sourceVersion != null) {
            command.add("--release");
            command.add(sourceVersion.toString());
        }
        command.addAll(List.of("-d", outputDir.toString(), source.toString()));
        return ProcessRunner.runInUserLocale(
                original.getParent(), java.time.Duration.ofSeconds(60), command, environment);
    }

    /**
     * A remote file's compilation on its target: the folder for its classes, the copy of its source when one
     * is compiled instead of the file ({@code null} otherwise), both named as Editora names the target's files
     * ({@link com.editora.process.ExecTarget#toPath}), and how the folder is removed.
     */
    private record HostCompilation(Path directory, Path source, Runnable removal) {}

    /**
     * A new folder for a remote file's compiled classes: {@code debug-classes/<id>} in its target's cache, with
     * {@code source} (when not {@code null}) written into it. Removed when the session ends, like a local one's
     * temporary folder — on the target, which for a container is the only place it is.
     */
    private static HostCompilation hostCompilation(Path file, String source) throws java.io.IOException {
        com.editora.process.ExecTarget target = com.editora.process.ExecTargets.forPath(file);
        com.editora.process.TargetCache cache = target.cache();
        if (cache == null) {
            throw new java.io.IOException("the host has no folder for Editora's files");
        }
        String folder = "debug-classes/" + java.util.UUID.randomUUID();
        Path directory = target.toPath(cache.makeDirectories(folder));
        Runnable removal = () -> {
            try {
                cache.delete(folder);
            } catch (java.io.IOException | RuntimeException e) {
                LOG.log(Level.FINE, "Could not remove temporary debug classes", e);
            }
        };
        Path copy = source == null
                ? null
                : target.toPath(cache.putText(folder + "/" + file.getFileName() + ".java", source));
        return new HostCompilation(directory, copy, removal);
    }

    /** {@code java} on a remote file's host: under its configured JDK ({@code jdk.home}), else its PATH's. */
    static String hostJava(Path file) {
        String home = com.editora.process.ExecTargets.remoteTool(file, "jdk.home");
        return home == null || home.isBlank() ? "java" : home.strip().replaceAll("/+$", "") + "/bin/java";
    }

    /** {@code javac} beside a host's {@code java} ({@link #hostJava}). Pure. */
    static String hostJavac(String java) {
        return java.endsWith("/bin/java") ? java.substring(0, java.length() - "java".length()) + "javac" : "javac";
    }

    /** {@link #compileStandalone} on a remote file's host: its javac, its paths. */
    private static ProcessRunner.Result compileOnHost(
            Path original,
            Path source,
            Path outputDir,
            String hostJava,
            Map<String, String> environment,
            Integer sourceVersion) {
        DapPaths paths = pathsFor(original);
        List<String> command = new ArrayList<>(List.of(hostJavac(hostJava), "-g"));
        if (sourceVersion != null) {
            command.add("--release");
            command.add(sourceVersion.toString());
        }
        command.addAll(List.of("-d", paths.toAdapter(outputDir), paths.toAdapter(source)));
        return ProcessRunner.runInUserLocale(
                original.getParent(), java.time.Duration.ofSeconds(120), command, environment);
    }

    /** Blank the first line without changing later line numbers for breakpoints and stack frames. */
    static String withoutShebang(String source) {
        if (source == null || !source.startsWith("#!")) {
            return source;
        }
        int newline = source.indexOf('\n');
        return newline < 0 ? "" : " ".repeat(newline) + source.substring(newline);
    }

    private SourceAlias activeSourceAlias() {
        SourceAlias alias = sourceAlias;
        return alias != null && alias.epoch() == sessionEpoch ? alias : null;
    }

    /** Remove both the temporary source and compiled classes after the adapter releases them. */
    private void releaseCompilationDirectory() {
        Path directory = compilationDirectory;
        Runnable removal = compilationRemoval;
        compilationDirectory = null;
        compilationRemoval = null;
        sourceAlias = null;
        if (directory != null) {
            java.util.concurrent.CompletableFuture.runAsync(() -> removeCompilation(directory, removal));
        }
    }

    /** {@code removal} when the folder is a target's, else this machine's folder walked and deleted. */
    private static void removeCompilation(Path directory, Runnable removal) {
        if (removal != null) {
            removal.run();
        } else {
            removeCompilationDirectory(directory);
        }
    }

    static void removeCompilationDirectory(Path directory) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, java.io.IOException error)
                        throws java.io.IOException {
                    if (error != null) throw error;
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (java.io.IOException e) {
            LOG.log(Level.FINE, "Could not remove temporary debug classes", e);
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
        String className = alias.original().getFileName().toString();
        return frame.file() == null
                && frame.name() != null
                && (frame.name().startsWith(className + ".") || frame.name().startsWith(className + "$"));
    }

    /** A compact source's implicit class is named for its file, even when it declares nested types. */
    static String compactMainClassFromFile(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.substring(0, fileName.length() - ".java".length());
    }

    private static String firstOrNull(List<String> l) {
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    /** Matches a top-level type declaration, capturing its name (e.g. {@code public final class Foo}). */
    private static final java.util.regex.Pattern TYPE_DECL =
            java.util.regex.Pattern.compile("\\b(?:public\\s+)?(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*"
                    + "(?:class|record|enum|interface)\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*)"); // not \\w: ASCII-only, cut
    // Café to Caf

    /**
     * Derives the fully-qualified main-class name from a {@code .java} file: its {@code package}
     * declaration plus the <em>declared</em> type name (the first top-level {@code class}/{@code record}/
     * {@code enum}), falling back to the file's base name. Reads the source (not the filename) so a
     * class whose name differs from the file resolves correctly. Pure-ish (one bounded file read).
     */
    static String mainClassFromFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return null;
        }
        String base = file.getFileName().toString();
        if (base.endsWith(".java")) {
            base = base.substring(0, base.length() - ".java".length());
        }
        if (base.isEmpty()) {
            return null;
        }
        String pkg = "";
        String cls = null;
        java.util.List<String> lines;
        try (java.util.stream.Stream<String> s = java.nio.file.Files.lines(file)) {
            lines = s.limit(500).toList();
        } catch (java.io.IOException | RuntimeException ignored) {
            lines = java.util.List.of();
        }
        for (String raw : lines) {
            String t = raw.strip();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                continue; // skip comment lines (crude, but enough to avoid most false matches)
            }
            if (pkg.isEmpty() && t.startsWith("package ") && t.endsWith(";")) {
                pkg = t.substring("package ".length(), t.length() - 1).strip();
            }
            if (cls == null) {
                java.util.regex.Matcher m = TYPE_DECL.matcher(t);
                if (m.find()) {
                    cls = m.group(1);
                }
            }
            if (!pkg.isEmpty() && cls != null) {
                break;
            }
        }
        if (cls == null) {
            cls = base; // no type declaration found — best-effort (e.g. a JEP 512 compact source)
        }
        return pkg.isEmpty() ? cls : pkg + "." + cls;
    }

    private void startDebugSessionAndConnect(
            Path file, java.util.Map<String, Object> args, boolean attach, long epoch) {
        if (!isCurrent(epoch)) {
            return;
        }
        lsp.executeCommand(file, "vscode.java.startDebugSession", List.of(), (res, err) -> {
            if (!isCurrent(epoch)) {
                return;
            }
            if (err != null) {
                fail(epoch, "Could not start the debug session: " + msg(err));
                return;
            }
            int port = asInt(res);
            if (port <= 0) {
                fail(epoch, "The debug adapter did not return a port.");
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
                    fail(epoch, "Could not connect to the debug adapter: " + msg(e));
                    return;
                }
                if (!isCurrent(epoch, c)) {
                    c.dispose();
                    return;
                }
                (attach ? c.attach(args) : c.launch(args)).whenComplete((v, le) -> {
                    if (le != null) {
                        fail(epoch, (attach ? "attach" : "launch") + " failed: " + msg(le));
                        return;
                    }
                    // Running once the adapter has the program, not when the request left: until then
                    // java-debug is still starting the JVM. A stop that came first has already said SUSPENDED.
                    Platform.runLater(() -> {
                        if (isCurrent(epoch, c) && state == State.STARTING) {
                            setState(State.RUNNING);
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
            listener.onError("No debug adapter is configured for this file type.");
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
                    fail(epoch, "Unsupported debug transport for " + language + ".");
                }
            } catch (Exception e) {
                fail(epoch, "Could not start the debugger: " + msg(e));
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
            listener.onError("Debugging is not enabled (turn it on in Settings → Debugging).");
            return false;
        }
        if (file == null) {
            listener.onError("Open a file to debug first.");
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
                    "python".equals(language)
                            ? "Python debugging is not available (enable it and install debugpy)."
                            : "JavaScript debugging is not available (enable it and install vscode-js-debug).");
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
        fail(
                epoch,
                "pytest is not installed for " + program.executable()
                        + " — install it in that environment (python -m pip install pytest).");
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
        com.editora.run.PythonRunPlan.Request request = com.editora.run.PythonRunPlan.Request.file(
                file,
                asked != null ? asked.projectRoot() : pythonRootFor(file),
                target.toTargetPath(file),
                List.of(),
                asked != null ? asked.firstLine() : null,
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
                fail(epoch, "Could not connect to debugpy: " + msg(e) + AdapterStderr.suffix(stderr));
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
            return "debugpy did not start the program within " + DapClient.requestTimeoutSeconds()
                    + " s — check that the Python interpreter runs: " + launch.get("python");
        }
        return "launch failed: " + msg(error);
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
            fail(epoch, "The vscode-js-debug adapter was not found.");
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
            listener.onError("Debugging is not enabled (turn it on in Settings → Debugging).");
            return;
        }
        if (!isLanguageAvailable("javascript")) {
            listener.onError("JavaScript debugging is not available (enable it and install vscode-js-debug).");
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
                    fail(epoch, "The vscode-js-debug adapter was not found.");
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
                fail(epoch, "Could not start the debugger: " + msg(e));
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
                fail(epoch, "Could not connect to vscode-js-debug: " + msg(e));
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
                            fail(epoch, (attachArgs != null ? "attach failed: " : "launch failed: ") + msg(le));
                        }
                    });
            Platform.runLater(() -> {
                if (isCurrent(epoch, c)) {
                    setState(State.RUNNING);
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
            return "Debugging this language on a remote host is not available yet.";
        }
        if ("python".equals(language) ? !pythonEnabled : !jsEnabled) {
            return "python".equals(language)
                    ? "Python debugging is turned off (Settings → Debugging)."
                    : "JavaScript debugging is turned off (Settings → Debugging).";
        }
        if (!com.editora.process.ExecTargets.forPath(file).isAvailable()) {
            return "Commands cannot run on this file's host (connect, and allow running commands there).";
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
            fail(
                    epoch,
                    "debugpy is not installed for " + python.executable() + " on " + target.id()
                            + " (install it there with pip, or set the host's Python for debugging).");
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
                            ? "node is not installed on " + target.id() + "."
                            : "vscode-js-debug (dapDebugServer.js) was not found on " + target.id()
                                    + " (install it there, or set its path for the host).");
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
            fail(epoch, "vscode-js-debug did not start on " + target.id() + ".");
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

    /** The file's existing breakpoints plus a temporary plain one at {@code line} (no duplicate when a
     *  breakpoint already sits there). Pure — tested. */
    static DapModels.FileBreakpoints withTempLine(List<DapModels.FileBreakpoints> all, Path file, int line) {
        List<DapModels.LineBreakpoint> lines = new ArrayList<>();
        for (DapModels.FileBreakpoints fb : all == null ? List.<DapModels.FileBreakpoints>of() : all) {
            if (com.editora.config.PathKeys.samePath(fb.file(), file)) {
                lines.addAll(fb.breakpoints());
            }
        }
        boolean present = false;
        for (DapModels.LineBreakpoint lb : lines) {
            if (lb.line() == line) {
                present = true;
                break;
            }
        }
        if (!present) {
            lines.add(new DapModels.LineBreakpoint(line, null, null));
        }
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
            c.dispose();
        }
        killAdapterProgram(); // an adapter that is gone cannot end the program it launched
        releaseCompilationDirectory();
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
                .whenComplete((vars, e) -> Platform.runLater(() -> {
                    if (client == c) {
                        cb.accept(vars == null ? List.of() : vars);
                    }
                }));
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
        c.evaluate(expression, frameId, context)
                .whenComplete((r, e) -> Platform.runLater(() -> {
                    if (console && client == c) {
                        consoleEvaluations = Math.max(0, consoleEvaluations - 1);
                    }
                    if (client == c) {
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

    // --- DapClient.Host (launcher reader thread → FX) -------------------------------------------

    @Override
    public void onStopped(int threadId, String reason) {
        onStopped(sessionEpoch, threadId, reason, false);
    }

    @Override
    public void onStopped(int threadId, String reason, boolean allThreadsStopped) {
        onStopped(sessionEpoch, threadId, reason, allThreadsStopped);
    }

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

    @Override
    public void onContinued() {
        onContinued(sessionEpoch, 0, true);
    }

    @Override
    public void onContinued(int threadId, boolean allThreadsContinued) {
        onContinued(sessionEpoch, threadId, allThreadsContinued);
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

    @Override
    public void onOutput(String text, String category) {
        onOutput(sessionEpoch, text, category);
    }

    /** Batches adapter output onto the FX thread (see {@link DapOutputPump}); the sink runs on FX. */
    private final DapOutputPump outputPump = new DapOutputPump(Platform::runLater, (epoch, text, category) -> {
        if (isCurrent(epoch)) {
            listener.onOutput(text, category);
        }
    });

    private void onOutput(long epoch, String text, String category) {
        outputPump.offer(epoch, text, category);
    }

    @Override
    public void onTerminated() {
        onTerminated(sessionEpoch);
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
            c.dispose();
        }
        killAdapterProgram();
        releaseCompilationDirectory();
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
            listener.onProgramExit(exitCode);
        });
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

    @Override
    public void onError(String message) {
        onError(sessionEpoch, message);
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
        return t == null ? "" : (t.getMessage() == null ? t.toString() : t.getMessage());
    }

    // --- gson result parsing (jdtls executeCommand returns untyped JsonElements) -----------------

    /**
     * Reads {@code vscode.java.resolveMainClass}'s reply — {@code [{mainClass, projectName, filePath}, …]}.
     *
     * <p>Handles a raw {@link java.util.List} of {@link Map}s as well as a gson {@link JsonElement}, for the
     * same reason {@link #parseClasspath} does — and here it was not merely a hazard but the whole feature:
     * lsp4j hands this particular result back as an {@code ArrayList<LinkedTreeMap>}, so {@link #asJson}'s
     * {@code String.valueOf} fallback fed gson Java's {@code toString()} form,
     * {@code [{mainClass=com.example.App, filePath=/home/…}]}. Gson's lenient parser reads unquoted keys and
     * {@code =} separators, but not an unquoted value beginning {@code /} (it scans as a comment) — so every
     * absolute file path threw {@code MalformedJsonException}, {@code asJson} logged it at {@code FINE} and
     * returned null, and Editora reported "no main class was found" for a project whose main class jdtls had
     * just named. Confirmed against a real jdtls in {@code JdtlsResolveMainClassProbeTest}. Reading the list
     * directly removes the round trip entirely.
     */
    private static List<MainClassOption> parseMainClasses(Object res) {
        List<MainClassOption> out = new ArrayList<>();
        if (res instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof Map<?, ?> m) {
                    out.add(new MainClassOption(str(m, "mainClass"), str(m, "projectName"), str(m, "filePath")));
                } else if (e instanceof JsonObject o) {
                    out.add(new MainClassOption(str(o, "mainClass"), str(o, "projectName"), str(o, "filePath")));
                }
            }
            return out;
        }
        JsonElement el = asJson(res);
        if (el != null && el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    JsonObject o = e.getAsJsonObject();
                    out.add(new MainClassOption(str(o, "mainClass"), str(o, "projectName"), str(o, "filePath")));
                }
            }
        }
        return out;
    }

    /**
     * resolveClasspath returns {@code [modulepaths, classpaths]} (a 2-element array of string arrays).
     *
     * <p>Handles a raw {@link java.util.List} as well as a gson {@link JsonElement}. lsp4j deserializes an
     * untyped {@code executeCommand} result to whichever the payload maps to, and the {@link #asJson}
     * fallback — {@code JsonParser.parseString(String.valueOf(res))} — round-trips a {@code List} through
     * Java's {@code toString()}, which emits <em>unquoted</em> elements. Gson's lenient parser accepts that
     * for a plain path but not for one containing a comma, bracket or space, so a classpath entry could be
     * silently dropped or mangled depending on where the project happens to live. Reading the list directly
     * removes the round trip entirely.
     */
    private static void parseClasspath(Object res, List<String> modulepaths, List<String> classpaths) {
        if (res instanceof List<?> list) {
            if (!list.isEmpty()) {
                collectStrings(list.get(0), modulepaths);
            }
            if (list.size() >= 2) {
                collectStrings(list.get(1), classpaths);
            }
            return;
        }
        JsonElement el = asJson(res);
        if (el != null && el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() >= 1) {
                collectStrings(arr.get(0), modulepaths);
            }
            if (arr.size() >= 2) {
                collectStrings(arr.get(1), classpaths);
            }
        }
    }

    /** Collects strings from either a gson array or a plain {@link java.util.List}. */
    private static void collectStrings(Object maybeList, List<String> out) {
        if (maybeList instanceof JsonElement el) {
            collectStrings(el, out);
            return;
        }
        if (maybeList instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    out.add(s);
                } else if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        }
    }

    private static void collectStrings(JsonElement el, List<String> out) {
        if (el != null && el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e.isJsonPrimitive()) {
                    out.add(e.getAsString());
                }
            }
        }
    }

    private static int asInt(Object res) {
        JsonElement el = asJson(res);
        if (el != null && el.isJsonPrimitive()) {
            try {
                return el.getAsInt();
            } catch (RuntimeException ignored) {
                // not a number
            }
        }
        if (res instanceof Number n) {
            return n.intValue();
        }
        return -1;
    }

    /**
     * A server reply that is meant to be a single string — a path, a name.
     *
     * <p>A {@link String} is taken <b>as the value</b> rather than as JSON text. lsp4j only hands back a
     * gson {@link JsonElement} for a reply it could not type; {@code vscode.java.resolveJavaExecutable}
     * answers a bare path, which arrives as a plain String and is not JSON — so routing it through
     * {@link #asJson} threw on every debug launch, logged a WARNING with a stack trace, and then fell back
     * to exactly this value anyway. The result was always right; the noise said otherwise.
     */
    private static String asString(Object res) {
        if (res instanceof String s) {
            return s;
        }
        JsonElement el = asJson(res);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** The {@link Map} twin of the above, for a reply lsp4j deserialized to plain collections. */
    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** Bounded rendering of a server reply for a log line — a classpath can be very long. */
    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 600 ? s : s.substring(0, 600) + "… (" + s.length() + " chars)";
    }

    /** lsp4j hands untyped executeCommand results back as gson {@link JsonElement}s; pass others through. */
    private static JsonElement asJson(Object res) {
        if (res instanceof JsonElement el) {
            return el;
        }
        if (res == null) {
            return null;
        }
        try {
            return com.google.gson.JsonParser.parseString(String.valueOf(res));
        } catch (RuntimeException e) {
            // WARNING, not FINE: every caller turns null into an empty result, which reaches the user as a
            // statement about their project ("no main class was found"). A swallowed parse failure reported
            // as a fact about the workspace is how the resolveMainClass bug survived — say so in the log.
            LOG.log(
                    Level.WARNING,
                    e,
                    () -> "could not parse an executeCommand result of type "
                            + res.getClass().getName() + ": " + abbreviate(String.valueOf(res)));
            return null;
        }
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
