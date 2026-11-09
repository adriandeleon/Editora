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

/**
 * C#, Ruby and PHP debugging through the window, without their debuggers: stand-ins for netcoredbg (over its standard
 * streams), rdbg (announcing its port on its standard error, as rdbg does) and vscode-php-debug relay to a
 * {@link FakeDebugAdapter}; a stand-in {@code dotnet} builds and names an assembly. They receive what F5 and a
 * launch.json configuration ask. {@code CsharpDebugProbeFxTest}, {@code RubyDebugProbeFxTest} and
 * {@code PhpDebugProbeFxTest} drive the real ones.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PresetDebugWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path root;
    private Path rdbgArgs;
    private Path dotnetLog;

    private static final String DOTNET = """
            #!/bin/sh
            echo "$@" >> '%s'
            case "$*" in
              *fail.cs*) echo "fail.cs(1,1): error CS0029: no"; exit 1 ;;
              *-getProperty:TargetPath*empty.cs*|*empty.cs*-getProperty:TargetPath*) exit 0 ;;
              *-getProperty:TargetPath*) echo '%s' ;;
              *) echo "Build succeeded." ;;
            esac
            """;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-ins are shell scripts");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        root = Files.createDirectories(fx.configDir.resolve("shelf")).toRealPath();
        Files.writeString(root.resolve("shelf.csproj"), "<Project Sdk=\"Microsoft.NET.Sdk\"/>");
        Files.writeString(root.resolve("Program.cs"), "var x = 1;\nConsole.WriteLine(x);\n");
        Files.writeString(root.resolve("shelf.rb"), "x = 1\nputs x\n");
        Files.writeString(root.resolve("shelf.php"), "<?php\n$x = 1;\necho $x;\n");
        Path assembly =
                Files.createDirectories(root.resolve("bin/Debug/net10.0")).resolve("shelf.dll");
        Files.writeString(assembly, "");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        String relay = "exec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                + "' com.editora.dap.RelayAdapterMain ";
        Path netcoredbg = script("netcoredbg", "#!/bin/sh\n" + relay + "stdio " + adapter.port() + "\n");
        rdbgArgs = fx.configDir.resolve("rdbg-args");
        Path rdbg = script(
                "rdbg",
                "#!/bin/sh\necho \"$@\" > '" + rdbgArgs + "'\n" + relay + "announce-rdbg " + adapter.port() + "\n");
        Path phpDebug = script("php-debug", "#!/bin/sh\n" + relay + "stdio " + adapter.port() + "\n");
        dotnetLog = fx.configDir.resolve("dotnet-log");
        Path dotnet = script("dotnet", DOTNET.formatted(dotnetLog, assembly));
        FxTestSupport.runOnFx(() -> {
            var s = fx.shared.getSettings();
            s.setDebugSupport(true);
            s.setCsharpDebugPath(netcoredbg.toString());
            s.setRubyDebugPath(rdbg.toString());
            s.setPhpDebugPath(phpDebug.toString());
            fx.shared.getTrustStore().trust(root);
            debug.dotnetCommand = List.of(dotnet.toString());
            debug.applySupport();
        });
        for (String language : List.of("csharp", "ruby", "php")) {
            await(language + " debugging available", () -> call(() -> debug.debugEffectiveFor(language)));
        }
    }

    private Path script(String name, String text) throws Exception {
        Path p = fx.configDir.resolve(name);
        Files.writeString(p, text);
        assertTrue(p.toFile().setExecutable(true));
        return p;
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
                    return b != null && b.getPath() != null && Files.isSameFile(b.getPath(), file);
                }));
        return FxTestSupport.callOnFx(fx.controller::activeBuffer);
    }

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private String sessionName() {
        return call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile"));
    }

    private String status() {
        return call(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<javafx.scene.control.Label>field(bar, "echo").getText();
        });
    }

    private FakeDebugAdapter.Session nextSession(String request) throws Exception {
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest(request);
        return session;
    }

    @Test
    void f5BuildsTheCsharpProjectThenLaunchesItsAssemblyOnNetcoredbg() throws Exception {
        EditorBuffer program = open(root.resolve("Program.cs"));
        assertTrue(call(program::isBreakpointsEnabled), "a C# file has the breakpoint gutter");
        FxTestSupport.runOnFx(() -> program.toggleBreakpoint(1));
        run("debug.start");
        FakeDebugAdapter.Session session = nextSession("launch");
        Map<String, Object> launch = session.launchArgs;
        assertEquals("coreclr", launch.get("type"));
        assertEquals(root.resolve("bin/Debug/net10.0/shelf.dll").toString(), launch.get("program"));
        assertEquals(root.toString(), launch.get("cwd"), "the program runs in the project's folder");
        assertEquals("shelf", sessionName());
        String log = Files.readString(dotnetLog);
        assertTrue(log.contains("build " + root.resolve("shelf.csproj")), log);
        assertTrue(log.contains("-getProperty:TargetPath"), log);
        await(
                "the breakpoint sent",
                () -> session.breakpoints.stream()
                        .anyMatch(a -> a.getSource().getPath().endsWith("Program.cs")));
        assertEquals(
                List.of("user-unhandled"),
                session.exceptionBreakpoints.getLast().getFilters() == null
                        ? List.of()
                        : List.of(session.exceptionBreakpoints.getLast().getFilters()),
                "an exception user code does not handle stops, as in Visual Studio");
        FxTestSupport.runOnFx(() -> program.toggleBreakpoint(1));
    }

    @Test
    void aCsharpBuildThatFailsOrMakesNothingLaunchesNothing() throws Exception {
        Path scratch = Files.createDirectories(fx.configDir.resolve("scratch")).toRealPath();
        Files.writeString(scratch.resolve("fail.cs"), "int x = \"s\";\n");
        Files.writeString(scratch.resolve("empty.cs"), "System.Console.WriteLine(1);\n");
        open(scratch.resolve("fail.cs"));
        run("debug.start");
        await(
                "the build's failure",
                () -> status().startsWith(tr("status.run.dotnetBuildFailed", "fail.cs", "")
                        .substring(0, 6)));
        assertFalse(call(manager::isActive));
        open(scratch.resolve("empty.cs"));
        run("debug.start");
        await(
                "nothing to debug",
                () -> tr("status.debug.csharpNoProgram", "empty.cs").equals(status()));
        assertFalse(call(manager::isActive));
        assertTrue(Files.readString(dotnetLog).contains("build " + scratch.resolve("empty.cs")), "a file-based app");
    }

    @Test
    void f5RunsARubyScriptUnderRdbg() throws Exception {
        open(root.resolve("shelf.rb"));
        run("debug.start");
        FakeDebugAdapter.Session session = nextSession("launch");
        assertEquals("rdbg", session.launchArgs.get("type"));
        assertEquals(root.resolve("shelf.rb").toString(), session.launchArgs.get("script"));
        assertEquals(true, session.launchArgs.get("localfs"));
        String args = Files.readString(rdbgArgs).strip();
        assertTrue(args.startsWith("--command --open --stop-at-load --host=127.0.0.1 --port=0"), args);
        assertTrue(args.endsWith("-- ruby " + root.resolve("shelf.rb")), args);
    }

    @Test
    void f5RunsAPhpScriptWithXdebug() throws Exception {
        open(root.resolve("shelf.php"));
        run("debug.start");
        FakeDebugAdapter.Session session = nextSession("launch");
        assertEquals("php", session.launchArgs.get("type"));
        assertEquals(root.resolve("shelf.php").toString(), session.launchArgs.get("program"));
        assertEquals(0, ((Number) session.launchArgs.get("port")).intValue());
        assertEquals("shelf.php", sessionName());
    }

    @Test
    void launchJsonRdbgLaunchesItsCommandAndAttachesToItsPort() throws Exception {
        EditorBuffer b = open(root.resolve("shelf.rb"));
        Path launchJson = root.resolve(".vscode/launch.json");
        LaunchJson.File parsed = LaunchJson.parse("""
                {"configurations": [
                  {"name": "Rake", "type": "rdbg", "request": "launch", "command": "rake", "script": "spec",
                   "cwd": "${workspaceFolder}", "env": {"SHELF": "1"}},
                  {"name": "Server", "type": "rdbg", "request": "attach", "debugPort": "%d"}
                ]}
                """.formatted(adapter.port()));
        FxTestSupport.runOnFx(() -> debug.runLaunchConfiguration(
                launchJson, root, parsed, parsed.configurations().get(0), b));
        FakeDebugAdapter.Session launched = nextSession("launch");
        assertEquals("spec", launched.launchArgs.get("script"));
        assertTrue(Files.readString(rdbgArgs).strip().endsWith("-- rake spec"), Files.readString(rdbgArgs));
        assertEquals("Rake", sessionName());
        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));

        FxTestSupport.runOnFx(() -> debug.runLaunchConfiguration(
                launchJson, root, parsed, parsed.configurations().get(1), b));
        FakeDebugAdapter.Session attached = nextSession("attach");
        assertTrue(attached.attached);
        assertEquals(true, attached.launchArgs.get("localfs"));
        assertEquals("Server", sessionName());
    }

    @Test
    void whyEachCannotBeDebuggedIsSaid() throws Exception {
        String[][] cases = {
            {"csharp", "status.debug.csharpOff", "status.debug.netcoredbgMissing"},
            {"ruby", "status.debug.rubyOff", "status.debug.rdbgMissing"},
            {"php", "status.debug.phpOff", "status.debug.phpDebugMissing"}
        };
        var s = fx.shared.getSettings();
        DebugAdapterSettings.Binding[] bindings = {
            DebugAdapterSettings.of("csharp"), DebugAdapterSettings.of("ruby"), DebugAdapterSettings.of("php")
        };
        for (int i = 0; i < cases.length; i++) {
            String[] c = cases[i];
            DebugAdapterSettings.Binding binding = bindings[i];
            String configured = call(() -> binding.path().apply(s));
            try {
                FxTestSupport.runOnFx(() -> binding.setEnabled().accept(s, false));
                assertEquals(tr(c[1]), call(() -> debug.unavailableMessage(c[0], null)));
                FxTestSupport.runOnFx(() -> {
                    binding.setEnabled().accept(s, true);
                    binding.setPath().accept(s, fx.configDir.resolve("none").toString());
                    debug.applySupport();
                });
                await(c[0] + " not found", () -> !call(() -> debug.debugEffectiveFor(c[0])));
                assertEquals(tr(c[2]), call(() -> debug.unavailableMessage(c[0], null)));
            } finally {
                FxTestSupport.runOnFx(() -> {
                    binding.setPath().accept(s, configured);
                    debug.applySupport();
                });
                await(c[0] + " available again", () -> call(() -> debug.debugEffectiveFor(c[0])));
            }
        }
    }
}
