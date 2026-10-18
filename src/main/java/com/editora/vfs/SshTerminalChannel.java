package com.editora.vfs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import com.editora.process.TerminalChannel;
import com.editora.process.TerminalSpec;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.PtyChannelConfiguration;

/**
 * A login shell on a pseudo-terminal on an SSH host: an exec channel of the window's own session that asks for a
 * PTY ({@code pty-req}). Unlike a tool command's channel, everything is in-band here — the PTY merges stderr into
 * stdout, and the size travels as {@code window-change} requests.
 *
 * <p>When the connection drops the server hangs up the PTY, so the shell and its jobs get SIGHUP the way a dropped
 * {@code ssh} gives it to them; what the user put under {@code nohup} or tmux survives. No watchdog, unlike tool
 * commands (ADR 0014).
 */
final class SshTerminalChannel implements TerminalChannel {

    /** What the shell is told the terminal is; the emulator is xterm-compatible with 256 colours and RGB. */
    static final String TERM = "xterm-256color";

    private final ClientSession session;
    private final ChannelExec channel;
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile boolean closedByUs;
    private volatile long pid = -1;
    /** The channel's output with the start script's pid line taken out (and its pid kept). */
    private final InputStream output;

    private SshTerminalChannel(ClientSession session, ChannelExec channel) {
        this.session = session;
        this.channel = channel;
        channel.addCloseFutureListener(f -> closed.countDown());
        this.output = new com.editora.terminal.PidMarkStream(channel.getInvertedOut(), found -> pid = found);
    }

    /** Opens {@code commandLine} on a new PTY channel of {@code session}, sized as {@code spec} says. */
    static SshTerminalChannel open(ClientSession session, String commandLine, TerminalSpec spec, Duration timeout)
            throws IOException {
        PtyChannelConfiguration pty = new PtyChannelConfiguration();
        pty.setPtyType(TERM);
        pty.setPtyColumns(spec.columns());
        pty.setPtyLines(spec.rows());
        ChannelExec channel = session.createExecChannel(commandLine, pty, Map.of());
        // A PTY configuration alone asks for nothing: ChannelExec defaults to no PTY (ChannelShell to one), and
        // without the pty-req the shell has no tty, no size, and no hang-up when the connection goes.
        channel.setUsePty(true);
        // A server that runs the command without a real tty (MINA's own) keeps stderr apart; a terminal shows both.
        channel.setRedirectErrorStream(true);
        try {
            channel.open().verify(timeout);
        } catch (IOException | RuntimeException e) {
            channel.close(true);
            throw e;
        }
        return new SshTerminalChannel(session, channel);
    }

    /** Runs {@code action} once the channel has closed, at once if it already has. */
    void whenClosed(Runnable action) {
        channel.addCloseFutureListener(f -> action.run());
    }

    @Override
    public InputStream output() {
        return output;
    }

    @Override
    public long pid() {
        return pid;
    }

    @Override
    public OutputStream input() {
        return channel.getInvertedIn();
    }

    @Override
    public void resize(int columns, int rows) {
        if (!isOpen()) {
            return;
        }
        try {
            channel.sendWindowChange(Math.max(1, columns), Math.max(1, rows));
        } catch (IOException | RuntimeException gone) {
            // The channel closed between the check and the request: the next shell gets the size at open.
        }
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen() && !channel.isClosing();
    }

    @Override
    public int waitFor() throws InterruptedException {
        closed.await();
        Integer status = channel.getExitStatus();
        return status == null ? -1 : status;
    }

    @Override
    public boolean lostConnection() {
        return !closedByUs && closed.getCount() == 0 && (session.isClosing() || !session.isOpen());
    }

    @Override
    public void close() {
        closedByUs = true;
        channel.close(false);
    }

    /**
     * The command a terminal channel runs: start in {@code cwd} (home when it is null or gone), tell the shell what
     * terminal it is on, then become the account's login shell so its profile runs as in any {@code ssh} session.
     * Wrapped in {@code /bin/sh -c} because the server hands the line to the account's own shell, which may be fish
     * or csh.
     */
    static String commandLine(String cwd) {
        return commandLine(cwd, (String) null);
    }

    /** As above, with Editora's shell integration from {@code integration} on the host (null for none). */
    static String commandLine(String cwd, String integration) {
        return "exec /bin/sh -c "
                + com.editora.process.PosixShell.quote(
                        com.editora.process.PosixShell.terminalScript(cwd, true, integration));
    }

    /** The command line for {@code spec}: its login shell, or the program it names. */
    static String commandLine(String cwd, com.editora.process.TerminalSpec spec) {
        return "exec /bin/sh -c "
                + com.editora.process.PosixShell.quote(com.editora.process.PosixShell.terminalScript(cwd, true, spec));
    }
}
