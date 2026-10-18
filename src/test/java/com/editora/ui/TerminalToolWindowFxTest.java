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
            await(() -> FxTestSupport.callOnFx(() -> terminals.panel().hasFocusWithin()), "the new shell's focus");

            FxTestSupport.runOnFx(() -> registry.run("tool.terminal"));
            assertFalse(FxTestSupport.callOnFx(() -> toolWindows.isOpen(window)), "the second toggle hides it");
            assertEquals(
                    2,
                    (int) FxTestSupport.callOnFx(() -> terminals.panel().terminalCount()),
                    "hiding the window keeps its shells");

            // Open Terminal Here on a folder: a new tab whose shell starts there.
            java.nio.file.Path folder =
                    java.nio.file.Files.createTempDirectory("editora-open-here").toRealPath();
            try {
                FxTestSupport.runOnFx(() -> fx.controller.openTerminalAt(folder, true, true));
                assertTrue(FxTestSupport.callOnFx(() -> toolWindows.isOpen(window)), "it shows the tool window");
                assertEquals(
                        3, (int) FxTestSupport.callOnFx(() -> terminals.panel().terminalCount()));
                await(
                        () -> FxTestSupport.callOnFx(() -> terminals.panel().activeView() != null),
                        "the shell started (off the FX thread)");
                long shell = FxTestSupport.callOnFx(
                        () -> terminals.panel().activeView().getSession().pid());
                if (com.editora.terminal.ProcessCwd.isSupported()) {
                    assertEquals(
                            folder.toString(),
                            com.editora.terminal.ProcessCwd.of(shell).orElse(""));
                }
            } finally {
                java.nio.file.Files.deleteIfExists(folder);
            }

            // A file:line:col clicked in a terminal opens in the editor at that place (1-based on the wire).
            java.nio.file.Path source = java.nio.file.Files.createTempFile("editora-link", ".txt");
            try {
                java.nio.file.Files.writeString(source, "one\ntwo\nthree words here\n");
                FxTestSupport.runOnFx(() -> terminals.panel().linkActions().openFile(source, 3, 7));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int[] at = null;
                while (System.nanoTime() < deadline) {
                    at = FxTestSupport.callOnFx(() -> {
                        com.editora.editor.EditorBuffer b = fx.controller.activeBuffer();
                        return b == null || !source.equals(b.getPath())
                                ? null
                                : new int[] {
                                    b.getArea().getCurrentParagraph(),
                                    b.getArea().getCaretColumn()
                                };
                    });
                    if (at != null && at[0] == 2) break;
                    Thread.sleep(20);
                }
                assertTrue(at != null, "the file opened");
                assertEquals(2, at[0], "line 3");
                assertEquals(6, at[1], "column 7");
            } finally {
                java.nio.file.Files.deleteIfExists(source);
            }

            // Every tab's shell, not only the active one's, ends with the window.
            await(() -> FxTestSupport.callOnFx(() -> terminals.panel().viewsForTest().size() == 3), "all three shells");
            java.util.List<Long> pids = FxTestSupport.callOnFx(() -> terminals.panel().viewsForTest().stream()
                    .map(v -> v.getSession().pid())
                    .toList());
            assertEquals(3, pids.size());
            fx.dispose();
            for (long pid : pids) {
                var process = ProcessHandle.of(pid);
                if (process.isPresent()) {
                    process.get().onExit().get(10, TimeUnit.SECONDS);
                }
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "shell " + pid + " ended");
            }
        }
    }

    private static void await(java.util.concurrent.Callable<Boolean> condition, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
