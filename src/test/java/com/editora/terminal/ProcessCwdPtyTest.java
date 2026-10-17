package com.editora.terminal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The working-directory read, against a real shell on a real PTY.
 *
 * <p>Structurally this cannot be unit-tested: the whole point is that we are asking the operating
 * system about a process we did not write, through an interface that differs per platform. A
 * compile proves nothing, and the failure mode is a terminal tab that never learns its directory.
 *
 * <p>No JavaFX here — the display is a stub, so this runs headless like its sibling PTY test.
 */
class ProcessCwdPtyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Test
    void readsTheShellsDirectoryAndFollowsItAcrossACd() throws Exception {
        assumeTrue(ProcessCwd.isSupported(), "no way to read a cwd on this platform");
        assumeTrue(!LocalShell.isWindows(), "shell syntax below is POSIX");

        // A directory of our own, so the assertion cannot pass by coincidence with $HOME or /tmp.
        Path target = Files.createTempDirectory("editora-terminal-cwd").toRealPath();

        StubDisplay display = new StubDisplay();
        TerminalSession session =
                new TerminalSession(display, LocalShell.start(java.util.List.of(), null, 80, 24), 80, 24, 5000);
        AtomicReference<String> reported = new AtomicReference<>();
        try {
            session.start();
            assumeTrue(awaitReady(session), "shell did not start");

            // The shell is started in the user's home, and that is what a fresh tab should say.
            assertEquals(
                    Path.of(System.getProperty("user.home")).toRealPath().toString(),
                    ProcessCwd.of(session.pid()).orElse(null),
                    "a new session should report the directory its shell was started in");

            try (CwdWatcher watcher = CwdWatcher.watch(session.pid(), reported::set)) {
                assertTrue(await(() -> reported.get() != null), "the watcher never reported at all");

                // Login startup output can precede input readiness. Retry this idempotent command
                // until the watcher observes the actual directory, rather than trusting first output.
                assertTrue(
                        awaitDirectory(session, reported, target),
                        () -> "the watcher did not follow the cd. Last reported: " + reported.get());
            }

            // And it stops when closed: whatever it reported last stays put through another cd.
            String afterClose = reported.get();
            session.sendString("cd /\r");
            Thread.sleep(2 * 750);
            assertEquals(afterClose, reported.get(), "a closed watcher went on polling");
        } finally {
            session.close();
            Files.deleteIfExists(target);
        }
    }

    @Test
    void aPidThatIsNotOursAnswersNothingRatherThanThrowing() {
        // Closing a tab races the last poll, so this is an ordinary occurrence, not an error path.
        assertTrue(ProcessCwd.of(-1).isEmpty());
        assertTrue(ProcessCwd.of(0).isEmpty());
        // Comfortably above /proc/sys/kernel/pid_max on any default configuration.
        assertTrue(ProcessCwd.of(Integer.MAX_VALUE).isEmpty());
    }

    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static boolean awaitDirectory(TerminalSession session, AtomicReference<String> reported, Path target)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            session.sendString("cd '" + target.toString().replace("'", "'\"'\"'") + "'\r");
            Thread.sleep(1000);
            if (target.toString().equals(reported.get())) return true;
        }
        return false;
    }

    private static boolean awaitReady(TerminalSession session) throws InterruptedException {
        TerminalTextBuffer buffer = session.getTextBuffer();
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            buffer.lock();
            try {
                if (!buffer.getScreenLines().isBlank()) return true;
            } finally {
                buffer.unlock();
            }
            if (!session.isRunning()) return false;
            Thread.sleep(50);
        }
        return false;
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
