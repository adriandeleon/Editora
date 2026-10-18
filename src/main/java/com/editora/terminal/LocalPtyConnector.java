package com.editora.terminal;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
 */
public final class LocalPtyConnector extends ProcessTtyConnector implements TerminalConnector {

    private final PtyProcess process;
    private final String name;

    LocalPtyConnector(PtyProcess process, List<String> commandLine) {
        // The child was told LANG=…UTF-8 and TERM=xterm-256color, so decode its output as UTF-8.
        super(process, StandardCharsets.UTF_8, commandLine);
        this.process = process;
        this.name = commandLine.isEmpty() ? "shell" : commandLine.get(0);
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
        // Terminates the whole process group: a shell almost always has children (the foreground
        // job), and destroying only the shell would orphan them.
        process.destroy();
        super.close();
    }

    /**
     * The shell as a {@link Process} that can be tracked: pty4j's own throws from {@code toHandle()}, which
     * Editora's process registry needs to end the shell's tree and to recognise it after a crash.
     */
    public Process trackableProcess() {
        return new PtyHandleProcess(process);
    }

    /** The shell's own pid — what the OS is asked about to find its working directory. */
    @Override
    public long pid() {
        return process.pid();
    }
}
