package com.editora.terminal;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Scene;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.editora.testkit.Await;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * More of the view over an in-memory connector: which modified keys scroll the history and which reach the
 * program, Insert's clipboard chords, keys with no shell behind the view, AltGr on Linux, the kitty protocol's
 * releases and text, the caret a screen reader is told, and copying the last command's output.
 */
@Tag("fx")
class TerminalViewMoreFxTest {

    private Stage stage;
    private TerminalView view;
    private FakeConnector connector;

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void tearDown() throws Exception {
        fx(() -> {
            if (view != null) view.close();
            if (stage != null) stage.close();
            return null;
        });
    }

    @Test
    void shiftWithTheScrollKeysWalksTheHistoryAndOtherModifiersReachTheProgram() throws Exception {
        open();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 200; i++) lines.append("line ").append(i).append("\r\n");
        connector.emit(lines.toString());
        await(() -> screen().contains("line 199"), "the output");
        int history = fx(() -> view.getSession().getTextBuffer().getHistoryLinesCount());

        assertEquals(-history, (int) press(KeyCode.HOME, true, false, false, false), "Shift+Home: the top");
        int down = press(KeyCode.PAGE_DOWN, true, false, false, false);
        assertTrue(down > -history && down < 0, "Shift+PageDown: a page lower, " + down);
        int up = press(KeyCode.PAGE_UP, true, false, false, false);
        assertEquals(-history, up, "Shift+PageUp: back up, and no further than the top");
        assertEquals(-history, (int) press(KeyCode.PAGE_UP, true, false, false, false), "already at the top");
        assertEquals(0, (int) press(KeyCode.END, true, false, false, false));
        assertEquals(0, (int) press(KeyCode.PAGE_DOWN, true, false, false, false), "the live screen is the bottom");

