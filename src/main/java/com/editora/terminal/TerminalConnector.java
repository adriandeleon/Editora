package com.editora.terminal;

import com.jediterm.terminal.TtyConnector;

/**
 * Where a terminal's bytes come from and go to: a shell on this machine ({@link LocalPtyConnector}), or one on
 * a host, in a container or in a WSL distribution. The emulator and the view never know which.
 *
 * <p>JediTerm's {@link TtyConnector} already covers reading, writing, resizing and closing. What it lacks is
 * what the view asks the operating system about the shell — its working directory — which only a process on
 * this machine can answer.
 */
public interface TerminalConnector extends TtyConnector {

    /** The shell's process id on this machine, or {@code -1} when it runs elsewhere. */
    default long pid() {
        return -1;
    }

    /**
     * Opens a connector at the size the view has laid out, so the shell starts at the right size rather than
     * learning it from a resize a moment later.
     */
    @FunctionalInterface
    interface Factory {
        TerminalConnector open(int columns, int rows) throws java.io.IOException;
    }
}
