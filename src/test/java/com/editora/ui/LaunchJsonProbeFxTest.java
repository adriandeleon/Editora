package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.dap.DapManager;
import com.editora.dap.LaunchJson;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of launch.json configurations, custom adapters and GDB against the real tools, in a window: a CodeLLDB
 * ({@code lldb}) configuration whose {@code preLaunchTask} compiles the program, a {@code gdb} and a {@code cppdbg} one
 * on GDB's DAP mode, an adapter of the user's own ({@code debug-adapters.json}) that is GDB again, F5 with C and C++ set
 * to GDB, and Delve's {@code exec} mode on a binary Go built. Each stops at a breakpoint. Run with
 * {@code ./mvnw test -Dtest=LaunchJsonProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs {@code cc}, GDB 14+ and
 * CodeLLDB (as {@code NativeDebugProbeFxTest} finds it); Go and Delve for the Go case.
 */
@Tag("probe")
@Tag("fx")
class LaunchJsonProbeFxTest {

    private static final String C_FILE = """
            #include <stdio.h>

            int main(int argc, char **argv) {
                int total = 0;
                for (int i = 0; i < argc; i++) {
                    total += i;
                }
                printf("args %d total %d\\n", argc, total);
                return 0;
            }
            """;

    private static final String LAUNCH = """
            {
              // launch.json as VS Code writes it: comments, and trailing commas.
              "version": "0.2.0",
              "configurations": [
                {
                  "name": "C on CodeLLDB",
                  "type": "lldb",
                  "request": "launch",
                  "program": "${workspaceFolder}/shelf",
                  "args": ["one", "two"],
                  "cwd": "${workspaceFolder}",
                  "preLaunchTask": "build",
                },
                {
                  "name": "C on GDB",
                  "type": "gdb",
                  "request": "launch",
                  "program": "${workspaceFolder}/shelf",
                  "args": ["one"],
                },
                {
                  "name": "C on cppdbg",
                  "type": "cppdbg",
                  "request": "launch",
                  "MIMode": "gdb",
                  "program": "${workspaceFolder}/shelf",
                  "args": [],
                  "cwd": "${workspaceFolder}",
                  "environment": [{ "name": "SHELF", "value": "1" }],
                },
                {
                  "name": "C on my GDB",
                  "type": "my-gdb",
                  "request": "launch",
                  "program": "${workspaceFolder}/shelf",
                },
              ],
            }
            """;

    private static final String TASKS = """
            {
              "version": "2.0.0",
              "tasks": [
                { "label": "build", "type": "shell", "command": "cc", "args": ["-g", "-O0", "-o", "shelf", "shelf.c"] }
              ]
            }
            """;

    private static final String ADAPTERS = """
            {
              "adapters": [
                { "type": "my-gdb", "name": "My GDB", "command": ["gdb", "-q", "-i", "dap"], "transport": "stdio" }
              ]
            }
            """;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
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
        long end = System.nanoTime() + 180_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    private static void awaitStop(DapManager dap, DebugCoordinator debug, int line, String what)
            throws InterruptedException {
        try {
            await(
                    what,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().get(0).line() == line));
        } catch (AssertionError e) {
            String top = call(() -> debug.panel().callStack().isEmpty()
                    ? "no frames"
                    : debug.panel().callStack().get(0).name() + " at "
                            + (debug.panel().callStack().get(0).line() + 1));
            throw new AssertionError(e.getMessage() + " (" + call(dap::state) + ", " + top + ")", e);
        }
    }

    @Test
    void launchJsonConfigurationsCustomAdaptersAndGdbEachStop(@TempDir Path dir) throws Exception {
        String configured = NativeDebugProbeFxTest.assumeToolsAndCodelldb("cc", "gdb");
        Path root = Files.createDirectories(dir.resolve("shelf")).toRealPath();
        Path file = Files.writeString(root.resolve("shelf.c"), C_FILE);
        Files.createDirectories(root.resolve(".vscode"));
        Path launchJson = Files.writeString(root.resolve(".vscode/launch.json"), LAUNCH);
        Files.writeString(root.resolve(".vscode/tasks.json"), TASKS);
        Path configDir = Files.createDirectory(dir.resolve("config"));
        Files.writeString(configDir.resolve("debug-adapters.json"), ADAPTERS);
        com.editora.config.SharedConfig seed = new com.editora.config.SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setRustDebugPath(configured);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("C debugging available", () -> call(() -> debug.debugEffectiveFor("cpp")));
            com.editora.editor.EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(7)); // the printf
            LaunchJson.File parsed = LaunchJson.parse(Files.readString(launchJson));
            assertEquals(4, parsed.configurations().size());

            for (LaunchJson.Configuration config : parsed.configurations()) {
                FxTestSupport.runOnFx(() -> debug.runLaunchConfiguration(launchJson, root, parsed, config, b));
                awaitStop(dap, debug, 7, config.name() + " stops at the printf");
                assertEquals(config.name(), call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile")));
                assertTrue(Files.isRegularFile(root.resolve("shelf")), "the preLaunchTask built it");
                FxTestSupport.runOnFx(dap::stop);
                await("stopped", () -> !call(dap::isActive));
            }

            // F5 on the file with C and C++ set to GDB: compiled with cc, then GDB's DAP mode.
            Files.delete(root.resolve(".vscode/launch.json")); // F5 is not a launch.json start
            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setCppDebugger("gdb");
                debug.applySupport();
            });
            await("C debugging on GDB available", () -> call(() -> debug.debugEffectiveFor("cpp")));
            FxTestSupport.runOnFx(() ->
                    ((com.editora.command.CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitStop(dap, debug, 7, "F5 on GDB stops at the printf");
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    @Test
    void delvesExecModeDebugsABinaryGoBuilt(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        String go = ProcessRunner.resolveExecutable(List.of("go")).getFirst();
        assumeTrue(Path.of(go).isAbsolute(), "needs go");
        assumeTrue(DapManager.locateExternal(com.editora.dap.DebugLanguages.GO, "") != null, "needs Delve");
        Path root = Files.createDirectories(dir.resolve("app")).toRealPath();
        Files.writeString(root.resolve("go.mod"), "module example.com/app\n\ngo 1.22\n");
        Path main = Files.writeString(
                root.resolve("main.go"),
                "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tn := 41\n\tn++\n\tfmt.Println(\"n\", n)\n}\n");
        ProcessRunner.Result built = ProcessRunner.run(
                root, java.time.Duration.ofMinutes(3), List.of(go, "build", "-gcflags=all=-N -l", "-o", "app", "."));
        assertTrue(built.ok(), built.err());
        Files.createDirectories(root.resolve(".vscode"));
        Path launchJson = Files.writeString(
                root.resolve(".vscode/launch.json"),
                "{\"configurations\": [{\"name\": \"Go binary\", \"type\": \"go\", \"request\": \"launch\","
                        + " \"mode\": \"exec\", \"program\": \"${workspaceFolder}/app\"}]}");
        Path configDir = Files.createDirectory(dir.resolve("config"));
        com.editora.config.SharedConfig seed = new com.editora.config.SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(main, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("Go debugging available", () -> call(() -> debug.debugEffectiveFor("go")));
            com.editora.editor.EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(6)); // n++
            LaunchJson.File parsed = LaunchJson.parse(Files.readString(launchJson));
            LaunchJson.Configuration config = parsed.configurations().getFirst();
            assertNotNull(config);
            FxTestSupport.runOnFx(() -> debug.runLaunchConfiguration(launchJson, root, parsed, config, b));
            awaitStop(dap, debug, 6, "the Go binary stops at n++");
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    @Test
    void pythonAndNodeConfigurationsCarryTheirArgumentsAndEnvironment(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path home = Path.of(System.getProperty("user.home"));
        assumeTrue(com.editora.dap.DebugAdapterLocator.locateDebugpy("", home).isPresent(), "needs debugpy");
        assumeTrue(
                com.editora.dap.DebugAdapterLocator.locateJsDebugServer("", home)
                        .isPresent(),
                "needs js-debug");
        Path root = Files.createDirectories(dir.resolve("app")).toRealPath();
        Path py = Files.writeString(
                root.resolve("app.py"),
                "import os, sys\nword = os.environ.get('GREETING', '?')\nprint('py', word, ' '.join(sys.argv[1:]))\n");
        Path js = Files.writeString(
                root.resolve("app.js"),
                "const word = process.env.GREETING || '?';\nconsole.log('js', word, process.argv.slice(2).join(' '));\n");
        Files.createDirectories(root.resolve(".vscode"));
        Path launchJson = Files.writeString(root.resolve(".vscode/launch.json"), """
                {
                  "configurations": [
                    { "name": "Python app", "type": "debugpy", "request": "launch", "program": "${workspaceFolder}/app.py",
                      "args": ["one", "two"], "env": { "GREETING": "hi" } },
                    { "name": "Node app", "type": "node", "request": "launch", "program": "${workspaceFolder}/app.js",
                      "args": ["three"], "env": { "GREETING": "hey" } },
                  ]
                }
                """);
        Path configDir = Files.createDirectory(dir.resolve("config"));
        com.editora.config.SharedConfig seed = new com.editora.config.SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(py, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("Python debugging available", () -> call(() -> debug.debugEffectiveFor("python")));
            await("JavaScript debugging available", () -> call(() -> debug.debugEffectiveFor("javascript")));
            com.editora.editor.EditorBuffer pyBuffer = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> pyBuffer.toggleBreakpoint(2));
            LaunchJson.File parsed = LaunchJson.parse(Files.readString(launchJson));

            FxTestSupport.runOnFx(() -> debug.runLaunchConfiguration(
                    launchJson, root, parsed, parsed.configurations().get(0), pyBuffer));
            awaitStop(dap, debug, 2, "the Python program stops at its print");
            FxTestSupport.runOnFx(dap::resume);
            await(
                    "the Python program's output",
                    () -> call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(debug.panel(), "console")
                                    .getText())
                            .contains("py hi one two"));
            await("the Python session ends", () -> !call(dap::isActive));

            FxTestSupport.runOnFx(() -> c.fileWorkflows.openPath(js));
            await(
                    "app.js in front",
                    () -> call(() -> c.activeBuffer() != null
                            && c.activeBuffer().getPath() != null
                            && c.activeBuffer().getPath().toRealPath().equals(js)));
            com.editora.editor.EditorBuffer jsBuffer = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> jsBuffer.toggleBreakpoint(1));
            FxTestSupport.runOnFx(() -> debug.runLaunchConfiguration(
                    launchJson, root, parsed, parsed.configurations().get(1), jsBuffer));
            awaitStop(dap, debug, 1, "the Node program stops at its log");
            FxTestSupport.runOnFx(dap::resume);
            await(
                    "the Node program's output",
                    () -> call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(debug.panel(), "console")
                                    .getText())
                            .replaceAll("\u001B\\[[0-9;]*m", "")
                            .contains("js hey three"));
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    @Test
    void codelldbsCargoFieldBuildsThenDebugsTheBinaryItsFilterPicks(@TempDir Path dir) throws Exception {
        String configured = NativeDebugProbeFxTest.assumeToolsAndCodelldb("cargo");
        Path root = RustDebugProbeFxTest.cargoPackage(Files.createDirectories(dir.resolve("shelf")));
        Path main = root.resolve("src/main.rs");
        Files.createDirectories(root.resolve(".vscode"));
        Path launchJson = Files.writeString(
                root.resolve(".vscode/launch.json"),
                "{\"configurations\": [{\"name\": \"cargo\", \"type\": \"lldb\", \"request\": \"launch\","
                        + " \"cargo\": {\"args\": [\"build\", \"--bin=shelf\"],"
                        + " \"filter\": {\"name\": \"shelf\", \"kind\": \"bin\"}}}]}");
        Path configDir = Files.createDirectory(dir.resolve("config"));
        com.editora.config.SharedConfig seed = new com.editora.config.SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setRustDebugPath(configured);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(main, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            fx.shared.getTrustStore().trust(root);
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("Rust debugging available", () -> call(() -> debug.debugEffectiveFor("rust")));
            com.editora.editor.EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(15));
            LaunchJson.File parsed = LaunchJson.parse(Files.readString(launchJson));
            FxTestSupport.runOnFx(() -> debug.runLaunchConfiguration(
                    launchJson, root, parsed, parsed.configurations().getFirst(), b));
            awaitStop(dap, debug, 15, "the cargo-built program stops in its loop");
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }
}
