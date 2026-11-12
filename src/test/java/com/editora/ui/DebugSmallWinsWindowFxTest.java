package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The debugger's small wins through the window, against a {@link FakeDebugAdapter} reached as Ruby's rdbg is: Copy as
 * Expression gives the adapter's own expression, a line number in the gutter runs to its line while paused, a run is
 * timed to its stop, and a logpoint with {@code $FUNCTION} or {@code $CALLSTACK} is written by Editora at a stop the
 * program goes on from. {@code DebugSmallWinsProbeFxTest} drives real adapters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DebugSmallWinsWindowFxTest {

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
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        Path root = Files.createDirectories(fx.configDir.resolve("shelf")).toRealPath();
        script = Files.writeString(root.resolve("shelf.rb"), "x = 1\nx += 1\nputs x\nputs x * 2\nputs x * 3\n");
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
            fx.shared.getSettings().setDebugStepTiming(true);
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

    private String console() {
        return call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(debug.panel(), "console")
                .getText());
    }

    private long continues(FakeDebugAdapter.Session session) {
        return session.requests.stream().filter("continue"::equals).count();
    }

    /** Opens the script and starts debugging it; the session, launched. */
    private EditorBuffer open() throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(script));
        await(
                "shelf.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && script.equals(fx.controller.activeBuffer().getPath())));
        return FxTestSupport.callOnFx(fx.controller::activeBuffer);
    }

    private FakeDebugAdapter.Session start() throws Exception {
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        session.framePath = script.toString();
        return session;
    }

    /** The program stops ({@code reason}) on 0-based {@code line}, and the window shows the stop. */
    private void stopAt(FakeDebugAdapter.Session session, int line, String reason) throws Exception {
        session.frameLine = line + 1; // DAP lines start at 1
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(1, reason);
        await(
                "the stop shown",
                () -> call(() -> manager.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()
                        && debug.panel().callStack().getFirst().line() == line));
    }

    /** The Variables row called {@code name}. FX thread. */
    private TreeItem<DebugPanel.VarRow> item(String name) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
        if (tree.getRoot() == null) {
            return null;
        }
        for (TreeItem<DebugPanel.VarRow> top : tree.getRoot().getChildren()) {
            for (TreeItem<DebugPanel.VarRow> child : top.getChildren()) {
                if (child.getValue() != null && child.getValue().name().equals(name)) {
                    return child;
                }
            }
        }
        return null;
    }

    /** The Variables menu as it opens on the row called {@code name}. FX thread. */
    private List<MenuItem> menuOn(String name) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
        tree.getSelectionModel().select(item(name));
        ContextMenu menu = tree.getContextMenu();
        menu.getOnShowing().handle(null);
        return List.copyOf(menu.getItems());
    }

    private static MenuItem find(List<MenuItem> items, String text) {
        return items.stream().filter(i -> text.equals(i.getText())).findFirst().orElse(null);
    }

    private static MouseEvent mouse(EventType<MouseEvent> type, boolean shift) {
        return new MouseEvent(
                type,
                0,
                0,
                0,
                0,
                MouseButton.PRIMARY,
                1,
                shift,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    /** The line number of 0-based {@code line} in a freshly built gutter row. FX thread. */
    private static Label lineNumber(EditorBuffer b, int line) {
        HBox row = (HBox) b.getFocusedArea().getParagraphGraphicFactory().apply(line);
        return (Label) row.getChildren().stream()
                .filter(n -> n.getStyleClass().contains("lineno"))
                .findFirst()
                .orElseThrow();
    }

    private static boolean armed(Label number) {
        return number.getStyleClass().contains("run-to-here") && number.getGraphic() != null;
    }

    /** The last breakpoints sent for the script, as 1-based lines. */
    private List<Integer> sentLines(FakeDebugAdapter.Session session) {
        List<SetBreakpointsArguments> sent = session.breakpoints.stream()
                .filter(a -> a.getSource() != null
                        && script.toString().equals(a.getSource().getPath()))
                .toList();
        if (sent.isEmpty()) {
            return List.of();
        }
        SourceBreakpoint[] bps = sent.getLast().getBreakpoints();
        return bps == null
                ? List.of()
                : java.util.Arrays.stream(bps).map(SourceBreakpoint::getLine).toList();
    }

    private static String elapsed(EditorBuffer b) {
        Object overlay = FxTestSupport.field(b, "inlineValues");
        return overlay == null ? null : FxTestSupport.field(overlay, "elapsed");
    }

    @Test
    void copyAsExpressionGivesTheAdaptersOwnExpression() throws Exception {
        open();
        FakeDebugAdapter.Session session = start();
        var first = FakeDebugAdapter.Session.variable("first", "\"a\"", 0);
        first.setEvaluateName("this.items.get(0)");
        session.variables.put(
                FakeDebugAdapter.Session.LOCALS, List.of(first, FakeDebugAdapter.Session.variable("x", "2", 0)));
        stopAt(session, 1, "breakpoint");
        await("the variables", () -> call(() -> item("first") != null && item("x") != null));

        List<MenuItem> onFirst = call(() -> menuOn("first"));
        assertNotNull(find(onFirst, tr("debugpanel.addToWatches", "this.items.get(0)")), "and Add to Watches uses it");
        FxTestSupport.runOnFx(
                () -> find(onFirst, tr("debugpanel.copyExpression")).fire());
        assertEquals(
                "this.items.get(0)",
                call(() -> javafx.scene.input.Clipboard.getSystemClipboard().getString()));
        FxTestSupport.runOnFx(
                () -> find(menuOn("x"), tr("debugpanel.copyExpression")).fire());
        assertEquals(
                "x",
                call(() -> javafx.scene.input.Clipboard.getSystemClipboard().getString()),
                "no expression from the adapter: the row's path");
        assertNull(
                call(() -> find(menuOn("x"), tr("debugpanel.jumpToTypeSource"))),
                "no language server finds the type: not offered");
    }

    @Test
    void aLineNumberInTheGutterRunsToItsLineWhilePaused() throws Exception {
        EditorBuffer b = open();
        FakeDebugAdapter.Session session = start();
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        assertFalse(
                call(() -> {
                    Label running = lineNumber(b, 3);
                    Event.fireEvent(running, mouse(MouseEvent.MOUSE_ENTERED, false));
                    return armed(running);
                }),
                "only while paused");
        stopAt(session, 1, "breakpoint");

        assertFalse(
                call(() -> {
                    Label here = lineNumber(b, 1);
                    Event.fireEvent(here, mouse(MouseEvent.MOUSE_ENTERED, false));
                    return armed(here);
                }),
                "not the line it stopped on");
        Label number = call(() -> lineNumber(b, 3));
        FxTestSupport.runOnFx(() -> Event.fireEvent(number, mouse(MouseEvent.MOUSE_ENTERED, false)));
        assertTrue(call(() -> armed(number)), "the hovered number turns into the arrow");
        FxTestSupport.runOnFx(() -> Event.fireEvent(number, mouse(MouseEvent.MOUSE_EXITED, false)));
        assertFalse(call(() -> armed(number)), "and back when the mouse leaves");

        long before = continues(session);
        FxTestSupport.runOnFx(() -> {
            Event.fireEvent(number, mouse(MouseEvent.MOUSE_ENTERED, false));
            Event.fireEvent(number, mouse(MouseEvent.MOUSE_CLICKED, false));
        });
        await("continued", () -> continues(session) == before + 1);
        assertTrue(sentLines(session).contains(4), "a breakpoint for the run to line 4: " + sentLines(session));
    }

    @Test
    void aRunIsTimedToItsStop() throws Exception {
        EditorBuffer b = open();
        FakeDebugAdapter.Session session = start();
        stopAt(session, 1, "breakpoint");
        assertNull(call(() -> elapsed(b)), "the first stop after the launch is not timed");

        FxTestSupport.runOnFx(manager::resume);
        stopAt(session, 2, "step");
        await("the time shown", () -> call(() -> elapsed(b)) != null);
        String shown = call(() -> elapsed(b));
        assertTrue(
                shown.matches(".*\\d.*")
                        && shown.endsWith(tr("debug.stepTime", "").strip()),
                shown);
        assertEquals(2, (int)
                call(() -> FxTestSupport.<Integer>field(FxTestSupport.field(b, "inlineValues"), "elapsedLine")));

        FxTestSupport.runOnFx(manager::resume);
        assertNull(call(() -> elapsed(b)), "gone while the program runs");
        session.frameLine = 4;
        session.stop(1, "pause");
        await("paused", () -> call(manager::state) == DapManager.State.SUSPENDED);
        Thread.sleep(200);
        assertNull(call(() -> elapsed(b)), "a Pause's time is the user's");

        FxTestSupport.runOnFx(() -> fx.shared.getSettings().setDebugStepTiming(false));
        FxTestSupport.runOnFx(manager::resume);
        stopAt(session, 4, "step");
        Thread.sleep(200);
        assertNull(call(() -> elapsed(b)), "the setting off");
    }

    @Test
    void aLogpointWithTokensIsWrittenByEditoraAndTheProgramGoesOn() throws Exception {
        EditorBuffer b = open();
        var mgr = b.getBreakpointManager();
        FxTestSupport.runOnFx(() -> {
            b.toggleBreakpoint(2);
            mgr.setLogMessage(2, "$FUNCTION: x={x} on $TNAME$CALLSTACK");
            b.toggleBreakpoint(3);
            mgr.setLogMessage(3, "plain {x}");
        });
        FakeDebugAdapter.Session session = start();
        await("breakpoints sent", () -> sentLines(session).size() == 2);
        SourceBreakpoint[] sent = session.breakpoints.stream()
                .filter(a -> a.getSource() != null
                        && script.toString().equals(a.getSource().getPath()))
                .toList()
                .getLast()
                .getBreakpoints();
        assertNull(sent[0].getLogMessage(), "the adapter does not know the tokens: a plain stop");
        assertEquals("plain {x}", sent[1].getLogMessage(), "a logpoint without tokens is the adapter's");

        long before = continues(session);
        session.frameLine = 3;
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(session.mainThread, "breakpoint");
        await("written and continued", () -> continues(session) == before + 1);
        try {
            await("in the console", () -> console().contains("debuggee: x=val(x) on main\n\tdebuggee(shelf.rb:3)"));
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + ":\n" + console(), e);
        }
        assertFalse(
                call(() ->
                        b.getFocusedArea().getParagraph(2).getParagraphStyle().contains("exec-line")),
                "the editor is not taken to it");
    }
}
