package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.dap.LaunchJson;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * launch.json's {@code serverReadyAction} and {@code postDebugTask} through the window, against a
 * {@link FakeDebugAdapter} reached as Ruby's rdbg is: the server's output names its port, which starts the
 * {@code startDebugging} configuration beside it; the server's end stops that one ({@code killOnServerStop}) and runs
 * the {@code postDebugTask}; Restart runs the task before the launch again.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class ServerReadyWindowFxTest {

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
        root = Files.createDirectories(fx.configDir.resolve("web")).toRealPath();
        fx.shared.getTrustStore().trust(root); // its tasks run only in a trusted folder
        Files.writeString(root.resolve("server.rb"), "x = 1\nputs x\n");
        Files.writeString(root.resolve("client.rb"), "y = 1\nputs y\n");
        Files.createDirectories(root.resolve(".vscode"));
        Files.writeString(root.resolve(".vscode/launch.json"), """
                {
                  "configurations": [
                    { "name": "Server", "type": "rdbg", "request": "launch", "script": "${workspaceFolder}/server.rb",
                      "postDebugTask": "cleanup",
                      "serverReadyAction": { "action": "startDebugging", "name": "Client",
                                             "pattern": "Listening on port ([0-9]+)", "killOnServerStop": true } },
                    { "name": "Client", "type": "rdbg", "request": "launch", "script": "${workspaceFolder}/client.rb" }
                  ]
                }
                """);
        Files.writeString(root.resolve(".vscode/tasks.json"), """
                { "tasks": [ { "label": "cleanup", "type": "process", "command": "touch",
                               "args": ["${workspaceFolder}/cleaned"] } ] }
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
        Await.until(condition, what, Duration.ofSeconds(20));
    }

    private static String console(DebugPanel panel) {
        return call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(panel, "console")
                .getText());
    }

    @Test
    void theServersAddressStartsTheNextConfigurationAndItsEndRunsThePostDebugTask() throws Exception {
        Path launchJson = root.resolve(".vscode/launch.json");
        LaunchJson.File parsed = LaunchJson.parse(Files.readString(launchJson));
        EditorBuffer none = null;
        Path cleaned = root.resolve("cleaned");
        FxTestSupport.runOnFx(() -> debug.launchJson.runLaunchConfiguration(
                launchJson, root, parsed, parsed.configuration("Server"), none));
        FakeDebugAdapter.Session server = adapter.awaitSession();
        server.awaitRequest("launch");
        DebugSession serverSession = call(() -> debug.sessions().focused());
        await("server running", () -> call(() -> serverSession.dap.state() == DapManager.State.RUNNING));

        server.output("booting\n");
        server.output("Listening on port 4567\n"); // the address: the Client configuration starts beside it
        FakeDebugAdapter.Session client = adapter.awaitSession();
        client.awaitRequest("launch");
        assertTrue(String.valueOf(client.launchArgs.get("script")).endsWith("client.rb"));
        await("two sessions", () -> call(() -> debug.sessions().size()) == 2);
        DebugSession clientSession = call(() -> debug.sessions().focused());
        assertNotSame(serverSession, clientSession, "in a session of its own");
        assertEquals("Client", call(() -> clientSession.sessionLabel));
        await("the server's console says so", () -> console(serverSession.panel).contains("http://localhost:4567"));
        server.output("Listening on port 4567\n"); // once only
        server.awaitDelivered();
        assertEquals(2, adapter.sessionCount(), "the action is taken once");
        assertFalse(Files.exists(cleaned), "the postDebugTask waits for the end");

        FxTestSupport.runOnFx(() -> debug.inSession(serverSession, debug::stop));
        assertTrue(server.disconnected.await(10, TimeUnit.SECONDS));
        assertTrue(client.disconnected.await(10, TimeUnit.SECONDS), "killOnServerStop: the client too");
        await("the postDebugTask ran", () -> Files.exists(cleaned));

        // Restart: the postDebugTask, then the launch again (as VS Code).
        Files.delete(cleaned);
        FxTestSupport.runOnFx(() -> debug.focusSession(serverSession));
        FxTestSupport.runOnFx(() -> debug.launchJson.runLaunchConfiguration(
                launchJson, root, parsed, parsed.configuration("Server"), none));
        FakeDebugAdapter.Session second = adapter.awaitSession();
        second.awaitRequest("launch");
        await("running again", () -> call(() -> serverSession.dap.state() == DapManager.State.RUNNING));
        FxTestSupport.runOnFx(() -> debug.inSession(serverSession, debug::restart));
        FakeDebugAdapter.Session third = adapter.awaitSession();
        third.awaitRequest("launch");
        assertTrue(Files.exists(cleaned), "the postDebugTask ran before the launch again");
        await("running after the restart", () -> call(() -> serverSession.dap.state() == DapManager.State.RUNNING));
        Files.delete(cleaned);
        FxTestSupport.runOnFx(() -> debug.inSession(serverSession, debug::stop));
        assertTrue(third.disconnected.await(10, TimeUnit.SECONDS));
        await("the restarted launch's end ran it too", () -> Files.exists(cleaned));
    }
}
