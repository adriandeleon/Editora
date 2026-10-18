package com.editora.ui;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.terminal.TerminalView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the keyboard goes around the Terminal tool window: into the terminal on every path that shows it, back to
 * it from the editor with the toggle, to "New Terminal" when the last shell ends, and into a floating terminal,
 * whose window's keys still work.
 */
@Tag("fx")
class TerminalFocusFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theToggleFocusesAnOpenTerminalAndHidesOnlyAFocusedOne() throws Exception {
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            ToolWindowManager toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
            ToolWindow window = fx.controller.terminalToolWindow;
            fx.controller.config.getSettings().setTerminalShell("/bin/sh");
            FxTestSupport.runOnFx(() -> fx.controller.terminalCoordinator.applySettings());

            FxTestSupport.runOnFx(() -> registry.run("tool.terminal"));
            await(() -> focusOwner(fx) instanceof TerminalView, "the terminal has the keyboard");

            FxTestSupport.runOnFx(toolWindows::focusEditor);
            await(() -> !(focusOwner(fx) instanceof TerminalView), "the editor has it");

            FxTestSupport.runOnFx(() -> registry.run("tool.terminal"));
            assertTrue(FxTestSupport.callOnFx(() -> toolWindows.isOpen(window)), "an unfocused terminal stays open");
            await(() -> focusOwner(fx) instanceof TerminalView, "and gets the keyboard back");

            FxTestSupport.runOnFx(() -> registry.run("tool.terminal"));
            assertFalse(FxTestSupport.callOnFx(() -> toolWindows.isOpen(window)), "a focused one is hidden");
        }
    }

    @Test
    void theStripePathFocusesTheTerminalAndTheLastShellsExitFocusesNewTerminal() throws Exception {
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            ToolWindowManager toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
            ToolWindow window = fx.controller.terminalToolWindow;
            TerminalCoordinator terminals = fx.controller.terminalCoordinator;
            fx.controller.config.getSettings().setTerminalShell("/bin/sh");
            FxTestSupport.runOnFx(terminals::applySettings);
            assertTrue(FxTestSupport.callOnFx(() -> terminals.panel() instanceof ToolWindowContent));

            FxTestSupport.runOnFx(() -> toolWindows.open(window)); // the stripe button
            await(() -> call(() -> terminals.panel().terminalCount() == 1), "a shell");
            await(() -> focusOwner(fx) instanceof TerminalView, "the shell has the keyboard, not the panel");

            FxTestSupport.runOnFx(() -> terminals.panel().activeView().getSession().sendString("exit\n"));
            await(() -> call(() -> terminals.panel().terminalCount() == 0), "the shell's exit");
            await(
                    () -> focusOwner(fx) instanceof javafx.scene.control.Button b
                            && b.getText().equals(com.editora.i18n.Messages.tr("terminal.new")),
                    "New Terminal has the keyboard, not a toolbar button: " + focusOwner(fx));
        }
    }

    @Test
    void aFloatingTerminalGetsTheKeyboardAndTheWindowsKeys() throws Exception {
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            KeymapManager keymap = FxTestSupport.field(fx.controller, "keymap");
            ToolWindowManager toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
            ToolWindow window = fx.controller.terminalToolWindow;
            TerminalCoordinator terminals = fx.controller.terminalCoordinator;
            fx.controller.config.getSettings().setTerminalShell("/bin/sh");
            FxTestSupport.runOnFx(terminals::applySettings);
            FxTestSupport.runOnFx(() -> keymap.applyOverrides(Map.of("C-S-f11", "tool.terminal")));

            FxTestSupport.runOnFx(() -> registry.run("tool.terminal"));
            await(() -> call(() -> terminals.panel().terminalCount() == 1), "a shell");
            FxTestSupport.runOnFx(() -> toolWindows.toggleFloating(window));
            assertTrue(FxTestSupport.callOnFx(() -> toolWindows.isFloating(window)));
            await(
                    () -> call(() -> {
                        var scene = terminals.panel().getScene();
                        return scene != null && scene.getFocusOwner() instanceof TerminalView;
                    }),
                    "the floating terminal has the keyboard");

            // The window's own chord, typed in the floating stage, reaches the window's command.
            FxTestSupport.runOnFx(() -> Event.fireEvent(
                    terminals.panel().activeView(),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F11, true, true, false, false)));
            await(() -> call(() -> !toolWindows.isOpen(window)), "tool.terminal from inside the floating terminal");
        }
    }

    @Test
    void closingTheWindowAsksWhenATerminalIsRunningAProgram() throws Exception {
        try (WindowRig w = new WindowRig()) {
            TerminalCoordinator terminals = w.controller.terminalCoordinator;
            w.controller.config.getSettings().setTerminalShell("/bin/sh");
            FxTestSupport.runOnFx(terminals::applySettings);
            FxTestSupport.runOnFx(() -> w.registry.run("tool.terminal"));
            await(() -> call(() -> terminals.panel().activeView() != null), "a shell");
            long shell = FxTestSupport.callOnFx(() -> terminals.panel().activeView().getSession().pid());
            FxTestSupport.runOnFx(() -> terminals.panel().activeView().getSession().sendString("sleep 30\n"));
            await(() -> com.editora.terminal.RunningProgram.in(shell).contains("sleep"), "sleep running");

            java.util.concurrent.CountDownLatch cancel =
                    w.answer(com.editora.i18n.Messages.tr("terminal.closeWindow.header"), "Cancel");
            FxTestSupport.runOnFx(() -> javafx.event.Event.fireEvent(
                    w.stage(), new javafx.stage.WindowEvent(w.stage(), javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)));
            w.async.await(cancel, "the busy-terminal question");
            assertTrue(FxTestSupport.callOnFx(() -> w.stage().isShowing()), "Cancel keeps the window");
            assertTrue(com.editora.terminal.RunningProgram.in(shell).contains("sleep"), "and the program");
        }
    }

    private static Node focusOwner(FxWindowFixture fx) {
        try {
            javafx.stage.Stage stage = FxTestSupport.field(fx.controller, "stage");
            return FxTestSupport.callOnFx(() -> stage.getScene().getFocusOwner());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean call(Callable<Boolean> c) {
        try {
            return FxTestSupport.callOnFx(c);
        } catch (Exception e) {
            return false;
        }
    }

    private static void await(java.util.function.BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
