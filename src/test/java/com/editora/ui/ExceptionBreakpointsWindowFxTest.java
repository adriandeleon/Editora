package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.config.ExceptionBreakpoint;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.testkit.Await;
import org.eclipse.lsp4j.debug.ExceptionBreakMode;
import org.eclipse.lsp4j.debug.ExceptionDetails;
import org.eclipse.lsp4j.debug.ExceptionInfoResponse;
import org.eclipse.lsp4j.debug.StackFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Exception breakpoints through the window, against a {@link FakeDebugAdapter} reached as Ruby's rdbg is (an adapter
 * with no "every thrown exception" filter, whose exception stops they narrow): a stop is shown when an exception
 * breakpoint names the exception's type and its condition holds, and goes on unseen otherwise; an uncaught one is
 * always shown; the same exception passing up a frame is not shown again; Add Exception Breakpoint offers the type of
 * the exception shown. {@code ExceptionBreakpointsProbeFxTest} drives debugpy and java-debug.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExceptionBreakpointsWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path script;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-in is a shell script");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        adapter.exceptionInfo = exception("KeyError", ExceptionBreakMode.ALWAYS); // advertised from initialize on
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        Path root = Files.createDirectories(fx.configDir.resolve("orders")).toRealPath();
        script = Files.writeString(root.resolve("orders.rb"), "def risky\n  raise KeyError\nend\nrisky\n");
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
        set(List.of());
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
        Await.until(condition, what, Duration.ofSeconds(20));
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

    private static ExceptionInfoResponse exception(String type, ExceptionBreakMode mode) {
        ExceptionInfoResponse r = new ExceptionInfoResponse();
        r.setExceptionId(type);
        r.setDescription("boom");
        r.setBreakMode(mode);
        ExceptionDetails d = new ExceptionDetails();
        d.setTypeName(type);
        r.setDetails(d);
        return r;
    }

    /** The project's exception breakpoints are {@code list} (and every session is told). */
    private void set(List<ExceptionBreakpoint> list) throws Exception {
        FxTestSupport.runOnFx(() -> {
            fx.controller.config.getExceptionBreakpoints().clear();
            fx.controller.config.getExceptionBreakpoints().addAll(list);
            debug.pushExceptionBreakpoints();
        });
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

    private FakeDebugAdapter.Session start() throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(script));
        await(
                "orders.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && script.equals(fx.controller.activeBuffer().getPath())));
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        String path = script.toString();
        session.frames = List.of(frame(1, "risky", path, 1), frame(2, "<main>", path, 3));
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        return session;
    }

    /** The program stops on {@code thrown}; whether the stop is shown (else it went on unseen). */
    private boolean thrown(FakeDebugAdapter.Session session, ExceptionInfoResponse thrown) throws Exception {
        adapter.exceptionInfo = thrown;
        int serial = call(() -> debug.s().stopSerial);
        session.stop(session.mainThread, "exception");
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

    @Test
    void onlyTheTypesNamedAreShown() throws Exception {
        FakeDebugAdapter.Session session = start();
        assertTrue(thrown(session, exception("ValueError", ExceptionBreakMode.ALWAYS)), "none set: every stop");

        set(List.of(new ExceptionBreakpoint("ruby", "KeyError, Name*", "", true)));
        assertTrue(thrown(session, exception("KeyError", ExceptionBreakMode.ALWAYS)));
        assertTrue(thrown(session, exception("Errors::NameError", ExceptionBreakMode.ALWAYS)), "by its last part");
        long before = session.requests.stream().filter("continue"::equals).count();
        assertFalse(thrown(session, exception("ValueError", ExceptionBreakMode.ALWAYS)));
        await(
                "it went on",
                () -> session.requests.stream().filter("continue"::equals).count() > before);
        assertTrue(
                thrown(session, exception("ValueError", ExceptionBreakMode.UNHANDLED)), "uncaught: the user's choice");

        set(List.of(new ExceptionBreakpoint("ruby", "KeyError", "", false)));
        assertTrue(thrown(session, exception("ValueError", ExceptionBreakMode.ALWAYS)), "the only one is off");
        set(List.of(new ExceptionBreakpoint("python", "KeyError", "", true)));
        assertTrue(thrown(session, exception("ValueError", ExceptionBreakMode.ALWAYS)), "another language's");
    }

    @Test
    void aConditionIsEvaluatedWhereItWasThrown() throws Exception {
        FakeDebugAdapter.Session session = start();
        set(List.of(new ExceptionBreakpoint("ruby", "", "n > 1", true)));
        session.evaluateResults.put("n > 1", "false");
        assertFalse(thrown(session, exception("KeyError", ExceptionBreakMode.ALWAYS)));
        assertEquals(1, session.evaluateRequests.getLast().getFrameId(), "the frame that threw");
        session.evaluateResults.put("n > 1", "true");
        assertTrue(thrown(session, exception("KeyError", ExceptionBreakMode.ALWAYS)));

        set(List.of(new ExceptionBreakpoint("ruby", "", "bad condition", true)));
        assertTrue(thrown(session, exception("KeyError", ExceptionBreakMode.ALWAYS)), "cannot evaluate: it stops");
        assertTrue(status().contains("bad condition"), "and says why: " + status());
    }

    @Test
    void theSameExceptionPassingUpIsShownOnce() throws Exception {
        FakeDebugAdapter.Session session = start();
        set(List.of(new ExceptionBreakpoint("ruby", "KeyError", "", true)));
        assertTrue(thrown(session, exception("KeyError", ExceptionBreakMode.ALWAYS)));
        session.frames = List.of(frame(2, "<main>", script.toString(), 3)); // one frame further out
        assertFalse(thrown(session, exception("KeyError", ExceptionBreakMode.ALWAYS)), "the same one, passing up");
        session.frames = List.of(frame(1, "risky", script.toString(), 1), frame(2, "<main>", script.toString(), 3));
        assertTrue(thrown(session, exception("KeyError", ExceptionBreakMode.ALWAYS)), "thrown anew");
    }

    @Test
    void addOffersTheTypeOfTheExceptionShownAndTheyAreKept() throws Exception {
        FakeDebugAdapter.Session session = start();
        adapter.exceptionInfo = exception("KeyError", ExceptionBreakMode.ALWAYS);
        session.stop(session.mainThread, "exception");
        await("the exception shown", () -> call(() -> debug.s().stopException != null));
        ExceptionBreakpoint[] offered = new ExceptionBreakpoint[1];
        FxTestSupport.runOnFx(() -> debug.exceptions().exceptionEditor = (initial, isNew) -> {
            offered[0] = initial;
            return Optional.of(new ExceptionBreakpointDialog.Result(
                    new ExceptionBreakpoint(initial.language(), initial.type(), "x > 0", true), false));
        });
        run("debug.addExceptionBreakpoint");
        assertEquals(new ExceptionBreakpoint("ruby", "KeyError", "", true), offered[0]);
        assertEquals(
                List.of(new ExceptionBreakpoint("ruby", "KeyError", "x > 0", true)),
                call(() -> List.copyOf(fx.controller.config.getExceptionBreakpoints())));
        assertEquals(tr("status.debug.exceptionBreakpointSaved", "KeyError  if x > 0"), status());

        // The picker lists it after the adapter's filters, then Add.
        List<ExceptionBreakpointCommands.ExceptionRow> rows =
                call(() -> debug.exceptions().exceptionRows("ruby", manager.exceptionFilterOptions("ruby")));
        assertTrue(rows.getLast() instanceof ExceptionBreakpointCommands.ExceptionRow.Add);
        assertTrue(rows.get(rows.size() - 2) instanceof ExceptionBreakpointCommands.ExceptionRow.Typed);

        // Edit → Remove.
        FxTestSupport.runOnFx(() -> debug.exceptions().exceptionEditor =
                (initial, isNew) -> Optional.of(new ExceptionBreakpointDialog.Result(null, true)));
        FxTestSupport.runOnFx(() -> debug.exceptions().editExceptionBreakpoint(0, "ruby"));
        assertTrue(call(() -> fx.controller.config.getExceptionBreakpoints().isEmpty()));
        FxTestSupport.runOnFx(() -> debug.exceptions().exceptionEditor = null);
    }
}
