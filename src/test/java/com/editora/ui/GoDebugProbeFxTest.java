package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugLanguages;
import com.editora.dap.GoLaunch;
import com.editora.dap.LocalProcesses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe against the real Delve ({@code dlv dap}) and Go toolchain: a Go package's breakpoint hits, its frame has
 * the variables, Step Over advances, the goroutines are the threads, the program's output reaches the console and the
 * session ends with it; a test file's test at the caret stops in that test; Attach to Process finds a running Go
 * program, attaches by its process id, stops it at a breakpoint, and Stop leaves it running. Run with
 * {@code ./mvnw test -Dtest=GoDebugProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs {@code go} and {@code dlv}.
 */
@Tag("probe")
@Tag("fx")
class GoDebugProbeFxTest {

    private record Stop(String reason, List<DapModels.StackFrameInfo> frames) {}

    /** A session's events, as the Debug window hears them. */
    private static final class Session implements DapManager.Listener {
        final BlockingQueue<Stop> stops = new LinkedBlockingQueue<>();
        final List<DapManager.State> states = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final StringBuffer output = new StringBuffer();
        final CountDownLatch ended = new CountDownLatch(1);
        private boolean started;

        @Override
        public void onState(DapManager.State state) {
            states.add(state);
            started |= state == DapManager.State.RUNNING;
            if (started && state == DapManager.State.INACTIVE) {
                ended.countDown();
            }
        }

        @Override
        public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {
            stops.add(new Stop(reason, frames));
        }

        @Override
        public void onOutput(String text, String category) {
            output.append(text);
        }

        @Override
        public void onError(String message) {
            errors.add(message);
        }

        Stop next(String what) throws InterruptedException {
            Stop s = stops.poll(120, TimeUnit.SECONDS);
            assertNotNull(s, what + " never happened; errors: " + errors + " states: " + states + " output: " + output);
            return s;
        }
    }

    private static final String MAIN = """
            package main

            import (
            \t"fmt"
            \t"sync"
            )

            func main() {
            \tvar wg sync.WaitGroup
            \tresults := make([]int, 8)
            \tfor i := range 8 {
            \t\twg.Add(1)
            \t\tgo func() {
            \t\t\tdefer wg.Done()
            \t\t\tresults[i] = i * i
            \t\t}()
            \t}
            \twg.Wait()
            \ttotal := 0
            \tfor _, r := range results {
            \t\ttotal += r
            \t}
            \tfmt.Println("total", total)
            }
            """;

    private static final String SQUARE = """
            package main

            func square(n int) int {
            \treturn n * n
            }
            """;

    private static final String SQUARE_TEST = """
            package main

            import "testing"

            func TestOther(t *testing.T) {
            \tt.Log("other")
            }

            func TestSquare(t *testing.T) {
            \tgot := square(7)
            \tif got != 49 {
            \t\tt.Fatalf("square(7) = %d", got)
            \t}
            }
            """;

    /** A program that runs until it is stopped, calling {@code tick} every 50 ms. */
    private static final String LOOP = """
            package main

            import "time"

            func tick(n int) int {
            \treturn n + 1
            }

            func main() {
            \tn := 0
            \tfor {
            \t\tn = tick(n)
            \t\ttime.Sleep(50 * time.Millisecond)
            \t}
            }
            """;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    /** A Go module in {@code dir} with {@code files}; its folder's real path. */
    private static Path module(Path dir, Map<String, String> files) throws Exception {
        Files.writeString(dir.resolve("go.mod"), "module example.com/probe\n\ngo 1.22\n");
        for (var e : files.entrySet()) {
            Files.writeString(dir.resolve(e.getKey()), e.getValue());
        }
        return dir.toRealPath();
    }

    /** Skips the test unless it was opted into and Go and Delve are here. */
    private static void assumeGoAndDelve() {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DapManager.locateExternal(DebugLanguages.GO, "") != null, "needs Delve (dlv)");
        String go = com.editora.process.ProcessRunner.resolveExecutable(List.of("go"))
                .getFirst();
        assumeTrue(Path.of(go).isAbsolute(), "needs go on the PATH");
    }

    /** A manager with Delve found, or the test skipped when Delve or Go is not here. */
    private static DapManager manager(Session session, List<DapModels.FileBreakpoints> bps) throws Exception {
        assumeGoAndDelve();
        DapManager manager = new DapManager(null);
        manager.setListener(session);
        manager.setBreakpointsSupplier(() -> bps);
        CompletableFuture<Boolean> found = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> {
            manager.configure(true, "", false, "", false, "");
            manager.configureExternal(DebugLanguages.GO, true, "", found::complete);
        });
        assertTrue(found.get(30, TimeUnit.SECONDS), "Delve was found by the probe's lookup but not the manager's");
        return manager;
    }

    private static <T> T onFx(java.util.function.Consumer<java.util.function.Consumer<T>> ask) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> ask.accept(f::complete));
        return f.get(60, TimeUnit.SECONDS);
    }

    /** Prints where it runs, a variable and its arguments: what a run configuration sets. */
    private static final String WHERE = """
            package main

            import (
            \t"fmt"
            \t"os"
            )

            func main() {
            \tdir, _ := os.Getwd()
            \tfmt.Println("in", dir, "MODE", os.Getenv("MODE"), "args", os.Args[1:])
            }
            """;

    /**
     * A run configuration's launch: the program runs in the configuration's working directory, with its variables
     * and arguments, while Delve still builds the package from the package's folder ({@code dlvCwd}).
     */
    @Test
    void aConfigurationsFolderVariablesAndArgumentsReachTheProgram(@TempDir Path dir) throws Exception {
        Path root = module(Files.createDirectories(dir.resolve("mod")), Map.of("main.go", WHERE));
        Path data = Files.createDirectories(dir.resolve("data")).toRealPath();
        Path main = root.resolve("main.go");
        Session session = new Session();
        DapManager manager = manager(session, List.of());
        try {
            Map<String, Object> launch =
                    GoLaunch.launch(main, WHERE, -1, true, List.of("one", "two"), Map.of("MODE", "dev"), false, data);
            assertEquals(root.toString(), launch.get("dlvCwd"));
            FxTestSupport.runOnFx(() -> manager.startExternal(main, DebugLanguages.GO, launch));
            assertTrue(
                    session.ended.await(120, TimeUnit.SECONDS),
                    "the session ends with the program: " + session.states + " errors: " + session.errors);
            String out = session.output.toString();
            assertTrue(out.contains("in " + data + " MODE dev args [one two]"), out + " errors: " + session.errors);
        } finally {
            FxTestSupport.runOnFx(manager::shutdown);
        }
    }

    @Test
    void aPackageStopsAtItsBreakpointWithItsVariablesStepsListsGoroutinesAndEnds(@TempDir Path dir) throws Exception {
        Path root = module(dir, Map.of("main.go", MAIN, "square.go", SQUARE));
        Path main = root.resolve("main.go");
        Session session = new Session();
        // 0-based line 20: `total += r` (after every goroutine ran), and line 14: `results[i] = i * i` in a goroutine.
        DapManager manager = manager(
                session,
                List.of(new DapModels.FileBreakpoints(main, List.of(new DapModels.LineBreakpoint(14, null, null)))));
        try {
            Map<String, Object> launch = GoLaunch.launch(main, MAIN, 0, true, List.of(), Map.of(), false);
            assertEquals("debug", launch.get("mode"));
            FxTestSupport.runOnFx(() -> manager.startExternal(main, DebugLanguages.GO, launch));

            Stop inGoroutine = session.next("the goroutine's breakpoint");
            assertEquals(14, inGoroutine.frames().getFirst().line(), "stopped on the breakpoint's line");
            assertEquals(main, inGoroutine.frames().getFirst().file().toRealPath());
            List<DapModels.ThreadInfo> threads = onFx(manager::threads);
            assertTrue(threads.size() > 1, "every goroutine is a thread: " + threads);
            // Delve disassembles, reads memory and steps by instruction: the Disassembly and Memory views.
            String ip = inGoroutine.frames().getFirst().instructionPointer();
            assertTrue(ip != null, "a Go frame has an instruction pointer");
            assertTrue(FxTestSupport.callOnFx(manager::supportsDisassemble));
            assertTrue(FxTestSupport.callOnFx(manager::supportsReadMemory));
            assertTrue(FxTestSupport.callOnFx(manager::supportsInstructionStepping));
            List<DapModels.Instruction> around = onFx(cb -> manager.disassemble(ip, -5, 20, cb));
            int at = DebugDisassemblyView.indexOf(around, ip);
            assertEquals(5, at, "the instruction pointer after the five before it: " + around);
            assertEquals(14, around.get(at).line(), "on the breakpoint's line");

            // Now the breakpoint at the sum, alone: the goroutines' one is taken away.
            DapModels.FileBreakpoints atTheSum =
                    new DapModels.FileBreakpoints(main, List.of(new DapModels.LineBreakpoint(20, null, null)));
            manager.setBreakpointsSupplier(() -> List.of(atTheSum));
            FxTestSupport.runOnFx(() -> manager.updateBreakpoints(atTheSum));
            FxTestSupport.runOnFx(manager::resume);
            Stop atSum = session.next("the sum's breakpoint");
            while (atSum.frames().getFirst().line() != 20) { // a goroutine already at line 14 stops once more
                FxTestSupport.runOnFx(manager::resume);
                atSum = session.next("the sum's breakpoint");
            }
            int frame = atSum.frames().getFirst().id();
            List<DapModels.ScopeInfo> scopes = onFx(cb -> manager.scopes(frame, cb));
            assertFalse(scopes.isEmpty(), "the frame has scopes");
            List<DapModels.VariableInfo> locals =
                    onFx(cb -> manager.variables(scopes.getFirst().variablesReference(), cb));
            assertTrue(
                    locals.stream()
                            .anyMatch(v -> v.name().equals("total") && v.value().equals("0")),
                    "total is a local, 0 before the first sum: " + locals);
            assertTrue(locals.stream().anyMatch(v -> v.name().equals("results")), "results is a local: " + locals);

            FxTestSupport.runOnFx(manager::stepOver);
            Stop stepped = session.next("Step Over");
            assertEquals(19, stepped.frames().getFirst().line(), "back to the range loop's head");

            DapModels.FileBreakpoints none = new DapModels.FileBreakpoints(main, List.of());
            manager.setBreakpointsSupplier(() -> List.of(none));
            FxTestSupport.runOnFx(() -> manager.updateBreakpoints(none));
            FxTestSupport.runOnFx(manager::resume);
            assertTrue(
                    session.ended.await(120, TimeUnit.SECONDS), "the session ends with the program: " + session.states);
            assertTrue(session.output.toString().contains("total 140"), "the program's output: " + session.output);
            assertFalse(
                    Files.list(root).anyMatch(p -> p.getFileName().toString().startsWith("__debug_bin")),
                    "Delve's build is not left in the package");
        } finally {
            FxTestSupport.runOnFx(manager::shutdown);
        }
    }

    @Test
    void aTestFileDebugsTheTestAtTheCaret(@TempDir Path dir) throws Exception {
        Path root = module(dir, Map.of("square.go", SQUARE, "square_test.go", SQUARE_TEST));
        Path test = root.resolve("square_test.go");
        Session session = new Session();
        // A breakpoint in each test: only TestSquare's is reached, since only it runs.
        DapManager manager = manager(
                session,
                List.of(new DapModels.FileBreakpoints(
                        test,
                        List.of(
                                new DapModels.LineBreakpoint(5, null, null),
                                new DapModels.LineBreakpoint(9, null, null)))));
        try {
            int caret = 10; // inside TestSquare's body
            assertEquals("TestSquare", GoLaunch.sessionName(test, SQUARE_TEST, caret));
            Map<String, Object> launch = GoLaunch.launch(test, SQUARE_TEST, caret, true, List.of(), Map.of(), false);
            FxTestSupport.runOnFx(() -> manager.startExternal(test, DebugLanguages.GO, launch));

            Stop stop = session.next("TestSquare's breakpoint");
            assertEquals(9, stop.frames().getFirst().line(), "stopped in TestSquare, not TestOther");
            assertTrue(
                    stop.frames().getFirst().name().contains("TestSquare"),
                    stop.frames().getFirst().name());
            FxTestSupport.runOnFx(manager::resume);
            assertTrue(session.ended.await(120, TimeUnit.SECONDS), "the test run ends: " + session.states);
        } finally {
            FxTestSupport.runOnFx(manager::shutdown);
        }
    }

    @Test
    void aRunningGoProgramIsFoundAttachedByItsProcessIdAndLeftRunning(@TempDir Path dir) throws Exception {
        assumeGoAndDelve(); // before go build
        Path ptrace = Path.of("/proc/sys/kernel/yama/ptrace_scope");
        assumeTrue(
                !Files.exists(ptrace) || Files.readString(ptrace).strip().equals("0"),
                "attaching to a program Delve did not start needs ptrace_scope 0");
        Path root = module(dir, Map.of("main.go", LOOP));
        Path main = root.resolve("main.go");
        String exe = System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win")
                ? "looper.exe"
                : "looper";
        Process build = new ProcessBuilder(com.editora.process.ProcessRunner.resolveExecutable(
                        List.of("go", "build", "-gcflags=all=-N -l", "-o", exe, ".")))
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        assertTrue(build.waitFor(180, TimeUnit.SECONDS), "go build ended");
        assertEquals(0, build.exitValue(), new String(build.getInputStream().readAllBytes()));
        Process program = new ProcessBuilder(root.resolve(exe).toString()).start();
        Session session = new Session();
        DapManager manager = manager(
                session,
                List.of(new DapModels.FileBreakpoints(main, List.of(new DapModels.LineBreakpoint(5, null, null)))));
        try {
            LocalProcesses.Target target = LocalProcesses.scan().stream()
                    .filter(t -> t.pid() == program.pid())
                    .findFirst()
                    .orElse(null);
            assertNotNull(target, "Attach to Process lists the Go program");
            assertEquals(LocalProcesses.Kind.GO, target.kind());
            assertTrue(target.attachable());

            FxTestSupport.runOnFx(() -> manager.startExternal(main, DebugLanguages.GO, GoLaunch.attach(program.pid())));
            Stop stop = session.next("the attached program's breakpoint");
            assertEquals(5, stop.frames().getFirst().line(), "stopped in tick");

            FxTestSupport.runOnFx(manager::stop);
            assertTrue(session.ended.await(60, TimeUnit.SECONDS), "Stop ends the session: " + session.states);
            Thread.sleep(500);
            assertTrue(program.isAlive(), "Stop detaches: the program runs on");
        } finally {
            FxTestSupport.runOnFx(manager::shutdown);
            program.destroyForcibly();
        }
    }

    @Test
    void inAWindowF5DebugsTheGoFileAndDebugTestAtCaretItsTest(@TempDir Path dir) throws Exception {
        assumeGoAndDelve();
        Path root = module(
                Files.createDirectory(dir.resolve("probe")),
                Map.of("main.go", MAIN, "square.go", SQUARE, "square_test.go", SQUARE_TEST));
        Path main = root.resolve("main.go");
        Path test = root.resolve("square_test.go");
        Path configDir = Files.createDirectory(dir.resolve("config"));
        com.editora.config.SharedConfig seed = new com.editora.config.SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir,
                false,
                false,
                false,
                List.of(new MainController.OpenTarget(test, 0, 0), new MainController.OpenTarget(main, 0, 0)),
                true,
                c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("Go debugging available", 60, () -> call(() -> debug.debugEffectiveFor("go")));
            com.editora.editor.EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            assertEquals(main, call(() -> b.getPath().toRealPath()));
            assertTrue(call(b::isBreakpointsEnabled), "a Go file has the breakpoint gutter");
            assertTrue(call(() -> b.getBreakpointManager().toggle(20)), "a breakpoint at the sum");

            FxTestSupport.runOnFx(() ->
                    ((com.editora.command.CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitStop(dap, debug, 20, "main.main");
            assertEquals("main.go", call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile")));
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));

            // Debug Test at Caret in the test file: TestSquare alone runs, under Delve's test mode.
            FxTestSupport.runOnFx(() -> c.fileWorkflows.openPath(test));
            await(
                    "the test file in front",
                    30,
                    () -> call(() -> {
                        Path p = c.activeBuffer() == null
                                ? null
                                : c.activeBuffer().getPath();
                        return p != null && p.toRealPath().equals(test);
                    }));
            com.editora.editor.EditorBuffer t = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> {
                t.getBreakpointManager().toggle(5); // TestOther: never reached
                t.getBreakpointManager().toggle(9);
                t.getFocusedArea().moveTo(10, 1);
            });
            FxTestSupport.runOnFx(() -> ((com.editora.command.CommandRegistry) FxTestSupport.field(c, "registry"))
                    .run("test.debugAtCaret"));
            awaitStop(dap, debug, 9, "TestSquare");
            assertEquals("TestSquare", call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile")));
            FxTestSupport.runOnFx(dap::resume);
            await("the test run ends", 120, () -> !call(dap::isActive));
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

    private static void await(String what, int seconds, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    /** A stop shown in the window on 0-based {@code line}, in a function whose name has {@code function}. */
    private static void awaitStop(DapManager dap, DebugCoordinator debug, int line, String function)
            throws InterruptedException {
        try {
            await(
                    "a stop on line " + (line + 1) + " in " + function,
                    180,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().get(0).line() == line
                            && debug.panel().callStack().get(0).name().contains(function)));
        } catch (AssertionError e) {
            String top = call(() -> debug.panel().callStack().isEmpty()
                    ? "no frames"
                    : debug.panel().callStack().get(0).name() + " at "
                            + (debug.panel().callStack().get(0).line() + 1));
            throw new AssertionError(e.getMessage() + " (" + call(dap::state) + ", " + top + ")", e);
        }
    }
}
