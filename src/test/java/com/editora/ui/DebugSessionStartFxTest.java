package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.editora.dap.DapManager;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a debug start meets the session before it, in a real window: Stop cancels a start still reading the
 * breakpoints of closed files (review D104), the main-class picker of a launch that was replaced or stopped
 * closes, telling that launch it was dismissed (review A106), and a launch still being built is live for every
 * way to start, worded as starting (review 3, C202–C204, A209).
 */
@Tag("fx")
class DebugSessionStartFxTest {

    private FxWindowFixture fx;
    private DebugCoordinator debug;
    private DapManager dap;

    @BeforeEach
    void setUp() throws Exception {
        fx = FxWindowFixture.create();
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        dap = FxTestSupport.field(fx.controller, "dapManager");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private void withClosedBreakpoints(Runnable then) {
        FxTestSupport.call(debug, "withClosedBreakpoints", new Class<?>[] {Runnable.class}, then);
    }

    /** Waits long enough for the closed-file read (none here) to post its result. */
    private static void settle() throws Exception {
        for (int i = 0; i < 10; i++) {
            Thread.sleep(30);
            FxTestSupport.drainFx();
        }
    }

    @Test
    void aStartStillReadingClosedFilesBreakpointsGoesAheadUnlessStopped() throws Exception {
        List<String> started = new ArrayList<>();
        FxTestSupport.runOnFx(() -> withClosedBreakpoints(() -> started.add("plain")));
        settle();
        assertEquals(List.of("plain"), started, "with nothing in the way the start is made");

        FxTestSupport.runOnFx(() -> {
            withClosedBreakpoints(() -> started.add("stopped"));
            debug.stop(); // before the read has come back
        });
        settle();
        assertEquals(List.of("plain"), started, "Stop cancelled the start");

        FxTestSupport.runOnFx(() -> {
            withClosedBreakpoints(() -> started.add("replaced"));
            withClosedBreakpoints(() -> started.add("newer"));
        });
        settle();
        assertEquals(List.of("plain", "newer"), started, "a newer start replaces one still reading");
    }

    @SuppressWarnings("unchecked")
    private void pick(List<DapManager.MainClassOption> options, Consumer<DapManager.MainClassOption> chosen) {
        FxTestSupport.call(debug, "pickMainClass", new Class<?>[] {List.class, Consumer.class}, options, chosen);
    }

    private void setState(DapManager.State state) {
        FxTestSupport.call(dap, "setState", new Class<?>[] {DapManager.State.class}, state);
    }

    @Test
    void theMainClassPickerOfALaunchThatWasReplacedOrStoppedCloses() throws Exception {
        List<DapManager.MainClassOption> options = List.of(
                new DapManager.MainClassOption("a.One", "p", null), new DapManager.MainClassOption("a.Two", "p", null));
        for (DapManager.State next : List.of(DapManager.State.STARTING, DapManager.State.INACTIVE)) {
            List<Object> answers = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                setState(DapManager.State.STARTING);
                pick(options, answers::add);
                assertNotNull(debug.openMainClassPicker(), "the picker is open");
                setState(next); // a new launch begins, or this one is stopped
                assertNull(debug.openMainClassPicker(), "the picker closed on " + next);
            });
            FxTestSupport.drainFx();
            assertEquals(java.util.Arrays.asList((Object) null), answers, "the launch was told: no choice");
            FxTestSupport.runOnFx(() -> setState(DapManager.State.INACTIVE));
        }
    }

    @Test
    void escOnTheMainClassPickerEndsTheLaunchInsteadOfLeavingItStarting() throws Exception {
        List<Object> answers = new ArrayList<>();
        FxTestSupport.runOnFx(() -> pick(
                List.of(
                        new DapManager.MainClassOption("a.One", "p", null),
                        new DapManager.MainClassOption("a.Two", "p", null)),
                answers::add));
        FxTestSupport.runOnFx(() -> {
            QuickOpen<DapManager.MainClassOption> picker = debug.openMainClassPicker();
            assertNotNull(picker);
            picker.hide(); // what Esc does
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (answers.isEmpty() && System.nanoTime() < deadline) {
            FxTestSupport.drainFx();
            Thread.sleep(20);
        }
        assertEquals(java.util.Arrays.asList((Object) null), answers);
        assertFalse(FxTestSupport.callOnFx(() -> debug.openMainClassPicker() != null));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * Review 3, C203 / C204 / C202: while the build before a launch runs, the window says "Building…" with Stop on,
     * F5 in the same file says it is still starting (not "is running: pause or stop it"), and the editor menu's
     * Debug File in another file asks before replacing it — and hides itself in the file being built.
     */
    @Test
    void aLaunchBeingBuiltIsLiveForF5AndDebugFileAndSaysItIsStarting() throws Exception {
        Path root = fx.useProject();
        Path a = Files.writeString(root.resolve("Alpha.java"), "public class Alpha {}\n");
        Path b = Files.writeString(root.resolve("Beta.java"), "public class Beta {}\n");
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(b, 0));
        EditorBuffer beta = FxTestSupport.callOnFx(fx.controller::activeBuffer);
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(a, 0));
        EditorBuffer alpha = FxTestSupport.callOnFx(fx.controller::activeBuffer);
        List<String> asked = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            debug.confirmRetarget = q -> {
                asked.add(q);
                return false;
            };
            FxTestSupport.call(debug, "nameSession", new Class<?>[] {String.class}, "Alpha.java");
            try {
                set(debug, "pendingBuild", new Object()); // what looseBuildThenLaunch sets, before its first step
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            FxTestSupport.call(debug, "showPreparing", new Class<?>[] {Path.class}, a);
        });
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = debug.panel();
            javafx.scene.control.Label status = FxTestSupport.field(panel, "status");
            javafx.scene.control.Button stop = FxTestSupport.field(panel, "stop");
            assertTrue(status.getText().startsWith(tr("debugpanel.state.building")), status.getText());
            assertFalse(stop.isDisabled(), "Stop cancels the build");
            assertTrue(debug.sessionLive());

            debug.debugStart(); // F5 again in Alpha
        });
        assertEquals(List.of(), asked, "the same file is not asked about");
        assertEquals(tr("status.debug.alreadyStarting", "Alpha.java"), SaveDecisionsFxTest.lastMessage(fx));
        FxTestSupport.runOnFx(() -> {
            assertTrue(
                    debug.editorMenuItems(alpha).stream()
                            .noneMatch(i -> tr("editmenu.debugFile").equals(i.getText())),
                    "no second launch of the file being built");
            debug.debugFile(beta); // the editor menu's Debug File in Beta
            assertEquals(List.of(tr("dialog.debug.retargetStarting", "Alpha.java", "Beta.java")), asked);
            assertTrue(debug.sessionLive(), "kept: the answer was no");
            debug.stop();
            assertFalse(debug.sessionLive(), "Stop cancelled the build");
        });
    }
}
