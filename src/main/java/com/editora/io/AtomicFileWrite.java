package com.editora.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;
import org.apache.sshd.sftp.client.fs.SftpFileSystem;

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
 *       the temp file before the move. A <em>new</em> file has nothing to copy, and must not inherit the
 *       temp file's owner-only mode either: it is staged with the mode any newly created file gets
 *       (read/write for everyone, narrowed by the process umask).
 * </ul>
 *
 * <p>The staged bytes are forced to the device before the move. Without that, a crash shortly after the save
 * can leave the new name pointing at a file whose data never reached the disk — an empty document where the
 * previous version used to be.
 *
 * <p>If an existing target cannot be staged safely, {@link #writeIf} fails without touching it. A plain
 * in-place write truncates first and could destroy the only recoverable copy if the write then fails. Direct
 * writing is retained only for a brand-new target, where there is no prior file to preserve.
 *
 * <p>{@link #writeDocument} is the editor's own save. It adds one more step for the files a replacement
 * cannot reach or would damage — a writable file in a directory that does not allow new entries, a file
 * that cannot be renamed over, one with several hard links or another owner: the previous bytes are first
 * copied to a backup file, and only then is the target overwritten in place (see {@link Outcome#IN_PLACE}).
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

        /** Makes {@code path}'s written bytes durable before it is moved into place. No-op by default. */
        default void force(Path path) throws IOException {}

        void writeNew(Path path, byte[] bytes) throws IOException;

        /** Overwrites the <em>existing</em> {@code path} in place and forces the bytes to the device. */
        default void overwrite(Path path, byte[] bytes) throws IOException {
            try (FileChannel channel =
                    FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        }

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
        public void force(Path path) throws IOException {
            if (isRemote(path)) {
                return; // SFTP has no portable fsync; the server-side rename is the commit point there
            }
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }

        @Override
        public void writeNew(Path path, byte[] bytes) throws IOException {
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            if (replaceRemote(source, target)) {
                return;
            }
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

    /**
     * Apache MINA's {@code SftpFileSystemProvider.move(..., REPLACE_EXISTING)} deletes the destination before
     * issuing its rename request. A lost connection or rejected rename in that gap therefore destroys the
     * previous remote file. Use a server-side replacement operation instead, where the server supports one,
     * and fail without touching the destination otherwise.
     */
    private static boolean replaceRemote(Path source, Path target) throws IOException {
        if (!(source.getFileSystem() instanceof SftpFileSystem fs)) {
            return false;
        }
        if (target.getFileSystem() != fs) {
            throw new ProviderMismatchException("Mismatched SFTP filesystems for " + source + " and " + target);
        }
        try (SftpClient client = fs.getClient()) {
            OpenSSHPosixRenameExtension posix = client.getExtension(OpenSSHPosixRenameExtension.class);
            if (posix != null && posix.isSupported()) {
                posix.posixRename(source.toString(), target.toString());
                return true;
            }
            if (client.getVersion() >= 5) {
                client.rename(
                        source.toString(),
                        target.toString(),
                        SftpClient.CopyMode.Atomic,
                        SftpClient.CopyMode.Overwrite);
                return true;
            }
        }
        throw new IOException("The SFTP server does not support safe remote file replacement");
    }

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
        return write(file, bytes, commit, files, false, null) != Outcome.SKIPPED;
    }

    /** How {@link #writeDocument} put the bytes on disk. */
    public enum Outcome {
        /** {@code commit} refused: the write became obsolete and the target is untouched. */
        SKIPPED,
        /** The target was replaced by a complete staged file (or newly created). */
        REPLACED,
        /**
         * The existing file was overwritten in place, because it could not be replaced or a replacement
         * would have changed what it is. Its previous bytes were held in a backup file for the duration.
         */
        IN_PLACE
    }

    /**
     * Saves an editor document: a staged replacement wherever that is possible and harmless, and otherwise
     * a backed-up overwrite of the existing file.
     *
     * <p>A replacement needs permission to create an entry beside the target and to rename over it. A file
     * the user may write can lack both — it sits in a directory they cannot write to, in a sticky directory
     * under another owner, or it is a bind-mounted single file — and {@link #writeIf} then refuses for good,
     * although {@code echo > file} works. A replacement is also a <em>new</em> file: other hard links keep
     * the old content, and a file owned by someone else changes owner. In those cases the target is
     * overwritten in place instead, after its current bytes have been copied to {@code backupDir} (the
     * system temp directory when null or unusable). If the overwrite fails, the previous bytes are written
     * back; if that fails too, the error names the backup file, which is then kept.
     *
     * @param backupDir where the previous bytes are held during an in-place write; may be null
     */
    public static Outcome writeDocument(Path file, byte[] bytes, BooleanSupplier commit, Path backupDir)
            throws IOException {
        return writeDocument(file, bytes, commit, backupDir, FILES);
    }

    /** As {@link #writeDocument(Path, byte[], BooleanSupplier, Path)}, with an injectable filesystem boundary. */
    public static Outcome writeDocument(
            Path file, byte[] bytes, BooleanSupplier commit, Path backupDir, FileOperations files) throws IOException {
        return write(file, bytes, commit, files, true, backupDir);
    }

    private static Outcome write(
            Path file,
            byte[] bytes,
            BooleanSupplier commit,
            FileOperations files,
            boolean inPlaceAllowed,
            Path backupDir)
            throws IOException {
        Path target = resolveLink(file);
        Path dir = target.getParent();
        if (dir == null) {
            dir = target.toAbsolutePath().getParent();
        }
        if (dir == null || !files.isDirectory(dir)) {
            return writeUnstagedNewTarget(target, bytes, commit, files, null);
        }
        if (inPlaceAllowed && hasOtherLinks(target) && canOverwrite(target)) {
            return writeInPlace(target, bytes, commit, files, backupDir, null);
        }
        Path tmp;
        try {
            tmp = createStagingFile(files, dir, target);
        } catch (IOException cannotStage) {
            if (inPlaceAllowed && canOverwrite(target)) {
                return writeInPlace(target, bytes, commit, files, backupDir, cannotStage);
            }
            return writeUnstagedNewTarget(target, bytes, commit, files, cannotStage);
        }
        boolean replaced = false;
        IOException cannotReplace = null;
        try {
            boolean keepsIdentity = !inPlaceAllowed || !canOverwrite(target) || keepsOwnership(target, tmp);
            if (keepsIdentity) {
                files.write(tmp, bytes);
                files.force(tmp);
                copyPermissions(target, tmp);
                if (!commit.getAsBoolean()) {
                    return Outcome.SKIPPED;
                }
                try {
                    files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnsupported) {
                    if (isRemote(tmp)) {
                        throw atomicUnsupported;
                    }
                    if (!commit.getAsBoolean()) {
                        return Outcome.SKIPPED;
                    }
                    try {
                        files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException neitherMove) {
                        if (!inPlaceAllowed || !canOverwrite(target)) {
                            throw neitherMove;
                        }
                        cannotReplace = neitherMove;
                    }
                }
                if (cannotReplace == null) {
                    replaced = true;
                    return Outcome.REPLACED;
                }
            }
        } finally {
            if (!replaced) {
                files.deleteIfExists(tmp);
            }
        }
        return writeInPlace(target, bytes, commit, files, backupDir, cannotReplace);
    }

    private static Outcome writeUnstagedNewTarget(
            Path target, byte[] bytes, BooleanSupplier commit, FileOperations files, IOException stagingFailure)
            throws IOException {
        if (!commit.getAsBoolean()) {
            return Outcome.SKIPPED;
        }
        if (files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cannot stage a safe replacement for existing file " + target, stagingFailure);
        }
        files.writeNew(target, bytes);
        return Outcome.REPLACED;
    }

    /** An existing local regular file this process may write: the only thing ever overwritten in place. */
    private static boolean canOverwrite(Path target) {
        return !isRemote(target) && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.isWritable(target);
    }

    /** True when {@code target} has another name: a replacement would leave that name on the old content. */
    private static boolean hasOtherLinks(Path target) {
        try {
            return !isRemote(target)
                    && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && Files.getAttribute(target, "unix:nlink") instanceof Integer links
                    && links > 1;
        } catch (IOException | RuntimeException noLinkCount) {
            return false; // not a POSIX filesystem: nothing to preserve that a replacement would lose
        }
    }

    /**
     * Whether a replacement staged as {@code tmp} would still belong to {@code target}'s owner and group.
     * The group is carried over where the process is allowed to (it is a member); an owner cannot be given
     * away, so a file that belongs to someone else is written in place rather than re-owned by a save.
     */
    private static boolean keepsOwnership(Path target, Path tmp) {
        try {
            if (!Objects.equals(Files.getAttribute(target, "unix:uid"), Files.getAttribute(tmp, "unix:uid"))) {
                return false;
            }
            Object group = Files.getAttribute(target, "unix:gid");
            if (!Objects.equals(group, Files.getAttribute(tmp, "unix:gid"))) {
                Files.setAttribute(tmp, "unix:gid", group);
            }
            return true;
        } catch (IOException | RuntimeException notPosixOrNotPermitted) {
            // No POSIX ownership here (true: nothing to lose), or the group could not be carried over.
            return !(notPosixOrNotPermitted instanceof IOException);
        }
    }

    /**
     * Overwrites the existing {@code target}, holding its previous bytes in a backup file until the new ones
     * are on the device. {@code cause} is why no replacement was possible, when that is what led here.
     */
    private static Outcome writeInPlace(
            Path target, byte[] bytes, BooleanSupplier commit, FileOperations files, Path backupDir, IOException cause)
            throws IOException {
        byte[] previous;
        Path backup;
        try {
            previous = files.readAllBytes(target);
            backup = createBackupFile(files, backupDir, target);
        } catch (IOException noBackup) {
            IOException refused = new IOException("Cannot stage a safe replacement for existing file " + target, cause);
            refused.addSuppressed(noBackup);
            throw refused;
        }
        boolean keepBackup = false;
        try {
            files.write(backup, previous);
            files.force(backup);
            if (!commit.getAsBoolean()) {
                return Outcome.SKIPPED;
            }
            try {
                files.overwrite(target, bytes);
            } catch (IOException torn) {
                try {
                    files.overwrite(target, previous);
                } catch (IOException notRestored) {
                    keepBackup = true;
                    torn.addSuppressed(notRestored);
                    throw new IOException(
                            "Could not finish writing " + target + " in place; it may be incomplete. Its previous"
                                    + " contents are in " + backup,
                            torn);
                }
                throw new IOException(
                        "Could not write " + target + " in place (" + torn.getMessage()
                                + "); its previous contents were restored",
                        torn);
            }
            return Outcome.IN_PLACE;
        } finally {
            if (!keepBackup) {
                try {
                    files.deleteIfExists(backup);
                } catch (IOException leftBehind) {
                    // The save itself is decided; a stray backup copy must not turn it into a failure.
                }
            }
        }
    }

    private static Path createBackupFile(FileOperations files, Path backupDir, Path target) throws IOException {
        String prefix = boundedName(target) + ".";
        if (backupDir != null) {
            try {
                files.createDirectories(backupDir);
                return files.createTempFile(backupDir, prefix, ".editora-backup");
            } catch (IOException | RuntimeException unusable) {
                // Fall through to the system temp directory: a backup somewhere beats none.
            }
        }
        return files.createTempFile(prefix, ".editora-backup");
    }

    /** Longest slice of a file name kept in a staging or backup name, in UTF-8 bytes. */
    private static final int MAX_NAME_BYTES = 96;

    /**
     * {@code target}'s file name, cut to {@link #MAX_NAME_BYTES}. A staging name used to be the whole name
     * plus ~34 characters, which overflows the 255-byte limit for any name longer than ~220 bytes — a file
     * the filesystem allows but which could then never be saved.
     */
    static String boundedName(Path target) {
        String name = String.valueOf(target.getFileName());
        int bytes = 0;
        int end = 0;
        while (end < name.length()) {
            int codePoint = name.codePointAt(end);
            int width = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + width > MAX_NAME_BYTES) {
                break;
            }
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return name.substring(0, end);
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
        Path tmp = files.createTempFile(dir, "." + boundedName(target) + ".", ".editora-tmp");
        boolean replaced = false;
        try {
            files.write(tmp, replacementBytes);
            files.force(tmp);
            copyPermissions(target, tmp);
            if (!commit.getAsBoolean() || !Arrays.equals(expectedBytes, files.readAllBytes(target))) {
                return false;
            }
            try {
                files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                if (isRemote(tmp)) {
                    throw atomicUnsupported;
                }
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

    /** What {@code open(2)} is asked for when a program creates a file; the process umask narrows it. */
    private static final Set<PosixFilePermission> NEW_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-rw-rw-");

    /**
     * Creates the temp file a write is staged in. {@code createTempFile} makes it owner-only (0600) — right
     * for a secret, and fine for a replacement, whose mode is then copied from the file it replaces. A target
     * that does not exist yet has no mode to copy, so every newly saved file used to keep the 0600 and
     * ignore the umask: unreadable to the group, to a web server, to a container user. Such a target is
     * staged with the ordinary new-file mode instead, which the kernel narrows by the umask at creation.
     */
    private static Path createStagingFile(FileOperations files, Path dir, Path target) throws IOException {
        String prefix = "." + boundedName(target) + ".";
        boolean newTarget = !Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (newTarget
                && !isRemote(dir)
                && dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try {
                return files.createTempFile(
                        dir, prefix, ".editora-tmp", PosixFilePermissions.asFileAttribute(NEW_FILE_PERMISSIONS));
            } catch (UnsupportedOperationException noInitialMode) {
                // This provider cannot set a mode at creation; fall back to its default below.
            }
        }
        return files.createTempFile(dir, prefix, ".editora-tmp");
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

    private static boolean isRemote(Path path) {
        return path.getFileSystem() instanceof SftpFileSystem;
    }

    /** Copies {@code from}'s POSIX permissions onto {@code to}, so the saved file keeps its mode (e.g. +x). */
    private static void copyPermissions(Path from, Path to) {
        try {
            if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
                return; // a brand-new file: createStagingFile already gave it the ordinary new-file mode
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
