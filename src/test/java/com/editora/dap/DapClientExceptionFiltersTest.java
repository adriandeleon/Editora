package com.editora.dap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsResponse;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Toggling exception breakpoints during a live session must reach the adapter. The filters are installed by
 * the {@code initialized} event at session start; once that has fired it never reads the field again, so a
 * later change that only writes the field is silently lost — the program then runs straight through an
 * uncaught exception while the status bar reports "exception breakpoints on".
 */
class DapClientExceptionFiltersTest {

    /** Records the requests that actually went on the wire. */
    private static final class RecordingServer implements IDebugProtocolServer {
        final List<List<String>> exceptionFilterCalls = new ArrayList<>();

        @Override
        public CompletableFuture<SetExceptionBreakpointsResponse> setExceptionBreakpoints(
                SetExceptionBreakpointsArguments args) {
            exceptionFilterCalls.add(List.of(args.getFilters()));
            return CompletableFuture.completedFuture(new SetExceptionBreakpointsResponse());
        }

        @Override
        public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments args) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final DapClient.Host SILENT_HOST = new DapClient.Host() {
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

    /** A client wired to {@code server}, as {@code connect}/{@code connectStdio} would leave it. */
    private static DapClient connected(RecordingServer server) throws Exception {
        DapClient c = new DapClient(SILENT_HOST);
        var f = DapClient.class.getDeclaredField("server");
        f.setAccessible(true);
        f.set(c, server);
        return c;
    }

    @Test
    void changingFiltersOnALiveSessionSendsThemToTheAdapter() throws Exception {
        RecordingServer server = new RecordingServer();
        DapClient c = connected(server);
        c.initialized(); // the adapter's initialized event — configuration is now installed
        server.exceptionFilterCalls.clear();

        c.setExceptionFilters(List.of("uncaught")); // the user runs debug.toggleExceptionBreakpoints

        assertEquals(
                1,
                server.exceptionFilterCalls.size(),
                "a live filter change must go on the wire — initialized() has already read the field for the last time");
        assertEquals(List.of("uncaught"), server.exceptionFilterCalls.get(0));
    }

    @Test
    void clearingFiltersOnALiveSessionAlsoSends() throws Exception {
        RecordingServer server = new RecordingServer();
        DapClient c = connected(server);
        c.setExceptionFilters(List.of("uncaught"));
        c.initialized();
        server.exceptionFilterCalls.clear();

        c.setExceptionFilters(List.of()); // toggling back OFF must reach the adapter too

        assertEquals(1, server.exceptionFilterCalls.size(), "turning exception breakpoints off must reach the adapter");
        assertTrue(server.exceptionFilterCalls.get(0).isEmpty());
    }

    @Test
    void beforeTheInitializedEventTheFiltersAreOnlyStaged() throws Exception {
        RecordingServer server = new RecordingServer();
        DapClient c = connected(server);

        c.setExceptionFilters(List.of("uncaught")); // session setup, before the handshake completes

        assertTrue(
                server.exceptionFilterCalls.isEmpty(), "DAP configuration requests only follow the initialized event");

        c.initialized(); // ...which then installs them
        assertEquals(List.of(List.of("uncaught")), server.exceptionFilterCalls);
    }
}
