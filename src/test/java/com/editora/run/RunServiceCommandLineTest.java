package com.editora.run;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The command line the Run console shows first (review C15): readable, and pasteable into a shell. */
class RunServiceCommandLineTest {

    @Test
    void wordsWithSpacesOrQuotesAreQuoted() {
        assertEquals(
                "/p/.venv/bin/python \"/my proj/app.py\" --name \"Ada \\\"the\\\" first\" \"\"",
                RunService.commandLine(
                        List.of("/p/.venv/bin/python", "/my proj/app.py", "--name", "Ada \"the\" first", "")));
        assertEquals("python3 -m pytest", RunService.commandLine(List.of("python3", "-m", "pytest")));
    }
}
