package com.editora.ui;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.dap.DapClient;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class DapSessionLifecycleFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void callbacksFromAnOldSessionCannotReachAReplacement() throws Exception {
        DapManager manager = new DapManager(null);
        AtomicInteger output = new AtomicInteger();
        manager.setListener(new DapManager.Listener() {
            @Override
            public void onState(DapManager.State state) {}

            @Override
            public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {}

            @Override
            public void onOutput(String text, String category) {
                output.incrementAndGet();
            }

            @Override
            public void onError(String message) {}
        });

        long oldEpoch = (Long) FxTestSupport.call(manager, "beginSession", new Class<?>[] {});
        DapClient.Host oldHost =
                (DapClient.Host) FxTestSupport.call(manager, "sessionHost", new Class<?>[] {long.class}, oldEpoch);
        FxTestSupport.call(manager, "beginSession", new Class<?>[] {});

        oldHost.onOutput("stale", "stdout");
        oldHost.onTerminated();
        FxTestSupport.runOnFx(() -> {});
        assertEquals(0, output.get());

        DapClient lateClient = new DapClient(oldHost);
        assertFalse((Boolean) FxTestSupport.call(
                manager, "publishClient", new Class<?>[] {long.class, DapClient.class}, oldEpoch, lateClient));
        lateClient.dispose();

        manager.shutdown();
        ExecutorService io = FxTestSupport.field(manager, "io");
        assertTrue(io.isShutdown(), "final window disposal must release dap-connect");
    }

    /** Records what the debug UI is told, with a latch per stop so a test can wait for one. */
    private static class RecordingListener implements DapManager.Listener {
        final List<DapManager.State> states = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> output = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.BlockingQueue<Integer> stops = new java.util.concurrent.LinkedBlockingQueue<>();

        @Override
        public void onState(DapManager.State state) {
            states.add(state);
        }

        @Override
        public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {
            stops.add(threadId);
        }

        @Override
        public void onOutput(String text, String category) {
            output.add(text);
        }

        @Override
        public void onError(String message) {}

        void awaitStop() throws InterruptedException {
            assertTrue(stops.poll(10, java.util.concurrent.TimeUnit.SECONDS) != null, "the stop never arrived");
        }
    }

    private static long beginSession(DapManager manager) {
        return (Long) FxTestSupport.call(manager, "beginSession", new Class<?>[] {});
    }

    private static DapClient.Host sessionHost(DapManager manager, long epoch) {
        return (DapClient.Host) FxTestSupport.call(manager, "sessionHost", new Class<?>[] {long.class}, epoch);
    }

    /**
     * Step Over/Into/Out used to leave the session SUSPENDED: the previous stop's execution line and frames
     * stayed on screen for as long as the step ran, and Pause — which only acts while RUNNING — did nothing.
     */
    @Test
    void steppingPutsTheSessionBackIntoRunningUntilTheNextStop() throws Exception {
        try (var adapter = new com.editora.dap.FakeDebugAdapter(false)) {
            DapManager manager = new DapManager(null);
            RecordingListener listener = new RecordingListener();
            manager.setListener(listener);
            long epoch = beginSession(manager);
            DapClient client = new DapClient(sessionHost(manager, epoch));
            client.connect(adapter.port(), "java").get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue((Boolean) FxTestSupport.call(
                    manager, "publishClient", new Class<?>[] {long.class, DapClient.class}, epoch, client));
            var session = adapter.awaitSession();

            String[][] steps = {{"stepOver", "next"}, {"stepInto", "stepIn"}, {"stepOut", "stepOut"}};
            for (String[] step : steps) {
                session.stop(7, "breakpoint");
                listener.awaitStop();
                assertEquals(DapManager.State.SUSPENDED, FxTestSupport.callOnFx(manager::state));

                FxTestSupport.runOnFx(() -> FxTestSupport.invoke(manager, step[0]));

                session.awaitRequest(step[1]);
                awaitState(manager, DapManager.State.RUNNING, step[0] + " must leave the suspended state");
                assertTrue(FxTestSupport.callOnFx(manager::isStepping), "a step is in flight until the next stop");
            }
            session.stop(7, "step");
            listener.awaitStop();
            assertFalse(FxTestSupport.callOnFx(manager::isStepping), "the stop ends the step");
            FxTestSupport.runOnFx(manager::shutdown);
        }
    }

    private static void awaitState(DapManager manager, DapManager.State wanted, String why) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (FxTestSupport.callOnFx(manager::state) != wanted) {
            assertTrue(System.nanoTime() < deadline, why + " (state never became " + wanted + ")");
            Thread.sleep(10);
        }
    }

    /** A manager connected to {@code adapter}, as after a launch; the adapter's side is the returned session. */
    private static com.editora.dap.FakeDebugAdapter.Session connect(
            DapManager manager, com.editora.dap.FakeDebugAdapter adapter) throws Exception {
        long epoch = beginSession(manager);
        DapClient client = new DapClient(sessionHost(manager, epoch));
        client.connect(adapter.port(), "java").get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue((Boolean) FxTestSupport.call(
                manager, "publishClient", new Class<?>[] {long.class, DapClient.class}, epoch, client));
        return adapter.awaitSession();
    }

    /**
     * A step the adapter refuses — java-debug answers "the thread is not suspended" when another thread was
     * picked in the selector — moved nothing. The session flipped to RUNNING anyway, with Continue, Step and
     * the thread selector disabled while the debuggee sat on its breakpoint.
     */
    @Test
    void aStepTheAdapterRefusesLeavesTheSessionSuspended() throws Exception {
        try (var adapter = new com.editora.dap.FakeDebugAdapter(false)) {
            DapManager manager = new DapManager(null);
            List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
            RecordingListener listener = new RecordingListener() {
                @Override
                public void onError(String message) {
                    errors.add(message);
                }
            };
            manager.setListener(listener);
            var session = connect(manager, adapter);
            session.stop(7, "breakpoint");
            listener.awaitStop();

            session.stepFailure = "Failed to step because the thread 'worker' is not suspended in the target VM.";
            FxTestSupport.runOnFx(manager::stepOver);
            session.awaitRequest("next");
            session.awaitDelivered();
            FxTestSupport.runOnFx(() -> {});
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (errors.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            assertEquals(DapManager.State.SUSPENDED, FxTestSupport.callOnFx(manager::state));
            assertFalse(FxTestSupport.callOnFx(manager::isStepping));
            assertEquals(1, errors.size(), "the adapter's refusal is reported");
            assertTrue(errors.get(0).contains("not suspended"), errors.get(0));
            FxTestSupport.runOnFx(manager::shutdown);
        }
    }

    /**
     * java-debug suspends and resumes per thread. With two workers stopped on one breakpoint, Continue
     * resumed only the thread on screen and reported a running session; the other thread stayed suspended
     * with nothing to show it, and Continue kept re-sending the same thread.
     */
    @Test
    void continuingOneOfTwoStoppedThreadsBringsTheOtherForward() throws Exception {
        try (var adapter = new com.editora.dap.FakeDebugAdapter(false)) {
            DapManager manager = new DapManager(null);
            RecordingListener listener = new RecordingListener();
            manager.setListener(listener);
            var session = connect(manager, adapter);
            session.allThreadsContinued = false; // java-debug: only the thread that was asked for
            session.stop(11, "breakpoint", false);
            assertEquals(11, listener.stops.poll(10, java.util.concurrent.TimeUnit.SECONDS));
            session.stop(12, "breakpoint", false);
            assertEquals(12, listener.stops.poll(10, java.util.concurrent.TimeUnit.SECONDS));

            FxTestSupport.runOnFx(manager::resume);
            assertEquals(
                    11,
                    listener.stops.poll(10, java.util.concurrent.TimeUnit.SECONDS),
                    "thread 11 is still stopped and must be shown");
            assertEquals(DapManager.State.SUSPENDED, FxTestSupport.callOnFx(manager::state));
            assertEquals(11, FxTestSupport.callOnFx(manager::currentThreadId));

            FxTestSupport.runOnFx(manager::resume);
            session.awaitRequests("continue", 2);
            session.awaitDelivered();
            FxTestSupport.runOnFx(() -> {});
            assertEquals(List.of(12, 11), List.copyOf(session.continuedThreads));
            awaitState(manager, DapManager.State.RUNNING, "nothing is stopped any more");
            assertTrue(listener.stops.isEmpty());
            FxTestSupport.runOnFx(manager::shutdown);
        }
    }

    /** An adapter that stops every thread at once is resumed with one Continue, as before. */
    @Test
    void continuingAfterAnAllThreadsStopLeavesTheSessionRunning() throws Exception {
        try (var adapter = new com.editora.dap.FakeDebugAdapter(false)) {
            DapManager manager = new DapManager(null);
            RecordingListener listener = new RecordingListener();
            manager.setListener(listener);
            var session = connect(manager, adapter);
            session.stop(1, "breakpoint", true);
            listener.awaitStop();
            session.stop(2, "breakpoint", true);
            listener.awaitStop();

            FxTestSupport.runOnFx(manager::resume);
            session.awaitRequest("continue");
            session.awaitDelivered();
            FxTestSupport.runOnFx(() -> {});
            assertEquals(DapManager.State.RUNNING, FxTestSupport.callOnFx(manager::state));
            assertTrue(listener.stops.isEmpty(), "no thread is left to bring forward");
            FxTestSupport.runOnFx(manager::shutdown);
        }
    }

    /** Output is batched onto the FX thread instead of one runLater + one console append per event. */
    @Test
    void aBurstOfOutputReachesTheConsoleAsOneAppend() throws Exception {
        DapManager manager = new DapManager(null);
        RecordingListener listener = new RecordingListener();
        manager.setListener(listener);
        DapClient.Host host = sessionHost(manager, beginSession(manager));

        // Offered while the FX thread is busy, exactly as a chatty debuggee does.
        FxTestSupport.runOnFx(() -> {
            for (int i = 0; i < 200; i++) {
                host.onOutput("line " + i + "\n", "stdout");
            }
        });
        FxTestSupport.runOnFx(() -> {}); // the drain

        assertEquals(1, listener.output.size(), "200 events must not be 200 appends");
        assertTrue(listener.output.get(0).startsWith("line 0\n"));
        assertTrue(listener.output.get(0).endsWith("line 199\n"));
        FxTestSupport.runOnFx(manager::shutdown);
    }

    /** The program's last lines arrive just before its termination; batching must not drop them. */
    @Test
    void outputStillWaitingWhenTheSessionEndsIsShownFirst() throws Exception {
        DapManager manager = new DapManager(null);
        RecordingListener listener = new RecordingListener();
        manager.setListener(listener);
        DapClient.Host host = sessionHost(manager, beginSession(manager));
        int events = 1000; // more than one pulse delivers

        FxTestSupport.runOnFx(() -> {
            for (int i = 0; i < events; i++) {
                host.onOutput("x", "stdout");
            }
            host.onOutput("goodbye\n", "stdout");
            host.onTerminated();
        });
        FxTestSupport.runOnFx(() -> {});
        FxTestSupport.runOnFx(() -> {});

        String shown = String.join("", listener.output);
        assertEquals(events + "goodbye\n".length(), shown.length(), "nothing may be lost at the end of a session");
        assertTrue(shown.endsWith("goodbye\n"));
        assertEquals(DapManager.State.INACTIVE, listener.states.get(listener.states.size() - 1));
        FxTestSupport.runOnFx(manager::shutdown);
    }
}
