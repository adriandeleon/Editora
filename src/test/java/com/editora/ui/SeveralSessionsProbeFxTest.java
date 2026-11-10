package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of several debug sessions against real debugpy, in a window: two Python programs, each stopped on its
 * own breakpoint at once, each with its own values; showing one and continuing it leaves the other where it was; Stop
 * All ends both. Run with {@code ./mvnw test -Dtest=SeveralSessionsProbeFxTest -Dgroups=probe -Dlsp.probe=true};
 * needs debugpy installed where Editora looks.
 */
@Tag("probe")
@Tag("fx")
class SeveralSessionsProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @Test
    void twoPythonProgramsStopAtOnce(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DebugAdapterLocator.locateDebugpy("", HOME).isPresent(), "needs debugpy");
        Path app = Files.createDirectory(dir.resolve("app")).toRealPath();
        Path server = Files.writeString(
                app.resolve("server.py"), "total = 0\nfor i in range(3):\n    total += i\nprint('server', total)\n");
        Path client = Files.writeString(
                app.resolve("client.py"), "count = 10\nfor j in range(3):\n    count += j\nprint('client', count)\n");
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        assertEquals(true, seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(server, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            await("python debugging available", 90, () -> call(() -> debug.debugEffectiveFor("python")));
            EditorBuffer first = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> first.toggleBreakpoint(2));
            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            DebugSession a = call(() -> debug.sessions().focused());
            awaitStop(a, 2);
            awaitRow(a, "i", "0");

            FxTestSupport.runOnFx(() -> c.fileWorkflows.openPath(client));
            await(
                    "client.py in front",
                    10,
                    () -> call(() -> c.activeBuffer() != null
                            && client.equals(c.activeBuffer().getPath())));
            EditorBuffer second = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> second.toggleBreakpoint(2));
            debug.retargetChoice = (question, both) -> DebugCoordinator.Retarget.BOTH;
            FxTestSupport.runOnFx(() -> debug.debugFile(second)); // Debug File: never continues the paused one
            await("a second session", 30, () -> call(() -> debug.sessions().size()) == 2);
            DebugSession b = call(() -> debug.sessions().focused());
            assertNotSame(a, b);
            awaitStop(b, 2);
            awaitRow(b, "j", "0");
            assertSame(b, call(() -> debug.sessions().focused()), "the one that stopped last is shown");
            assertEquals(DapManager.State.SUSPENDED, call(a.dap::state), "the first is still paused");

            FxTestSupport.runOnFx(() -> debug.focusSession(a));
            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.continue"));
            awaitRow(a, "i", "1");
            assertEquals("0", call(() -> row(b.panel, "j").getValue().value()), "the second did not move");
            assertEquals(DapManager.State.SUSPENDED, call(b.dap::state));

            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.stopAll"));
            await("both ended", 30, () -> !call(a.dap::isActive) && !call(b.dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    @Test
    void aLaunchJsonCompoundStartsBothAndStopsThemTogether(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DebugAdapterLocator.locateDebugpy("", HOME).isPresent(), "needs debugpy");
        Path app = Files.createDirectory(dir.resolve("app")).toRealPath();
        Path server = Files.writeString(
                app.resolve("server.py"), "total = 0\nfor i in range(3):\n    total += i\nprint('server', total)\n");
        Files.writeString(
                app.resolve("client.py"), "count = 10\nfor j in range(3):\n    count += j\nprint('client', count)\n");
        Files.createDirectory(app.resolve(".vscode"));
        Files.writeString(app.resolve(".vscode/launch.json"), """
                {
                  "configurations": [
                    { "name": "Server", "type": "debugpy", "request": "launch",
                      "program": "${workspaceFolder}/server.py", "console": "internalConsole" },
                    { "name": "Client", "type": "debugpy", "request": "launch",
                      "program": "${workspaceFolder}/client.py", "console": "internalConsole" }
                  ],
                  "compounds": [ { "name": "Both", "configurations": ["Server", "Client"], "stopAll": true } ]
                }
                """);
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        assertEquals(true, seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(server, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            await("python debugging available", 90, () -> call(() -> debug.debugEffectiveFor("python")));
            EditorBuffer first = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> first.toggleBreakpoint(2));
            Path launchJson = app.resolve(".vscode/launch.json");
            com.editora.dap.LaunchJson.File parsed = com.editora.dap.LaunchJson.parse(Files.readString(launchJson));
            DebugCoordinator.LaunchFile launch = new DebugCoordinator.LaunchFile(launchJson, app, parsed);
            FxTestSupport.runOnFx(
                    () -> debug.runLaunchCompound(launch, parsed.compounds().getFirst(), first));
            await("two sessions", 30, () -> call(() -> debug.sessions().size()) == 2);
            List<DebugSession> both = call(() -> debug.sessions().all());
            DebugSession a = both.getFirst();
            DebugSession b = both.getLast();
            awaitStop(a, 2); // the server stops on its breakpoint
            awaitRow(a, "i", "0");
            await("the client ran to its end", 60, () -> !call(b.dap::isActive));
            assertTrue(call(a.dap::isActive), "a session that ends by itself leaves the others (stopAll is for Stop)");
            assertTrue(
                    call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(b.panel, "console")
                            .getText()
                            .contains("client 13")),
                    "the client's output, in its own console");

            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.stop"));
            await("the server stopped", 30, () -> !call(a.dap::isActive));
        } finally {
            fx.dispose();
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

    private static void await(String what, int seconds, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    private static void awaitStop(DebugSession session, int line) throws InterruptedException {
        await(
                "a stop on line " + (line + 1),
                120,
                () -> call(() -> session.dap.state() == DapManager.State.SUSPENDED
                        && session.panel != null
                        && !session.panel.callStack().isEmpty()
                        && session.panel.callStack().getFirst().line() == line));
    }

    /** The first row called {@code name} in any scope of {@code panel}'s tree. */
    private static TreeItem<DebugPanel.VarRow> row(DebugPanel panel, String name) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(panel, "variables");
        for (TreeItem<DebugPanel.VarRow> top : tree.getRoot().getChildren()) {
            for (TreeItem<DebugPanel.VarRow> child : top.getChildren()) {
                if (child.getValue() != null && child.getValue().name().equals(name)) {
                    return child;
                }
            }
        }
        return null;
    }

    private static void awaitRow(DebugSession session, String name, String value) throws InterruptedException {
        await(
                name + " = " + value,
                30,
                () -> call(() -> {
                    TreeItem<DebugPanel.VarRow> row = session.panel == null ? null : row(session.panel, name);
                    return row != null && value.equals(row.getValue().value());
                }));
    }
}
