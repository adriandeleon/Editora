package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.dap.LocalProcesses;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * C and C++ debugging through the window, without a compiler or CodeLLDB: a Make project whose recipe writes a
 * stand-in program, and a stand-in {@code codelldb} (a script starting {@code RelayAdapterMain} on the port it is
 * given, relaying to a {@link FakeDebugAdapter}) receive what F5, Restart and Attach to Process ask.
 * {@code NativeDebugProbeFxTest} drives the real ones.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NativeDebugWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path root;
    private Path codelldb;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-ins are shell scripts");
        assumeTrue(
                Path.of(com.editora.process.ProcessRunner.resolveExecutable(List.of("make"))
                                .getFirst())
                        .isAbsolute(),
                "needs make");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        root = Files.createDirectories(fx.configDir.resolve("shelf")).toRealPath();
        Files.writeString(root.resolve("shelf.c"), "int main(void) {\n    return 0;\n}\n");
        // The recipe "builds" a program: a file that starts as an ELF program does.
        Files.writeString(root.resolve("Makefile"), "shelf: shelf.c\n\tprintf '\\177ELF' > shelf && chmod +x shelf\n");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        codelldb = fx.configDir.resolve("codelldb");
        Files.writeString(
                codelldb,
                "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                        + "' com.editora.dap.RelayAdapterMain listen \"$2\" " + adapter.port() + "\n");
        assertTrue(codelldb.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setRustDebugPath(codelldb.toString());
            fx.shared.getTrustStore().trust(root);
            debug.applySupport();
        });
        await("C/C++ debugging available", () -> call(() -> debug.debugEffectiveFor("cpp")));
    }

    @AfterEach
    void endSession() throws Exception {
        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));
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
        long end = System.nanoTime() + 30_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private EditorBuffer open(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(file));
        await(
                file.getFileName() + " in front",
                () -> call(() -> {
                    EditorBuffer b = fx.controller.activeBuffer();
                    return b != null && b.getPath() != null && Files.isSameFile(b.getPath(), file);
                }));
        return FxTestSupport.callOnFx(fx.controller::activeBuffer);
    }

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private String status() {
        return call(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<javafx.scene.control.Label>field(bar, "echo").getText();
        });
    }

    private FakeDebugAdapter.Session next(String request) throws Exception {
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest(request);
        return session;
    }

    @Test
    void f5BuildsTheMakeProjectAndLaunchesTheProgramItMadeAndRestartDoesItAgain() throws Exception {
        EditorBuffer shelf = open(root.resolve("shelf.c"));
        assertTrue(call(shelf::isBreakpointsEnabled), "a C file has the breakpoint gutter");
        FxTestSupport.runOnFx(() -> {
            var area = shelf.getFocusedArea();
            area.replaceText(area.getLength(), area.getLength(), "// edited\n");
        });
        run("debug.start");
        FakeDebugAdapter.Session session = next("launch");
        Map<String, Object> launch = session.launchArgs;
        assertEquals(root.resolve("shelf").toString(), launch.get("program"), "the program make built");
        assertEquals(List.of("cpp"), launch.get("sourceLanguages"));
        assertEquals(root.toString(), launch.get("cwd"));
        assertEquals("shelf.c", call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile")));
        assertFalse(call(shelf::isDirty), "the project's C files are saved first: make builds from disk");
        await(
                "C++'s throw stop sent",
                () -> session.exceptionBreakpoints.stream()
                        .anyMatch(a -> java.util.Arrays.asList(a.getFilters()).contains("cpp_throw")));

        FxTestSupport.runOnFx(debug::restart);
        FakeDebugAdapter.Session again = next("launch");
        assertEquals(root.resolve("shelf").toString(), again.launchArgs.get("program"), "Restart builds and launches");
    }

    @Test
    void aLoneHeaderSaysToOpenItsSource() throws Exception {
        Path header = Files.writeString(fx.configDir.resolve("loose.h"), "int f(void);\n");
        open(header);
        run("debug.start");
        await(
                "the status",
                () -> tr("status.debug.nativeHeaderAlone", "loose.h").equals(status()));
        assertFalse(call(manager::isActive));
    }

    @Test
    void attachToProcessAttachesCodelldbToANativeOrRustProgram() throws Exception {
        open(root.resolve("shelf.c"));
        FxTestSupport.runOnFx(
                () -> debug.attachToProcess(new LocalProcesses.Target(4242, LocalProcesses.Kind.NATIVE, "app", "", 0)));
        FakeDebugAdapter.Session session = next("attach");
        assertEquals(4242, ((Number) session.launchArgs.get("pid")).intValue());
        assertEquals(List.of("cpp"), session.launchArgs.get("sourceLanguages"));
        assertEquals("app (4242)", call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile")));
        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));

        FxTestSupport.runOnFx(
                () -> debug.attachToProcess(new LocalProcesses.Target(4343, LocalProcesses.Kind.RUST, "shelf", "", 0)));
        FakeDebugAdapter.Session rust = next("attach");
        assertEquals(List.of("rust"), rust.launchArgs.get("sourceLanguages"));
    }

    @Test
    void whyCAndCppCannotBeDebuggedIsSaid() throws Exception {
        try {
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setCppDebugEnabled(false));
            assertEquals(tr("status.debug.cppOff"), call(() -> debug.unavailableMessage("c", null)));
            FxTestSupport.runOnFx(debug::applySupport);
            await("C/C++ debugging off", () -> !call(() -> debug.debugEffectiveFor("cpp")));
            FxTestSupport.runOnFx(() ->
                    debug.attachToProcess(new LocalProcesses.Target(1, LocalProcesses.Kind.NATIVE, "app", "", 0)));
            assertEquals(tr("status.debug.cppOff"), status());
            assertFalse(call(manager::isActive), "nothing is attached");
        } finally {
            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setCppDebugEnabled(true);
                debug.applySupport();
            });
            await("C/C++ debugging available again", () -> call(() -> debug.debugEffectiveFor("cpp")));
        }
    }
}
