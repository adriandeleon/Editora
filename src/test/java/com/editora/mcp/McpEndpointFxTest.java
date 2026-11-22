package com.editora.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP endpoint file is written and removed off the FX thread (PERF-01, Pauta 6.2.0): the settings toggle,
 * New Token and the last window's close ask on the FX thread, and the {@code mcp-endpoint} thread does the file
 * work, in the order asked.
 */
@Tag("fx")
class McpEndpointFxTest {

    private final List<String> onFx = new CopyOnWriteArrayList<>();
    private final List<String> onEndpointThread = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void tearDown() {
        BlockingSites.listenForTest(null);
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
    }

    @Test
    void startNewTokenAndStopTouchTheFileOffTheFxThreadInOrder(@TempDir Path dir) throws Exception {
        McpServer server = new McpServer(new McpServerTest.FakeBridge(), dir);
        Path endpoint = dir.resolve("mcp-endpoint.json");
        BlockingSites.listenForTest((site, thread) -> {
            if (site.startsWith("McpServer.") && !site.equals("McpServer.reapStaleEndpoint")) {
                (Platform.isFxApplicationThread() ? onFx : onEndpointThread).add(site + "@" + thread.getName());
            }
        });
        String[] tokens = new String[2];
        onFx(() -> {
            try {
                server.start();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            tokens[0] = server.token();
            server.newToken();
            tokens[1] = server.token();
        });
        McpServer.awaitEndpointFileForTest();
        String written = Files.readString(endpoint);
        assertTrue(written.contains(tokens[1]), "the last write is the new token's: " + written);
        assertFalse(written.contains(tokens[0]), "not the old one");

        onFx(server::stop);
        McpServer.awaitEndpointFileForTest();
        assertFalse(Files.exists(endpoint), "stop removes the file");
        assertEquals(List.of(), onFx, "no endpoint file work on the FX thread");
        assertEquals(
                List.of(
                        "McpServer.writeEndpointFile@mcp-endpoint",
                        "McpServer.writeEndpointFile@mcp-endpoint",
                        "McpServer.stop@mcp-endpoint"),
                onEndpointThread);
    }
}
