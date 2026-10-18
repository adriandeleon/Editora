package com.editora.terminal;

import com.jediterm.core.typeahead.TerminalTypeAheadManager;
import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TerminalStarter;
import com.jediterm.terminal.TtyBasedArrayDataStream;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;

/**
 * One shell attached to one emulator: a {@link TerminalConnector}, the escape-sequence parser, and the screen
 * buffer.
 *
 * <p>The read loop runs on its own thread — {@link TerminalStarter#start()} blocks for the life of
 * the session — and writes into the buffer under its lock. Renderers must take the same lock.
 */
public final class TerminalSession {

    private final TerminalConnector connector;
    private final TerminalTextBuffer textBuffer;
    private final JediTerminal terminal;
    private final TerminalStarter starter;
    private final TerminalExecutors executors;
    private final Thread emulatorThread;

    private final TypeAheadOverlay typeAheadOverlay;
    private final TerminalTypeAheadManager typeAhead;
    private volatile Runnable onTypeAheadChanged = () -> {};
    private volatile boolean closed;
    private Runnable onSessionEnded = () -> {};

    public TerminalSession(
            com.jediterm.terminal.TerminalDisplay display,
            TerminalConnector connector,
            int columns,
            int rows,
            int scrollbackLines) {
        this.connector = connector;

        StyleState styleState = new StyleState();
        textBuffer = new TerminalTextBuffer(columns, rows, styleState, Math.max(0, scrollbackLines));
        terminal = new JediTerminal(display, textBuffer, styleState);
        terminal.setUrlHyperlinkFilter(Osc8Links.filter()); // OSC 8: the URI rides in the cells' style

        executors = new TerminalExecutors();
        typeAheadOverlay = new TypeAheadOverlay(terminal, textBuffer, () -> onTypeAheadChanged.run());
        typeAhead = new TerminalTypeAheadManager(typeAheadOverlay);
        typeAhead.setClearPredictionsDebouncer(new com.jediterm.terminal.model.JediTermDebouncerImpl(
                typeAhead::debounce, TerminalTypeAheadManager.MAX_TERMINAL_DELAY, executors));
        // The manager checks its predictions against what the shell echoed after every chunk of output.
        starter = new TerminalStarter(
                terminal,
                connector,
                new TtyBasedArrayDataStream(connector, typeAhead::onTerminalStateChanged),
                typeAhead,
                executors);

        emulatorThread = new Thread(this::runEmulator, "editora-terminal-emulator");
        emulatorThread.setDaemon(true);
    }

    public void start() {
        emulatorThread.start();
    }

    private void runEmulator() {
        try {
            starter.start();
        } catch (Exception e) {
            if (!closed) {
                System.getLogger(TerminalSession.class.getName())
                        .log(System.Logger.Level.ERROR, "terminal emulator stopped", e);
            }
        } finally {
            // Reached when the shell exits (the user typed `exit`, or the process died) — but also
            // when we tore the session down ourselves. Only the first is news: firing the callback
            // during our own shutdown races the toolkit, whose listener marshals to the FX thread
            // ("Main Java thread is detached" out of the Glass dispatcher once it has stopped).
            if (!closed) {
                settle();
                onSessionEnded.run();
            }
        }
    }

    /** How long the end of the output may come before the connector has finished closing. */
    private static final long SETTLE_MILLIS = 2000;

    /**
     * Waits, briefly, for the connector to finish closing after its output ended. The two arrive separately — an
     * SSH channel's end of data before its close notice, a local shell's EOF before its exit — and what the end
     * <em>means</em> (the shell exited, or the connection went: {@link #lostConnection}) is only known once the
     * close has landed. Bounded, so a program that closes its output and lives on cannot hold the end back.
     */
    private void settle() {
        Thread waiter = Thread.ofVirtual().name("terminal-settle").start(() -> {
            try {
                exitCode = connector.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            waiter.join(SETTLE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        waiter.interrupt();
    }

    private volatile Integer exitCode;

    /** The program's exit code once it has ended and the code arrived; null before, or when none came. */
    public Integer exitCode() {
        return exitCode;
    }

    /**
     * Ends the program but keeps the session listening, so its end is still reported ({@link #setOnSessionEnded})
     * — Stop on a program run in a terminal. {@link #close} is the silent version.
     */
    public void terminate() {
        if (closed) return;
        try {
            connector.close();
        } catch (Exception ignored) {
            // already gone
        }
    }

    /** Called off the FX thread when the shell exits. */
    public void setOnSessionEnded(Runnable onSessionEnded) {
        this.onSessionEnded = onSessionEnded;
    }

    /**
     * Applies a new terminal size. Reflows the buffer and issues the PTY ioctl, which is what
     * makes the child receive SIGWINCH and redraw.
     */
    public void resize(int columns, int rows) {
        if (closed || columns <= 0 || rows <= 0) return;
        starter.postResize(new TermSize(columns, rows), RequestOrigin.User);
        typeAhead.onResize();
    }

    /** Sends raw bytes to the shell (already-encoded key sequences, pasted text). */
    public void send(byte[] bytes) {
        if (closed || bytes == null || bytes.length == 0) return;
        starter.sendBytes(bytes, true);
    }

    public void sendString(String text) {
        if (closed || text == null || text.isEmpty()) return;
        starter.sendString(text, true);
    }

    /** Encodes a key using the emulator's current modes (application vs ANSI cursor keys). */
    public byte[] keyCode(int awtKeyCode, int awtModifiers) {
        return starter.getCode(awtKeyCode, awtModifiers);
    }

    /** Turns type-ahead on or off (see {@link TypeAheadOverlay}); off until set. */
    public void setTypeAhead(boolean enabled) {
        typeAheadOverlay.setEnabled(enabled);
    }

    /** Where predictions changed and the view should repaint. */
    public void setOnTypeAheadChanged(Runnable onChanged) {
        this.onTypeAheadChanged = onChanged == null ? () -> {} : onChanged;
    }

    /** The predicted text to paint over the cursor's row, or null. Caller holds the buffer lock. */
    public TypeAheadOverlay.Prediction typeAheadPrediction() {
        return typeAheadOverlay.prediction();
    }

    /** The cursor column, 0-based, where the predictions put it. Caller holds the buffer lock. */
    public int typeAheadCursorColumn() {
        return typeAhead.getCursorX() - 1;
    }

    public TerminalTextBuffer getTextBuffer() {
        return textBuffer;
    }

    public JediTerminal getTerminal() {
        return terminal;
    }

    public boolean isRunning() {
        return !closed && connector.isConnected();
    }

    /** Whether the shell ended because its connection went (see {@link TerminalConnector#lostConnection}). */
    public boolean lostConnection() {
        return connector.lostConnection();
    }

    /** The shell's pid on the machine it runs on when that is not this one, once known; -1 otherwise. */
    public long remotePid() {
        return connector.remotePid();
    }

    /** The shell's pid on this machine, or {@code -1} when it runs elsewhere. */
    public long pid() {
        return connector.pid();
    }

    public void close() {
        if (closed) return;
        closed = true;
        try {
            starter.requestEmulatorStop();
        } catch (Exception ignored) {
            // Best effort: we are shutting down and the connector may already be gone.
        }
        try {
            connector.close();
        } catch (Exception ignored) {
        }
        executors.shutdownNow();
    }
}
