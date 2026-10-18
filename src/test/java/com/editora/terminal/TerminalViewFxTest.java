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
        connector.emit("\u001b]7;file://box/srv/my%20app\u0007$ ");
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