        // Not scrollback chords: the view stays put and the keys go on.
        assertEquals(0, (int) press(KeyCode.PAGE_UP, true, true, false, false), "Ctrl+Shift+PageUp");
        assertEquals(0, (int) press(KeyCode.PAGE_UP, true, false, true, false), "Alt+Shift+PageUp");
        if (!com.editora.Os.isMac()) {
            assertEquals(0, (int) press(KeyCode.PAGE_UP, true, false, false, true), "Meta+Shift+PageUp");
        }
        assertEquals(0, (int) press(KeyCode.F5, true, false, false, false), "Shift+F5 is the program's");
        assertEquals(0, (int) press(KeyCode.PAGE_UP, false, false, false, false), "PageUp alone");
        await(() -> connector.written().contains("\u001b[15"), "Shift+F5 reached the program");
    }

    @Test
    void onTheAlternateScreenShiftPageUpIsTheProgramsKey() throws Exception {
        open();
        connector.emit("\u001b[?1049h");
        await(() -> view.getDisplay().isAlternateScreen(), "the alternate screen");
        assertEquals(0, (int) press(KeyCode.PAGE_UP, true, false, false, false));
        await(() -> connector.written().contains("\u001b[5"), "Shift+PageUp sent to the program");
    }

    @Test
    void insertWithAnyOtherModifierIsAKeyForTheProgram() throws Exception {
        open();
        fx(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString("CLIP");
            Clipboard.getSystemClipboard().setContent(content);
            return null;
        });
        press(KeyCode.INSERT, false, false, false, false);
        press(KeyCode.INSERT, true, true, false, false);
        press(KeyCode.INSERT, true, false, true, false);
        press(KeyCode.INSERT, true, false, false, true);
        type("x");
        await(() -> connector.written().endsWith("x"), "the marker after the keys");
        assertFalse(connector.written().contains("CLIP"), "none of them pasted");
        String sent = connector.written();
        assertEquals(4, sent.split("\u001b\\[2", -1).length - 1, "four Insert keys: " + visible(sent));
    }

    @Test
    void keysWithNoShellBehindTheViewAreLeftAlone() throws Exception {
        fx(() -> {
            view = new TerminalView(13);
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 400, 200));
            stage.show();
            KeyEvent key = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false);
            Event.fireEvent(view, key);
            Event.fireEvent(view, typed("a"));
            Event.fireEvent(
                    view, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.ENTER, false, false, false, false));
            assertNull(view.lastCommandOutput(), "no buffer, no output");
            assertEquals(0, view.queryAccessibleAttribute(AccessibleAttribute.CARET_OFFSET));
            assertEquals(Boolean.FALSE, view.queryAccessibleAttribute(AccessibleAttribute.EDITABLE));
            return null;
        });
    }

    @Test
    void afterTheShellEndsKeysGoNowhere() throws Exception {
        open();
        fx(() -> {
            view.getSession().close();
            return null;
        });
        assertFalse(fx(() -> view.getSession().isRunning()), "the shell ended");
        connector.clearWritten();
        fx(() -> {
            Event.fireEvent(view, typed("a"));
            Event.fireEvent(view, pressed(KeyCode.ENTER, false));
            return null;
        });
        assertEquals("", connector.written());
    }

    @Test
    void whileAltGrIsHeldCtrlAltComposesAndAfterItIsAChord() throws Exception {
        Assumptions.assumeTrue(com.editora.Os.isLinux(), "Linux tells AltGr from Ctrl+Alt");
        open();
        fx(() -> {
            Event.fireEvent(
                    view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ALT_GRAPH, false, false, false, false));
            Event.fireEvent(view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "e", KeyCode.E, false, true, true, false));
            Event.fireEvent(
                    view, new KeyEvent(KeyEvent.KEY_TYPED, "€", "", KeyCode.UNDEFINED, false, true, true, false));
            Event.fireEvent(
                    view, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.ALT_GRAPH, false, false, false, false));
            Event.fireEvent(view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "e", KeyCode.E, false, true, true, false));
            return null;
        });
        await(
                () -> connector.written().equals("€\u001b\u0005"),
                "the euro sign, then C-M-e: " + visible(connector.written()));
    }

    @Test
    void aKeyWithoutAltClearsAnAltGrWhoseReleaseWasMissed() throws Exception {
        Assumptions.assumeTrue(com.editora.Os.isLinux(), "Linux tells AltGr from Ctrl+Alt");
        open();
        fx(() -> {
            Event.fireEvent(
                    view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ALT_GRAPH, false, false, false, false));
            Event.fireEvent(view, pressed(KeyCode.A, true));
            Event.fireEvent(view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "e", KeyCode.E, false, true, true, false));
            return null;
        });
        await(
                () -> connector.written().equals("\u0001\u001b\u0005"),
                "C-a, then C-M-e: " + visible(connector.written()));
    }

    @Test
    void underTheKittyProtocolReleasesAreReportedAndTextGoesAsItIs() throws Exception {
        open();
        connector.emit("\u001b[>11u\u001b[?u");
        await(() -> connector.written().contains("\u001b[?11u"), "all keys and event types in force");
        connector.clearWritten();
        fx(() -> {
            Event.fireEvent(view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "e", KeyCode.E, false, false, false, false));
            Event.fireEvent(view, typed("e"));
            Event.fireEvent(view, new KeyEvent(KeyEvent.KEY_RELEASED, "", "e", KeyCode.E, false, false, false, false));
            Event.fireEvent(view, typed("é"));
            return null;
        });
        await(
                () -> connector.written().equals("\u001b[101u\u001b[101;1:3ué"),
                "press, release, then the composed text: " + visible(connector.written()));
    }

    @Test
    void theScreenReadersCaretIsTheCursorInTheRowsInView() throws Exception {
        open();
        connector.emit("ab\r\ncd");
        await(() -> screen().contains("cd"), "two rows");
        await(
                () -> Integer.valueOf(5)
                        .equals(call(() -> view.queryAccessibleAttribute(AccessibleAttribute.CARET_OFFSET))),
                "after ab and its line break, two into cd");
        StringBuilder lines = new StringBuilder("\r\n");
        for (int i = 0; i < 100; i++) lines.append("line ").append(i).append("\r\n");
        connector.emit(lines.toString());
        await(() -> screen().contains("line 99"), "history");
        assertEquals(
                0,
                (int) fx(() -> {
                    Event.fireEvent(
                            view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.HOME, true, false, false, false));
                    return view.queryAccessibleAttribute(AccessibleAttribute.CARET_OFFSET);
                }),
                "the cursor is out of view");
    }

    @Test
    void copyingTheLastOutputNeedsItsMarks() throws Exception {
        open();
        connector.emit("plain\r\n");
        await(() -> screen().startsWith("plain"), "output without marks");
        assertFalse(fx(() -> view.copyLastOutput()), "no marks, nothing to copy");

        connector.emit(
                "\u001b]133;A\u0007$ ls\r\n\u001b]133;C\u0007one\r\ntwo\r\n\u001b]133;D;0\u0007\u001b]133;A\u0007$ ");
        await(() -> call(() -> view.lastCommandOutput()) != null, "the marks");
        assertTrue(fx(() -> view.copyLastOutput()));
        await(
                () -> "one\ntwo\n"
                        .equals(call(() -> Clipboard.getSystemClipboard().getString())),
                "the output on the clipboard");
    }

    @Test
    void aCommandStillRunningHasItsOutputUpToTheCursorAndAnEmptyOneHasNone() throws Exception {
        open();
        connector.emit("\u001b]133;A\u0007$ quiet\r\n\u001b]133;C\u0007\u001b]133;D;0\u0007\u001b]133;A\u0007$ ");
        awaitFx(() -> view.hasPromptMarks(), "the marks");
        await(() -> screen().contains("$ quiet"), "the prompt");
        assertNull(fx(() -> view.lastCommandOutput()), "a command that printed nothing");

        connector.emit("sleep\r\n\u001b]133;C\u0007working\r\nstill\r\n");
        await(
                () -> "working\nstill\n".equals(call(() -> view.lastCommandOutput())),
                "a running command's output so far: " + call(() -> view.lastCommandOutput()));
    }

    @Test
    void withNoQueryThereIsNothingToFindOrStepTo() throws Exception {
        fx(() -> {
            view = new TerminalView(13);
            assertEquals(TerminalView.SearchResult.NONE, view.search("x", false), "no buffer yet");
            return null;
        });
        fx(() -> {
            view.close();
            return null;
        });
        view = null;
        open();
        connector.emit("needle\r\n");
        await(() -> screen().contains("needle"), "the output");
        java.util.List<TerminalView.SearchResult> got = new java.util.concurrent.CopyOnWriteArrayList<>();
        fx(() -> {
            assertEquals(TerminalView.SearchResult.NONE, view.findNext(true), "no search yet");
            view.findNextAsync(true, got::add);
            assertEquals(TerminalView.SearchResult.NONE, view.search("", false));
            assertEquals(TerminalView.SearchResult.NONE, view.search(null, false));
            view.searchAsync("", false, got::add);
            return null;
        });
        assertEquals(java.util.List.of(TerminalView.SearchResult.NONE, TerminalView.SearchResult.NONE), got);
        assertEquals(new TerminalView.SearchResult(0, 0), fx(() -> view.search("absent", false)));
        assertEquals(new TerminalView.SearchResult(0, 0), fx(() -> view.findNext(false)), "no hits to step to");
    }

    @Test
    void steppingAfterNewOutputFindsAgainFirst() throws Exception {
        open();
        connector.emit("needle one\r\n");
        await(() -> screen().contains("needle one"), "the output");
        assertEquals(new TerminalView.SearchResult(1, 1), fx(() -> view.search("needle", false)));
        java.util.List<TerminalView.SearchResult> got = new java.util.concurrent.CopyOnWriteArrayList<>();
        fx(() -> {
            view.findNextAsync(false, got::add);
            return null;
        });
        assertEquals(java.util.List.of(new TerminalView.SearchResult(1, 1)), got, "nothing new: a step at once");

        connector.emit("needle two\r\n");
        await(() -> screen().contains("needle two"), "more output");
        await(() -> Integer.valueOf(2).equals(call(() -> view.findNext(true).count())), "found again: two hits");

        connector.emit("needle three\r\n");
        await(() -> screen().contains("needle three"), "still more");
        got.clear();
        fx(() -> {
            view.findNextAsync(false, got::add);
            return null;
        });
        await(() -> !got.isEmpty(), "the async step");
        assertEquals(3, got.getFirst().count(), "found again off the FX thread before stepping");
    }

    @Test
    void theCursorIsPaintedInTheShapeAskedFor() throws Exception {
        open();
        TerminalPalette palette = TerminalPalettes.EDITORA_LIGHT;
        fx(() -> {
            view.setPalette(palette);
            view.setPreferredCursor(TerminalCursor.BLOCK);
            view.requestFocus();
            return null;
        });
        Assumptions.assumeTrue(fx(view::isFocused), "the view can hold focus here");
        double cw = fx(view::cellWidth);
        double lh = fx(view::lineHeightForTest);
        Color fg = palette.foreground();

        // A block on an empty cell fills it.
        awaitPixel(cw / 2, lh / 2, fg, "a block cursor");

        connector.emit("\u001b[4 q");
        awaitPixel(cw / 2, lh - 1, fg, "an underline at the foot of the cell");
        awaitNotPixel(cw / 2, lh / 3, fg, "and nothing above it");

        connector.emit("\u001b[6 q");
        awaitPixel(0.5, lh / 2, fg, "a bar at the left edge");
        awaitNotPixel(cw - 1, lh / 2, fg, "and nothing to its right");

        fx(() -> {
            view.setPreferredCursor(TerminalCursor.UNDERLINE);
            return null;
        });
        connector.emit("\u001b[0 q");
        awaitPixel(cw / 2, lh - 1, fg, "the user's underline once the program lets go");

        connector.emit("\u001b[2 q\u001b[1mW\u001b[0m\u001b[D");
        awaitPixel(0.5, 0.5, fg, "a block over a bold letter, its corner still the cursor's");

        connector.emit("\r\n\ud83d\ude00\u001b[2D");
        if (!com.editora.Os.isMac()) { // Apple Color Emoji paints its own pixels over the cursor's there
            awaitPixel(cw * 1.5, 1, fg, "a wide glyph's cursor covers both its cells", lh);
        }
    }

    @Test
    void aDoubleClickSelectsTheWordAndTripleTheLine() throws Exception {
        open();
        connector.emit("hello brave world\r\n");
        await(() -> screen().contains("world"), "the output");
        double cw = fx(view::cellWidth);
        double lh = fx(view::lineHeightForTest);
        fx(() -> {
            Event.fireEvent(
                    view, mouse(javafx.scene.input.MouseEvent.MOUSE_PRESSED, cw * 7.5, lh / 2, MouseButton.PRIMARY, 2));
            return null;
        });
        await(() -> "brave".equals(call(view::selectedText)), "the word under the pointer");
        fx(() -> {
            Event.fireEvent(
                    view, mouse(javafx.scene.input.MouseEvent.MOUSE_PRESSED, cw * 2.5, lh / 2, MouseButton.PRIMARY, 3));
            return null;
        });
        await(() -> String.valueOf(call(view::selectedText)).startsWith("hello brave world"), "the whole line");
        fx(() -> {
            Event.fireEvent(
                    view,
                    mouse(javafx.scene.input.MouseEvent.MOUSE_PRESSED, cw * 2.5, lh / 2, MouseButton.SECONDARY, 1));
            return null;
        });
        assertTrue(fx(view::selectedText).startsWith("hello brave world"), "a right press leaves the selection");
    }

    @Test
    void aProgramThatReportsTheMouseGetsThePress() throws Exception {
        open();
        connector.emit("\u001b[?1000h\u001b[?1006h");
        await(
                () -> call(() -> view.getDisplay().getMouseMode()) != null
                        && call(() -> view.getDisplay().getMouseMode())
                                != com.jediterm.terminal.emulator.mouse.MouseMode.MOUSE_REPORTING_NONE,
                "mouse reporting on");
        double cw = fx(view::cellWidth);
        double lh = fx(view::lineHeightForTest);
        fx(() -> {
            Event.fireEvent(
                    view,
                    mouse(javafx.scene.input.MouseEvent.MOUSE_PRESSED, cw * 3.5, lh * 1.5, MouseButton.PRIMARY, 1));
            return null;
        });
        await(
                () -> connector.written().contains("\u001b[<0;4;2M"),
                "an SGR press at column 4, row 2: " + visible(connector.written()));
    }

    @Test
    void aLinkWhoseTextHidesItsAddressShowsTheAddressOnHover() throws Exception {
        open();
        java.util.List<String> opened = links();
        connector.emit("go \u001b]8;;https://example.com/real\u0007click here\u001b]8;;\u0007 now\r\n"
                + "plain https://example.org/shown\r\n");
        await(() -> screen().contains("click here"), "the output");
        double cw = fx(view::cellWidth);
        double lh = fx(view::lineHeightForTest);

        move(cw * 6.5, lh / 2, true);
        await(() -> "https://example.com/real".equals(call(view::linkTipText)), "the address in a tip");
        move(cw * 8.5, lh / 2, true);
        assertEquals("https://example.com/real", fx(view::linkTipText), "still the same link: the same tip");

        move(cw * 10.5, lh * 1.5, true);
        await(() -> call(view::linkTipText) == null, "a URL that shows its address has no tip");
        move(cw * 10.5, lh * 1.5, false);
        move(cw * 1.5, lh / 2, true);
        assertNull(fx(view::linkTipText), "no link under the pointer");

        fx(() -> {
            Event.fireEvent(view, ctrlClick(cw * 7.5, lh / 2));
            return null;
        });
        assertEquals(java.util.List.of("url https://example.com/real"), opened, "the address opens, not the text");
        fx(() -> {
            Event.fireEvent(view, ctrlClick(-5, lh / 2));
            Event.fireEvent(view, ctrlClick(cw * 2.5, lh * 30));
            return null;
        });
        assertEquals(1, opened.size(), "nothing outside the cells");
    }

    @Test
    void aUrlWrappedOverTwoRowsIsOneLink() throws Exception {
        open();
        java.util.List<String> opened = links();
        int columns = fx(() -> view.getSession().getTerminal().getSize().getColumns());
        String url = "https://example.com/" + "a".repeat(columns);
        connector.emit("x " + url + "\r\n");
        await(() -> screen().contains("aaaa"), "the output");
        double cw = fx(view::cellWidth);
        double lh = fx(view::lineHeightForTest);
        fx(() -> {
            Event.fireEvent(view, ctrlClick(cw * 3.5, lh * 1.5));
            return null;
        });
        assertEquals(java.util.List.of("url " + url), opened, "clicked on its second row");
    }

    @Test
    void aPathLinkOpensOnceItIsFoundOnDisk(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        Assumptions.assumeFalse(com.editora.Os.isWindows(), "a POSIX shell's directory report");
        open();
        java.util.List<String> opened = links();
        java.nio.file.Files.writeString(dir.resolve("notes.txt"), "x");
        connector.emit("\u001b]7;file://" + dir.toUri().getRawPath() + "\u0007missing.txt notes.txt:3:4\r\n");
        await(() -> screen().contains("notes.txt"), "the output");
        double cw = fx(view::cellWidth);
        double lh = fx(view::lineHeightForTest);
        fx(() -> {
            Event.fireEvent(view, ctrlClick(cw * 14.5, lh / 2));
            Event.fireEvent(view, ctrlClick(cw * 2.5, lh / 2));
            return null;
        });
        await(() -> opened.size() == 1, "the click acted on once the disk answered");
        assertEquals(dir.resolve("notes.txt") + ":3:4", opened.getFirst());
        move(cw * 14.5, lh / 2, true);
        await(() -> javafx.scene.Cursor.HAND.equals(call(view::getCursor)), "a hand over a file that is there");
        move(cw * 2.5, lh / 2, true);
        await(() -> javafx.scene.Cursor.DEFAULT.equals(call(view::getCursor)), "no hand over one that is not");
    }

    // ---------------------------------------------------------------- helpers

    /** Presses {@code code} at the view and returns where the view is scrolled to afterwards. */
    private int press(KeyCode code, boolean shift, boolean ctrl, boolean alt, boolean meta) throws Exception {
        return fx(() -> {
            Event.fireEvent(view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, meta));
            return view.scrollOrigin();
        });
    }

    private void type(String text) throws Exception {
        fx(() -> {
            Event.fireEvent(view, typed(text));
            return null;
        });
    }

    private static String visible(String s) {
        return s.replace("\u001b", "\\e");
    }

    private void awaitPixel(double x, double y, Color want, String what) throws Exception {
        awaitPixel(x, y, want, what, 0);
    }

    /** Waits until the view's pixel at {@code x}, {@code y} (plus {@code rowOffset}) is close to {@code want}. */
    private void awaitPixel(double x, double y, Color want, String what, double rowOffset) throws Exception {
        await(() -> near(pixel(x, y + rowOffset), want), what);
    }

    private void awaitNotPixel(double x, double y, Color unwanted, String what) throws Exception {
        await(() -> !near(pixel(x, y), unwanted), what);
    }

    private Color pixel(double x, double y) {
        return call(() -> view.snapshot(null, null).getPixelReader().getColor((int) x, (int) y));
    }

    private static boolean near(Color got, Color want) {
        return got != null
                && Math.abs(got.getRed() - want.getRed()) < 0.12
                && Math.abs(got.getGreen() - want.getGreen()) < 0.12
                && Math.abs(got.getBlue() - want.getBlue()) < 0.12;
    }

    private static javafx.scene.input.MouseEvent mouse(
            javafx.event.EventType<javafx.scene.input.MouseEvent> type,
            double x,
            double y,
            MouseButton button,
            int clicks) {
        return new javafx.scene.input.MouseEvent(
                type,
                x,
                y,
                x,
                y,
                button,
                clicks,
                false,
                false,
                false,
                false,
                button == MouseButton.PRIMARY,
                button == MouseButton.MIDDLE,
                button == MouseButton.SECONDARY,
                false,
                false,
                true,
                null);
    }

    private java.util.List<String> links() throws Exception {
        java.util.List<String> opened = new java.util.concurrent.CopyOnWriteArrayList<>();
        fx(() -> {
            view.setLinkActions(new LinkActions() {
                @Override
                public void openUrl(String url) {
                    opened.add("url " + url);
                }

                @Override
                public void openFile(java.nio.file.Path f, int line, int column) {
                    opened.add(f + ":" + line + ":" + column);
                }
            });
            return null;
        });
        return opened;
    }

    /** A move with the link gesture's modifier down when {@code ctrl}: Ctrl, or Cmd on macOS. */
    private void move(double x, double y, boolean ctrl) throws Exception {
        boolean mac = com.editora.Os.isMac();
        fx(() -> {
            Event.fireEvent(
                    view,
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_MOVED,
                            x,
                            y,
                            x,
                            y,
                            MouseButton.NONE,
                            0,
                            false,
                            ctrl && !mac,
                            false,
                            ctrl && mac,
                            false,
                            false,
                            false,
                            false,
                            false,
                            true,
                            null));
            return null;
        });
    }

    private static javafx.scene.input.MouseEvent ctrlClick(double x, double y) {
        boolean mac = com.editora.Os.isMac();
        return new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                x,
                y,
                x,
                y,
                MouseButton.PRIMARY,
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
                true,
                null);
    }

    private void open() throws Exception {
        connector = new FakeConnector();
        fx(() -> {
            view = new TerminalView(13);
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 800, 400));
            stage.show();
            view.getScene().getRoot().layout();
            view.start((columns, rows) -> connector);
            return null;
        });
    }

    /** The screen's text. Throws when it cannot be read, so a negative check cannot pass on a failure. */
    private String screen() {
        try {
            return fx(() -> {
                var buffer = view.getSession().getTextBuffer();
                buffer.lock();
                try {
                    return buffer.getScreenLines();
                } finally {
                    buffer.unlock();
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("the screen could not be read", e);
        }
    }

    private static KeyEvent typed(String character) {
        return new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, false, false, false, false);
    }

    private static KeyEvent pressed(KeyCode code, boolean control) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false);
    }

    private static <T> T call(Callable<T> work) {
        try {
            return fx(work);
        } catch (Exception e) {
            return null;
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
        Await.until(condition, what, java.time.Duration.ofSeconds(10));
    }

    private static void awaitFx(Callable<Boolean> condition, String what) throws InterruptedException {
        Await.until(() -> Boolean.TRUE.equals(call(condition)), what, java.time.Duration.ofSeconds(10));
    }
}
