package com.editora.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import com.editora.terminal.TerminalPalette;
import com.editora.terminal.TerminalPalettes;
import com.editora.terminal.TerminalThemes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static com.editora.i18n.Messages.tr;
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
        shells.get(0).emit("copy me\r\n");
        await(
                () -> call(() -> panel.activeView()
                        .getSession()
                        .getTextBuffer()
                        .getScreenLines()
                        .contains("copy me")),
                "the output");
        fx(() -> {
            var view = panel.activeView();
            view.selectAll();
            javafx.scene.input.ClipboardContent empty = new javafx.scene.input.ClipboardContent();
            empty.putString("");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(empty);
            Event.fireEvent(
                    view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, !mac, !mac, false, mac)); // copy
            return null;
        });
        assertTrue(
                fx(() -> javafx.scene.input.Clipboard.getSystemClipboard()
                        .getString()
                        .contains("copy me")),
                "the selection is on the clipboard");
        fx(() -> {
            Event.fireEvent(
                    panel.activeView(),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, false, true, false, false));
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
        assertTrue(rule.test("C-S-s", "file.save"));
        assertFalse(
                rule.test("C-s", "file.save"), "C-s is the shell's (XOFF, readline's forward search) unless listed");
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
    void aTerminalSchemeOfItsOwnRecoloursOpenTerminalsAndTheirGround() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.newTerminal();
            return null;
        });
        TerminalPalette solarized = TerminalThemes.palette("Solarized Dark");
        assertNotNull(solarized);
        fx(() -> {
            panel.usePalette(solarized);
            return null;
        });
        assertEquals(solarized, fx(panel::palette));
        assertTrue(
                fx(() -> panel.activeView().getParent().getStyle()).contains("#002b36"),
                "the area around the terminal takes the scheme's background");
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
        // The URL first: a long temp path (macOS's /var/folders/…) can wrap onto a second row.
        shells.get(0).emit("see https://example.com/docs\r\n" + file + ":12:3\r\n");
        await(() -> call(() -> screen().contains("example.com")), "the output on screen");
        // A click is only a link once the view has a size: before its first layout every point is outside it.
        await(
                () -> call(() ->
                        panel.activeView().getWidth() > 0 && panel.activeView().getHeight() > 0),
                "a layout");

        clickWithLinkModifier(6, 0); // inside the URL on the first row
        clickWithLinkModifier(1, 1); // a column inside the path on the second row

        assertEquals(List.of("url https://example.com/docs", file + ":12:3"), opened);
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

    @Test
    void aRemoteShellsLinkIsCheckedOffTheFxThread(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        // A file system other than this machine's stands in for SFTP, where every check asks the host.
        try (var remote =
                java.nio.file.FileSystems.newFileSystem(dir.resolve("host.zip"), java.util.Map.of("create", "true"))) {
            java.nio.file.Files.writeString(remote.getPath("/notes.txt"), "x");
            List<String> opened = new java.util.concurrent.CopyOnWriteArrayList<>();
            show(fakeShells(), (title, programs) -> true, remote.getPath("/"));
            fx(() -> {
                panel.setShellPaths(d -> p -> remote.getPath(p));
                panel.setLinkActions(new com.editora.terminal.LinkActions() {
                    @Override
                    public void openUrl(String url) {}

                    @Override
                    public void openFile(Path f, int line, int column) {
                        opened.add(f.toString());
                    }
                });
                panel.newTerminal();
                return null;
            });
            shells.get(0).emit("see /notes.txt\r\n");
            await(() -> call(() -> screen().contains("notes")), "the output");
            await(() -> call(() -> panel.activeView().getWidth() > 0), "a layout");
            clickWithLinkModifier(6, 0);
            assertEquals(List.of(), opened, "not asked of the host on the FX thread: no link until it answers");
            await(
                    () -> {
                        try {
                            clickWithLinkModifier(6, 0);
                        } catch (Exception e) {
                            return false;
                        }
                        return !opened.isEmpty();
                    },
                    "the link, once the host said the file is there");
            assertEquals("/notes.txt", opened.getFirst());
        }
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
        var banner = (javafx.scene.layout.HBox) panel.activeTab().getContent().lookup(".terminal-lost-banner");
        return banner.getChildren().stream()
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
        // The emulator writes on its own thread, so a leaked chord would arrive late: send a key after it and
        // wait for that, then nothing else may be there.
        fx(() -> {
            Event.fireEvent(
                    panel.activeView(),
                    new KeyEvent(KeyEvent.KEY_TYPED, "z", "z", KeyCode.UNDEFINED, false, false, false, false));
            return null;
        });
        await(() -> shells.get(0).written().contains("z"), "the key sent after the chord");
        assertEquals("z", shells.get(0).written(), "and nothing of the chord reached the shell");
        String early = fx(() -> {
            panel.findBar().field().setText("b");
            panel.findBar().field().setText("be");
            panel.findBar().field().setText("beta");
            return panel.findBar().countText();
        });
        assertEquals("", early, "typing does not scan the scrollback per keystroke");
        await(() -> call(() -> panel.findBar().countText().equals("2 of 2")), "the search once the query settled");

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
        // The program's end arrives after its tab's close: let it, then it must not have been told twice.
        await(() -> call(() -> panel.terminalCount() == 0), "the tab gone");
        fx(() -> null);
        fx(() -> null);
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

    @Test
    void ctrlPageUpAndDownSwitchTerminalTabs() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.newTerminal();
            panel.newTerminal();
            panel.newTerminal();
            return null;
        });
        assertEquals(2, (int) fx(() -> panel.tabsForTest().getSelectionModel().getSelectedIndex()));
        fx(() -> {
            Event.fireEvent(
                    panel.activeView(),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.PAGE_DOWN, false, true, false, false));
            return null;
        });
        assertEquals(0, (int) fx(() -> panel.tabsForTest().getSelectionModel().getSelectedIndex()), "wraps");
        fx(() -> {
            Event.fireEvent(
                    panel.activeView(),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.PAGE_UP, false, true, false, false));
            return null;
        });
        assertEquals(2, (int) fx(() -> panel.tabsForTest().getSelectionModel().getSelectedIndex()));
        Thread.sleep(200);
        assertTrue(shells.stream().allMatch(c -> c.written().isEmpty()), "the shells got nothing");
    }

    @Test
    void tabsWithTheSameTitleAreNumbered() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.newTerminal();
            panel.newTerminal();
            return null;
        });
        await(
                () -> call(() -> panel.tabsForTest().getTabs().get(1).getText().endsWith(" (2)")),
                "the second tab told apart from the first");
        assertFalse(fx(() -> panel.tabsForTest().getTabs().getFirst().getText().endsWith(")")));
    }

    @Test
    void pasteInTheFindFieldPastesTheQueryNotIntoTheShell() throws Exception {
        boolean mac = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .startsWith("mac");
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.newTerminal();
            panel.find();
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString("rm -rf build\n");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            Event.fireEvent(
                    panel.findBar().field(),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.V, !mac, !mac, false, mac));
            return null;
        });
        Thread.sleep(200);
        assertEquals("", shells.get(0).written(), "nothing reached the shell");
    }

    @Test
    void aBellInATabOutOfSightMarksItUntilItIsSelected() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.newTerminal();
            panel.newTerminal();
            return null;
        });
        shells.get(0).emit("\u0007");
        await(
                () -> call(() ->
                        panel.tabsForTest().getTabs().getFirst().getStyleClass().contains(TerminalPanel.BELL)),
                "the first tab's bell mark");
        fx(() -> {
            panel.tabsForTest().getSelectionModel().select(0);
            return null;
        });
        assertFalse(fx(
                () -> panel.tabsForTest().getTabs().getFirst().getStyleClass().contains(TerminalPanel.BELL)));
    }

    @Test
    void theConnectionLostBannerSitsAboveTheTextNotOverIt() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.newTerminal();
            return null;
        });
        shells.get(0).emit("last words\r\n");
        shells.get(0).dropConnection();
        await(() -> call(() -> panel.showsReconnect(panel.activeTab())), "the banner");
        await(
                () -> call(() -> {
                    panel.getScene().getRoot().applyCss();
                    panel.getScene().getRoot().layout();
                    var view = panel.activeView();
                    var banner = panel.activeTab().getContent().lookup(".terminal-lost-banner");
                    // Layout positions: a focused Reconnect's focus ring draws a little past the banner.
                    double bannerBottom =
                            banner.getLayoutY() + banner.getLayoutBounds().getHeight();
                    return banner.getLayoutBounds().getHeight() > 0 && view.getLayoutY() >= bannerBottom - 0.5;
                }),
                "the view starting below the banner");
    }

    @Test
    void closingARemoteTabAsksItsHostOffTheFxThread() throws Exception {
        java.util.concurrent.CountDownLatch answer = new java.util.concurrent.CountDownLatch(1);
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        TerminalPanel.Shells fake = fakeShells();
        show(
                (dir, shell) -> (columns, rows) -> {
                    FakeConnector c = (FakeConnector) fake.open(dir, shell).open(columns, rows);
                    c.remotePid = 4242;
                    return c;
                },
                (title, programs) -> {
                    asked.add(String.join(",", programs));
                    return true;
                },
                Path.of(System.getProperty("java.io.tmpdir")));
        fx(() -> {
            panel.setRemotePrograms((dir, pid) -> {
                try {
                    answer.await(10, TimeUnit.SECONDS); // a slow host
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of("vim");
            });
            panel.newTerminal();
            return null;
        });
        long t0 = System.nanoTime();
        fx(() -> {
            panel.closeActive();
            return null;
        });
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 1_000, "the FX thread did not wait");
        assertEquals(1, fx(panel::terminalCount), "the tab stays while the host is asked");
        assertTrue(fx(() -> panel.isAskingToClose(panel.activeTab())));
        answer.countDown();
        await(() -> call(() -> panel.terminalCount() == 0), "the close once the host answered");
        assertEquals(List.of("vim"), asked);
    }

    @Test
    void aProgramOnAnotherMachineOpensOffTheFxThread() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        java.util.concurrent.CountDownLatch hostAnswers = new java.util.concurrent.CountDownLatch(1);
        FakeConnector program = new FakeConnector();
        List<Integer> exits = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.editora.terminal.ProgramRun run = fx(() -> panel.runProgram(
                "Main",
                null,
                com.editora.terminal.TerminalConnector.Factory.slow((c, r) -> {
                    try {
                        hostAnswers.await(10, TimeUnit.SECONDS); // an SSH channel opening
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return program;
                }),
                exits::add));
        assertEquals(1, fx(panel::terminalCount), "the tab is there while it connects");
        assertTrue(run.isRunning());
        hostAnswers.countDown();
        await(() -> call(() -> panel.activeView() != null), "the program's view once connected");
        program.exit(0);
        await(() -> !exits.isEmpty(), "its end");
        assertEquals(List.of(0), exits);
    }

    @Test
    void optionComposesOnMacUnlessTheSettingMakesItMeta() {
        assertFalse(TerminalPanel.altIsMeta(true, false), "macOS default: @ [ ] { } on Option chords can be typed");
        assertTrue(TerminalPanel.altIsMeta(true, true));
        assertTrue(TerminalPanel.altIsMeta(false, false), "Alt is Meta on Linux and Windows");
    }

    @Test
    void aMultiLinePasteIntoAProgramWithoutBracketedPasteAsksFirst() throws Exception {
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.setPasteGuard(text -> {
                asked.add(text);
                return false; // Cancel
            });
            panel.newTerminal();
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString("echo one\necho two\n");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            panel.activeView().paste();
            return null;
        });
        assertEquals(1, asked.size(), "asked");
        Thread.sleep(200);
        assertEquals("", shells.get(0).written(), "and Cancel pasted nothing");

        shells.get(0).emit("\u001b[?2004h"); // bash turns bracketed paste on
        await(() -> call(() -> panel.activeView().getDisplay().isBracketedPaste()), "bracketed paste");
        fx(() -> {
            panel.activeView().paste();
            return null;
        });
        await(() -> shells.get(0).written().contains("echo two"), "the paste, unasked");
        assertEquals(1, asked.size());
    }

    @Test
    void theWindowCloseQuestionListsEveryBusyTerminal() throws Exception {
        TerminalPanel.Shells fake = fakeShells();
        show(
                (dir, shell) -> (columns, rows) -> {
                    FakeConnector c = (FakeConnector) fake.open(dir, shell).open(columns, rows);
                    c.remotePid = 77;
                    return c;
                },
                (title, programs) -> true,
                Path.of(System.getProperty("java.io.tmpdir")));
        FakeConnector program = new FakeConnector();
        fx(() -> {
            panel.setRemotePrograms((dir, pid) -> List.of("htop"));
            panel.newTerminal();
            panel.runProgram("Build", null, (c, r) -> program, code -> {});
            return null;
        });
        List<String> busy = fx(() -> panel.busyTabs(java.time.Duration.ofSeconds(2)));
        assertEquals(2, busy.size(), busy.toString());
        assertTrue(busy.stream().anyMatch(b -> b.endsWith("(htop)")), busy.toString());
        assertTrue(busy.contains("Build (Build)"), busy.toString());

        program.exit(0);
        await(
                () -> call(() -> panel.busyTabs(java.time.Duration.ofSeconds(2)).size() == 1),
                "an ended program is not busy");
    }

    @Test
    void aShellStartedInTheBackgroundGetsTheKeyboardOnlyWhenItWasAskedFor() throws Exception {
        javafx.scene.control.TextField editor = fx(javafx.scene.control.TextField::new);
        TerminalPanel.Shells fake = fakeShells();
        fx(() -> {
            panel = new TerminalPanel(
                    (dir, shell) -> com.editora.terminal.TerminalConnector.Factory.background(fake.open(dir, shell)),
                    (title, programs) -> true,
                    () -> null);
            stage = new Stage();
            stage.setScene(new Scene(new javafx.scene.layout.VBox(editor, panel), 800, 400));
            stage.show();
            editor.requestFocus();
            panel.newTerminalQuietly(); // a restored window's
            return null;
        });
        await(() -> call(() -> panel.activeView() != null), "the quiet shell started");
        Thread.sleep(200);
        assertTrue(fx(editor::isFocused), "a quiet start leaves the editor the keyboard");

        fx(() -> {
            panel.newTerminal(); // asked for
            return null;
        });
        await(
                () -> call(
                        () -> panel.activeView() != null && panel.activeView().isFocused()),
                "the asked-for shell's focus");
    }

    @Test
    void runningTheSameProgramAgainReusesItsEndedTab() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        FakeConnector first = new FakeConnector();
        fx(() -> panel.runProgram("App.java", null, (c, r) -> first, code -> {}));
        first.exit(0);
        await(() -> call(() -> panel.exitBanner(panel.activeTab()) != null), "the first run's end");

        AtomicInteger emptied = new AtomicInteger();
        fx(() -> {
            panel.setOnLastClosed(emptied::incrementAndGet);
            return null;
        });
        FakeConnector second = new FakeConnector();
        fx(() -> panel.runProgram("App.java", null, (c, r) -> second, code -> {}));
        assertEquals(1, fx(panel::terminalCount), "the ended run's tab was reused");
        assertEquals(0, emptied.get(), "without the window ever seeing no tabs (it would hide)");
        assertTrue(first.wasClosed() || !first.isConnected());

        FakeConnector third = new FakeConnector();
        fx(() -> panel.runProgram("App.java", null, (c, r) -> third, code -> {}));
        assertEquals(2, fx(panel::terminalCount), "a run still going keeps its tab");
    }

    @Test
    void tabsCanBeRenamedMovedAndHaveTheirOwnMenu() throws Exception {
        show(fakeShells(), (title, programs) -> true, null);
        fx(() -> {
            panel.newTerminal();
            panel.newTerminal();
            panel.newTerminal();
            return null;
        });
        var first = fx(() -> panel.tabsForTest().getTabs().get(0));
        var views = fx(panel::viewsForTest);

        fx(() -> {
            panel.rename(first, "build");
            return null;
        });
        assertEquals("build", fx(first::getText), "a name of the user's own");

        fx(() -> {
            panel.moveTab(0, 2); // what a drag or Move Tab Right does
            return null;
        });
        assertEquals(2, (int) fx(() -> panel.tabsForTest().getTabs().indexOf(first)));
        assertEquals(3, fx(panel::terminalCount), "a move closes nothing");
        assertTrue(shells.stream().noneMatch(FakeConnector::wasClosed), "and ends no shell");
        assertEquals(views.size(), fx(() -> panel.viewsForTest().size()));

        fx(() -> {
            Event.fireEvent(
                    panel.activeView(),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.PAGE_UP, true, true, false, false));
            return null;
        });
        assertEquals(
                1, (int) fx(() -> panel.tabsForTest().getTabs().indexOf(first)), "Ctrl+Shift+PageUp moves it left");

        assertTrue(
                fx(() -> first.getContextMenu() != null
                        && first.getContextMenu().getItems().stream()
                                .anyMatch(i -> tr("terminal.tab.rename").equals(i.getText()))),
                "its own menu");
        fx(() -> {
            panel.rename(first, "");
            return null;
        });
        assertFalse(fx(() -> "build".equals(first.getText())), "an empty name gives back the shell's title");
    }

    @Test
    void aShellStartedQuietlyLeavesTheKeyboardWhereItWas() throws Exception {
        // A terminal left open comes back at startup: the editor's keystrokes must not land in a shell.
        javafx.scene.control.TextField editor = fx(javafx.scene.control.TextField::new);
        fx(() -> {
            panel = new TerminalPanel(fakeShells(), (title, programs) -> true, () -> null);
            stage = new Stage();
            stage.setScene(new Scene(new javafx.scene.layout.VBox(editor, panel), 800, 400));
            stage.show();
            editor.requestFocus();
            panel.newTerminalQuietly();
            return null;
        });
        assertEquals(1, fx(panel::terminalCount));
        Thread.sleep(200);
        assertTrue(fx(editor::isFocused), "the editor kept the keyboard");
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
