package com.editora.ui;

import java.util.concurrent.TimeUnit;

import com.editora.command.CommandRegistry;
import com.editora.command.KeyDispatcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Terminal tool window in a real window: {@code tool.terminal} opens it with a shell ready, a second
 * {@code tool.terminal} hides it, {@code terminal.new} adds a tab, and closing the window ends every shell.
 */
@Tag("fx")
class TerminalToolWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theTerminalCommandsOpenHideAndAddShells() throws Exception {
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            TerminalCoordinator terminals = fx.controller.terminalCoordinator;
            ToolWindow window = fx.controller.terminalToolWindow;
            ToolWindowManager toolWindows = FxTestSupport.field(fx.controller, "toolWindows");

            FxTestSupport.runOnFx(() -> registry.run("tool.terminal"));
            assertTrue(FxTestSupport.callOnFx(() -> toolWindows.isOpen(window)));
            assertEquals(1, (int) FxTestSupport.callOnFx(() -> terminals.panel().terminalCount()));
            assertTrue(
                    FxTestSupport.callOnFx(
                            () -> terminals.panel().getProperties().get(KeyDispatcher.SHELL_KEYS) != null),
                    "a focused terminal owns the keyboard");

            FxTestSupport.runOnFx(() -> registry.run("terminal.new"));
            assertEquals(2, (int) FxTestSupport.callOnFx(() -> terminals.panel().terminalCount()));

            FxTestSupport.runOnFx(() -> registry.run("tool.terminal"));
            assertFalse(FxTestSupport.callOnFx(() -> toolWindows.isOpen(window)), "the second toggle hides it");
            assertEquals(
                    2,
                    (int) FxTestSupport.callOnFx(() -> terminals.panel().terminalCount()),
                    "hiding the window keeps its shells");

            var view = FxTestSupport.callOnFx(() -> terminals.panel().activeView());
            long pid = FxTestSupport.callOnFx(() -> view.getSession().pid());
            fx.dispose();
            if (pid > 0) {
                var process = ProcessHandle.of(pid);
                if (process.isPresent()) {
                    process.get().onExit().get(10, TimeUnit.SECONDS);
                }
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the shell ended");
            }
        }
    }
}
