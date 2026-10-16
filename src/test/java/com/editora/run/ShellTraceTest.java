package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Run with Trace's launch, and shell error / trace lines as console links. */
class ShellTraceTest {

    private static ShellCommand.Launch launch(String shebang, String name) {
        return ShellCommand.launch(shebang, name, "", "", null);
    }

    @Test
    void bashErrorsDashErrorsAndTraceLinesAreLinks() {
        // These were not links: only Java, Python and Node frames were.
        assertEquals(
                new StackTraceLinks.Link("/tmp/a b/x.sh", 7),
                strip(StackTraceLinks.parse("/tmp/a b/x.sh: line 7: nosuch: command not found")));
        assertEquals(new StackTraceLinks.Link("/w/x.sh", 2), strip(StackTraceLinks.parse("/w/x.sh: 2: [[: not found")));
        assertEquals(new StackTraceLinks.Link("/w/x.sh", 12), strip(StackTraceLinks.parse("+/w/x.sh:12: echo hi")));
        assertEquals(new StackTraceLinks.Link("lib.sh", 3), strip(StackTraceLinks.parse("++lib.sh:3: f 1")));
        assertTrue(StackTraceLinks.isTraceLine("+/w/x.sh:12: echo hi"));
        assertFalse(StackTraceLinks.isTraceLine("+ echo hi"), "dash's plain trace has no location");
        assertNull(StackTraceLinks.parse("just some output: 42"));
    }

    private static StackTraceLinks.Link strip(StackTraceLinks.Link l) {
        return l == null ? null : new StackTraceLinks.Link(l.file(), l.line());
    }

    @Test
    void traceAddsXOnceAndOnlyForShellsThatCanTrace() {
        assertEquals(
                List.of("bash", "-x", "-e"),
                ShellCommand.withTrace(launch("#!/usr/bin/env -S bash -e", "x.sh"))
                        .interpreter());
        assertEquals(
                List.of("/bin/bash", "-eux"),
                ShellCommand.withTrace(launch("#!/bin/bash -eux", "x.sh")).interpreter());
        assertTrue(ShellCommand.traces(launch("#!/bin/bash -o xtrace", "x.sh")));
        assertFalse(ShellCommand.traces(launch("#!/bin/bash -e -o pipefail", "x.sh")));
        assertTrue(ShellCommand.canTrace(launch("#!/bin/sh", "x.sh")));
        assertFalse(ShellCommand.canTrace(launch("", "x.zsh")), "zsh: not supported");
        assertFalse(ShellCommand.canTrace(launch("#!/usr/bin/env python3", "x.sh")));
        assertEquals("zsh", ShellCommand.interpreterName(launch("#!/usr/bin/env zsh", "x.sh")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aBashTraceNamesTheFileAndLineOfEveryCommand(@TempDir Path dir) throws Exception {
        assumeTrue(new ProcessBuilder("bash", "-c", "exit 0").start().waitFor() == 0, "needs bash");
        Path script = dir.resolve("t.sh");
        Files.writeString(script, "#!/usr/bin/env bash\nx=1\nf() { echo in; }\nf\n");
        ShellCommand.Launch l = ShellCommand.withTrace(launch("#!/usr/bin/env bash", "t.sh"));
        ProcessBuilder pb = new ProcessBuilder(ShellCommand.argv(l, script.toString(), List.of()));
        pb.environment().put("PS4", ShellCommand.TRACE_PROMPT);
        Process p = pb.start();
        String err = new String(p.getErrorStream().readAllBytes());
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        List<StackTraceLinks.Link> links =
                err.lines().map(StackTraceLinks::parse).toList();
        assertEquals(
                List.of(
                        new StackTraceLinks.Link(script.toString(), 2),
                        new StackTraceLinks.Link(script.toString(), 4),
                        new StackTraceLinks.Link(script.toString(), 3)),
                links.stream().map(ShellTraceTest::strip).toList(),
                err);
    }

    @Test
    void envOptionsWithAValueAreNotTheInterpreter() {
        // `-u NAME` made NAME the interpreter Run started, while detection said bash.
        ShellCommand.Launch unset = launch("#!/usr/bin/env -u SECRET bash -e", "x.sh");
        assertEquals(List.of("bash", "-e"), unset.interpreter());
        assertTrue(unset.env().containsKey("SECRET"));
        assertNull(unset.env().get("SECRET"), "unset for the child");
        assertNull(launch("#!/usr/bin/env --unset=A -uB bash", "x.sh").env().get("B"));
        ShellCommand.Launch chdir = launch("#!/usr/bin/env -C build bash", "x.sh");
        assertEquals(List.of("bash"), chdir.interpreter());
        assertEquals("build", chdir.chdir());
        assertEquals("out", launch("#!/usr/bin/env --chdir=out sh", "x.sh").chdir());
        assertEquals(
                List.of("bash"),
                launch("#!/usr/bin/env -P /opt/bin bash", "x.sh").interpreter());
        assertEquals(
                List.of("bash"), launch("#!/usr/bin/env -a myname bash", "x.sh").interpreter());
        assertEquals(
                List.of("bash", "-e"),
                launch("#!/usr/bin/env --split-string bash -e", "x.sh").interpreter());
    }

    @Test
    void busyboxRunsItsShellApplet() {
        ShellCommand.Launch l = launch("#!/bin/busybox sh", "x.sh");
        assertEquals(List.of("/bin/busybox", "sh"), l.interpreter());
        assertTrue(ShellCommand.canTrace(l));
        assertEquals(
                List.of("/bin/busybox", "sh", "-x"), ShellCommand.withTrace(l).interpreter());
        assertEquals("busybox sh", ShellCommand.interpreterName(l));
    }
}
