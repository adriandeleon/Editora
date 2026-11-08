package com.editora.dap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ending a session ends the program it launched, and only detaches from one it attached to. */
class DapClientDisconnectTest {

    private static final class RecordingServer implements IDebugProtocolServer {
        final List<Boolean> terminate = new ArrayList<>();

        @Override
        public CompletableFuture<Void> disconnect(DisconnectArguments args) {
            terminate.add(args.getTerminateDebuggee());
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

    private static DapClient connected(RecordingServer server) throws Exception {
        DapClient c = new DapClient(SILENT_HOST);
        var f = DapClient.class.getDeclaredField("server");
        f.setAccessible(true);
        f.set(c, server);
        return c;
    }

    @Test
    void aLaunchEndsTheProgramAndAnAttachDetaches() throws Exception {
        RecordingServer launched = new RecordingServer();
        connected(launched).dispose();
        assertEquals(List.of(true), launched.terminate);

        // debugpy and vscode-js-debug both end an attached program on terminateDebuggee.
        RecordingServer attached = new RecordingServer();
        DapClient c = connected(attached);
        c.setTerminateOnDisconnect(false);
        c.dispose();
        assertEquals(List.of(false), attached.terminate);
    }
}
