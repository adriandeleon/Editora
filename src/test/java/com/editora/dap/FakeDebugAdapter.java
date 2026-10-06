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

    /**
     * When set (before the client connects), sessions advertise {@code supportsExceptionInfoRequest} and
     * answer {@code exceptionInfo} with it.
     */
    public volatile org.eclipse.lsp4j.debug.ExceptionInfoResponse exceptionInfo;

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
        /** When set, every step request is refused with this message (java-debug: "thread is not suspended"). */
        public volatile String stepFailure;
        /** The {@code allThreadsContinued} of every {@code continue} response; null leaves it out. */
        public volatile Boolean allThreadsContinued;
        /** The thread id of every {@code continue} request, in arrival order. */
        public final List<Integer> continuedThreads = new CopyOnWriteArrayList<>();
        /** The source path and 1-based line of the one frame every {@code stackTrace} answers with. */
        public volatile String framePath;

        public volatile int frameLine = 3;

        /** Root only: completes when the client has answered the reverse {@code startDebugging} request. */
        public final CompletableFuture<Void> startDebuggingAnswered = new CompletableFuture<>();

        private Session(Socket socket, boolean first) {
            this.socket = socket;
            this.first = first;
        }

        private volatile org.eclipse.lsp4j.jsonrpc.RemoteEndpoint remote;

        private void start() throws IOException {
            var launcher = DSPLauncher.createServerLauncher(this, socket.getInputStream(), socket.getOutputStream());
            client = launcher.getRemoteProxy();
            remote = launcher.getRemoteEndpoint();
            launcher.startListening();
        }

        /**
         * Answers {@code setBreakpoints}: one breakpoint per requested one, in order. Null answers an empty
         * response, as an adapter that says nothing about them.
         */
        public volatile java.util.function.Function<SetBreakpointsArguments, List<org.eclipse.lsp4j.debug.Breakpoint>>
                breakpointAnswer;

        /** When set, every {@code setBreakpoints} is refused with this message. */
        public volatile String breakpointFailure;

        /** An adapter breakpoint as a {@code setBreakpoints} answer or a {@code breakpoint} event carries it. */
        public static org.eclipse.lsp4j.debug.Breakpoint breakpoint(
                Integer id, boolean verified, Integer line, String message) {
            org.eclipse.lsp4j.debug.Breakpoint b = new org.eclipse.lsp4j.debug.Breakpoint();
            b.setId(id);
            b.setVerified(verified);
            b.setLine(line);
            b.setMessage(message);
            return b;
        }

        /** The {@code breakpoint} event: the adapter changed its mind about one it already answered for. */
        public void breakpointChanged(org.eclipse.lsp4j.debug.Breakpoint breakpoint) {
            org.eclipse.lsp4j.debug.BreakpointEventArguments event =
                    new org.eclipse.lsp4j.debug.BreakpointEventArguments();
            event.setReason(org.eclipse.lsp4j.debug.BreakpointEventArgumentsReason.CHANGED);
            event.setBreakpoint(breakpoint);
            client.breakpoint(event);
        }

        /** java-debug's non-standard {@code usernotification} event. */
        public void userNotification(String type, String message) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("notificationType", type);
            body.put("message", message);
            remote.notify("usernotification", body);
        }

        /** A stop event carrying the adapter's own description of it. */
        public void stop(int threadId, String reason, String description, String text) {
            StoppedEventArguments stopped = new StoppedEventArguments();
            stopped.setThreadId(threadId);
            stopped.setReason(reason);
            stopped.setDescription(description);
            stopped.setText(text);
            client.stopped(stopped);
        }

        @Override
        public CompletableFuture<org.eclipse.lsp4j.debug.ExceptionInfoResponse> exceptionInfo(
                org.eclipse.lsp4j.debug.ExceptionInfoArguments args) {
            record("exceptionInfo");
            return CompletableFuture.completedFuture(exceptionInfo);
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
            stop(threadId, reason, null);
        }

        /** A stop that says whether every thread was suspended ({@code null} leaves the property out). */
        public void stop(int threadId, String reason, Boolean allThreadsStopped) {
            StoppedEventArguments stopped = new StoppedEventArguments();
            stopped.setThreadId(threadId);
            stopped.setReason(reason);
            stopped.setAllThreadsStopped(allThreadsStopped);
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

        /**
         * The reverse request java-debug sends for a launch with {@code console: integratedTerminal}: the
         * client is to start {@code argv} itself. Completes with the client's answer (the process id).
         */
        public CompletableFuture<org.eclipse.lsp4j.debug.RunInTerminalResponse> runInTerminal(
                String cwd, List<String> argv, Map<String, String> env) {
            org.eclipse.lsp4j.debug.RunInTerminalRequestArguments request =
                    new org.eclipse.lsp4j.debug.RunInTerminalRequestArguments();
            request.setKind(org.eclipse.lsp4j.debug.RunInTerminalRequestArgumentsKind.INTEGRATED);
            request.setTitle("Debug: Echo");
            request.setCwd(cwd);
            request.setArgs(argv.toArray(new String[0]));
            request.setEnv(env);
            return client.runInTerminal(request);
        }

        // --- IDebugProtocolServer -------------------------------------------------------------------

        @Override
        public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
            initializeArgs = args;
            record("initialize");
            Capabilities capabilities = new Capabilities();
            if (exceptionInfo != null) {
                capabilities.setSupportsExceptionInfoRequest(true);
            }
            CompletableFuture<Capabilities> reply = CompletableFuture.completedFuture(capabilities);
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
            if (breakpointFailure != null) {
                return refused(breakpointFailure);
            }
            SetBreakpointsResponse response = new SetBreakpointsResponse();
            var answer = breakpointAnswer;
            if (answer != null) {
                response.setBreakpoints(answer.apply(args).toArray(new org.eclipse.lsp4j.debug.Breakpoint[0]));
            }
            return CompletableFuture.completedFuture(response);
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
            List<org.eclipse.lsp4j.debug.Thread> all = new java.util.ArrayList<>();
            all.add(thread(7, "main"));
            for (int id : runningThreads) {
                all.add(thread(id, "worker-" + id));
            }
            ThreadsResponse response = new ThreadsResponse();
            response.setThreads(all.toArray(new org.eclipse.lsp4j.debug.Thread[0]));
            return CompletableFuture.completedFuture(response);
        }

        private static org.eclipse.lsp4j.debug.Thread thread(int id, String name) {
            org.eclipse.lsp4j.debug.Thread thread = new org.eclipse.lsp4j.debug.Thread();
            thread.setId(id);
            thread.setName(name);
            return thread;
        }

        @Override
        public CompletableFuture<StackTraceResponse> stackTrace(StackTraceArguments args) {
            record("stackTrace");
            if (runningThreads.contains(args.getThreadId())) {
                return refused("Thread " + args.getThreadId() + " is not suspended");
            }
            StackTraceResponse response = new StackTraceResponse();
            List<StackFrame> scripted = frames;
            if (scripted != null) {
                response.setStackFrames(scripted.toArray(new StackFrame[0]));
                return CompletableFuture.completedFuture(response);
            }
            StackFrame frame = new StackFrame();
            frame.setId(1);
            frame.setName(first && multiSession ? "root" : "debuggee");
            frame.setLine(frameLine);
            if (framePath != null) {
                org.eclipse.lsp4j.debug.Source source = new org.eclipse.lsp4j.debug.Source();
                source.setPath(framePath);
                frame.setSource(source);
            }
            response.setStackFrames(new StackFrame[] {frame});
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public CompletableFuture<Void> next(NextArguments args) {
            record("next");
            return stepResult();
        }

        @Override
        public CompletableFuture<Void> stepIn(StepInArguments args) {
            record("stepIn");
            return stepResult();
        }

        @Override
        public CompletableFuture<Void> stepOut(StepOutArguments args) {
            record("stepOut");
            return stepResult();
        }

        private CompletableFuture<Void> stepResult() {
            String failure = stepFailure;
            if (failure == null) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.failedFuture(new org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                    new org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                            org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.RequestFailed, failure, null)));
        }

        @Override
        public CompletableFuture<ContinueResponse> continue_(ContinueArguments args) {
            continuedThreads.add(args.getThreadId());
            record("continue");
            ContinueResponse response = new ContinueResponse();
            response.setAllThreadsContinued(allThreadsContinued);
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public CompletableFuture<Void> disconnect(DisconnectArguments args) {
            record("disconnect");
            disconnected.countDown();
            return CompletableFuture.completedFuture(null);
        }
        // --- scripted inspection: scopes, variables, evaluate, setVariable -----------------------------

        /** The reference of the one "Locals" scope every {@code scopes} request answers with. */
        public static final int LOCALS = 1000;

        /** Container reference → its children, as the test scripted them (see {@link #variable}). */
        public final Map<Integer, List<org.eclipse.lsp4j.debug.Variable>> variables =
                new java.util.concurrent.ConcurrentHashMap<>();
        /** Container reference → the length of an int array whose {@code [i]} children are generated. */
        public final Map<Integer, Integer> arrays = new java.util.concurrent.ConcurrentHashMap<>();
        /** Whether a {@code start}/{@code count} request for an array is honoured (else: every element). */
        public volatile boolean pagingHonoured = true;
        /** Every {@code variables} request, in arrival order. */
        public final List<org.eclipse.lsp4j.debug.VariablesArguments> variableRequests = new CopyOnWriteArrayList<>();
        /** When set, every {@code setVariable} is refused with this message. */
        public volatile String setVariableFailure;
        /** When set, {@code stackTrace} answers these frames instead of the single default one. */
        public volatile List<StackFrame> frames;
        /** Extra threads that are listed but not suspended: their {@code stackTrace} is refused. */
        public final List<Integer> runningThreads = new CopyOnWriteArrayList<>();

        /** A child for {@link #variables}; {@code reference > 0} makes it expandable. */
        public static org.eclipse.lsp4j.debug.Variable variable(String name, String value, int reference) {
            org.eclipse.lsp4j.debug.Variable v = new org.eclipse.lsp4j.debug.Variable();
            v.setName(name);
            v.setValue(value);
            v.setVariablesReference(reference);
            return v;
        }

        /** An expandable array child for {@link #variables} that reports its length, as a paging adapter does. */
        public org.eclipse.lsp4j.debug.Variable array(String name, int reference, int length) {
            arrays.put(reference, length);
            org.eclipse.lsp4j.debug.Variable v = variable(name, "int[" + length + "]", reference);
            v.setIndexedVariables(length);
            return v;
        }

        /** A frame for {@link #frames}; {@code path} may be null (or not a file at all). */
        public static StackFrame frame(int id, String name, String path, int line) {
            StackFrame frame = new StackFrame();
            frame.setId(id);
            frame.setName(name);
            frame.setLine(line);
            if (path != null) {
                org.eclipse.lsp4j.debug.Source source = new org.eclipse.lsp4j.debug.Source();
                source.setPath(path);
                frame.setSource(source);
            }
            return frame;
        }

        private static <T> CompletableFuture<T> refused(String message) {
            return CompletableFuture.failedFuture(new org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                    new org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                            org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.RequestFailed, message, null)));
        }

        @Override
        public CompletableFuture<org.eclipse.lsp4j.debug.ScopesResponse> scopes(
                org.eclipse.lsp4j.debug.ScopesArguments args) {
            record("scopes");
            org.eclipse.lsp4j.debug.Scope scope = new org.eclipse.lsp4j.debug.Scope();
            scope.setName("Locals");
            scope.setVariablesReference(LOCALS);
            scope.setExpensive(false);
            org.eclipse.lsp4j.debug.ScopesResponse response = new org.eclipse.lsp4j.debug.ScopesResponse();
            response.setScopes(new org.eclipse.lsp4j.debug.Scope[] {scope});
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public CompletableFuture<org.eclipse.lsp4j.debug.VariablesResponse> variables(
                org.eclipse.lsp4j.debug.VariablesArguments args) {
            variableRequests.add(args);
            record("variables");
            List<org.eclipse.lsp4j.debug.Variable> out = new java.util.ArrayList<>();
            Integer length = arrays.get(args.getVariablesReference());
            if (length != null) {
                int from = 0;
                int to = length;
                if (pagingHonoured && args.getCount() != null && args.getCount() > 0) {
                    from = Math.min(length, args.getStart() == null ? 0 : args.getStart());
                    to = Math.min(length, from + args.getCount());
                }
                for (int i = from; i < to; i++) {
                    out.add(variable("[" + i + "]", Integer.toString(i), 0));
                }
            } else {
                out.addAll(variables.getOrDefault(args.getVariablesReference(), List.of()));
            }
            org.eclipse.lsp4j.debug.VariablesResponse response = new org.eclipse.lsp4j.debug.VariablesResponse();
            response.setVariables(out.toArray(new org.eclipse.lsp4j.debug.Variable[0]));
            return CompletableFuture.completedFuture(response);
        }

        /** Evaluates to {@code val(<expression>)}; an expression starting with {@code bad} is refused. */
        @Override
        public CompletableFuture<org.eclipse.lsp4j.debug.EvaluateResponse> evaluate(
                org.eclipse.lsp4j.debug.EvaluateArguments args) {
            record("evaluate");
            if (args.getExpression().startsWith("bad")) {
                return refused("Cannot evaluate: " + args.getExpression());
            }
            org.eclipse.lsp4j.debug.EvaluateResponse response = new org.eclipse.lsp4j.debug.EvaluateResponse();
            response.setResult("val(" + args.getExpression() + ")");
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public CompletableFuture<org.eclipse.lsp4j.debug.SetVariableResponse> setVariable(
                org.eclipse.lsp4j.debug.SetVariableArguments args) {
            record("setVariable");
            String failure = setVariableFailure;
            if (failure != null) {
                return refused(failure);
            }
            org.eclipse.lsp4j.debug.SetVariableResponse response = new org.eclipse.lsp4j.debug.SetVariableResponse();
            response.setValue(args.getValue());
            return CompletableFuture.completedFuture(response);
        }
    }
}
