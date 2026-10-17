package com.editora.process;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Stop on a Python program interrupts it first, so its cleanup runs (review C5). */
@DisabledOnOs(OS.WINDOWS)
class ProcessRegistryInterruptProcessTest {

    @TempDir
    Path dir;

    private static Process python(String code) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3"))
                || Files.isExecutable(Path.of("/usr/local/bin/python3")));
        Process p = new ProcessBuilder("python3", "-u", "-c", code).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            assertTrue(r.readLine().startsWith("ready"), "the program started");
        }
        return p;
    }

    @Test
    void anInterruptedPythonProgramRunsItsFinallyAndAtexit() throws Exception {
        Path mark = dir.resolve("marks");
        Process p = python("import atexit, time\n"
                + "atexit.register(lambda: open(r'" + mark + "', 'a').write('atexit;'))\n"
                + "print('ready', flush=True)\n"
                + "try:\n    time.sleep(60)\nfinally:\n    open(r'" + mark + "', 'a').write('finally;')\n");
        ProcessRegistry.interruptThenKillTree(p);
        assertTrue(p.waitFor(10, TimeUnit.SECONDS), "it ended");
        String marks = Files.readString(mark);
        assertTrue(marks.contains("finally;") && marks.contains("atexit;"), marks);
    }

    @Test
    void aProgramThatIgnoresTheInterruptIsStillKilled() throws Exception {
        Process p = python("import signal, time\nsignal.signal(signal.SIGINT, signal.SIG_IGN)\n"
                + "print('ready', flush=True)\ntime.sleep(60)\n");
        ProcessRegistry.interruptThenKillTree(p);
        assertTrue(p.waitFor(15, TimeUnit.SECONDS), "killed after the grace period");
        assertFalse(p.isAlive());
    }
}
