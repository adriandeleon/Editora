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
import com.editora.dap.DebugLanguages;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Thread Dump against debugpy, java-debug with JDT LS, and Delve, in a window. Each program starts
 * three workers that block in one place and stops on a breakpoint in its main thread; the dump lists every thread
 * and merges the three workers into one row. Then, the program running, Thread Dump pauses it, takes the dump and the
 * program goes on. Run with {@code ./mvnw test -Dtest=ThreadDumpProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class ThreadDumpProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @Test
    void python(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DebugAdapterLocator.locateDebugpy("", HOME).isPresent(), "needs debugpy");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("m.py"), """
                import threading, time
                gate = threading.Event()
                def work(n):
                    gate.wait()
                    return n
                workers = [threading.Thread(target=work, args=(i,), name=f"worker-{i}") for i in range(3)]
                for t in workers:
                    t.start()
                time.sleep(0.3)
                x = 1
                for _ in range(1200):
                    time.sleep(0.05)
                gate.set()
                """);
        probe(dir, file, "python", 9, "worker-", "work", st -> {});
    }

    @Test
    void java(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path jdtls = HOME.resolve(".editora/plugins/lsp/java/bin/jdtls");
        Path plugin = DebugAdapterLocator.locate("", HOME).orElse(null);
        assumeTrue(Files.isExecutable(jdtls) && plugin != null, "needs JDT LS and java-debug");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("loose")).toRealPath().resolve("Main.java"), """
                import java.util.concurrent.CountDownLatch;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        CountDownLatch gate = new CountDownLatch(1);
                        for (int i = 0; i < 3; i++) {
                            Thread t = new Thread(() -> { try { gate.await(); } catch (InterruptedException e) {} }, "worker-" + i);
                            t.setDaemon(true);
                            t.start();
                        }
                        Thread.sleep(300);
                        int x = 1;
                        for (int i = 0; i < 1200; i++) Thread.sleep(50);
                        gate.countDown();
                    }
                }
                """);
        probe(dir, file, "java", 10, "worker-", "CountDownLatch.await", st -> {
            st.setLspSupport(true);
            st.setJavaLspEnabled(true);
            st.setJavaLspCommand(jdtls.toString());
            st.setJavaDebugPluginPath(plugin.toString());
        });
    }

    @Test
    void go(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DapManager.locateExternal(DebugLanguages.GO, "") != null, "needs Delve (dlv)");
        String go = com.editora.process.ProcessRunner.resolveExecutable(List.of("go"))
                .getFirst();
        assumeTrue(Path.of(go).isAbsolute(), "needs go on the PATH");
        Path root = Files.createDirectory(dir.resolve("pool")).toRealPath();
        Files.writeString(root.resolve("go.mod"), "module example.com/pool\n\ngo 1.22\n");
        Path file = Files.writeString(root.resolve("main.go"), """
                package main

                import "time"

                func work(gate chan bool) {
                \t<-gate
                }

                func main() {
                \tgate := make(chan bool)
                \tfor i := 0; i < 3; i++ {
                \t\tgo work(gate)
                \t}
                \ttime.Sleep(300 * time.Millisecond)
                \tx := 1
                \tfor i := 0; i < 1200; i++ {
                \t\ttime.Sleep(50 * time.Millisecond)
                \t}
                \tclose(gate)
                \t_ = x
                }
                """);
        // Delve names a goroutine by its function, not a name of the program's.
        probe(dir, file, "go", 14, "main.work", "main.work", st -> {});
    }

    /**
     * Starts debugging {@code file} with a breakpoint on 0-based {@code line} in its main thread, then checks the
     * dump there — the three workers (threads whose name contains {@code worker}) one row, its stack showing
     * {@code frame} — and the dump of the running program.
     */
    private static void probe(
            Path dir,
            Path file,
            String language,
            int line,
            String worker,
            String frame,
            java.util.function.Consumer<com.editora.config.Settings> settings)
            throws Exception {
        FxWindowFixture fx = window(dir, file, settings);
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            await(language + " debugging available", 120, () -> call(() -> debug.debugEffectiveFor(language)));
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(line));
            run(c, "debug.start");
            await(
                    "a stop on line " + (line + 1),
                    180,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().getFirst().line() == line));

            // At the stop: the threads the adapter walks, the workers merged when it walked them.
            run(c, "debug.threadDump");
            ThreadDump atStop = awaitDump(debug, null);
            String text = call(() -> atStop.text(true));
            assertTrue(atStop.threads().size() >= 4, text);
            assertTrue(atStop.threads().getFirst().thread().shown(), "the stopped thread first: " + text);
            assertEquals(line, atStop.threads().getFirst().frames().getFirst().line(), text);
            List<DapModels.ThreadStack> workers = workers(atStop, worker);
            assertEquals(3, workers.size(), text);
            if (workers.stream().noneMatch(t -> t.frames().isEmpty())) {
                assertTrue(
                        call(() -> atStop.groups(true).stream()
                                .anyMatch(g -> g.threads().containsAll(workers))),
                        "the workers' stacks merged: " + text);
                assertTrue(text.contains(frame), text);
            } else {
                // java-debug stops the thread at the breakpoint alone (Suspend All Threads off): the others ran.
                assertTrue(call(() -> atStop.runningWithoutStack() >= 3), text);
            }

            // Running: paused for the dump, then on again, the editor left alone.
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(line));
            FxTestSupport.runOnFx(dap::resume);
            await("running", 30, () -> call(dap::state) == DapManager.State.RUNNING);
            Thread.sleep(300);
            run(c, "debug.threadDump");
            ThreadDump running = awaitDump(debug, atStop);
            String runningText = call(() -> running.text(true));
            assertTrue(running.pausedForIt(), runningText);
            List<DapModels.ThreadStack> pool = workers(running, worker);
            assertEquals(3, pool.size(), runningText);
            assertTrue(pool.stream().noneMatch(t -> t.frames().isEmpty()), "every stack, paused: " + runningText);
            assertTrue(
                    call(() -> running.groups(true).stream()
                            .anyMatch(g -> g.threads().containsAll(pool))),
                    "the workers merged: " + runningText);
            await("running again", 30, () -> call(dap::state) == DapManager.State.RUNNING);
            Thread.sleep(500);
            assertEquals(DapManager.State.RUNNING, call(dap::state), "it stays running");
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    private static List<DapModels.ThreadStack> workers(ThreadDump dump, String worker) {
        return dump.threads().stream()
                .filter(t -> ThreadDump.name(t).contains(worker)
                        || t.frames().stream().anyMatch(f -> f.name().contains(worker)))
                .filter(t -> !t.thread().shown())
                .toList();
    }

    /** The dump the window shows, once it is a new one (not {@code before}). */
    private static ThreadDump awaitDump(DebugCoordinator debug, ThreadDump before) throws InterruptedException {
        await(
                "the Thread Dump window",
                60,
                () -> call(() -> {
                    DebugThreadDumpWindow w = debug.threadDumpWindowIfOpen();
                    return w != null && w.isShowing() && w.dump() != null && w.dump() != before;
                }));
        return call(() -> debug.threadDumpWindowIfOpen().dump());
    }

    private static void run(MainController c, String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run(command));
    }

    private static FxWindowFixture window(
            Path dir, Path file, java.util.function.Consumer<com.editora.config.Settings> settings) throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        settings.accept(seed.getSettings());
        assertTrue(seed.saveSettings());
        seed.shutdown();
        return FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
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
}
