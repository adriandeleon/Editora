package com.editora.terminal;

import java.time.Duration;
import java.time.Instant;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end over a real PTY: start the user's shell, run a command, read it back off the emulated
 * screen.
 *
 * <p>This exercises the part of the stack that unit tests structurally cannot — pty4j's native
 * library actually loading and extracting, the shell starting under a PTY, and JediTerm's parser
 * consuming its output. A compile proves none of that, and the failure mode for any of it is a
 * blank window rather than an exception.
 *
 * <p>No JavaFX here: the display below is a bare stub, so this runs headless.
 */
class TerminalSessionPtyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Test
    void runsACommandAndShowsItsOutput() throws Exception {
        assumeTrue(!LocalShell.isWindows(), "shell syntax below is POSIX");

        StubDisplay display = new StubDisplay();
        TerminalSession session =
                new TerminalSession(display, LocalShell.start(java.util.List.of(), null, 80, 24), 80, 24, 5000);
        try {
            session.start();
            TerminalTextBuffer buffer = session.getTextBuffer();

            // A marker unlikely to appear in a prompt, MOTD, or shell startup noise.
            String marker = "TERMINA_PTY_OK_" + ProcessHandle.current().pid();
            // Wait for the shell to be ready to read before typing at it: a login shell sources the
            // user's profile first, and input sent before then is simply discarded.
            assertTrue(awaitReady(session), "the shell started");

            // Typed repeatedly rather than once. First output is not the same as ready to read:
            // macOS bash prints a notice about zsh before it reaches its prompt, and anything sent
            // in that window is discarded. CI found this — on a runner the gap is wide enough to
            // lose the keystroke every time, where locally it never showed. A user with a slow
            // profile has exactly the same race.
            assertTrue(
                    awaitTyping(session, buffer, "echo " + marker + "\r", marker),
                    () -> "marker never appeared on screen. Screen was:\n" + screen(buffer));
        } finally {
            session.close();
        }
    }

    /**
     * Sends {@code input} until {@code expected} shows up, or the timeout runs out.
     *
     * <p>Re-sending is safe here: the worst case is a shell that echoes the same harmless command
     * twice, and the assertion only cares that it appeared at all.
     */
    private static boolean awaitTyping(
            TerminalSession session, TerminalTextBuffer buffer, String input, String expected)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            session.sendString(input);
            if (await(buffer, expected, Duration.ofSeconds(2))) return true;
        }
        return false;
    }

    @Test
    void resizeIsAppliedToTheBuffer() throws Exception {
        assumeTrue(!LocalShell.isWindows(), "shell syntax below is POSIX");

        StubDisplay display = new StubDisplay();
        TerminalSession session =
                new TerminalSession(display, LocalShell.start(java.util.List.of(), null, 80, 24), 80, 24, 5000);
        try {
            session.start();
            assertTrue(awaitReady(session), "the shell started");
            session.resize(100, 30);

            TerminalTextBuffer buffer = session.getTextBuffer();
            Instant deadline = Instant.now().plus(TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                if (buffer.getWidth() == 100 && buffer.getHeight() == 30) return;
                Thread.sleep(50);
            }
            assertTrue(false, "buffer stayed " + buffer.getWidth() + "x" + buffer.getHeight());
        } finally {
            session.close();
        }
    }

    /** True once the shell has written anything at all — i.e. it is up and has printed a prompt. */
    private static boolean awaitReady(TerminalSession session) throws InterruptedException {
        TerminalTextBuffer buffer = session.getTextBuffer();
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!screen(buffer).isBlank()) return true;
            if (!session.isRunning()) return false;
            Thread.sleep(50);
        }
        return false;
    }

    private static boolean await(TerminalTextBuffer buffer, String needle) throws InterruptedException {
        return await(buffer, needle, TIMEOUT);
    }

    private static boolean await(TerminalTextBuffer buffer, String needle, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            // The command echoes once as typed and once as output; two occurrences means the shell
            // actually ran it rather than merely echoing the keystrokes back.
            if (countOccurrences(screen(buffer), needle) >= 2) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) return count;
            count++;
            from = at + needle.length();
        }
    }

    private static String screen(TerminalTextBuffer buffer) {
        buffer.lock();
        try {
            return buffer.getScreenLines();
        } finally {
            buffer.unlock();
        }
    }

    /** Everything the emulator calls into, doing nothing. */
    private static final class StubDisplay implements TerminalDisplay {
        @Override
        public void setCursor(int x, int y) {}

        @Override
        public void setCursorShape(CursorShape shape) {}

        @Override
        public void beep() {}

        @Override
        public void onResize(TermSize newSize, RequestOrigin origin) {}

        @Override
        public void scrollArea(int top, int size, int dy) {}

        @Override
        public void setCursorVisible(boolean visible) {}

        @Override
        public void useAlternateScreenBuffer(boolean enabled) {}

        @Override
        public String getWindowTitle() {
            return "test";
        }

        @Override
        public void setWindowTitle(String title) {}

        @Override
        public TerminalSelection getSelection() {
            return null;
        }

        @Override
        public void terminalMouseModeSet(MouseMode mode) {}

        @Override
        public void setMouseFormat(MouseFormat format) {}

        @Override
        public boolean ambiguousCharsAreDoubleWidth() {
            return false;
        }
    }
}
