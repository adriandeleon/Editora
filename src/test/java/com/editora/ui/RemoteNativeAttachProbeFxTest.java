package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of attaching to a C program under gdbserver, in a window: Debug: Attach to Remote Program… on GDB and on
 * CodeLLDB, and a launch.json {@code cppdbg} configuration with {@code miDebuggerServerAddress} — each passes over the
 * server's hold before the first instruction and stops at a breakpoint in the program's code, and Stop ends the
 * program and the server. Run with {@code ./mvnw test -Dtest=RemoteNativeAttachProbeFxTest -Dgroups=probe
 * -Dlsp.probe=true}; needs {@code cc}, {@code gdbserver}, and {@code gdb} (14 or later) or CodeLLDB.
 */
@Tag("probe")
@Tag("fx")
class RemoteNativeAttachProbeFxTest {

    private static final String SOURCE = """
            #include <stdio.h>
            int add(int a, int b) {
                int sum = a + b;
                return sum;
            }
            int main(void) {
                int total = 0;
                for (int i = 0; i < 3; i++) total = add(total, i);
                printf("total %d\\n", total);
                return 0;
            }
            """;

    /** {@code return sum;}, 0-based. */
    private static final int LINE = 3;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static boolean onPath(String tool) {
        return Path.of(ProcessRunner.resolveExecutable(List.of(tool)).getFirst())
                .isAbsolute();
    }

    private record Program(Path source, Path binary) {}

