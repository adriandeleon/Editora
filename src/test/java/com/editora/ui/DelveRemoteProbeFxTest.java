package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.DebugLanguages;
import com.editora.dap.LaunchJson;
import com.editora.editor.EditorBuffer;
import com.editora.process.ProcessRunner;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of attaching to a Go program under Delve's headless server, in a window. The program is built and run
 * from one folder ("there") and its sources are open from a copy in another ("here"), so every stop also proves the
 * paths were mapped. Debug: Attach to Remote Delve… with the project's folder over there, against a multi-client
 * server: it stops at a breakpoint in the copy here, and Stop leaves the program running to its end, not halted at
 * that breakpoint with nobody attached. A launch.json {@code mode: remote} configuration with {@code substitutePath},
 * against a single-client server: it stops too, and Stop ends the server. Run with {@code ./mvnw test
 * -Dtest=DelveRemoteProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs {@code go} and {@code dlv}.
 */
@Tag("probe")
@Tag("fx")
class DelveRemoteProbeFxTest {

    private static final String MAIN = """
            package main

            import (
            \t"fmt"
            \t"time"
            )

            func add(a, b int) int {
            \tsum := a + b
            \treturn sum
            }

            func main() {
            \tfor i := 0; i < 4; i++ {
            \t\tfmt.Println("tick", add(i, 1))
            \t\ttime.Sleep(200 * time.Millisecond)
            \t}
            \tfmt.Println("done")
            }
            """;

    /** {@code sum := a + b}, 0-based. */
    private static final int LINE = 8;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    /** Delve's path; skips the test unless it was opted into and Go and Delve are here. */
    private static String assumeGoAndDelve() {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        String dlv = DapManager.locateExternal(DebugLanguages.GO, "");
        assumeTrue(dlv != null, "needs Delve (dlv)");
        assumeTrue(Path.of(go()).isAbsolute(), "needs go");
        return dlv;
    }

    private static String go() {
        return ProcessRunner.resolveExecutable(List.of("go")).getFirst();
    }

    /** The program's module in {@code dir}; its folder's real path. */
    private static Path module(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("go.mod"), "module example.com/remote\n\ngo 1.22\n");
        Files.writeString(dir.resolve("main.go"), MAIN);
        return dir.toRealPath();
    }

    private record Server(Process process, Path log) {
        boolean printed(String text) {
            try {
                return Files.readString(log).contains(text);
            } catch (java.io.IOException e) {
                return false;
            }
        }

        void destroy() {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    /** {@code dlv debug --headless} on a free port of this machine, building and holding the module in {@code there}. */
    private static Server headless(String dlv, Path there, int port, boolean multiclient) throws Exception {
        List<String> argv = new java.util.ArrayList<>(
                List.of(dlv, "debug", "--headless", "--listen=127.0.0.1:" + port, "--api-version=2"));
        if (multiclient) {
            argv.add("--accept-multiclient");
        }
        argv.add(".");
        Path log = there.resolveSibling(there.getFileName() + "-dlv.log");
        ProcessBuilder pb = new ProcessBuilder(argv)
                .directory(there.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        String goBin = String.valueOf(Path.of(go()).getParent());
        pb.environment().merge("PATH", goBin, (old, add) -> add + java.io.File.pathSeparator + old);
        Server server = new Server(pb.start(), log);
        await("Delve listening", 180, () -> server.printed("API server listening"));
        return server;
    }

    private static int freePort() throws java.io.IOException {
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    void attachToRemoteDelveMapsTheProjectAndStopLeavesTheProgramRunning(@TempDir Path dir) throws Exception {
        String dlv = assumeGoAndDelve();
        Path there = module(dir.resolve("there"));
        Path here = module(dir.resolve("here"));
        int port = freePort();
        Server server = headless(dlv, there, port, true);
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create(
                    Files.createDirectory(dir.resolve("config")),
                    shared -> shared.getSettings().setDebugSupport(true)));
            MainController c = WindowMcpBridgeFxTest.openProject(async, fx, here);
            FileWorkflowCoordinator workflows = FxTestSupport.field(c, "fileWorkflows");
            Path main = here.resolve("main.go");
            FxTestSupport.runOnFx(() -> workflows.openPath(main));
            EditorBuffer b = Await.value(
                    () -> call(() -> {
                        EditorBuffer active = c.activeBuffer();
                        return active != null && main.equals(active.getPath()) && !active.isLoading() ? active : null;
                    }),
                    "main.go open");
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(LINE));
            run(c, "debug.attachRemoteGo");
            await("the address prompt", 30, () -> call(() -> OverlayDriver.showing(c)));
            FxTestSupport.runOnFx(() -> OverlayDriver.answer(c, "127.0.0.1:" + port));
            await("the folder prompt", 30, () -> call(() -> OverlayDriver.showing(c)));
            FxTestSupport.runOnFx(() -> OverlayDriver.answer(c, there.toString()));
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            awaitStop(debug, dap, main);
            awaitRow(debug, "b", "1");

            run(c, "debug.stop");
            await("the session ended", 60, () -> !call(dap::isActive));
            await(
                    "the program ran on to its end: its breakpoint was taken out before the detach",
                    60,
                    () -> server.printed("done"));
            assertTrue(server.process().isAlive(), "a multi-client server stays for the next attach");
        } finally {
            server.destroy();
        }
    }

    @Test
    void aRemoteGoConfigurationAttachesWithItsSubstitutePath(@TempDir Path dir) throws Exception {
        String dlv = assumeGoAndDelve();
        Path there = module(dir.resolve("there"));
        Path here = module(dir.resolve("here"));
        int port = freePort();
        Path launchJson = Files.createDirectories(here.resolve(".vscode")).resolve("launch.json");
        Files.writeString(launchJson, """
                {"configurations": [{"name": "Container", "type": "go", "request": "attach", "mode": "remote",
                  "host": "127.0.0.1", "port": %d,
                  "substitutePath": [{"from": "${workspaceFolder}", "to": "%s"}]}]}
                """.formatted(port, there.toString().replace("\\", "\\\\")));
        Server server = headless(dlv, there, port, false);
        Path main = here.resolve("main.go");
        FxWindowFixture fx = FxWindowFixture.create(
                Files.createDirectory(dir.resolve("config")),
                shared -> shared.getSettings().setDebugSupport(true));
        try {
            MainController c = fx.controller;
            FileWorkflowCoordinator workflows = FxTestSupport.field(c, "fileWorkflows");
            FxTestSupport.runOnFx(() -> workflows.openPath(main));
            EditorBuffer b = Await.value(
                    () -> call(() -> {
                        EditorBuffer active = c.activeBuffer();
                        return active != null && main.equals(active.getPath()) && !active.isLoading() ? active : null;
                    }),
                    "main.go open");
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(LINE));
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            LaunchJson.File parsed = LaunchJson.parse(Files.readString(launchJson));
            FxTestSupport.runOnFx(() -> debug.launchJson.runLaunchConfiguration(
                    launchJson, here, parsed, parsed.configurations().getFirst(), b));
            awaitStop(debug, dap, main);

            run(c, "debug.stop");
            await("the session ended", 60, () -> !call(dap::isActive));
            await(
                    "a single-client server ends with its client",
                    60,
                    () -> !server.process().isAlive());
        } finally {
            server.destroy();
            fx.dispose();
        }
    }

    private static void run(MainController c, String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run(command));
    }

    /** A stop at {@link #LINE} of {@code file}, the copy here (the paths were mapped). */
    private static void awaitStop(DebugCoordinator debug, DapManager dap, Path file) throws InterruptedException {
        try {
            await(
                    "a stop on line " + (LINE + 1),
                    120,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().getFirst().line() == LINE));
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + " (" + call(dap::state) + ")\n" + console(debug), e);
        }
        Path top = call(() -> debug.panel().callStack().getFirst().file());
        assertEquals(file, top, "the frame is the file here, not the one it was built from");
    }

    private static String console(DebugCoordinator debug) {
        return call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(debug.panel(), "console")
                .getText());
    }

    private static void awaitRow(DebugCoordinator debug, String name, String value) throws InterruptedException {
        await(
                name + " = " + value,
                60,
                () -> call(() -> {
                    javafx.scene.control.TreeView<DebugPanel.VarRow> tree =
                            FxTestSupport.field(debug.panel(), "variables");
                    return tree.getRoot() != null && find(tree.getRoot(), name, value);
                }));
    }

    private static boolean find(javafx.scene.control.TreeItem<DebugPanel.VarRow> item, String name, String value) {
        DebugPanel.VarRow row = item.getValue();
        if (row != null && name.equals(row.name()) && value.equals(row.value())) {
            return true;
        }
        for (var child : item.getChildren()) {
            if (find(child, name, value)) {
                return true;
            }
        }
        return false;
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
        Await.until(condition, what, Duration.ofSeconds(seconds));
    }
}
