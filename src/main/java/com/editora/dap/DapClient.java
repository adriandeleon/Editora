package com.editora.dap;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.process.ProcessRegistry;
import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.BreakpointEventArguments;
import org.eclipse.lsp4j.debug.BreakpointEventArgumentsReason;
import org.eclipse.lsp4j.debug.BreakpointNotVerifiedReason;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.ContinuedEventArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.ExceptionDetails;
import org.eclipse.lsp4j.debug.ExceptionInfoArguments;
import org.eclipse.lsp4j.debug.ExceptionInfoResponse;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.NextArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.ScopesArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetVariableArguments;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StepInArguments;
import org.eclipse.lsp4j.debug.StepOutArguments;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.TerminatedEventArguments;
import org.eclipse.lsp4j.debug.Variable;
import org.eclipse.lsp4j.debug.VariablesArguments;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;

/**
 * One Debug Adapter Protocol session over a TCP socket to the Microsoft java-debug adapter (started inside
 * jdtls via {@code vscode.java.startDebugSession}, which returns the port). Mirrors {@code LanguageServerSession}:
 * it implements {@link IDebugProtocolClient} to receive events, drives the DAP handshake
 * ({@code initialize} → on the {@code initialized} event send {@code setBreakpoints}/{@code setExceptionBreakpoints}
 * → {@code configurationDone}), and exposes the control + inspection requests.
 *
 * <p>Pure of JavaFX: event callbacks fire on the launcher's reader thread, so the {@link Host}
 * implementation ({@link DapManager}) marshals to the FX thread. Requests return raw futures.
 *
 * <p><b>Multi-session adapters.</b> vscode-js-debug never debugs the program on the connection that
 * launched it. That first ("root") session only starts the launcher; for every debuggee it then sends the
 * reverse request {@code startDebugging} and waits for a <em>second</em> connection to the same port whose
 * {@code launch}/{@code attach} carries the configuration it supplied (including {@code __pendingTargetId}).
 * Breakpoints, stops, stepping and output all live on that child session. A root client therefore owns
 * its children: it opens one per {@code startDebugging}, gives each the current breakpoints and exception
 * filters, forwards their events to the one {@link Host}, addresses inspection and control requests to the
 * child that last stopped ({@link #target()}), and disposes them with itself. To the manager it remains a
 * single session.
 */
public final class DapClient implements IDebugProtocolClient {

    private static final Logger LOG = Logger.getLogger(DapClient.class.getName());
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Event sink (implemented by {@link DapManager}); calls arrive on the launcher's reader thread. */
    public interface Host {
        void onStopped(int threadId, String reason);

        /**
         * A stop, with whether the adapter suspended every thread or only {@code threadId}. java-debug
         * suspends per thread, so several threads can be stopped at once and each must be resumed.
         */
        default void onStopped(int threadId, String reason, boolean allThreadsStopped) {
            onStopped(threadId, reason);
        }

        void onContinued();

        /** A {@code continued} event for {@code threadId}, or for every thread. */
        default void onContinued(int threadId, boolean allThreadsContinued) {
            onContinued();
        }

        void onOutput(String text, String category);

        void onTerminated();

        void onError(String message);

        default void onTransportClosed(Throwable error) {
            onTerminated();
        }

        /**
         * What the adapter says about {@code file}'s breakpoints. {@code whole} is a {@code setBreakpoints}
         * answer, standing for every breakpoint of the file (one not listed has no answer yet); otherwise
         * the statuses are {@code breakpoint} events, each changing one breakpoint and leaving the rest.
         */
        default void onBreakpointStatus(Path file, List<DapModels.BreakpointStatus> statuses, boolean whole) {}

        /** Something the adapter wants the user told that is not program output (java-debug's
         *  {@code usernotification}: a breakpoint condition or log message it could not evaluate). */
        default void onNotice(String message, boolean error) {}

