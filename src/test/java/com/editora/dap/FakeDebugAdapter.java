package com.editora.dap;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.ContinueResponse;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.NextArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsResponse;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StackTraceResponse;
import org.eclipse.lsp4j.debug.StartDebuggingRequestArguments;
import org.eclipse.lsp4j.debug.StartDebuggingRequestArgumentsType;
import org.eclipse.lsp4j.debug.StepInArguments;
import org.eclipse.lsp4j.debug.StepOutArguments;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.TerminatedEventArguments;
import org.eclipse.lsp4j.debug.ThreadsResponse;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

/**
 * An in-process debug adapter listening on a loopback port, speaking real DAP over real sockets. Every
 * connection is a session that records the requests it receives; a test then plays the adapter's side
 * (stops, output, termination) through {@link Session}.
 *
 * <p>With {@code multiSession} it behaves like vscode-js-debug's {@code dapDebugServer.js}: the first
 * connection is a root session that debugs nothing. After its {@code launch} it sends the reverse request
 * {@code startDebugging} and expects a <em>second</em> connection whose {@code launch} carries the
 * {@code __pendingTargetId} it handed out — that child session is where breakpoints and stops live.
 */
public final class FakeDebugAdapter implements AutoCloseable {

    /** The id js-debug uses to bind a new connection to the target it asked a session for. */
    public static final String PENDING_TARGET = "target-1";

    private final ServerSocket server;
    private final boolean multiSession;
    private final BlockingQueue<Session> accepted = new LinkedBlockingQueue<>();
    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public FakeDebugAdapter(boolean multiSession) throws IOException {
        this(multiSession, InetAddress.getLoopbackAddress());
    }

    /** Listens on {@code bindAddress} only — e.g. {@code ::1}, where js-debug ends up on many systems. */
    public FakeDebugAdapter(boolean multiSession, InetAddress bindAddress) throws IOException {
        this.multiSession = multiSession;
        this.server = new ServerSocket(0, 50, bindAddress);
        Thread acceptor = new Thread(this::acceptLoop, "fake-dap-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    /** The next connection the adapter accepts, in order; fails the test if none arrives. */
    public Session awaitSession() throws InterruptedException {
        Session next = accepted.poll(10, TimeUnit.SECONDS);
        if (next == null) {
            throw new AssertionError("the client never opened another connection to the adapter");
        }
        return next;
    }

    public int sessionCount() {
        return sessions.size();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                Session session = new Session(socket, sessions.isEmpty());
                sessions.add(session);
                session.start();
                accepted.add(session);
            } catch (IOException e) {
                return; // closed
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
        for (Session session : sessions) {
            session.socket.close();
        }
    }

    /** One connection to the adapter. */
    public final class Session implements IDebugProtocolServer {

        private final Socket socket;
        private final boolean first;
        private volatile IDebugProtocolClient client;

        /** Every request name, in arrival order. */
        public final List<String> requests = new CopyOnWriteArrayList<>();

        public final List<SetBreakpointsArguments> breakpoints = new CopyOnWriteArrayList<>();
        public final List<SetExceptionBreakpointsArguments> exceptionBreakpoints = new CopyOnWriteArrayList<>();
        public final CountDownLatch configured = new CountDownLatch(1);
        public final CountDownLatch disconnected = new CountDownLatch(1);
        public volatile InitializeRequestArguments initializeArgs;
        public volatile Map<String, Object> launchArgs;
        public volatile boolean attached;
        /** Root only: completes when the client has answered the reverse {@code startDebugging} request. */
        public final CompletableFuture<Void> startDebuggingAnswered = new CompletableFuture<>();

        private Session(Socket socket, boolean first) {
            this.socket = socket;
            this.first = first;
        }

        private void start() throws IOException {
            var launcher = DSPLauncher.createServerLauncher(this, socket.getInputStream(), socket.getOutputStream());
            client = launcher.getRemoteProxy();
            launcher.startListening();
        }

        private void record(String request) {
            synchronized (requests) {
                requests.add(request);
                requests.notifyAll();
            }
        }

        /** Blocks until {@code request} has been received on this session. */
        public void awaitRequest(String request) throws InterruptedException {
            awaitRequests(request, 1);
        }

        /** Blocks until {@code request} has been received {@code count} times on this session. */
        public void awaitRequests(String request, int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            synchronized (requests) {
                while (java.util.Collections.frequency(requests, request) < count) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new AssertionError(
                                "the adapter never received " + count + " x '" + request + "'; got " + requests);
                    }
                    TimeUnit.NANOSECONDS.timedWait(requests, remaining);
                }
            }
        }

        // --- the adapter's side, driven by the test -------------------------------------------------

        public void stop(int threadId, String reason) {
            StoppedEventArguments stopped = new StoppedEventArguments();
            stopped.setThreadId(threadId);
            stopped.setReason(reason);
            client.stopped(stopped);
        }

        public void output(String text) {
            output(text, "stdout");
        }

        public void output(String text, String category) {
            OutputEventArguments output = new OutputEventArguments();
            output.setOutput(text);
            output.setCategory(category);
            client.output(output);
        }

        /**
         * Returns once the client has processed everything this session sent so far: a reverse request is
         * answered only after the events written before it have been handled, on the same reader thread.
         */
        public void awaitDelivered() throws Exception {
            org.eclipse.lsp4j.debug.RunInTerminalRequestArguments ping =
                    new org.eclipse.lsp4j.debug.RunInTerminalRequestArguments();
            ping.setArgs(new String[0]);
            ping.setCwd("");
            try {
                client.runInTerminal(ping).get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException answeredWithAnError) {
                // The client does not run terminals; any answer at all is the acknowledgement we wanted.
            }
        }

        public void terminate() {
            client.terminated(new TerminatedEventArguments());
        }

        // --- IDebugProtocolServer -------------------------------------------------------------------

        @Override
        public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
            initializeArgs = args;
            record("initialize");
            CompletableFuture<Capabilities> reply = CompletableFuture.completedFuture(new Capabilities());
            // The event follows the response, as the protocol orders them.
            CompletableFuture.runAsync(() -> client.initialized());
            return reply;
        }

