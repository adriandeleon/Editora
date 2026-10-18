package com.editora.terminal;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TerminalColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The view end to end over an in-memory connector: what the "shell" prints reaches the screen buffer with its
 * colours, keys reach the shell as the bytes a terminal sends, a layout change resizes the shell, and the
 * window's menu items join the view's own. No process, so it runs the same on every OS.
 */
@Tag("fx")
class TerminalViewFxTest {

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
    void outputReachesTheScreenWithItsColours() throws Exception {
        open();
        connector.emit("hello \u001b[31mred\u001b[0m\r\n");

        await(() -> screen().startsWith("hello red"), "the output on the first row");
        TerminalColor red = fx(() -> {
            var buffer = view.getSession().getTextBuffer();
            buffer.lock();
            try {
                return buffer.getStyleAt(6, 0).getForeground();
            } finally {
                buffer.unlock();
            }
        });
        assertEquals(TerminalColor.index(1), red);
    }

    @Test
    void theShellStartsAtTheSizeTheViewLaidOut() throws Exception {
        open();
        TermSize opened = connector.openedAt;
        assertNotNull(opened);
        assertTrue(opened.getColumns() >= 20 && opened.getRows() >= 4, () -> "opened at " + opened);
    }

    @Test
    void typedKeysReachTheShellAsTerminalBytes() throws Exception {
        open();
        fx(() -> {
            Event.fireEvent(view, typed("l"));
            Event.fireEvent(view, typed("s"));
            Event.fireEvent(view, pressed(KeyCode.ENTER, false));
            Event.fireEvent(view, pressed(KeyCode.C, true));
            return null;
        });

        await(() -> connector.written().equals("ls\r\u0003"), "ls, Enter and Ctrl+C on the wire");
    }

    @Test
    void aSmallerViewResizesTheShell() throws Exception {
        open();
        int before = fx(() -> view.getSession().getTerminal().getSize().getColumns());
        fx(() -> {
            stage.setWidth(stage.getWidth() / 2);
            view.getScene().getRoot().layout();
            return null;
        });

        await(
                () -> connector.lastResize.get() != null
                        && connector.lastResize.get().getColumns() < before,
                "a resize to fewer than " + before + " columns");
    }

    @Test
    void aRunOfSizeChangesResizesTheShellOnceItSettles() throws Exception {
        // A splitter drag: every column change used to reflow the whole scrollback, under the lock painting needs.
        open();
        await(() -> connector.resizes.get() >= 0, "the start");
        Thread.sleep(300);
        int before = connector.resizes.get();
        fx(() -> {
            for (int size = 14; size <= 18; size++) {
                view.setFontSize(size);
                view.getScene().getRoot().layout();
            }
            return null;
        });
        await(() -> connector.resizes.get() > before, "the resize once the size held still");
        Thread.sleep((long) TerminalView.RESIZE_SETTLE.toMillis() + 300);
        assertEquals(before + 1, connector.resizes.get(), "one resize for the whole run of changes");
    }

