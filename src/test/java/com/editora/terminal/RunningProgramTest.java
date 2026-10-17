package com.editora.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the close prompt calls the thing it is about to end. */
class RunningProgramTest {

    @Test
    void aPathIsReducedToTheProgramName() {
        // The path is how the OS found it and says nothing a dialog's reader needs.
        assertEquals("sleep", RunningProgram.displayName("/usr/bin/sleep"));
        assertEquals("vim", RunningProgram.displayName("/usr/local/bin/vim"));
    }

    @Test
    void windowsPathsSplitOnTheirOwnSeparator() {
        assertEquals("vim.exe", RunningProgram.displayName("C:\\Program Files\\Vim\\vim.exe"));
    }

    @Test
    void aBareNameIsLeftAlone() {
        assertEquals("make", RunningProgram.displayName("make"));
    }

    @Test
    void nothingUsableGivesNothing() {
        // A process can refuse to say what it is; the caller filters these out rather than
        // showing a dialog about "".
        assertEquals("", RunningProgram.displayName(""));
        assertEquals("", RunningProgram.displayName("   "));
        assertEquals("", RunningProgram.displayName(null));
    }

    @Test
    void ourOwnProcessHasNoShellChildren() {
        // Not an assertion about children in general — the test JVM may fork — but the call has to
        // answer rather than throw for a live pid.
        assertTrue(RunningProgram.in(ProcessHandle.current().pid()) != null);
    }

    @Test
    void aPidThatIsNotThereAnswersEmpty() {
        // The tab closed between the click and the check: ordinary, not an error.
        assertTrue(RunningProgram.in(-1).isEmpty());
        assertTrue(RunningProgram.first(-1).isEmpty());
    }
}
