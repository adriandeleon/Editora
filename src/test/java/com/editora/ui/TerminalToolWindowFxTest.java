package com.editora.ui;

import java.util.concurrent.TimeUnit;

import com.editora.command.CommandRegistry;
import com.editora.command.KeyDispatcher;
import com.editora.terminal.RunningProgram;
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
            await(
                    () -> FxTestSupport.callOnFx(
                            () -> terminals.panel().viewsForTest().size() == 3),
                    "all three shells");
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

    @Test
    void moveToEditorAndBackKeepsTheShellAndClosingTheWindowEndsIt() throws Exception {
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            TerminalCoordinator terminals = fx.controller.terminalCoordinator;
            ToolWindow window = fx.controller.terminalToolWindow;
            ToolWindowManager toolWindows = FxTestSupport.field(fx.controller, "toolWindows");

            FxTestSupport.runOnFx(() -> registry.run("terminal.new"));
            await(() -> FxTestSupport.callOnFx(() -> terminals.panel().activeView() != null), "the shell");
            long pid = FxTestSupport.callOnFx(
                    () -> terminals.panel().activeView().getSession().pid());
            awaitShellAtItsPrompt(terminals); // its tab is closed below

            FxTestSupport.runOnFx(() -> registry.run("terminal.moveToEditor"));
            TerminalEditorTab shown = FxTestSupport.callOnFx(() -> fx.controller.editorArea.selectedTab() != null
                            && fx.controller.editorArea.selectedTab().getUserData() instanceof TerminalEditorTab t
                    ? t
                    : null);
            assertTrue(shown != null, "the terminal is the selected editor tab");
            assertFalse(FxTestSupport.callOnFx(() -> toolWindows.isOpen(window)), "the emptied tool window hid");
            assertEquals(0, (int) FxTestSupport.callOnFx(() -> terminals.panel().terminalCount()));
            assertTrue(FxTestSupport.callOnFx(terminals::hasTerminals), "the terminal commands stay enabled");
            assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the same shell runs on");

            FxTestSupport.runOnFx(() -> registry.run("terminal.moveToToolWindow"));
            assertTrue(FxTestSupport.callOnFx(() -> toolWindows.isOpen(window)), "back in the tool window");
            assertEquals(1, (int) FxTestSupport.callOnFx(() -> terminals.panel().terminalCount()), "no second shell");
            assertEquals(pid, (long) FxTestSupport.callOnFx(
                    () -> terminals.panel().activeView().getSession().pid()));
            assertFalse(
                    FxTestSupport.callOnFx(() -> fx.controller.editorArea.tabs().stream()
                            .anyMatch(t -> t.getUserData() instanceof TerminalEditorTab)),
                    "its editor tab went");

            // Find, with the keyboard in a terminal in the editor, opens over it and leaves the tool window shut.
            FxTestSupport.runOnFx(() -> registry.run("terminal.moveToEditor"));
            javafx.scene.control.Tab moved = FxTestSupport.callOnFx(
                    () -> terminals.panel().editorTabsForTest().getFirst());
            await(
                    () -> FxTestSupport.callOnFx(() -> terminals.panel().currentTab() == moved),
                    "the moved terminal to have the keyboard");
            FxTestSupport.runOnFx(terminals::find);
            assertTrue(FxTestSupport.callOnFx(() -> terminals.panel().findBar().isShowing()));
            assertFalse(FxTestSupport.callOnFx(() -> toolWindows.isOpen(window)), "the tool window stays shut");
            FxTestSupport.runOnFx(() -> terminals.panel().findBar().close());

            // With the keyboard elsewhere, Move to Terminal Tool Window takes the selected editor tab's terminal.
            FxTestSupport.runOnFx(
                    () -> fx.controller.editorArea.selectedTab().getTabPane().requestFocus());
            await(
                    () -> FxTestSupport.callOnFx(() -> terminals.panel().currentTab() != moved),
                    "the keyboard to leave the terminal");
            FxTestSupport.runOnFx(() -> registry.run("terminal.moveToToolWindow"));
            assertTrue(FxTestSupport.callOnFx(() -> toolWindows.isOpen(window)));
            assertFalse(FxTestSupport.callOnFx(() -> terminals.panel().isInEditor(moved)));

            // Find on a terminal in the tool window opens over it there.
            FxTestSupport.runOnFx(terminals::find);
            assertTrue(FxTestSupport.callOnFx(() -> terminals.panel().findBar().isShowing()));
            FxTestSupport.runOnFx(() -> terminals.panel().findBar().close());

            // With no terminal in the editor, it says so.
            FxTestSupport.runOnFx(() -> registry.run("terminal.moveToToolWindow"));
            assertEquals(1, (int) FxTestSupport.callOnFx(() -> terminals.panel().terminalCount()));

            // Closed in the editor (Ctrl+W's path), an idle shell ends with its tab.
            FxTestSupport.runOnFx(() -> registry.run("terminal.moveToEditor"));
            FxTestSupport.runOnFx(() -> fx.controller.closeTab(fx.controller.editorArea.selectedTab()));
            assertFalse(FxTestSupport.callOnFx(terminals::hasTerminals), "the shell's tab went");
            ProcessHandle.of(pid)
                    .ifPresent(p -> p.onExit().orTimeout(10, TimeUnit.SECONDS).join());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "shell ended");

            // Close Others in the editor closes another terminal there too, and a terminal moved there ends with
            // the window like any other shell.
            FxTestSupport.runOnFx(() -> registry.run("terminal.moveToEditor"));
            await(
                    () -> FxTestSupport.callOnFx(
                            () -> terminals.panel().viewsForTest().size() == 1),
                    "a new shell");
            awaitShellAtItsPrompt(terminals);
            long other = FxTestSupport.callOnFx(() ->
                    terminals.panel().viewsForTest().getFirst().getSession().pid());
            FxTestSupport.runOnFx(() -> fx.controller.onNew());
            FxTestSupport.runOnFx(() -> fx.controller.closeOtherTabs(fx.controller.editorArea.selectedTab()));
            assertFalse(FxTestSupport.callOnFx(terminals::hasTerminals), "Close Others took the terminal");
            ProcessHandle.of(other)
                    .ifPresent(p -> p.onExit().orTimeout(10, TimeUnit.SECONDS).join());

            FxTestSupport.runOnFx(() -> registry.run("terminal.moveToEditor"));
            await(
                    () -> FxTestSupport.callOnFx(
                            () -> terminals.panel().viewsForTest().size() == 1),
                    "a new shell");
            pid = FxTestSupport.callOnFx(() ->
                    terminals.panel().viewsForTest().getFirst().getSession().pid());
            fx.dispose();
            var process = ProcessHandle.of(pid);
            if (process.isPresent()) {
                process.get().onExit().get(10, TimeUnit.SECONDS);
            }
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "shell ended");
        }
    }

    /**
     * Waits for the one shell here to reach its prompt: it has drawn something, and the programs its start-up
     * files run have ended. Until then the shell is "running something" ({@link RunningProgram}), and closing its
     * tab asks first. Nobody answers that question in a test, so the close held the FX thread until the call timed
     * out: `FX task timed out`, three tries out of three, on runners where the shell was slow to start.
     */
    private static void awaitShellAtItsPrompt(TerminalCoordinator terminals) throws Exception {
        await(
                () -> FxTestSupport.callOnFx(() -> {
                    var views = terminals.panel().viewsForTest();
                    if (views.size() != 1 || views.getFirst().getSession() == null) {
                        return false;
                    }
                    var session = views.getFirst().getSession();
                    var buffer = session.getTextBuffer();
                    buffer.lock();
                    try {
                        if (buffer.getScreenLines().isBlank()) {
                            return false;
                        }
                    } finally {
                        buffer.unlock();
                    }
                    return RunningProgram.in(session.pid()).isEmpty();
                }),
                "the shell to reach its prompt");
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
