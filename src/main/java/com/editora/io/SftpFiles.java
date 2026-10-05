package com.editora.io;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.fs.SftpFileSystem;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

/**
 * What {@link AtomicFileWrite} has to ask an SFTP server itself, because the NIO view of a remote file does
 * not answer it: {@code toRealPath()} does not follow links there, {@code Files.isWritable} says yes to a
 * mode 0444 file, and the protocol's attributes carry neither a link count nor "is this mine".
 */
final class SftpFiles {

    /** As many links as a kernel follows before it gives up ({@code ELOOP}). */
    private static final int MAX_LINK_DEPTH = 40;

    /** Directory entries read while looking for one file's link count; a larger folder is not searched on. */
    private static final int MAX_LISTED_ENTRIES = 1000;

    /** Staging files a dropped connection left behind, by connection, removed on that server's next save. */
    private static final Map<String, Set<String>> ORPHANS = new ConcurrentHashMap<>();

    private SftpFiles() {}

    static boolean isRemote(Path path) {
        return path.getFileSystem() instanceof SftpFileSystem;
    }

    /**
     * The file a chain of remote symlinks ends at. Each link is read and resolved against the directory that
     * holds it; the result is deliberately not normalized, so a {@code ..} behind a linked directory is
     * still resolved by the server, physically. A link whose target is missing resolves to that target.
     */
    static Path resolveLink(Path file) throws IOException {
        Path current = file;
        for (int depth = 0; depth < MAX_LINK_DEPTH; depth++) {
            if (!Files.isSymbolicLink(current)) {
                return current;
            }
            Path link = Files.readSymbolicLink(current);
            Path parent = current.getParent();
            current = parent == null ? link : parent.resolve(link);
        }
        throw new IOException("Too many levels of symbolic links: " + file);
    }

    /**
     * An existing regular file with at least one write bit. Whether <em>this login</em> may write it is not
     * knowable from here; a refused open changes nothing, so trying is safe.
     */
    static boolean looksOverwritable(Path target) {
        try {
            PosixFileAttributes attributes =
                    Files.readAttributes(target, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && hasWriteBit(attributes.permissions());
        } catch (IOException | RuntimeException missingOrUnreadable) {
            return false;
        }
    }

    static boolean hasWriteBit(Set<PosixFilePermission> permissions) {
        return permissions.contains(PosixFilePermission.OWNER_WRITE)
                || permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE);
    }

    /**
     * True when {@code target} has another name on the server. SFTP attributes (up to protocol version 3,
     * which is all OpenSSH speaks) have no link count; the only place a server reports one is the
     * {@code ls -l} line of a directory listing, so the file's folder is listed until its entry turns up.
     * Unknown — a huge folder, a server without long names — counts as "no other links".
     */
    static boolean hasOtherLinks(Path target) {
        Path dir = target.getParent();
        Path name = target.getFileName();
        if (dir == null || name == null || !(target.getFileSystem() instanceof SftpFileSystem fs)) {
            return false;
        }
        try (SftpClient client = fs.getClient();
                SftpClient.CloseableHandle handle = client.openDir(dir.toString())) {
            int listed = 0;
            for (List<SftpClient.DirEntry> batch = client.readDir(handle);
                    batch != null && listed < MAX_LISTED_ENTRIES;
                    batch = client.readDir(handle)) {
                for (SftpClient.DirEntry entry : batch) {
                    listed++;
                    if (name.toString().equals(entry.getFilename())) {
                        return linkCount(entry.getLongFilename()) > 1;
                    }
                }
            }
        } catch (IOException | RuntimeException cannotList) {
            // Nothing learned: treated as a file with one name, which is what a save assumed before.
        }
        return false;
    }

    /** The link count in an {@code ls -l} style long name ({@code -rw-r--r--   2 owner group …}), else 1. */
    static int linkCount(String longName) {
        if (longName == null) {
            return 1;
        }
        String[] fields = longName.strip().split("\\s+", 3);
        if (fields.length < 3 || fields[0].length() < 10 || "-dlbcps".indexOf(fields[0].charAt(0)) < 0) {
            return 1;
        }
        try {
            return Integer.parseInt(fields[1]);
        } catch (NumberFormatException notACount) {
            return 1;
        }
    }

    /**
     * Whether the freshly staged {@code tmp} belongs to the same user as {@code target} — that is, whether
     * moving it into place would leave the file's owner alone. Unknown counts as "same".
     */
    static boolean sameOwner(Path target, Path tmp) {
        if (!(target.getFileSystem() instanceof SftpFileSystem fs)) {
            return true;
        }
        try (SftpClient client = fs.getClient()) {
            SftpClient.Attributes existing = client.stat(target.toString());
            SftpClient.Attributes staged = client.stat(tmp.toString());
            return existing.getUserId() == staged.getUserId() && Objects.equals(existing.getOwner(), staged.getOwner());
        } catch (IOException | RuntimeException unknown) {
            return true;
        }
    }

    /** True when the server refused for permissions — as opposed to a full disk, a quota or a dead link. */
    static boolean permissionDenied(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AccessDeniedException
                    || (cause instanceof SftpException sftp
                            && sftp.getStatus() == SftpConstants.SSH_FX_PERMISSION_DENIED)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * Removes a staging file that is no longer wanted. A connection that died mid-save cannot be asked to;
     * the file is then remembered and removed by the next save that reaches the same server, and the
     * failure never replaces the error that is already on its way to the user.
     */
    static void discardStaged(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException | RuntimeException unreachable) {
            String server = serverKey(tmp);
            if (server != null) {
                ORPHANS.computeIfAbsent(server, ignored -> ConcurrentHashMap.newKeySet())
                        .add(tmp.toString());
            }
        }
    }

    /** Deletes the staging files earlier saves had to leave on {@code anyPath}'s server. Best effort. */
    static void sweepOrphans(Path anyPath) {
        String server = ORPHANS.isEmpty() ? null : serverKey(anyPath);
        Set<String> orphans = server == null ? null : ORPHANS.get(server);
        if (orphans == null) {
            return;
        }
        for (String orphan : Set.copyOf(orphans)) {
            try {
                Files.deleteIfExists(anyPath.getFileSystem().getPath(orphan));
                orphans.remove(orphan);
            } catch (IOException | RuntimeException stillUnreachable) {
                // Kept for the next save.
            }
        }
    }

    /** {@code user@host:port} of the session behind {@code path}; it outlives the filesystem being closed. */
    private static String serverKey(Path path) {
        try {
            if (path.getFileSystem() instanceof SftpFileSystem fs && fs.getSession() != null) {
                return fs.getSession().getUsername() + "@" + fs.getSession().getConnectAddress();
            }
        } catch (RuntimeException noSession) {
            // fall through
        }
        return null;
    }
}
