package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.dap.FakeDebugAdapter.Session.frame;
import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Parallel Stacks through the window, against a {@link FakeDebugAdapter} reached as Ruby's rdbg is: at a stop the
 * threads' stacks are one tree of boxes (two pool threads sleeping on two lines share a box, the main thread's work is
 * another, both above the frame they all start in), a frame opens its line, the menus offer it, and a running program
 * is paused for it and goes on. {@code ParallelStacksProbeFxTest} drives real adapters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DebugParallelStacksWindowFxTest {

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
        script = Files.writeString(root.resolve("pool.rb"), "def run\n  work\nend\nsleep 1\nsleep 2\n");
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
            DebugParallelStacksWindow window = debug.parallelStacksWindowIfOpen();
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

    /**
     * Starts the script: the main thread works in run (line 2), two pool threads sleep in run on lines 4 and 5, and a
     * worker runs on.
     */
    private FakeDebugAdapter.Session start() throws Exception {
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        String path = script.toString();
        session.threadFrames.put(session.mainThread, List.of(frame(1, "work", path, 2), frame(2, "run", path, 1)));
        session.threadFrames.put(31, List.of(frame(3, "sleep", path, 4), frame(4, "run", path, 1)));
        session.threadFrames.put(32, List.of(frame(5, "sleep", path, 5), frame(6, "run", path, 1)));
        session.idleThreads.addAll(List.of(31, 32));
        session.runningThreads.add(41);
        return session;
    }

    private DebugParallelStacksWindow awaitWindow() throws InterruptedException {
        await(
                "the Parallel Stacks window",
                () -> call(() -> {
                    DebugParallelStacksWindow w = debug.parallelStacksWindowIfOpen();
                    return w != null && w.isShowing() && w.stacks() != null;
                }));
        return call(debug::parallelStacksWindowIfOpen);
    }

    private static List<String> texts(DebugParallelStacksWindow w, String styleClass) {
        return w.canvas().lookupAll("." + styleClass).stream()
                .map(n -> ((Label) n).getText())
                .toList();
    }

    private static Label row(DebugParallelStacksWindow w, String text) {
        for (Node n : w.canvas().lookupAll(".parallel-stacks-frame")) {
            if (n instanceof Label l && text.equals(l.getText())) {
                return l;
            }
        }
        throw new AssertionError("no row " + text);
    }

    @Test
    void theStacksAtAStopAreOneTree() throws Exception {
        EditorBuffer b = open();
        FakeDebugAdapter.Session session = start();
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(session.mainThread, "breakpoint");
        await("the stop shown", () -> call(() -> !debug.panel().callStack().isEmpty()));

        run("debug.parallelStacks");
        DebugParallelStacksWindow w = awaitWindow();
        assertEquals(
                List.of("3 threads: main, …", "main", "2 threads: pool-31, …"),
                call(() -> texts(w, "parallel-stacks-heading")),
                "a box for the frame they start in, one each way they went");
        assertEquals(
                List.of("run:1", "work:2", tr("parallelstacks.severalLines", "sleep")),
                call(() -> texts(w, "parallel-stacks-frame")));
        assertEquals(2, call(() -> w.canvas().lookupAll(".parallel-stacks-edge").size()));
        assertEquals(tr("parallelstacks.withoutStack", 1), call(() -> w.note().getText()), "the running worker");
        assertTrue(call(() -> row(w, "work:2").getStyleClass().contains("parallel-stacks-current")));
        assertFalse(call(() -> row(w, "run:1").getStyleClass().contains("parallel-stacks-current")));
        assertEquals(
                2,
                call(() -> w.canvas()
                        .lookupAll(".parallel-stacks-box.parallel-stacks-shown")
                        .size()),
                "the shown thread's way: the root and main's box");
        assertTrue(call(() -> w.stacks().text()).contains("    sleep (several lines)"));

        // Enter on a frame opens its line (the first thread's, when the threads are on several).
        FxTestSupport.runOnFx(() -> b.getFocusedArea().moveTo(0, 0));
        FxTestSupport.runOnFx(() -> Event.fireEvent(
                row(w, tr("parallelstacks.severalLines", "sleep")),
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)));
        await("the frame's line", () -> call(() -> b.getFocusedArea().getCurrentParagraph() == 3));
        assertFalse(call(() -> w.takeAgainButton().isDisabled()));
    }

    @Test
    void aRunningProgramIsPausedForItAndGoesOn() throws Exception {
        open();
        FakeDebugAdapter.Session session = start();
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);

        run("debug.parallelStacks");
        await("a pause asked for", () -> !session.pausedThreads.isEmpty());
        session.stop(session.mainThread, "pause", true);
        DebugParallelStacksWindow w = awaitWindow();
        assertTrue(call(() -> w.stacks().dump().pausedForIt()));
        await("the program goes on", () -> session.requests.contains("continue"));
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        assertTrue(call(() -> debug.threadDumpWindowIfOpen() == null), "Parallel Stacks, not Thread Dump");

        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));
        assertTrue(call(() -> w.takeAgainButton().isDisabled()), "nothing to take again");
    }

    @Test
    void theThreadsViewsMenuOffersIt() throws Exception {
        open();
        FakeDebugAdapter.Session session = start();
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(session.mainThread, "breakpoint", true);
        await("the stop shown", () -> call(() -> !debug.panel().callStack().isEmpty()));
        run("debug.threads");
        await(
                "threads listed",
                () -> call(() -> !debug.panel().threadList().getItems().isEmpty()));
        MenuItem item = call(() -> {
            debug.panel().threadList().getSelectionModel().selectFirst();
            ContextMenu menu = debug.panel().threadList().getContextMenu();
            menu.getOnShowing().handle(null);
            return menu.getItems().stream()
                    .filter(i -> tr("debugpanel.parallelStacks").equals(i.getText()))
                    .findFirst()
                    .orElse(null);
        });
        assertTrue(item != null, "Parallel Stacks beside Thread Dump");
        FxTestSupport.runOnFx(item::fire);
        DebugParallelStacksWindow w = awaitWindow();
        assertEquals(3, call(() -> w.stacks().roots().getFirst().threads().size()));
    }
}
