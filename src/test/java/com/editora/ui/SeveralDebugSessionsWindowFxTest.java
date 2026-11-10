package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Two programs debugged at once in one window, against a {@link FakeDebugAdapter} reached as Ruby's rdbg is: F5 in a
 * second file while the first runs offers Debug Both; each session has its own tab, panel and console; a session that
 * stops is shown, with its execution line in the editor; a breakpoint set meanwhile reaches both; Stop All ends both,
 * and an ended session's tab can be closed. {@code SeveralSessionsProbeFxTest} drives real debugpy.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeveralDebugSessionsWindowFxTest {

    private FxWindowFixture fx;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path first;
    private Path second;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-in is a shell script");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        Path root = Files.createDirectories(fx.configDir.resolve("pair")).toRealPath();
        first = Files.writeString(root.resolve("server.rb"), "x = 1\nx += 1\nputs x\n");
        second = Files.writeString(root.resolve("client.rb"), "y = 1\ny += 1\nputs y\n");
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

    private EditorBuffer open(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(file));
        await(
                file.getFileName() + " in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && file.equals(fx.controller.activeBuffer().getPath())));
        return FxTestSupport.callOnFx(fx.controller::activeBuffer);
    }

    private static String console(DebugPanel panel) {
        return call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(panel, "console")
                .getText());
    }

    private static boolean execLine(EditorBuffer b, int line) {
        return call(
                () -> b.getFocusedArea().getParagraph(line).getParagraphStyle().contains("exec-line"));
    }

    private static long setBreakpoints(FakeDebugAdapter.Session session) {
        return session.requests.stream().filter("setBreakpoints"::equals).count();
    }

    @Test
    void twoProgramsAreDebuggedSideBySide() throws Exception {
        EditorBuffer server = open(first);
        run("debug.start");
        FakeDebugAdapter.Session one = adapter.awaitSession();
        one.awaitRequest("launch");
        one.framePath = first.toString();
        DebugSession a = call(() -> debug.sessions().focused());
        await("the server running", () -> call(a.dap::state) == DapManager.State.RUNNING);
        DebugSessionsView view = call(debug::view);
        assertFalse(call(view::stripShown), "one session: no tabs");

        EditorBuffer client = open(second);
        List<Boolean> offered = new ArrayList<>();
        debug.retargetChoice = (question, both) -> {
            offered.add(both);
            return DebugCoordinator.Retarget.BOTH;
        };
        run("debug.start");
        FakeDebugAdapter.Session two = adapter.awaitSession();
        two.awaitRequest("launch");
        two.framePath = second.toString();
        assertEquals(List.of(true), offered, "Debug Both is offered beside a running session");
        DebugSession b = call(() -> debug.sessions().focused());
        assertNotSame(a, b, "the second file is debugged in a session of its own");
        assertTrue(call(() -> b.dap.sharesSettingsWith(a.dap)));
        await("the client running", () -> call(b.dap::state) == DapManager.State.RUNNING);
        assertEquals(1, one.disconnected.getCount(), "the server goes on");
        assertTrue(call(view::stripShown), "two sessions: a tab each");
        assertEquals(
                List.of("server.rb", "client.rb"),
                call(() ->
                        view.tabs().stream().map(DebugSessionsView.Tab::title).toList()));
        assertSame(b.panel, call(view::getCenter), "the new session is shown");
        assertNotSame(a.panel, b.panel, "each session has its own panel");

        // Output goes to the console of the session that wrote it, shown or not.
        one.output("from the server\n");
        two.output("from the client\n");
        await(
                "each console",
                () -> console(a.panel).contains("from the server")
                        && console(b.panel).contains("from the client"));
        assertFalse(console(b.panel).contains("from the server"));

        // The server stops while the client is shown: the server is shown, with its line in the editor.
        one.frameLine = 2;
        one.stop(1, "breakpoint");
        await("the server shown", () -> call(() -> debug.sessions().focused()) == a);
        await("its execution line", () -> execLine(server, 1));
        assertSame(a.panel, call(view::getCenter));
        assertEquals(DapManager.State.RUNNING, call(b.dap::state), "the client still runs");
        assertEquals(
                List.of(tr("debug.state.suspended"), tr("debug.state.running")),
                call(() ->
                        view.tabs().stream().map(DebugSessionsView.Tab::state).toList()));

        // A breakpoint set now reaches both sessions.
        long before1 = setBreakpoints(one);
        long before2 = setBreakpoints(two);
        FxTestSupport.runOnFx(() -> client.toggleBreakpoint(2));
        await("both told", () -> setBreakpoints(one) > before1 && setBreakpoints(two) > before2);

        // Showing the client takes the server's line off the editor; showing the server again puts it back.
        FxTestSupport.runOnFx(() -> debug.focusSession(b));
        assertFalse(execLine(server, 1), "the client is shown: no server line");
        FxTestSupport.runOnFx(() -> debug.focusSession(a));
        await("the server's line again", () -> execLine(server, 1));

        // Continue acts on the session shown.
        run("debug.continue");
        await("the server continued", () -> one.requests.contains("continue"));
        assertFalse(two.requests.contains("continue"), "not the client");

        run("debug.stopAll");
        await("both ended", () -> one.disconnected.getCount() == 0 && two.disconnected.getCount() == 0);
        await("both tabs ended", () -> call(() -> view.tabs().stream().noneMatch(DebugSessionsView.Tab::live)));
        assertTrue(call(view::stripShown), "ended sessions keep their tabs and output");

        FxTestSupport.runOnFx(() -> debug.closeSession(a));
        assertEquals(1, call(() -> debug.sessions().size()));
        assertSame(b, call(() -> debug.sessions().focused()));
        assertFalse(call(view::stripShown), "one session again: no tabs");
        assertTrue(console(b.panel).contains("from the client"), "its output stays");
    }
}
