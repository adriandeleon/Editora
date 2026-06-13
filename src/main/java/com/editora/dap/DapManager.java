package com.editora.dap;

import com.editora.lsp.LspManager;
import com.editora.process.ProcessRunner;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.application.Platform;

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
    public record MainClassOption(String mainClass, String projectName, String filePath) {}

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

    private DapClient client;
    private State state = State.INACTIVE;
    private int currentThreadId;
    private Path debugFile;
    private Supplier<List<DapModels.FileBreakpoints>> breakpointsSupplier = List::of;
    private List<String> exceptionFilters = List.of();
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

    public void setExceptionFilters(List<String> filters) {
        this.exceptionFilters = filters == null ? List.of() : List.copyOf(filters);
        if (client != null) {
            client.setExceptionFilters(this.exceptionFilters);
        }
    }

    /** Java-only configure (kept for back-compat); delegates with python/js disabled. */
    public void configure(boolean enabled, String pluginPath) {
        configure(enabled, pluginPath, false, "", false, "");
    }

    /**
     * Stores the master enable flag + every language's enable/command, re-resolves the java-debug bundle
     * jar(s) and the python/js adapter locations (filesystem, cheap — like the existing java {@code locate}).
     * Availability (which needs a subprocess probe) is set later by {@link #detectPython}/{@link #detectJs}.
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
        this.debugpyDir = enabled && pythonEnabled
                ? DebugAdapterLocator.locateDebugpy("", home()).orElse(null)
                : null;
        this.jsServer = enabled && jsEnabled
                ? DebugAdapterLocator.locateJsDebugServer(this.jsPath, home()).orElse(null)
                : null;
        if (!enabled || !pythonEnabled) {
            this.pythonAvailable = false;
        }
        if (!enabled || !jsEnabled) {
            this.jsAvailable = false;
        }
    }

    /** The located java-debug plugin jar(s) — for {@code LspManager.setDebugBundles}. Empty when not found. */
    public List<String> bundlePaths() {
        return bundlePaths;
    }

    public boolean isAdapterAvailable() {
        return !bundlePaths.isEmpty();
    }

    /** Whether a usable debug adapter is available for {@code language} (after detection). */
    public boolean isLanguageAvailable(String language) {
        if (language == null) {
            return false;
        }
        return switch (language) {
            case "java" -> !bundlePaths.isEmpty();
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
        io.submit(() -> {
            Path dir = DebugAdapterLocator.locateDebugpy("", home()).orElse(null);
            boolean ok = enabled && pythonEnabled && probeDebugpy(dir);
            Platform.runLater(() -> {
                this.debugpyDir = dir;
                this.pythonAvailable = ok;
                onResult.accept(ok);
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
        try {
            List<String> cmd = ProcessRunner.resolveExecutable(argv);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            ProcessRunner.applyStandardEnv(pb);
            if (pythonPathDir != null) {
                prependPythonPath(pb, pythonPathDir);
            }
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
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

    // --- Start (launch / attach) ----------------------------------------------------------------

    /**
     * Launches a debug session for {@code file} in {@code language}: dispatches to the jdtls flow for
     * {@code java} (the {@code picker} resolves an ambiguous main class), or to the standalone-adapter
     * flow ({@link #startProgram}) for {@code python}/{@code javascript}. No-op (with an error) for an
     * unsupported language.
     */
    public void startLaunch(Path file, String language, MainClassPicker picker) {
        if ("java".equals(language)) {
            startLaunch(file, picker);
        } else if (DapServerRegistry.isDebuggable(language)) {
            startProgram(file, language);
        } else {
            listener.onError("Debugging is not supported for this file type.");
        }
    }

    /** Program arguments for the next launch (shared with the Run feature's per-file args). */
    private volatile List<String> programArgs = List.of();

    /** Sets the debuggee's program arguments; call before {@link #startLaunch}. Sticky across
     *  {@link #restart()} (the restart re-runs the same launch). */
    public void setProgramArgs(List<String> args) {
        this.programArgs = args == null ? List.of() : List.copyOf(args);
    }

    /**
     * Launches a Java debug session for {@code file}: resolves the main class (asking {@code picker} if
     * several are found), its classpath + java executable, starts the adapter, and connects. No-op (with an
     * error to the listener) when debugging is unavailable.
     */
    public void startLaunch(Path file, MainClassPicker picker) {
        if (!ready(file)) {
            return;
        }
        restartAction = () -> startLaunch(file, picker);
        debugFile = file;
        setState(State.STARTING);
        lsp.executeCommand(file, "vscode.java.resolveMainClass", List.of(), (res, err) -> {
            if (err != null) {
                fail("resolveMainClass failed: " + msg(err));
                return;
            }
            List<MainClassOption> options = parseMainClasses(res);
            if (options.isEmpty()) {
                // jdtls enumerated no project main class (a standalone file, or the project is still
                // importing). Derive the fully-qualified class name from the file (package + type name)
                // and resolve its classpath — the adapter needs a real class name + classPaths, not a path.
                String fqn = mainClassFromFile(file);
                if (fqn == null) {
                    fail("No main class could be determined for this file.");
                    return;
                }
                compileAndLaunch(file, fqn);
                return;
            }
            MainClassOption match = options.stream()
                    .filter(o -> sameFile(o.filePath(), file))
                    .findFirst()
                    .orElse(null);
            if (match != null) {
                resolveAndLaunch(file, match);
            } else if (options.size() == 1) {
                resolveAndLaunch(file, options.get(0));
            } else {
                picker.pick(options, chosen -> {
                    if (chosen != null) {
                        resolveAndLaunch(file, chosen);
                    } else {
                        setState(State.INACTIVE);
                    }
                });
            }
        });
    }

    /** Attaches to a running JVM at {@code host:port} (the file provides the jdtls session for the adapter). */
    public void startAttach(Path file, String host, int port) {
        if (!ready(file)) {
            return;
        }
        restartAction = () -> startAttach(file, host, port);
        debugFile = file;
        setState(State.STARTING);
        startDebugSessionAndConnect(file, LaunchConfig.attach(host, port), true);
    }

    private boolean ready(Path file) {
        if (!enabled || bundlePaths.isEmpty()) {
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

    private void resolveAndLaunch(Path file, MainClassOption opt) {
        String proj = opt.projectName() == null ? "" : opt.projectName();
        lsp.executeCommand(file, "vscode.java.resolveClasspath", List.of(opt.mainClass(), proj), (res, err) -> {
            if (err != null) {
                fail("Could not resolve the classpath: " + msg(err));
                return;
            }
            List<String> modulepaths = new ArrayList<>();
            List<String> classpaths = new ArrayList<>();
            parseClasspath(res, modulepaths, classpaths);
            if (classpaths.isEmpty() && modulepaths.isEmpty()) {
                fail("Could not resolve the classpath for " + opt.mainClass()
                        + " — make sure the Java project has finished importing (watch the LSP status), "
                        + "then try again.");
                return;
            }
            lsp.executeCommand(file, "vscode.java.resolveJavaExecutable", List.of(opt.mainClass(), proj), (jx, e2) -> {
                String javaExec = asString(jx);
                String cwd = file.getParent() == null ? null : file.getParent().toString();
                startDebugSessionAndConnect(
                        file,
                        LaunchConfig.launch(
                                opt.mainClass(), proj, classpaths, modulepaths, javaExec, cwd, programArgs, false),
                        false);
            });
        });
    }

    /**
     * Standalone-file fallback: jdtls enumerated no project main class (a loose file with no build
     * project, where jdtls's invisible-project classpath resolution is unreliable). Compile the file
     * ourselves with {@code javac -g} (debug info) to a temp dir, then launch with that dir as the
     * classpath — self-contained, like the Run feature. jdtls is still used only to start the adapter.
     * Runs off the FX thread (javac is a subprocess).
     */
    private void compileAndLaunch(Path file, String fqn) {
        io.submit(() -> {
            try {
                Path out = java.nio.file.Files.createTempDirectory("editora-dap-");
                out.toFile().deleteOnExit();
                List<String> cmd =
                        ProcessRunner.resolveExecutable(List.of("javac", "-g", "-d", out.toString(), file.toString()));
                ProcessBuilder pb = new ProcessBuilder(cmd);
                ProcessRunner.applyStandardEnv(pb);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String log = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (p.waitFor() != 0) {
                    fail("Compilation failed:\n" + log.strip());
                    return;
                }
                String javaExec = firstOrNull(ProcessRunner.resolveExecutable(List.of("java")));
                String cwd = file.getParent() == null ? null : file.getParent().toString();
                Platform.runLater(() -> startDebugSessionAndConnect(
                        file,
                        LaunchConfig.launch(
                                fqn, null, List.of(out.toString()), List.of(), javaExec, cwd, programArgs, false),
                        false));
            } catch (Exception e) {
                fail("Could not compile/launch " + fqn + ": " + msg(e));
            }
        });
    }

    private static String firstOrNull(List<String> l) {
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    /** Matches a top-level type declaration, capturing its name (e.g. {@code public final class Foo}). */
    private static final java.util.regex.Pattern TYPE_DECL =
            java.util.regex.Pattern.compile("\\b(?:public\\s+)?(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*"
                    + "(?:class|record|enum|interface)\\s+(\\w+)");

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

    private void startDebugSessionAndConnect(Path file, java.util.Map<String, Object> args, boolean attach) {
        lsp.executeCommand(file, "vscode.java.startDebugSession", List.of(), (res, err) -> {
            if (err != null) {
                fail("Could not start the debug session: " + msg(err));
                return;
            }
            int port = asInt(res);
            if (port <= 0) {
                fail("The debug adapter did not return a port.");
                return;
            }
            DapClient c = new DapClient(this);
            c.setBreakpoints(breakpointsSupplier.get()); // snapshot on the FX thread (this callback is on FX)
            c.setExceptionFilters(exceptionFilters);
            client = c;
            // Connect off the FX thread (the socket open blocks with retries), then launch/attach. The
            // connect future completes on the DAP reader thread, so every state change here must be
            // marshaled back to the FX thread (it touches the Debug panel + status bar).
            io.submit(() -> c.connect(port, "java").whenComplete((caps, e) -> {
                if (e != null) {
                    fail("Could not connect to the debug adapter: " + msg(e));
                    return;
                }
                (attach ? c.attach(args) : c.launch(args)).whenComplete((v, le) -> {
                    if (le != null) {
                        fail((attach ? "attach" : "launch") + " failed: " + msg(le));
                    }
                });
                Platform.runLater(() -> setState(State.RUNNING));
            }));
        });
    }

    // --- Standalone adapters (python / javascript) ----------------------------------------------

    /**
     * Launches a debug session for a standalone-adapter language (python/javascript). Snapshots the
     * breakpoints on the FX thread, then spawns the adapter + connects + sends {@code launch} off it.
     */
    private void startProgram(Path file, String language) {
        if (!readyProgram(file, language)) {
            return;
        }
        DapServerRegistry.DapServerSpec spec = DapServerRegistry.specFor(language);
        if (spec == null) {
            listener.onError("No debug adapter is configured for this file type.");
            return;
        }
        restartAction = () -> startProgram(file, language);
        debugFile = file;
        setState(State.STARTING);
        // Snapshot UI state on the FX thread before going off it.
        List<DapModels.FileBreakpoints> bps = breakpointsSupplier.get();
        List<String> filters = exceptionFilters;
        io.submit(() -> {
            try {
                if (spec.kind() == DapServerRegistry.Kind.STDIO) {
                    startStdio(file, spec, bps, filters);
                } else if (spec.kind() == DapServerRegistry.Kind.SOCKET) {
                    startSocket(file, spec, bps, filters);
                } else {
                    fail("Unsupported debug transport for " + language + ".");
                }
            } catch (Exception e) {
                fail("Could not start the debugger: " + msg(e));
            }
        });
    }

    private boolean readyProgram(Path file, String language) {
        if (!enabled) {
            listener.onError("Debugging is not enabled (turn it on in Settings → Debugging).");
            return false;
        }
        if (file == null) {
            listener.onError("Open a file to debug first.");
            return false;
        }
        if (!isLanguageAvailable(language)) {
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

    /** debugpy over stdio: spawn {@code <python> -m debugpy.adapter}, wire DAP to its streams, launch. */
    private void startStdio(
            Path file, DapServerRegistry.DapServerSpec spec, List<DapModels.FileBreakpoints> bps, List<String> filters)
            throws Exception {
        List<String> argv = new ArrayList<>(DapServerRegistry.interpreterArgv("python", pythonCommand));
        argv.addAll(spec.adapterArgs()); // -m debugpy.adapter
        List<String> cmd = ProcessRunner.resolveExecutable(argv);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        ProcessRunner.applyStandardEnv(pb);
        if (debugpyDir != null) {
            prependPythonPath(pb, debugpyDir);
        }
        pb.redirectError(ProcessBuilder.Redirect.DISCARD); // DAP is on stdin/stdout; an undrained PIPE deadlocks
        Process proc = pb.start();
        DapClient c = new DapClient(this);
        c.setBreakpoints(bps);
        c.setExceptionFilters(filters);
        Platform.runLater(() -> client = c);
        String interpExe = cmd.isEmpty() ? null : cmd.get(0);
        c.connectStdio(proc, spec.adapterId()).whenComplete((caps, e) -> {
            if (e != null) {
                fail("Could not connect to debugpy: " + msg(e));
                return;
            }
            c.launch(LaunchConfig.program(
                            spec.launchType(), file.toString(), cwdOf(file), interpExe, programArgs, false))
                    .whenComplete((v, le) -> {
                        if (le != null) {
                            fail("launch failed: " + msg(le));
                        }
                    });
            Platform.runLater(() -> setState(State.RUNNING));
        });
    }

    /** vscode-js-debug over a socket: spawn {@code node dapDebugServer.js <port>}, connect, launch. */
    private void startSocket(
            Path file, DapServerRegistry.DapServerSpec spec, List<DapModels.FileBreakpoints> bps, List<String> filters)
            throws Exception {
        if (jsServer == null) {
            fail("The vscode-js-debug adapter was not found.");
            return;
        }
        int port = freePort();
        List<String> cmd = ProcessRunner.resolveExecutable(
                List.of(spec.defaultInterpreter(), jsServer.toString(), String.valueOf(port)));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        ProcessRunner.applyStandardEnv(pb);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD); // DAP is on the socket; stdout/stderr are logs
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();
        String nodeExe = cmd.isEmpty() ? null : cmd.get(0);
        DapClient c = new DapClient(this);
        c.setBreakpoints(bps);
        c.setExceptionFilters(filters);
        c.setAdapterProcess(proc); // killed on dispose (it serves the socket)
        Platform.runLater(() -> client = c);
        c.connect(port, spec.adapterId()).whenComplete((caps, e) -> {
            if (e != null) {
                fail("Could not connect to vscode-js-debug: " + msg(e));
                return;
            }
            c.launch(LaunchConfig.program(spec.launchType(), file.toString(), cwdOf(file), nodeExe, programArgs, false))
                    .whenComplete((v, le) -> {
                        if (le != null) {
                            fail("launch failed: " + msg(le));
                        }
                    });
            Platform.runLater(() -> setState(State.RUNNING));
        });
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
        if (client != null) {
            client.resume(currentThreadId);
            setState(State.RUNNING);
        }
    }

    /** Pauses a running session: targets the current thread if the adapter reports it, else the first.
     *  The adapter answers with a {@code stopped(reason=pause)} event, which flows through onStopped. */
    public void pause() {
        DapClient c = client;
        if (c == null || state != State.RUNNING) {
            return;
        }
        c.threads().whenComplete((list, e) -> {
            if (e != null || list == null || list.isEmpty()) {
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
        if (client == null) {
            cb.accept(List.of());
            return;
        }
        client.threads().whenComplete((list, e) -> Platform.runLater(() -> cb.accept(list == null ? List.of() : list)));
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
        c.sendSetBreakpoints(withTempLine(breakpointsSupplier.get(), file, line));
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
                            Platform.runLater(() -> onError.accept(msg(e2)));
                        }
                    });
                }));
    }

    /** The file's existing breakpoints plus a temporary plain one at {@code line} (no duplicate when a
     *  breakpoint already sits there). Pure — tested. */
    static DapModels.FileBreakpoints withTempLine(List<DapModels.FileBreakpoints> all, Path file, int line) {
        List<DapModels.LineBreakpoint> lines = new ArrayList<>();
        for (DapModels.FileBreakpoints fb : all == null ? List.<DapModels.FileBreakpoints>of() : all) {
            if (fb.file().equals(file)) {
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
            if (fb.file().equals(f)) {
                real.addAll(fb.breakpoints());
            }
        }
        c.sendSetBreakpoints(new DapModels.FileBreakpoints(f, real));
    }

    public void stepOver() {
        if (client != null) {
            client.next(currentThreadId);
        }
    }

    public void stepInto() {
        if (client != null) {
            client.stepIn(currentThreadId);
        }
    }

    public void stepOut() {
        if (client != null) {
            client.stepOut(currentThreadId);
        }
    }

    public void stop() {
        DapClient c = client;
        client = null;
        if (c != null) {
            c.dispose();
        }
        setState(State.INACTIVE);
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
        if (client == null) {
            cb.accept(List.of());
            return;
        }
        client.stackTrace(threadId)
                .whenComplete((frames, e) -> Platform.runLater(() -> cb.accept(frames == null ? List.of() : frames)));
    }

    public void scopes(int frameId, Consumer<List<DapModels.ScopeInfo>> cb) {
        if (client == null) {
            cb.accept(List.of());
            return;
        }
        client.scopes(frameId)
                .whenComplete((scopes, e) -> Platform.runLater(() -> cb.accept(scopes == null ? List.of() : scopes)));
    }

    public void variables(int ref, Consumer<List<DapModels.VariableInfo>> cb) {
        if (client == null) {
            cb.accept(List.of());
            return;
        }
        client.variables(ref)
                .whenComplete((vars, e) -> Platform.runLater(() -> cb.accept(vars == null ? List.of() : vars)));
    }

    public void evaluate(String expression, int frameId, String context, Consumer<String> cb) {
        if (client == null) {
            cb.accept("");
            return;
        }
        client.evaluate(expression, frameId, context)
                .whenComplete((r, e) ->
                        Platform.runLater(() -> cb.accept(e != null ? "error: " + msg(e) : (r == null ? "" : r))));
    }

    /** Full evaluate (result + expandable reference + type) for watches and the hover value popup.
     *  Failures deliver an {@code EvalResult} with the error text and no children. */
    public void evaluateFull(String expression, int frameId, String context, Consumer<DapModels.EvalResult> cb) {
        if (client == null) {
            cb.accept(new DapModels.EvalResult("", 0, null));
            return;
        }
        client.evaluateFull(expression, frameId, context)
                .whenComplete((r, e) -> Platform.runLater(() -> cb.accept(
                        e != null
                                ? new DapModels.EvalResult(msg(e), 0, null)
                                : (r == null ? new DapModels.EvalResult("", 0, null) : r))));
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
                .whenComplete((r, e) -> Platform.runLater(() -> cb.accept(e != null || r == null ? null : r.result())));
    }

    public void setVariable(int ref, String name, String value, Consumer<String> cb) {
        if (client == null) {
            cb.accept(value);
            return;
        }
        client.setVariable(ref, name, value)
                .whenComplete((r, e) -> Platform.runLater(() -> cb.accept(r == null ? value : r)));
    }

    /** (Re)sends a file's breakpoints to the live adapter (after the user toggled one while running). */
    public void updateBreakpoints(DapModels.FileBreakpoints fb) {
        if (client != null && state != State.INACTIVE) {
            client.sendSetBreakpoints(fb);
        }
    }

    // --- DapClient.Host (launcher reader thread → FX) -------------------------------------------

    @Override
    public void onStopped(int threadId, String reason) {
        currentThreadId = threadId;
        if (client == null) {
            return;
        }
        client.stackTrace(threadId)
                .whenComplete((frames, e) -> Platform.runLater(() -> {
                    clearTempBreakpoint(); // a run-to-cursor temp breakpoint is one-shot
                    state = State.SUSPENDED;
                    listener.onState(State.SUSPENDED);
                    listener.onStopped(threadId, reason, frames == null ? List.of() : frames);
                }));
    }

    @Override
    public void onContinued() {
        Platform.runLater(() -> setState(State.RUNNING));
    }

    @Override
    public void onOutput(String text, String category) {
        Platform.runLater(() -> listener.onOutput(text, category));
    }

    @Override
    public void onTerminated() {
        Platform.runLater(() -> {
            tempBreakpointFile = null; // session over — nothing to restore
            DapClient c = client;
            client = null;
            if (c != null) {
                c.dispose();
            }
            setState(State.INACTIVE);
        });
    }

    @Override
    public void onError(String message) {
        Platform.runLater(() -> listener.onError(message));
    }

    // --- Internals ------------------------------------------------------------------------------

    private void setState(State s) {
        this.state = s;
        listener.onState(s);
    }

    private void fail(String message) {
        Platform.runLater(() -> {
            listener.onError(message);
            stop();
        });
    }

    private static String msg(Throwable t) {
        return t == null ? "" : (t.getMessage() == null ? t.toString() : t.getMessage());
    }

    private static boolean sameFile(String a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return Objects.equals(
                    Path.of(a).toAbsolutePath().normalize(), b.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    // --- gson result parsing (jdtls executeCommand returns untyped JsonElements) -----------------

    private static List<MainClassOption> parseMainClasses(Object res) {
        List<MainClassOption> out = new ArrayList<>();
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

    /** resolveClasspath returns {@code [modulepaths, classpaths]} (a 2-element array of string arrays). */
    private static void parseClasspath(Object res, List<String> modulepaths, List<String> classpaths) {
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

    private static String asString(Object res) {
        JsonElement el = asJson(res);
        if (el != null && el.isJsonPrimitive()) {
            return el.getAsString();
        }
        return res instanceof String s ? s : null;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
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
            LOG.log(Level.FINE, "could not parse executeCommand result", e);
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
