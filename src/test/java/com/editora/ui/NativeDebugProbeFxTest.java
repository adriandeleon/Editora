package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
import com.editora.dap.LocalProcesses;
import com.editora.dap.NativeBuild;
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
 * Opt-in probe against the real CodeLLDB and C/C++ tool chains: a lone C file compiled with {@code cc} stops with its
 * values, steps and ends; a CMake C++ project's executable (found through CMake's File API) stops in another source
 * file with {@code std::string} and {@code std::vector} shown as values; a Make project's program stops; a running
 * native program is found by Attach to Process, attached by process id and left running; and in a window F5 builds a
 * CMake project and stops. Run with {@code ./mvnw test -Dtest=NativeDebugProbeFxTest -Dgroups=probe
 * -Dlsp.probe=true}; needs {@code cc}, {@code c++}, {@code cmake}, {@code make} and CodeLLDB (as
 * {@code RustDebugProbeFxTest} finds it, or {@code -Dcodelldb.path}).
 */
@Tag("probe")
@Tag("fx")
class NativeDebugProbeFxTest {

    private record Stop(List<DapModels.StackFrameInfo> frames) {}

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
            stops.add(new Stop(frames));
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

    static final String C_FILE = """
            #include <stdio.h>

            struct item {
                const char *name;
                int quantity;
            };

            int main(void) {
                struct item items[] = {{"pencil", 12}, {"tape", 4}};
                const char *greeting = "hello";
                int total = 0;
                for (int i = 0; i < 2; i++) {
                    total += items[i].quantity;
                }
                printf("%s total %d\\n", greeting, total);
                return 0;
            }
            """;

    static final String CMAKE_LISTS = """
            cmake_minimum_required(VERSION 3.16)
            project(shelf CXX)
            set(CMAKE_CXX_STANDARD 17)
            add_executable(shelf src/main.cpp src/greet.cpp)
            """;

    static final String MAIN_CPP = """
            #include <iostream>
            #include <string>

            std::string greet(const std::string &name);

            int main() {
                std::cout << greet("world") << std::endl;
                return 0;
            }
            """;

    static final String GREET_CPP = """
            #include <string>
            #include <vector>

            std::string greet(const std::string &name) {
                std::vector<int> counts{1, 2, 3};
                std::string text = "hello " + name;
                return text + " " + std::to_string(counts.size());
            }
            """;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    /** The CodeLLDB to use, as {@code RustDebugProbeFxTest} finds it; the test skipped without the tools. */
    static String assumeToolsAndCodelldb(String... tools) {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        for (String tool : tools) {
            assumeTrue(
                    Path.of(ProcessRunner.resolveExecutable(List.of(tool)).getFirst())
                            .isAbsolute(),
                    "needs " + tool + " on the PATH");
        }
        String configured = System.getProperty("codelldb.path", "");
        assumeTrue(DapManager.locateExternal(DebugLanguages.CPP, configured) != null, "needs CodeLLDB");
        return configured;
    }

