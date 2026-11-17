package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import org.eclipse.lsp4j.debug.StackFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Bash debugging through the window, against a {@link FakeDebugAdapter} reached as vscode-bash-debug is (a stand-in
 * over its standard streams): F5 on a Bash script launches it with the programs bash-debug needs named, bashdb's own
 * stop at the script's first line is passed, a breakpoint's is shown, a watch of {@code $total} is asked as {@code
 * total}, and a zsh script is not debugged, saying why. {@code BashDebugProbeFxTest} drives the real adapter.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class BashDebugWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path root;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-in is a shell script");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        root = Files.createDirectories(fx.configDir.resolve("scripts")).toRealPath();
        Files.writeString(
                root.resolve("loop.sh"),
                "#!/usr/bin/env bash\ntotal=0\nfor n in 1 2; do\n  total=$((total + n))\ndone\n");
        Files.writeString(root.resolve("prompt.zsh"), "#!/usr/bin/env zsh\necho hi\n");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        Path bashDebug = fx.configDir.resolve("bash-debug");
        Files.writeString(
                bashDebug,
                "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                        + "' com.editora.dap.RelayAdapterMain stdio " + adapter.port() + "\n");
        assertTrue(bashDebug.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setBashDebugPath(bashDebug.toString());
            debug.applySupport();
        });
        await("Bash debugging available", () -> call(() -> debug.debugEffectiveFor("bash")));
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
        long end = System.nanoTime() + 20_000_000_000L;
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
                    return b != null && file.equals(b.getPath());
                }));
        return FxTestSupport.callOnFx(fx.controller::activeBuffer);
    }

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private String status() {
        return call(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<Label>field(bar, "echo").getText();
        });
    }

    private static StackFrame frame(Path file, int line) {
        StackFrame f = new StackFrame();
        f.setId(0);
        f.setName("->0 in file `" + file + "' at line " + (line + 1));
        f.setLine(line + 1);
        org.eclipse.lsp4j.debug.Source source = new org.eclipse.lsp4j.debug.Source();
        source.setPath(file.toString());
        f.setSource(source);
        return f;
    }

    private long count(FakeDebugAdapter.Session session, String request) {
        return session.requests.stream().filter(request::equals).count();
    }

    @Test
    void f5LaunchesTheScriptPassesBashdbsFirstStopAndShowsTheBreakpoints() throws Exception {
        Path loop = root.resolve("loop.sh");
        EditorBuffer b = open(loop);
        assertTrue(call(b::isBreakpointsEnabled), "a Bash script has the breakpoint gutter");
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(3));
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        Map<String, Object> launch = session.launchArgs;
        assertEquals("bashdb", launch.get("type"));
        assertEquals(loop.toString(), launch.get("program"));
        assertEquals(root.toString(), launch.get("cwd"));
        assertTrue(String.valueOf(launch.get("pathBashdb")).endsWith("bashdb_dir/bashdb"), launch.toString());
        assertTrue(Path.of(String.valueOf(launch.get("pathBash"))).isAbsolute(), "bash by its absolute path");
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);

        // bashdb stops at the first command: not shown, the script goes on.
        session.frames = List.of(frame(loop, 1));
        session.stop(session.mainThread, "break");
        await("passed on", () -> count(session, "continue") == 1);
        assertEquals(DapManager.State.RUNNING, call(manager::state));

        // The breakpoint's stop is shown.
        session.frames = List.of(frame(loop, 3));
        session.stop(session.mainThread, "break");
        await(
                "shown",
                () -> call(() -> manager.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()
                        && debug.panel().callStack().getFirst().line() == 3));
        assertEquals(1, count(session, "continue"), "only the first stop is passed");

        // A watch written as the script writes a variable is asked by its bare name, as bashdb reads it.
        FxTestSupport.runOnFx(() -> debug.panel().addWatchExpression("$total"));
        await(
                "the watch asked",
                () -> session.evaluateRequests.stream().anyMatch(a -> "total".equals(a.getExpression())));
    }

    @Test
    void aZshScriptIsNotDebuggedAndSaysWhy() throws Exception {
        open(root.resolve("prompt.zsh"));
        run("debug.start");
        FxTestSupport.drainFx();
        assertEquals(tr("status.debug.shellNotBash"), status());
        assertTrue(!call(manager::isActive), "no session");
    }
}
