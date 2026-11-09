package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugLanguages;
import com.editora.dap.DotnetLaunch;
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
 * Opt-in probe against the real .NET SDK and netcoredbg: a project's breakpoint hits, its frame has the locals with
 * their C# values, Step Over advances, the program's output reaches the console and the session ends with it; an
 * exception user code does not handle stops it; and in a window F5 builds a project, and a lone file (a .NET 10
 * file-based app), with {@code dotnet build} and stops. Run with
 * {@code ./mvnw test -Dtest=CsharpDebugProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs {@code dotnet} (10 or
 * later) and netcoredbg (Editora's install, or {@code -Dnetcoredbg.path=<netcoredbg or its folder>}).
 */
@Tag("probe")
@Tag("fx")
class CsharpDebugProbeFxTest {

    private record Stop(String reason, List<DapModels.StackFrameInfo> frames) {}

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

    /** 0-based line 8 is {@code total += item.Quantity;}. */
    static final String PROGRAM = """
            var greeting = "hello";
            var items = new List<Item> { new("pencil", 12), new("tape", 4) };
            int Square(int n) => n * n;

            int total = 0;
            foreach (var item in items)
            {
                var name = item.Name;
                total += item.Quantity;
            }
            Console.WriteLine($"{greeting} total {total} square {Square(7)}");

            record Item(string Name, int Quantity);
            """;

    static final String PROJECT = """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup>
                <OutputType>Exe</OutputType>
                <TargetFramework>net10.0</TargetFramework>
                <ImplicitUsings>enable</ImplicitUsings>
                <Nullable>enable</Nullable>
              </PropertyGroup>
            </Project>
            """;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    /** The netcoredbg to use: {@code -Dnetcoredbg.path}, else wherever Editora looks; the test skipped without it. */
    static String assumeDotnetAndNetcoredbg() {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        String dotnet = ProcessRunner.resolveExecutable(List.of("dotnet")).getFirst();
        assumeTrue(Path.of(dotnet).isAbsolute(), "needs dotnet on the PATH");
        String configured = System.getProperty("netcoredbg.path", "");
        assumeTrue(DapManager.locateExternal(DebugLanguages.CSHARP, configured) != null, "needs netcoredbg");
        return configured;
    }

    /** A project {@code shelf} in {@code dir} with {@code Program.cs}; its folder's real path. */
    static Path project(Path dir, String program) throws Exception {
        Files.writeString(dir.resolve(dir.getFileName() + ".csproj"), PROJECT);
        Files.writeString(dir.resolve("Program.cs"), program);
        return dir.toRealPath();
    }

    private static DapManager manager(Session session, String configured, List<DapModels.FileBreakpoints> bps)
            throws Exception {
        DapManager manager = new DapManager(null);
        manager.setListener(session);
        manager.setBreakpointsSupplier(() -> bps);
        CompletableFuture<Boolean> found = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> {
            manager.configure(true, "", false, "", false, "");
            manager.configureExternal(DebugLanguages.CSHARP, true, configured, found::complete);
        });
        assertTrue(found.get(30, TimeUnit.SECONDS), "netcoredbg found by the manager");
        return manager;
    }

    /** Builds {@code plan} with {@code dotnet build} and returns the assembly it made. */
    private static Path build(DotnetLaunch.Plan plan) {
        List<String> argv = new java.util.ArrayList<>(List.of("dotnet"));
        argv.addAll(plan.buildArgs());
        ProcessRunner.Result r = ProcessRunner.run(
                plan.root(), Duration.ofMinutes(5), ProcessRunner.resolveExecutable(argv), DotnetLaunch.BUILD_ENV);
        assertTrue(r.ok(), "dotnet build: " + r.out() + r.err());
        ProcessRunner.Result q = DotnetLaunch.queryTargetPath(List.of("dotnet"), plan, Duration.ofMinutes(2));
        assertTrue(q.ok(), "the target path: " + q.err());
        Path assembly = DotnetLaunch.targetPath(q.out());
        assertNotNull(assembly, "an assembly in " + q.out());
        assertTrue(Files.isRegularFile(assembly), "built: " + assembly);
        return assembly;
    }

    private static <T> T onFx(java.util.function.Consumer<java.util.function.Consumer<T>> ask) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> ask.accept(f::complete));
        return f.get(60, TimeUnit.SECONDS);
    }

    @Test
    void aProjectStopsWithItsValuesStepsAndEnds(@TempDir Path dir) throws Exception {
        String configured = assumeDotnetAndNetcoredbg();
        Path root = project(Files.createDirectory(dir.resolve("shelf")), PROGRAM);
        Path program = root.resolve("Program.cs");
        Session session = new Session();
        DapManager manager = manager(
                session,
                configured,
                List.of(new DapModels.FileBreakpoints(program, List.of(new DapModels.LineBreakpoint(8, null, null)))));
        try {
            Path csproj = DotnetLaunch.projectFile(program);
            assertEquals(root.resolve("shelf.csproj"), csproj);
            DotnetLaunch.Plan plan = DotnetLaunch.plan(program, csproj);
            Path assembly = build(plan);
            Map<String, Object> launch = DotnetLaunch.launch(assembly, List.of(), root);
            FxTestSupport.runOnFx(() -> manager.startExternal(program, DebugLanguages.CSHARP, launch));

            Stop stop = session.next("the loop's breakpoint");
            assertEquals(8, stop.frames().getFirst().line(), "stopped on the breakpoint's line");
            assertEquals(program, stop.frames().getFirst().file().toRealPath());
            int frame = stop.frames().getFirst().id();
            List<DapModels.ScopeInfo> scopes = onFx(cb -> manager.scopes(frame, cb));
            List<DapModels.VariableInfo> locals =
                    onFx(cb -> manager.variables(scopes.getFirst().variablesReference(), cb));
            assertTrue(
                    locals.stream()
                            .anyMatch(v ->
                                    v.name().equals("greeting") && v.value().equals("\"hello\"")),
                    "a string reads as its text: " + locals);
            assertTrue(
                    locals.stream()
                            .anyMatch(v -> v.name().equals("total") && v.value().equals("0")),
                    "total is 0 before the first pass: " + locals);
            assertTrue(
                    locals.stream().anyMatch(v -> v.name().equals("items") && v.variablesReference() > 0),
                    "the List expands: " + locals);

            FxTestSupport.runOnFx(manager::stepOver);
            Stop stepped = session.next("Step Over");
            assertTrue(
                    List.of(5, 9).contains(stepped.frames().getFirst().line()),
                    "Step Over goes on in the loop: " + stepped.frames().getFirst());

            DapModels.FileBreakpoints none = new DapModels.FileBreakpoints(program, List.of());
            manager.setBreakpointsSupplier(() -> List.of(none));
            FxTestSupport.runOnFx(() -> manager.updateBreakpoints(none));
            FxTestSupport.runOnFx(manager::resume);
            assertTrue(
                    session.ended.await(120, TimeUnit.SECONDS), "the session ends with the program: " + session.states);
            assertTrue(
                    session.output.toString().contains("hello total 16 square 49"),
                    "the program's output: " + session.output);
        } finally {
            FxTestSupport.runOnFx(manager::shutdown);
        }
    }

    @Test
    void anExceptionUserCodeDoesNotHandleStops(@TempDir Path dir) throws Exception {
        String configured = assumeDotnetAndNetcoredbg();
        String throwing = PROGRAM.replace("int total = 0;\n", "int total = 0;\n_ = items[9].Quantity;\n");
        Path root = project(Files.createDirectory(dir.resolve("shelf")), throwing);
        Path program = root.resolve("Program.cs");
        Session session = new Session();
        DapManager manager = manager(session, configured, List.of());
        try {
            Path assembly = build(DotnetLaunch.plan(program, DotnetLaunch.projectFile(program)));
            FxTestSupport.runOnFx(() -> manager.startExternal(
                    program, DebugLanguages.CSHARP, DotnetLaunch.launch(assembly, List.of(), root)));
            Stop stop = session.next("the exception's stop");
            assertEquals("exception", stop.reason());
            assertTrue(
                    stop.frames().stream()
                            .anyMatch(f -> f.file() != null && f.file().endsWith("Program.cs") && f.line() == 5),
                    "the line that indexed out of range is on the stack: " + stop.frames());
        } finally {
            FxTestSupport.runOnFx(manager::shutdown);
        }
    }

    @Test
    void inAWindowF5BuildsAProjectAndALoneFile(@TempDir Path dir) throws Exception {
        String configured = assumeDotnetAndNetcoredbg();
        Path root = project(Files.createDirectory(dir.resolve("shelf")), PROGRAM);
        Path program = root.resolve("Program.cs");
        Path lone = Files.createDirectory(dir.resolve("scratch")).toRealPath().resolve("tally.cs");
        Files.writeString(lone, PROGRAM);
        Path configDir = Files.createDirectory(dir.resolve("config"));
        com.editora.config.SharedConfig seed = new com.editora.config.SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setCsharpDebugPath(configured);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(program, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            fx.shared.getTrustStore().trust(root); // the user's own project: MSBuild runs its targets
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("C# debugging available", 60, () -> call(() -> debug.debugEffectiveFor("csharp")));
            com.editora.editor.EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            assertTrue(call(b::isBreakpointsEnabled), "a C# file has the breakpoint gutter");
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(8));
            FxTestSupport.runOnFx(() ->
                    ((com.editora.command.CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitStop(dap, debug, 8);
            assertEquals("shelf", call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile")));
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));

            FxTestSupport.runOnFx(() -> c.fileWorkflows.openPath(lone));
            await(
                    "tally.cs in front",
                    30,
                    () -> call(() -> c.activeBuffer() != null
                            && lone.equals(c.activeBuffer().getPath())));
            com.editora.editor.EditorBuffer loneBuffer = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> loneBuffer.toggleBreakpoint(8));
            FxTestSupport.runOnFx(() ->
                    ((com.editora.command.CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitStop(dap, debug, 8);
            assertEquals("tally.cs", call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile")));
            FxTestSupport.runOnFx(dap::stop);
            await("stopped again", 30, () -> !call(dap::isActive));
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
        try {
            await(
                    "a stop on line " + (line + 1),
                    300,
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
}