    /** {@code tally.c} built with its debug symbols. */
    private static Program build(Path dir) throws Exception {
        Path folder = Files.createDirectory(dir.resolve("tally")).toRealPath();
        Path source = Files.writeString(folder.resolve("tally.c"), SOURCE);
        Path binary = folder.resolve("tally");
        Process cc = new ProcessBuilder(ProcessRunner.resolveExecutable(
                        List.of("cc", "-g", "-O0", "-o", binary.toString(), source.toString())))
                .redirectErrorStream(true)
                .start();
        assertTrue(cc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS) && cc.exitValue() == 0, "cc built it");
        return new Program(source, binary);
    }

    /** gdbserver on a free port of this machine, holding {@code binary} before its first instruction. */
    private static Process gdbserver(Path binary, int port) throws Exception {
        return new ProcessBuilder(
                        ProcessRunner.resolveExecutable(List.of("gdbserver", "127.0.0.1:" + port, binary.toString())))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static int freePort() throws java.io.IOException {
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    void attachToRemoteProgramOnGdbStopsInTheProgramsCode(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(onPath("cc") && onPath("gdbserver") && onPath("gdb"), "needs cc, gdbserver and gdb");
        attachByCommand(dir, s -> s.setCppDebugger("gdb"));
    }

    @Test
    void attachToRemoteProgramOnCodelldbStopsInTheProgramsCode(@TempDir Path dir) throws Exception {
        String codelldb = NativeDebugProbeFxTest.assumeToolsAndCodelldb("cc", "gdbserver");
        attachByCommand(dir, s -> s.setRustDebugPath(codelldb));
    }

    @Test
    void aCppdbgConfigurationWithAServerAddressAttaches(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(onPath("cc") && onPath("gdbserver") && onPath("gdb"), "needs cc, gdbserver and gdb");
        Program p = build(dir);
        int port = freePort();
        Path launchJson = Files.createDirectories(p.source().getParent().resolve(".vscode"))
                .resolve("launch.json");
        Files.writeString(launchJson, """
                {"configurations": [{"name": "Board", "type": "cppdbg", "request": "launch",
                  "program": "${workspaceFolder}/tally", "miDebuggerServerAddress": "127.0.0.1:%d"}]}
                """.formatted(port));
        Process server = gdbserver(p.binary(), port);
        FxWindowFixture fx = window(dir, p.source(), s -> s.setCppDebugger("gdb"));
        try {
            Window w = Window.of(fx);
            await("GDB found", 60, () -> call(() -> w.dap.isExternalLocated(DebugLanguages.GDB)));
            FxTestSupport.runOnFx(() -> w.b.toggleBreakpoint(LINE));
            LaunchJson.File parsed = LaunchJson.parse(Files.readString(launchJson));
            FxTestSupport.runOnFx(() -> w.debug.runLaunchConfiguration(
                    launchJson,
                    p.source().getParent(),
                    parsed,
                    parsed.configurations().getFirst(),
                    w.b));
            awaitStop(w, LINE);
            stopAndAwaitEnd(w, server);
        } finally {
            server.destroyForcibly();
            fx.dispose();
        }
    }

    private void attachByCommand(Path dir, Consumer<com.editora.config.Settings> settings) throws Exception {
        Program p = build(dir);
        int port = freePort();
        Process server = gdbserver(p.binary(), port);
        FxWindowFixture fx = window(dir, p.source(), settings);
        try {
            Window w = Window.of(fx);
            await(
                    "C debugging available",
                    60,
                    () -> call(() -> w.debug.debugEffectiveFor("cpp") || w.dap.isExternalLocated(DebugLanguages.GDB)));
            FxTestSupport.runOnFx(() -> w.b.toggleBreakpoint(LINE));
            FxTestSupport.runOnFx(
                    () -> ((CommandRegistry) FxTestSupport.field(w.c, "registry")).run("debug.attachRemote"));
            await("the address prompt", 30, () -> call(() -> OverlayDriver.showing(w.c)));
            FxTestSupport.runOnFx(() -> OverlayDriver.answer(w.c, "127.0.0.1:" + port));
            await("the program prompt", 30, () -> call(() -> OverlayDriver.showing(w.c)));
            FxTestSupport.runOnFx(() -> OverlayDriver.answer(w.c, p.binary().toString()));
            awaitStop(w, LINE);
            stopAndAwaitEnd(w, server);
        } finally {
            server.destroyForcibly();
            fx.dispose();
        }
    }

    private static void stopAndAwaitEnd(Window w, Process server) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(w.c, "registry")).run("debug.stop"));
        await("the session ended", 60, () -> !call(w.dap::isActive));
        await("the server ended with the program", 60, () -> !server.isAlive());
    }

    private record Window(MainController c, DebugCoordinator debug, DapManager dap, EditorBuffer b) {
        static Window of(FxWindowFixture fx) throws Exception {
            MainController c = fx.controller;
            return new Window(
                    c,
                    FxTestSupport.field(c, "debugCoordinator"),
                    FxTestSupport.field(c, "dapManager"),
                    FxTestSupport.callOnFx(c::activeBuffer));
        }
    }

    private static FxWindowFixture window(Path dir, Path open, Consumer<com.editora.config.Settings> settings)
            throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        settings.accept(seed.getSettings());
        assertTrue(seed.saveSettings());
        seed.shutdown();
        return FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(open, 0, 0)), true, c -> {});
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
        Await.until(condition, what, java.time.Duration.ofSeconds(seconds));
    }

    /** A stop at {@code line} of the program's source; never one before it (the server's hold is passed over). */
    private static void awaitStop(Window w, int line) throws InterruptedException {
        try {
            await(
                    "a stop on line " + (line + 1),
                    120,
                    () -> call(() -> {
                        if (w.dap.state() != DapManager.State.SUSPENDED
                                || w.debug.panel().callStack().isEmpty()) {
                            return false;
                        }
                        var top = w.debug.panel().callStack().get(0);
                        if (top.line() != line || top.file() == null) {
                            throw new AssertionError("stopped elsewhere first: " + top.name() + " at " + top.file()
                                    + ":" + (top.line() + 1));
                        }
                        return true;
                    }));
        } catch (AssertionError e) {
            String console = call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(w.debug.panel(), "console")
                    .getText());
            throw new AssertionError(e.getMessage() + " (" + call(w.dap::state) + ")\n" + console, e);
        }
    }
}
