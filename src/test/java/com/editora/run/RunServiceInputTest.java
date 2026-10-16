package com.editora.run;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What one line typed into the Run console is sent as. */
class RunServiceInputTest {

    @Test
    void aShellGetsUnixLineEndsEvenWhereTheMachineUsesCrlf() {
        // Git Bash's `read` kept the \r of a CRLF line, so `[ "$ans" = y ]` failed for every typed answer.
        assertEquals("y\n", new String(RunService.inputLine("y", false, true, "\r\n"), StandardCharsets.UTF_8));
        assertEquals("y\r\n", new String(RunService.inputLine("y", false, false, "\r\n"), StandardCharsets.UTF_8));
        assertEquals("y\n", new String(RunService.inputLine("y", true, false, "\r\n"), StandardCharsets.UTF_8));
    }

    @Test
    void posixShellsAreRecognizedByNameOrPath() {
        assertTrue(RunService.isPosixShell("bash"));
        assertTrue(RunService.isPosixShell("/bin/sh"));
        assertTrue(RunService.isPosixShell("C:\\Program Files\\Git\\bin\\bash.exe"));
        assertFalse(RunService.isPosixShell("python3"));
        assertFalse(RunService.isPosixShell(null));
    }
}
