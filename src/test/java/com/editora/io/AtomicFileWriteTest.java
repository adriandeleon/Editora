package com.editora.io;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        // Fail after a real target's staging file has been partially written. This exercises the valuable
        // target itself rather than an unrelated invalid child path.
        Path file = dir.resolve("keep.txt");
        Files.writeString(file, "precious\n");
        AtomicFileWrite.FileOperations files = new DelegatingFileOperations() {
            @Override
            public void write(Path path, byte[] content) throws IOException {
                assertFalse(path.equals(file), "the original must never be opened for a staged write");
                Files.write(path, Arrays.copyOf(content, 2));
                throw new IOException("simulated short staged write");
            }
        };

        IOException failure = assertThrows(
                IOException.class, () -> AtomicFileWrite.writeIf(file, bytes("replacement"), () -> true, files));

        assertEquals("simulated short staged write", failure.getMessage());
        assertEquals("precious\n", Files.readString(file), "the existing file is untouched by a failed write");
        assertEquals(1, entryCount(), "the failed staging file was cleaned up");
    }

    private enum FailureStage {
        TEMP_CREATION,
        BOTH_MOVES,
        CLEANUP
    }

    @ParameterizedTest(name = "{0} failure preserves the original")
    @EnumSource(FailureStage.class)
    void replacementStageFailuresAreReportedWithoutChangingTheOriginal(FailureStage stage) throws IOException {
        Path file = Files.writeString(dir.resolve("valuable-" + stage + ".txt"), "original");
        AtomicFileWrite.FileOperations files = new DelegatingFileOperations() {
            @Override
            public Path createTempFile(Path directory, String prefix, String suffix, FileAttribute<?>... attributes)
                    throws IOException {
                if (stage == FailureStage.TEMP_CREATION) {
                    throw new IOException("staging denied");
                }
                return super.createTempFile(directory, prefix, suffix, attributes);
            }

            @Override
            public void move(Path source, Path target, CopyOption... options) throws IOException {
                if (stage == FailureStage.BOTH_MOVES) {
                    throw new IOException(
                            hasOption(options, StandardCopyOption.ATOMIC_MOVE)
                                    ? "atomic move failed"
                                    : "fallback move failed");
                }
                super.move(source, target, options);
            }

            @Override
            public boolean deleteIfExists(Path path) throws IOException {
                if (stage == FailureStage.CLEANUP) {
                    throw new IOException("cleanup failed");
                }
                return super.deleteIfExists(path);
            }
        };

        assertThrows(
                IOException.class,
                () -> AtomicFileWrite.writeIf(file, bytes("replacement"), () -> stage != FailureStage.CLEANUP, files));

        assertEquals("original", Files.readString(file));
        if (stage != FailureStage.CLEANUP) {
            assertEquals(1, entryCount(), "failed replacement staging must be cleaned up when possible");
        }
    }

    @Test
    void aNewFileCanStillBeCreatedWhenStagingIsUnavailable() throws IOException {
        Path file = dir.resolve("new-without-staging.txt");
        AtomicFileWrite.FileOperations files = new DelegatingFileOperations() {
            @Override
            public Path createTempFile(Path directory, String prefix, String suffix, FileAttribute<?>... attributes)
                    throws IOException {
                throw new IOException("staging denied");
            }
        };

        assertTrue(AtomicFileWrite.writeIf(file, bytes("new content"), () -> true, files));

        assertEquals("new content", Files.readString(file));
    }

    @Test
    void unstagedCreationCannotOverwriteAFileThatAppearsAfterTheExistenceCheck() throws IOException {
        Path file = dir.resolve("raced-new-file.txt");
        AtomicFileWrite.FileOperations files = new DelegatingFileOperations() {
            @Override
            public Path createTempFile(Path directory, String prefix, String suffix, FileAttribute<?>... attributes)
                    throws IOException {
                throw new IOException("staging denied");
            }

            @Override
            public boolean exists(Path path, LinkOption... options) {
                boolean exists = super.exists(path, options);
                try {
                    Files.writeString(path, "created concurrently");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return exists;
            }
        };

        assertThrows(
                java.nio.file.FileAlreadyExistsException.class,
                () -> AtomicFileWrite.writeIf(file, bytes("editor content"), () -> true, files));

        assertEquals("created concurrently", Files.readString(file));
    }

    @Test
    void atomicMoveFailureUsesTheNonAtomicReplacementFallback() throws IOException {
        Path file = Files.writeString(dir.resolve("fallback.txt"), "original");
        AtomicInteger moves = new AtomicInteger();
        AtomicFileWrite.FileOperations files = new DelegatingFileOperations() {
            @Override
            public void move(Path source, Path target, CopyOption... options) throws IOException {
                moves.incrementAndGet();
                if (hasOption(options, StandardCopyOption.ATOMIC_MOVE)) {
                    throw new IOException("atomic move unsupported");
                }
                super.move(source, target, options);
            }
        };

        assertTrue(AtomicFileWrite.writeIf(file, bytes("replacement"), () -> true, files));

        assertEquals(2, moves.get());
        assertEquals("replacement", Files.readString(file));
        assertEquals(1, entryCount());
    }

    @Test
    void supersededWriteBetweenMoveAttemptsDoesNotUseTheFallback() throws IOException {
        Path file = Files.writeString(dir.resolve("superseded.txt"), "original");
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicInteger moves = new AtomicInteger();
        AtomicFileWrite.FileOperations files = new DelegatingFileOperations() {
            @Override
            public void move(Path source, Path target, CopyOption... options) throws IOException {
                moves.incrementAndGet();
                current.set(false);
                throw new IOException("atomic move failed");
            }
        };

        assertFalse(AtomicFileWrite.writeIf(file, bytes("obsolete"), current::get, files));

        assertEquals(1, moves.get(), "the obsolete write must not attempt the non-atomic move");
        assertEquals("original", Files.readString(file));
        assertEquals(1, entryCount());
    }

    @Test
    void cleanupCannotTurnAnAlreadyCommittedWriteIntoAFailure() throws IOException {
        Path file = Files.writeString(dir.resolve("committed.txt"), "original");
        AtomicFileWrite.FileOperations files = new DelegatingFileOperations() {
            @Override
            public boolean deleteIfExists(Path path) throws IOException {
                throw new IOException("cleanup should not run after the staging file was moved");
            }
        };

        assertTrue(AtomicFileWrite.writeIf(file, bytes("committed"), () -> true, files));

        assertEquals("committed", Files.readString(file));
    }

    @Test
    void strictReplacementMoveFailureLeavesTheExpectedSourceIntact() throws IOException {
        Path file = Files.writeString(dir.resolve("strict.txt"), "expected");
        AtomicFileWrite.FileOperations files = new DelegatingFileOperations() {
            @Override
            public void move(Path source, Path target, CopyOption... options) throws IOException {
                throw new IOException("move failed");
            }
        };

        assertThrows(
                IOException.class,
                () -> AtomicFileWrite.replaceIfUnchanged(
                        file, bytes("expected"), bytes("replacement"), () -> true, files));

        assertEquals("expected", Files.readString(file));
        assertEquals(1, entryCount());
    }

    @Test
    void obsoleteStagedWriteDoesNotReplaceTheTarget() throws IOException {
        Path file = dir.resolve("keep.txt");
        Files.writeString(file, "current\n");

        assertFalse(AtomicFileWrite.writeIf(file, bytes("obsolete\n"), () -> false));

        assertEquals("current\n", Files.readString(file));
        try (var entries = Files.list(dir)) {
            assertEquals(1, entries.count(), "the refused staged file was cleaned up");
        }
    }

    @Test
    void strictReplacementRefusesWhenNoStagingDirectoryExists() throws IOException {
        Path original = Files.writeString(dir.resolve("original.txt"), "precious");
        Path impossible = original.resolve("child.txt");

        org.junit.jupiter.api.Assertions.assertThrows(
                IOException.class,
                () -> AtomicFileWrite.replaceIfUnchanged(impossible, bytes("precious"), bytes("changed"), () -> true));

        assertEquals("precious", Files.readString(original));
    }

    private long entryCount() throws IOException {
        try (var entries = Files.list(dir)) {
            return entries.count();
        }
    }

    private static boolean hasOption(CopyOption[] options, CopyOption expected) {
        return Arrays.asList(options).contains(expected);
    }
}
