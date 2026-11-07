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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe against the installed JDT LS and java-debug adapter, in a real window: with main stopped at a
 * breakpoint the Threads view lists main suspended and a worker running; Pause Thread suspends the worker alone (its
 * output stops), and Resume Thread resumes it alone, main's stop coming back on screen. Run it with
 * {@code ./mvnw test -Dtest=ThreadsViewProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class ThreadsViewProbeFxTest {

    private static final String SOURCE = """
            public class Main {
                public static void main(String[] args) throws Exception {
                    Thread worker = new Thread(() -> {
                        for (int n = 0; n < 5000; n++) {
                            System.out.println("tick " + n);
                            try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                        }
                    }, "worker");
                    worker.setDaemon(true);
                    worker.start();
                    int total = 0;
                    for (int i = 0; i < 1000; i++) {
                        total += i;
                        Thread.sleep(50);
                    }
                    System.out.println("done " + total);
                }
            }
            """;

    private static final int BREAK_LINE = 12; // total += i;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void pauseAndResumeOneThreadFromTheThreadsView(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path jdtls = Path.of(System.getProperty("user.home"), ".editora/plugins/lsp/java/bin/jdtls");
        Path plugin = DebugAdapterLocator.locate("", Path.of(System.getProperty("user.home")))
                .orElse(null);
        assumeTrue(Files.isExecutable(jdtls) && plugin != null);
        Path file = Files.createDirectory(dir.resolve("loose")).resolve("Main.java");
        Files.writeString(file, SOURCE);
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setLspSupport(true);
        seed.getSettings().setJavaLspEnabled(true);
        seed.getSettings().setJavaLspCommand(jdtls.toString());
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setJavaDebugPluginPath(plugin.toString());
        assertTrue(seed.saveSettings());
        seed.shutdown();

        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("Java debugging available", 60, () -> call(debug::debugEffective));
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(BREAK_LINE));

            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitStop(dap, BREAK_LINE, debug);
            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.threads"));
            await("main suspended and the worker running", 20, () -> {
                DapModels.ThreadStatus main = row(debug, "main");
                DapModels.ThreadStatus worker = row(debug, "worker");
                return main != null && main.paused() && main.shown() && worker != null && !worker.paused();
            });
            int mainId = row(debug, "main").id();
            int workerId = row(debug, "worker").id();
            await("the worker printing while main is stopped", 10, () -> ticks(debug) > 5);

            // Pause Thread: the worker alone. Its stop is shown, main stays suspended, and its output stops.
            FxTestSupport.runOnFx(() -> dap.pauseThread(workerId));
            await(
                    "the worker shown suspended",
                    20,
                    () -> call(dap::currentThreadId) == workerId && call(dap::state) == DapManager.State.SUSPENDED);
            FxTestSupport.runOnFx(() -> debug.panel().refreshThreads());
            await("both suspended in the list", 20, () -> {
                DapModels.ThreadStatus worker = row(debug, "worker");
                DapModels.ThreadStatus main = row(debug, "main");
                return worker != null && worker.paused() && worker.shown() && main != null && main.paused();
            });
            Thread.sleep(500); // output already on its way
            int atPause = ticks(debug);
            Thread.sleep(1000);
            assertEquals(atPause, ticks(debug), "the worker prints nothing while suspended");

            // Resume Thread on the worker: it runs again, and main's stop is back on screen.
            FxTestSupport.runOnFx(() -> dap.resumeThread(workerId));
            await(
                    "main shown again",
                    20,
                    () -> call(dap::currentThreadId) == mainId && call(dap::state) == DapManager.State.SUSPENDED);
            await("the worker printing again", 10, () -> ticks(debug) > atPause + 10);
            await("the list says so", 20, () -> {
                DapModels.ThreadStatus worker = row(debug, "worker");
                return worker != null && !worker.paused();
            });

            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 20, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    private static DapModels.ThreadStatus row(DebugCoordinator debug, String name) {
        return call(() -> {
            for (DapModels.ThreadStatus t : debug.panel().threadList().getItems()) {
                if (name.equals(DebugPanelText.threadName(t.name()))) {
                    return t;
                }
            }
            return null;
        });
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

    private static void awaitStop(DapManager dap, int line, DebugCoordinator debug) throws InterruptedException {
        await(
                "a stop on line " + (line + 1),
                90,
                () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()
                        && debug.panel().callStack().get(0).line() == line));
    }

    private static int ticks(DebugCoordinator debug) {
        String text =
                call(() -> ((org.fxmisc.richtext.CodeArea) FxTestSupport.field(debug.panel(), "console")).getText());
        return (int) text.lines().filter(l -> l.startsWith("tick ")).count();
    }
}
