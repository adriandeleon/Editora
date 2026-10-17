package com.editora.dap;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.StackFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DapClient}'s transport: requests must not be written on the thread that issues them (Resume, Step,
 * evaluate and the variables requests come from the FX thread), must still arrive in order and with the
 * final {@code disconnect}, and a stop must not fetch a whole runaway stack.
 */
class DapClientTransportTest {

    private static final DapClient.Host NO_HOST = new DapClient.Host() {
        @Override
        public void onStopped(int threadId, String reason) {}

        @Override
        public void onContinued() {}

        @Override
        public void onOutput(String text, String category) {}

        @Override
        public void onTerminated() {}

        @Override
        public void onError(String message) {}
    };

    /**
     * An adapter that accepted the connection and then reads nothing — paused in its own debugger, or
     * wedged. 32 MB of requests is more than the loopback socket buffers hold, so a write made on the
     * calling thread blocks part-way through and never returns.
     */
    @Test
    void requestsToAnAdapterThatIsNotReadingDoNotBlockTheCaller() throws Exception {
        try (ServerSocket deaf = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            List<Socket> accepted = new ArrayList<>();
            Thread acceptor = new Thread(() -> {
                try {
                    accepted.add(deaf.accept()); // accepted, and never read from
                } catch (java.io.IOException ignored) {
                    // the test closed the listener
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            DapClient client = new DapClient(NO_HOST);
            String expression = "x".repeat(100_000);
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                    client.connect(deaf.getLocalPort(), "test"); // sends initialize; nobody answers
                    for (int i = 0; i < 320; i++) {
                        client.evaluate(expression, 1, "repl");
                    }
                    client.resume(1);
                });
            } finally {
                // Bounded too: the queued disconnect is given a moment, not the adapter's whole silence.
                assertTimeoutPreemptively(Duration.ofSeconds(30), client::dispose);
                for (Socket socket : accepted) {
                    socket.close();
                }
            }
        }
    }

    /** dispose() closes the socket right after {@code disconnect}; the request must be on the wire first. */
    @Test
    void disposeStillDeliversDisconnectBeforeClosingTheSocket() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            DapClient client = new DapClient(NO_HOST);
            client.connect(adapter.port(), "test").get(10, TimeUnit.SECONDS);
            client.launch(Map.of("type", "test", "request", "launch")).get(10, TimeUnit.SECONDS);
            FakeDebugAdapter.Session session = adapter.awaitSession();

            client.dispose();

