package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.dap.LaunchJson;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.eclipse.lsp4j.debug.StackFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Lua debugging through the window, against a {@link FakeDebugAdapter} reached as local-lua-debugger is (a stand-in
 * over its standard streams): F5 on a Lua script launches it with what VS Code's extension would add (the extension's
 * folder, the workspace) and the script's output on a pipe, and a breakpoint's stop is shown; a launch.json
 * {@code lua-local} configuration starts the same adapter. {@code LuaDebugProbeFxTest} drives the real one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class LuaDebugWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path root;
    private Path luaDebug;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-in is a shell script");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        root = Files.createDirectories(fx.configDir.resolve("scripts")).toRealPath();
        Files.writeString(root.resolve("sum.lua"), "local total = 0\nfor i = 1, 3 do\n  total = total + i\nend\n");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        Path extension = Files.createDirectories(fx.configDir.resolve("lua-debugger/extension"));
        luaDebug = extension.resolve("lua-debug");
        Files.writeString(
                luaDebug,
                "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                        + "' com.editora.dap.RelayAdapterMain stdio " + adapter.port() + "\n");
        assertTrue(luaDebug.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setLuaDebugPath(luaDebug.toString());
            debug.applySupport();
        });
        await("Lua debugging available", () -> call(() -> debug.debugEffectiveFor("lua")));
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
        Await.until(condition, what, java.time.Duration.ofSeconds(20));
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

    private static StackFrame frame(Path file, int line) {
        StackFrame f = new StackFrame();
        f.setId(0);
        f.setName("main chunk");
        f.setLine(line + 1);
        org.eclipse.lsp4j.debug.Source source = new org.eclipse.lsp4j.debug.Source();
        source.setPath(file.toString());
        f.setSource(source);
        return f;
    }

    @Test
    void f5LaunchesTheScriptAsTheExtensionWouldAndShowsItsBreakpointStop() throws Exception {
        Path sum = root.resolve("sum.lua");
        EditorBuffer b = open(sum);
        assertTrue(call(b::isBreakpointsEnabled), "a Lua script has the breakpoint gutter");
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(2));
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        Map<String, Object> launch = session.launchArgs;
        assertEquals("lua-local", launch.get("type"));
        Map<?, ?> program = (Map<?, ?>) launch.get("program");
        assertEquals(sum.toString(), program.get("file"));
        assertEquals("pipe", program.get("communication"));
        String lua = String.valueOf(program.get("lua"));
        assertTrue(lua.equals("lua") || Path.of(lua).isAbsolute(), "the installed Lua, or the bare name: " + lua);
        assertEquals(root.toString(), launch.get("cwd"));
        assertEquals(root.toString(), launch.get("workspacePath"));
        assertEquals(
                fx.configDir.resolve("lua-debugger").toString(),
                launch.get("extensionPath"),
                "the folder above the adapter's, where debugger/lldebugger.lua is");
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);

        session.frames = List.of(frame(sum, 2));
        session.stop(session.mainThread, "breakpoint");
        await(
                "shown",
                () -> call(() -> manager.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()
                        && debug.panel().callStack().getFirst().line() == 2));
    }

    @Test
    void aLaunchJsonLuaConfigurationStartsTheAdapter() throws Exception {
        EditorBuffer b = open(root.resolve("sum.lua"));
        LaunchJson.File parsed = LaunchJson.parse("""
                { "configurations": [ { "name": "Sum", "type": "lua-local", "request": "launch",
                  "program": { "lua": "lua", "file": "${file}" } } ] }
                """);
        LaunchJson.Configuration config = parsed.configurations().getFirst();
        FxTestSupport.runOnFx(
                () -> debug.runLaunchConfiguration(root.resolve(".vscode/launch.json"), root, parsed, config, b));
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        assertEquals("lua-local", session.launchArgs.get("type"));
        assertEquals(root.resolve("sum.lua").toString(), ((Map<?, ?>) session.launchArgs.get("program")).get("file"));
        assertEquals(fx.configDir.resolve("lua-debugger").toString(), session.launchArgs.get("extensionPath"));
    }
}
