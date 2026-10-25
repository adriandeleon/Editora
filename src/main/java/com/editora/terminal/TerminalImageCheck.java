package com.editora.terminal;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.editora.process.ProcessRunner;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.ProcessTtyConnector;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.TtyBasedArrayDataStream;
import com.jediterm.terminal.emulator.JediEmulator;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import org.jspecify.annotations.Nullable;

/**
 * Proves that the integrated terminal's native stack works where Editora is running: pty4j opens a real
 * pseudo-terminal, a shell started on it sees the size it was given, and JediTerm turns the bytes into a screen.
 *
 * <p>The stack fails in ways no compile can see. pty4j's natives load from {@code com/pty4j/native/…} inside
 * its jar, which JPMS leaves readable only because {@code native} is a reserved word; jediterm and pty4j are
 * Kotlin automatic modules whose {@code kotlin.stdlib} and {@code org.slf4j} must be resolved by name; and the
 * jlink image links whatever the moditect descriptors say. Each of those breaks at the first emulator call of
 * the linked app, not at build time. So {@code editora --check-terminal} runs this inside the packaged image
 * (see {@code .github/workflows/terminal-image.yml}), and {@code TerminalImageCheckTest} runs it from the
 * test classpath.
 *
 * <p>The shell asks the terminal for its size ({@code stty size}, or the console window size on Windows). That
 * answer exists only on a real tty: on a pipe {@code stty} fails, so "24 80" on the screen means the whole path
 * worked — PTY, size, output, emulation.
 */
public final class TerminalImageCheck {

    private TerminalImageCheck() {}

    static final int COLUMNS = 80;
    static final int ROWS = 24;
    static final String MARK = "editora-pty";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** What the check found; {@code detail} is the line to print either way. */
    public record Result(boolean ok, String detail) {}

    /** The command that prints {@link #MARK}, then the rows and columns the child sees. */
    static List<String> command(boolean windows) {
        if (windows) {
            // ConPTY sizes the console the child attaches to; [Console] reads it back. powershell.exe is on every
            // supported Windows, and -NoProfile keeps a user's profile out of what is a build check.
            return List.of(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "Write-Output ('" + MARK + " ' + [Console]::WindowHeight + ' ' + [Console]::WindowWidth)");
        }
        // On Linux pty4j forks the child first and sets the window size after, so a child that asks at once can
        // read 0 0 (measured: every run did). Wait up to 5 s for the size to arrive. A terminal tab must allow
        // for the same race when it starts a program directly rather than an interactive shell.
        return List.of(
                "/bin/sh",
                "-c",
                "i=0; while [ \"$(stty size)\" = '0 0' ] && [ $i -lt 50 ]; do sleep 0.1; i=$((i+1)); done; "
                        + "printf '%s %s\\n' " + MARK + " \"$(stty size)\"");
    }

    /**
     * Reads the screen the emulator produced. Pure, so the rule is tested without a terminal.
     *
     * @param screen JediTerm's screen text, one line per row
     */
    static Result evaluate(String screen) {
        String expected = MARK + " " + ROWS + " " + COLUMNS;
        for (String line : screen.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.equals(expected)) {
                return new Result(true, "terminal ok: a shell on a " + COLUMNS + "x" + ROWS + " pseudo-terminal");
            }
            if (trimmed.startsWith(MARK + " ")) {
                return new Result(false, "terminal size wrong: expected \"" + expected + "\", got \"" + trimmed + "\"");
            }
        }
        String shown = screen.strip();
        return new Result(
                false,
                "terminal produced no answer" + (shown.isEmpty() ? "" : ": " + shown.replaceAll("\\s*\\R\\s*", " | ")));
    }

    /** Runs the check and prints its one line; returns the process exit status for {@code --check-terminal}. */
    public static int runAndReport(PrintStream out) {
        Result result = run();
        out.println(result.detail());
        return result.ok() ? 0 : 1;
    }

    public static Result run() {
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        List<String> command = command(windows);
        Map<String, String> env =
                ProcessRunner.applyUserEnv(new HashMap<>(System.getenv()), Map.of("TERM", "xterm-256color"));
        // COLUMNS/LINES inherited from a launching terminal would override what the tty reports.
        env.remove("COLUMNS");
        env.remove("LINES");
        PtyProcess process;
        try {
            process = new PtyProcessBuilder(command.toArray(String[]::new))
                    .setEnvironment(env)
                    .setInitialColumns(COLUMNS)
                    .setInitialRows(ROWS)
                    // false = a real PTY; console mode is Windows' screen-buffer path, which sends no escapes.
                    .setConsole(false)
                    .setUseWinConPty(true)
                    .start();
        } catch (Throwable e) {
            // LinkageError included: a native library that did not load is exactly what this exists to report.
            return new Result(false, "could not start a shell on a pseudo-terminal: " + e);
        }
        try {
            return emulate(process, command);
        } catch (Throwable e) {
            return new Result(false, "terminal emulation failed: " + e);
        } finally {
            process.destroyForcibly();
        }
    }

    private static Result emulate(PtyProcess process, List<String> command) throws InterruptedException {
        ProcessTtyConnector connector = new ProcessTtyConnector(process, StandardCharsets.UTF_8, command) {
            @Override
            public String getName() {
                return MARK;
            }
        };
        StyleState style = new StyleState();
        TerminalTextBuffer buffer = new TerminalTextBuffer(COLUMNS, ROWS, style, 0);
        JediTerminal terminal = new JediTerminal(new QuietDisplay(), buffer, style);
        JediEmulator emulator = new JediEmulator(new TtyBasedArrayDataStream(connector), terminal);
        Thread reader = Thread.ofPlatform().daemon().name("editora-pty-check").start(() -> {
            try {
                while (emulator.hasNext()) {
                    emulator.next();
                }
            } catch (Exception endOfOutput) {
                // The child exited and the PTY closed: the screen is complete.
            }
        });
        if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            return new Result(false, "the shell did not finish within " + TIMEOUT.toSeconds() + " s");
        }
        reader.join(TIMEOUT.toMillis());
        buffer.lock();
        try {
            return evaluate(buffer.getScreenLines());
        } finally {
            buffer.unlock();
        }
    }

    /** The check has no window: every display callback is accepted and ignored. */
    private static final class QuietDisplay implements TerminalDisplay {
        private String title = "";

        @Override
        public void setCursor(int x, int y) {}

        @Override
        public void setCursorShape(@Nullable CursorShape shape) {}

        @Override
        public void beep() {}

        @Override
        public void scrollArea(int scrollRegionTop, int scrollRegionSize, int dy) {}

        @Override
        public void setCursorVisible(boolean visible) {}

        @Override
        public void useAlternateScreenBuffer(boolean enabled) {}

        @Override
        public String getWindowTitle() {
            return title;
        }

        @Override
        public void setWindowTitle(String title) {
            this.title = title;
        }

        @Override
        public @Nullable TerminalSelection getSelection() {
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
