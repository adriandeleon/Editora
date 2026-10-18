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
    void theCopyChordCopiesInsteadOfSendingAnInterrupt() throws Exception {
        // Ctrl+Shift+C off macOS; Cmd+C on it, where Ctrl+Shift+C is the shell's like any Ctrl chord.
        boolean mac = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .startsWith("mac");
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.newTerminal();
            return null;
        });
        fx(() -> {
            var view = panel.activeView();
            Event.fireEvent(
                    view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, !mac, !mac, false, mac)); // copy
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

    @Test
    void openInTerminalStartsTheShellInTheFolderAsked(@TempDir Path project, @TempDir Path elsewhere) throws Exception {
        show(fakeShells(), (title, programs) -> true, project);
        fx(() -> {
            panel.newTerminal(elsewhere);
            return null;
        });
        assertEquals(List.of(elsewhere), openedIn);
    }

    @Test
    void aModifierClickOnAPathInTheOutputOpensItAtItsLineAndColumn(@TempDir Path dir) throws Exception {
        Path file = java.nio.file.Files.writeString(dir.resolve("Foo.java"), "class Foo {}\n");
        List<String> opened = new ArrayList<>();
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.setLinkActions(new com.editora.terminal.LinkActions() {
                @Override
                public void openUrl(String url) {
                    opened.add("url " + url);
                }

                @Override
                public void openFile(Path f, int line, int column) {
                    opened.add(f + ":" + line + ":" + column);
                }
            });
            panel.newTerminal();
            return null;
        });
        shells.get(0).emit(file + ":12:3\r\nsee https://example.com/docs\r\n");
        await(() -> call(() -> screen().contains("example.com")), "the output on screen");
        // A click is only a link once the view has a size: before its first layout every point is outside it.
        await(
                () -> call(() ->
                        panel.activeView().getWidth() > 0 && panel.activeView().getHeight() > 0),
                "a layout");

        clickWithLinkModifier(1, 0); // a column inside the path on the first row
        clickWithLinkModifier(6, 1); // inside the URL on the second row

        assertEquals(List.of(file + ":12:3", "url https://example.com/docs"), opened);
    }

    @Test
    void anOsc8LinkOpensItsTargetNotItsTextAndOnlySafeSchemesAreLinks(@TempDir Path dir) throws Exception {
        Path file = java.nio.file.Files.writeString(dir.resolve("Bar.java"), "class Bar {}\n");
        List<String> opened = new ArrayList<>();
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.setLinkActions(new com.editora.terminal.LinkActions() {
                @Override
                public void openUrl(String url) {
                    opened.add("url " + url);
                }

                @Override
                public void openFile(Path f, int line, int column) {
                    opened.add("file " + f);
                }
            });
            panel.newTerminal();
            return null;
        });
        String st = "\u001b\\";
        shells.get(0)
                .emit("\u001b]8;;https://example.com/manual" + st + "the manual\u001b]8;;" + st + " end\r\n"
                        + "\u001b]8;;" + file.toUri() + st + "Bar\u001b]8;;" + st + "\r\n"
                        + "\u001b]8;;javascript:alert(1)" + st + "trap\u001b]8;;" + st + "\r\n");
        await(() -> call(() -> screen().contains("trap")), "the output on screen");
        assertFalse(call(() -> screen().contains("example.com")), "the target is not drawn");
        await(
                () -> call(() ->
                        panel.activeView().getWidth() > 0 && panel.activeView().getHeight() > 0),
                "a layout");

        clickWithLinkModifier(5, 0); // inside "the manual"
        clickWithLinkModifier(12, 0); // " end": past the link
        clickWithLinkModifier(1, 1); // "Bar", a file:// link
        clickWithLinkModifier(1, 2); // "trap": a scheme that is never a link

        assertEquals(List.of("url https://example.com/manual", "file " + file), opened);

        mouseWithLinkModifier(javafx.scene.input.MouseEvent.MOUSE_MOVED, 5, 0);
        assertEquals(
                "https://example.com/manual",
                fx(() -> panel.activeView().linkTipText()),
                "hovering shows where the link really goes");
        mouseWithLinkModifier(javafx.scene.input.MouseEvent.MOUSE_MOVED, 12, 0);
        assertEquals(null, fx(() -> panel.activeView().linkTipText()), "and hides off it");
    }

    /** A primary press with the platform's link modifier (Cmd on macOS, Ctrl elsewhere) at a cell. */
    private void clickWithLinkModifier(int column, int row) throws Exception {
        mouseWithLinkModifier(javafx.scene.input.MouseEvent.MOUSE_PRESSED, column, row);
    }

    private void mouseWithLinkModifier(javafx.event.EventType<javafx.scene.input.MouseEvent> type, int column, int row)
            throws Exception {
        boolean mac = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .startsWith("mac");
        fx(() -> {
            var view = panel.activeView();
            var size = view.getSession().getTerminal().getSize();
            double cellWidth = view.getWidth() / size.getColumns();
            double cellHeight = view.getHeight() / size.getRows();
            // A MouseEvent is built in scene coordinates; firing it at the view makes them local again.
            var at = view.localToScene((column + 0.5) * cellWidth, (row + 0.5) * cellHeight);
            double x = at.getX();
            double y = at.getY();
            Event.fireEvent(
                    view,
                    new javafx.scene.input.MouseEvent(
                            type,
                            x,
                            y,
                            x,
                            y,
                            javafx.scene.input.MouseButton.PRIMARY,
                            1,
                            false,
                            !mac,
                            false,
                            mac,
                            true,
                            false,
                            false,
                            false,
                            false,
                            false,
                            null));
            return null;
        });
    }

    private String screen() throws Exception {
        var buffer = panel.activeView().getSession().getTextBuffer();
        buffer.lock();
        try {
            return buffer.getScreenLines();
        } finally {
            buffer.unlock();
        }
    }

    @Test
    void aDroppedConnectionKeepsTheTabAndReconnectStartsANewShellInIt() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.newTerminal();
            return null;
        });
        shells.get(0).emit("what was on screen\r\n");
        shells.get(0).dropConnection();

        await(() -> call(() -> panel.showsReconnect(panel.activeTab())), "the connection-lost banner");
        assertEquals(1, fx(panel::terminalCount), "the tab stays, with its text");

        fx(() -> {
            reconnectButton().fire();
            return null;
        });
        assertEquals(2, shells.size(), "a new shell");
        assertFalse(fx(() -> panel.showsReconnect(panel.activeTab())), "the banner goes with the old view");
        assertEquals(1, fx(panel::terminalCount), "in the same tab");
        assertTrue(shells.get(0).wasClosed() || !shells.get(0).isConnected());
    }

    @Test
    void aReconnectThatFailsKeepsTheBannerAndSaysWhy() throws Exception {
        AtomicReference<String> error = new AtomicReference<>();
        AtomicBoolean refuse = new AtomicBoolean(false);
        TerminalPanel.Shells fake = fakeShells();
        show(
                (dir, shell) -> refuse.get()
                        ? (columns, rows) -> {
                            throw new IOException("not connected to box");
                        }
                        : fake.open(dir, shell),
                (title, programs) -> true,
                null);
        fx(() -> {
            panel.setOnError(error::set);
            panel.newTerminal();
            return null;
        });
        shells.get(0).dropConnection();
        await(() -> call(() -> panel.showsReconnect(panel.activeTab())), "the connection-lost banner");

        refuse.set(true);
        fx(() -> {
            reconnectButton().fire();
            return null;
        });
        assertTrue(fx(() -> panel.showsReconnect(panel.activeTab())), "still offering to reconnect");
        assertTrue(error.get() != null && error.get().contains("not connected to box"), error.get());
    }

    @Test
    void aShellThatExitsOnItsOwnStillClosesItsTab() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.newTerminal();
            return null;
        });
        shells.get(0).endOfOutput(); // exit, not a drop
        await(() -> call(() -> !panel.hasTerminals()), "the tab to close");
    }

    private javafx.scene.control.Button reconnectButton() {
        var content = (javafx.scene.layout.StackPane) panel.activeTab().getContent();
        return content.getChildren().stream()
                .filter(n -> n.getStyleClass().contains("terminal-lost-banner"))
                .map(n -> (javafx.scene.layout.HBox) n)
                .flatMap(box -> box.getChildren().stream())
                .filter(n -> n instanceof javafx.scene.control.Button)
                .map(n -> (javafx.scene.control.Button) n)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theFindChordOpensTheFindBarOverTheTabAndEscapeGivesTheKeysBack() throws Exception {
        boolean mac = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .startsWith("mac");
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.newTerminal();
            return null;
        });
        shells.get(0).emit("alpha beta\r\nbeta gamma\r\n");
        await(
                () -> call(() -> panel.activeView()
                        .getSession()
                        .getTextBuffer()
                        .getScreenLines()
                        .contains("gamma")),
                "the output");

        fx(() -> {
            Event.fireEvent(
                    panel.activeView(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, !mac, !mac, false, mac));
            return null;
        });
        assertTrue(fx(() -> panel.findBar().isShowing()), "the chord opened the bar");
        assertTrue(shells.get(0).written().isEmpty(), "and nothing reached the shell");
        fx(() -> {
            panel.findBar().field().setText("beta");
            return null;
        });
        assertEquals("2 of 2", fx(() -> panel.findBar().countText()));

        fx(() -> {
            Event.fireEvent(
                    panel.findBar().field(),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            return null;
        });
        assertFalse(fx(() -> panel.findBar().isShowing()));
        assertTrue(fx(() -> panel.activeView().searchMatches().isEmpty()), "the highlights went with it");
    }

    @Test
    void aProgramTabStaysWhenItsProgramEndsAndTellsItsStarterTheExitCode() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        FakeConnector program = new FakeConnector();
        List<Integer> exits = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.editora.terminal.ProgramRun run = fx(() -> panel.runProgram("Main", null, (c, r) -> program, exits::add));
        assertEquals("Main", fx(() -> panel.activeTab().getText()));
        assertTrue(run.isRunning());

        program.emit("hello\r\n");
        program.exit(3);
        await(() -> !exits.isEmpty(), "the program's end");
        assertEquals(List.of(3), exits);
        assertFalse(run.isRunning());
        assertEquals(1, fx(panel::terminalCount), "the tab stays, with what the program printed");
        assertEquals("Process finished with exit code 3", fx(() -> panel.exitBanner(panel.activeTab())));
        assertFalse(fx(() -> panel.showsReconnect(panel.activeTab())), "an exit is not a lost connection");
    }

    @Test
    void closingAProgramTabEndsTheProgramAndSaysSoOnce() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        FakeConnector program = new FakeConnector();
        List<Integer> exits = new java.util.concurrent.CopyOnWriteArrayList<>();
        fx(() -> panel.runProgram("Main", null, (c, r) -> program, exits::add));
        fx(() -> {
            panel.closeActive();
            return null;
        });
        assertTrue(program.wasClosed());
        Thread.sleep(300);
        assertEquals(List.of(-1), exits);
    }

    @Test
    void closingARunningProgramsTabAsksFirstAndAnEndedOneDoesNot() throws Exception {
        AtomicBoolean answer = new AtomicBoolean(false);
        List<String> asked = new ArrayList<>();
        show(
                fakeShells(),
                (title, programs) -> {
                    asked.add(title + ": " + programs);
                    return answer.get();
                },
                null);
        FakeConnector program = new FakeConnector();
        List<Integer> exits = new java.util.concurrent.CopyOnWriteArrayList<>();
        fx(() -> panel.runProgram("Main", "java", null, (c, r) -> program, exits::add));

        fx(() -> {
            panel.closeActive();
            return null;
        });
        assertEquals(List.of("Main: [java]"), asked, "asked, naming the program");
        assertTrue(fx(panel::hasTerminals), "Cancel keeps the tab");
        assertFalse(program.wasClosed(), "and the program runs on");
        assertTrue(exits.isEmpty());

        answer.set(true);
        fx(() -> {
            panel.closeActive();
            return null;
        });
        assertFalse(fx(panel::hasTerminals), "Close ends it");
        assertTrue(program.wasClosed());

        // Once its program has ended, a tab closes without a question.
        asked.clear();
        FakeConnector done = new FakeConnector();
        List<Integer> doneExits = new java.util.concurrent.CopyOnWriteArrayList<>();
        fx(() -> panel.runProgram("Done", "make", null, (c, r) -> done, doneExits::add));
        done.exit(0);
        await(() -> !doneExits.isEmpty(), "the program's end");
        fx(() -> {
            panel.closeActive();
            return null;
        });
        assertEquals(List.of(), asked);
        assertFalse(fx(panel::hasTerminals));
    }

    @Test
    void withConfirmCloseOffAProgramsTabClosesWithoutAsking() throws Exception {
        List<String> asked = new ArrayList<>();
        show(fakeShells(), (title, programs) -> asked.add(title) && false, null);
        fx(() -> {
            panel.apply(new TerminalPanel.Config("", 13, 1000, TerminalCursor.BAR, false, "", List.of(), false));
            return null;
        });
        FakeConnector program = new FakeConnector();
        fx(() -> panel.runProgram("Main", "java", null, (c, r) -> program, code -> {}));
        fx(() -> {
            panel.closeActive();
            return null;
        });
        assertEquals(List.of(), asked);
        assertTrue(program.wasClosed());
    }

    @Test
    void stopEndsTheProgramButKeepsItsTab() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        FakeConnector program = new FakeConnector();
        List<Integer> exits = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.editora.terminal.ProgramRun run = fx(() -> panel.runProgram("Main", null, (c, r) -> program, exits::add));
        fx(() -> {
            run.stop();
            return null;
        });
        await(() -> !exits.isEmpty(), "the end after Stop");
        assertTrue(program.wasClosed());
        assertEquals(1, fx(panel::terminalCount));
    }

    @Test
    void aProgramThatCannotStartLeavesNoTab() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        java.io.IOException failure = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> {
            try {
                fx(() -> panel.runProgram(
                        "Main",
                        null,
                        (c, r) -> {
                            throw new java.io.IOException("no such program");
                        },
                        code -> {}));
            } catch (java.util.concurrent.ExecutionException e) {
                throw e.getCause();
            }
        });
        assertEquals("no such program", failure.getMessage());
        assertFalse(fx(panel::hasTerminals));
    }

    @Test
    void aShellOnAnotherMachineConnectsWithoutHoldingTheFxThread() throws Exception {
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        FakeConnector remote = new FakeConnector();
        show(
                (dir, shell) -> com.editora.terminal.TerminalConnector.Factory.slow((columns, rows) -> {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    return remote;
                }),
                (title, programs) -> true,
                null);
        fx(() -> {
            panel.newTerminal(); // returns while the "host" has not answered: the FX thread is free
            return null;
        });
        assertEquals(1, fx(panel::terminalCount), "the tab is there at once");
        assertTrue(fx(() -> panel.isConnecting(panel.activeTab())), "saying it is connecting");
        assertEquals(null, fx(panel::activeView));

        gate.countDown();
        await(() -> call(() -> panel.activeView() != null), "the shell once connected");
        assertFalse(fx(() -> panel.isConnecting(panel.activeTab())));
        remote.emit("hello\r\n");
        await(() -> call(() -> screen().contains("hello")), "its output");
    }

    @Test
    void aShellThatFailsToConnectTakesItsNewTabAwayAndSaysWhy() throws Exception {
        AtomicReference<String> error = new AtomicReference<>();
        show(
                (dir, shell) -> com.editora.terminal.TerminalConnector.Factory.slow((columns, rows) -> {
                    throw new IOException("not connected to box");
                }),
                (title, programs) -> true,
                null);
        fx(() -> {
            panel.setOnError(error::set);
            panel.newTerminal();
            return null;
        });
        await(() -> !call(panel::hasTerminals), "the tab gone");
        assertTrue(error.get() != null && error.get().contains("not connected to box"), error.get());
    }

    @Test
    void aTabClosedWhileConnectingClosesTheConnectionThatArrivesLate() throws Exception {
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        FakeConnector remote = new FakeConnector();
        show(
                (dir, shell) -> com.editora.terminal.TerminalConnector.Factory.slow((columns, rows) -> {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    return remote;
                }),
                (title, programs) -> true,
                null);
        fx(() -> {
            panel.newTerminal();
            panel.closeActive();
            return null;
        });
        assertFalse(fx(panel::hasTerminals));
        gate.countDown();
        await(remote::wasClosed, "the late connection closed");
        assertFalse(fx(panel::hasTerminals), "and no tab came back for it");
    }

    @Test
    void aSlowReconnectThatFailsKeepsTheBanner() throws Exception {
        AtomicReference<String> error = new AtomicReference<>();
        AtomicBoolean refuse = new AtomicBoolean(false);
        TerminalPanel.Shells fake = fakeShells();
        show(
                (dir, shell) -> refuse.get()
                        ? com.editora.terminal.TerminalConnector.Factory.slow((columns, rows) -> {
                            throw new IOException("not connected to box");
                        })
                        : fake.open(dir, shell),
                (title, programs) -> true,
                null);
        fx(() -> {
            panel.setOnError(error::set);
            panel.newTerminal();
            return null;
        });
        shells.get(0).dropConnection();
        await(() -> call(() -> panel.showsReconnect(panel.activeTab())), "the connection-lost banner");

        refuse.set(true);
        fx(() -> {
            reconnectButton().fire();
            return null;
        });
        await(() -> error.get() != null, "the failure");
        assertTrue(error.get().contains("not connected to box"), error.get());
        assertTrue(fx(() -> panel.showsReconnect(panel.activeTab())), "still offering to reconnect");
        assertFalse(fx(() -> panel.isConnecting(panel.activeTab())));
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
