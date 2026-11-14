package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.dap.LaunchJson;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * A launch.json compound through the window, against a {@link FakeDebugAdapter} reached as Ruby's rdbg is: its
 * {@code preLaunchTask} runs once, then each configuration starts in a session of its own, in order; with
 * {@code stopAll}, Stop in one ends both. A configuration started while a session runs is debugged beside it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class LaunchCompoundWindowFxTest {

    private FxWindowFixture fx;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path root;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-in is a shell script");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        root = Files.createDirectories(fx.configDir.resolve("pair")).toRealPath();
        Files.writeString(root.resolve("server.rb"), "x = 1\nputs x\n");
        Files.writeString(root.resolve("client.rb"), "y = 1\nputs y\n");
        Files.createDirectories(root.resolve(".vscode"));
        Files.writeString(root.resolve(".vscode/launch.json"), """
                {
                  "configurations": [
                    { "name": "Server", "type": "rdbg", "request": "launch", "script": "${workspaceFolder}/server.rb" },
                    { "name": "Client", "type": "rdbg", "request": "launch", "script": "${workspaceFolder}/client.rb" }
                  ],
                  "compounds": [
                    { "name": "Both", "configurations": ["Server", "Client"], "stopAll": true,
                      "preLaunchTask": "prepare" }
                  ]
                }
                """);
        Files.writeString(root.resolve(".vscode/tasks.json"), """
                { "tasks": [ { "label": "prepare", "type": "process", "command": "echo",
                               "args": ["compound-prepared"] } ] }
                """);
        String jvm = ProcessHandle.current().info().command().orElse("java");
        Path rdbg = fx.configDir.resolve("rdbg");
        Files.writeString(
                rdbg,
                "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                        + "' com.editora.dap.RelayAdapterMain announce-rdbg " + adapter.port() + "\n");
        assertTrue(rdbg.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setRubyDebugPath(rdbg.toString());
            debug.applySupport();
        });
        await("Ruby debugging available", () -> call(() -> debug.debugEffectiveFor("ruby")));
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
        if (adapter != null) {
            adapter.close();
        }
    }

    private interface Check<T> {
        T get() throws Exception;
    }

    private static <T> T call(Check<T> check) {
        try {
            return FxTestSupport.callOnFx(check::get);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private static String console(DebugPanel panel) {
        return call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(panel, "console")
                .getText());
    }

    @Test
    void aCompoundStartsEachConfigurationInItsOwnSessionAndStopsThemTogether() throws Exception {
        Path launchJson = root.resolve(".vscode/launch.json");
        LaunchJson.File parsed = LaunchJson.parse(Files.readString(launchJson));
        DebugCoordinator.LaunchFile launch = new DebugCoordinator.LaunchFile(launchJson, root, parsed);
        EditorBuffer none = null;
        FxTestSupport.runOnFx(
                () -> debug.runLaunchCompound(launch, parsed.compounds().getFirst(), none));

        // Both start at once: they reach the adapter in either order.
        FakeDebugAdapter.Session one = adapter.awaitSession();
        one.awaitRequest("launch");
        FakeDebugAdapter.Session two = adapter.awaitSession();
        two.awaitRequest("launch");
        boolean oneIsServer = String.valueOf(one.launchArgs.get("script")).endsWith("server.rb");
        FakeDebugAdapter.Session server = oneIsServer ? one : two;
        FakeDebugAdapter.Session client = oneIsServer ? two : one;
        await("two sessions", () -> call(() -> debug.sessions().size()) == 2);
        List<DebugSession> both = call(() -> debug.sessions().all());
        assertEquals(
                List.of("Server", "Client"),
                call(() -> both.stream().map(s -> s.sessionLabel).toList()),
                "each configuration in a session of its own, in order");
        assertTrue(String.valueOf(server.launchArgs.get("script")).endsWith("server.rb"));
        assertTrue(String.valueOf(client.launchArgs.get("script")).endsWith("client.rb"));
        assertTrue(console(both.getFirst().panel).contains("compound-prepared"), "the compound's task ran first");
        await("both running", () -> call(() -> both.stream().allMatch(s -> s.dap.state() == DapManager.State.RUNNING)));

        FxTestSupport.runOnFx(() -> debug.inSession(both.getLast(), debug::stop)); // Stop in the client
        assertTrue(client.disconnected.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(server.disconnected.await(10, java.util.concurrent.TimeUnit.SECONDS), "stopAll: the server too");
        await("both ended", () -> call(() -> both.stream().noneMatch(s -> s.dap.isActive())));

        // A configuration started while a session runs goes beside it, as in VS Code.
        FxTestSupport.runOnFx(() -> debug.focusSession(both.getFirst()));
        FxTestSupport.runOnFx(
                () -> debug.runLaunchConfiguration(launchJson, root, parsed, parsed.configuration("Server"), none));
        FakeDebugAdapter.Session again = adapter.awaitSession();
        again.awaitRequest("launch");
        DebugSession reused = call(() -> debug.sessions().focused());
        assertSame(both.getFirst(), reused, "an ended session is used again");
        await("running", () -> call(() -> reused.dap.state() == DapManager.State.RUNNING));
        FxTestSupport.runOnFx(
                () -> debug.runLaunchConfiguration(launchJson, root, parsed, parsed.configuration("Client"), none));
        FakeDebugAdapter.Session beside = adapter.awaitSession();
        beside.awaitRequest("launch");
        assertNotSame(reused, call(() -> debug.sessions().focused()), "beside the running one");
        assertEquals(1, again.disconnected.getCount(), "which goes on");
        FxTestSupport.runOnFx(debug::stopAll);
        assertTrue(again.disconnected.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(beside.disconnected.await(10, java.util.concurrent.TimeUnit.SECONDS));
    }
}
