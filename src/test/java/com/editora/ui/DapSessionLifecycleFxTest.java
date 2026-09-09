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
}
