package com.editora.io;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Writes a <b>document</b> (the user's file) as safely as the platform allows: to a temp file in the same
 * directory, then moved into place — so the real file is only ever replaced by a complete one.
 *
 * <p>{@code Files.write(file, bytes)} truncates the target and then streams into it: a crash, a full disk, or
 * any I/O error partway through leaves the user's file truncated or half-written, with no copy to recover
 * from. The editor still holds the text in memory, but a power cut doesn't care.
 *
 * <p>Two things a naive temp-and-move gets wrong, both of which would be worse than the bug it fixes:
 *
 * <ul>
 *   <li><b>Symlinks.</b> Moving over a symlink <em>replaces the link with a regular file</em>. Editing a
 *       dotfile that's symlinked into a dotfiles repo (a very normal setup) would quietly detach it. So the
 *       link is resolved first and the target is written.
 *   <li><b>Permissions.</b> A fresh temp file gets default permissions, so a shell script would silently lose
 *       its executable bit and any group/other access. The existing file's POSIX permissions are copied onto
 *       the temp file before the move.
 * </ul>
 *
 * <p>If an existing target cannot be staged safely, the save fails without touching it. A plain in-place
 * write truncates first and could destroy the only recoverable copy if the write then fails. Direct writing
 * is retained only for a brand-new target, where there is no prior file to preserve.
 */
public final class AtomicFileWrite {

    /**
     * Narrow filesystem boundary used to verify failures at each stage of document replacement. The
     * production implementation delegates directly to {@link Files}; callers normally use {@link #writeIf}.
     */
    public interface FileOperations {

        boolean isDirectory(Path path);

        boolean exists(Path path, LinkOption... options);

        void createDirectories(Path path) throws IOException;

        Path createTempFile(Path directory, String prefix, String suffix, FileAttribute<?>... attributes)
                throws IOException;

        Path createTempFile(String prefix, String suffix, FileAttribute<?>... attributes) throws IOException;

        void write(Path path, byte[] bytes) throws IOException;

        void writeNew(Path path, byte[] bytes) throws IOException;

        void move(Path source, Path target, CopyOption... options) throws IOException;

        byte[] readAllBytes(Path path) throws IOException;

        boolean deleteIfExists(Path path) throws IOException;
    }

    private static final FileOperations FILES = new FileOperations() {
        @Override
        public boolean isDirectory(Path path) {
            return Files.isDirectory(path);
        }

        @Override
        public boolean exists(Path path, LinkOption... options) {
            return Files.exists(path, options);
        }

        @Override
        public void createDirectories(Path path) throws IOException {
            Files.createDirectories(path);
        }

        @Override
        public Path createTempFile(Path directory, String prefix, String suffix, FileAttribute<?>... attributes)
                throws IOException {
            return Files.createTempFile(directory, prefix, suffix, attributes);
        }

        @Override
        public Path createTempFile(String prefix, String suffix, FileAttribute<?>... attributes) throws IOException {
            return Files.createTempFile(prefix, suffix, attributes);
        }

        @Override
        public void write(Path path, byte[] bytes) throws IOException {
            Files.write(path, bytes);
        }

        @Override
        public void writeNew(Path path, byte[] bytes) throws IOException {
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            Files.move(source, target, options);
        }

        @Override
        public byte[] readAllBytes(Path path) throws IOException {
            return Files.readAllBytes(path);
        }

        @Override
        public boolean deleteIfExists(Path path) throws IOException {
            return Files.deleteIfExists(path);
        }
    };

    private AtomicFileWrite() {}

    /** The production {@link Files}-backed operations implementation. */
    public static FileOperations systemFileOperations() {
        return FILES;
    }

    /** Writes {@code bytes} to {@code file}, replacing it atomically where the platform supports it. */
    public static void write(Path file, byte[] bytes) throws IOException {
        writeIf(file, bytes, () -> true);
    }

    /**
     * Stages {@code bytes}, then replaces {@code file} only when {@code commit} still permits the write.
     *
     * @return true when the target was written; false when the staged write became obsolete
     */
    public static boolean writeIf(Path file, byte[] bytes, BooleanSupplier commit) throws IOException {
        return writeIf(file, bytes, commit, FILES);
    }

    /**
     * Creates a document only when the path is still absent. This is the safe counterpart to a restore or
     * generated-file operation that must never overwrite a file which appeared after the operation began.
     * There is no prior target to preserve, so {@link StandardOpenOption#CREATE_NEW} is the commit boundary.
     */
    public static boolean createNew(Path file, byte[] bytes, BooleanSupplier commit) throws IOException {
        if (!commit.getAsBoolean()) {
            return false;
        }
        FILES.writeNew(file, bytes);
        return true;
    }

    /**
     * As {@link #writeIf(Path, byte[], BooleanSupplier)}, using the supplied filesystem boundary.
     * Intended for deterministic fault injection and alternate filesystem adapters.
     */
    public static boolean writeIf(Path file, byte[] bytes, BooleanSupplier commit, FileOperations files)
            throws IOException {
        Path target = resolveLink(file);
        Path dir = target.getParent();
        if (dir == null) {
            dir = target.toAbsolutePath().getParent();
        }
        if (dir == null || !files.isDirectory(dir)) {
            return writeUnstagedNewTarget(target, bytes, commit, files, null);
        }
        Path tmp;
        try {
            tmp = files.createTempFile(dir, "." + target.getFileName() + ".", ".editora-tmp");
        } catch (IOException cannotStage) {
            return writeUnstagedNewTarget(target, bytes, commit, files, cannotStage);
        }
        boolean replaced = false;
        try {
            files.write(tmp, bytes);
            copyPermissions(target, tmp);
            if (!commit.getAsBoolean()) {
                return false;
            }
            try {
                files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                if (!commit.getAsBoolean()) {
                    return false;
                }
                files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            replaced = true;
            return true;
        } finally {
            if (!replaced) {
                files.deleteIfExists(tmp);
            }
        }
    }

    private static boolean writeUnstagedNewTarget(
            Path target, byte[] bytes, BooleanSupplier commit, FileOperations files, IOException stagingFailure)
            throws IOException {
        if (!commit.getAsBoolean()) {
            return false;
        }
        if (files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cannot stage a safe replacement for existing file " + target, stagingFailure);
        }
        files.writeNew(target, bytes);
        return true;
    }

    /**
     * Strict staged replacement for destructive bulk edits. Unlike {@link #writeIf}, this method refuses
     * to fall back to an in-place truncating write, and it verifies the expected source bytes immediately
     * before each move attempt.
     */
    public static boolean replaceIfUnchanged(
            Path file, byte[] expectedBytes, byte[] replacementBytes, BooleanSupplier commit) throws IOException {
        return replaceIfUnchanged(file, expectedBytes, replacementBytes, commit, FILES);
    }

    /** Strict replacement with an injectable filesystem boundary. */
    public static boolean replaceIfUnchanged(
            Path file, byte[] expectedBytes, byte[] replacementBytes, BooleanSupplier commit, FileOperations files)
            throws IOException {
        Path target = resolveLink(file);
        Path dir = target.getParent();
        if (dir == null) {
            dir = target.toAbsolutePath().getParent();
        }
        if (dir == null || !files.isDirectory(dir)) {
            throw new IOException("Cannot stage a safe replacement for " + target);
        }
        Path tmp = files.createTempFile(dir, "." + target.getFileName() + ".", ".editora-tmp");
        boolean replaced = false;
        try {
            files.write(tmp, replacementBytes);
            copyPermissions(target, tmp);
            if (!commit.getAsBoolean() || !Arrays.equals(expectedBytes, files.readAllBytes(target))) {
                return false;
            }
            try {
                files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                if (!commit.getAsBoolean() || !Arrays.equals(expectedBytes, files.readAllBytes(target))) {
                    return false;
                }
                files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            replaced = true;
            return true;
        } finally {
            if (!replaced) {
                files.deleteIfExists(tmp);
            }
        }
    }

    /**
     * The real file behind {@code file} when it is a symlink — writing through the link keeps it a link.
     * A broken link, or any resolution failure, falls back to the path as given.
     */
    static Path resolveLink(Path file) {
        try {
            return Files.isSymbolicLink(file) ? file.toRealPath() : file;
        } catch (IOException brokenLink) {
            return file;
        }
    }

    /** Copies {@code from}'s POSIX permissions onto {@code to}, so the saved file keeps its mode (e.g. +x). */
    private static void copyPermissions(Path from, Path to) {
        try {
            if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
                return; // a brand-new file: the temp file's defaults are correct
            }
            PosixFileAttributeView view = Files.getFileAttributeView(from, PosixFileAttributeView.class);
            if (view == null) {
                return; // not a POSIX filesystem (Windows) — nothing to carry over
            }
            Set<PosixFilePermission> perms = view.readAttributes().permissions();
            Files.setPosixFilePermissions(to, perms);
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Best effort: a save that keeps the wrong mode still beats a save that doesn't happen.
        }
    }
}
