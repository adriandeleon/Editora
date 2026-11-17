package com.editora.dap;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Debugger review, the client: a thread dump does not move the inspected child session, a child's per-thread
 * {@code continued} and its exit reach the session, configurationDone waits for method breakpoints too, a child that
 * ends is disposed off its own thread, far memory offsets do not wrap, Set Value with no value in the answer keeps the
 * one typed, and a connect stops retrying once the session is disposed.
 */
class DapClientSessionReviewTest {

    @BeforeAll
    static void english() {
        com.editora.i18n.Messages.init("en");
    }

    private static final Path FILE = Path.of("app.js").toAbsolutePath();

    /** Records what the session reports, in arrival order. */
    private static final class Host implements DapClient.Host {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        /** Whether the thread that delivered {@code onTerminated} had been interrupted. */
        volatile Boolean terminatedInterrupted;

        @Override
        public void onStopped(int threadId, String reason) {
            events.add("stopped:" + threadId);
        }

        @Override
        public void onContinued() {
            events.add("continued");
        }

        @Override
        public void onContinued(int threadId, boolean allThreadsContinued) {
            events.add("continued:" + threadId + ":" + allThreadsContinued);
        }

        @Override
        public void onOutput(String text, String category) {}

        @Override
        public void onTerminated() {
            terminatedInterrupted = Thread.currentThread().isInterrupted();
            events.add("terminated");
        }

        @Override
        public void onError(String message) {
            events.add("error:" + message);
        }

        @Override
        public void onExited(int exitCode) {
            events.add("exited:" + exitCode);
        }

        String next(String what) throws InterruptedException {
            String event = events.poll(10, TimeUnit.SECONDS);
            assertNotNull(event, "never received " + what);
            return event;
        }
    }

    /** Connects to a js-debug-like adapter and returns {root, child} once the child is configured. */
    private static FakeDebugAdapter.Session[] launchWithChild(FakeDebugAdapter adapter, DapClient client)
            throws Exception {
        client.connect(adapter.port(), "pwa-node").get(10, TimeUnit.SECONDS);
        client.launch(Map.of("type", "pwa-node", "request", "launch", "program", FILE.toString()))
                .get(10, TimeUnit.SECONDS);
        FakeDebugAdapter.Session root = adapter.awaitSession();
        FakeDebugAdapter.Session child = adapter.awaitSession();
        assertTrue(child.configured.await(10, TimeUnit.SECONDS), "the child session was never configured");
        root.startDebuggingAnswered.get(10, TimeUnit.SECONDS);
        return new FakeDebugAdapter.Session[] {root, child};
    }

    /** A second child (a worker) with main thread 9, configured, its thread listed with the first's. */
    private static FakeDebugAdapter.Session secondChild(
            FakeDebugAdapter adapter, DapClient client, FakeDebugAdapter.Session root) throws Exception {
        root.requestChild();
        FakeDebugAdapter.Session second = adapter.awaitSession();
        second.mainThread = 9;
        assertTrue(second.configured.await(10, TimeUnit.SECONDS), "the second child was never configured");
        assertEquals(2, client.threads().get(10, TimeUnit.SECONDS).size());
        return second;
    }

    @Test
    void aThreadDumpsStacksLeaveTheInspectionWithTheSessionThatStopped() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            Host host = new Host();
            DapClient client = new DapClient(host);
            FakeDebugAdapter.Session[] sessions = launchWithChild(adapter, client);
            FakeDebugAdapter.Session first = sessions[1];
            FakeDebugAdapter.Session second = secondChild(adapter, client, sessions[0]);
            first.stop(7, "breakpoint");
            assertEquals("stopped:7", host.next("the stop"));

            // Thread Dump / Parallel Stacks ask every thread, the other session's last.
            client.stackTrace(7, false).get(10, TimeUnit.SECONDS);
            client.stackTrace(9, false).get(10, TimeUnit.SECONDS);
            second.awaitRequest("stackTrace");

            client.scopes(1).get(10, TimeUnit.SECONDS);
            first.awaitRequest("scopes");
            assertFalse(second.requests.contains("scopes"), "the stopped thread's frames are the first child's");