    @Test
    void paintingDoesNotWaitForTheEmulatorsLock() throws Exception {
        open();
        var buffer = fx(() -> view.getSession().getTextBuffer());
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> {
            buffer.lock(); // what a reflow of a long scrollback or a flood of output does
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                buffer.unlock();
            }
        });
        held.await();
        try {
            long t0 = System.nanoTime();
            fx(() -> {
                view.requestLayout();
                view.layout(); // paints
                return null;
            });
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(ms < 1_000, "the FX thread skipped the frame instead of waiting: " + ms + " ms");
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void theFindBarsSearchRunsOffTheFxThread() throws Exception {
        open();
        connector.emit("needle one\r\nhay\r\nneedle two\r\n");
        await(() -> screen().contains("needle two"), "the output");
        var buffer = fx(() -> view.getSession().getTextBuffer());
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> {
            buffer.lock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                buffer.unlock();
            }
        });
        held.await();
        CompletableFuture<TerminalView.SearchResult> found = new CompletableFuture<>();
        try {
            long t0 = System.nanoTime();
            fx(() -> {
                view.searchAsync("needle", false, found::complete);
                return null;
            });
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 1_000, "the FX thread did not scan");
            assertFalse(found.isDone());
        } finally {
            release.countDown();
            holder.join();
        }
        assertEquals(new TerminalView.SearchResult(2, 2), found.get(10, TimeUnit.SECONDS));
    }

    @Test
    void anEmojiTakesTheTwoColumnsTheShellCountsAndTheCursorStaysOnItsText() throws Exception {
        open();
        // What bash's line editor does after `echo 😀😀 end` and moving left three columns: its cursor is on the e.
        connector.emit("echo \uD83D\uDE00\uD83D\uDE00 end\u001b[3D");
        await(() -> screen().contains("end"), "the line");
        String text = fx(() -> {
            var buffer = view.getSession().getTextBuffer();
            buffer.lock();
            try {
                return buffer.getLine(0).getText();
            } finally {
                buffer.unlock();
            }
        });
        int slot = fx(() -> view.getDisplay().getCursorX());
        assertEquals(10, ColumnMap.columnAt(text, slot), "the cursor's column is the shell's");
        assertEquals('e', text.charAt(slot), "and it sits on the e");
        assertEquals(0, TerminalView.glyphAt(text, 6), "never half an emoji");
        assertEquals(0x1F600, TerminalView.glyphAt(text, 5));
    }

    @Test
    void colourQueriesAreAnsweredWithThePalette() throws Exception {
        // vim, neovim, delta and bat ask, to tell a light terminal from a dark one.
        open();
        fx(() -> {
            view.setPalette(TerminalPalettes.EDITORA_LIGHT);
            return null;
        });
        connector.emit("\u001b]11;?\u0007\u001b]10;?\u0007");
        await(() -> connector.written().contains("]11;rgb:") && connector.written().contains("]10;rgb:"), "replies");
    }

    @Test
    void theFirstLineIntoHistoryDoesNotResizeTheShell() throws Exception {
        open();
        Thread.sleep(300);
        int before = connector.resizes.get();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 80; i++) lines.append("line ").append(i).append("\r\n");
        connector.emit(lines.toString());
        await(() -> screen().contains("line 79"), "the output");
        fx(() -> {
            view.getScene().getRoot().layout();
            view.flushResize();
            return null;
        });
        Thread.sleep(200);
        assertEquals(before, connector.resizes.get(), "the scrollbar appearing kept the column count");
    }

    @Test
    void aTrackpadsSmallDeltasAddUpInsteadOfEachScrollingThreeLines() throws Exception {
        open();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 200; i++) lines.append("line ").append(i).append("\r\n");
        connector.emit(lines.toString());
        await(() -> screen().contains("line 199"), "the output");
        int origin = fx(() -> {
            for (int i = 0; i < 10; i++) Event.fireEvent(view, scroll(1, 0.075));
            return view.scrollOrigin();
        });
        assertTrue(origin >= -1, "ten one-pixel events are less than a line or two: " + origin);
        int notch = fx(() -> {
            Event.fireEvent(view, scroll(40, 3));
            return view.scrollOrigin();
        });
        assertEquals(origin - 3, notch, "a wheel notch is still three lines");
    }

    @Test
    void draggingASelectionPastTheTopScrollsIntoHistory() throws Exception {
        open();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 200; i++) lines.append("line ").append(i).append("\r\n");
        connector.emit(lines.toString());
        await(() -> screen().contains("line 199"), "the output");
        fx(() -> {
            Event.fireEvent(view, mouse(javafx.scene.input.MouseEvent.MOUSE_PRESSED, 20, 40));
            Event.fireEvent(view, mouse(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, 20, -30));
            return null;
        });
        assertTrue(fx(view::isDragScrolling));
        await(() -> call(view::scrollOrigin) < -3, "the view scrolling up while the pointer is above it");
        fx(() -> {
            Event.fireEvent(view, mouse(javafx.scene.input.MouseEvent.MOUSE_RELEASED, 20, -30));
            return null;
        });
        assertFalse(fx(view::isDragScrolling));
        assertTrue(fx(() -> view.selectedText().contains("line")), "the selection reaches into history");
    }

    @Test
    void aProgramsDefaultCursorRequestGivesTheUsersShapeBack() throws Exception {
        open();
        connector.emit("\u001b[6 q");
        await(() -> call(() -> view.getDisplay().getCursorShape()) == com.jediterm.terminal.CursorShape.STEADY_VERTICAL_BAR,
                "vim's bar");
        connector.emit("\u001b[0 q");
        await(() -> call(() -> view.getDisplay().getCursorShape()) == null, "back to the user's preference");
    }

    private static javafx.scene.input.ScrollEvent scroll(double deltaY, double textDeltaY) {
        return new javafx.scene.input.ScrollEvent(
                javafx.scene.input.ScrollEvent.SCROLL,
                50, 50, 50, 50,
                false, false, false, false, false, false,
                0, deltaY, 0, deltaY,
                javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.LINES, textDeltaY,
                0, null);
    }

    private static javafx.scene.input.MouseEvent mouse(
            javafx.event.EventType<javafx.scene.input.MouseEvent> type, double x, double y) {
        return new javafx.scene.input.MouseEvent(
                type, x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null);
    }

    @Test
    void anIdleTerminalStopsThePulseAndOutputStartsItAgain() throws Exception {
        open();
        connector.emit("hello\r\n");
        await(() -> screen().contains("hello"), "the output");
        await(() -> !call(view::isPainting), "the painter stopping once there is nothing to paint");
        connector.emit("more\r\n");
        await(() -> call(view::isPainting) || screen().contains("more"), "output wakes it");
        await(() -> !call(view::isPainting), "and it stops again");
    }

    @Test
    void selectAllSelectsAndTypingDropsTheSelection() throws Exception {
        open();
        connector.emit("one\r\ntwo\r\n");
        await(() -> screen().contains("two"), "the output");

        assertTrue(fx(() -> {
            view.selectAll();
            return view.hasSelection();
        }));
        assertFalse(fx(() -> {
            Event.fireEvent(view, typed("x"));
            return view.hasSelection();
        }));
    }

    @Test
    void theWindowsItemsFollowTheViewsOwn() throws Exception {
        open();
        AtomicInteger asked = new AtomicInteger();
        MenuItem extra = new MenuItem("New Terminal");
        fx(() -> {
            view.setMenuExtras(() -> {
                asked.incrementAndGet();
                return List.of(extra);
            });
            Event.fireEvent(
                    view, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 10, 10, false, null));
            return null;
        });

        assertEquals(1, asked.get());
        assertNotNull(fx(extra::getParentPopup), "the extra item was added to the view's menu");
        assertTrue(
                fx(() -> extra.getParentPopup().getItems().stream()
                        .anyMatch(item -> item.getAccelerator() != null && item.getAccelerator().getName().contains("C"))),
                "Copy shows its chord");
        fx(() -> {
            extra.getParentPopup().hide();
            Event.fireEvent(
                    view, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 10, 10, false, null));
            return null;
        });
        assertEquals(2, asked.get(), "asked again each time the menu opens");
        assertEquals(1, fx(() -> (int) extra.getParentPopup().getItems().stream()
                .filter(item -> item == extra)
                .count()));
    }

    @Test
    void theShellEndingIsReported() throws Exception {
        open();
        CountDownLatch ended = new CountDownLatch(1);
        fx(() -> {
            view.setOnSessionEnded(ended::countDown);
            return null;
        });
        connector.endOfOutput();

        assertTrue(ended.await(10, TimeUnit.SECONDS), "onSessionEnded after the connector closed");
    }

    @Test
    void aShellsCwdReportBecomesTheTerminalsDirectory() throws Exception {
        open();
        // Another machine's report (a remote prompt after `ssh`, a printed file) does not move a local tab.
        connector.emit("\u001b]7;file://elsewhere.example/etc\u0007");
        connector.emit("\u001b]7;file://localhost/srv/my%20app\u0007$ ");
        await(() -> "/srv/my app".equals(call(() -> view.getDisplay().getCwd())), "the reported directory");
        await(
                () -> "my app"
                        .equals(call(() -> view.getDisplay().tabTitleProperty().get())),
                "the tab named after it");
        assertFalse(screen().contains("]7;"), "the report is not drawn");
    }

    @Test
    void theLastCommandsOutputIsWhatCameBetweenItsMarks() throws Exception {
        open();
        connector.emit("\u001b]133;A\u0007$ ls\r\n\u001b]133;C\u0007one\r\ntwo  \r\n\u001b]133;D;0\u0007"
                + "\u001b]133;A\u0007$ ");
        await(() -> call(() -> view.lastCommandOutput()) != null, "the output marks");
        assertEquals("one\ntwo\n", fx(() -> view.lastCommandOutput()));
        assertTrue(fx(() -> view.hasPromptMarks()));
        assertFalse(screen().contains("133"), "the marks are not drawn");
    }

    @Test
    void promptJumpsScrollBackThroughHistoryAndReturn() throws Exception {
        open();
        StringBuilder out = new StringBuilder("\u001b]133;A\u0007$ first\r\n");
        for (int i = 0; i < 80; i++) {
            out.append("line ").append(i).append("\r\n");
        }
        out.append("\u001b]133;A\u0007$ ");
        connector.emit(out.toString());
        await(() -> screen().contains("line 79"), "the output");

        int history = fx(() -> view.getSession().getTextBuffer().getHistoryLinesCount());
        assertTrue(history > 0, "the first prompt scrolled off the screen");
        int firstRow = fx(() -> view.promptMarks().getFirst().row());
        assertTrue(firstRow < 0, () -> "its mark moved into history with it: " + firstRow);
        String firstLine = fx(() -> {
            var buffer = view.getSession().getTextBuffer();
            buffer.lock();
            try {
                return buffer.getLine(firstRow).getText();
            } finally {
                buffer.unlock();
            }
        });
        assertTrue(firstLine.startsWith("$ first"), () -> "the mark is still on its prompt: " + firstLine);

        assertTrue(fx(() -> view.previousPrompt()), "a prompt above");
        assertEquals(firstRow, fx(() -> view.scrollOrigin()), "scrolled to it");
        assertTrue(fx(() -> view.nextPrompt()), "and back down");
        assertFalse(fx(() -> view.nextPrompt()), "already at the live screen");
    }

    @Test
    void aScrolledBackViewStaysOnItsTextAsOutputArrives() throws Exception {
        // JediTerm's history listener fires on resizes and clears only, not as lines scroll off.
        open();
        StringBuilder out = new StringBuilder("\u001b]133;A\u0007$ first\r\n");
        for (int i = 0; i < 80; i++) {
            out.append("line ").append(i).append("\r\n");
        }
        connector.emit(out.toString());
        await(() -> screen().contains("line 79"), "the output");
        assertTrue(fx(() -> view.previousPrompt()), "scrolled back to the prompt");
        int before = fx(() -> view.scrollOrigin());

        StringBuilder more = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            more.append("more ").append(i).append("\r\n");
        }
        connector.emit(more.toString());
        await(() -> screen().contains("more 9"), "more output");

        assertEquals(before - 10, fx(() -> view.scrollOrigin()), "the view moved up with its text");
        assertEquals(before - 10, fx(() -> view.promptMarks().getFirst().row()), "and so did the mark");
    }

    @Test
    void searchFindsHitsInHistoryStartsAtTheLatestAndStepsUpThroughThem() throws Exception {
        open();
        StringBuilder out = new StringBuilder("needle zero\r\n");
        for (int i = 0; i < 60; i++) {
            out.append("line ").append(i).append(i == 30 ? " needle" : "").append("\r\n");
        }
        out.append("Needle last\r\n");
        connector.emit(out.toString());
        await(() -> screen().contains("Needle last"), "the output");

        TerminalView.SearchResult first = fx(() -> view.search("needle", false));
        assertEquals(new TerminalView.SearchResult(3, 3), first, "the latest of three, counted from the top");
        assertEquals(0, fx(() -> view.scrollOrigin()), "already in view: no scroll");

        TerminalView.SearchResult older = fx(() -> view.findNext(true));
        assertEquals(2, older.index());
        int row = fx(() -> view.searchMatches().get(1).row());
        assertTrue(row < 0, "the middle hit is in history");
        int origin = fx(() -> view.scrollOrigin());
        assertTrue(origin <= row && row < origin + 10, () -> "scrolled to it: origin " + origin + ", row " + row);

        assertEquals(1, fx(() -> view.findNext(true)).index());
        assertEquals(3, fx(() -> view.findNext(true)).index(), "wraps to the latest");
        assertEquals(1, fx(() -> view.findNext(false)).index(), "and down wraps to the oldest");
        assertEquals(1, fx(() -> view.search("Needle", true)).count(), "match case");
        assertEquals(new TerminalView.SearchResult(0, 0), fx(() -> view.search("absent", false)));
    }

    @Test
    void searchHitsMoveWithTheirTextAsOutputArrives() throws Exception {
        open();
        connector.emit("needle\r\n");
        await(() -> screen().contains("needle"), "the output");
        int before = fx(() -> {
            view.search("needle", false);
            return view.searchMatches().getFirst().row();
        });
        StringBuilder more = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            more.append("more ").append(i).append("\r\n");
        }
        connector.emit(more.toString());
        await(() -> screen().contains("more 69"), "more output");

        int after = fx(() -> view.searchMatches().getFirst().row());
        String text = fx(() -> {
            var buffer = view.getSession().getTextBuffer();
            buffer.lock();
            try {
                return buffer.getLine(after).getText();
            } finally {
                buffer.unlock();
            }
        });
        assertTrue(after < before, "moved up into history");
        assertTrue(text.startsWith("needle"), () -> "still on its text: " + text);
        fx(() -> {
            view.clearSearch();
            return null;
        });
        assertTrue(fx(() -> view.searchMatches().isEmpty()));
    }

    @Test
    void overASlowLinkTypingIsShownBeforeTheEchoAndReplacedByIt() throws Exception {
        open();
        fx(() -> {
            view.setTypeAhead(true);
            return null;
        });
        connector.emit("$ ");
        await(() -> screen().startsWith("$ "), "the prompt");
        // Two keystrokes whose echo takes 150 ms: the manager measures the link as slow.
        for (String key : java.util.List.of("a", "b")) {
            type(key);
            Thread.sleep(150);
            connector.emit(key);
            String typed = "$ " + (key.equals("a") ? "a" : "ab");
            await(() -> screen().startsWith(typed), "the echo of " + key);
        }

        type("c");
        await(() -> "$ abc".equals(call(this::predictedText)), "c shown before its echo");
        assertFalse(screen().startsWith("$ abc"), "the shell has not echoed it yet");

        connector.emit("c");
        await(() -> screen().startsWith("$ abc"), "the echo of c");
        await(() -> call(this::predictedText) == null, "the prediction gives way to the echo");
    }

    @Test
    void overAFastLinkTypeAheadStaysOutOfSight() throws Exception {
        open();
        fx(() -> {
            view.setTypeAhead(true);
            return null;
        });
        connector.emit("$ ");
        await(() -> screen().startsWith("$ "), "the prompt");
        StringBuilder typed = new StringBuilder("$ ");
        for (String key : java.util.List.of("a", "b", "c", "d")) {
            type(key);
            connector.emit(key); // echoed at once, as a local shell does
            typed.append(key);
            String now = typed.toString();
            await(() -> screen().startsWith(now), "the echo of " + key);
            assertEquals(null, call(this::predictedText), "nothing predicted after " + key);
        }
    }

    @Test
    void withTypeAheadOffNothingIsPredicted() throws Exception {
        open();
        connector.emit("$ ");
        await(() -> screen().startsWith("$ "), "the prompt");
        for (String key : java.util.List.of("a", "b", "c")) {
            type(key);
            Thread.sleep(150);
            assertEquals(null, call(this::predictedText));
            connector.emit(key);
        }
    }

    private void type(String text) throws Exception {
        fx(() -> {
            view.getSession().sendString(text);
            return null;
        });
    }

    private String predictedText() {
        var buffer = view.getSession().getTextBuffer();
        buffer.lock();
        try {
            var p = view.getSession().typeAheadPrediction();
            return p == null ? null : p.text().stripTrailing();
        } finally {
            buffer.unlock();
        }
    }

    private static <T> T call(java.util.concurrent.Callable<T> work) {
        try {
            return fx(work);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private void open() throws Exception {
        connector = new FakeConnector();
        fx(() -> {
            view = new TerminalView(13);
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 800, 400));
            stage.show();
            view.getScene().getRoot().layout();
            view.start((columns, rows) -> {
                connector.openedAt = new TermSize(columns, rows);
                return connector;
            });
            return null;
        });
    }

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
            return "";
        }
    }

    private static KeyEvent typed(String character) {
        return new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, false, false, false, false);
    }

    private static KeyEvent pressed(KeyCode code, boolean control) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false);
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
