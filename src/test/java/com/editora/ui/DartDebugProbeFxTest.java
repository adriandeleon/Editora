package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugLanguages;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Dart debugging against the SDK's own adapters, in a window: F5 on a Dart program ({@code dart
 * debug_adapter}) stops at a breakpoint in its loop with its locals, the adapter's own pause as the isolate starts
 * not shown, and Continue runs it to its end; a Flutter app's file ({@code flutter debug_adapter}) is built, run on
 * this computer's Linux desktop, stops at its breakpoint, and Stop ends the app. Run with {@code ./mvnw test
 * -Dtest=DartDebugProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs {@code dart} on the PATH, and for the Flutter case
 * {@code flutter}, Linux and a display.
 */
@Tag("probe")
@Tag("fx")
class DartDebugProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    /** 0-based line 3 is {@code total += i;}. */
    static final String PROGRAM = """
            void main(List<String> args) {
              var total = 0;
              for (var i = 0; i < 3; i++) {
                total += i;
                print('running $total');
              }
              print('total $total');
            }
            """;

    @Test
    void aProgramStopsInItsLoopWithItsLocalsAndRunsToItsEnd(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DapManager.locateExternal(DebugLanguages.DART, "") != null, "needs dart on the PATH");
        Path pkg = Files.createDirectory(dir.resolve("probe")).toRealPath();
        Files.writeString(pkg.resolve("pubspec.yaml"), "name: probe\nenvironment:\n  sdk: ^3.0.0\n");
        Path main = Files.writeString(Files.createDirectory(pkg.resolve("bin")).resolve("main.dart"), PROGRAM);
        try (Window w = Window.open(dir, pkg, main)) {
            w.f5At(3);
            w.awaitStop(main, 3, 120);
            assertEquals("0", w.evaluate("total"), "total, before the first sum");
            assertEquals("0", w.evaluate("i"), "the loop's first i");
            assertEquals(1, w.call(() -> w.debug.panel().history().size()), "the isolate's own start is not shown");
            FxTestSupport.runOnFx(() -> w.buffer.toggleBreakpoint(3)); // off: Continue runs to the end
            FxTestSupport.runOnFx(w.dap::resume);
            await("the end", 120, () -> !w.call(w.dap::isActive));
        }
    }

    /** 0-based line 5 is {@code total += i;}. */
    static final String APP = """
            import 'package:flutter/widgets.dart';

            void main() {
              var total = 0;
              for (var i = 0; i < 3; i++) {
                total += i;
              }
              runApp(Text('total $total', textDirection: TextDirection.ltr));
            }
            """;

    @Test
    void aFlutterAppStopsAtItsBreakpointOnTheDesktopAndStopEndsIt(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(com.editora.Os.isLinux(), "Flutter's Linux desktop");
        assumeTrue(
                System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null,
                "the app opens a window: needs a display");
        String flutter = DapManager.locateExternal(DebugLanguages.FLUTTER, "");
        assumeTrue(flutter != null, "needs flutter on the PATH");
        Path app = dir.resolve("probe_app").toAbsolutePath();
        // The Linux runner's files, without the template's pub packages (offline: only Flutter's own are cached).
        Process create = new ProcessBuilder(
                        flutter,
                        "create",
                        "--platforms=linux",
                        "--no-pub",
                        "--project-name",
                        "probe_app",
                        app.toString())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("create.log").toFile())
                .start();
        assumeTrue(create.waitFor(300, TimeUnit.SECONDS) && create.exitValue() == 0, "flutter create");
        app = app.toRealPath();
        Files.writeString(
                app.resolve("pubspec.yaml"),
                "name: probe_app\nenvironment:\n  sdk: ^3.0.0\ndependencies:\n  flutter:\n    sdk: flutter\n");
        Files.deleteIfExists(app.resolve("analysis_options.yaml"));
        try (var walk = Files.walk(app.resolve("test"))) { // its widget test names the template's app
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
        Path main = Files.writeString(app.resolve("lib/main.dart"), APP);
        Path bundle = app.resolve("build");
        try (Window w = Window.open(dir, app, main)) {
            w.f5At(5);
            w.awaitStop(main, 5, 600); // flutter builds the app first
            assertEquals("0", w.evaluate("total"));
            FxTestSupport.runOnFx(w.dap::stop);
            await("stopped", 120, () -> !w.call(w.dap::isActive));
            await(
                    "the app ended",
                    60,
                    () -> ProcessHandle.allProcesses()
                            .noneMatch(p -> p.info().command().orElse("").startsWith(bundle.toString())));
        }
    }

    /** A window with {@code file} open, its folder trusted, debugging on. */
    private static final class Window implements AutoCloseable {
        final FxWindowFixture fx;
        final DebugCoordinator debug;
        final DapManager dap;
        final CommandRegistry registry;
        final EditorBuffer buffer;

        private Window(FxWindowFixture fx) throws Exception {
            this.fx = fx;
            MainController c = fx.controller;
            debug = FxTestSupport.field(c, "debugCoordinator");
            dap = FxTestSupport.field(c, "dapManager");
            registry = FxTestSupport.field(c, "registry");
            await("Dart debugging available", 120, () -> call(() -> debug.debugEffectiveFor("dart")));
            buffer = FxTestSupport.callOnFx(c::activeBuffer);
        }

        static Window open(Path dir, Path root, Path file) throws Exception {
            Path configDir = Files.createDirectory(dir.resolve("config"));
            SharedConfig seed = new SharedConfig(configDir, false);
            seed.load();
            seed.getSettings().setDebugSupport(true);
            seed.getTrustStore().trust(root); // the trust question (pub runs build hooks), answered
            assertTrue(seed.saveSettings());
            seed.saveTrust();
            seed.shutdown();
            return new Window(FxWindowFixture.create(
                    configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {}));
        }

        void f5At(int line) throws Exception {
            assertTrue(call(buffer::isBreakpointsEnabled), "a Dart file has the breakpoint gutter");
            FxTestSupport.runOnFx(() -> buffer.toggleBreakpoint(line));
            FxTestSupport.runOnFx(() -> registry.run("debug.start"));
        }

        void awaitStop(Path file, int line, int seconds) throws Exception {
            await(
                    "a stop",
                    seconds,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()));
            DapModels.StackFrameInfo top = call(() -> debug.panel().callStack().getFirst());
            assertEquals(line, top.line(), "on the breakpoint: " + top);
            assertEquals(file, top.file());
            assertEquals("main", top.name());
        }

        String evaluate(String expression) throws Exception {
            int frame = call(() -> debug.panel().selectedFrameId());
            java.util.concurrent.atomic.AtomicReference<DapModels.EvalResult> r =
                    new java.util.concurrent.atomic.AtomicReference<>();
            FxTestSupport.runOnFx(() -> dap.evaluateFull(expression, frame, "watch", r::set));
            await(expression, 60, () -> r.get() != null);
            return r.get().result();
        }

        <T> T call(Check<T> check) {
            try {
                return FxTestSupport.callOnFx(check::get);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() throws Exception {
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
        Await.until(condition, what, java.time.Duration.ofSeconds(seconds));
    }
}
