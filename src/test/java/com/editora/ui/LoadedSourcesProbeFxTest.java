package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugAdapterLocator;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Loaded Sources and Modules against real adapters, in a window, at a stop: js-debug lists the
 * program's file first and Node's internals after it, opens the file in its tab and an internal read-only with the
 * text js-debug gives; CodeLLDB lists a C program's modules (the program, with its debug info, and the C library);
 * debugpy answers the modules request with the modules whose code has run (the script, as {@code __main__}). Run with {@code ./mvnw test -Dtest=LoadedSourcesProbeFxTest -Dgroups=probe
 * -Dlsp.probe=true} (the Node test needs {@code -Dnode.probe=true} too, the C test {@code cc} and CodeLLDB, found as
 * {@code NativeDebugProbeFxTest} finds it).
 */
@Tag("probe")
@Tag("fx")
class LoadedSourcesProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @Test
    void javascriptListsItsScriptsAndOpensAnInternalReadOnly(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe") && Boolean.getBoolean("node.probe"), "opt-in: Node probes");
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", HOME).isPresent(), "needs js-debug");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("loop.js"),
                "const fs = require('fs');\nlet total = 0;\nfor (let i = 0; i < 3; i++) {\n  total += i;\n}\n"
                        + "console.log('total', total, typeof fs.readFileSync);\n");
        inWindow(dir, file, 3, "javascript", s -> {}, (c, debug, dap) -> {
            LoadedSourcesPicker picker = debug.loadedSources();
            FxTestSupport.runOnFx(debug::showLoadedSources);
            await("the list", 30, () -> call(() -> picker.sourcesPicker != null));
            List<DapModels.LoadedSource> listed = call(() -> OverlayDriver.choices(c));
            assertEquals(file, listed.getFirst().file(), "the program's own file first: " + names(listed));
            DapModels.LoadedSource internal = listed.stream()
                    .filter(s -> s.file() == null && s.sourceReference() > 0)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no internal listed: " + names(listed)));
            FxTestSupport.runOnFx(() -> OverlayDriver.dismiss(c));
            FxTestSupport.runOnFx(() -> picker.open(internal));
            await(
                    "the internal open, read-only",
                    30,
                    () -> call(() -> c.activeBuffer() != null
                            && c.activeBuffer().getPath() == null
                            && c.activeBuffer().getArea().getLength() > 100));
            EditorBuffer opened = call(c::activeBuffer);
            assertTrue(call(opened::isViewMode), "read-only");
            assertEquals("javascript", call(opened::getLanguage));
        });
    }

    @Test
    void cListsItsModules(@TempDir Path dir) throws Exception {
        String configured = NativeDebugProbeFxTest.assumeToolsAndCodelldb("cc");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("count.c"),
                "#include <stdio.h>\nint main(void) {\n    int total = 0;\n    total += 2;\n"
                        + "    printf(\"%d\\n\", total);\n    return 0;\n}\n");
        inWindow(dir, file, 3, "c", s -> s.setRustDebugPath(configured), (c, debug, dap) -> {
            assertFalse(call(dap::listsLoadedSources), "CodeLLDB lists no sources");
            LoadedSourcesPicker picker = debug.loadedSources();
            await("the modules known", 30, () -> call(dap::listsModules));
            FxTestSupport.runOnFx(debug::showModules);
            await("the list", 30, () -> call(() -> picker.modulesPicker != null));
            List<DapModels.ModuleInfo> listed = call(() -> OverlayDriver.choices(c));
            String home = dir.toRealPath().toString();
            assertTrue(
                    listed.stream()
                            .anyMatch(m -> m.name().startsWith("count")
                                    && m.path() != null
                                    && m.path().startsWith(home)),
                    "the program: " + listed);
            assertTrue(listed.stream().anyMatch(m -> m.name().startsWith("libc")), "the C library: " + listed);
            FxTestSupport.runOnFx(() -> OverlayDriver.dismiss(c));
        });
    }

    @Test
    void pythonListsItsModules(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DebugAdapterLocator.locateDebugpy("", HOME).isPresent(), "needs debugpy");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("loop.py"),
                "import json\ntotal = 0\nfor i in range(3):\n    total += i\nprint(json.dumps(total))\n");
        inWindow(dir, file, 3, "python", s -> {}, (c, debug, dap) -> {
            LoadedSourcesPicker picker = debug.loadedSources();
            FxTestSupport.runOnFx(debug::showModules);
            await("the list", 30, () -> call(() -> picker.modulesPicker != null));
            List<DapModels.ModuleInfo> listed = call(() -> OverlayDriver.choices(c));
            // debugpy lists the modules whose code has run under it, not every one imported.
            assertTrue(
                    listed.stream()
                            .anyMatch(m -> m.name().equals("__main__")
                                    && file.toString().equals(m.path())),
                    "the script, as __main__: " + listed);
            FxTestSupport.runOnFx(() -> OverlayDriver.dismiss(c));
        });
    }

    private static String names(List<DapModels.LoadedSource> listed) {
        return listed.stream()
                .limit(8)
                .map(DapModels.LoadedSource::name)
                .toList()
                .toString();
    }

    private interface AtStop {
        void run(MainController c, DebugCoordinator debug, DapManager dap) throws Exception;
    }

    /** Opens {@code file} in a window, starts it with a breakpoint on {@code line}, and runs {@code atStop} there. */
    private void inWindow(
            Path dir,
            Path file,
            int line,
            String language,
            java.util.function.Consumer<com.editora.config.Settings> settings,
            AtStop atStop)
            throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        settings.accept(seed.getSettings());
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await(language + " debugging available", 90, () -> call(() -> debug.debugEffectiveFor(language)));
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(line));
            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitStop(dap, debug, line);
            atStop.run(c, debug, dap);
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));
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

    private static void awaitStop(DapManager dap, DebugCoordinator debug, int line) throws InterruptedException {
        await(
                "a stop on line " + (line + 1),
                120,
                () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()
                        && debug.panel().callStack().getFirst().line() == line));
    }
}
