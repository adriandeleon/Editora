package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The interpreter a shell script runs under: its shebang decides, a run configuration overrides. */
class ShellCommandTest {

    /** A run on a remote host: interpreter paths are used as written. */
    private static List<String> interpreter(String firstLine, String fileName) {
        return ShellCommand.launch(firstLine, fileName, "", "", null).interpreter();
    }

    @Test
    void theShebangDecidesTheInterpreterAndItsFlags() {
        assertEquals(List.of("bash", "-e"), interpreter("#!/usr/bin/env -S bash -e", "deploy.sh"));
        assertEquals(List.of("/bin/bash", "-eu"), interpreter("#!/bin/bash -eu", "deploy.sh"));
        assertEquals(List.of("/bin/sh"), interpreter("#!/bin/sh", "posix.sh"));
        assertEquals(List.of("zsh"), interpreter("#!/usr/bin/env zsh", "x.sh"));
        assertEquals(List.of("bash"), interpreter("#!/usr/bin/env bash", "x.sh"));
        assertEquals(List.of("/bin/bash"), interpreter("#! /bin/bash", "x.sh"));
    }

    @Test
    void aShebangNamingAnotherLanguageRunsThatLanguage() {
        assertEquals(List.of("python3"), interpreter("#!/usr/bin/env python3", "tool.sh"));
    }

    @Test
    void noShebangMeansBashExceptForZshFiles() {
        assertEquals(List.of("bash"), interpreter("echo hi", "x.sh"));
        assertEquals(List.of("bash"), interpreter(null, "x.bash"));
        assertEquals(List.of("zsh"), interpreter("print -P hi", "prompt.zsh"));
        assertEquals(List.of("zsh"), interpreter("", "PROMPT.ZSH"));
    }

    @Test
    void envOptionsAndAssignmentsAreResolved() {
        ShellCommand.Launch l = ShellCommand.launch("#!/usr/bin/env -S LC_ALL=C bash -x", "x.sh", "", "", null);
        assertEquals(List.of("bash", "-x"), l.interpreter());
        assertEquals(Map.of("LC_ALL", "C"), l.env());
        assertEquals(List.of("bash"), interpreter("#!/usr/bin/env -i bash", "x.sh"));
        assertEquals(List.of("bash", "-e"), interpreter("#!/usr/bin/env -Sbash -e", "x.sh"));
        assertEquals(List.of("ksh"), interpreter("#!/usr/bin/env -u FOO ksh", "x.sh"));
        assertEquals(List.of("bash"), interpreter("#!/usr/bin/env", "x.sh"), "env with nothing after it");
    }

    @Test
    void aByteOrderMarkDoesNotHideTheShebang() {
        assertEquals(List.of("/bin/sh"), interpreter("\uFEFF#!/bin/sh", "x.sh"));
    }

    @Test
    void aRunConfigurationOverridesTheShebang() {
        ShellCommand.Launch l =
                ShellCommand.launch("#!/usr/bin/env -S FOO=1 bash -e", "x.sh", "/opt/homebrew/bin/bash", "", null);
        assertEquals(List.of("/opt/homebrew/bin/bash"), l.interpreter(), "the override drops the shebang's flags");
        assertTrue(l.env().isEmpty(), "and its env line");
        assertEquals(
                List.of("/bin/bash", "-x", "-o", "pipefail"),
                ShellCommand.launch("#!/bin/bash -eu", "x.sh", "", "-x -o pipefail", null)
                        .interpreter(),
                "options alone replace the shebang's flags and keep its interpreter");
        assertThrows(
                ProgramArgs.UnclosedQuoteException.class,
                () -> ShellCommand.launch("", "x.sh", "bash", "-c 'oops", null));
    }

    @Test
    void anAbsoluteInterpreterMissingHereFallsBackToItsNameOnThePath() {
        ShellCommand.Launch missing = ShellCommand.launch("#!/usr/local/bin/bash -e", "x.sh", "", "", p -> false);
        assertEquals(List.of("bash", "-e"), missing.interpreter());
        ShellCommand.Launch present = ShellCommand.launch("#!/bin/sh", "x.sh", "", "", p -> true);
        assertEquals(List.of("/bin/sh"), present.interpreter());
    }

    @Test
    void theArgvIsInterpreterScriptThenArguments() {
        ShellCommand.Launch l = ShellCommand.launch("#!/bin/bash -e", "x.sh", "", "", null);
        assertEquals(
                List.of("/bin/bash", "-e", "/w/x.sh", "a b", "c"),
                ShellCommand.argv(l, "/w/x.sh", List.of("a b", "c")));
        assertTrue(ShellCommand.isBash("/usr/bin/bash"));
        assertTrue(ShellCommand.isBash("C:\\Git\\bin\\bash.exe"));
        assertFalse(ShellCommand.isBash("/bin/sh"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theShebangsErrexitFlagReallyStopsTheScript(@TempDir Path dir) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/env")), "needs /usr/bin/env");
        Path script = dir.resolve("strict.sh");
        String first = "#!/usr/bin/env -S bash -e";
        Files.writeString(script, first + "\nfalse\necho AFTER\n");
        ShellCommand.Launch l = ShellCommand.launch(first, "strict.sh", "", "", p -> Files.isExecutable(Path.of(p)));
        Process p = new ProcessBuilder(ShellCommand.argv(l, script.toString(), List.of()))
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        assertEquals(1, p.exitValue(), out);
        assertFalse(out.contains("AFTER"), "bash -e stopped at `false`: " + out);
    }
}
