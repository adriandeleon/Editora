package com.editora.dap.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassNotPreparedException;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.ListeningConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.StepRequest;
import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.BreakpointEventArguments;
import org.eclipse.lsp4j.debug.BreakpointEventArgumentsReason;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.ContinueResponse;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.EvaluateResponse;
import org.eclipse.lsp4j.debug.ExceptionBreakpointsFilter;
import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.NextArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.PauseArguments;
import org.eclipse.lsp4j.debug.RunInTerminalRequestArguments;
import org.eclipse.lsp4j.debug.RunInTerminalRequestArgumentsKind;
import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.ScopesArguments;
import org.eclipse.lsp4j.debug.ScopesResponse;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsResponse;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StackFramePresentationHint;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StackTraceResponse;
import org.eclipse.lsp4j.debug.StepInArguments;
import org.eclipse.lsp4j.debug.StepOutArguments;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.StoppedEventArgumentsReason;
import org.eclipse.lsp4j.debug.TerminateArguments;
import org.eclipse.lsp4j.debug.TerminatedEventArguments;
import org.eclipse.lsp4j.debug.Thread;
import org.eclipse.lsp4j.debug.ThreadsResponse;
import org.eclipse.lsp4j.debug.Variable;
import org.eclipse.lsp4j.debug.VariablesArguments;
import org.eclipse.lsp4j.debug.VariablesResponse;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

/**
 * A Debug Adapter Protocol server for programs in any JVM language — Editora's own, built on JDI, for the
 * languages no other adapter covers (Groovy first). See {@link JvmSourceMap} for why java-debug cannot.
 *
 * <p><b>Launch.</b> The {@code launch} request names the program's command line ({@code argv}), folder, extra
 * environment, and the environment variable that carries JVM options to its JVM ({@code jvmOptionsVariable},
 * {@code JAVA_OPTS} for Groovy). The adapter listens for the JVM on a loopback port, puts
 * {@code -agentlib:jdwp=…,server=n,suspend=y,address=127.0.0.1:<port>} in that variable, and has the client
 * start the program ({@code runInTerminal}, so its output, input and exit code go wherever the client shows
 * them) — or starts it itself and forwards its output, for a client that does not offer that. The JVM
 * connects back before it runs anything, so breakpoints are in place when the first line runs.
 *
 * <p><b>Breakpoints</b> bind by source name: in every loaded class compiled from the file, and in each such
 * class as it loads ({@code ClassPrepareRequest.addSourceNameFilter}).
 *
 * <p><b>Inspection</b> is what JDI gives without running code in the program: threads, frames, locals,
 * {@code this}, fields and array elements, and {@code evaluate} of a variable or field path. Nothing is invoked
 * in the program: a watch or a value never runs {@code toString()}, so looking cannot change what it looks at.
 *
 * <p>JDI work runs on one thread ({@link #jdi}); the event loop has its own.
 */
public final class JvmDebugAdapter implements IDebugProtocolServer {

    private static final Logger LOG = Logger.getLogger(JvmDebugAdapter.class.getName());

    /** Exception filter ids, as the Debug panel's catalog names them. */
    public static final String FILTER_UNCAUGHT = "uncaught";

    public static final String FILTER_CAUGHT = "caught";

    /** How long the program has to start and connect back before the launch fails. */
    private static final long CONNECT_TIMEOUT_MS = 60_000;

    /**
     * How long this launch's program has to connect back: {@link #CONNECT_TIMEOUT_MS}, or the launch's own
     * {@code connectTimeoutMs} — Scala CLI compiles first, and on a first run downloads the compiler.
     */
    private volatile long connectTimeoutMs = CONNECT_TIMEOUT_MS;

    /** Longest text of a string value shown before it is cut. */
    private static final int MAX_STRING = 500;

    private final ExecutorService jdi = Executors.newSingleThreadExecutor(r -> {
        java.lang.Thread t = new java.lang.Thread(r, "jvm-debug-jdi");
        t.setDaemon(true);
        return t;
    });

    private volatile IDebugProtocolClient client;
    private volatile boolean clientRunsTerminal;
    private volatile VirtualMachine vm;
    private volatile Process process; // only when the adapter started the program itself
    private volatile boolean disposed;
    private volatile boolean terminatedSent;
    private volatile String language = "";
    private volatile List<String> stepFilters = JvmSourceMap.stepFilters("");
    private volatile List<Path> sourceRoots = List.of();

    /** Breakpoints by file, in the order the client set them. JDI thread only. */
    private final Map<Path, List<Bp>> breakpoints = new LinkedHashMap<>();

    /** The files with breakpoints, for the event thread (a copy, replaced whenever {@link #breakpoints} is). */
    private volatile List<Path> breakpointFiles = List.of();

    /** One class-prepare watch per source file name with breakpoints. */
    private final Map<String, ClassPrepareRequest> prepareWatches = new HashMap<>();

    private final AtomicInteger nextBreakpointId = new AtomicInteger(1);
    private ExceptionRequest exceptionRequest;
    private volatile boolean stopOnUncaught;
    private volatile boolean stopOnCaught;

    /** DAP thread ids ↔ JDI threads (DAP ids are ints, JDI's are longs). */
    private final Map<Long, Integer> threadIds = new ConcurrentHashMap<>();

    private final Map<Integer, ThreadReference> threadsById = new ConcurrentHashMap<>();
    private final AtomicInteger nextThreadId = new AtomicInteger(1);

