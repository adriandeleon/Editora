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
}
