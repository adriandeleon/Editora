package com.editora.io;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.editora.io.SaveBackups.Leftover;
import com.editora.io.SaveBackups.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The corners of the file layer that the main suites pass by: an in-place overwrite on a filesystem that is
 * not the local disk, a backup whose owner is remote, unknown or unparseable, and the choice of trash for a
 * platform whose environment is incomplete.
 */
class IoEdgeCasesTest {

    @TempDir
    Path dir;

    // --- AtomicFileWrite.overwriteDurably ---

    @Test
    void anInPlaceOverwriteOnlyOverwritesItNeverCreates() {
        Path missing = dir.resolve("never-existed.txt");

        assertThrows(NoSuchFileException.class, () -> AtomicFileWrite.overwriteDurably(missing, bytes("new")));

        assertFalse(Files.exists(missing));
    }

    @Test
    void anInPlaceOverwriteWithShorterContentLeavesNoTailOfTheOldBytes() throws IOException {
        Path file = Files.writeString(dir.resolve("long.txt"), "a much longer previous version\n");

        AtomicFileWrite.overwriteDurably(file, bytes("short\n"));

        assertEquals("short\n", Files.readString(file));
    }

    @Test
    void anInPlaceOverwriteOnAnotherFilesystemCompletesEvenWhenTheThreadWasInterrupted() throws IOException {
        Path archive = dir.resolve("other-filesystem.zip");
        try (FileSystem zip = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
            Path inside = Files.writeString(zip.getPath("/note.txt"), "a much longer previous version\n");

            AtomicFileWrite.overwriteDurably(inside, bytes("first\n"));
            assertEquals("first\n", Files.readString(inside));

            // The window closing while an auto-save runs interrupts the writing thread. The write must still
            // finish (a cut-short one leaves an empty file), and the interrupt must still be there afterwards.
            Thread.currentThread().interrupt();
            try {
                AtomicFileWrite.overwriteDurably(inside, bytes("second\n"));
                assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is handed back, not swallowed");
            } finally {
                Thread.interrupted(); // clear it for the rest of the suite
            }
            assertEquals("second\n", Files.readString(inside));
        }
    }

    // --- SaveBackups ---

    private Leftover leftover(String target) throws IOException {
        Path backup = Files.writeString(dir.resolve("doc.txt" + SaveBackups.SUFFIX), "previous bytes");
        return new Leftover(backup, target, Instant.now());
    }

    @Test
    void aBackupOfARemoteFileIsNeitherComparedNorRestoredFromHere() throws IOException {
        Leftover remote = leftover("sftp://build-host/srv/app/doc.txt");

        assertTrue(remote.remote());
        assertEquals(State.REMOTE, SaveBackups.state(remote));
        assertNull(SaveBackups.targetPath(remote));
        IOException refused = assertThrows(IOException.class, () -> SaveBackups.restore(remote, dir));
        assertTrue(refused.getMessage().contains(remote.backup().toString()), refused.getMessage());
        assertEquals("previous bytes", Files.readString(remote.backup()), "the backup is kept");
    }

    @Test
    void aBackupWhoseOwnerIsUnknownOrUnparseableIsKeptAndReportedAsUnknown() throws IOException {
        Leftover unknown = leftover(null);
        assertFalse(unknown.remote());
        assertEquals(State.UNKNOWN_TARGET, SaveBackups.state(unknown));
        assertNull(SaveBackups.targetPath(unknown));
        assertThrows(IOException.class, () -> SaveBackups.restore(unknown, dir));

        Leftover unparseable = leftover("bad\0path");
        assertNull(SaveBackups.targetPath(unparseable));
        assertEquals(State.UNKNOWN_TARGET, SaveBackups.state(unparseable));
        assertTrue(Files.exists(unparseable.backup()));
    }