        /**
         * The adapter asks the client to start the debuggee itself ({@code runInTerminal}): {@code argv} in
         * {@code cwd} with {@code env} on top of the client's environment (a null value unsets a variable).
         * Completes with the process id. Only asked of a session that {@link #setRunsDebuggee offered} it.
         */
        default CompletableFuture<Long> onRunInTerminal(String cwd, List<String> argv, Map<String, String> env) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("runInTerminal"));
        }
    }

    private final Host host;
    /** The root session this client is a child of, or null when it is the root (or a plain session). */
    private final DapClient root;
    /** Root only: the live child sessions, in the order the adapter asked for them. */
    private final List<DapClient> children = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Root only: the child inspection/control requests are addressed to — the one that last stopped. */
    private volatile DapClient focus;
    /** The port and adapter id of a socket transport, so a child can join the same adapter. */
    private volatile int port = -1;

    private volatile String adapterId;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dap-session");
        t.setDaemon(true);
        return t;
    });

    private Socket socket;
    /** The adapter subprocess for stdio transports (debugpy); null for socket transports. Killed on dispose. */
    private Process adapterProcess;

    private IDebugProtocolServer server;
    private volatile boolean disposed;
    /** The breakpoints to install when the adapter signals {@code initialized} (snapshot taken on the FX
     *  thread at session start, so this never reads UI state off-thread). */
    private volatile List<DapModels.FileBreakpoints> initialBreakpoints = List.of();
    /** Exception-breakpoint filter ids (e.g. {@code uncaught}/{@code caught}). */
    private volatile List<String> exceptionFilters = List.of();
    /** True once the adapter's {@code initialized} event installed the configuration (breakpoints +
     *  exception filters). Before that, a filter change only updates the field — {@link #initialized}
     *  will read it. After it, a change must be sent on the wire itself. */
    private volatile boolean configured;

    /** Whether this session offers {@code runInTerminal} — see {@link #setRunsDebuggee}. */
    private volatile boolean runsDebuggee;

    public DapClient(Host host) {
        this.host = host;
        this.root = null;
    }

    /**
     * Offers the adapter {@code runInTerminal} (call before {@link #connect}): a launch with
     * {@code console: integratedTerminal} then has the {@link Host} start the debuggee, which is what gives
     * it a standard input. The adapter sends no {@code output} events for such a process and does not end
     * it on {@code disconnect} (measured against java-debug 0.53.2) — both become the host's job.
     */
    public void setRunsDebuggee(boolean runsDebuggee) {
        this.runsDebuggee = runsDebuggee;
    }

    /** A child session of {@code root}; its events are folded into the root's host. */
    private DapClient(DapClient root) {
        this.root = root;
        this.host = root.childHost(this);
    }

    public void setBreakpoints(List<DapModels.FileBreakpoints> breakpoints) {
        this.initialBreakpoints = breakpoints == null ? List.of() : List.copyOf(breakpoints);
    }

    /**
     * The session a request about the debuggee should go to: the child that last stopped, else the first
     * live child, else this client itself (every adapter that debugs on its first connection).
     */
    private DapClient target() {
        DapClient f = focus;
        if (f != null && !f.disposed) {
            return f;
        }
        for (DapClient child : children) {
            if (!child.disposed) {
                return child;
            }
        }
        return this;
    }

    /** Live child sessions (test/diagnostic read). */
    int childCount() {
        return children.size();
    }

    /**
     * Sets the exception-breakpoint filters. Before the session starts these are installed by
     * {@link #initialized}; once it is running they must also go on the wire right away, because
     * {@code initialized} has already fired and will never read the field again — otherwise toggling
     * exception breakpoints mid-session silently does nothing while the UI reports it took effect.
     * Fire-and-forget, mirroring {@link #sendSetBreakpoints}.
     */
    public void setExceptionFilters(List<String> filters) {
        this.exceptionFilters = filters == null ? List.of() : List.copyOf(filters);
        for (DapClient child : children) {
            child.setExceptionFilters(filters); // each session holds its own exception configuration
        }
        if (server != null && configured) {
            try {
                SetExceptionBreakpointsArguments ex = new SetExceptionBreakpointsArguments();
                ex.setFilters(this.exceptionFilters.toArray(new String[0]));
                ignore(timed(server.setExceptionBreakpoints(ex)));
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "live setExceptionBreakpoints failed", e);
            }
        }
    }

    /** The adapter's {@code initialize} capabilities (kept for feature gating, e.g. Jump to Line). */
    private volatile Capabilities capabilities;

    /** Whether the adapter supports {@code gotoTargets}/{@code goto} (debugpy does; java-debug and
     *  vscode-js-debug currently do not). */
    public boolean supportsGotoTargets() {
        Capabilities caps = target().capabilities;
        return caps != null && Boolean.TRUE.equals(caps.getSupportsGotoTargetsRequest());
    }

    /**
     * Opens the socket to {@code 127.0.0.1:port} (with a few short retries — the adapter may need a moment
     * to start listening), wires the DAP launcher, and sends {@code initialize} with {@code adapterId}
     * (e.g. {@code "java"}/{@code "pwa-node"}). The returned future completes when the adapter's
     * capabilities arrive (the caller then sends {@code launch}/{@code attach}).
     *
     * <p>Used for socket transports: the jdtls-started java adapter and the {@code vscode-js-debug}
     * {@code dapDebugServer.js} (spawned by {@link DapManager}, which sets {@link #adapterProcess} via
     * {@link #setAdapterProcess} so it is killed on {@link #dispose}).
     */
    public CompletableFuture<Capabilities> connect(int port, String adapterId) {
        try {
            if (disposed) {
                return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("disposed"));
            }
            Socket opened = openWithRetry(port, 50);
            if (disposed) {
                opened.close();
                return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("disposed"));
            }
            socket = opened;
            if (disposed) {
                socket = null;
                opened.close();
                return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("disposed"));
            }
            this.port = port;
            this.adapterId = adapterId;
            Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(
                    this, socket.getInputStream(), orderedWriter(socket.getOutputStream()), executor, c -> c);
            server = launcher.getRemoteProxy();
            watchTransport(launcher.startListening());
            // A socket adapter can be joined by a second connection, so child sessions are possible here.
            return timed(server.initialize(initArgs(adapterId, true, runsDebuggee)))
                    .thenApply(c -> {
                        this.capabilities = c;
                        return c;
                    });
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to connect to debug adapter on port " + port, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Wires the DAP launcher to a subprocess's stdin/stdout (the stdio transport used by debugpy:
     * {@code python -m debugpy.adapter}) and sends {@code initialize} with {@code adapterId} (e.g.
     * {@code "python"}). The process is killed (with its descendants) on {@link #dispose}; its stderr
     * must be {@code Redirect.DISCARD}ed by the caller (an undrained PIPE deadlocks, like the LSP servers).
     */
    public CompletableFuture<Capabilities> connectStdio(Process process, String adapterId) {
        try {
            if (disposed) {
                ProcessRegistry.killTree(process);
                return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("disposed"));
            }
            this.adapterProcess = process;
            if (disposed) {
                this.adapterProcess = null;
                ProcessRegistry.killTree(process);
                return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("disposed"));
            }
            ProcessRegistry.track(process); // reaped on JVM exit / next-run startup if we die without dispose()
            watchProcess(process);
            Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(
                    this, process.getInputStream(), orderedWriter(process.getOutputStream()), executor, c -> c);
            server = launcher.getRemoteProxy();
            watchTransport(launcher.startListening());
            return timed(server.initialize(initArgs(adapterId, false, false))).thenApply(c -> {
                this.capabilities = c;
                return c;
            });
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to wire stdio debug adapter", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /** The ordered writer to the adapter; null until connected. */
    private volatile com.editora.lsp.AsyncPipeWriter writer;

    /** How long {@link #dispose} lets a queued {@code disconnect} reach the adapter before closing. */
    private static final long DISCONNECT_FLUSH_MILLIS = 250;

    /**
     * What lsp4j writes to instead of the socket or pipe itself. lsp4j writes on the calling thread, and
     * Resume, Step, evaluate and the variables requests are issued from the FX thread: an adapter that has
     * stopped reading (paused, wedged, a full socket buffer) would hold the editor in that write. Requests
     * are queued in call order and one thread does the writing; an adapter that never reads again has its
     * transport cut once the backlog limit is reached, which ends the session like any lost connection.
     */
    private java.io.OutputStream orderedWriter(java.io.OutputStream transport) {
        var out = new com.editora.lsp.AsyncPipeWriter(transport, "dap-writer", this::cutTransport);
        writer = out;
        return out;
    }

    private void cutTransport() {
        try {
            Socket s = socket;
            if (s != null) {
                s.close(); // also releases the writer thread blocked in the socket write
            }
        } catch (Exception ignored) {
            // best effort
        }
        ProcessRegistry.killTree(adapterProcess);
    }

    /** Records the adapter subprocess (socket transports that spawn their own server, e.g. js-debug) so
     *  {@link #dispose} kills it and its descendants. */
    public void setAdapterProcess(Process process) {
        this.adapterProcess = process;
        if (disposed) {
            this.adapterProcess = null;
            ProcessRegistry.killTree(process);
            return;
        }
        ProcessRegistry.track(process); // reaped on JVM exit / next-run startup if we die without dispose()
        watchProcess(process);
    }

    /**
     * The loopback addresses an adapter may be listening on. vscode-js-debug binds {@code localhost}, which
     * on many systems resolves to {@code ::1} first — so an adapter that had started correctly was reported
     * as unreachable when only the IPv4 loopback was tried.
     */
    private static final String[] LOOPBACKS = {"127.0.0.1", "::1"};

    private static Socket openWithRetry(int port, int tries) throws InterruptedException {
        for (int i = 0; i < tries; i++) {
            for (String loopback : LOOPBACKS) {
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(loopback, port), 200);
                    return s;
                } catch (Exception e) {
                    try {
                        s.close();
                    } catch (java.io.IOException ignored) {
                        // nothing was opened
                    }
                }
            }
            Thread.sleep(40);
        }
        throw new IllegalStateException("could not connect to the debug adapter on port " + port);
    }

    /**
     * {@code startDebugging}: whether the adapter may ask for child sessions. Only declared for socket
     * transports, where a child can connect to the same port; a stdio adapter has no second connection to
     * offer, and declaring it would make debugpy route subprocesses through a request we could not serve.
     */
    private static InitializeRequestArguments initArgs(
            String adapterId, boolean startDebugging, boolean runInTerminal) {
        InitializeRequestArguments a = new InitializeRequestArguments();
        a.setSupportsStartDebuggingRequest(startDebugging);
        a.setClientID("editora");
        a.setClientName("Editora");
        a.setAdapterID(adapterId == null || adapterId.isBlank() ? "java" : adapterId);
        a.setPathFormat("path");
        a.setLinesStartAt1(true);
        a.setColumnsStartAt1(true);
        a.setSupportsRunInTerminalRequest(runInTerminal);
        // Lets the adapter report indexedVariables/namedVariables, so a huge array is fetched page by page.
        a.setSupportsVariablePaging(true);
        return a;
    }

    private void watchTransport(Future<Void> listening) {
        executor.submit(() -> {
            Throwable failure = null;
            try {
                listening.get();
            } catch (java.util.concurrent.ExecutionException e) {
                failure = e.getCause();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                failure = e;
            }
            if (!disposed) {
                host.onTransportClosed(failure);
            }
        });
    }

    private void watchProcess(Process process) {
        if (process != null) {
            process.onExit().thenRun(() -> {
                if (!disposed) {
                    host.onTransportClosed(null);
                }
            });
        }
    }

    private static <T> CompletableFuture<T> timed(CompletableFuture<T> future) {
        return withTimeout(future, REQUEST_TIMEOUT);
    }

    static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future, Duration timeout) {
        return future.orTimeout(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
    }

    /** Sends the {@code launch} request (body from {@link LaunchConfig#launch}). */
    public CompletableFuture<Void> launch(Map<String, Object> args) {
        return timed(server.launch(args));
    }

    /** Sends the {@code attach} request (body from {@link LaunchConfig#attach}). */
    public CompletableFuture<Void> attach(Map<String, Object> args) {
        return timed(server.attach(args));
    }

    // --- IDebugProtocolClient events (launcher reader thread) -----------------------------------

    @Override
    public void initialized() {
        // The adapter is ready for configuration: install breakpoints + exception filters, then signal
        // configurationDone so it proceeds with launch/attach.
        //
        // CRITICAL: this runs on the DAP *reader* thread. We must NOT block here — a .join() would
        // deadlock, since the response we'd wait for is delivered by this same thread. lsp4j serializes
        // outgoing messages, so firing the requests in order still puts setBreakpoints on the wire before
        // configurationDone; the adapter processes them in order. We don't need the responses for the
        // handshake.
        try {
            for (DapModels.FileBreakpoints fb : initialBreakpoints) {
                sendSetBreakpoints(fb);
            }
            if (!exceptionFilters.isEmpty()) {
                SetExceptionBreakpointsArguments ex = new SetExceptionBreakpointsArguments();
                ex.setFilters(exceptionFilters.toArray(new String[0]));
                ignore(timed(server.setExceptionBreakpoints(ex)));
            }
            ignore(timed(server.configurationDone(new ConfigurationDoneArguments())));
            configured = true; // later filter/breakpoint changes must now go on the wire themselves
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "configuration phase failed", e);
        }
    }

    @Override
    public void stopped(StoppedEventArguments args) {
        Integer tid = args.getThreadId();
        int threadId = tid == null ? 0 : tid;
        // Kept for exceptionInfo(): the only description of an exception stop some adapters ever give.
        String text = args.getText() != null && !args.getText().isBlank() ? args.getText() : args.getDescription();
        if (text == null || text.isBlank()) {
            stopTexts.remove(threadId);
        } else {
            stopTexts.put(threadId, text.strip());
        }
        host.onStopped(threadId, args.getReason(), Boolean.TRUE.equals(args.getAllThreadsStopped()));
    }

    /**
     * The adapter changed its mind about a breakpoint after answering {@code setBreakpoints} — how java-debug
     * reports that a breakpoint was bound once its class loaded. The event names the breakpoint by id only.
     */
    @Override
    public void breakpoint(BreakpointEventArguments args) {
        Breakpoint b = args == null ? null : args.getBreakpoint();
        if (b == null || disposed || BreakpointEventArgumentsReason.REMOVED.equals(args.getReason())) {
            return;
        }
        BreakpointKey key = b.getId() == null ? null : breakpointIds.get(b.getId());
        if (key == null) {
            Path file = sourcePath(b.getSource());
            if (file == null || b.getLine() == null || b.getLine() < 1) {
                return; // nothing says which of the breakpoints this is
            }
            key = new BreakpointKey(file, b.getLine() - 1);
        }
        host.onBreakpointStatus(key.file(), List.of(status(key.line(), b)), false);
    }

    /** The body of java-debug's {@code usernotification} event (not part of the protocol). */
    public static final class UserNotification {
        String notificationType;
        String message;
    }

    /** java-debug's way of saying a breakpoint condition or a logpoint message failed to evaluate. */
    @JsonNotification("usernotification")
    public void userNotification(UserNotification args) {
        if (args != null && args.message != null && !args.message.isBlank() && !disposed) {
            host.onNotice(args.message.strip(), "ERROR".equalsIgnoreCase(args.notificationType));
        }
    }

    @Override
    public void continued(ContinuedEventArguments args) {
        // "allThreadsContinued" is optional on the event and means "only this thread" when it is missing.
        host.onContinued(args.getThreadId(), Boolean.TRUE.equals(args.getAllThreadsContinued()));
    }

    @Override
    public void output(OutputEventArguments args) {
        if ("telemetry".equals(args.getCategory())) {
            return; // the protocol's "send to telemetry instead of showing it to the user" — not console text
        }
        host.onOutput(args.getOutput(), args.getCategory());
    }

    /** How long a root session that has terminated waits for its children to deliver their final events. */
    private static final long CHILD_DRAIN_MILLIS = 1_000;

    @Override
    public void terminated(TerminatedEventArguments args) {
        if (root == null && !children.isEmpty()) {
            // The debuggee's last output and its own `terminated` travel on the child's connection, and
            // nothing orders the two sockets: ending here would dispose the child with that output still
            // unread (measured against js-debug: the root's event overtakes the child's). The session ends
            // when the last child does (childEnded); this is only the fallback for a child that never says so.
            CompletableFuture.delayedExecutor(CHILD_DRAIN_MILLIS, TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        if (!disposed) {
                            host.onTerminated();
                        }
                    });
            return;
        }
        host.onTerminated();
    }

    @Override
    public void exited(org.eclipse.lsp4j.debug.ExitedEventArguments args) {
        // The debuggee process exited; the session ends on the following `terminated` event.
    }

    /**
     * The adapter asks the client to start the debuggee (see {@link #setRunsDebuggee}). A session that did
     * not offer this — and a request naming no command — gets lsp4j's default answer, an error, as before.
     */
    @Override
    public CompletableFuture<org.eclipse.lsp4j.debug.RunInTerminalResponse> runInTerminal(
            org.eclipse.lsp4j.debug.RunInTerminalRequestArguments args) {
        String[] argv = args == null ? null : args.getArgs();
        if (!runsDebuggee || disposed || root != null || argv == null || argv.length == 0) {
            return IDebugProtocolClient.super.runInTerminal(args);
        }
        // A plain HashMap: the adapter may send a null value, which unsets the variable.
        Map<String, String> env = new java.util.HashMap<>();
        if (args.getEnv() != null) {
            env.putAll(args.getEnv());
        }
        return host.onRunInTerminal(args.getCwd(), List.of(argv), env).handle((pid, error) -> {
            if (error != null) {
                // Answered with the reason, which the adapter puts in its launch failure; a bare exception
                // would reach it as "Internal error.".
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                        ? error.getCause()
                        : error;
                String reason = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                throw new org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                        new org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                                org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.RequestFailed, reason, null));
            }
            org.eclipse.lsp4j.debug.RunInTerminalResponse response =
                    new org.eclipse.lsp4j.debug.RunInTerminalResponse();
            response.setProcessId(pid == null || pid > Integer.MAX_VALUE ? null : pid.intValue());
            return response;
        });
    }

    /**
     * The adapter asks for a child session (see the class doc). lsp4j's default throws
     * {@code UnsupportedOperationException}, which is why JavaScript debugging never attached: the request
     * failed, js-debug kept waiting for a connection that never came, and the session sat in RUNNING with
     * no debuggee behind it. A child may itself ask for children (a debuggee's subprocesses); they all join
     * the root.
     */
    @Override
    public CompletableFuture<Void> startDebugging(org.eclipse.lsp4j.debug.StartDebuggingRequestArguments args) {
        DapClient owner = root == null ? this : root;
        return owner.startChild(args);
    }

    private CompletableFuture<Void> startChild(org.eclipse.lsp4j.debug.StartDebuggingRequestArguments args) {
        int adapterPort = port;
        if (disposed || adapterPort < 0 || args == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("this debug adapter transport cannot start a child session"));
        }
        boolean attach = args.getRequest() == org.eclipse.lsp4j.debug.StartDebuggingRequestArgumentsType.ATTACH;
        Map<String, Object> configuration = new java.util.LinkedHashMap<>();
        if (args.getConfiguration() != null) {
            configuration.putAll(args.getConfiguration());
        }
        CompletableFuture<Void> started = new CompletableFuture<>();
        try {
            // Off the reader thread: connecting blocks, and the child's replies arrive on its own reader.
            executor.execute(() -> connectChild(adapterPort, attach, configuration, started));
        } catch (RuntimeException rejected) {
            started.completeExceptionally(rejected); // disposed between the check and the submit
        }
        return started;
    }

    private void connectChild(
            int adapterPort, boolean attach, Map<String, Object> configuration, CompletableFuture<Void> started) {
        DapClient child = new DapClient(this);
        child.initialBreakpoints = initialBreakpoints;
        child.exceptionFilters = exceptionFilters;
        children.add(child);
        if (disposed) { // the session ended while this was queued
            children.remove(child);
            child.dispose();
            started.completeExceptionally(new java.util.concurrent.CancellationException("disposed"));
            return;
        }
        child.connect(adapterPort, adapterId).whenComplete((caps, error) -> {
            if (error != null) {
                children.remove(child);
                child.dispose();
                started.completeExceptionally(error);
                return;
            }
            // The adapter binds this connection to the pending target named in the configuration it sent.
            // Its `initialized` event (handled by the child) then installs breakpoints + configurationDone.
            CompletableFuture<Void> begun = attach ? child.attach(configuration) : child.launch(configuration);
            begun.whenComplete((v, launchError) -> {
                if (launchError == null || child.disposed) {
                    return;
                }
                if (isTimeout(launchError)) {
                    // Some adapters answer launch/attach late or only once the target ends. The session is
                    // judged by its events, not by this reply, so a missing answer must not tear it down.
                    LOG.log(Level.FINE, "child debug session did not answer launch/attach", launchError);
                    return;
                }
                host.onError("Could not start the debug target: " + launchError.getMessage());
                childEnded(child);
            });
            started.complete(null);
        });
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** The {@link Host} a child reports to: its events become this (root) session's events. */
    private Host childHost(DapClient child) {
        return new Host() {
            @Override
            public void onStopped(int threadId, String reason) {
                onStopped(threadId, reason, false);
            }

            @Override
            public void onStopped(int threadId, String reason, boolean allThreadsStopped) {
                focus = child; // inspection and stepping now address the session that stopped
                host.onStopped(threadId, reason, allThreadsStopped);
            }

            @Override
            public void onContinued() {
                DapClient f = focus;
                if (f == null || f == child) {
                    host.onContinued();
                }
            }

            @Override
            public void onOutput(String text, String category) {
                host.onOutput(text, category);
            }

            @Override
            public void onTerminated() {
                childEnded(child);
            }

            @Override
            public void onError(String message) {
                host.onError(message);
            }

            @Override
            public void onTransportClosed(Throwable error) {
                childEnded(child);
            }

            @Override
            public void onBreakpointStatus(Path file, List<DapModels.BreakpointStatus> statuses, boolean whole) {
                host.onBreakpointStatus(file, statuses, whole);
            }

            @Override
            public void onNotice(String message, boolean error) {
                host.onNotice(message, error);
            }
        };
    }

    /**
     * A child session ended. The others keep running; when it was the last one the debuggee is gone, so the
     * whole session ends — the root session has nothing left to debug.
     */
    private void childEnded(DapClient child) {
        if (!children.remove(child)) {
            return; // already handled (terminated is followed by the transport closing)
        }
        boolean wasFocus = focus == child;
        if (wasFocus) {
            focus = null;
        }
        child.dispose();
        if (disposed) {
            return;
        }
        if (children.isEmpty()) {
            host.onTerminated();
        } else if (wasFocus) {
            host.onContinued(); // the stop being shown belonged to the session that just ended
        }
    }

    // --- Requests (raw futures; DapManager marshals to FX + maps to neutral records) -------------

    /** (Re)sends the breakpoints for a single file (used live while running, and during configuration). */
    public CompletableFuture<Void> sendSetBreakpoints(DapModels.FileBreakpoints fb) {
        if (fb != null && root == null) {
            rememberBreakpoints(fb); // a child session started later must begin with the current set
            for (DapClient child : children) {
                child.sendSetBreakpoints(fb); // each session holds its own breakpoints
            }
        }
        if (server == null || fb == null) {
            return CompletableFuture.completedFuture(null);
        }
        Source source = new Source();
        source.setName(fb.file().getFileName().toString());
        source.setPath(fb.file().toString());
        List<SourceBreakpoint> sbs = new ArrayList<>();
        for (DapModels.LineBreakpoint lb : fb.breakpoints()) {
            SourceBreakpoint sb = new SourceBreakpoint();
            sb.setLine(lb.line() + 1); // DAP is 1-based; our model is 0-based
            if (lb.condition() != null && !lb.condition().isBlank()) {
                sb.setCondition(lb.condition());
            }
            if (lb.logMessage() != null && !lb.logMessage().isBlank()) {
                sb.setLogMessage(lb.logMessage());
            }
            sbs.add(sb);
        }
        SetBreakpointsArguments a = new SetBreakpointsArguments();
        a.setSource(source);
        a.setBreakpoints(sbs.toArray(new SourceBreakpoint[0]));
        a.setSourceModified(false);
        List<DapModels.LineBreakpoint> sent = List.copyOf(fb.breakpoints());
        return timed(server.setBreakpoints(a))
                .whenComplete((r, e) -> reportBreakpoints(fb.file(), sent, r, e))
                .thenApply(r -> null);
    }

    /** Which breakpoint an adapter-assigned id stands for: {@code breakpoint} events carry only the id. */
    private record BreakpointKey(Path file, int line) {}

    private final Map<Integer, BreakpointKey> breakpointIds = new java.util.concurrent.ConcurrentHashMap<>();

    /** The text of each thread's last stop event, when it had one. */
    private final Map<Integer, String> stopTexts = new java.util.concurrent.ConcurrentHashMap<>();

    /** Passes a {@code setBreakpoints} answer on: the adapter answers in the order the breakpoints were sent. */
    private void reportBreakpoints(
            Path file, List<DapModels.LineBreakpoint> sent, SetBreakpointsResponse response, Throwable error) {
        if (disposed || (root == null && !children.isEmpty())) {
            // A session that only starts child sessions (js-debug's first connection) debugs nothing: its
            // "unbound" answers would overwrite what the session that owns the program said.
            return;
        }
        List<DapModels.BreakpointStatus> statuses = new ArrayList<>();
        if (error != null) {
            String message = adapterMessage(error);
            if (message == null) {
                return; // no answer (a timeout): nothing is known, which is not the same as rejected
            }
            for (DapModels.LineBreakpoint lb : sent) {
                statuses.add(new DapModels.BreakpointStatus(lb.line(), false, true, message, -1));
            }
        } else {
            breakpointIds.values().removeIf(key -> key.file().equals(file));
            Breakpoint[] answered = response == null ? null : response.getBreakpoints();
            for (int i = 0; answered != null && i < answered.length && i < sent.size(); i++) {
                Breakpoint b = answered[i];
                if (b == null) {
                    continue;
                }
                int line = sent.get(i).line();
                if (b.getId() != null) {
                    breakpointIds.put(b.getId(), new BreakpointKey(file, line));
                }
                statuses.add(status(line, b));
            }
        }
        host.onBreakpointStatus(file, statuses, true);
    }

    /** Pure: one adapter breakpoint as a {@link DapModels.BreakpointStatus} for the 0-based line asked for. */
    static DapModels.BreakpointStatus status(int requestedLine, Breakpoint b) {
        boolean verified = b.isVerified();
        boolean failed = !verified && b.getReason() == BreakpointNotVerifiedReason.FAILED;
        Integer line = b.getLine();
        return new DapModels.BreakpointStatus(
                requestedLine, verified, failed, b.getMessage(), line == null || line < 1 ? -1 : line - 1);
    }

    /** What the adapter said when it refused a request, or null when the failure is not an answer at all. */
    private static String adapterMessage(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof org.eclipse.lsp4j.jsonrpc.ResponseErrorException refused) {
                String message = refused.getResponseError() == null
                        ? refused.getMessage()
                        : refused.getResponseError().getMessage();
                return message == null || message.isBlank() ? null : message;
            }
        }
        return null;
    }

    /**
     * The exception {@code threadId} is stopped on, or null when the adapter cannot say. Asks
     * {@code exceptionInfo} where the adapter supports it; otherwise (and when that fails) falls back to the
     * text its stop event carried.
     */
    public CompletableFuture<DapModels.ExceptionInfo> exceptionInfo(int threadId) {
        DapClient session = target();
        if (session != this) {
            return session.exceptionInfo(threadId);
        }
        String stopText = stopTexts.get(threadId);
        DapModels.ExceptionInfo fromStop = stopText == null ? null : new DapModels.ExceptionInfo("", stopText);
        Capabilities caps = capabilities;
        if (server == null || caps == null || !Boolean.TRUE.equals(caps.getSupportsExceptionInfoRequest())) {
            return CompletableFuture.completedFuture(fromStop);
        }
        ExceptionInfoArguments a = new ExceptionInfoArguments();
        a.setThreadId(threadId);
        return timed(server.exceptionInfo(a))
                .thenApply(r -> {
                    DapModels.ExceptionInfo info = exceptionInfo(r);
                    return info == null ? fromStop : info;
                })
                .exceptionally(e -> fromStop);
    }

    /** Pure: the type and message of an {@code exceptionInfo} answer; null when it names neither. */
    static DapModels.ExceptionInfo exceptionInfo(ExceptionInfoResponse r) {
        if (r == null) {
            return null;
        }
        ExceptionDetails d = r.getDetails();
        String type = d != null
                        && d.getFullTypeName() != null
                        && !d.getFullTypeName().isBlank()
                ? d.getFullTypeName()
                : d != null && d.getTypeName() != null && !d.getTypeName().isBlank()
                        ? d.getTypeName()
                        : r.getExceptionId();
        String message =
                d != null && d.getMessage() != null && !d.getMessage().isBlank() ? d.getMessage() : r.getDescription();
        DapModels.ExceptionInfo info = new DapModels.ExceptionInfo(type, message);
        return info.isEmpty() ? null : info;
    }

    /** Replaces {@code fb}'s file in the set installed on a session's {@code initialized} event. */
    private void rememberBreakpoints(DapModels.FileBreakpoints fb) {
        List<DapModels.FileBreakpoints> updated = new ArrayList<>();
        for (DapModels.FileBreakpoints existing : initialBreakpoints) {
            if (!existing.file().equals(fb.file())) {
                updated.add(existing);
            }
        }
        updated.add(fb);
        initialBreakpoints = List.copyOf(updated);
    }

    public CompletableFuture<List<DapModels.ThreadInfo>> threads() {
        DapClient session = target();
        if (session != this) {
            return session.threads();
        }
        return timed(server.threads()).thenApply(r -> {
            List<DapModels.ThreadInfo> out = new ArrayList<>();
            if (r != null && r.getThreads() != null) {
                for (org.eclipse.lsp4j.debug.Thread t : r.getThreads()) {
                    out.add(new DapModels.ThreadInfo(t.getId(), t.getName()));
                }
            }
            return out;
        });
    }

    public CompletableFuture<List<DapModels.StackFrameInfo>> stackTrace(int threadId) {
        DapClient session = target();
        if (session != this) {
            return session.stackTrace(threadId);
        }
        StackTraceArguments a = new StackTraceArguments();
        a.setThreadId(threadId);
        if (pagesStackTraces(capabilities)) {
            a.setStartFrame(0);
            a.setLevels(STACK_FRAME_LIMIT);
        }
        return timed(server.stackTrace(a)).thenApply(r -> {
            List<DapModels.StackFrameInfo> out = new ArrayList<>();
            if (r != null && r.getStackFrames() != null) {
                for (StackFrame f : r.getStackFrames()) {
                    out.add(new DapModels.StackFrameInfo(
                            f.getId(),
                            f.getName(),
                            sourcePath(f.getSource()),
                            f.getLine() - 1, // back to 0-based line
                            f.getColumn()));
                }
            }
            return out;
        });
    }

    /**
     * The most frames asked for per stop. Every stop and every step fetches the stack, and without a limit
     * an adapter sends — and the client decodes and maps — all of it: tens of thousands of frames in a
     * runaway recursion, on each Step Over. The frames that matter are the innermost ones.
     */
    static final int STACK_FRAME_LIMIT = 1000;

    /** Pure: whether the adapter honours {@code startFrame}/{@code levels} (null-safe). */
    static boolean pagesStackTraces(Capabilities capabilities) {
        return capabilities != null && Boolean.TRUE.equals(capabilities.getSupportsDelayedStackTraceLoading());
    }

    /**
     * A frame's source as a local path, or {@code null} when it has none. Adapters put strings that are not
     * file paths into {@code Source.path} — {@code <node_internals>/…}, {@code jdt://contents/…}, {@code
     * <frozen importlib>} — and on Windows those are not even legal paths: one such frame used to fail the
     * whole {@code stackTrace} response, leaving every stop with an empty call stack.
     */
    static Path sourcePath(Source source) {
        if (source == null || source.getPath() == null) {
            return null;
        }
        try {
            return Path.of(source.getPath());
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    public CompletableFuture<List<DapModels.ScopeInfo>> scopes(int frameId) {
        DapClient session = target();
        if (session != this) {
            return session.scopes(frameId);
        }
        ScopesArguments a = new ScopesArguments();
        a.setFrameId(frameId);
        return timed(server.scopes(a)).thenApply(r -> {
            List<DapModels.ScopeInfo> out = new ArrayList<>();
            if (r != null && r.getScopes() != null) {
                for (Scope s : r.getScopes()) {
                    out.add(new DapModels.ScopeInfo(s.getName(), s.getVariablesReference(), s.isExpensive()));
                }
            }
            return out;
        });
    }

    public CompletableFuture<List<DapModels.VariableInfo>> variables(int variablesReference) {
        return variables(variablesReference, null, 0, 0);
    }

    /**
     * One page of a container's children: {@code filter} is {@code "indexed"}, {@code "named"} or {@code null}
     * (both), and {@code count > 0} asks for {@code count} children from {@code start} — the DAP paging
     * contract, usable for a container whose {@code indexedVariables} / {@code namedVariables} the adapter
     * reported. {@code count <= 0} asks for everything, as {@link #variables(int)} does.
     */
    public CompletableFuture<List<DapModels.VariableInfo>> variables(
            int variablesReference, String filter, int start, int count) {
        DapClient session = target();
        if (session != this) {
            return session.variables(variablesReference, filter, start, count);
        }
        VariablesArguments a = new VariablesArguments();
        a.setVariablesReference(variablesReference);
        if ("indexed".equals(filter)) {
            a.setFilter(org.eclipse.lsp4j.debug.VariablesArgumentsFilter.INDEXED);
        } else if ("named".equals(filter)) {
            a.setFilter(org.eclipse.lsp4j.debug.VariablesArgumentsFilter.NAMED);
        }
        if (count > 0) {
            a.setStart(Math.max(0, start));
            a.setCount(count);
        }
        return timed(server.variables(a)).thenApply(r -> {
            List<DapModels.VariableInfo> out = new ArrayList<>();
            if (r != null && r.getVariables() != null) {
                for (Variable v : r.getVariables()) {
                    out.add(new DapModels.VariableInfo(
                            v.getName(),
                            v.getValue(),
                            v.getType(),
                            v.getVariablesReference(),
                            count(v.getNamedVariables()),
                            count(v.getIndexedVariables())));
                }
            }
            return out;
        });
    }

    private static int count(Integer reported) {
        return reported == null ? 0 : Math.max(0, reported);
    }

    /** Evaluates {@code expression} in {@code frameId}'s context ({@code "repl"} or {@code "watch"}). */
    public CompletableFuture<String> evaluate(String expression, int frameId, String context) {
        DapClient session = target();
        if (session != this) {
            return session.evaluate(expression, frameId, context);
        }
        EvaluateArguments a = new EvaluateArguments();
        a.setExpression(expression);
        a.setFrameId(frameId);
        a.setContext(context);
        return timed(server.evaluate(a)).thenApply(r -> r == null ? null : r.getResult());
    }

    /** Like {@link #evaluate} but keeps the full response: result + expandable children reference + type
     *  (for watches that expand into the variables tree and the hover value popup). */
    public CompletableFuture<DapModels.EvalResult> evaluateFull(String expression, int frameId, String context) {
        DapClient session = target();
        if (session != this) {
            return session.evaluateFull(expression, frameId, context);
        }
        EvaluateArguments a = new EvaluateArguments();
        a.setExpression(expression);
        a.setFrameId(frameId);
        a.setContext(context);
        return timed(server.evaluate(a))
                .thenApply(r -> r == null
                        ? null
                        : new DapModels.EvalResult(
                                r.getResult(),
                                r.getVariablesReference(),
                                r.getType(),
                                count(r.getNamedVariables()),
                                count(r.getIndexedVariables())));
    }

    public CompletableFuture<String> setVariable(int variablesReference, String name, String value) {
        DapClient session = target();
        if (session != this) {
            return session.setVariable(variablesReference, name, value);
        }
        SetVariableArguments a = new SetVariableArguments();
        a.setVariablesReference(variablesReference);
        a.setName(name);
        a.setValue(value);
        return timed(server.setVariable(a)).thenApply(r -> r == null ? value : r.getValue());
    }

    /**
     * Resumes {@code threadId}. The result says whether the adapter resumed <em>every</em> thread: per the
     * protocol a missing {@code allThreadsContinued} means it did, and only an explicit {@code false} means
     * other stopped threads are still stopped.
     */
    public CompletableFuture<Boolean> resume(int threadId) {
        DapClient session = target();
        if (session != this) {
            return session.resume(threadId);
        }
        ContinueArguments a = new ContinueArguments();
        a.setThreadId(threadId);
        return timed(server.continue_(a))
                .thenApply(r -> r == null || !Boolean.FALSE.equals(r.getAllThreadsContinued()));
    }

    /** Pauses a running thread; the adapter answers with a {@code stopped(reason=pause)} event. */
    public void pause(int threadId) {
        DapClient session = target();
        if (session != this) {
            session.pause(threadId);
            return;
        }
        org.eclipse.lsp4j.debug.PauseArguments a = new org.eclipse.lsp4j.debug.PauseArguments();
        a.setThreadId(threadId);
        ignore(timed(server.pause(a)));
    }

    /** Asks the adapter for the goto targets at {@code line} (0-based) of {@code file}; the first
     *  target's id feeds {@link #gotoTarget}. Empty when the line isn't a valid jump target. */
    public CompletableFuture<List<Integer>> gotoTargets(Path file, int line) {
        DapClient session = target();
        if (session != this) {
            return session.gotoTargets(file, line);
        }
        Source source = new Source();
        source.setName(file.getFileName().toString());
        source.setPath(file.toString());
        org.eclipse.lsp4j.debug.GotoTargetsArguments a = new org.eclipse.lsp4j.debug.GotoTargetsArguments();
        a.setSource(source);
        a.setLine(line + 1); // DAP is 1-based
        return timed(server.gotoTargets(a)).thenApply(r -> {
            List<Integer> ids = new ArrayList<>();
            if (r != null && r.getTargets() != null) {
                for (org.eclipse.lsp4j.debug.GotoTarget t : r.getTargets()) {
                    ids.add(t.getId());
                }
            }
            return ids;
        });
    }

    /** Moves the execution pointer of {@code threadId} to a target from {@link #gotoTargets} (Jump to
     *  Line); the adapter then emits {@code stopped(reason=goto)}, refreshing the UI like any stop. */
    public CompletableFuture<Void> gotoTarget(int threadId, int targetId) {
        DapClient session = target();
        if (session != this) {
            return session.gotoTarget(threadId, targetId);
        }
        org.eclipse.lsp4j.debug.GotoArguments a = new org.eclipse.lsp4j.debug.GotoArguments();
        a.setThreadId(threadId);
        a.setTargetId(targetId);
        return timed(server.goto_(a));
    }

    public CompletableFuture<Void> next(int threadId) {
        DapClient session = target();
        if (session != this) {
            return session.next(threadId);
        }
        NextArguments a = new NextArguments();
        a.setThreadId(threadId);
        return timed(server.next(a));
    }

    public CompletableFuture<Void> stepIn(int threadId) {
        DapClient session = target();
        if (session != this) {
            return session.stepIn(threadId);
        }
        StepInArguments a = new StepInArguments();
        a.setThreadId(threadId);
        return timed(server.stepIn(a));
    }

    public CompletableFuture<Void> stepOut(int threadId) {
        DapClient session = target();
        if (session != this) {
            return session.stepOut(threadId);
        }
        StepOutArguments a = new StepOutArguments();
        a.setThreadId(threadId);
        return timed(server.stepOut(a));
    }

    /** Disconnects (terminates the debuggee), closes the socket, and kills the adapter subprocess tree. */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        focus = null;
        for (DapClient child : children) {
            child.dispose(); // children live and die with the session that started them
        }
        children.clear();
        try {
            if (server != null) {
                DisconnectArguments a = new DisconnectArguments();
                a.setTerminateDebuggee(true);
                ignore(timed(server.disconnect(a)));
            }
        } catch (RuntimeException ignored) {
            // best effort
        }
        com.editora.lsp.AsyncPipeWriter out = writer;
        if (out != null) {
            // The disconnect was only queued. Give it a moment to be written before the transport is
            // closed under it — normally microseconds; bounded, so an adapter that is not reading cannot
            // hold the caller the way the direct write used to.
            try {
                out.awaitDrained(DISCONNECT_FLUSH_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
            // best effort
        }
        if (out != null) {
            out.close();
        }
        // Kill the adapter subprocess and its descendants (debugpy stdio, or a node js-debug server). Like
        // LanguageServerSession: ProcessRegistry.killTree destroys the descendant tree first (a wrapper
        // script would otherwise orphan the real adapter), escalates to a force-kill, and untracks it.
        ProcessRegistry.killTree(adapterProcess);
        executor.shutdownNow();
    }

    private static void ignore(CompletableFuture<?> f) {
        if (f != null) {
            f.exceptionally(e -> null);
        }
    }
}