            session.awaitRequest("disconnect");
        }
    }

    /** Requests issued back to back arrive in the order they were issued. */
    @Test
    void requestsArriveInTheOrderTheyWereIssued() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            DapClient client = new DapClient(NO_HOST);
            client.connect(adapter.port(), "test").get(10, TimeUnit.SECONDS);
            client.launch(Map.of("type", "test", "request", "launch")).get(10, TimeUnit.SECONDS);
            FakeDebugAdapter.Session session = adapter.awaitSession();
            // The handshake is not over when launch() answers: the adapter sends `initialized` on its own
            // thread and the client replies with configurationDone from its reader thread. Counted before
            // that reply lands, it would turn up among the requests below.
            session.awaitRequest("configurationDone");
            int before = session.requests.size();

            List<CompletableFuture<?>> sent = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                sent.add(client.next(1));
                sent.add(client.threads());
                sent.add(client.stackTrace(1));
            }
            CompletableFuture.allOf(sent.toArray(new CompletableFuture[0])).get(20, TimeUnit.SECONDS);

            List<String> received = List.copyOf(session.requests.subList(before, session.requests.size()));
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                expected.addAll(List.of("next", "threads", "stackTrace"));
            }
            assertEquals(expected, received);
            client.dispose();
        }
    }

    // --- stack traces ----------------------------------------------------------------------------------

    private static List<StackFrame> deepStack(int depth) {
        List<StackFrame> frames = new ArrayList<>(depth);
        for (int i = 0; i < depth; i++) {
            StackFrame frame = new StackFrame();
            frame.setId(i + 1);
            frame.setName("recurse");
            frame.setLine(10);
            frames.add(frame);
        }
        return frames;
    }

    /** A runaway recursion: each stop and each step used to fetch and map every one of its frames. */
    @Test
    void aStopFetchesOnlyTheInnermostFramesFromAnAdapterThatPages() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            adapter.delayedStackTraceLoading = true;
            DapClient client = new DapClient(NO_HOST);
            client.connect(adapter.port(), "test").get(10, TimeUnit.SECONDS);
            client.launch(Map.of("type", "test", "request", "launch")).get(10, TimeUnit.SECONDS);
            FakeDebugAdapter.Session session = adapter.awaitSession();
            session.frames = deepStack(20_000);

            var frames = client.stackTrace(1).get(10, TimeUnit.SECONDS);

            var request = session.stackTraceRequests.get(session.stackTraceRequests.size() - 1);
            assertEquals(0, request.getStartFrame());
            assertEquals(DapClient.STACK_FRAME_LIMIT, request.getLevels());
            assertEquals(DapClient.STACK_FRAME_LIMIT, frames.size(), "not all 20,000");
            assertEquals(1, frames.get(0).id(), "the innermost frame comes first");
            client.dispose();
        }
    }

    /** Without the capability the arguments are not the adapter's to honour: ask as before. */
    @Test
    void anAdapterThatDoesNotPageIsAskedAsBefore() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            DapClient client = new DapClient(NO_HOST);
            client.connect(adapter.port(), "test").get(10, TimeUnit.SECONDS);
            client.launch(Map.of("type", "test", "request", "launch")).get(10, TimeUnit.SECONDS);
            FakeDebugAdapter.Session session = adapter.awaitSession();
            session.frames = deepStack(1_500);

            var frames = client.stackTrace(1).get(10, TimeUnit.SECONDS);

            var request = session.stackTraceRequests.get(session.stackTraceRequests.size() - 1);
            assertNull(request.getLevels());
            assertNull(request.getStartFrame());
            assertEquals(1_500, frames.size());
            client.dispose();
        }
    }

    @Test
    void pagingFollowsTheAdaptersCapability() {
        assertFalse(DapClient.pagesStackTraces(null));
        assertFalse(DapClient.pagesStackTraces(new Capabilities()));
        Capabilities paging = new Capabilities();
        paging.setSupportsDelayedStackTraceLoading(true);
        assertTrue(DapClient.pagesStackTraces(paging));
    }

    /**
     * Ending a session closes its socket under the DAP reader, which lsp4j logged at INFO with a stack trace —
     * and Editora's Debug Log records INFO, so every Stop and every closed window added a
     * {@code SocketException: Socket closed} trace there. The reader of a disposed session now ends quietly.
     */
    @Test
    void disposingASessionLogsNoStreamClosedTrace() throws Exception {
        java.util.logging.Logger lsp4j =
                java.util.logging.Logger.getLogger("org.eclipse.lsp4j.jsonrpc.json.StreamMessageProducer");
        List<java.util.logging.LogRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        lsp4j.addHandler(handler);
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            DapClient client = new DapClient(NO_HOST);
            client.connect(adapter.port(), "test").get(10, TimeUnit.SECONDS);
            client.launch(Map.of("type", "test", "request", "launch")).get(10, TimeUnit.SECONDS);
            FakeDebugAdapter.Session session = adapter.awaitSession();
            assertTrue(session.configured.await(10, TimeUnit.SECONDS));

            client.dispose();

            // The reader has ended once no client session thread is still reading.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (clientReaderAlive() && records.isEmpty()) {
                assertTrue(System.nanoTime() < deadline, "the client's DAP reader never ended");
                Thread.onSpinWait();
            }
            assertTrue(
                    records.isEmpty(),
                    () -> "lsp4j logged the stream close: "
                            + records.stream()
                                    .map(java.util.logging.LogRecord::getMessage)
                                    .toList());
        } finally {
            lsp4j.removeHandler(handler);
        }
    }

    /** Whether a {@code dap-session} thread is still in lsp4j's read loop. */
    private static boolean clientReaderAlive() {
        for (var entry : Thread.getAllStackTraces().entrySet()) {
            if (!entry.getKey().getName().equals("dap-session")) {
                continue;
            }
            for (StackTraceElement frame : entry.getValue()) {
                if (frame.getClassName().endsWith("StreamMessageProducer")
                        && frame.getMethodName().equals("listen")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Against java-debug's order — {@code initialized} only once the launch has created the session, and
     * configuration refused before that — the handshake still installs the breakpoints and finishes.
     */
    @Test
    void theHandshakeConfiguresAJavaDebugSessionOnceItsLaunchHasCreatedIt() throws Exception {
        try (FakeDebugAdapter adapter = FakeDebugAdapter.javaDebug()) {
            adapter.sessionBreakpointAnswer = args ->
                    List.of(FakeDebugAdapter.Session.breakpoint(1, true, args.getBreakpoints()[0].getLine(), null));
            DapClient client = new DapClient(NO_HOST);
            client.setBreakpoints(List.of(new DapModels.FileBreakpoints(
                    java.nio.file.Path.of("/work/App.java").toAbsolutePath(),
                    List.of(new DapModels.LineBreakpoint(4, null, null)))));
            client.setExceptionFilters(List.of("uncaught"));
            client.connect(adapter.port(), "java").get(10, TimeUnit.SECONDS);
            FakeDebugAdapter.Session session = adapter.awaitSession();

            client.launch(Map.of("type", "java", "request", "launch")).get(10, TimeUnit.SECONDS);

            assertTrue(session.configured.await(10, TimeUnit.SECONDS), "configurationDone was accepted");
            assertEquals(
                    List.of("initialize", "launch", "setBreakpoints", "setExceptionBreakpoints", "configurationDone"),
                    session.requests.subList(0, 5));
            client.dispose();
        }
    }
}
