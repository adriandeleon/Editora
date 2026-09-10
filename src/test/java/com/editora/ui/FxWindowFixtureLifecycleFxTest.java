package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class FxWindowFixtureLifecycleFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void disposalStopsOwnedWorkersDeletesConfigurationAndIsIdempotent() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            ExecutorService saveWorker = FxTestSupport.field(workflows, "autoSaveExecutor");
            ExecutorService loadWorkers = FxTestSupport.field(workflows, "fileLoadExecutor");
            Object configWriter = FxTestSupport.field(fx.shared, "writer");
            ExecutorService configWorker = FxTestSupport.field(configWriter, "io");
            Object historyService = FxTestSupport.field(fx.shared, "historyService");
            ExecutorService historyWorker = FxTestSupport.field(historyService, "exec");
            Path configDir = fx.configDir;

            async.awaitWorker(saveWorker);
            async.awaitWorker(loadWorkers);
            async.awaitWorker(configWorker);
            async.awaitWorker(historyWorker);
            async.awaitFx();

            fx.dispose();

            assertTrue(saveWorker.isShutdown());
            assertTrue(loadWorkers.isShutdown());
            assertTrue(configWorker.isShutdown());
            assertTrue(historyWorker.isShutdown());
            assertFalse(Files.exists(configDir));
            fx.dispose();
        }
    }
}
