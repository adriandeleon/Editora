package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of an Electron app debugged as one, through the real js-debug and Electron, headless (Chromium's
 * headless Ozone platform, no display): F5 on the app's main file stops in the main process and then in its window's
 * script, and Stop ends the app; Run File starts the app. The app's {@code node_modules/electron} is linked to an
 * installed Electron: {@code -Delectron.dir=<a folder whose node_modules has electron>}, else
 * {@code ~/.cache/editora/electron-probe}. Run with {@code ./mvnw test -Dtest=ElectronDebugProbeFxTest -Dgroups=probe
 * -Dlsp.probe=true -Dnode.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class ElectronDebugProbeFxTest {

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    private static final String MAIN = """
            const { app, BrowserWindow } = require('electron');
            function size() {
              const width = 400;
              return width;
            }
            app.whenReady().then(() => {
              console.log('shelf ready');
              const win = new BrowserWindow({ width: size(), height: 300, show: false });
              win.loadFile('index.html');
            });
            app.on('window-all-closed', () => app.quit());
            """;

    /** {@code return width;}, 0-based. */
    private static final int MAIN_LINE = 3;

    private static final String RENDERER = """
            let count = 0;
            function tick(n) {
              const doubled = n * 2;
              document.title = 'tick ' + doubled;
            }
            setInterval(() => tick(++count), 300);
            """;

    /** {@code const doubled = n * 2;}, 0-based. */
    private static final int RENDERER_LINE = 2;

    /** Electron without a display, and without the sandbox a CI runner's kernel refuses it. */
    private static final String HEADLESS = "--ozone-platform=headless --no-sandbox";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    /** An installed Electron package to link the app to; the test skipped without it, js-debug or the opt-in. */
    private static Path electron() {
        assumeTrue(Boolean.getBoolean("lsp.probe") && Boolean.getBoolean("node.probe"), "opt-in: Node probes");
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", HOME).isPresent(), "needs js-debug");
        Path dir = Path.of(System.getProperty(
                "electron.dir", HOME.resolve(".cache/editora/electron-probe").toString()));
        Path pkg = dir.resolve("node_modules/electron");
        assumeTrue(Files.isRegularFile(pkg.resolve("path.txt")), "needs Electron installed in " + dir);
        return pkg;
    }

    private record App(Path root, Path main, Path renderer) {}

    private static App app(Path dir, Path electron) throws Exception {
        Path root = Files.createDirectory(dir.resolve("shelf-app")).toRealPath();
        Files.writeString(root.resolve("package.json"), """
                {"name": "shelf-app", "version": "1.0.0", "main": "main.js",
                 "devDependencies": {"electron": "*"}}
                """);
        Files.createDirectories(root.resolve("node_modules"));
        Files.createSymbolicLink(root.resolve("node_modules/electron"), electron);
        Files.writeString(root.resolve("index.html"), """
                <!doctype html><html><body><h1>shelf</h1><script src="renderer.js"></script></body></html>
                """);
        return new App(
                root,
                Files.writeString(root.resolve("main.js"), MAIN),
                Files.writeString(root.resolve("renderer.js"), RENDERER));
    }

    private static FxWindowFixture window(Path dir, Path... open) throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        List<MainController.OpenTarget> targets = java.util.Arrays.stream(open)
                .map(p -> new MainController.OpenTarget(p, 0, 0))
                .toList();
        return FxWindowFixture.create(configDir, false, false, false, targets, true, c -> {});
    }

    @Test
    void f5OnTheMainFileStopsInTheMainProcessThenInTheWindowAndStopEndsTheApp(@TempDir Path dir) throws Exception {
        App app = app(dir, electron());
        FxWindowFixture fx = window(dir, app.renderer(), app.main());
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DebugCoordinator.Ops ops = FxTestSupport.field(debug, "ops");
            await("JavaScript debugging available", 60, () -> call(() -> debug.debugEffectiveFor("javascript")));
            EditorBuffer main = FxTestSupport.callOnFx(c::activeBuffer);
            EditorBuffer renderer = FxTestSupport.callOnFx(() -> ops.bufferForPath(app.renderer()));
            FxTestSupport.runOnFx(() -> {
                main.toggleBreakpoint(MAIN_LINE);
                renderer.toggleBreakpoint(RENDERER_LINE);
            });
            setArgs(c, app.main());
            run(c, "debug.start");
            awaitStop(debug, MAIN_LINE, "main.js");
            FxTestSupport.runOnFx(() -> main.toggleBreakpoint(MAIN_LINE));
            FxTestSupport.runOnFx(() -> debug.dap().resume());
            awaitStop(debug, RENDERER_LINE, "renderer.js"); // the window's script, in the renderer's session
            FxTestSupport.runOnFx(() -> renderer.toggleBreakpoint(RENDERER_LINE));
            run(c, "debug.stop"); // the compound's Stop: both sessions, and the app
            await("the app ended", 60, () -> call(() -> !debug.dap().isActive()) && !electronRunning(app.root()));
        } finally {
            fx.dispose();
        }
    }

    @Test
    void runFileOnTheMainFileStartsTheApp(@TempDir Path dir) throws Exception {
        App app = app(dir, electron());
        FxWindowFixture fx = window(dir, app.main());
        try {
            MainController c = fx.controller;
            setArgs(c, app.main());
            await(
                    "a ▶ for the main file",
                    30,
                    () -> call(
                            () -> c.activeBuffer() != null && c.activeBuffer().isRunnable()));
            run(c, "file.run");
            await(
                    "the app's output",
                    90,
                    () -> call(() -> ((org.fxmisc.richtext.CodeArea)
                                    FxTestSupport.field(c.runCoordinator.panel(), "output"))
                            .getText()
                            .contains("shelf ready")));
            FxTestSupport.runOnFx(c.runCoordinator::stopRun);
            await("the app ended", 60, () -> !electronRunning(app.root()));
        } finally {
            fx.dispose();
        }
    }

    /** Gives {@code main} the program arguments that run Electron headless, as Run with Arguments would. */
    private static void setArgs(MainController c, Path main) throws Exception {
        RunCoordinator.Ops runOps = FxTestSupport.field(c.runCoordinator, "ops");
        FxTestSupport.runOnFx(() -> runOps.setProgramArgs(main, HEADLESS));
    }

    /** Whether an Electron of the app in {@code root} still runs. */
    private static boolean electronRunning(Path root) {
        // Electron's processes run in the app's folder, under its program: found by their working directory.
        return ProcessHandle.allProcesses()
                .anyMatch(p ->
                        p.info().command().map(cmd -> cmd.endsWith("electron")).orElse(false)
                                && cwdOf(p).map(root::equals).orElse(false));
    }

    private static java.util.Optional<Path> cwdOf(ProcessHandle p) {
        try {
            return java.util.Optional.of(Files.readSymbolicLink(Path.of("/proc", String.valueOf(p.pid()), "cwd")));
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException e) {
            return java.util.Optional.empty(); // not Linux, or not ours to read: counted as ended
        }
    }

    private static void run(MainController c, String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run(command));
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

    /** A stop at {@code line} of {@code file} in the session shown. */
    private static void awaitStop(DebugCoordinator debug, int line, String file) throws InterruptedException {
        try {
            await(
                    "a stop at " + file + ":" + (line + 1),
                    120,
                    () -> call(() -> debug.dap().state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().get(0).line() == line
                            && debug.panel().callStack().get(0).file() != null
                            && debug.panel().callStack().get(0).file().endsWith(file)));
        } catch (AssertionError e) {
            String top = call(() -> debug.panel().callStack().isEmpty()
                    ? "no frames"
                    : debug.panel().callStack().get(0).name() + " at "
                            + debug.panel().callStack().get(0).file() + ":"
                            + (debug.panel().callStack().get(0).line() + 1));
            String console = call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(debug.panel(), "console")
                    .getText());
            throw new AssertionError(
                    e.getMessage() + " (" + call(() -> debug.dap().state()) + ", " + top + ")\n" + console, e);
        }
    }
}