    /** Frame and variable handles, valid while the program is stopped (cleared on every resume). */
    private final Map<Integer, Object> handles = new ConcurrentHashMap<>();

    private final AtomicInteger nextHandle = new AtomicInteger(1);

    /** A breakpoint; {@code refused} says why one with a condition or a log message is not set. */
    private record Bp(int id, int line, List<BreakpointRequest> requests, boolean[] verified, String refused) {}

    /** Said on a breakpoint with a condition, hit count or log message: those need code run in the program. */
    static final String CONDITIONS_UNSUPPORTED =
            "Conditions, hit counts and log messages are not supported by this debugger yet";

    private record FrameHandle(ThreadReference thread, int index) {}

    private record LocalsHandle(FrameHandle frame) {}

    private record ObjectHandle(ObjectReference object) {}

    /** Called by {@link JvmDebugServer} once the client is connected. */
    void connect(IDebugProtocolClient client) {
        this.client = client;
    }

    // --- lifecycle ---------------------------------------------------------------------------------

    @Override
    public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
        clientRunsTerminal = args != null && Boolean.TRUE.equals(args.getSupportsRunInTerminalRequest());
        Capabilities caps = new Capabilities();
        caps.setSupportsConfigurationDoneRequest(true);
        caps.setSupportsTerminateRequest(true);
        caps.setSupportsEvaluateForHovers(true);
        caps.setExceptionBreakpointFilters(new ExceptionBreakpointsFilter[] {
            filter(FILTER_UNCAUGHT, "Uncaught exceptions", true), filter(FILTER_CAUGHT, "Caught exceptions", false)
        });
        return CompletableFuture.completedFuture(caps);
    }

    private static ExceptionBreakpointsFilter filter(String id, String label, boolean on) {
        ExceptionBreakpointsFilter f = new ExceptionBreakpointsFilter();
        f.setFilter(id);
        f.setLabel(label);
        f.setDefault_(on);
        return f;
    }

    @Override
    public CompletableFuture<Void> launch(Map<String, Object> args) {
        return CompletableFuture.runAsync(() -> launchNow(args), jdi);
    }

    private void launchNow(Map<String, Object> args) {
        List<String> argv = strings(args.get("argv"));
        if (argv.isEmpty()) {
            throw failure("Nothing to launch: the request names no command");
        }
        language = string(args.get("language"));
        stepFilters = JvmSourceMap.stepFilters(language);
        if (args.get("connectTimeoutMs") instanceof Number timeout && timeout.longValue() > 0) {
            connectTimeoutMs = timeout.longValue();
        }
        List<Path> roots = new ArrayList<>();
        for (String root : strings(args.get("sourceRoots"))) {
            roots.add(Path.of(root));
        }
        String cwd = string(args.get("cwd"));
        if (!cwd.isBlank()) {
            roots.add(Path.of(cwd));
        }
        sourceRoots = List.copyOf(roots);
        Map<String, String> env = new LinkedHashMap<>(stringMap(args.get("env")));
        ListeningConnector connector = Bootstrap.virtualMachineManager().listeningConnectors().stream()
                .filter(c -> "dt_socket".equals(c.transport().name()))
                .findFirst()
                .orElseThrow(() -> failure("This Java runtime has no socket debugging transport"));
        Map<String, Connector.Argument> connect = connector.defaultArguments();
        setArgument(connect, "localAddress", "127.0.0.1");
        setArgument(connect, "port", "0");
        setArgument(connect, "timeout", String.valueOf(connectTimeoutMs));
        try {
            String address = connector.startListening(connect);
            String port = address.substring(address.lastIndexOf(':') + 1);
            String agent = "-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=127.0.0.1:" + port;
            // A tool that takes JVM options on its command line (Scala CLI's --java-opt) names the argument the
            // agent goes in; the others are handed it in the variable their launcher passes to the JVM.
            String agentArgument = string(args.get("agentArgument"));
            if (!agentArgument.isBlank() && argv.contains(agentArgument)) {
                argv = argv.stream()
                        .map(a -> a.equals(agentArgument) ? agent : a)
                        .toList();
            } else {
                String variable = string(args.get("jvmOptionsVariable"));
                if (variable.isBlank()) {
                    variable = "JAVA_TOOL_OPTIONS";
                }
                String inherited = env.containsKey(variable) ? env.get(variable) : System.getenv(variable);
                env.put(variable, inherited == null || inherited.isBlank() ? agent : inherited.strip() + " " + agent);
            }
            startProgram(argv, cwd, env);
            Process own = process;
            if (own != null) {
                // A program that dies before connecting (a typo in its command) must not leave the launch waiting
                // out the whole timeout: closing the listener ends accept().
                own.onExit().thenRun(() -> {
                    if (vm == null) {
                        stopListening(connector, connect);
                    }
                });
            }
            VirtualMachine machine = connector.accept(connect);
            stopListening(connector, connect);
            vm = machine;
            machine.setDebugTraceMode(VirtualMachine.TRACE_NONE);
            java.lang.Thread loop = new java.lang.Thread(this::eventLoop, "jvm-debug-events");
            loop.setDaemon(true);
            loop.start();
        } catch (IOException | com.sun.jdi.connect.IllegalConnectorArgumentsException e) {
            stopListening(connector, connect);
            Process own = process;
            if (own != null && !own.isAlive()) {
                throw failure("The program exited (code " + own.exitValue() + ") before the debugger could attach");
            }
            throw failure("The program did not connect to the debugger: " + e.getMessage());
        }
        IDebugProtocolClient c = client;
        if (c != null) {
            c.initialized(); // breakpoints, exception filters, then configurationDone — which starts the program
        }
    }

    private static void setArgument(Map<String, Connector.Argument> args, String name, String value) {
        Connector.Argument a = args.get(name);
        if (a != null) {
            a.setValue(value);
        }
    }

    private static void stopListening(ListeningConnector connector, Map<String, Connector.Argument> args) {
        try {
            connector.stopListening(args);
        } catch (IOException | com.sun.jdi.connect.IllegalConnectorArgumentsException | RuntimeException e) {
            LOG.log(Level.FINEST, "listener already closed", e);
        }
    }

    /** Has the client start the program (its console, its input, its exit code); else starts it here. */
    private void startProgram(List<String> argv, String cwd, Map<String, String> env) throws IOException {
        IDebugProtocolClient c = client;
        if (clientRunsTerminal && c != null) {
            RunInTerminalRequestArguments run = new RunInTerminalRequestArguments();
            run.setKind(RunInTerminalRequestArgumentsKind.INTEGRATED);
            run.setTitle("Debug");
            run.setCwd(cwd);
            run.setArgs(argv.toArray(new String[0]));
            run.setEnv(new HashMap<>(env));
            try {
                c.runInTerminal(run).get(connectTimeoutMs, TimeUnit.MILLISECONDS);
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw failure("Could not start the program: " + cause.getMessage());
            } catch (java.util.concurrent.TimeoutException e) {
                throw failure("The client did not start the program");
            } catch (InterruptedException e) {
                java.lang.Thread.currentThread().interrupt();
                throw failure("Interrupted");
            }
        }
        ProcessBuilder pb = new ProcessBuilder(argv);
        if (!cwd.isBlank()) {
            pb.directory(Path.of(cwd).toFile());
        }
        com.editora.process.ProcessRunner.applyUserEnv(pb); // the user's program: their locale, the augmented PATH
        pb.environment().putAll(env);
        Process p = pb.start();
        process = p;
        pump(p.getInputStream(), "stdout");
        pump(p.getErrorStream(), "stderr");
    }

    private void pump(InputStream in, String category) {
        java.lang.Thread t = new java.lang.Thread(
                () -> {
                    byte[] buf = new byte[8192];
                    try (in) {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            output(new String(buf, 0, n, StandardCharsets.UTF_8), category);
                        }
                    } catch (IOException e) {
                        LOG.log(Level.FINEST, "program output closed", e);
                    }
                },
                "jvm-debug-" + category);
        t.setDaemon(true);
        t.start();
    }

    private void output(String text, String category) {
        IDebugProtocolClient c = client;
        if (c != null && !disposed) {
            OutputEventArguments o = new OutputEventArguments();
            o.setCategory(category);
            o.setOutput(text);
            c.output(o);
        }
    }

    @Override
    public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments args) {
        return CompletableFuture.runAsync(
                () -> {
                    VirtualMachine machine = vm;
                    if (machine != null) {
                        machine.resume(); // the VM waited, suspended, since it connected (suspend=y)
                    }
                },
                jdi);
    }

    @Override
    public CompletableFuture<Void> disconnect(DisconnectArguments args) {
        return CompletableFuture.runAsync(this::endProgram, jdi);
    }

    @Override
    public CompletableFuture<Void> terminate(TerminateArguments args) {
        return CompletableFuture.runAsync(this::endProgram, jdi);
    }

    /** Ends the program: the VM exits; a program this adapter started is destroyed if it lingers. */
    private void endProgram() {
        VirtualMachine machine = vm;
        if (machine != null) {
            try {
                machine.exit(1);
            } catch (VMDisconnectedException | IllegalStateException e) {
                LOG.log(Level.FINEST, "already gone", e);
            }
        }
        Process own = process;
        if (own != null) {
            try {
                if (!own.waitFor(2, TimeUnit.SECONDS)) {
                    own.destroyForcibly();
                }
            } catch (InterruptedException e) {
                java.lang.Thread.currentThread().interrupt();
            }
        }
        sendTerminated();
    }

    /** Releases everything; called when the client's connection closes. */
    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        VirtualMachine machine = vm;
        if (machine != null) {
            try {
                machine.exit(1);
            } catch (RuntimeException e) {
                LOG.log(Level.FINEST, "already gone", e);
            }
        }
        Process own = process;
        if (own != null && own.isAlive()) {
            own.destroyForcibly();
        }
        jdi.shutdownNow();
    }

    private void sendTerminated() {
        if (terminatedSent) {
            return;
        }
        terminatedSent = true;
        IDebugProtocolClient c = client;
        if (c == null) {
            return;
        }
        Process own = process;
        if (own != null) {
            try {
                if (own.waitFor(2, TimeUnit.SECONDS)) {
                    ExitedEventArguments exited = new ExitedEventArguments();
                    exited.setExitCode(own.exitValue());
                    c.exited(exited);
                }
            } catch (InterruptedException e) {
                java.lang.Thread.currentThread().interrupt();
            }
        }
        c.terminated(new TerminatedEventArguments());
    }

    // --- events ------------------------------------------------------------------------------------

    private void eventLoop() {
        VirtualMachine machine = vm;
        try {
            while (!disposed) {
                EventSet set = machine.eventQueue().remove();
                boolean resume = true;
                for (Event event : set) {
                    if (!handle(event)) {
                        resume = false;
                    }
                }
                if (resume) {
                    set.resume();
                }
            }
        } catch (VMDisconnectedException e) {
            LOG.log(Level.FINEST, "program ended", e);
        } catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
        } finally {
            sendTerminated();
        }
    }

    /** Handles one event on the event thread; false when the program stays stopped. */
    private boolean handle(Event event) {
        if (event instanceof VMStartEvent) {
            return false; // suspended until configurationDone resumes it
        }
        if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
            return true;
        }
        if (event instanceof ClassPrepareEvent prepared) {
            onJdi(() -> bindPrepared(prepared.referenceType()));
            return true; // resumed once bound: onJdi waits
        }
        if (event instanceof BreakpointEvent hit) {
            stopped(hit.thread(), StoppedEventArgumentsReason.BREAKPOINT, null);
            return false;
        }
        if (event instanceof StepEvent step) {
            onJdi(() -> vm.eventRequestManager().deleteEventRequest(step.request()));
            stopped(step.thread(), StoppedEventArgumentsReason.STEP, null);
            return false;
        }
        if (event instanceof ExceptionEvent thrown) {
            String text = exceptionText(thrown.exception());
            if (shouldStopOn(thrown)) {
                stopped(thrown.thread(), StoppedEventArgumentsReason.EXCEPTION, text);
                return false;
            }
            return true;
        }
        return true;
    }

    /** Runs {@code work} on the JDI thread and waits for it (handles touch state the requests also touch). */
    private void onJdi(Runnable work) {
        try {
            jdi.submit(work).get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            LOG.log(Level.FINE, "JVM debugger work failed", e);
        } catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
        } catch (java.util.concurrent.RejectedExecutionException e) {
            LOG.log(Level.FINEST, "disposed", e);
        }
    }

    private void stopped(ThreadReference thread, String reason, String text) {
        handles.clear();
        IDebugProtocolClient c = client;
        if (c == null) {
            return;
        }
        StoppedEventArguments s = new StoppedEventArguments();
        s.setReason(reason);
        s.setThreadId(threadId(thread));
        s.setAllThreadsStopped(true);
        if (text != null) {
            s.setText(text);
            s.setDescription(text);
        }
        c.stopped(s);
    }

    /**
     * Whether an exception stop is one the user asked for. "Uncaught" means no code of the program catches it:
     * either nothing does, or only the runtime does (Groovy's script runner catches everything to print it, so
     * a JVM-level "uncaught" never fires for a script). "Caught" means a frame of the program catches it after
     * it passed through the program. An exception the runtime throws and catches inside itself — Groovy's
     * dispatch does that constantly — touches no frame of the program and is never a stop.
     */
    private boolean shouldStopOn(ExceptionEvent thrown) {
        if (!stopOnUncaught && !stopOnCaught) {
            return false;
        }
        try {
            List<com.sun.jdi.StackFrame> frames = thrown.thread().frames();
            int firstUser = -1;
            int lastUser = -1;
            for (int i = 0; i < frames.size(); i++) {
                if (isProgram(frames.get(i).location().declaringType())) {
                    if (firstUser < 0) {
                        firstUser = i; // frames are newest first: the newest program frame
                    }
                    lastUser = i; // … and the oldest
                }
            }
            if (lastUser < 0) {
                return false; // the runtime's own business
            }
            Location catcher = thrown.catchLocation();
            int catchFrame = -1;
            if (catcher != null) {
                for (int i = 0; i < frames.size(); i++) {
                    if (frames.get(i).location().method().equals(catcher.method())) {
                        catchFrame = i;
                        break;
                    }
                }
            }
            // It unwinds frames 0..catchFrame. Caught before it reaches the program (the runtime catching its own,
            // above the program) is no stop at all.
            if (catchFrame >= 0 && catchFrame < firstUser) {
                return false;
            }
            if (alreadyReported(thrown.exception())) {
                return false; // the same exception, rethrown or wrapped on its way out: stopped for at its throw
            }
            // Caught by the program is "caught". Escaping every program frame is "uncaught" — and so is a catch by
            // the runtime between two program frames (a method handle's or reflection's), which passes the
            // exception on: Groovy's dynamic dispatch catches and rethrows every exception a call throws.
            boolean caught = catchFrame >= 0 && catchFrame <= lastUser && isProgram(catcher.declaringType());
            boolean stop = caught ? stopOnCaught : stopOnUncaught;
            if (stop) {
                reported.add(thrown.exception().uniqueID());
            }
            return stop;
        } catch (IncompatibleThreadStateException | RuntimeException e) {
            return stopOnUncaught;
        }
    }

    private boolean filtered(String className) {
        for (String pattern : stepFilters) {
            if (pattern.endsWith("*")
                    ? className.startsWith(pattern.substring(0, pattern.length() - 1))
                    : className.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    /** Exceptions already stopped for (ids), so a rethrow or a wrapper of one is not a second stop. */
    private final java.util.Set<Long> reported = ConcurrentHashMap.newKeySet();

    /** Whether {@code exception}, or an exception it wraps (its cause chain), was already stopped for. */
    private boolean alreadyReported(ObjectReference exception) {
        ObjectReference current = exception;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (reported.contains(current.uniqueID())) {
                return true;
            }
            Field cause = current.referenceType().fieldByName("cause");
            Value next = cause == null ? null : current.getValue(cause);
            current = next instanceof ObjectReference o && !o.equals(current) ? o : null;
        }
        return false;
    }

    /** Which loaded classes are the program's own: answered once per class (an exception asks for every frame). */
    private final Map<ReferenceType, Boolean> programTypes = new ConcurrentHashMap<>();

    /**
     * Whether {@code type} is the program's own code: not runtime or library code ({@link #filtered}), and
     * compiled from a source file that is here — one with breakpoints, or under the program's folder. A
     * library on the classpath, or a parser bundled inside Groovy, has no source here and so does not count.
     */
    private boolean isProgram(ReferenceType type) {
        return programTypes.computeIfAbsent(type, t -> {
            if (filtered(t.name())) {
                return false;
            }
            try {
                return JvmSourceMap.resolve(
                                t.sourcePaths(null).isEmpty()
                                        ? null
                                        : t.sourcePaths(null).get(0),
                                breakpointFiles,
                                sourceRoots,
                                Files::isRegularFile,
                                language)
                        != null;
            } catch (AbsentInformationException | RuntimeException e) {
                return false;
            }
        });
    }

    private static String exceptionText(ObjectReference exception) {
        String type = exception.referenceType().name();
        String message = null;
        Field detail = exception.referenceType().fieldByName("detailMessage");
        if (detail != null && exception.getValue(detail) instanceof StringReference s) {
            message = s.value();
        }
        return message == null ? type : type + ": " + message;
    }

    // --- breakpoints -------------------------------------------------------------------------------

    @Override
    public CompletableFuture<SetBreakpointsResponse> setBreakpoints(SetBreakpointsArguments args) {
        return CompletableFuture.supplyAsync(() -> setBreakpointsNow(args), jdi);
    }

    private SetBreakpointsResponse setBreakpointsNow(SetBreakpointsArguments args) {
        Path file = Path.of(args.getSource().getPath()).toAbsolutePath().normalize();
        if (vm == null) {
            throw failure("The program is not running");
        }
        EventRequestManager erm = vm.eventRequestManager();
        for (Bp old : breakpoints.getOrDefault(file, List.of())) {
            for (BreakpointRequest r : old.requests()) {
                erm.deleteEventRequest(r);
            }
        }
        List<Bp> now = new ArrayList<>();
        SourceBreakpoint[] wanted = args.getBreakpoints() == null ? new SourceBreakpoint[0] : args.getBreakpoints();
        for (SourceBreakpoint sb : wanted) {
            // A condition or a log message left out would turn into a plain stop — a logpoint would halt the
            // program. Not set at all, and said so, until this adapter evaluates expressions.
            boolean qualified =
                    notBlank(sb.getCondition()) || notBlank(sb.getHitCondition()) || notBlank(sb.getLogMessage());
            now.add(new Bp(
                    nextBreakpointId.getAndIncrement(),
                    sb.getLine(),
                    new ArrayList<>(),
                    new boolean[1],
                    qualified ? CONDITIONS_UNSUPPORTED : null));
        }
        breakpoints.put(file, now);
        breakpointFiles = List.copyOf(breakpoints.keySet());
        String name = JvmSourceMap.sourceName(file);
        if (!now.isEmpty() && !prepareWatches.containsKey(name)) {
            ClassPrepareRequest watch = erm.createClassPrepareRequest();
            watch.addSourceNameFilter(name);
            watch.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); // bound before the class runs
            watch.enable();
            prepareWatches.put(name, watch);
        }
        for (ReferenceType type : vm.allClasses()) {
            if (compiledFrom(type, file)) {
                bind(type, now);
            }
        }
        Breakpoint[] out = new Breakpoint[now.size()];
        for (int i = 0; i < now.size(); i++) {
            out[i] = describe(now.get(i), args.getSource());
        }
        SetBreakpointsResponse response = new SetBreakpointsResponse();
        response.setBreakpoints(out);
        return response;
    }

    private void bindPrepared(ReferenceType type) {
        for (Map.Entry<Path, List<Bp>> e : breakpoints.entrySet()) {
            if (!compiledFrom(type, e.getKey())) {
                continue;
            }
            for (Bp bp : e.getValue()) {
                boolean was = bp.verified()[0];
                bind(type, List.of(bp));
                if (!was && bp.verified()[0]) {
                    Source source = new Source();
                    source.setPath(e.getKey().toString());
                    BreakpointEventArguments changed = new BreakpointEventArguments();
                    changed.setReason(BreakpointEventArgumentsReason.CHANGED);
                    changed.setBreakpoint(describe(bp, source));
                    IDebugProtocolClient c = client;
                    if (c != null) {
                        c.breakpoint(changed);
                    }
                }
            }
        }
    }

    private void bind(ReferenceType type, List<Bp> bps) {
        EventRequestManager erm = vm.eventRequestManager();
        for (Bp bp : bps) {
            if (bp.refused() != null) {
                continue;
            }
            try {
                for (Location at : type.locationsOfLine(bp.line())) {
                    BreakpointRequest request = erm.createBreakpointRequest(at);
                    request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                    request.enable();
                    bp.requests().add(request);
                    bp.verified()[0] = true;
                }
            } catch (AbsentInformationException | ClassNotPreparedException e) {
                LOG.log(Level.FINEST, "no line table in " + type.name(), e);
            }
        }
    }

    private boolean compiledFrom(ReferenceType type, Path file) {
        try {
            if (!JvmSourceMap.sourceName(file).equals(type.sourceName())) {
                return false;
            }
            for (String path : type.sourcePaths(null)) {
                if (JvmSourceMap.compiledFrom(path, file, language)) {
                    return true;
                }
            }
            return false;
        } catch (AbsentInformationException e) {
            return false;
        }
    }

    private static Breakpoint describe(Bp bp, Source source) {
        Breakpoint b = new Breakpoint();
        b.setId(bp.id());
        b.setLine(bp.line());
        b.setVerified(bp.verified()[0]);
        b.setSource(source);
        if (bp.refused() != null) {
            b.setMessage(bp.refused());
        }
        return b;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @Override
    public CompletableFuture<SetExceptionBreakpointsResponse> setExceptionBreakpoints(
            SetExceptionBreakpointsArguments args) {
        return CompletableFuture.supplyAsync(
                () -> {
                    List<String> filters = args.getFilters() == null ? List.of() : List.of(args.getFilters());
                    stopOnUncaught = filters.contains(FILTER_UNCAUGHT);
                    stopOnCaught = filters.contains(FILTER_CAUGHT);
                    EventRequestManager erm = vm.eventRequestManager();
                    if (exceptionRequest != null) {
                        erm.deleteEventRequest(exceptionRequest);
                        exceptionRequest = null;
                    }
                    if (stopOnUncaught || stopOnCaught) {
                        // Both kinds from JDI: which one an exception is depends on who catches it (shouldStopOn).
                        exceptionRequest = erm.createExceptionRequest(null, true, true);
                        exceptionRequest.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                        exceptionRequest.enable();
                    }
                    return new SetExceptionBreakpointsResponse();
                },
                jdi);
    }

    // --- execution control -------------------------------------------------------------------------

    @Override
    public CompletableFuture<ContinueResponse> continue_(ContinueArguments args) {
        return CompletableFuture.supplyAsync(
                () -> {
                    resume();
                    ContinueResponse r = new ContinueResponse();
                    r.setAllThreadsContinued(true);
                    return r;
                },
                jdi);
    }

    @Override
    public CompletableFuture<Void> next(NextArguments args) {
        return CompletableFuture.runAsync(() -> step(args.getThreadId(), StepRequest.STEP_OVER), jdi);
    }

    @Override
    public CompletableFuture<Void> stepIn(StepInArguments args) {
        return CompletableFuture.runAsync(() -> step(args.getThreadId(), StepRequest.STEP_INTO), jdi);
    }

    @Override
    public CompletableFuture<Void> stepOut(StepOutArguments args) {
        return CompletableFuture.runAsync(() -> step(args.getThreadId(), StepRequest.STEP_OUT), jdi);
    }

    @Override
    public CompletableFuture<Void> pause(PauseArguments args) {
        return CompletableFuture.runAsync(
                () -> {
                    vm.suspend();
                    ThreadReference thread = threadsById.get(args.getThreadId());
                    if (thread == null) {
                        thread = vm.allThreads().stream()
                                .filter(t -> "main".equals(t.name()))
                                .findFirst()
                                .orElse(
                                        vm.allThreads().isEmpty()
                                                ? null
                                                : vm.allThreads().get(0));
                    }
                    if (thread != null) {
                        stopped(thread, StoppedEventArgumentsReason.PAUSE, null);
                    }
                },
                jdi);
    }

    private void step(int threadId, int depth) {
        ThreadReference thread = threadsById.get(threadId);
        if (thread == null) {
            throw failure("No such thread");
        }
        EventRequestManager erm = vm.eventRequestManager();
        for (StepRequest old : new ArrayList<>(erm.stepRequests())) {
            if (old.thread().equals(thread)) {
                erm.deleteEventRequest(old);
            }
        }
        StepRequest step = erm.createStepRequest(thread, StepRequest.STEP_LINE, depth);
        for (String pattern : stepFilters) {
            step.addClassExclusionFilter(pattern);
        }
        step.addCountFilter(1);
        step.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        step.enable();
        resume();
    }

    private void resume() {
        handles.clear();
        vm.resume();
    }

    // --- inspection --------------------------------------------------------------------------------

    @Override
    public CompletableFuture<ThreadsResponse> threads() {
        return CompletableFuture.supplyAsync(
                () -> {
                    List<Thread> out = new ArrayList<>();
                    VirtualMachine machine = vm;
                    if (machine != null) {
                        for (ThreadReference t : machine.allThreads()) {
                            Thread d = new Thread();
                            d.setId(threadId(t));
                            d.setName(t.name());
                            out.add(d);
                        }
                    }
                    ThreadsResponse r = new ThreadsResponse();
                    r.setThreads(out.toArray(new Thread[0]));
                    return r;
                },
                jdi);
    }

    private int threadId(ThreadReference thread) {
        int id = threadIds.computeIfAbsent(thread.uniqueID(), k -> nextThreadId.getAndIncrement());
        threadsById.put(id, thread);
        return id;
    }

    @Override
    public CompletableFuture<StackTraceResponse> stackTrace(StackTraceArguments args) {
        return CompletableFuture.supplyAsync(() -> stackTraceNow(args), jdi);
    }

    private StackTraceResponse stackTraceNow(StackTraceArguments args) {
        ThreadReference thread = threadsById.get(args.getThreadId());
        StackTraceResponse r = new StackTraceResponse();
        if (thread == null) {
            r.setStackFrames(new StackFrame[0]);
            return r;
        }
        try {
            int total = thread.frameCount();
            int start = args.getStartFrame() == null ? 0 : args.getStartFrame();
            int levels = args.getLevels() == null || args.getLevels() <= 0 ? total : args.getLevels();
            int end = Math.min(total, start + levels);
            List<Path> known = breakpointFiles;
            List<StackFrame> out = new ArrayList<>();
            for (int i = start; i < end; i++) {
                Location at = thread.frame(i).location();
                StackFrame f = new StackFrame();
                f.setId(handle(new FrameHandle(thread, i)));
                f.setName(JvmSourceMap.frameName(
                        at.declaringType().name(), at.method().name(), language));
                f.setLine(Math.max(0, at.lineNumber()));
                f.setColumn(1);
                Path file = sourceOf(at, known);
                if (file != null) {
                    Source s = new Source();
                    s.setPath(file.toString());
                    s.setName(file.getFileName().toString());
                    f.setSource(s);
                }
                if (filtered(at.declaringType().name())) {
                    f.setPresentationHint(StackFramePresentationHint.SUBTLE);
                }
                out.add(f);
            }
            r.setStackFrames(out.toArray(new StackFrame[0]));
            r.setTotalFrames(total);
            return r;
        } catch (IncompatibleThreadStateException e) {
            throw failure("The thread is not stopped");
        }
    }

    private Path sourceOf(Location at, List<Path> known) {
        try {
            return JvmSourceMap.resolve(at.sourcePath(null), known, sourceRoots, Files::isRegularFile, language);
        } catch (AbsentInformationException e) {
            return null;
        }
    }

    @Override
    public CompletableFuture<ScopesResponse> scopes(ScopesArguments args) {
        return CompletableFuture.supplyAsync(
                () -> {
                    ScopesResponse r = new ScopesResponse();
                    if (!(handles.get(args.getFrameId()) instanceof FrameHandle frame)) {
                        r.setScopes(new Scope[0]);
                        return r;
                    }
                    Scope locals = new Scope();
                    locals.setName("Locals");
                    locals.setPresentationHint("locals");
                    locals.setVariablesReference(handle(new LocalsHandle(frame)));
                    r.setScopes(new Scope[] {locals});
                    return r;
                },
                jdi);
    }

    @Override
    public CompletableFuture<VariablesResponse> variables(VariablesArguments args) {
        return CompletableFuture.supplyAsync(() -> variablesNow(args), jdi);
    }

    private VariablesResponse variablesNow(VariablesArguments args) {
        Object h = handles.get(args.getVariablesReference());
        List<Variable> out = new ArrayList<>();
        try {
            if (h instanceof LocalsHandle locals) {
                com.sun.jdi.StackFrame frame =
                        locals.frame().thread().frame(locals.frame().index());
                ObjectReference self = frame.thisObject();
                if (self != null) {
                    out.add(variable("this", self));
                }
                try {
                    for (LocalVariable v : frame.visibleVariables()) {
                        out.add(variable(v.name(), frame.getValue(v)));
                    }
                } catch (AbsentInformationException e) {
                    List<Value> values = frame.getArgumentValues();
                    for (int i = 0; i < values.size(); i++) {
                        out.add(variable("arg" + i, values.get(i)));
                    }
                }
            } else if (h instanceof ObjectHandle object) {
                if (object.object() instanceof ArrayReference array) {
                    int start = args.getStart() == null ? 0 : args.getStart();
                    int count = args.getCount() == null || args.getCount() <= 0
                            ? array.length() - start
                            : Math.min(args.getCount(), array.length() - start);
                    List<Value> values = count <= 0 ? List.of() : array.getValues(start, count);
                    for (int i = 0; i < values.size(); i++) {
                        out.add(variable("[" + (start + i) + "]", values.get(i)));
                    }
                } else {
                    ObjectReference o = object.object();
                    for (Field field : o.referenceType().allFields()) {
                        if (!field.isStatic() && !JvmSourceMap.hiddenField(field.name(), language)) {
                            out.add(variable(field.name(), o.getValue(field)));
                        }
                    }
                }
            }
        } catch (IncompatibleThreadStateException | com.sun.jdi.InvalidStackFrameException e) {
            throw failure("The program is running");
        }
        VariablesResponse r = new VariablesResponse();
        r.setVariables(out.toArray(new Variable[0]));
        return r;
    }

    private Variable variable(String name, Value value) {
        Variable v = new Variable();
        v.setName(name);
        v.setValue(display(value));
        v.setType(value == null ? null : value.type().name());
        v.setVariablesReference(expandable(value) ? handle(new ObjectHandle((ObjectReference) value)) : 0);
        if (value instanceof ArrayReference array) {
            v.setIndexedVariables(array.length());
        }
        return v;
    }

    private static boolean expandable(Value value) {
        if (value instanceof ArrayReference array) {
            return array.length() > 0;
        }
        return value instanceof ObjectReference && !(value instanceof StringReference) && boxed(value) == null;
    }

    /** What a value shows: a string quoted, a boxed number as its number, an object as its type and id. */
    static String display(Value value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof PrimitiveValue p) {
            return p.toString();
        }
        if (value instanceof StringReference s) {
            String text = s.value();
            if (text.length() > MAX_STRING) {
                text = text.substring(0, MAX_STRING) + "…";
            }
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }
        String boxed = boxed(value);
        if (boxed != null) {
            return boxed;
        }
        if (value instanceof ArrayReference array) {
            String type = array.type().name();
            int bracket = type.indexOf('[');
            return (bracket > 0 ? type.substring(0, bracket) : type) + "[" + array.length() + "]";
        }
        ObjectReference o = (ObjectReference) value;
        String type = o.referenceType().name();
        int dot = type.lastIndexOf('.');
        return (dot >= 0 ? type.substring(dot + 1) : type) + " (id=" + o.uniqueID() + ")";
    }

    /** A boxed primitive's value ({@code Integer 5} → {@code 5}), read from its field; null otherwise. */
    private static String boxed(Value value) {
        if (!(value instanceof ObjectReference o)) {
            return null;
        }
        String type = o.referenceType().name();
        if (!type.startsWith("java.lang.")) {
            return null;
        }
        switch (type) {
            case "java.lang.Integer",
                    "java.lang.Long",
                    "java.lang.Short",
                    "java.lang.Byte",
                    "java.lang.Double",
                    "java.lang.Float",
                    "java.lang.Boolean",
                    "java.lang.Character" -> {
                Field field = o.referenceType().fieldByName("value");
                Value v = field == null ? null : o.getValue(field);
                return v == null ? null : v.toString();
            }
            default -> {
                return null;
            }
        }
    }

    @Override
    public CompletableFuture<EvaluateResponse> evaluate(EvaluateArguments args) {
        return CompletableFuture.supplyAsync(() -> evaluateNow(args), jdi);
    }

    /**
     * A variable, {@code this}, or a field path from one ({@code order.lines.size}); anything else is refused
     * rather than run, so a hover or a watch never executes code in the program.
     */
    private EvaluateResponse evaluateNow(EvaluateArguments args) {
        String expression =
                args.getExpression() == null ? "" : args.getExpression().strip();
        if (!expression.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*")) {
            throw failure("Only variables and their fields can be evaluated here");
        }
        Object h = args.getFrameId() == null ? null : handles.get(args.getFrameId());
        if (!(h instanceof FrameHandle frameHandle)) {
            throw failure("No frame to evaluate in");
        }
        try {
            com.sun.jdi.StackFrame frame = frameHandle.thread().frame(frameHandle.index());
            String[] parts = expression.split("\\.");
            Value value;
            int i = 1;
            if ("this".equals(parts[0])) {
                value = frame.thisObject();
            } else {
                LocalVariable local = null;
                try {
                    local = frame.visibleVariableByName(parts[0]);
                } catch (AbsentInformationException e) {
                    LOG.log(Level.FINEST, "no local variable table", e);
                }
                if (local != null) {
                    value = frame.getValue(local);
                } else if (frame.thisObject() != null) {
                    value = field(frame.thisObject(), parts[0]);
                    if (value == null && frame.thisObject().referenceType().fieldByName(parts[0]) == null) {
                        throw failure("'" + parts[0] + "' is not a variable here");
                    }
                } else {
                    throw failure("'" + parts[0] + "' is not a variable here");
                }
            }
            for (; i < parts.length; i++) {
                if (!(value instanceof ObjectReference o)) {
                    throw failure("'" + parts[i - 1] + "' has no fields");
                }
                if (o.referenceType().fieldByName(parts[i]) == null) {
                    throw failure("'" + parts[i] + "' is not a field of "
                            + o.referenceType().name());
                }
                value = field(o, parts[i]);
            }
            Variable v = variable(expression, value);
            EvaluateResponse r = new EvaluateResponse();
            r.setResult(v.getValue());
            r.setType(v.getType());
            r.setVariablesReference(v.getVariablesReference());
            return r;
        } catch (IncompatibleThreadStateException | com.sun.jdi.InvalidStackFrameException e) {
            throw failure("The program is running");
        }
    }

    private static Value field(ObjectReference o, String name) {
        Field f = o.referenceType().fieldByName(name);
        return f == null ? null : o.getValue(f);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private int handle(Object target) {
        int id = nextHandle.getAndIncrement();
        handles.put(id, target);
        return id;
    }

    private static RuntimeException failure(String message) {
        return new org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                new org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                        org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.RequestFailed, message, null));
    }

    private static String string(Object o) {
        if (o instanceof com.google.gson.JsonPrimitive p) {
            return p.getAsString();
        }
        return o == null || o instanceof com.google.gson.JsonNull ? "" : o.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                out.add(
                        item == null
                                ? ""
                                : item instanceof com.google.gson.JsonPrimitive p ? p.getAsString() : item.toString());
            }
        } else if (o instanceof com.google.gson.JsonArray array) {
            array.forEach(e -> out.add(e.isJsonNull() ? "" : e.getAsString()));
        }
        return out;
    }

    private static Map<String, String> stringMap(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> map) {
            map.forEach((k, v) -> out.put(
                    String.valueOf(k),
                    v == null ? null : v instanceof com.google.gson.JsonPrimitive p ? p.getAsString() : v.toString()));
        } else if (o instanceof com.google.gson.JsonObject object) {
            object.entrySet()
                    .forEach(e -> out.put(
                            e.getKey(),
                            e.getValue().isJsonNull() ? null : e.getValue().getAsString()));
        }
        return out;
    }
}
