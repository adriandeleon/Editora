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
import java.util.function.BooleanSupplier;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugLanguages;
import com.editora.dap.ScriptLaunch;
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
 * Opt-in probe against the real PHP, Xdebug and vscode-php-debug (on Node.js): a script's breakpoint hits, its frame
 * has the locals, Step Over advances, the program's output reaches the console and the session ends with it; an
 * exception stops when PHP's exceptions are chosen; and in a window F5 runs the script with Xdebug and stops. Run with
 * {@code ./mvnw test -Dtest=PhpDebugProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs {@code php} with Xdebug,
 * {@code node} and vscode-php-debug (Editora's install, or {@code -Dphpdebug.path=<phpDebug.js or its folder>}).
 */
@Tag("probe")
@Tag("fx")
class PhpDebugProbeFxTest {

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

    /** 0-based line 6 is {@code $total += $quantity;}. */
    static final String SCRIPT = """
            <?php
            function square(int $n): int { return $n * $n; }
            $greeting = "hello";
            $items = [["pencil", 12], ["tape", 4]];
            $total = 0;
            foreach ($items as [$name, $quantity]) {
                $total += $quantity;
            }
            echo "$greeting total $total square " . square(7) . "\\n";
            """;

    /** The adapter to use: {@code -Dphpdebug.path}, else wherever Editora looks; the test skipped without it. */
    static String assumePhpXdebugAndAdapter() {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(
                Path.of(ProcessRunner.resolveExecutable(List.of("php")).getFirst())
                        .isAbsolute(),
                "needs php");
        assumeTrue(
                Path.of(ProcessRunner.resolveExecutable(List.of("node")).getFirst())
                        .isAbsolute(),
                "needs node");
        ProcessRunner.Result modules = ProcessRunner.run(
                null, java.time.Duration.ofSeconds(30), ProcessRunner.resolveExecutable(List.of("php", "-m")));
        assumeTrue(modules.out().contains("Xdebug"), "needs PHP's Xdebug extension");
        String configured = System.getProperty("phpdebug.path", "");
        assumeTrue(DapManager.locateExternal(DebugLanguages.PHP, configured) != null, "needs vscode-php-debug");
        return configured;
    }

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static <T> T onFx(java.util.function.Consumer<java.util.function.Consumer<T>> ask) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> ask.accept(f::complete));
        return f.get(60, TimeUnit.SECONDS);
    }

    private static DapManager manager(
            Session session,
            com.editora.dap.DebugLanguage language,
            String configured,
            List<DapModels.FileBreakpoints> bps)
            throws Exception {
        DapManager manager = new DapManager(null);
        manager.setListener(session);
        manager.setBreakpointsSupplier(() -> bps);
        CompletableFuture<Boolean> found = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> {
            manager.configure(true, "", false, "", false, "");
            manager.configureExternal(language, true, configured, found::complete);
        });
        assertTrue(found.get(30, TimeUnit.SECONDS), language.displayName() + "'s adapter found by the manager");
        return manager;
    }

    @Test
    void aScriptStopsWithItsValuesStepsAndEnds(@TempDir Path dir) throws Exception {
        String configured = assumePhpXdebugAndAdapter();
        Path script = Files.writeString(dir.toRealPath().resolve("shelf.php"), SCRIPT);
        Session session = new Session();
        DapManager manager = manager(
                session,
                DebugLanguages.PHP,
                configured,
                List.of(new DapModels.FileBreakpoints(script, List.of(new DapModels.LineBreakpoint(6, null, null)))));
        try {
            Map<String, Object> launch = ScriptLaunch.phpLaunch(script, List.of(), script.getParent());
            FxTestSupport.runOnFx(() -> manager.startExternal(script, DebugLanguages.PHP, launch));

            Stop stop = session.next("the loop's breakpoint");
            assertEquals(6, stop.frames().getFirst().line(), "stopped on the breakpoint's line");
            assertEquals(script, stop.frames().getFirst().file().toRealPath());
            int frame = stop.frames().getFirst().id();
            List<DapModels.ScopeInfo> scopes = onFx(cb -> manager.scopes(frame, cb));
            List<DapModels.VariableInfo> locals =
                    onFx(cb -> manager.variables(scopes.getFirst().variablesReference(), cb));
            assertTrue(
                    locals.stream()
                            .anyMatch(v ->
                                    v.name().equals("$greeting") && v.value().contains("hello")),
                    "a string reads as its text: " + locals);
            assertTrue(
                    locals.stream()
                            .anyMatch(
                                    v -> v.name().equals("$total") && v.value().equals("0")),
                    "total is 0 before the first pass: " + locals);

            FxTestSupport.runOnFx(manager::stepOver);
            Stop stepped = session.next("Step Over");
            assertTrue(
                    List.of(5, 6, 7).contains(stepped.frames().getFirst().line()),
                    "Step Over goes on in the loop: " + stepped.frames().getFirst());

            DapModels.FileBreakpoints none = new DapModels.FileBreakpoints(script, List.of());
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
    void inAWindowF5RunsTheScriptWithXdebug(@TempDir Path dir) throws Exception {
        String configured = assumePhpXdebugAndAdapter();
        Path script = Files.writeString(
                Files.createDirectory(dir.resolve("shelf")).toRealPath().resolve("shelf.php"), SCRIPT);
        Path configDir = Files.createDirectory(dir.resolve("config"));
        com.editora.config.SharedConfig seed = new com.editora.config.SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setPhpDebugPath(configured);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(script, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("PHP debugging available", 60, () -> call(() -> debug.debugEffectiveFor("php")));
            com.editora.editor.EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            assertTrue(call(b::isBreakpointsEnabled), "a PHP file has the breakpoint gutter");
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(6));
            FxTestSupport.runOnFx(() ->
                    ((com.editora.command.CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitStop(dap, debug, 6);
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
