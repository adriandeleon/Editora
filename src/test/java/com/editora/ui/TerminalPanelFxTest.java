package com.editora.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.editora.command.KeyDispatcher;
import com.editora.i18n.Messages;
import com.editora.terminal.FakeConnector;
import com.editora.terminal.LocalShell;
import com.editora.terminal.RunningProgram;
import com.editora.terminal.TerminalCursor;
import com.editora.terminal.TerminalPalettes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Terminal tool window's panel with stand-in shells: tabs come and go with their shells, the keys the
 * shell owns stay with it, and the palette follows the editor theme the scene has applied.
 */
@Tag("fx")
class TerminalPanelFxTest {

    private Stage stage;
    private TerminalPanel panel;
    private final List<FakeConnector> shells = new ArrayList<>();
    private final List<Path> openedIn = new ArrayList<>();

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
        Messages.init("en");
    }

    @AfterEach
    void tearDown() throws Exception {
        fx(() -> {
            if (panel != null) panel.shutdown();
            if (stage != null) stage.close();
            return null;
        });
    }

    @Test
    void aNewTerminalOpensATabWithAShellInTheProjectFolder(@TempDir Path project) throws Exception {
        show(fakeShells(), (title, programs) -> true, project);
        fx(() -> {
            panel.newTerminal();
            return null;
        });

        assertEquals(1, fx(panel::terminalCount));
        assertEquals(List.of(project), openedIn);
        assertNotNull(fx(panel::activeView));
    }

    @Test
    void closingATabEndsItsShell() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.newTerminal();
            panel.newTerminal();
            return null;
        });
        assertEquals(2, fx(panel::terminalCount));

        fx(() -> {
            panel.closeActive();
            return null;
        });

        assertEquals(1, fx(panel::terminalCount));
        assertTrue(shells.get(1).wasClosed(), "the selected (second) tab's shell");
        assertFalse(shells.get(0).wasClosed());
    }

    @Test
    void aShellThatExitsTakesItsTabWith() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.newTerminal();
            return null;
        });
        shells.get(0).endOfOutput();

        await(() -> call(() -> !panel.hasTerminals()), "the tab to close after its shell ended");
    }

    @Test
    void theWindowClosingEndsEveryShell() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.newTerminal();
            panel.newTerminal();
            panel.shutdown();
            return null;
        });
        assertTrue(shells.stream().allMatch(FakeConnector::wasClosed));
    }

    @Test
    void aShellThatCannotStartLeavesNoTabAndSaysWhy() throws Exception {
        AtomicReference<String> error = new AtomicReference<>();
        show(
                (dir, shell) -> (columns, rows) -> {
                    throw new IOException("no such shell");
                },
                (title, programs) -> true,
                null);
        fx(() -> {
            panel.setOnError(error::set);
            panel.newTerminal();
            return null;
        });

        assertFalse(fx(panel::hasTerminals));
        assertTrue(error.get() != null && error.get().contains("no such shell"), error.get());
    }

    @Test
    void ctrlShiftCCopiesInsteadOfSendingAnInterrupt() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.newTerminal();
            return null;
        });
        fx(() -> {
            var view = panel.activeView();
            Event.fireEvent(view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, true, true, false, false));
            Event.fireEvent(view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, false, true, false, false));
            return null;
        });

        await(() -> shells.get(0).written().equals("\u0003"), "only the plain Ctrl+C on the wire");
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePanelTellsTheDispatcherWhichKeysAreTheShells() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        BiPredicate<String, String> rule =
                (BiPredicate<String, String>) fx(() -> panel.getProperties().get(KeyDispatcher.SHELL_KEYS));
        assertFalse(rule.test("C-c", "edit.copy"));
        assertTrue(rule.test("C-S-p", "palette.show"));

        fx(() -> {
            panel.apply(
                    new TerminalPanel.Config("", 13, 1000, TerminalCursor.BAR, false, "", List.of("file.save"), true));
            return null;
        });
        assertFalse(rule.test("C-S-p", "palette.show"), "the allow-list comes from the settings");
        assertTrue(rule.test("C-s", "file.save"));
    }

    @Test
    void thePaletteFollowsTheEditorThemeTheSceneApplied() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            stage.getScene()
                    .getStylesheets()
                    .addAll(
                            css("/com/editora/styles/syntax.css"),
                            css("/com/editora/styles/editor-themes/editora-dark.css"));
            panel.refreshPalette(TerminalPalettes.EDITORA_LIGHT);
            return null;
        });

        assertEquals(Color.web("#171a24"), fx(() -> panel.palette().background()), "Editora Dark's editor surface");
        assertEquals(Color.web("#ff7b72"), fx(() -> panel.palette().ansi()[1]), "its keyword colour as ANSI red");
    }

    @Test
    void anUnreadableThemeFallsBackToTheGivenPalette() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.refreshPalette(TerminalPalettes.EDITORA_LIGHT); // no editor stylesheet in this scene
            return null;
        });
        assertEquals(TerminalPalettes.EDITORA_LIGHT, fx(panel::palette));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "RunningProgram reads child processes; sh syntax below")
    void closingAShellThatIsRunningAProgramAsksFirst(@TempDir Path dir) throws Exception {
        AtomicBoolean answer = new AtomicBoolean(false);
        List<List<String>> asked = new ArrayList<>();
        show(
                (directory, shell) ->
                        (columns, rows) -> LocalShell.start(List.of("/bin/sh"), directory.toString(), columns, rows),
                (title, programs) -> {
                    asked.add(programs);
                    return answer.get();
                },
                dir);
        fx(() -> {
            panel.newTerminal();
            return null;
        });
        long pid = fx(() -> panel.activeView().getSession().pid());
        fx(() -> {
            panel.activeView().getSession().sendString("sleep 30\r");
            return null;
        });
        await(() -> !RunningProgram.in(pid).isEmpty(), "sleep to be running under the shell");

        fx(() -> {
            panel.closeActive();
            return null;
        });
        assertEquals(List.of(List.of("sleep")), asked);
        assertTrue(fx(panel::hasTerminals), "Cancel keeps the tab");

        answer.set(true);
        fx(() -> {
            panel.closeActive();
            return null;
        });
        assertFalse(fx(panel::hasTerminals), "Close ends it");
    }

    // ---------------------------------------------------------------- helpers

    private TerminalPanel.Shells fakeShells() {
        return (dir, shell) -> (columns, rows) -> {
            FakeConnector connector = new FakeConnector();
            shells.add(connector);
            openedIn.add(dir);
            return connector;
        };
    }

    private void show(TerminalPanel.Shells source, TerminalPanel.CloseGuard guard, Path dir) throws Exception {
        fx(() -> {
            panel = new TerminalPanel(source, guard, () -> dir);
            stage = new Stage();
            stage.setScene(new Scene(panel, 800, 400));
            stage.show();
            return null;
        });
    }

    private static String css(String resource) {
        return TerminalPanelFxTest.class.getResource(resource).toExternalForm();
    }

    private static boolean call(Callable<Boolean> c) {
        try {
            return fx(c);
        } catch (Exception e) {
            return false;
        }
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
