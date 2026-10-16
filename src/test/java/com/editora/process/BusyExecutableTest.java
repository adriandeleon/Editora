package com.editora.process;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Waiting out "Text file busy". The busy file here is a script this test holds open for writing, which is
 * what a child forked by another thread does to a file written a moment before (see {@link BusyExecutable}).
 */
class BusyExecutableTest {

    private static Path script(Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("tool"), "#!/bin/sh\nexit 3\n");
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aScriptThatIsReleasedWhileWaitingStarts(@TempDir Path dir) throws Exception {
        Path tool = script(dir);
        FileChannel writer = FileChannel.open(tool, StandardOpenOption.WRITE);
        IOException busy = assertThrows(IOException.class, () -> new ProcessBuilder(tool.toString()).start());
        assertTrue(BusyExecutable.isBusy(busy), "an open writer makes the exec fail: " + busy.getMessage());

        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(50);
                writer.close();
            } catch (InterruptedException | IOException ignored) {
                // the test fails on the start below
            }
        });
        releaser.start();
        Process p = BusyExecutable.start(new ProcessBuilder(tool.toString()));
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        assertEquals(3, p.exitValue());
        releaser.join();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aScriptThatStaysBusyFailsWithTheBusyError(@TempDir Path dir) throws Exception {
        Path tool = script(dir);
        try (FileChannel ignored = FileChannel.open(tool, StandardOpenOption.WRITE)) {
            IOException e = assertThrows(
                    IOException.class, () -> BusyExecutable.start(new ProcessBuilder(tool.toString()), 3, 1));
            assertTrue(BusyExecutable.isBusy(e), e.getMessage());
        }
    }

    @Test
    void anyOtherFailureIsReportedAtOnce(@TempDir Path dir) {
        long started = System.nanoTime();
        ProcessBuilder missing = new ProcessBuilder(dir.resolve("no-such-tool").toString());
        IOException e = assertThrows(IOException.class, () -> BusyExecutable.start(missing, 8, 1_000));
        assertFalse(BusyExecutable.isBusy(e), e.getMessage());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000, "no wait for a missing file");
    }

    @Test
    void theBusyErrorIsRecognisedInBothJdkSpellings() {
        assertTrue(BusyExecutable.isBusy(
                new IOException("Cannot run program \"/t/x\": Exec failed, error: 26 (Text file busy)")));
        assertTrue(BusyExecutable.isBusy(new IOException("Cannot run program \"/t/x\": error=26, Text file busy")));
        assertFalse(BusyExecutable.isBusy(
                new IOException("Cannot run program \"/t/x\": error=2, No such file or directory")));
        assertFalse(BusyExecutable.isBusy(new IOException("Cannot run program \"/t/x\": error: 260 (made up)")));
        assertFalse(BusyExecutable.isBusy(new IOException((String) null)));
    }
}