        @Override
        public CompletableFuture<Void> launch(Map<String, Object> args) {
            launchArgs = args;
            record("launch");
            if (multiSession && first) {
                CompletableFuture.runAsync(this::requestChildSession);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> attach(Map<String, Object> args) {
            launchArgs = args;
            attached = true;
            record("attach");
            return CompletableFuture.completedFuture(null);
        }

        private void requestChildSession() {
            Map<String, Object> configuration = new LinkedHashMap<>();
            configuration.put("type", "pwa-node");
            configuration.put("name", "app.js [1234]");
            configuration.put("__pendingTargetId", PENDING_TARGET);
            StartDebuggingRequestArguments args = new StartDebuggingRequestArguments();
            args.setRequest(StartDebuggingRequestArgumentsType.LAUNCH);
            args.setConfiguration(configuration);
            client.startDebugging(args).whenComplete((v, error) -> {
                if (error != null) {
                    startDebuggingAnswered.completeExceptionally(error);
                } else {
                    startDebuggingAnswered.complete(null);
                }
            });
        }

        @Override
        public CompletableFuture<SetBreakpointsResponse> setBreakpoints(SetBreakpointsArguments args) {
            breakpoints.add(args);
            record("setBreakpoints");
            return CompletableFuture.completedFuture(new SetBreakpointsResponse());
        }

        @Override
        public CompletableFuture<SetExceptionBreakpointsResponse> setExceptionBreakpoints(
                SetExceptionBreakpointsArguments args) {
            exceptionBreakpoints.add(args);
            record("setExceptionBreakpoints");
            return CompletableFuture.completedFuture(new SetExceptionBreakpointsResponse());
        }

        @Override
        public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments args) {
            record("configurationDone");
            configured.countDown();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<ThreadsResponse> threads() {
            record("threads");
            org.eclipse.lsp4j.debug.Thread thread = new org.eclipse.lsp4j.debug.Thread();
            thread.setId(7);
            thread.setName("main");
            ThreadsResponse response = new ThreadsResponse();
            response.setThreads(new org.eclipse.lsp4j.debug.Thread[] {thread});
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public CompletableFuture<StackTraceResponse> stackTrace(StackTraceArguments args) {
            record("stackTrace");
            StackFrame frame = new StackFrame();
            frame.setId(1);
            frame.setName(first && multiSession ? "root" : "debuggee");
            frame.setLine(3);
            StackTraceResponse response = new StackTraceResponse();
            response.setStackFrames(new StackFrame[] {frame});
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public CompletableFuture<Void> next(NextArguments args) {
            record("next");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> stepIn(StepInArguments args) {
            record("stepIn");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> stepOut(StepOutArguments args) {
            record("stepOut");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<ContinueResponse> continue_(ContinueArguments args) {
            record("continue");
            return CompletableFuture.completedFuture(new ContinueResponse());
        }

        @Override
        public CompletableFuture<Void> disconnect(DisconnectArguments args) {
            record("disconnect");
            disconnected.countDown();
            return CompletableFuture.completedFuture(null);
        }
    }
}
