package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A language server is now given a moment to exit by itself, and for that moment a jdtls still holds its
 * Eclipse workspace lock. The manager must not hand that workspace to a replacement until the old process
 * has really gone — or a restart would launch straight into a locked workspace and never finish initialize.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class LspRestartAfterExitFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void theWorkspaceClaimIsHeldUntilTheOldServerHasExited(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("project"));
        Path file = root.resolve("A.java");
        Files.writeString(file, "class A {}\n");
        Path log = dir.resolve("lifecycle.log");
        CountDownLatch ready = new CountDownLatch(1);
        LspManager manager = new LspManager((f, d) -> {}, (type, message) -> {
            if ("ServiceReady".equals(type)) {
                ready.countDown();
            }
        });
        manager.setJdtlsWorkspaceBase(dir.resolve("workspaces"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        manager.setSessionStarterForTest(session -> {
            // A real process that never answers `shutdown`, so it stays alive for the whole graceful wait.
            session.configureStart(
                    List.of(
                            java,
                            "-cp",
                            System.getProperty("java.class.path"),
                            LspGracefulExitProcessTest.RecordingServer.class.getName(),
                            log.toString(),
                            "mute"),
                    () -> null);
            Thread.ofVirtual().start(session::start);
        });
        manager.configure(true, Map.of("java", "jdtls"));
        manager.openDocument(file, root, "java", "class A {}\n");
        assertTrue(ready.await(60, TimeUnit.SECONDS), "the test server never finished initialize");
        String canonical = LspServerRegistry.workspaceDirName(root);

        manager.shutdownAll(); // a restart: the old server starts exiting, the caller moves straight on
        var exits = manager.pendingExitsForTest();
        String whileExiting = LspManager.claimJdtlsWorkspaceName(canonical);
        try {
            assertNotEquals(canonical, whileExiting, "the workspace must stay claimed while the old process is alive");
        } finally {
            LspManager.releaseJdtlsWorkspaceName(whileExiting);
        }

        exits.get(30, TimeUnit.SECONDS);
        String afterExit = LspManager.claimJdtlsWorkspaceName(canonical);
        try {
            assertEquals(canonical, afterExit, "once it has exited the workspace is free again");
        } finally {
            LspManager.releaseJdtlsWorkspaceName(afterExit);
        }
        manager.close();
    }
}
