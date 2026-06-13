package com.editora.dap;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.ContinuedEventArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.EvaluateResponse;
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
 */
public final class DapClient implements IDebugProtocolClient {

    private static final Logger LOG = Logger.getLogger(DapClient.class.getName());

    /** Event sink (implemented by {@link DapManager}); calls arrive on the launcher's reader thread. */
    public interface Host {
        void onStopped(int threadId, String reason);

        void onContinued();

        void onOutput(String text, String category);

        void onTerminated();

        void onError(String message);
    }

    private final Host host;
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

    public DapClient(Host host) {
        this.host = host;
    }

    public void setBreakpoints(List<DapModels.FileBreakpoints> breakpoints) {
        this.initialBreakpoints = breakpoints == null ? List.of() : List.copyOf(breakpoints);
    }

    public void setExceptionFilters(List<String> filters) {
        this.exceptionFilters = filters == null ? List.of() : List.copyOf(filters);
    }

    /** The adapter's {@code initialize} capabilities (kept for feature gating, e.g. Jump to Line). */
    private volatile Capabilities capabilities;

    /** Whether the adapter supports {@code gotoTargets}/{@code goto} (debugpy does; java-debug and
     *  vscode-js-debug currently do not). */
    public boolean supportsGotoTargets() {
        return capabilities != null && Boolean.TRUE.equals(capabilities.getSupportsGotoTargetsRequest());
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
            socket = openWithRetry(port, 50);
            Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(
                    this, socket.getInputStream(), socket.getOutputStream(), executor, c -> c);
            server = launcher.getRemoteProxy();
            launcher.startListening();
            return server.initialize(initArgs(adapterId)).thenApply(c -> {
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
            this.adapterProcess = process;
            Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(
                    this, process.getInputStream(), process.getOutputStream(), executor, c -> c);
            server = launcher.getRemoteProxy();
            launcher.startListening();
            return server.initialize(initArgs(adapterId)).thenApply(c -> {
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
    }

    private static Socket openWithRetry(int port, int tries) throws InterruptedException {
        for (int i = 0; i < tries; i++) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return s;
            } catch (Exception e) {
                Thread.sleep(40);
            }
        }
        throw new IllegalStateException("could not connect to the debug adapter on port " + port);
    }

    private static InitializeRequestArguments initArgs(String adapterId) {
        InitializeRequestArguments a = new InitializeRequestArguments();
        a.setClientID("editora");
        a.setClientName("Editora");
        a.setAdapterID(adapterId == null || adapterId.isBlank() ? "java" : adapterId);
        a.setPathFormat("path");
        a.setLinesStartAt1(true);
        a.setColumnsStartAt1(true);
        a.setSupportsRunInTerminalRequest(false);
        return a;
    }

    /** Sends the {@code launch} request (body from {@link LaunchConfig#launch}). */
    public CompletableFuture<Void> launch(Map<String, Object> args) {
        return server.launch(args);
    }

    /** Sends the {@code attach} request (body from {@link LaunchConfig#attach}). */
    public CompletableFuture<Void> attach(Map<String, Object> args) {
        return server.attach(args);
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
                server.setExceptionBreakpoints(ex);
            }
            server.configurationDone(new ConfigurationDoneArguments());
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
        host.onOutput(args.getOutput(), args.getCategory());
    }

    @Override
    public void terminated(TerminatedEventArguments args) {
        host.onTerminated();
    }

    @Override
    public void exited(org.eclipse.lsp4j.debug.ExitedEventArguments args) {
        // The debuggee process exited; the session ends on the following `terminated` event.
    }

    // --- Requests (raw futures; DapManager marshals to FX + maps to neutral records) -------------

    /** (Re)sends the breakpoints for a single file (used live while running, and during configuration). */
    public CompletableFuture<Void> sendSetBreakpoints(DapModels.FileBreakpoints fb) {
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
        return server.setBreakpoints(a).thenApply(r -> null);
    }

    public CompletableFuture<List<DapModels.ThreadInfo>> threads() {
        return server.threads().thenApply(r -> {
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
        StackTraceArguments a = new StackTraceArguments();
        a.setThreadId(threadId);
        return server.stackTrace(a).thenApply(r -> {
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
        ScopesArguments a = new ScopesArguments();
        a.setFrameId(frameId);
        return server.scopes(a).thenApply(r -> {
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
        VariablesArguments a = new VariablesArguments();
        a.setVariablesReference(variablesReference);
        return server.variables(a).thenApply(r -> {
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
        EvaluateArguments a = new EvaluateArguments();
        a.setExpression(expression);
        a.setFrameId(frameId);
        a.setContext(context);
        return server.evaluate(a).thenApply(EvaluateResponse::getResult);
    }

    /** Like {@link #evaluate} but keeps the full response: result + expandable children reference + type
     *  (for watches that expand into the variables tree and the hover value popup). */
    public CompletableFuture<DapModels.EvalResult> evaluateFull(String expression, int frameId, String context) {
        EvaluateArguments a = new EvaluateArguments();
        a.setExpression(expression);
        a.setFrameId(frameId);
        a.setContext(context);
        return server.evaluate(a)
                .thenApply(r -> r == null
                        ? null
                        : new DapModels.EvalResult(r.getResult(), r.getVariablesReference(), r.getType()));
    }

    public CompletableFuture<String> setVariable(int variablesReference, String name, String value) {
        SetVariableArguments a = new SetVariableArguments();
        a.setVariablesReference(variablesReference);
        a.setName(name);
        a.setValue(value);
        return server.setVariable(a).thenApply(r -> r == null ? value : r.getValue());
    }

    public void resume(int threadId) {
        ContinueArguments a = new ContinueArguments();
        a.setThreadId(threadId);
        ignore(server.continue_(a));
    }

    /** Pauses a running thread; the adapter answers with a {@code stopped(reason=pause)} event. */
    public void pause(int threadId) {
        org.eclipse.lsp4j.debug.PauseArguments a = new org.eclipse.lsp4j.debug.PauseArguments();
        a.setThreadId(threadId);
        ignore(server.pause(a));
    }

    /** Asks the adapter for the goto targets at {@code line} (0-based) of {@code file}; the first
     *  target's id feeds {@link #gotoTarget}. Empty when the line isn't a valid jump target. */
    public CompletableFuture<List<Integer>> gotoTargets(Path file, int line) {
        Source source = new Source();
        source.setName(file.getFileName().toString());
        source.setPath(file.toString());
        org.eclipse.lsp4j.debug.GotoTargetsArguments a = new org.eclipse.lsp4j.debug.GotoTargetsArguments();
        a.setSource(source);
        a.setLine(line + 1); // DAP is 1-based
        return server.gotoTargets(a).thenApply(r -> {
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
        org.eclipse.lsp4j.debug.GotoArguments a = new org.eclipse.lsp4j.debug.GotoArguments();
        a.setThreadId(threadId);
        a.setTargetId(targetId);
        return server.goto_(a);
    }

    public void next(int threadId) {
        NextArguments a = new NextArguments();
        a.setThreadId(threadId);
        ignore(server.next(a));
    }

    public void stepIn(int threadId) {
        StepInArguments a = new StepInArguments();
        a.setThreadId(threadId);
        ignore(server.stepIn(a));
    }

    public void stepOut(int threadId) {
        StepOutArguments a = new StepOutArguments();
        a.setThreadId(threadId);
        ignore(server.stepOut(a));
    }

    /** Disconnects (terminates the debuggee), closes the socket, and kills the adapter subprocess tree. */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        try {
            if (server != null) {
                DisconnectArguments a = new DisconnectArguments();
                a.setTerminateDebuggee(true);
                server.disconnect(a);
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
        // LanguageServerSession: destroy the descendant tree first, then the root — a wrapper script
        // (e.g. node launched via a shim) would otherwise orphan the real adapter.
        Process p = adapterProcess;
        if (p != null) {
            try {
                p.descendants().forEach(ProcessHandle::destroy);
            } catch (RuntimeException ignored) {
                // best effort
            }
            p.destroy();
        }
        executor.shutdownNow();
    }

    private static void ignore(CompletableFuture<?> f) {
        if (f != null) {
            f.exceptionally(e -> null);
        }
    }
}
