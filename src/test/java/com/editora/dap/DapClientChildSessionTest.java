package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JavaScript debugging against an adapter that behaves like vscode-js-debug: the program is never debugged
 * on the connection that launched it. Before {@code startDebugging} was implemented the reverse request
 * failed (lsp4j's default throws), no second connection was ever made, and the session sat in RUNNING with
 * breakpoints that could not hit.
 */
class DapClientChildSessionTest {

    private static final Path FILE = Path.of("/work/app.js");

    /** Records what reaches the one {@link DapClient.Host} the manager sees. */
    private static final class RecordingHost implements DapClient.Host {
        final BlockingQueue<String> stops = new LinkedBlockingQueue<>();
        final BlockingQueue<String> output = new LinkedBlockingQueue<>();
        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        final CountDownLatch terminated = new CountDownLatch(1);
        final CountDownLatch continued = new CountDownLatch(1);

        @Override
        public void onStopped(int threadId, String reason) {
            stops.add(threadId + ":" + reason);
        }

        @Override
        public void onContinued() {
            continued.countDown();
        }

        @Override
        public void onOutput(String text, String category) {
            output.add(text);
        }

        @Override
        public void onTerminated() {
            terminated.countDown();
        }

        @Override
        public void onError(String message) {
            errors.add(message);
        }
    }

    private static DapModels.FileBreakpoints breakpointAt(int line) {
        return new DapModels.FileBreakpoints(FILE, List.of(new DapModels.LineBreakpoint(line, null, null)));
    }

    private static <T> T next(BlockingQueue<T> queue, String what) throws InterruptedException {
        T value = queue.poll(10, TimeUnit.SECONDS);
        assertNotNull(value, "never received " + what);
        return value;
    }

    /** Connects, launches, and returns once the adapter's child session is configured. */
    private static FakeDebugAdapter.Session[] launch(FakeDebugAdapter adapter, DapClient client) throws Exception {
        client.setBreakpoints(List.of(breakpointAt(2)));
        client.setExceptionFilters(List.of("uncaught"));
        client.connect(adapter.port(), "pwa-node").get(10, TimeUnit.SECONDS);
        client.launch(Map.of("type", "pwa-node", "request", "launch", "program", FILE.toString()))
                .get(10, TimeUnit.SECONDS);
        FakeDebugAdapter.Session root = adapter.awaitSession();
        FakeDebugAdapter.Session child = adapter.awaitSession();
        assertTrue(child.configured.await(10, TimeUnit.SECONDS), "the child session was never configured");
        child.awaitRequest("launch");
        root.startDebuggingAnswered.get(10, TimeUnit.SECONDS); // the reverse request succeeded
        return new FakeDebugAdapter.Session[] {root, child};
    }

    @Test
    void theAdapterIsToldChildSessionsAreSupported() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            DapClient client = new DapClient(new RecordingHost());
            FakeDebugAdapter.Session[] sessions = launch(adapter, client);

            assertTrue(sessions[0].initializeArgs.getSupportsStartDebuggingRequest());
            client.dispose();
        }
    }

    @Test
    void aChildSessionIsOpenedWithThePendingTargetAndTheBreakpoints() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            DapClient client = new DapClient(new RecordingHost());
            FakeDebugAdapter.Session child = launch(adapter, client)[1];

            assertEquals(
                    FakeDebugAdapter.PENDING_TARGET,
                    child.launchArgs.get("__pendingTargetId"),
                    "the child must launch with the configuration the adapter supplied");
            assertFalse(child.attached, "a 'launch' request stays a launch");
            assertEquals(1, child.breakpoints.size(), "the debuggee's session needs the breakpoints");
            assertEquals(FILE.toString(), child.breakpoints.get(0).getSource().getPath());
            assertEquals(3, child.breakpoints.get(0).getBreakpoints()[0].getLine(), "0-based line 2 is DAP line 3");
            assertEquals(
                    List.of("uncaught"),
                    List.of(child.exceptionBreakpoints.get(0).getFilters()));
            assertEquals(1, client.childCount());
            client.dispose();
        }
    }

    @Test
    void stopsInspectionAndSteppingFollowTheChildSession() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            RecordingHost host = new RecordingHost();
            DapClient client = new DapClient(host);
            FakeDebugAdapter.Session[] sessions = launch(adapter, client);
            FakeDebugAdapter.Session root = sessions[0];
            FakeDebugAdapter.Session child = sessions[1];

            child.stop(7, "breakpoint");
            assertEquals("7:breakpoint", next(host.stops, "the child's stop"));

            var frames = client.stackTrace(7).get(10, TimeUnit.SECONDS);
            assertEquals("debuggee", frames.get(0).name(), "the call stack must come from the session that stopped");
            assertEquals(7, client.threads().get(10, TimeUnit.SECONDS).get(0).id());

            client.next(7);
            child.awaitRequest("next");
            client.stepIn(7);
            child.awaitRequest("stepIn");
            client.stepOut(7);
            child.awaitRequest("stepOut");
            client.resume(7);
            child.awaitRequest("continue");
            for (String request : List.of("stackTrace", "threads", "next", "stepIn", "stepOut", "continue")) {
                assertFalse(root.requests.contains(request), "the root session debugs nothing, yet got " + request);
            }

            child.output("hello from the debuggee\n");
            assertEquals("hello from the debuggee\n", next(host.output, "the child's output"));
            client.dispose();
        }
    }

    @Test
    void aBreakpointChangedWhileRunningReachesTheChildSession() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            DapClient client = new DapClient(new RecordingHost());
            FakeDebugAdapter.Session child = launch(adapter, client)[1];

            client.sendSetBreakpoints(breakpointAt(9)).get(10, TimeUnit.SECONDS);
            child.awaitRequests("setBreakpoints", 2); // the initial set, then the live change
            assertEquals(2, child.breakpoints.size());
            assertEquals(10, child.breakpoints.get(1).getBreakpoints()[0].getLine());
            client.dispose();
        }
    }

    @Test
    void theSessionEndsWhenItsOnlyDebuggeeDoes() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            RecordingHost host = new RecordingHost();
            DapClient client = new DapClient(host);
            FakeDebugAdapter.Session child = launch(adapter, client)[1];

            child.terminate();

            assertTrue(host.terminated.await(10, TimeUnit.SECONDS), "the last child ending must end the session");
            assertEquals(0, client.childCount());
            client.dispose();
        }
    }

    /**
     * Measured against the real adapter: when the program exits, the root session's {@code terminated}
     * overtakes the child's last {@code output}. Ending on the root's event disposed the child with the
     * program's final lines still unread.
     */
    @Test
    void theRootTerminatingFirstDoesNotCutOffTheChildsLastOutput() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            RecordingHost host = new RecordingHost();
            DapClient client = new DapClient(host);
            FakeDebugAdapter.Session[] sessions = launch(adapter, client);

            sessions[0].terminate(); // the root says it is over…
            sessions[0].awaitDelivered();
            assertEquals(1, host.terminated.getCount(), "…but the debuggee's session has not finished speaking");

            sessions[1].output("last words\n");
            sessions[1].terminate();

            assertEquals("last words\n", next(host.output, "the child's final output"));
            assertTrue(host.terminated.await(10, TimeUnit.SECONDS));
            client.dispose();
        }
    }

    /** A child that never reports its own end must not keep a terminated session alive. */
    @Test
    void aTerminatedRootEndsTheSessionEvenIfAChildNeverSaysSo() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            RecordingHost host = new RecordingHost();
            DapClient client = new DapClient(host);
            FakeDebugAdapter.Session[] sessions = launch(adapter, client);

            sessions[0].terminate();

            assertTrue(host.terminated.await(10, TimeUnit.SECONDS), "the bounded wait for children must expire");
            client.dispose();
        }
    }

    /** The protocol's telemetry category is not console text (js-debug sends "js-debug/launch" this way). */
    @Test
    void telemetryOutputIsNotShown() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            RecordingHost host = new RecordingHost();
            DapClient client = new DapClient(host);
            FakeDebugAdapter.Session[] sessions = launch(adapter, client);

            sessions[0].output("js-debug/launch", "telemetry");
            sessions[0].output("node ./app.js\n", "console");

            assertEquals("node ./app.js\n", next(host.output, "the console line"));
            assertTrue(host.output.isEmpty());
            client.dispose();
        }
    }

    @Test
    void disposingTheSessionTearsItsChildrenDown() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            RecordingHost host = new RecordingHost();
            DapClient client = new DapClient(host);
            FakeDebugAdapter.Session[] sessions = launch(adapter, client);

            client.dispose();

            assertTrue(sessions[1].disconnected.await(10, TimeUnit.SECONDS), "the child must be disconnected too");
            assertTrue(sessions[0].disconnected.await(10, TimeUnit.SECONDS));
            assertEquals(0, client.childCount());
            assertEquals(1, host.terminated.getCount(), "a deliberate dispose is not a termination event");
        }
    }

    /** An adapter that debugs on its first connection (java-debug) is untouched: no child, same routing. */
    @Test
    void aSingleSessionAdapterKeepsDebuggingOnItsOnlyConnection() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            RecordingHost host = new RecordingHost();
            DapClient client = new DapClient(host);
            client.setBreakpoints(List.of(breakpointAt(2)));
            client.connect(adapter.port(), "java").get(10, TimeUnit.SECONDS);
            client.launch(Map.of("request", "launch")).get(10, TimeUnit.SECONDS);
            FakeDebugAdapter.Session only = adapter.awaitSession();
            assertTrue(only.configured.await(10, TimeUnit.SECONDS));

            only.stop(7, "breakpoint");
            assertEquals("7:breakpoint", next(host.stops, "the stop"));
            client.next(7);
            only.awaitRequest("next");
            assertEquals(1, adapter.sessionCount());
            assertEquals(0, client.childCount());
            client.dispose();
        }
    }

    /**
     * vscode-js-debug listens on {@code localhost}, which resolves to {@code ::1} first on many systems.
     * Connecting only to {@code 127.0.0.1} reported a healthy adapter as unreachable.
     */
    @Test
    void anAdapterListeningOnTheIpv6LoopbackIsReached() throws Exception {
        FakeDebugAdapter adapter;
        try {
            adapter = new FakeDebugAdapter(true, java.net.InetAddress.getByName("::1"));
        } catch (java.io.IOException noIpv6) {
            org.junit.jupiter.api.Assumptions.abort("no IPv6 loopback on this machine");
            return;
        }
        try (adapter) {
            DapClient client = new DapClient(new RecordingHost());
            FakeDebugAdapter.Session[] sessions = launch(adapter, client);

            assertEquals(FakeDebugAdapter.PENDING_TARGET, sessions[1].launchArgs.get("__pendingTargetId"));
            client.dispose();
        }
    }
}
