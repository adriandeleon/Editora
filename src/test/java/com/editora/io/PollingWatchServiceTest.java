package com.editora.io;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The polling fallback reports what a native watcher would, and a refused native watcher falls back to it. */
class PollingWatchServiceTest {

    private static final WatchEvent.Kind<?>[] ALL = {
        StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY
    };

    /** The events of the next signalled key, as "KIND name" (waits up to 5 s). */
    private static List<String> next(WatchService ws, Path dir) throws InterruptedException {
        WatchKey key = ws.poll(5, TimeUnit.SECONDS);
        assertNotNull(key, "an event was reported");
        assertEquals(dir, key.watchable());
        List<String> out = new ArrayList<>();
        for (WatchEvent<?> e : key.pollEvents()) {
            out.add(e.kind().name() + " " + e.context());
        }
        assertTrue(key.reset());
        return out;
    }

    @Test
    void createModifyAndDeleteAreReported(@TempDir Path dir) throws Exception {
        try (PollingWatchService ws = new PollingWatchService(30)) {
            WatchKey key = WatchServices.register(dir, ws, ALL);
            assertSame(key, ws.register(dir, ALL), "one key per folder");
            Path file = dir.resolve("a.txt");
            Files.writeString(file, "1");
            assertEquals(List.of("ENTRY_CREATE a.txt"), next(ws, dir));
            Files.writeString(file, "12345");
            assertEquals(List.of("ENTRY_MODIFY a.txt"), next(ws, dir));
            Files.delete(file);
            assertEquals(List.of("ENTRY_DELETE a.txt"), next(ws, dir));
            key.cancel();
            Files.writeString(dir.resolve("b.txt"), "x");
            assertNull(ws.poll(200, TimeUnit.MILLISECONDS), "a cancelled folder reports nothing");
        }
    }

    /** A save renames a new file over the old one, the same size: reported, by its newer time. */
    @Test
    void aFileReplacedByARenameIsReported(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("open.txt"), "v1");
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 60_000)); // saved earlier
        try (PollingWatchService ws = new PollingWatchService(30)) {
            ws.register(dir, ALL);
            replaceByRename(file, "v2", false);
            assertModifyReported(ws, dir, "open.txt");
        }
    }

    /** The same, within the same millisecond: only the file key tells the two files apart. */
    @Test
    void aFileReplacedByARenameInTheSameMillisecondIsReported(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("open.txt"), "v1");
        // Windows has no file key (and NTFS tunnelling keeps the creation time): the documented limit of Stamp.
        assumeTrue(
                Files.readAttributes(file, BasicFileAttributes.class).fileKey() != null,
                "the file system gives files a key");
        try (PollingWatchService ws = new PollingWatchService(30)) {
            ws.register(dir, ALL);
            replaceByRename(file, "v2", true);
            assertModifyReported(ws, dir, "open.txt");
        }
    }

    /** Writes {@code text} to a temporary file beside {@code file}, then renames it over {@code file}. */
    private static void replaceByRename(Path file, String text, boolean sameTime) throws IOException {
        Path tmp = Files.writeString(file.resolveSibling(file.getFileName() + ".tmp"), text);
        if (sameTime) {
            Files.setLastModifiedTime(tmp, Files.getLastModifiedTime(file)); // the same time, to the millisecond
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void assertModifyReported(WatchService ws, Path dir, String name) throws InterruptedException {
        List<String> events = new ArrayList<>();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!events.contains("ENTRY_MODIFY " + name) && System.nanoTime() < end) {
            events.addAll(next(ws, dir)); // the temporary file may show up in a round of its own
        }
        assertTrue(events.contains("ENTRY_MODIFY " + name), String.valueOf(events));
    }

    @Test
    void onlyTheRegisteredKindsAreReported(@TempDir Path dir) throws Exception {
        try (PollingWatchService ws = new PollingWatchService(30)) {
            ws.register(dir, StandardWatchEventKinds.ENTRY_DELETE);
            Path file = dir.resolve("a.txt");
            Files.writeString(file, "1");
            assertNull(ws.poll(200, TimeUnit.MILLISECONDS), "a create was not asked for");
            Files.delete(file);
            assertEquals(List.of("ENTRY_DELETE a.txt"), next(ws, dir));
        }
    }

    @Test
    void closingUnblocksATake(@TempDir Path dir) throws Exception {
        PollingWatchService ws = new PollingWatchService(30);
        ws.register(dir, ALL);
        ws.close();
        assertThrows(ClosedWatchServiceException.class, ws::take);
        assertThrows(ClosedWatchServiceException.class, () -> ws.register(dir, ALL));
    }

    /** "User limit of inotify instances reached": the folder is still watched, by polling, and the reason kept. */
    @Test
    void aRefusedNativeWatcherFallsBackToPolling() throws Exception {
        String limit = "User limit of inotify instances reached or too many open files";
        WatchServices.Opener previous = WatchServices.setOpenerForTest(() -> {
            throw new IOException(limit);
        });
        try (WatchService ws = WatchServices.open()) {
            assertInstanceOf(PollingWatchService.class, ws);
            assertTrue(WatchServices.isPolling(ws));
            assertEquals(limit, WatchServices.fallbackReason());
            assertEquals(limit, WatchServices.probe());
        } finally {
            WatchServices.setOpenerForTest(previous);
        }
        assertNull(WatchServices.fallbackReason());
    }
}
