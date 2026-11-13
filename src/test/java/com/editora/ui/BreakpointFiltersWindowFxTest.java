package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.StopFilters;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.StackFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Breakpoint filters through the window, against a {@link FakeDebugAdapter} reached as Ruby's rdbg is: a stop that
 * fails a breakpoint's thread, caller or instance filter goes on unseen, one that passes is shown; a filtered
 * breakpoint is sent without its hit count and log message, which Editora applies to the hits that pass. {@code
 * BreakpointFiltersProbeFxTest} drives real adapters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BreakpointFiltersWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path script;

    /** The 0-based line the breakpoint is on. */
    private static final int LINE = 1;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-in is a shell script");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        Path root = Files.createDirectories(fx.configDir.resolve("orders")).toRealPath();
        script = Files.writeString(root.resolve("orders.rb"), "def save(o)\n  o.total\nend\nsave(1)\n");
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
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = fx.controller.activeBuffer();
            if (b != null) {
                b.getBreakpointManager().clear();
            }
        });
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

    private static StackFrame frame(int id, String name, String path, int line) {
        StackFrame f = new StackFrame();
        f.setId(id);
        f.setName(name);
        f.setLine(line + 1);
        org.eclipse.lsp4j.debug.Source source = new org.eclipse.lsp4j.debug.Source();
        source.setPath(path);
        f.setSource(source);
        return f;
    }

    /**
     * Opens the script with a breakpoint on {@link #LINE} set up by {@code breakpoint}, starts it, and has the stack
     * be the line called from {@code caller}; the session.
     */
    private FakeDebugAdapter.Session start(String caller, java.util.function.Consumer<EditorBuffer> breakpoint)
            throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(script));
        await(
                "orders.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && script.equals(fx.controller.activeBuffer().getPath())));
        EditorBuffer b = call(fx.controller::activeBuffer);
        FxTestSupport.runOnFx(() -> {
            b.toggleBreakpoint(LINE);
            breakpoint.accept(b);
        });
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        session.frames = List.of(frame(1, "save", script.toString(), LINE), frame(2, caller, script.toString(), 3));
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        sent(session);
        return session;
    }

    private long continues(FakeDebugAdapter.Session session) {
        return session.requests.stream().filter("continue"::equals).count();
    }

    /**
     * The program stops on the breakpoint; whether the stop is shown (else it went on unseen). Read from the session:
     * a stop after this one's start that is not held quiet was shown; one held quiet with the program running again
     * went on unseen.
     */
    private boolean hit(FakeDebugAdapter.Session session) throws Exception {
        int serial = call(() -> debug.s().stopSerial);
        session.stop(session.mainThread, "breakpoint");
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
        boolean shown = call(() -> debug.s().quietStop != debug.s().stopSerial);
        // Resume is refused while an evaluation is in flight (the stop's own values loading): asked until it runs.
        await("running", () -> {
            if (shown && call(manager::state) == DapManager.State.SUSPENDED) {
                call(() -> {
                    manager.resume();
                    return true;
                });
            }
            return call(manager::state) == DapManager.State.RUNNING;
        });
        return shown;
    }

    /**
     * The breakpoint last sent for the script, once one has been: the session can be running before the adapter
     * has the script's breakpoints.
     */
    private SourceBreakpoint sent(FakeDebugAdapter.Session session) throws InterruptedException {
        await("the script's breakpoints sent", () -> !sentForScript(session).isEmpty());
        return sentForScript(session).getLast().getBreakpoints()[0];
    }

    private List<SetBreakpointsArguments> sentForScript(FakeDebugAdapter.Session session) {
        return session.breakpoints.stream()
                .filter(a -> a.getSource() != null
                        && script.toString().equals(a.getSource().getPath())
                        && a.getBreakpoints() != null
                        && a.getBreakpoints().length > 0)
                .toList();
    }

    @Test
    void aThreadFilterLetsOnlyItsThreadsStop() throws Exception {
        FakeDebugAdapter.Session session =
                start("main", b -> b.getBreakpointManager().setFilters(LINE, new StopFilters("worker-*", "", "")));
        assertTrue(!hit(session), "the main thread is not a worker");
        FxTestSupport.runOnFx(() -> call(fx.controller::activeBuffer)
                .getBreakpointManager()
                .setFilters(LINE, new StopFilters("main, worker-*", "", "")));
        await("re-sent", () -> session.breakpoints.size() > 1);
        assertTrue(hit(session), "the main thread is one of them now");
    }

    @Test
    void aCallerFilterLooksAtTheFrameBelow() throws Exception {
        FakeDebugAdapter.Session session = start(
                "OrderService.place(Order)",
                b -> b.getBreakpointManager().setFilters(LINE, new StopFilters("", "*.place", "")));
        assertTrue(hit(session));
        session.frames =
                List.of(frame(1, "save", script.toString(), LINE), frame(2, "Report.print()", script.toString(), 3));
        assertTrue(!hit(session), "called from elsewhere");
    }

    @Test
    void aFilteredHitCountAndLogMessageAreEditorasAndCountOnlyPassingHits() throws Exception {
        FakeDebugAdapter.Session session = start("place", b -> {
            b.getBreakpointManager().setFilters(LINE, new StopFilters("", "place", ""));
            b.getBreakpointManager().setHitCondition(LINE, "2");
        });
        SourceBreakpoint sent = sent(session);
        assertNull(sent.getHitCondition(), "the adapter would count the hits the filter drops");
        assertTrue(!hit(session), "the first passing hit");
        session.frames = List.of(frame(1, "save", script.toString(), LINE), frame(2, "other", script.toString(), 3));
        assertTrue(!hit(session), "filtered out: not counted");
        session.frames = List.of(frame(1, "save", script.toString(), LINE), frame(2, "place", script.toString(), 3));
        assertTrue(hit(session), "the second passing hit");

        // A filtered logpoint: its message is not sent, Editora writes it at a passing hit.
        int sends = sentForScript(session).size();
        FxTestSupport.runOnFx(() -> {
            var mgr = call(fx.controller::activeBuffer).getBreakpointManager();
            mgr.setHitCondition(LINE, "");
            mgr.setLogMessage(LINE, "saved {o}");
        });
        await("re-sent as a logpoint", () -> {
            List<SetBreakpointsArguments> now = sentForScript(session);
            return now.size() > sends && now.getLast().getBreakpoints()[0].getLogMessage() == null;
        });
        long before = continues(session);
        session.stop(session.mainThread, "breakpoint");
        await(
                "written",
                () -> call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(debug.panel(), "console")
                        .getText()
                        .contains("saved val(o)")));
        await("and gone on", () -> continues(session) > before);
    }

    @Test
    void anInstanceFilterStopsOnTheMarkedObjectOnly() throws Exception {
        FakeDebugAdapter.Session session =
                start("place", b -> b.getBreakpointManager().setFilters(LINE, new StopFilters("", "", "mine")));
        session.evaluateResults.put("self", "#<Order:0x0000aaaa00001111>");
        assertTrue(hit(session), "no object has the mark yet: it stops as usual");
        FxTestSupport.runOnFx(() -> debug.s().marks.mark("#<Order:0x0000aaaa00001111>", "mine"));
        assertTrue(hit(session), "the marked object");
        session.evaluateResults.put("self", "#<Order:0x0000bbbb00002222>");
        assertTrue(!hit(session), "another object");
        assertEquals("self", session.evaluateRequests.getLast().getExpression(), "Ruby's self");
    }
}