    private static DapManager manager(Session session, String configured, List<DapModels.FileBreakpoints> bps)
            throws Exception {
        DapManager manager = new DapManager(null);
        manager.setListener(session);
        manager.setBreakpointsSupplier(() -> bps);
        CompletableFuture<Boolean> found = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> {
            manager.configure(true, "", false, "", false, "");
            manager.configureExternal(DebugLanguages.CPP, true, configured, found::complete);
        });
        assertTrue(found.get(30, TimeUnit.SECONDS), "CodeLLDB found by the manager");
        return manager;
    }

    /** Runs {@code plan}'s build steps, failing the test when one fails. */
    private static void build(NativeBuild.Plan plan) throws Exception {
        if (plan.buildDir() != null) {
            NativeBuild.requestCodemodel(plan.buildDir());
        }
        if (plan.output() != null) {
            Files.createDirectories(plan.output().getParent());
        }
        for (List<String> step : plan.steps()) {
            ProcessRunner.Result r =
                    ProcessRunner.run(plan.root(), Duration.ofMinutes(5), ProcessRunner.resolveExecutable(step));
            assertTrue(r.ok(), step + ": " + r.out() + r.err());
        }
    }

    private static <T> T onFx(java.util.function.Consumer<java.util.function.Consumer<T>> ask) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> ask.accept(f::complete));
        return f.get(60, TimeUnit.SECONDS);
    }

    private static List<DapModels.VariableInfo> locals(DapManager manager, Stop stop) throws Exception {
        int frame = stop.frames().getFirst().id();
        List<DapModels.ScopeInfo> scopes = onFx(cb -> manager.scopes(frame, cb));
        return onFx(cb -> manager.variables(scopes.getFirst().variablesReference(), cb));
    }

    @Test
    void aLoneCFileIsCompiledStopsWithItsValuesStepsAndEnds(@TempDir Path dir) throws Exception {
        String configured = assumeToolsAndCodelldb("cc");
        Path file = Files.writeString(dir.resolve("shelf.c"), C_FILE).toRealPath();
        NativeBuild.Plan plan = NativeBuild.plan(file, false, null, null, dir.resolve("out"));
        assertEquals(NativeBuild.Tool.COMPILER, plan.tool());
        build(plan);
        Session session = new Session();
        DapManager manager = manager(
                session,
                configured,
                List.of(new DapModels.FileBreakpoints(file, List.of(new DapModels.LineBreakpoint(12, null, null)))));
        try {
            FxTestSupport.runOnFx(() -> manager.startExternal(
                    file, DebugLanguages.CPP, NativeBuild.launch(plan.output(), List.of(), plan.root(), false)));
            Stop stop = session.next("the loop's breakpoint");
            assertEquals(12, stop.frames().getFirst().line());
            assertEquals(file, stop.frames().getFirst().file().toRealPath());
            List<DapModels.VariableInfo> locals = locals(manager, stop);
            assertTrue(
                    locals.stream()
                            .anyMatch(v ->
                                    v.name().equals("greeting") && v.value().contains("hello")),
                    "a C string reads as its text: " + locals);
            assertTrue(
                    locals.stream().anyMatch(v -> v.name().equals("items") && v.variablesReference() > 0),
                    "the array expands: " + locals);

            FxTestSupport.runOnFx(manager::stepOver);
            Stop stepped = session.next("Step Over");
            assertTrue(List.of(11, 12).contains(stepped.frames().getFirst().line()), "on in the loop: " + stepped);

            DapModels.FileBreakpoints none = new DapModels.FileBreakpoints(file, List.of());
            manager.setBreakpointsSupplier(() -> List.of(none));
            FxTestSupport.runOnFx(() -> manager.updateBreakpoints(none));
            FxTestSupport.runOnFx(manager::resume);
            assertTrue(session.ended.await(120, TimeUnit.SECONDS), "ends with the program: " + session.states);
            assertTrue(session.output.toString().contains("hello total 16"), "its output: " + session.output);
        } finally {
            FxTestSupport.runOnFx(manager::shutdown);
        }
    }

    /** A CMake C++ project in {@code dir}; its folder's real path. */
    static Path cmakeProject(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("CMakeLists.txt"), CMAKE_LISTS);
        Files.writeString(dir.resolve("src/main.cpp"), MAIN_CPP);
        Files.writeString(dir.resolve("src/greet.cpp"), GREET_CPP);
        return dir.toRealPath();
    }

    @Test
    void aCmakeProjectsExecutableStopsInAnotherSourceWithStlValues(@TempDir Path dir) throws Exception {
        String configured = assumeToolsAndCodelldb("cmake", "c++");
        Path root = cmakeProject(dir);
        Path greet = root.resolve("src/greet.cpp");
        assertEquals(root, NativeBuild.cmakeRoot(greet));
        NativeBuild.Plan plan = NativeBuild.plan(greet, true, root, null, dir.resolve("out"));
        build(plan);
        Path exe = NativeBuild.cmakeExecutable(plan.buildDir(), greet);
        assertNotNull(exe, "CMake's File API names the executable holding greet.cpp");
        Session session = new Session();
        DapManager manager = manager(
                session,
                configured,
                List.of(new DapModels.FileBreakpoints(greet, List.of(new DapModels.LineBreakpoint(6, null, null)))));
        try {
            FxTestSupport.runOnFx(() ->
                    manager.startExternal(greet, DebugLanguages.CPP, NativeBuild.launch(exe, List.of(), root, false)));
            Stop stop = session.next("greet's breakpoint");
            assertEquals(6, stop.frames().getFirst().line());
            assertEquals(greet, stop.frames().getFirst().file().toRealPath());
            List<DapModels.VariableInfo> locals = locals(manager, stop);
            assertTrue(
                    locals.stream()
                            .anyMatch(
                                    v -> v.name().equals("counts") && v.value().contains("size=3")),
                    "a std::vector shows its size: " + locals);
            FxTestSupport.runOnFx(manager::resume);
            assertTrue(session.ended.await(120, TimeUnit.SECONDS), "ends: " + session.states);
            assertTrue(session.output.toString().contains("hello world 3"), "its output: " + session.output);
        } finally {
            FxTestSupport.runOnFx(manager::shutdown);
        }
    }

    @Test
    void aMakeProjectsProgramStops(@TempDir Path dir) throws Exception {
        String configured = assumeToolsAndCodelldb("make", "cc");
        Path root = dir.toRealPath();
        Path file = Files.writeString(root.resolve("shelf.c"), C_FILE);
        Files.writeString(root.resolve("Makefile"), "shelf: shelf.c\n\tcc -g -O0 -o shelf shelf.c\n");
        NativeBuild.Plan plan = NativeBuild.plan(file, false, null, NativeBuild.makeRoot(file), dir.resolve("out"));
        assertEquals(NativeBuild.Tool.MAKE, plan.tool());
        build(plan);
        Path exe = NativeBuild.makeExecutable(root, file);
        assertEquals(root.resolve("shelf"), exe, "the program named after the file");
        Session session = new Session();
        DapManager manager = manager(
                session,
                configured,
                List.of(new DapModels.FileBreakpoints(file, List.of(new DapModels.LineBreakpoint(14, null, null)))));
        try {
            FxTestSupport.runOnFx(() ->
                    manager.startExternal(file, DebugLanguages.CPP, NativeBuild.launch(exe, List.of(), root, false)));
            Stop stop = session.next("the printf's breakpoint");
            assertEquals(14, stop.frames().getFirst().line());
            assertTrue(
                    locals(manager, stop).stream()
                            .anyMatch(v -> v.name().equals("total") && v.value().equals("16")),
                    "total after the loop");
        } finally {
            FxTestSupport.runOnFx(manager::shutdown);
        }
    }

    @Test
    void aRunningNativeProgramIsFoundAttachedByItsProcessIdAndLeftRunning(@TempDir Path dir) throws Exception {
        String configured = assumeToolsAndCodelldb("cc");
        Path ptrace = Path.of("/proc/sys/kernel/yama/ptrace_scope");
        assumeTrue(
                Files.exists(ptrace) && Files.readString(ptrace).strip().equals("0"),
                "a native program is listed on Linux, and attached with ptrace_scope 0");
        Path file = Files.writeString(
                        dir.resolve("loop.c"),
                        "#include <unistd.h>\nint tick(int n) {\n    return n + 1;\n}\n"
                                + "int main(void) {\n    int n = 0;\n    for (;;) {\n        n = tick(n);\n"
                                + "        usleep(50000);\n    }\n}\n")
                .toRealPath();
        Path exe = dir.resolve("loop");
        ProcessRunner.Result cc = ProcessRunner.run(
                dir,
                Duration.ofMinutes(2),
                ProcessRunner.resolveExecutable(List.of("cc", "-g", "-O0", "-o", exe.toString(), file.toString())));
        assertTrue(cc.ok(), cc.err());
        Process program = new ProcessBuilder(exe.toString()).start();
        Session session = new Session();
        DapManager manager = manager(
                session,
                configured,
                List.of(new DapModels.FileBreakpoints(file, List.of(new DapModels.LineBreakpoint(2, null, null)))));
        try {
            LocalProcesses.Target target = LocalProcesses.scan().stream()
                    .filter(t -> t.pid() == program.pid())
                    .findFirst()
                    .orElse(null);
            assertNotNull(target, "Attach to Process lists the native program");
            assertEquals(LocalProcesses.Kind.NATIVE, target.kind());
            FxTestSupport.runOnFx(
                    () -> manager.startExternal(file, DebugLanguages.CPP, NativeBuild.attach(program.pid(), "cpp")));
            Stop stop = session.next("the attached program's breakpoint");
            assertEquals(2, stop.frames().getFirst().line(), "stopped in tick");
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
    void inAWindowF5BuildsTheCmakeProjectAndStops(@TempDir Path dir) throws Exception {
        String configured = assumeToolsAndCodelldb("cmake", "c++");
        Path root = cmakeProject(Files.createDirectory(dir.resolve("shelf")));
        Path greet = root.resolve("src/greet.cpp");
        Path configDir = Files.createDirectory(dir.resolve("config"));
        com.editora.config.SharedConfig seed = new com.editora.config.SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setRustDebugPath(configured);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(greet, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            fx.shared.getTrustStore().trust(root); // the user's own project: CMake runs its build files
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("C/C++ debugging available", () -> call(() -> debug.debugEffectiveFor("cpp")));
            com.editora.editor.EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            assertTrue(call(b::isBreakpointsEnabled), "a C++ file has the breakpoint gutter");
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(6));
            FxTestSupport.runOnFx(() ->
                    ((com.editora.command.CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            await(
                    "a stop in greet",
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().get(0).line() == 6));
            assertEquals("greet.cpp", call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile")));
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", () -> !call(dap::isActive));
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

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + 300_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }
}
