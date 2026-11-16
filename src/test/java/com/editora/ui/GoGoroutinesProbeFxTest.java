package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
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
 * Opt-in probe of Go's goroutines in the Threads view against Delve, in a window: three workers started under pprof
 * labels and stopped in main; by default the runtime's goroutines are not listed and each worker is named by its label
 * ({@code [Go 20 job:resize-0] main.worker}); Hide Runtime Goroutines turned off lists them; a filter ({@code -with
 * label job=resize-1}) lists the one worker — each told to the running Delve through its console. Run with {@code
 * ./mvnw test -Dtest=GoGoroutinesProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class GoGoroutinesProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    static final String PROGRAM = """
            package main

            import (
            \t"context"
            \t"fmt"
            \t"runtime/pprof"
            \t"sync"
            )

            func worker(wg *sync.WaitGroup, ready chan<- bool, block <-chan bool) {
            \tdefer wg.Done()
            \tready <- true
            \t<-block
            }

            func main() {
            \tvar wg sync.WaitGroup
            \tready := make(chan bool)
            \tblock := make(chan bool)
            \tfor i := 0; i < 3; i++ {
            \t\twg.Add(1)
            \t\tpprof.Do(context.Background(), pprof.Labels("job", fmt.Sprint("resize-", i)), func(ctx context.Context) {
            \t\t\tgo worker(&wg, ready, block)
            \t\t})
            \t}
            \tfor i := 0; i < 3; i++ {
            \t\t<-ready
            \t}
            \tfmt.Println("all ready")
            \tclose(block)
            \twg.Wait()
            }
            """;

    @Test
    void theThreadsViewListsWhatTheOptionsSay(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DapManager.locateExternal(DebugLanguages.GO, "") != null, "needs Delve (dlv)");
        Path root = Files.createDirectory(dir.resolve("jobs")).toRealPath();
        Files.writeString(root.resolve("go.mod"), "module example.com/jobs\n\ngo 1.22\n");
        Path file = Files.writeString(root.resolve("main.go"), PROGRAM);
        int line = PROGRAM.lines().toList().indexOf("\tfmt.Println(\"all ready\")");
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            await("go", 120, () -> call(() -> debug.debugEffectiveFor("go")));
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(line));
            FxTestSupport.runOnFx(() -> registry.run("debug.start"));
            await(
                    "stop",
                    180,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()));

            // Settings' defaults: the runtime's goroutines left out, each worker named by its pprof label.
            List<String> shown = names(dap);
            assertTrue(shown.stream().noneMatch(n -> n.contains("runtime.")), "no runtime goroutine: " + shown);
            assertTrue(
                    shown.stream().anyMatch(n -> n.contains("job:resize-0") && n.contains("main.worker")), "" + shown);
            assertEquals(4, shown.size(), "main and the three workers: " + shown);

            // Hide Runtime Goroutines off, from the Threads view's command: Delve lists them again.
            FxTestSupport.runOnFx(() -> registry.run("debug.toggleRuntimeGoroutines"));
            await("the runtime's goroutines listed", 30, () -> {
                try {
                    return names(dap).stream().anyMatch(n -> n.contains("runtime."));
                } catch (Exception e) {
                    return false;
                }
            });

            // A filter: the one worker whose label it names.
            FxTestSupport.runOnFx(() -> debug.goroutines().setFilter("-with label job=resize-1"));
            await("one worker", 30, () -> {
                try {
                    List<String> n = names(dap);
                    return n.size() == 1 && n.getFirst().contains("job:resize-1");
                } catch (Exception e) {
                    return false;
                }
            });
            FxTestSupport.runOnFx(() -> debug.goroutines().setFilter(""));
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 60, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    private static List<String> names(DapManager dap) throws Exception {
        AtomicReference<List<DapModels.ThreadInfo>> got = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> dap.threads(got::set));
        await("threads", 30, () -> got.get() != null);
        List<String> out = new ArrayList<>();
        got.get().forEach(t -> out.add(t.name()));
        return out;
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
