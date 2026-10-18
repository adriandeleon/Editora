package com.editora.terminal;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.editora.process.ProcessRegistry;
import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.ProcessTtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.WinSize;

/**
 * A shell on this machine: bridges pty4j's {@link PtyProcess} to JediTerm's {@code TtyConnector}.
 *
 * <p>{@link ProcessTtyConnector} already implements read/write/close over the process streams; the
 * only thing it cannot do is resize, because resizing a PTY is an ioctl (TIOCSWINSZ) rather than
 * anything expressible through {@link Process}.
 *
 * <p>How it ends depends on what it runs. A shell is hung up, as closing any terminal window does: it passes the
 * hang-up on to its jobs, a job the user detached ({@code nohup}, {@code disown}) keeps running, and a shell still
 * there after the grace period is killed. A program (Run in Terminal) is ended as console Run ends one, through
 * {@link ProcessRegistry#killTree}: SIGTERM to its tree, then SIGKILL to whatever ignored it.
 */
public final class LocalPtyConnector extends ProcessTtyConnector implements TerminalConnector {

    private final PtyProcess process;
    private final String name;
    private final Process trackable;
    private final boolean program;

    LocalPtyConnector(PtyProcess process, List<String> commandLine) {
        this(process, commandLine, false);
    }

    LocalPtyConnector(PtyProcess process, List<String> commandLine, boolean program) {
        // The child was told LANG=…UTF-8 and TERM=xterm-256color, so decode its output as UTF-8.
        super(process, StandardCharsets.UTF_8, commandLine);
        this.process = process;
        this.name = commandLine.isEmpty() ? "shell" : commandLine.get(0);
        this.program = program;
        this.trackable = program ? new PtyHandleProcess(process) : new PtyHandleProcess.Shell(process, this::hangUp);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void resize(TermSize size) {
        if (!process.isAlive()) return;
        // The child learns about this through SIGWINCH; full-screen programs (vim, less, htop)
        // redraw themselves in response. Nothing needs to be sent down the stream by us.
        process.setWinSize(new WinSize(size.getColumns(), size.getRows()));
    }

    @Override
    public boolean isConnected() {
        return process.isAlive();
    }

    @Override
    public void close() {
        ProcessRegistry.killTree(trackable);
        if (program) {
            // Its streams stay open until it has gone, so what it prints on its way out is still shown.
            process.onExit().thenRun(this::closeStreams);
        }
    }

    /**
     * Hangs the shell up: the pseudo-terminal is closed, which sends it SIGHUP, as closing a terminal window does.
     * No SIGTERM: pty4j's {@code destroy} signals the shell's whole process group, jobs and all. A shell still there
     * after the grace period is killed.
     */
    private void hangUp() {
        for (java.io.Closeable stream : List.<java.io.Closeable>of(
                process.getOutputStream(), process.getInputStream(), process.getErrorStream())) {
            try {
                stream.close();
            } catch (java.io.IOException | RuntimeException alreadyClosed) {
                // nothing left to hang up
            }
        }
    }

    private void closeStreams() {
        super.close();
    }

    /**
     * The shell as a {@link Process} that can be tracked: pty4j's own throws from {@code toHandle()}, which
     * Editora's process registry needs to end the shell's tree and to recognise it after a crash. Always the same
     * object, so what is tracked is what {@link #close} ends.
     */
    public Process trackableProcess() {
        return trackable;
    }

    /** The shell's own pid — what the OS is asked about to find its working directory. */
    @Override
    public long pid() {
        return process.pid();
    }
}
