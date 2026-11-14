package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Thread Dump through the window, against a {@link FakeDebugAdapter} reached as Ruby's rdbg is: at a stop every thread
 * is listed with its stack (identical stacks merged, running ones without), a frame opens its line, and a running
 * program is paused for the dump and goes on with the editor left alone. {@code ThreadDumpProbeFxTest} drives real
 * adapters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class DebugThreadDumpWindowFxTest {

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
        Path root = Files.createDirectories(fx.configDir.resolve("pool")).toRealPath();
        script = Files.writeString(root.resolve("pool.rb"), "x = 1\nx += 1\nputs x\nputs x * 2\nputs x * 3\n");
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
        FxTestSupport.runOnFx(() -> {
            DebugThreadDumpWindow window = debug.threadDumpWindowIfOpen();
            if (window != null) {
                window.hide();
            }
        });
        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));
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

    private EditorBuffer open() throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(script));
        await(
                "pool.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && script.equals(fx.controller.activeBuffer().getPath())));
        return FxTestSupport.callOnFx(fx.controller::activeBuffer);
    }

    /** Starts debugging the script with two idle pool threads (one stack) and a running worker. */
    private FakeDebugAdapter.Session start() throws Exception {
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        session.framePath = script.toString();
        session.idleThreads.addAll(List.of(31, 32));
        session.runningThreads.add(41);
        return session;
    }

    private DebugThreadDumpWindow awaitDump() throws InterruptedException {
        await(
                "the Thread Dump window",
                () -> call(() -> {
                    DebugThreadDumpWindow w = debug.threadDumpWindowIfOpen();
                    return w != null && w.isShowing() && w.dump() != null;
                }));
        return call(debug::threadDumpWindowIfOpen);
    }

    /** The rows' first thread names, in order. */
    private static List<String> rows(DebugThreadDumpWindow w) {
        return w.threadList().getItems().stream()
                .map(g -> ThreadDump.name(g.first()) + "×" + g.threads().size())
                .toList();
    }

    @Test
    void aDumpAtAStopListsEveryThreadWithItsStack() throws Exception {
        EditorBuffer b = open();
        FakeDebugAdapter.Session session = start();
        session.frameLine = 2; // 0-based line 1
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(session.mainThread, "breakpoint"); // the main thread alone: the worker runs on
        await("the stop shown", () -> call(() -> !debug.panel().callStack().isEmpty()));

        run("debug.threadDump");
        DebugThreadDumpWindow w = awaitDump();
        assertEquals(List.of("main×1", "worker-41×1", "pool-31×2"), call(() -> rows(w)), "the pool merged");
        assertEquals(1, call(() -> w.frameList().getItems().size()), "the shown thread's stack, selected first");
        assertEquals(
                "debuggee(pool.rb:2)",
                call(() -> DebugPanelText.frameText(w.frameList().getItems().getFirst())));
        assertTrue(call(() -> w.note().isVisible()), "a running thread: the hint to pause");
        assertEquals(tr("threaddump.runningHint", 1), call(() -> w.note().getText()));

        // The running worker: no stack, said so.
        FxTestSupport.runOnFx(() -> w.threadList().getSelectionModel().select(1));
        assertTrue(call(() -> w.frameList().getItems().isEmpty()));
        String text = call(() -> w.dump().text(true));
        assertTrue(text.contains("\"main\" #" + session.mainThread), text);
        assertTrue(text.contains("    at debuggee(pool.rb:2)"), text);
        assertTrue(text.contains("\"pool-31\", \"pool-32\" (2 threads)"), text);
        assertTrue(text.contains(tr("threaddump.noStack")), text);

        // Merge off: one row a thread. The filter: a thread's name, or a frame.
        FxTestSupport.runOnFx(() -> w.merge().setSelected(false));
        assertEquals(List.of("main×1", "worker-41×1", "pool-31×1", "pool-32×1"), call(() -> rows(w)));
        FxTestSupport.runOnFx(() -> w.filter().setText("POOL-32"));
        assertEquals(List.of("pool-32×1"), call(() -> rows(w)));
        FxTestSupport.runOnFx(() -> w.filter().setText("pool.rb:2"));
        assertEquals(3, call(() -> rows(w).size()), "the frame's text matches, the running worker's nothing");
        FxTestSupport.runOnFx(() -> w.filter().setText("nothing like it"));
        assertTrue(call(() -> w.threadList().getItems().isEmpty()));
        assertTrue(call(() -> w.frameList().getItems().isEmpty()));
        FxTestSupport.runOnFx(() -> w.filter().clear());

        // Enter on a frame opens its line, even after the program went on: the dump is a snapshot.
        FxTestSupport.runOnFx(() -> b.getFocusedArea().moveTo(4, 0));
        FxTestSupport.runOnFx(manager::resume);
        await("running again", () -> call(manager::state) == DapManager.State.RUNNING);
        FxTestSupport.runOnFx(() -> {
            w.threadList().getSelectionModel().selectFirst();
            ListView<DapModels.StackFrameInfo> frames = w.frameList();
            frames.getSelectionModel().selectFirst();
            Event.fireEvent(frames, key(KeyCode.ENTER));
        });
        await("the frame's line", () -> call(() -> b.getFocusedArea().getCurrentParagraph() == 1));
        assertFalse(call(() -> w.takeAgainButton().isDisabled()), "a live session: Take Again");
    }

    @Test
    void aRunningProgramIsPausedForTheDumpAndGoesOn() throws Exception {
        open();
        FakeDebugAdapter.Session session = start();
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);

        run("debug.threadDump");
        await("a pause asked for", () -> !session.pausedThreads.isEmpty());
        assertTrue(
                call(() ->
                        FxTestSupport.<MessageLog>field(FxTestSupport.field(fx.controller, "statusBar"), "messageLog")
                                .entries()
                                .stream()
                                .anyMatch(e -> e.text().equals(tr("status.debug.threadDumpPausing")))),
                "said in the status bar");
        session.frameLine = 4;
        session.stop(session.mainThread, "pause", true);

        DebugThreadDumpWindow w = awaitDump();
        assertTrue(call(() -> w.dump().pausedForIt()));
        assertEquals(tr("threaddump.pausedForIt"), call(() -> w.note().getText()));
        assertTrue(
                call(() -> w.dump().threads().stream()
                        .allMatch(t -> w.dump().stateText(t).equals(tr("debugpanel.thread.running")))),
                "every thread was running");
        await("the program goes on", () -> session.requests.contains("continue"));
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        assertTrue(call(() -> debug.panel().callStack().isEmpty()), "the dump's stop is not shown");

        // The session ends: nothing to take again.
        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));
        assertTrue(call(() -> w.takeAgainButton().isDisabled()));
    }

    @Test
    void theThreadsViewAndTheCallStackOfferIt() throws Exception {
        open();
        FakeDebugAdapter.Session session = start();
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(session.mainThread, "breakpoint", true);
        await("the stop shown", () -> call(() -> !debug.panel().callStack().isEmpty()));

        run("debug.threads");
        await(
                "threads listed",
                () -> call(() -> !debug.panel().threadList().getItems().isEmpty()));
        MenuItem dump = call(() -> {
            debug.panel().threadList().getSelectionModel().selectFirst();
            ContextMenu menu = debug.panel().threadList().getContextMenu();
            menu.getOnShowing().handle(null);
            return menu.getItems().stream()
                    .filter(i -> tr("debugpanel.threadDump").equals(i.getText()))
                    .findFirst()
                    .orElse(null);
        });
        assertNotNull(dump, "Thread Dump in the Threads view's menu");
        FxTestSupport.runOnFx(dump::fire);
        DebugThreadDumpWindow w = awaitDump();
        assertEquals(4, call(() -> w.dump().threads().size()));
    }

    @Test
    void withoutASessionItSaysSo() throws Exception {
        run("debug.threadDump");
        // ifDebug may refuse first (debugging off); with it on, the coordinator answers.
        Thread.sleep(200);
        DebugThreadDumpWindow w = call(debug::threadDumpWindowIfOpen);
        assertTrue(w == null || !call(w::isShowing), "no window without a session");
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }
}
