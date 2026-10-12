package com.editora.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-entry readability decision a launch takes off the FX thread, and the one-shot hand-over of its
 * result to the window whose session it scanned.
 */
class SessionScanTest {

    private static final Function<String, Path> RESOLVE = s -> s.startsWith("sftp://") ? null : Path.of(s);

    @Test
    void sortsEntriesIntoOpenableMissingAndDropped() {
        var readable = new WorkspaceState.OpenFile("/home/me/a.txt", 3, false);
        var unmounted = new WorkspaceState.OpenFile("/vol/x/b.txt", 0, true); // its folder is not there
        var deleted = new WorkspaceState.OpenFile("/home/me/gone.txt", 0, false); // folder there, file not
        var remote = new WorkspaceState.OpenFile("sftp://host/c.txt", 0, false); // no resolver yet
        var blank = new WorkspaceState.OpenFile("", 0, false);
        List<WorkspaceState.OpenFile> files = List.of(readable, unmounted, deleted, remote, blank);

        Set<Path> readableSet = Set.of(Path.of("/home/me/a.txt"));
        // Absolute, the way the scan asks about a folder: on Windows "/home/me" is relative to a drive.
        Set<Path> dirs = Set.of(Path.of("/home/me").toAbsolutePath());
        SessionScan scan =
                SessionScan.of(files, RESOLVE, readableSet::contains, dirs::contains, p -> !readableSet.contains(p));

        assertEquals(List.of(readable), scan.openable());
        assertEquals(
                List.of(unmounted), scan.missing(), "an unmounted volume's entry is kept, a deleted file's is not");
        assertTrue(scan.covers(files));
        assertFalse(
                scan.covers(new java.util.ArrayList<>(files)),
                "the scan is of the list object it was given, not an equal one");
    }

    @Test
    void keepUnreadableDropsOnlyKnownDeletions() {
        Path file = Path.of("/p/q/file.txt");
        assertTrue(SessionScan.keepUnreadable(file, p -> false, p -> true), "folder missing: nothing learned");
        assertFalse(SessionScan.keepUnreadable(file, p -> true, p -> true), "folder there, file not: gone");
        assertTrue(SessionScan.keepUnreadable(file, p -> true, p -> false), "folder there, file there (unreadable)");
        assertFalse(SessionScan.keepUnreadable(null, p -> true, p -> true), "unresolvable: not restorable");
    }

    @Test
    void realFilesystemScan(@TempDir Path dir) throws Exception {
        Path present = java.nio.file.Files.writeString(dir.resolve("present.txt"), "x");
        var a = new WorkspaceState.OpenFile(present.toString(), 0, false);
        var b = new WorkspaceState.OpenFile(dir.resolve("deleted.txt").toString(), 0, false);
        var c = new WorkspaceState.OpenFile(dir.resolve("no-such-dir/file.txt").toString(), 0, false);
        SessionScan scan = SessionScan.of(List.of(a, b, c), Path::of);
        assertEquals(List.of(a), scan.openable());
        assertEquals(List.of(c), scan.missing());
    }

    @Test
    void configManagerHandsTheScanOverOnceAndOnlyForTheSameSession(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        try {
            config.load();
            List<WorkspaceState.OpenFile> files = config.getWorkspaceState().getOpenFiles();
            SessionScan scan = SessionScan.of(files, Path::of);
            config.setStartupSessionScan(scan);
            assertSame(scan, config.takeStartupSessionScan());
            assertNull(config.takeStartupSessionScan(), "one-shot");

            config.setStartupSessionScan(scan);
            config.useDefaultWorkspaceStateFile(); // the session is re-read: another list object
            assertNull(config.takeStartupSessionScan(), "a scan of the previous session is not handed over");
        } finally {
            config.shared().shutdown();
        }
    }
}
