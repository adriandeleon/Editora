package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.dap.StepFilter;
import org.eclipse.lsp4j.debug.StackFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Don't step into through the window, against a {@link FakeDebugAdapter} reached as Ruby's rdbg is (a debugger with no
 * option to skip code, so Editora does): a step that ends in a filtered file is not shown and the thread steps out of
 * it, a breakpoint there still stops, another language's filter does nothing, and the call stack's Don't Step Into
 * This… offers the frame's file and adds it. {@code StepFiltersProbeFxTest} drives debugpy, java-debug, js-debug and
 * Delve.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StepFiltersWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path script;
    private Path helper;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-in is a shell script");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        Path root = Files.createDirectories(fx.configDir.resolve("shout")).toRealPath();
        helper = Files.writeString(root.resolve("helper.rb"), "def shout(s)\n  s.upcase\nend\n");
        script = Files.writeString(root.resolve("main.rb"), "require_relative 'helper'\na = shout('hi')\nputs a\n");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        Path rdbg = fx.configDir.resolve("rdbg");
        Files.writeString(
                rdbg,
                "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                        + "' com.editora.dap.RelayAdapterMain announce-rdbg " + adapter.port() + "\n");
        assertTrue(rdbg.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setRubyDebugPath(rdbg.toString());
            debug.applySupport();
        });
        await("Ruby debugging available", () -> call(() -> debug.debugEffectiveFor("ruby")));
    }

    @AfterEach
    void endSession() throws Exception {
        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));
        filters(List.of());
        debug.stepFilters().editor = null;
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
        if (adapter != null) {
            adapter.close();
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
        long end = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private String status() {
        return call(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<Label>field(bar, "echo").getText();
        });
    }

    /** Settings' Don't step into list is {@code list}, and every session is told. */
    private void filters(List<StepFilter> list) throws Exception {
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugStepFilterList(new ArrayList<>(list));
            manager.setStepFilters(list);
        });
    }

    private static StackFrame frame(int id, String name, Path path, int line) {
        StackFrame f = new StackFrame();
        f.setId(id);
        f.setName(name);
        f.setLine(line + 1);
        org.eclipse.lsp4j.debug.Source source = new org.eclipse.lsp4j.debug.Source();
        source.setPath(path.toString());
        f.setSource(source);
        return f;
    }

    private FakeDebugAdapter.Session start() throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(script));
        await(
                "main.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && script.equals(fx.controller.activeBuffer().getPath())));
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        return session;
    }

    /** The program stops for {@code reason} with {@code frames}; whether the stop is shown (else it went on). */
    private boolean stops(FakeDebugAdapter.Session session, String reason, StackFrame... frames) throws Exception {
        session.frames = List.of(frames);
        int serial = call(() -> debug.s().stopSerial);
        session.stop(session.mainThread, reason);
        await(
                "shown or gone on",
                () -> call(() -> {
                    DebugSession s = debug.s();
                    if (s.stopSerial <= serial) {
                        return false;
                    }
                    return s.quietStop != s.stopSerial
                            ? manager.state() == DapManager.State.SUSPENDED
                            : manager.state() == DapManager.State.RUNNING;
                }));
        return call(() -> debug.s().quietStop != debug.s().stopSerial);
    }

    private long count(FakeDebugAdapter.Session session, String request) {
        return session.requests.stream().filter(request::equals).count();
    }

    @Test
    void aStepEndingInFilteredCodeIsSteppedOutOf() throws Exception {
        filters(List.of(new StepFilter(true, "ruby", "**/helper.rb"), new StepFilter(true, "python", "main")));
        FakeDebugAdapter.Session session = start();
        assertTrue(stops(session, "breakpoint", frame(1, "<main>", script, 1)));

        run("debug.stepInto");
        await("the step asked for", () -> count(session, "stepIn") == 1);
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        // The step ends in shout(), in helper.rb: not shown, and the thread steps out of it.
        assertTrue(!stops(session, "step", frame(2, "shout", helper, 1), frame(1, "<main>", script, 1)));
        await("out of it", () -> count(session, "stepOut") == 1);
        // Out of it: back in main.rb, shown — a python filter on "main" is another language's.
        assertTrue(stops(session, "step", frame(1, "<main>", script, 1)));
        assertEquals(script, call(() -> debug.panel().callStack().getFirst().file()));

        // A breakpoint in helper.rb stops there all the same.
        FxTestSupport.runOnFx(manager::resume);
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        assertTrue(stops(session, "breakpoint", frame(2, "shout", helper, 1), frame(1, "<main>", script, 1)));
        assertEquals(1, count(session, "stepOut"), "a breakpoint is not a step");

        // The filter turned off: a step into helper.rb is shown.
        filters(List.of(new StepFilter(false, "ruby", "**/helper.rb")));
        FxTestSupport.runOnFx(manager::resume);
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        assertTrue(stops(session, "step", frame(2, "shout", helper, 1), frame(1, "<main>", script, 1)));
    }

    @Test
    void dontStepIntoThisOffersTheFramesFileAndAddsIt() throws Exception {
        FakeDebugAdapter.Session session = start();
        assertTrue(stops(session, "breakpoint", frame(2, "shout", helper, 1), frame(1, "<main>", script, 1)));
        List<StepFilter> offered = new ArrayList<>();
        debug.stepFilters().editor = (owner, initial, isNew) -> {
            offered.add(initial);
            return Optional.of(initial);
        };
        run("debug.dontStepIntoFrame");
        await("the form", () -> !offered.isEmpty()); // the frame's package is looked for off the FX thread (PERF-01)
        assertEquals(List.of(new StepFilter(true, "ruby", "**/helper.rb")), offered);
        assertEquals(
                List.of(new StepFilter(true, "ruby", "**/helper.rb")),
                call(() -> fx.shared.getSettings().getDebugStepFilterList()));
        assertEquals(List.of("**/helper.rb"), call(manager::stepFilterPatterns), "the session is told at once");
        assertEquals(tr("status.debug.stepFilterAdded", "**/helper.rb"), status());
    }
}
