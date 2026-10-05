package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.scene.control.TextField;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapClient;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The debug flows of a real window against a scripted adapter: what a Step, a Restart and a narrowed buffer
 * do to the session, the panel and what the adapter is told.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DebugSessionFlowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setPythonDebugEnabled(true);
            // debugpy "installed", with an interpreter that cannot be spawned: a launch gets as far as the
            // coordinator can take it and then fails harmlessly instead of starting a real debuggee.
            set(manager, "enabled", true);
            set(manager, "pythonEnabled", true);
            set(manager, "pythonAvailable", true);
            set(manager, "pythonCommand", fx.configDir.resolve("no-such-python").toString());
        });
    }

    @AfterEach
    void endSession() throws Exception {
        FxTestSupport.runOnFx(manager::stop);
        FxTestSupport.drainFx();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static void set(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private EditorBuffer open(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "fileWorkflows"), "openPath", new Class[] {Path.class}, file));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            FxTestSupport.drainFx();
            EditorBuffer b = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
            if (b != null
                    && file.equals(b.getPath())
                    && !FxTestSupport.callOnFx(b::getContent).isEmpty()) {
                return b;
            }
            assertTrue(System.nanoTime() < deadline, "the file never opened: " + file);
            Thread.sleep(20);
        }
    }

    /** Puts the window's own manager into a live session with {@code adapter}, as after a launch of {@code file}. */
    private FakeDebugAdapter.Session connect(FakeDebugAdapter adapter, Path file) throws Exception {
        long epoch =
                (Long) FxTestSupport.callOnFx(() -> FxTestSupport.call(manager, "beginSession", new Class<?>[] {}));
        DapClient client = new DapClient(
                (DapClient.Host) FxTestSupport.call(manager, "sessionHost", new Class<?>[] {long.class}, epoch));
        client.connect(adapter.port(), "python").get(10, TimeUnit.SECONDS);
        assertTrue((Boolean) FxTestSupport.call(
                manager, "publishClient", new Class<?>[] {long.class, DapClient.class}, epoch, client));
        FxTestSupport.runOnFx(() -> {
            set(manager, "debugFile", file);
            FxTestSupport.call(manager, "setState", new Class<?>[] {DapManager.State.class}, DapManager.State.RUNNING);
        });
        return adapter.awaitSession();
    }

    private void awaitState(DapManager.State wanted) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (FxTestSupport.callOnFx(manager::state) != wanted) {
            assertTrue(System.nanoTime() < deadline, "the session never became " + wanted);
            Thread.sleep(10);
        }
        FxTestSupport.drainFx();
    }

    /**
     * F5 is Continue in the VS Code keymaps. Pressed while a step is in flight, with a file other than the
     * launched one in front, it used to take the "retarget" branch — the step reads RUNNING — and killed the
     * session instead of continuing it.
     */
    @Test
    void startDuringAStepContinuesTheSessionInsteadOfReplacingIt() throws Exception {
        Path launch = fx.configDir.resolve("launch.py");
        Path util = fx.configDir.resolve("util.py");
        Files.writeString(launch, "import util\nutil.work()\n");
        Files.writeString(util, "def work():\n    x = 1\n    x += 1\n    return x\n");
        open(util);
        assertTrue(FxTestSupport.callOnFx(() -> debug.debugEffectiveFor("python")));
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, launch);
            session.framePath = util.toString();
            session.frameLine = 2;
            session.stop(7, "breakpoint");
            awaitState(DapManager.State.SUSPENDED);

            FxTestSupport.runOnFx(manager::stepOver);
            session.awaitRequest("next");
            awaitState(DapManager.State.RUNNING);
            FxTestSupport.runOnFx(debug::debugStart);

            session.awaitRequest("continue");
            assertTrue(FxTestSupport.callOnFx(manager::isActive), "the session must survive");
            assertFalse(session.requests.contains("disconnect"), session.requests.toString());
        }
    }

    /**
     * The Evaluate field must stay usable through a Step: disabling it made JavaFX move focus out of the
     * panel, the next stop handed focus to the editor, and the expression being typed edited the source.
     */
    @Test
    void theEvaluateFieldStaysEnabledWhileTheProgramRunsButOnlyEvaluatesWhenPaused() throws Exception {
        java.util.List<String> evaluated = new java.util.ArrayList<>();
        DebugPanel panel = FxTestSupport.callOnFx(() -> new DebugPanel(new DebugPanel.Actions() {
            @Override
            public void start() {}

            @Override
            public void pause() {}

            @Override
            public void runToCursor() {}

            @Override
            public void selectThread(int threadId) {}

            @Override
            public void stepOver() {}

            @Override
            public void stepInto() {}

            @Override
            public void stepOut() {}

            @Override
            public void stop() {}

            @Override
            public void restart() {}

            @Override
            public void selectFrame(DapModels.StackFrameInfo frame) {}

            @Override
            public void loadChildren(int ref, Consumer<List<DapModels.VariableInfo>> cb) {}

            @Override
            public void evaluate(String expr, int frameId, Consumer<String> cb) {
                evaluated.add(expr);
            }

            @Override
            public void evaluateWatch(String expr, int frameId, Consumer<DapModels.EvalResult> cb) {}

            @Override
            public void setVariable(int parentRef, String name, String value, Consumer<String> cb) {}
        }));
        TextField eval = FxTestSupport.field(panel, "evalInput");
        FxTestSupport.runOnFx(() -> {
            panel.setState(DapManager.State.SUSPENDED);
            panel.setState(DapManager.State.RUNNING); // a step, or Continue
            assertFalse(eval.isDisabled(), "a disabled focus owner loses focus to the next control");
            eval.setText("x + 1");
            eval.fireEvent(new javafx.event.ActionEvent());
            assertEquals(List.of(), evaluated, "there is no frame to evaluate against while running");
            assertEquals("x + 1", eval.getText(), "what was typed ahead is kept for the next stop");
            panel.setState(DapManager.State.SUSPENDED);
            eval.fireEvent(new javafx.event.ActionEvent());
            assertEquals(List.of("x + 1"), evaluated);
            panel.setState(DapManager.State.INACTIVE);
            assertTrue(eval.isDisabled());
        });
    }

    /**
     * Restart must repeat the start, not just the adapter launch: the edited file is saved first. It used to
     * re-run the captured launch, debugging the previous code against the edited buffer's breakpoint lines.
     */
    @Test
    void restartSavesTheEditedFileLikeAStart() throws Exception {
        Path app = fx.configDir.resolve("app.py");
        Files.writeString(app, "msg = 'v1'\nprint(msg)\n");
        EditorBuffer b = open(app);
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");

        FxTestSupport.runOnFx(() -> registry.run("debug.start"));
        awaitLaunchAttempt();
        FxTestSupport.runOnFx(() -> b.getArea().replaceText(7, 9, "v2"));
        assertTrue(FxTestSupport.callOnFx(b::isDirty));

        FxTestSupport.runOnFx(() -> registry.run("debug.restart"));
        awaitLaunchAttempt();

        assertEquals("msg = 'v2'\nprint(msg)\n", Files.readString(app), "the restarted program is the edited one");
        assertFalse(FxTestSupport.callOnFx(b::isDirty));
    }

    /** The launch is handed to DapManager after an off-thread breakpoint scan; wait for that hand-over. */
    private void awaitLaunchAttempt() throws Exception {
        Thread.sleep(300);
        FxTestSupport.drainFx();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (FxTestSupport.callOnFx(manager::state) == DapManager.State.STARTING && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        FxTestSupport.drainFx();
    }

    /**
     * While a buffer is narrowed its breakpoint manager holds only the region's breakpoints, with
     * region-relative lines. Those went to the adapter as file lines: the wrong lines were armed and every
     * breakpoint outside the region was dropped.
     */
    @Test
    void aNarrowedBufferStillReportsItsBreakpointsInFileLines() throws Exception {
        Path file = fx.configDir.resolve("narrow.py");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            text.append("line").append(i).append("()\n");
        }
        Files.writeString(file, text);
        EditorBuffer b = open(file);
        FxTestSupport.runOnFx(() -> {
            b.toggleBreakpoint(4);
            b.toggleBreakpoint(24);
            var area = b.getArea();
            assertTrue(b.narrowTo(area.getAbsolutePosition(20, 0), area.getAbsolutePosition(29, 0)));
        });
        assertEquals(List.of(4, 24), sentLines(file));

        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(1)); // the region's second line: file line 21
        assertEquals(List.of(4, 21, 24), sentLines(file));

        // A stop at file line 24 is the region's line 4, and Run to Cursor from there names file line 24.
        DapModels.StackFrameInfo frame = new DapModels.StackFrameInfo(1, "f", file, 24, 0);
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(debug, "highlightFrame", new Class[] {DapModels.StackFrameInfo.class}, frame));
        FxTestSupport.drainFx();
        assertEquals(
                List.of("exec-line"),
                FxTestSupport.callOnFx(
                        () -> List.copyOf(b.getArea().getParagraph(4).getParagraphStyle())));
        assertEquals(24, (int) FxTestSupport.callOnFx(
                () -> (Integer) FxTestSupport.call(debug, "fileLineAtCaret", new Class[] {EditorBuffer.class}, b)));

        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(debug, "clearExecHighlight", new Class[] {});
            b.widen();
        });
        assertEquals(List.of(4, 21, 24), sentLines(file));
    }

    @SuppressWarnings("unchecked")
    private List<Integer> sentLines(Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            var all = (List<DapModels.FileBreakpoints>) FxTestSupport.call(debug, "collectBreakpoints", new Class[] {});
            return all.stream()
                    .filter(fb -> fb.file().equals(file))
                    .flatMap(fb -> fb.breakpoints().stream())
                    .map(DapModels.LineBreakpoint::line)
                    .toList();
        });
    }
}