    @Test
    void aBackupIsComparedWithItsFileByteForByte() throws IOException {
        Path target = dir.resolve("doc.txt");
        Leftover leftover = leftover(target.toString());

        assertEquals(State.TARGET_MISSING, SaveBackups.state(leftover));
        Files.writeString(target, "previous bytes");
        assertEquals(State.SAME, SaveBackups.state(leftover));
        Files.writeString(target, "previous bytez");
        assertEquals(State.DIFFERENT, SaveBackups.state(leftover), "same length, different bytes");
        Files.writeString(target, "torn");
        assertEquals(State.DIFFERENT, SaveBackups.state(leftover));

        // A file that is there but cannot be read as one (a folder took its place) is not shown to be
        // redundant: the backup is kept and the user is asked.
        Files.delete(target);
        Files.createDirectory(target);
        assertEquals(State.DIFFERENT, SaveBackups.state(leftover));
    }

    @Test
    void aScanSkipsFoldersNamedLikeBackupsAndTreatsAnEmptyNoteAsNoNote() throws IOException {
        Files.createDirectory(dir.resolve("folder" + SaveBackups.SUFFIX));
        Path backup = Files.writeString(dir.resolve("real.txt" + SaveBackups.SUFFIX), "bytes");
        Files.writeString(backup.resolveSibling(backup.getFileName() + SaveBackups.NOTE_SUFFIX), "  \n");

        List<Leftover> found = SaveBackups.scan(dir);

        assertEquals(List.of(backup), found.stream().map(Leftover::backup).toList());
        assertNull(found.get(0).target(), "a blank note names no file");
        assertEquals(List.of(), SaveBackups.scan(null));
        assertEquals(List.of(), SaveBackups.scan(backup), "a file is not a folder of backups");
    }

    @Test
    void theNoteBesideABackupNamesItsFileAndForgettingRemovesOnlyTheNote() throws IOException {
        Path backup = Files.writeString(dir.resolve("doc.txt" + SaveBackups.SUFFIX), "previous");
        Path target = dir.resolve("doc.txt");

        SaveBackups.noteTarget(backup, target);

        Leftover found = SaveBackups.scan(dir).get(0);
        assertEquals(target.toAbsolutePath().toString(), found.target());
        assertEquals(target.toAbsolutePath(), SaveBackups.targetPath(found));

        SaveBackups.forget(backup);
        assertTrue(Files.exists(backup));
        assertNull(SaveBackups.scan(dir).get(0).target());
        SaveBackups.forget(backup); // nothing left to forget: no error
    }

    // --- Trash ---

    @Test
    void thereIsNoTrashWhenItIsSwitchedOffOrThePlatformHasNoneOrHomeIsUnknown() {
        Map<String, String> env = Map.of();
        assertSame(Trash.NONE, Trash.forPlatform("Linux", dir.toString(), env, " OFF "));
        assertSame(Trash.NONE, Trash.forPlatform("Linux", null, env, null));
        assertSame(Trash.NONE, Trash.forPlatform("Linux", "  ", env, null));
        assertSame(Trash.NONE, Trash.forPlatform("Windows 11", dir.toString(), env, null));
        assertSame(Trash.NONE, Trash.forPlatform("Linux", "bad\0home", env, null), "an unusable home directory");
    }

    @Test
    void onMacOsTheTrashIsTheHomeFolderDotTrash() throws IOException {
        Path home = Files.createDirectory(dir.resolve("home"));
        Path file = Files.writeString(home.resolve("old.txt"), "kept");

        Trash.Bin bin = Trash.forPlatform("Mac OS X", home.toString(), null, null);
        assertTrue(bin.accepts(file));
        bin.trash(file);

        assertFalse(Files.exists(file));
        assertEquals("kept", Files.readString(home.resolve(".Trash").resolve("old.txt")));
    }

