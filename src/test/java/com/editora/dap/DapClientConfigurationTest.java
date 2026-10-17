package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * java-debug sends its {@code initialized} event only after the launch is answered, and until then refuses
 * {@code setBreakpoints} with "Empty debug session." A breakpoint toggled in that window was sent anyway and
 * shown as rejected (D3). It must be held and installed with the configuration. Also: java-debug's
 * {@code processid} event reaches the host (B2).
 */
class DapClientConfigurationTest {

    private static final Path FILE = Path.of("/work/Main.java");

    private static final class Host implements DapClient.Host {
        final BlockingQueue<List<DapModels.BreakpointStatus>> reports = new LinkedBlockingQueue<>();
        final BlockingQueue<Long> pids = new LinkedBlockingQueue<>();

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

        @Override
        public void onBreakpointStatus(Path file, List<DapModels.BreakpointStatus> statuses, boolean whole) {
            reports.add(List.copyOf(statuses));
        }

        @Override
        public void onProcessId(long pid) {
            pids.add(pid);
        }
    }

    private static DapModels.FileBreakpoints at(int line0) {
        return new DapModels.FileBreakpoints(FILE, List.of(new DapModels.LineBreakpoint(line0, null, null)));
    }

    @Test
    void aBreakpointSetWhileTheAdapterIsStillStartingIsInstalledWithTheConfigurationNotRefused() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            adapter.initializedAfterLaunch = true;
            adapter.launchGate = new CompletableFuture<>();
            adapter.sessionBreakpointAnswer = args -> List.of(FakeDebugAdapter.Session.breakpoint(1, true, 6, null));
            Host host = new Host();
            DapClient client = new DapClient(host);
            client.setBreakpoints(List.of(at(2)));
            client.connect(adapter.port(), "java").get(10, TimeUnit.SECONDS);
            FakeDebugAdapter.Session session = adapter.awaitSession();
            CompletableFuture<Void> launched = client.launch(Map.of("type", "java", "request", "launch"));
            session.awaitRequest("launch");

            // toggled while java-debug is still starting the program: only remembered, nothing on the wire
            client.sendSetBreakpoints(at(5)).get(10, TimeUnit.SECONDS);
            assertEquals(List.of(), session.breakpoints, "nothing is sent before the adapter is configured");

            adapter.launchGate.complete(null);
            launched.get(10, TimeUnit.SECONDS);
            assertTrue(session.configured.await(10, TimeUnit.SECONDS));
            assertEquals(1, session.breakpoints.size(), "one setBreakpoints for the file, with the latest set");
            assertEquals(6, session.breakpoints.get(0).getBreakpoints()[0].getLine());
            List<DapModels.BreakpointStatus> report = host.reports.poll(10, TimeUnit.SECONDS);
            assertNotNull(report);
            assertTrue(report.get(0).verified(), "verified, never shown as rejected: " + report);

            // configured from here on: a change goes on the wire at once
            client.sendSetBreakpoints(at(7)).get(10, TimeUnit.SECONDS);
            assertEquals(2, session.breakpoints.size());
            assertEquals(8, session.breakpoints.get(1).getBreakpoints()[0].getLine());
            client.dispose();
        }
    }

    @Test
    void javaDebugsProcessIdEventReachesTheHost() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            Host host = new Host();
            DapClient client = new DapClient(host);
            client.connect(adapter.port(), "java").get(10, TimeUnit.SECONDS);
            FakeDebugAdapter.Session session = adapter.awaitSession();

            session.processId(4242);

            assertEquals(4242L, host.pids.poll(10, TimeUnit.SECONDS));
            client.dispose();
        }
    }
}
