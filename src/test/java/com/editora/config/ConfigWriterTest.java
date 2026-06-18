package com.editora.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigWriterTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void writeAtomicCreatesParentDirsAndWritesContent(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nested/sub/settings.toml");
        ConfigWriter.writeAtomic(file, bytes("a = 1\n"));
        assertTrue(Files.exists(file));
        assertArrayEquals(bytes("a = 1\n"), Files.readAllBytes(file));
        assertFalse(Files.exists(file.resolveSibling("settings.toml.tmp")), "temp file is moved away, not left behind");
    }

    @Test
    void writeAtomicOverwritesExisting(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("x.json");
        ConfigWriter.writeAtomic(file, bytes("old"));
        ConfigWriter.writeAtomic(file, bytes("new"));
        assertEquals("new", Files.readString(file));
    }

    @Test
    void enqueueThenFlushWritesLatestBytes(@TempDir Path dir) throws IOException {
        ConfigWriter w = new ConfigWriter();
        Path file = dir.resolve("settings.toml");
        // A burst of writes to the same file coalesces to the last one.
        for (int i = 0; i < 50; i++) {
            w.enqueue(file, bytes("v=" + i + "\n"));
        }
        w.flush();
        assertEquals("v=49\n", Files.readString(file), "the latest queued bytes win after a flush");
        w.shutdown();
    }

    @Test
    void flushWritesAllPendingPaths(@TempDir Path dir) throws IOException {
        ConfigWriter w = new ConfigWriter();
        Path a = dir.resolve("settings.toml");
        Path b = dir.resolve("projects/p1.json");
        w.enqueue(a, bytes("A"));
        w.enqueue(b, bytes("B"));
        w.flush();
        assertEquals("A", Files.readString(a));
        assertEquals("B", Files.readString(b), "a queued write to a sub-dir is created + written");
        w.shutdown();
    }

    @Test
    void flushWithNothingPendingIsANoOp() {
        ConfigWriter w = new ConfigWriter();
        w.flush(); // must not throw or block
        w.shutdown();
    }

    @Test
    void enqueueAfterShutdownStillWritesSynchronously(@TempDir Path dir) throws IOException {
        ConfigWriter w = new ConfigWriter();
        w.shutdown();
        Path file = dir.resolve("late.json");
        w.enqueue(file, bytes("late")); // executor rejected → synchronous fallback
        assertEquals("late", Files.readString(file));
    }
}
