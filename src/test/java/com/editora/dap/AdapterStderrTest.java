package com.editora.dap;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stderr tail of a stdio debug adapter. Until this test, its lines were covered only when some real adapter
 * in another test happened to write to stderr in time, so {@code com.editora.dap}'s coverage moved from run to run
 * and fell under its floor on a pull request that did not touch the package.
 */
class AdapterStderrTest {

    @Test
    void blankLinesAreDroppedAndLongOnesCut() {
        AdapterStderr tail = new AdapterStderr();
        tail.add(null);
        tail.add("   ");
        tail.add("x".repeat(AdapterStderr.MAX_LINE_CHARS + 10));
        assertEquals("x".repeat(AdapterStderr.MAX_LINE_CHARS) + "…", tail.text());
    }

    @Test
    void onlyTheLastLinesAreKeptOldestFirst() {
        AdapterStderr tail = new AdapterStderr();
        for (int i = 1; i <= AdapterStderr.MAX_LINES + 5; i++) {
            tail.add("line " + i);
        }
        String[] kept = tail.text().split("\n");
        assertEquals(AdapterStderr.MAX_LINES, kept.length);
        assertEquals("line 6", kept[0]);
        assertEquals("line " + (AdapterStderr.MAX_LINES + 5), kept[kept.length - 1]);
    }

    @Test
    void theSuffixIsEmptyWhenTheAdapterSaidNothing() {
        assertEquals("", AdapterStderr.suffix(null));
        assertEquals("", AdapterStderr.suffix(new AdapterStderr()));
        AdapterStderr tail = new AdapterStderr();
        tail.add("boom");
        assertEquals("\nboom", AdapterStderr.suffix(tail));
    }

    @Test
    void drainReadsAProcesssStandardError() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-version").start(); // the version goes to stderr
        AdapterStderr tail = AdapterStderr.drain(process);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "java -version exits");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (tail.text().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(tail.text().contains("version"), tail.text());
    }
}
