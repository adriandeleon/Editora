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
    private static final class RecordingListener implements DapManager.Listener {
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

                assertEquals(
                        DapManager.State.RUNNING,
                        FxTestSupport.callOnFx(manager::state),
                        step[0] + " must leave the suspended state");
                session.awaitRequest(step[1]);
            }
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