    @Test
    void onLinuxTheTrashFollowsXdgDataHomeOnlyWhenItIsAnAbsolutePath() throws IOException {
        Path home = Files.createDirectory(dir.resolve("home"));
        Path data = Files.createDirectory(dir.resolve("xdg-data"));

        Path first = Files.writeString(home.resolve("first.txt"), "one");
        Trash.forPlatform("Linux", home.toString(), Map.of("XDG_DATA_HOME", data.toString()), null)
                .trash(first);
        assertEquals("one", Files.readString(data.resolve("Trash/files/first.txt")));

        Path second = Files.writeString(home.resolve("second.txt"), "two");
        Trash.forPlatform("Linux", home.toString(), Map.of("XDG_DATA_HOME", "relative/data"), null)
                .trash(second);
        assertEquals(
                "two",
                Files.readString(home.resolve(".local/share/Trash/files/second.txt")),
                "the specification ignores a relative XDG_DATA_HOME");

        Path third = Files.writeString(home.resolve("third.txt"), "three");
        Trash.forPlatform(null, home.toString(), null, null).trash(third);
        assertEquals("three", Files.readString(home.resolve(".local/share/Trash/files/third.txt")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the executable bit
    void aToolIsFoundOnThePathSkippingEmptyAndUnusableEntriesAndNonExecutables() throws IOException {
        Path empty = Files.createDirectory(dir.resolve("empty"));
        Path notRunnable = Files.createDirectory(dir.resolve("not-runnable"));
        Files.writeString(notRunnable.resolve("gio"), "#!/bin/sh\n");
        Path bin = Files.createDirectory(dir.resolve("bin"));
        Path gio = Files.writeString(bin.resolve("gio"), "#!/bin/sh\n");
        assertTrue(gio.toFile().setExecutable(true));
        String sep = File.pathSeparator;

        assertEquals(
                gio,
                Trash.findOnPath("gio", sep + " " + sep + "bad\0entry" + sep + empty + sep + notRunnable + sep + bin));
        assertNull(Trash.findOnPath("gio", empty + sep + notRunnable));
        assertNull(Trash.findOnPath("gio", null));
        assertNull(Trash.findOnPath("gio", "  "));
    }

    @Test
    void aDirectoryTrashThatHasRunOutOfNamesRefusesAndLeavesTheFileInPlace() throws IOException {
        Path trash = Files.createDirectory(dir.resolve("trash"));
        for (int attempt = 0; attempt < 1000; attempt++) {
            Files.createFile(trash.resolve(Trash.numbered("report.txt", attempt)));
        }
        Path file = Files.writeString(dir.resolve("report.txt"), "still here");

        IOException refused =
                assertThrows(IOException.class, () -> Trash.directory(trash).trash(file));

        assertTrue(refused.getMessage().contains("report.txt"), refused.getMessage());
        assertEquals("still here", Files.readString(file), "trashing never deletes");
    }

    @Test
    void aFreedesktopTrashSkipsNamesTakenByAFileOrByALeftoverRecord() throws IOException {
        Path trash = dir.resolve("Trash");
        Files.createDirectories(trash.resolve("files"));
        Files.createDirectories(trash.resolve("info"));
        Files.writeString(trash.resolve("files/notes.txt"), "an earlier deletion");
        Files.writeString(trash.resolve("info/notes.2.txt.trashinfo"), "a record whose file is gone");
        Path file = Files.writeString(dir.resolve("notes.txt"), "today's");

        Trash.freedesktop(trash, null).trash(file);

        assertEquals("an earlier deletion", Files.readString(trash.resolve("files/notes.txt")));
        assertEquals("a record whose file is gone", Files.readString(trash.resolve("info/notes.2.txt.trashinfo")));
        assertEquals("today's", Files.readString(trash.resolve("files/notes.3.txt")), "the first name free in both");
        assertTrue(Files.readString(trash.resolve("info/notes.3.txt.trashinfo")).contains("notes.txt"));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void overwritingKeepsExactlyTheNewBytes() throws IOException {
        Path file = Files.write(dir.resolve("data.bin"), new byte[] {1, 2, 3, 4, 5, 6});

        AtomicFileWrite.overwriteDurably(file, new byte[] {9, 8});

        assertArrayEquals(new byte[] {9, 8}, Files.readAllBytes(file));
    }
}
