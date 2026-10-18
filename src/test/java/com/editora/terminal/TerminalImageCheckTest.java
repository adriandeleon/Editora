package com.editora.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The terminal's native stack from the test classpath: a real shell on a real pseudo-terminal (ConPTY on the
 * Windows lane), read back through JediTerm. The packaged image runs the same check through
 * {@code editora --check-terminal}.
 */
class TerminalImageCheckTest {

    @Test
    void aShellOnAPseudoTerminalSeesTheSizeItWasGiven() {
        TerminalImageCheck.Result result = TerminalImageCheck.run();

        assertTrue(result.ok(), result.detail());
    }

    @Test
    void theAnswerIsFoundOnAnyLineOfTheScreen() {
        String screen = "\n" + TerminalImageCheck.MARK + " 24 80   \n\n";

        assertTrue(TerminalImageCheck.evaluate(screen).ok());
    }

    @Test
    void aWrongSizeIsReportedWithWhatTheShellSaw() {
        TerminalImageCheck.Result result = TerminalImageCheck.evaluate(TerminalImageCheck.MARK + " 0 0\n");

        assertFalse(result.ok());
        assertTrue(result.detail().contains("\"" + TerminalImageCheck.MARK + " 0 0\""), result.detail());
    }

    @Test
    void aShellThatNeverAnsweredIsReportedWithItsScreen() {
        // What stty prints when its input is not a terminal: the failure this check exists to catch.
        TerminalImageCheck.Result result =
                TerminalImageCheck.evaluate("stty: 'standard input': Inappropriate ioctl for device\n\n");

        assertFalse(result.ok());
        assertEquals(
                "terminal produced no answer: stty: 'standard input': Inappropriate ioctl for device", result.detail());
    }

    @Test
    void theCommandAsksTheTerminalNotTheEnvironment() {
        // COLUMNS/LINES would answer without a tty; stty and [Console] read the terminal itself.
        assertTrue(String.join(" ", TerminalImageCheck.command(false)).contains("stty size"));
        assertTrue(String.join(" ", TerminalImageCheck.command(true)).contains("[Console]::WindowHeight"));
    }
}