            client.stackTrace(9).get(10, TimeUnit.SECONDS); // the user picks thread 9: it is inspected now
            client.scopes(1).get(10, TimeUnit.SECONDS);
            second.awaitRequest("scopes");
            client.dispose();
        }
    }

    @Test
    void aChildsContinuedNamesItsThreadAndItsExitCodeReachesTheSession() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            Host host = new Host();
            DapClient client = new DapClient(host);
            FakeDebugAdapter.Session[] sessions = launchWithChild(adapter, client);
            FakeDebugAdapter.Session first = sessions[1];

            first.continued(7, false);
            assertEquals("continued:7:false", host.next("the one thread's continued"));
            first.continued(7, true);
            assertEquals("continued:7:true", host.next("the only child's: the whole session ran on"));

            FakeDebugAdapter.Session second = secondChild(adapter, client, sessions[0]);
            second.stop(9, "breakpoint");
            assertEquals("stopped:9", host.next("the worker's stop"));
            first.continued(7, true);
            assertEquals(
                    "continued:7:false",
                    host.next("a child's 'all threads' is its own threads: the worker is still stopped"));
            assertNull(host.events.poll(200, TimeUnit.MILLISECONDS), "nothing said the whole program ran");

            second.exited(5);
            first.exited(3);
            assertEquals("exited:3", host.next("the program's exit code"), "a worker's exit is not the program's");
            client.dispose();
        }
    }

    @Test
    void aChildThatEndsIsDisposedOffItsOwnThread() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            adapter.holdDisconnect = new CountDownLatch(1); // the child's disconnect is not answered
            Host host = new Host();
            DapClient client = new DapClient(host);
            FakeDebugAdapter.Session child = launchWithChild(adapter, client)[1];

            long start = System.nanoTime();
            child.terminate();
            assertEquals("terminated", host.next("the session's end"));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(Boolean.FALSE, host.terminatedInterrupted, "the end was reported on an interrupted thread");
            assertTrue(millis < 2_000, "the end waited for the child's disconnect: " + millis + " ms");
            adapter.holdDisconnect.countDown();
            client.dispose();
        }
    }

    @Test
    void configurationDoneWaitsForTheMethodBreakpointsToo() throws Exception {
        try (FakeDebugAdapter adapter = FakeDebugAdapter.javaDebug()) { // initialized once the capabilities are in
            adapter.memberBreakpoints = true;
            adapter.memberBreakpointGate = new CompletableFuture<>();
            DapClient client = new DapClient(new Host());
            client.setMemberBreakpoints(List.of(new DapModels.MemberBreakpoint(false, "demo.App#run", "", null, null)));
            client.connect(adapter.port(), "java").get(10, TimeUnit.SECONDS);
            client.launch(Map.of("request", "launch")).get(10, TimeUnit.SECONDS);
            FakeDebugAdapter.Session session = adapter.awaitSession();
            session.awaitRequest("setFunctionBreakpoints");

            assertFalse(
                    session.configured.await(300, TimeUnit.MILLISECONDS),
                    "the program ran before its method breakpoint was in place: " + session.requests);
            adapter.memberBreakpointGate.complete(null);
            assertTrue(session.configured.await(10, TimeUnit.SECONDS));
            client.dispose();
        }
    }

    @Test
    void aMemoryOffsetPastTwoGigabytesIsAddedToTheAddressNotWrapped() throws Exception {
        assertEquals(new DapClient.MemoryAddress("0x2000", 16), DapClient.memoryAddress("0x2000", 16));
        assertEquals(new DapClient.MemoryAddress("0x2000", -16), DapClient.memoryAddress("0x2000", -16));
        assertEquals(new DapClient.MemoryAddress("0x100002000", 0), DapClient.memoryAddress("0x2000", 1L << 32));
        assertEquals(new DapClient.MemoryAddress("0x80001000", 0), DapClient.memoryAddress("4096", 1L << 31));
        assertNull(DapClient.memoryAddress("frame:3/x", 1L << 32), "an opaque reference has no address to add to");
        assertNull(DapClient.memoryAddress("0x10", -(1L << 32)), "below address zero");

        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            adapter.lowLevel = true;
            DapClient client = new DapClient(new Host());
            client.connect(adapter.port(), "lldb").get(10, TimeUnit.SECONDS);
            FakeDebugAdapter.Session session = adapter.awaitSession();

            client.readMemory("0x2000", 1L << 32, 4).get(10, TimeUnit.SECONDS);
            assertEquals("0x100002000", session.memoryReads.get(0).getMemoryReference());
            assertEquals(0, session.memoryReads.get(0).getOffset());
            ExecutionException refused = assertThrows(
                    ExecutionException.class,
                    () -> client.readMemory("opaque", 1L << 32, 4).get(10, TimeUnit.SECONDS));
            assertTrue(
                    refused.getCause().getMessage().contains("too large"),
                    refused.getCause().getMessage());
            assertEquals(1, session.memoryReads.size(), "nothing was read from a wrapped offset");
            client.dispose();
        }
    }

    @Test
    void setValueWithNoValueInTheAnswerKeepsTheValueTyped() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            adapter.setVariableWithoutValue = true;
            DapClient client = new DapClient(new Host());
            client.connect(adapter.port(), "java").get(10, TimeUnit.SECONDS);

            assertEquals("42", client.setVariable(1000, "x", "42").get(10, TimeUnit.SECONDS));
            client.dispose();
        }
    }

    @Test
    void aConnectStopsRetryingOnceTheSessionIsDisposed() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort(); // nothing listens there once it is closed
        }
        DapClient client = new DapClient(new Host());
        int port = closed;
        CompletableFuture<CompletableFuture<?>> connecting =
                CompletableFuture.supplyAsync(() -> client.connect(port, "java"));
        Thread.sleep(150); // a few attempts in
        client.dispose();

        CompletableFuture<?> connected = connecting.get(10, TimeUnit.SECONDS);
        // Cancelled because of Stop — not, two seconds of retries later, "could not connect".
        assertThrows(CancellationException.class, () -> connected.get(1, TimeUnit.SECONDS));
    }
}
