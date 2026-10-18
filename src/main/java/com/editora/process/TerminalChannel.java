package com.editora.process;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * A login shell on a pseudo-terminal somewhere other than this machine, as {@link ExecTarget#startTerminal}
 * starts it. The terminal emulator reads {@link #output()} and writes {@link #input()}; everything a program
 * prints arrives on the one stream, as on any terminal.
 */
public interface TerminalChannel extends AutoCloseable {

    /** What the shell prints, stdout and stderr together (the pseudo-terminal merges them). */
    InputStream output();

    /** What is typed. */
    OutputStream input();

    /** Tells the pseudo-terminal its new size, which the shell's foreground program learns as SIGWINCH. */
    void resize(int columns, int rows);

    boolean isOpen();

    /** Blocks until the channel closes; the shell's exit status, or -1 when there was none. */
    int waitFor() throws InterruptedException;

    /**
     * Whether it ended because the connection went, rather than because the shell exited or Editora closed it.
     * A terminal keeps its scrollback and offers to reconnect then, instead of closing its tab.
     */
    boolean lostConnection();

    /** Ends the shell: the server hangs up the pseudo-terminal, as a dropped {@code ssh} would. */
    @Override
    void close();
}
