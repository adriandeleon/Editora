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
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.ContinuedEventArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.NextArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.ScopesArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
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

        void onContinued();

        void onOutput(String text, String category);

        void onTerminated();

        void onError(String message);

        default void onTransportClosed(Throwable error) {
            onTerminated();
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

    public DapClient(Host host) {
        this.host = host;
        this.root = null;
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
                    this, socket.getInputStream(), socket.getOutputStream(), executor, c -> c);
            server = launcher.getRemoteProxy();
            watchTransport(launcher.startListening());
            // A socket adapter can be joined by a second connection, so child sessions are possible here.
            return timed(server.initialize(initArgs(adapterId, true))).thenApply(c -> {
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
                    this, process.getInputStream(), process.getOutputStream(), executor, c -> c);
            server = launcher.getRemoteProxy();
            watchTransport(launcher.startListening());
            return timed(server.initialize(initArgs(adapterId, false))).thenApply(c -> {
                this.capabilities = c;
                return c;
            });
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to wire stdio debug adapter", e);
            return CompletableFuture.failedFuture(e);
        }
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
    private static InitializeRequestArguments initArgs(String adapterId, boolean startDebugging) {
        InitializeRequestArguments a = new InitializeRequestArguments();
        a.setSupportsStartDebuggingRequest(startDebugging);
        a.setClientID("editora");
        a.setClientName("Editora");
        a.setAdapterID(adapterId == null || adapterId.isBlank() ? "java" : adapterId);
        a.setPathFormat("path");
        a.setLinesStartAt1(true);
        a.setColumnsStartAt1(true);
        a.setSupportsRunInTerminalRequest(false);
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
        host.onStopped(tid == null ? 0 : tid, args.getReason());
    }

    @Override
    public void continued(ContinuedEventArguments args) {
        host.onContinued();
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
                focus = child; // inspection and stepping now address the session that stopped
                host.onStopped(threadId, reason);
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
        return timed(server.setBreakpoints(a)).thenApply(r -> null);
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
        return timed(server.stackTrace(a)).thenApply(r -> {
            List<DapModels.StackFrameInfo> out = new ArrayList<>();
            if (r != null && r.getStackFrames() != null) {
                for (StackFrame f : r.getStackFrames()) {
                    Source src = f.getSource();
                    Path path = src != null && src.getPath() != null ? Path.of(src.getPath()) : null;
                    out.add(new DapModels.StackFrameInfo(
                            f.getId(), f.getName(), path, f.getLine() - 1, f.getColumn())); // back to 0-based line
                }
            }
            return out;
        });
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
        DapClient session = target();
        if (session != this) {
            return session.variables(variablesReference);
        }
        VariablesArguments a = new VariablesArguments();
        a.setVariablesReference(variablesReference);
        return timed(server.variables(a)).thenApply(r -> {
            List<DapModels.VariableInfo> out = new ArrayList<>();
            if (r != null && r.getVariables() != null) {
                for (Variable v : r.getVariables()) {
                    out.add(new DapModels.VariableInfo(
                            v.getName(), v.getValue(), v.getType(), v.getVariablesReference()));
                }
            }
            return out;
        });
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
                        : new DapModels.EvalResult(r.getResult(), r.getVariablesReference(), r.getType()));
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

    public void resume(int threadId) {
        DapClient session = target();
        if (session != this) {
            session.resume(threadId);
            return;
        }
        ContinueArguments a = new ContinueArguments();
        a.setThreadId(threadId);
        ignore(timed(server.continue_(a)));
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

    public void next(int threadId) {
        DapClient session = target();
        if (session != this) {
            session.next(threadId);
            return;
        }
        NextArguments a = new NextArguments();
        a.setThreadId(threadId);
        ignore(timed(server.next(a)));
    }

    public void stepIn(int threadId) {
        DapClient session = target();
        if (session != this) {
            session.stepIn(threadId);
            return;
        }
        StepInArguments a = new StepInArguments();
        a.setThreadId(threadId);
        ignore(timed(server.stepIn(a)));
    }

    public void stepOut(int threadId) {
        DapClient session = target();
        if (session != this) {
            session.stepOut(threadId);
            return;
        }
        StepOutArguments a = new StepOutArguments();
        a.setThreadId(threadId);
        ignore(timed(server.stepOut(a)));
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
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
            // best effort
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
