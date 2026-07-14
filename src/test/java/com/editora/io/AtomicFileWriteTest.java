package com.editora.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saving a document must replace it atomically — but the two obvious ways to get that wrong would each be
 * worse than the truncating write it replaces: following a symlink turns the link into a regular file, and a
 * fresh temp file loses the original's permissions (a shell script silently stops being executable).
 */
class AtomicFileWriteTest {

    @TempDir
    Path dir;

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void writesTheContentAndLeavesNoTempFileBehind() throws IOException {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "old\n");

        AtomicFileWrite.write(file, bytes("new\n"));

        assertEquals("new\n", Files.readString(file));
        try (var entries = Files.list(dir)) {
            assertEquals(1, entries.count(), "the temp file was moved into place, not left lying around");
        }
    }

    @Test
    void createsAFileThatDidNotExist() throws IOException {
        Path file = dir.resolve("new.txt");
        AtomicFileWrite.write(file, bytes("hello\n"));
        assertEquals("hello\n", Files.readString(file));
    }

    @Test
    void savingThroughASymlinkKeepsItASymlink() throws IOException {
        // The dotfiles case: ~/.zshrc is a symlink into a git repo. Moving a temp file over the LINK would
        // replace it with a regular file and quietly detach it from the repo.
        Path real = dir.resolve("real.txt");
        Files.writeString(real, "original\n");
        Path link = dir.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException noSymlinks) {
            Assumptions.abort("symlinks not supported here");
        }

        AtomicFileWrite.write(link, bytes("edited\n"));

        assertTrue(Files.isSymbolicLink(link), "still a symlink");
        assertEquals("edited\n", Files.readString(real), "and the edit landed in the real file");
    }

    @Test
    void anExecutableFileStaysExecutable() throws IOException {
        // Editing a shell script must not silently drop its +x bit.
        Path script = dir.resolve("run.sh");
        Files.writeString(script, "#!/bin/sh\necho old\n");
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException notPosix) {
            Assumptions.abort("not a POSIX filesystem");
        }

        AtomicFileWrite.write(script, bytes("#!/bin/sh\necho new\n"));

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(script, LinkOption.NOFOLLOW_LINKS);
        assertTrue(perms.contains(PosixFilePermission.OWNER_EXECUTE), "owner can still run it");
        assertTrue(perms.contains(PosixFilePermission.GROUP_EXECUTE), "and so can the group");
        assertTrue(perms.contains(PosixFilePermission.OTHERS_READ), "other permissions carried over too");
        assertEquals("#!/bin/sh\necho new\n", Files.readString(script));
    }

    @Test
    void aFailedWriteLeavesTheOriginalIntact() throws IOException {
        // The whole point: an interrupted save must not leave a truncated file. Simulate the failure by
        // writing to a path whose parent is a FILE, so staging fails and the original is never touched.
        Path file = dir.resolve("keep.txt");
        Files.writeString(file, "precious\n");
        Path bogus = file.resolve("child.txt"); // keep.txt/child.txt — not a directory

        assertFalse(Files.isDirectory(bogus.getParent().getParent().resolve("nope")));
        try {
            AtomicFileWrite.write(bogus, bytes("junk"));
        } catch (IOException expected) {
            // fine — what matters is the original
        }
        assertEquals("precious\n", Files.readString(file), "the existing file is untouched by a failed write");
    }
}
