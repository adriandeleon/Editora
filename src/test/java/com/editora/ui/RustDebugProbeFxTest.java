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
import com.editora.dap.RustLaunch;
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
 * Opt-in probe against the real CodeLLDB and Cargo: a Rust program's breakpoint hits, its frame has the variables
 * with Rust's formatters ({@code String}, {@code Vec}), Step Over advances, the program's output reaches the console
 * and the session ends with it; a test binary filtered to the test at the caret stops in that test only; and in a
 * window F5 builds with Cargo and stops, and Debug Test at Caret runs the test. Run with
 * {@code ./mvnw test -Dtest=RustDebugProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs {@code cargo} and CodeLLDB
 * (Editora's install, VS Code's, or {@code -Dcodelldb.path=<codelldb or its extension folder>}).
 */
@Tag("probe")
@Tag("fx")
class RustDebugProbeFxTest {

    private record Stop(List<DapModels.StackFrameInfo> frames) {}

    private static final class Session implements DapManager.Listener {
        final BlockingQueue<Stop> stops = new LinkedBlockingQueue<>();
        final List<String> reasons = new CopyOnWriteArrayList<>();
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
            reasons.add(reason);
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

    static final String MAIN = """
            #[derive(Debug)]
            struct Item {
                name: String,
                quantity: u32,
            }

            fn square(n: i64) -> i64 {
                n * n
            }

            fn main() {
                let greeting = String::from("hello");
                let items = vec![Item { name: "pencil".into(), quantity: 12 }, Item { name: "tape".into(), quantity: 4 }];
                let mut total: u32 = 0;
                for item in &items {
                    total += item.quantity;
                }
                println!("{} total {} square {}", greeting, total, square(7));
            }

            #[cfg(test)]
            mod tests {
                use super::*;

                #[test]
                fn other() {
                    assert_eq!(square(2), 4);
                }

                #[test]
                fn squares() {
                    let got = square(7);
                    assert_eq!(got, 49);
                }
            }
            """;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    /** The CodeLLDB to use: {@code -Dcodelldb.path}, else wherever Editora looks; the test skipped without it. */
    static String assumeCargoAndCodelldb() {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        String cargo = ProcessRunner.resolveExecutable(List.of("cargo")).getFirst();
        assumeTrue(Path.of(cargo).isAbsolute(), "needs cargo on the PATH");
        String configured = System.getProperty("codelldb.path", "");
        String codelldb = DapManager.locateExternal(DebugLanguages.RUST, configured);
        assumeTrue(codelldb != null, "needs CodeLLDB");
        return configured;
    }

    /** A Cargo package {@code shelf} in {@code dir} with {@code src/main.rs}; its folder's real path. */
    static Path cargoPackage(Path dir) throws Exception {
        Files.writeString(
                dir.resolve("Cargo.toml"), "[package]\nname = \"shelf\"\nversion = \"0.1.0\"\nedition = \"2021\"\n");
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/main.rs"), MAIN);
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
            manager.configureExternal(DebugLanguages.RUST, true, configured, found::complete);
        });
        assertTrue(found.get(30, TimeUnit.SECONDS), "CodeLLDB found by the manager");
        return manager;
    }

    /** Builds {@code plan} with Cargo and returns the executable for {@code file}. */
    private static RustLaunch.Artifact build(Path root, Path file, RustLaunch.Plan plan) {
        List<String> argv = new java.util.ArrayList<>(List.of("cargo"));
        argv.addAll(plan.jsonArgs());
        ProcessRunner.Result r = ProcessRunner.run(root, Duration.ofMinutes(5), ProcessRunner.resolveExecutable(argv));
        assertTrue(r.ok(), "cargo: " + r.err());
        RustLaunch.Artifact a = RustLaunch.pick(RustLaunch.artifacts(r.out()), file, plan.mode());
        assertNotNull(a, "an executable for " + file + " in " + r.out());
        return a;
    }

    private static <T> T onFx(java.util.function.Consumer<java.util.function.Consumer<T>> ask) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> ask.accept(f::complete));
        return f.get(60, TimeUnit.SECONDS);
    }

    @Test
    void aProgramStopsWithItsRustValuesStepsAndEnds(@TempDir Path dir) throws Exception {
        String configured = assumeCargoAndCodelldb();
        Path root = cargoPackage(dir);
        Path main = root.resolve("src/main.rs");
        Session session = new Session();
        // 0-based line 15: `total += item.quantity;`
        DapManager manager = manager(
                session,
                configured,
                List.of(new DapModels.FileBreakpoints(main, List.of(new DapModels.LineBreakpoint(15, null, null)))));
        try {
            RustLaunch.Plan plan = RustLaunch.plan(root, main, MAIN, 11, true);
            assertEquals(RustLaunch.Mode.PROGRAM, plan.mode());
            RustLaunch.Artifact program = build(root, main, plan);
            Map<String, Object> launch =
                    RustLaunch.launch(program.executable(), List.of(), root, Map.of(), false, false);
            FxTestSupport.runOnFx(() -> manager.startExternal(main, DebugLanguages.RUST, launch));

            Stop stop = session.next("the loop's breakpoint");
            assertEquals(15, stop.frames().getFirst().line(), "stopped on the breakpoint's line");
            assertEquals(main, stop.frames().getFirst().file().toRealPath());
            int frame = stop.frames().getFirst().id();
            List<DapModels.ScopeInfo> scopes = onFx(cb -> manager.scopes(frame, cb));
            List<DapModels.VariableInfo> locals =
                    onFx(cb -> manager.variables(scopes.getFirst().variablesReference(), cb));
            assertTrue(
                    locals.stream()
                            .anyMatch(v ->
                                    v.name().equals("greeting") && v.value().contains("hello")),
                    "a String reads as its text (Rust's formatters): " + locals);
            assertTrue(
                    locals.stream()
                            .anyMatch(v -> v.name().equals("total") && v.value().equals("0")),
                    "total is 0 before the first pass: " + locals);
            assertTrue(
                    locals.stream().anyMatch(v -> v.name().equals("items") && v.variablesReference() > 0),
                    "the Vec expands: " + locals);

            FxTestSupport.runOnFx(manager::stepOver);
            Stop stepped = session.next("Step Over");
            assertTrue(
                    List.of(14, 15).contains(stepped.frames().getFirst().line()),
                    "Step Over goes on in the loop: " + stepped.frames().getFirst());

            DapModels.FileBreakpoints none = new DapModels.FileBreakpoints(main, List.of());
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
    void theTestAtTheCaretRunsAlone(@TempDir Path dir) throws Exception {
        String configured = assumeCargoAndCodelldb();
        Path root = cargoPackage(dir);
        Path main = root.resolve("src/main.rs");
        Session session = new Session();
        // A breakpoint in each test: 0-based line 26 in other, line 31 in squares.
        DapManager manager = manager(
                session,
                configured,
                List.of(new DapModels.FileBreakpoints(
                        main,
                        List.of(
                                new DapModels.LineBreakpoint(26, null, null),
                                new DapModels.LineBreakpoint(31, null, null)))));
        try {
            RustLaunch.Plan plan = RustLaunch.plan(root, main, MAIN, 32, true);
            assertEquals(RustLaunch.Mode.TEST, plan.mode());
            assertEquals(List.of("tests::squares", "--exact"), plan.testArgs());
            RustLaunch.Artifact tests = build(root, main, plan);
            Map<String, Object> launch =
                    RustLaunch.launch(tests.executable(), plan.testArgs(), root, Map.of(), false, false);
            FxTestSupport.runOnFx(() -> manager.startExternal(main, DebugLanguages.RUST, launch));

            Stop stop = session.next("the test's breakpoint");
            assertEquals(31, stop.frames().getFirst().line(), "stopped in squares, not other");
            FxTestSupport.runOnFx(manager::resume);
            assertTrue(session.ended.await(120, TimeUnit.SECONDS), "the test run ends: " + session.states);
            assertTrue(session.output.toString().contains("1 passed"), "one test ran: " + session.output);
        } finally {
            FxTestSupport.runOnFx(manager::shutdown);
        }
    }

    @Test
    void aPanicStopsWhereItHappens(@TempDir Path dir) throws Exception {
        String configured = assumeCargoAndCodelldb();
        Path root = cargoPackage(dir);
        Path main = root.resolve("src/main.rs");
        String panicking = MAIN.replace(
                "    let mut total: u32 = 0;", "    let mut total: u32 = 0;\n    let _ = items[9].quantity;");
        Files.writeString(main, panicking);
        Session session = new Session();
        DapManager manager = manager(session, configured, List.of());
        try {
            RustLaunch.Plan plan = RustLaunch.plan(root, main, panicking, 11, true);
            RustLaunch.Artifact program = build(root, main, plan);
            FxTestSupport.runOnFx(() -> manager.startExternal(
                    main,
                    DebugLanguages.RUST,
                    RustLaunch.launch(program.executable(), List.of(), root, Map.of(), false, false)));
            Stop stop = session.next("the panic's stop");
            // CodeLLDB stops a panic at its rust_panic breakpoint: the reason it gives is "breakpoint".
            assertEquals(1, session.reasons.size(), "one stop, for the panic: " + session.reasons);
            assertTrue(
                    stop.frames().stream()
                            .anyMatch(f -> f.file() != null && f.file().endsWith("main.rs") && f.line() == 14),
                    "the frame that indexed out of bounds is on the stack: " + stop.frames());
        } finally {
            FxTestSupport.runOnFx(manager::shutdown);
        }
    }

    @Test
    void inAWindowF5BuildsWithCargoAndDebugTestAtCaretRunsTheTest(@TempDir Path dir) throws Exception {
        String configured = assumeCargoAndCodelldb();
        Path root = cargoPackage(Files.createDirectory(dir.resolve("shelf")));
        Path main = root.resolve("src/main.rs");
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
            fx.shared.getTrustStore().trust(root); // the user's own package: Cargo runs its build scripts
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("Rust debugging available", 60, () -> call(() -> debug.debugEffectiveFor("rust")));
            com.editora.editor.EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            assertTrue(call(b::isBreakpointsEnabled), "a Rust file has the breakpoint gutter");
            FxTestSupport.runOnFx(() -> {
                b.toggleBreakpoint(15);
                b.toggleBreakpoint(31);
            });
            FxTestSupport.runOnFx(() ->
                    ((com.editora.command.CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitStop(dap, debug, 15);
            assertEquals("main.rs", call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile")));
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));

            FxTestSupport.runOnFx(() -> b.getFocusedArea().moveTo(32, 4)); // in squares
            FxTestSupport.runOnFx(() -> ((com.editora.command.CommandRegistry) FxTestSupport.field(c, "registry"))
                    .run("test.debugAtCaret"));
            awaitStop(dap, debug, 31);
            assertEquals("squares", call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile")));
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
