package com.editora.terminal;

/**
 * A program running in a Terminal tab (Run in Terminal, a debugged program), as its starter sees it. The tab
 * belongs to the Terminal tool window: when the program ends it stays, with what the program printed.
 */
public interface ProgramRun {

    /** The program's pid on the machine it runs on, once known; -1 before that. */
    long pid();

    /** True until the program has ended. */
    boolean isRunning();

    /** Ends the program; its end is still reported to whoever started it. The tab stays. */
    void stop();
}
