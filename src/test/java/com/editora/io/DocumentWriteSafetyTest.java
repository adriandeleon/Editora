package com.editora.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a document save must not destroy besides the text: the link or special file at the path, the
 * metadata of the file it replaces, and the previous bytes when an in-place write is cut short.
 */
class DocumentWriteSafetyTest {

    @TempDir
    Path dir;

    @TempDir
    Path backups;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Path symlink(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException noSymlinks) {
            return Assumptions.abort("symlinks not supported here");
        }
    }

    // --- S6: links and special files ---------------------------------------------------------------

    @Test
    void savingThroughADanglingSymlinkCreatesItsTargetAndKeepsTheLink() throws IOException {
        Path target = dir.resolve("not-yet.conf");
        Path link = symlink(dir.resolve("conf"), target);

        AtomicFileWrite.writeDocument(link, bytes("new\n"), () -> true, backups);

        assertTrue(Files.isSymbolicLink(link), "the link must stay a link");
        assertEquals("new\n", Files.readString(target), "and the file it names is created");
    }

    @Test
    void aRelativeDanglingLinkIsResolvedAgainstItsOwnFolder() throws IOException {
        Path sub = Files.createDirectory(dir.resolve("sub"));
        Path link = symlink(dir.resolve("conf"), Path.of("sub", "real.conf"));

        AtomicFileWrite.write(link, bytes("x\n"));

        assertTrue(Files.isSymbolicLink(link));
        assertEquals("x\n", Files.readString(sub.resolve("real.conf")));
    }

    @Test
    void aChainOfLinksEndingNowhereIsFollowedToItsEnd() throws IOException {
        Path target = dir.resolve("end.txt");
        Path second = symlink(dir.resolve("second"), target);
        Path first = symlink(dir.resolve("first"), second);

        AtomicFileWrite.write(first, bytes("x\n"));

        assertTrue(Files.isSymbolicLink(first));
        assertTrue(Files.isSymbolicLink(second));
        assertEquals("x\n", Files.readString(target));
    }

    @Test
    void aLinkLoopIsRefusedRatherThanReplaced() {
        Path a = dir.resolve("a");
        Path b = dir.resolve("b");
        symlink(a, b);
        symlink(b, a);

        assertThrows(IOException.class, () -> AtomicFileWrite.write(a, bytes("x\n")));

        assertTrue(Files.isSymbolicLink(a), "the link is left as it was");
        assertTrue(Files.isSymbolicLink(b));
    }

    @Test
    void aLinkIntoAMissingFolderFailsAndStaysALink() {
        Path link = symlink(dir.resolve("conf"), dir.resolve("no-such-folder").resolve("conf"));

        assertThrows(IOException.class, () -> AtomicFileWrite.writeDocument(link, bytes("x\n"), () -> true, backups));

        assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    void aNamedPipeIsNotReplacedByARegularFile() throws Exception {
        Path fifo = dir.resolve("pipe");
        try {
            Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).start();
            Assumptions.assumeTrue(mkfifo.waitFor() == 0);
        } catch (IOException noMkfifo) {
            Assumptions.abort("mkfifo not available here");
        }

        IOException refused = assertThrows(
                IOException.class, () -> AtomicFileWrite.writeDocument(fifo, bytes("x\n"), () -> true, backups));

        assertTrue(refused.getMessage().contains("not a regular file"), refused.getMessage());
        assertFalse(Files.isRegularFile(fifo, LinkOption.NOFOLLOW_LINKS), "still the pipe");
        assertTrue(Files.exists(fifo, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void aDirectoryIsNotADocument() throws IOException {
        Path folder = Files.createDirectory(dir.resolve("folder"));
        assertThrows(IOException.class, () -> AtomicFileWrite.write(folder, bytes("x\n")));
        assertTrue(Files.isDirectory(folder));
    }

    // --- S3: metadata of the replaced file -----------------------------------------------------------

    private UserDefinedFileAttributeView xattrs(Path file) {
        UserDefinedFileAttributeView view = Files.getFileAttributeView(file, UserDefinedFileAttributeView.class);
        Assumptions.assumeTrue(view != null, "no user-defined attributes here");
        return view;
    }

    private void setXattr(Path file, String name, String value) {
        try {
            xattrs(file).write(name, ByteBuffer.wrap(bytes(value)));
        } catch (IOException | RuntimeException unsupported) {
            Assumptions.abort("extended attributes not supported on this filesystem");
        }
    }

    private String xattr(Path file, String name) throws IOException {
        UserDefinedFileAttributeView view = xattrs(file);
        ByteBuffer buffer = ByteBuffer.allocate(view.size(name));
        view.read(name, buffer);
        buffer.flip();
        return StandardCharsets.UTF_8.decode(buffer).toString();
    }

    @Test
    void extendedAttributesSurviveASave() throws IOException {
        Path file = dir.resolve("tagged.txt");
        Files.writeString(file, "old\n");
        setXattr(file, "comment", "keep me");
        setXattr(file, "empty", "");

        AtomicFileWrite.Outcome outcome = AtomicFileWrite.writeDocument(file, bytes("new\n"), () -> true, backups);

        assertEquals(AtomicFileWrite.Outcome.REPLACED, outcome, "still a staged replacement");
        assertEquals("new\n", Files.readString(file));
        assertEquals(
                List.of("comment", "empty"),
                xattrs(file).list().stream().sorted().toList());
        assertEquals("keep me", xattr(file, "comment"));
    }

    @Test
    void extendedAttributesSurviveAPlainAtomicWriteAndAStrictReplacement() throws IOException {
        Path file = dir.resolve("tagged.txt");
        Files.writeString(file, "old\n");
        setXattr(file, "comment", "keep me");

        AtomicFileWrite.write(file, bytes("mid\n"));
        assertEquals("keep me", xattr(file, "comment"));

        assertTrue(AtomicFileWrite.replaceIfUnchanged(file, bytes("mid\n"), bytes("new\n"), () -> true));
        assertEquals("keep me", xattr(file, "comment"));
    }

    @Test
    void setuidSetgidAndStickyBitsSurviveASave() throws IOException {
        Path file = dir.resolve("tool.sh");
        Files.writeString(file, "#!/bin/sh\n");
        int wanted = 06755;
        try {
            Files.setAttribute(file, "unix:mode", wanted);
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException notUnix) {
            Assumptions.abort("no unix:mode here");
        }
        int before = (Integer) Files.getAttribute(file, "unix:mode") & 07777;
        Assumptions.assumeTrue((before & 07000) != 0, "this filesystem keeps no special mode bits");

        AtomicFileWrite.writeDocument(file, bytes("#!/bin/sh\necho hi\n"), () -> true, backups);

        assertEquals(
                Integer.toOctalString(before),
                Integer.toOctalString((Integer) Files.getAttribute(file, "unix:mode") & 07777));
    }

    @Test
    void anInPlaceWriteAlsoKeepsTheSpecialModeBits() throws IOException {
        Path file = dir.resolve("tool.sh");
        Files.writeString(file, "#!/bin/sh\n");
        try {
            Files.createLink(dir.resolve("other-name.sh"), file);
            Files.setAttribute(file, "unix:mode", 02755);
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException unsupported) {
            Assumptions.abort("no hard links or unix:mode here");
        }
        int before = (Integer) Files.getAttribute(file, "unix:mode") & 07777;
        Assumptions.assumeTrue((before & 07000) != 0);

        AtomicFileWrite.Outcome outcome =
                AtomicFileWrite.writeDocument(file, bytes("#!/bin/sh\necho hi\n"), () -> true, backups);

        assertEquals(AtomicFileWrite.Outcome.IN_PLACE, outcome);
        assertEquals(
                Integer.toOctalString(before),
                Integer.toOctalString((Integer) Files.getAttribute(file, "unix:mode") & 07777));
    }

    // --- S2: an interrupted in-place write -------------------------------------------------------------

    private Path hardLinked(String content) throws IOException {
        Path file = dir.resolve("hard.txt");
        Files.writeString(file, content);
        try {
            Files.createLink(dir.resolve("other-name.txt"), file);
        } catch (UnsupportedOperationException | IOException noHardLinks) {
            Assumptions.abort("hard links not supported here");
        }
        return file;
    }

    @Test
    void anInterruptDuringAnInPlaceWriteDoesNotEmptyTheFile() throws IOException {
        Path file = hardLinked("previous contents\n");
        try {
            // The window closes while the save is running: the writer thread is interrupted after the
            // backup was made and before the file is overwritten.
            AtomicFileWrite.Outcome outcome = AtomicFileWrite.writeDocument(
                    file,
                    bytes("v2\n"),
                    () -> {
                        Thread.currentThread().interrupt();
                        return true;
                    },
                    backups);

            assertEquals(AtomicFileWrite.Outcome.IN_PLACE, outcome);
            assertEquals("v2\n", Files.readString(file));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is still delivered to the caller");
        } finally {
            Thread.interrupted();
        }
        try (var left = Files.list(backups)) {
            assertEquals(List.of(), left.toList(), "a finished write keeps no backup");
        }
    }

    @Test
    void theRestoreAfterATornWriteWorksOnAnInterruptedThread() throws IOException {
        Path file = hardLinked("previous contents\n");
        AtomicInteger overwrites = new AtomicInteger();
        AtomicFileWrite.FileOperations tornOnce = new DelegatingFileOperations() {
            @Override
            public void overwrite(Path path, byte[] content) throws IOException {
                if (overwrites.getAndIncrement() == 0) {
                    Files.write(path, new byte[0]); // truncated, then the channel was closed by an interrupt
                    Thread.currentThread().interrupt();
                    throw new java.nio.channels.ClosedByInterruptException();
                }
                AtomicFileWrite.systemFileOperations().overwrite(path, content);
            }
        };
        try {
            IOException failed = assertThrows(
                    IOException.class,
                    () -> AtomicFileWrite.writeDocument(file, bytes("v2\n"), () -> true, backups, tornOnce));

            assertTrue(failed.getMessage().contains("restored"), failed.getMessage());
            assertEquals("previous contents\n", Files.readString(file));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aBackupRecordsWhichFileItBelongsToWhileTheWriteRuns() throws IOException {
        Path file = hardLinked("previous contents\n");
        List<SaveBackups.Leftover> during = new java.util.ArrayList<>();
        byte[][] held = new byte[1][];

        AtomicFileWrite.writeDocument(
                file,
                bytes("v2\n"),
                () -> {
                    during.addAll(SaveBackups.scan(backups));
                    try {
                        held[0] = Files.readAllBytes(during.get(0).backup());
                    } catch (IOException unreadable) {
                        throw new java.io.UncheckedIOException(unreadable);
                    }
                    return true;
                },
                backups);

        assertEquals(1, during.size());
        assertEquals(file.toRealPath().toString(), during.get(0).target());
        assertArrayEquals(bytes("previous contents\n"), held[0]);
        assertEquals(List.of(), SaveBackups.scan(backups), "nothing is left once the write is done");
        try (var left = Files.list(backups)) {
            assertEquals(List.of(), left.toList());
        }
    }
}
