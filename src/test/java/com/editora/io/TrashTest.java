package com.editora.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trash a project-tree delete moves files to. A trashed file must be restorable — by the desktop's own
 * file manager, which is why the FreeDesktop layout and {@code .trashinfo} record are pinned here — and a file
 * the trash cannot take must stay where it is, never be deleted.
 */
class TrashTest {

    @TempDir
    Path dir;

    private static List<String> names(Path folder) throws IOException {
        try (var files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the record holds a POSIX path
    void aFreedesktopTrashHoldsTheFileAndARecordOfWhereItCameFrom() throws Exception {
        Path trash = dir.resolve("share/Trash");
        Path file = Files.writeString(
                Files.createDirectories(dir.resolve("my project")).resolve("notes ü.txt"), "x");
        Trash.Bin bin = Trash.freedesktop(trash, null);

        assertTrue(bin.accepts(file));
        bin.trash(file);

        assertFalse(Files.exists(file), "the file left the project");
        assertEquals("x", Files.readString(trash.resolve("files/notes ü.txt")), "…and is whole in the trash");
        String info = Files.readString(trash.resolve("info/notes ü.txt.trashinfo"));
        assertTrue(info.startsWith("[Trash Info]\nPath="), info);
        assertTrue(info.contains("/my%20project/notes%20%C3%BC.txt\n"), "the original path, percent-encoded: " + info);
        assertTrue(info.matches("(?s).*\nDeletionDate=\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\n"), info);
    }

    @Test
    void aSecondFileOfTheSameNameNeverReplacesTheOneAlreadyInTheTrash() throws Exception {
        Path trash = dir.resolve("Trash");
        Trash.Bin bin = Trash.freedesktop(trash, null);
        Path a = Files.writeString(Files.createDirectories(dir.resolve("a")).resolve("report.txt"), "first");
        Path b = Files.writeString(Files.createDirectories(dir.resolve("b")).resolve("report.txt"), "second");

        bin.trash(a);
        bin.trash(b);

        assertEquals("first", Files.readString(trash.resolve("files/report.txt")));
        assertEquals("second", Files.readString(trash.resolve("files/report.2.txt")));
        assertEquals(List.of("report.2.txt.trashinfo", "report.txt.trashinfo"), names(trash.resolve("info")));
        assertTrue(
                Files.readString(trash.resolve("info/report.2.txt.trashinfo")).contains("/b/report.txt"));
    }

    @Test
    void aFileThatIsNotThereIsNotAcceptedAndTrashingItThrowsWithoutSideEffects() {
        Path trash = dir.resolve("Trash");
        Trash.Bin bin = Trash.freedesktop(trash, null);
        Path missing = dir.resolve("missing.txt");

        assertFalse(bin.accepts(missing));
        assertFalse(bin.accepts(null));
        assertThrows(IOException.class, () -> bin.trash(missing));
        assertFalse(Files.exists(trash), "nothing is created for a file that cannot be trashed");
    }

    @Test
    void aFailedMoveLeavesTheFileInPlaceAndNoOrphanRecord() throws Exception {
        Path trash = dir.resolve("Trash");
        // "files" is a regular file, so nothing can be moved into it.
        Files.createDirectories(trash.resolve("info"));
        Files.writeString(trash.resolve("files"), "not a directory");
        Path file = Files.writeString(dir.resolve("keep.txt"), "still here");

        assertThrows(IOException.class, () -> Trash.freedesktop(trash, null).trash(file));

        assertEquals("still here", Files.readString(file), "a trash that fails must not lose the file");
        assertEquals(List.of(), names(trash.resolve("info")));
    }

    @Test
    void aDirectoryTrashMovesTheFileUnderAFreeName() throws Exception {
        Path trash = dir.resolve(".Trash");
        Trash.Bin bin = Trash.directory(trash);
        Path a = Files.writeString(Files.createDirectories(dir.resolve("a")).resolve("x.md"), "first");
        Path b = Files.writeString(Files.createDirectories(dir.resolve("b")).resolve("x.md"), "second");

        assertTrue(bin.accepts(a));
        bin.trash(a);
        bin.trash(b);

        assertEquals("first", Files.readString(trash.resolve("x.md")));
        assertEquals("second", Files.readString(trash.resolve("x.2.md")));
        assertFalse(Files.exists(a) || Files.exists(b));
    }

    /**
     * The home trash only takes files from its own volume. A file elsewhere is handed to {@code gio trash}
     * when that tool exists — and is not accepted at all (so the delete is announced as permanent) when it
     * does not. Needs two volumes: runs where {@code /dev/shm} is one and differs from the temp folder's.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFileOnAnotherVolumeGoesThroughGioOrIsNotAccepted() throws Exception {
        Path shm = Path.of("/dev/shm");
        Assumptions.assumeTrue(Files.isDirectory(shm) && Files.isWritable(shm));
        Assumptions.assumeFalse(Files.getFileStore(shm).equals(Files.getFileStore(dir)));
        Path otherVolume = Files.createTempDirectory(shm, "editora-trash-test");
        try {
            Path trash = otherVolume.resolve("Trash");
            Path file = Files.writeString(dir.resolve("far.txt"), "x");

            assertFalse(Trash.freedesktop(trash, null).accepts(file), "no gio: the trash cannot take it");
            assertThrows(IOException.class, () -> Trash.freedesktop(trash, null).trash(file));
            assertTrue(Files.exists(file));

            Path log = dir.resolve("gio.log");
            Path gio = dir.resolve("gio");
            Files.writeString(gio, "#!/bin/sh\necho \"$1 $2\" > '" + log + "'\nrm -- \"$2\"\n");
            assertTrue(gio.toFile().setExecutable(true));
            Trash.Bin viaGio = Trash.freedesktop(trash, gio);
            assertTrue(viaGio.accepts(file));
            viaGio.trash(file);
            assertEquals("trash " + file + "\n", Files.readString(log), "gio trash <absolute path>");
            assertFalse(Files.exists(trash), "the home trash is not used for another volume's file");

            Path stubborn = Files.writeString(dir.resolve("stubborn.txt"), "kept");
            Files.writeString(
                    gio, "#!/bin/sh\necho 'Trashing on system internal mounts is not supported' >&2\nexit 1\n");
            IOException refused = assertThrows(IOException.class, () -> viaGio.trash(stubborn));
            assertTrue(refused.getMessage().contains("not supported"), refused.getMessage());
            assertEquals("kept", Files.readString(stubborn), "a refusing gio leaves the file in place");
        } finally {
            StagedExport.deleteTree(otherVolume);
        }
    }

    @Test
    void theNoneTrashAcceptsNothingAndNeverDeletes() throws Exception {
        Path file = Files.writeString(dir.resolve("f.txt"), "kept");
        assertFalse(Trash.NONE.accepts(file));
        assertThrows(IOException.class, () -> Trash.NONE.trash(file));
        assertEquals("kept", Files.readString(file));
    }

    @Test
    void thePlatformDecidesWhichTrashIsUsed() throws Exception {
        String home = dir.toString();
        Path file = Files.writeString(dir.resolve("f.txt"), "x");

        assertSame(Trash.NONE, Trash.forPlatform("Windows 11", home, Map.of(), null), "no Recycle Bin without AWT");
        assertSame(Trash.NONE, Trash.forPlatform("Linux", home, Map.of(), "off"), "the test suite's switch");
        assertSame(Trash.NONE, Trash.forPlatform("Linux", null, Map.of(), null));

        Trash.forPlatform("Mac OS X", home, Map.of(), null).trash(file);
        assertEquals("x", Files.readString(dir.resolve(".Trash/f.txt")));

        Path second = Files.writeString(dir.resolve("g.txt"), "y");
        Trash.forPlatform("Linux", home, Map.of(), null).trash(second);
        assertEquals("y", Files.readString(dir.resolve(".local/share/Trash/files/g.txt")));

        Path third = Files.writeString(dir.resolve("h.txt"), "z");
        Path data = dir.resolve("xdg-data");
        Trash.forPlatform("Linux", home, Map.of("XDG_DATA_HOME", data.toString()), null)
                .trash(third);
        assertEquals("z", Files.readString(data.resolve("Trash/files/h.txt")));
    }

    @Test
    void theSuiteItselfRunsWithTheTrashSwitchedOff() {
        assertEquals("off", System.getProperty(Trash.PROPERTY), "tests must not fill the developer's real trash");
        assertSame(Trash.NONE, Trash.system());
    }

    @Test
    void namesAndPathEncoding() {
        assertEquals("/a%20b/%C3%A9%25.txt", Trash.encodePath("/a b/é%.txt"));
        assertEquals("a.txt", Trash.numbered("a.txt", 0));
        assertEquals("a.2.txt", Trash.numbered("a.txt", 1));
        assertEquals("Makefile.3", Trash.numbered("Makefile", 2));
        assertEquals(".env.2", Trash.numbered(".env", 1));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // POSIX paths and the executable bit
    void theRecordFormatAndFindingGio() throws Exception {
        assertEquals(
                "[Trash Info]\nPath=/p/q.txt\nDeletionDate=2026-10-06T09:08:07\n",
                Trash.trashInfo(Path.of("/p/q.txt"), LocalDateTime.of(2026, 10, 6, 9, 8, 7, 999)));

        Path tool =
                Files.writeString(Files.createDirectories(dir.resolve("bin")).resolve("gio"), "#!/bin/sh\n");
        assertNull(Trash.findOnPath("gio", dir.resolve("bin").toString()), "not executable yet");
        assertTrue(tool.toFile().setExecutable(true));
        assertEquals(
                tool,
                Trash.findOnPath("gio", dir.resolve("nowhere") + java.io.File.pathSeparator + dir.resolve("bin")));
        assertNull(Trash.findOnPath("gio", ""));
    }
}
