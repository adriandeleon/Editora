package com.editora.terminal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    /** A "shell" made of two pipes: what the test emits is output, what the view writes is recorded. */
    private static final class FakeConnector implements TerminalConnector {
        private final PipedWriter output = new PipedWriter();
        private final PipedReader reader;
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();
        private final CountDownLatch closed = new CountDownLatch(1);
        final AtomicReference<TermSize> lastResize = new AtomicReference<>();
        volatile TermSize openedAt;

        FakeConnector() throws IOException {
            reader = new PipedReader(output, 64 * 1024);
        }

        void emit(String text) throws IOException {
            output.write(text);
            output.flush();
        }

        void endOfOutput() throws IOException {
            output.close();
        }

        synchronized String written() {
            return input.toString(StandardCharsets.UTF_8);
        }

        @Override
        public int read(char[] buf, int offset, int length) throws IOException {
            try {
                return reader.read(buf, offset, length);
            } catch (IOException e) {
                // "Write end dead" once the test closed it: the end of the shell's output.
                return -1;
            }
        }

        @Override
        public synchronized void write(byte[] bytes) {
            input.writeBytes(bytes);
        }

        @Override
        public void write(String string) {
            write(string.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public boolean isConnected() {
            return closed.getCount() > 0;
        }

        @Override
        public void resize(TermSize size) {
            lastResize.set(size);
        }

        @Override
        public int waitFor() throws InterruptedException {
            closed.await();
            return 0;
        }

        @Override
        public boolean ready() throws IOException {
            return reader.ready();
        }

        @Override
        public String getName() {
            return "fake";
        }

        @Override
        public void close() {
            closed.countDown();
            try {
                output.close();
            } catch (IOException ignored) {
                // Already closed by the test.
            }
        }
    }
}
