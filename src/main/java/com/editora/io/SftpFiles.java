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

import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.sftp.client.RawSftpClient;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.SftpVersionSelector;
import org.apache.sshd.sftp.client.fs.SftpFileSystem;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

/**
 * What {@link AtomicFileWrite} has to ask an SFTP server itself, because the NIO view of a remote file does
 * not answer it: {@code toRealPath()} does not follow links there, {@code Files.isWritable} says yes to a
 * mode 0444 file, and the attributes MINA's client hands out carry neither a link count nor "is this mine".
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
     * True when {@code target} has another name on the server. Where a link count comes from depends on the
     * protocol version the connection speaks:
     * <ul>
     *   <li><b>6</b> — a {@code STAT} asking for {@code SSH_FILEXFER_ATTR_LINK_COUNT}, one request. MINA's
     *       client reads that field and throws it away, so the reply is decoded here. A server may leave
     *       the field out (MINA's own server does); that is remembered per connection.
     *   <li><b>3</b> (all OpenSSH speaks) — no attribute; the count is in the {@code ls -l} line of a
     *       directory listing, so the file's folder is listed until its entry turns up.
     *   <li><b>4 and 5</b>, and 6 without the field — neither exists. If the server also speaks 3 (MINA's
     *       does), a second SFTP channel on the same SSH session is opened at 3 for the listing.
     * </ul>
     * Unknown — a huge folder, a server with none of the above — counts as "no other links".
     */
    static boolean hasOtherLinks(Path target) {
        Path dir = target.getParent();
        Path name = target.getFileName();
        if (dir == null || name == null || !(target.getFileSystem() instanceof SftpFileSystem fs)) {
            return false;
        }
        try {
            LinkSource source = LINK_SOURCES.get(fs);
            if (fs.getVersion() >= SftpConstants.SFTP_V6 && source != LinkSource.LISTING && source != LinkSource.NONE) {
                Integer links = statLinkCount(fs, target.toString());
                if (links != null) {
                    return links > 1;
                }
                LINK_SOURCES.put(fs, LinkSource.LISTING); // answered without the field: do not ask again
            }
            if (fs.getVersion() == SftpConstants.SFTP_V3) {
                try (SftpClient client = fs.getClient()) {
                    return listedLinkCount(client, dir.toString(), name.toString()) > 1;
                }
            }
            if (source == LinkSource.NONE) {
                return false;
            }
            SftpClient listing = listingClient(fs);
            if (listing == null) {
                LINK_SOURCES.put(fs, LinkSource.NONE);
                return false;
            }
            return listedLinkCount(listing, dir.toString(), name.toString()) > 1;
        } catch (IOException | RuntimeException unknown) {
            // Nothing learned: treated as a file with one name, which is what a save assumed before.
            return false;
        }
    }

    /** Where a connection's link counts were found not to come from, so later saves skip the dead ends. */
    private enum LinkSource {
        /** No link-count attribute: the count has to be read from a protocol-3 listing. */
        LISTING,
        /** Neither the attribute nor a protocol-3 listing. */
        NONE
    }

    private static final Map<SftpFileSystem, LinkSource> LINK_SOURCES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** One protocol-3 client per connection that needs it; its channel closes with the SSH session. */
    private static final Map<SftpFileSystem, SftpClient> LISTING_CLIENTS = new java.util.WeakHashMap<>();

    private static final java.time.Duration STAT_TIMEOUT = java.time.Duration.ofSeconds(30);

    /** The link count a protocol-6 server reports for {@code path}, or null when its reply carries none. */
    private static Integer statLinkCount(SftpFileSystem fs, String path) throws IOException {
        try (SftpClient client = fs.getClient()) {
            if (!(client instanceof RawSftpClient raw)) {
                return null;
            }
            Buffer request = new ByteArrayBuffer(path.length() + Long.SIZE, false);
            request.putString(path, client.getNameDecodingCharset());
            request.putInt(SftpConstants.SSH_FILEXFER_ATTR_LINK_COUNT);
            Buffer reply = raw.receive(raw.send(SftpConstants.SSH_FXP_STAT, request), STAT_TIMEOUT);
            if (reply == null) {
                return null;
            }
            reply.getInt(); // length
            int type = reply.getUByte();
            reply.getInt(); // request id
            return type == SftpConstants.SSH_FXP_ATTRS ? linkCountOfV6Attributes(reply) : null;
        }
    }

    /**
     * The {@code link-count} field of protocol-6 file attributes (draft-ietf-secsh-filexfer-13, section 7),
     * or null when the server did not send one. Every field before it is skipped by its flag.
     */
    static Integer linkCountOfV6Attributes(Buffer attributes) {
        int flags = attributes.getInt();
        attributes.getUByte(); // type
        if ((flags & SftpConstants.SSH_FILEXFER_ATTR_SIZE) != 0) {
            attributes.getLong();
        }
        if ((flags & SftpConstants.SSH_FILEXFER_ATTR_ALLOCATION_SIZE) != 0) {
            attributes.getLong();
        }
        if ((flags & SftpConstants.SSH_FILEXFER_ATTR_OWNERGROUP) != 0) {
            attributes.getBytes();
            attributes.getBytes();
        }
        if ((flags & SftpConstants.SSH_FILEXFER_ATTR_PERMISSIONS) != 0) {
            attributes.getInt();
        }
        for (int time : new int[] {
            SftpConstants.SSH_FILEXFER_ATTR_ACCESSTIME,
            SftpConstants.SSH_FILEXFER_ATTR_CREATETIME,
            SftpConstants.SSH_FILEXFER_ATTR_MODIFYTIME,
            SftpConstants.SSH_FILEXFER_ATTR_CTIME
        }) {
            if ((flags & time) != 0) {
                attributes.getLong();
                if ((flags & SftpConstants.SSH_FILEXFER_ATTR_SUBSECOND_TIMES) != 0) {
                    attributes.getInt();
                }
            }
        }
        if ((flags & SftpConstants.SSH_FILEXFER_ATTR_ACL) != 0) {
            attributes.getBytes();
        }
        if ((flags & SftpConstants.SSH_FILEXFER_ATTR_BITS) != 0) {
            attributes.getInt();
            attributes.getInt();
        }
        if ((flags & SftpConstants.SSH_FILEXFER_ATTR_TEXT_HINT) != 0) {
            attributes.getUByte();
        }
        if ((flags & SftpConstants.SSH_FILEXFER_ATTR_MIME_TYPE) != 0) {
            attributes.getBytes();
        }
        return (flags & SftpConstants.SSH_FILEXFER_ATTR_LINK_COUNT) != 0 ? attributes.getInt() : null;
    }

    /** A protocol-3 client on {@code fs}'s SSH session, or null when the server does not speak 3. */
    private static SftpClient listingClient(SftpFileSystem fs) {
        synchronized (LISTING_CLIENTS) {
            SftpClient client = LISTING_CLIENTS.get(fs);
            if (client != null && client.isOpen()) {
                return client;
            }
            try {
                client = SftpClientFactory.instance()
                        .createSftpClient(
                                fs.getClientSession(), SftpVersionSelector.fixedVersionSelector(SftpConstants.SFTP_V3));
            } catch (IOException | RuntimeException noProtocol3) {
                return null;
            }
            LISTING_CLIENTS.put(fs, client);
            return client;
        }
    }

    /** The link count on {@code name}'s line of a protocol-3 listing of {@code dir}; 1 when it is not found. */
    private static int listedLinkCount(SftpClient client, String dir, String name) throws IOException {
        try (SftpClient.CloseableHandle handle = client.openDir(dir)) {
            int listed = 0;
            for (List<SftpClient.DirEntry> batch = client.readDir(handle);
                    batch != null && listed < MAX_LISTED_ENTRIES;
                    batch = client.readDir(handle)) {
                for (SftpClient.DirEntry entry : batch) {
                    listed++;
                    if (name.equals(entry.getFilename())) {
                        return linkCount(entry.getLongFilename());
                    }
                }
            }
        }
        return 1;
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

    /** The list of {@link #ORPHANS}, kept beside the save backups so it outlives the process. */
    static final String LEDGER_NAME = "remote-staging-leftovers.txt";

    /** Ledger folders already read into {@link #ORPHANS}. */
    private static final Set<Path> LOADED_LEDGERS = ConcurrentHashMap.newKeySet();

    /**
     * Removes a staging file that is no longer wanted. A connection that died mid-save cannot be asked to;
     * the file is then remembered — in memory and, when {@code ledgerDir} is given, in a small file there —
     * and removed by the next save that reaches the same server, in this run or a later one. The failure
     * never replaces the error that is already on its way to the user.
     */
    static void discardStaged(Path tmp, Path ledgerDir) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException | RuntimeException unreachable) {
            String server = serverKey(tmp);
            if (server != null) {
                synchronized (ORPHANS) {
                    loadLedger(ledgerDir);
                    ORPHANS.computeIfAbsent(server, ignored -> ConcurrentHashMap.newKeySet())
                            .add(tmp.toString());
                    storeLedger(ledgerDir);
                }
            }
        }
    }

    /** Deletes the staging files earlier saves had to leave on {@code anyPath}'s server. Best effort. */
    static void sweepOrphans(Path anyPath, Path ledgerDir) {
        synchronized (ORPHANS) {
            loadLedger(ledgerDir);
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
            if (orphans.isEmpty()) {
                ORPHANS.remove(server);
            }
            storeLedger(ledgerDir);
        }
    }

    /** Reads {@code ledgerDir}'s list once per run. Only names this class could have staged are taken. */
    private static void loadLedger(Path ledgerDir) {
        if (ledgerDir == null || !LOADED_LEDGERS.add(ledgerDir)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(ledgerDir.resolve(LEDGER_NAME))) {
                int tab = line.indexOf('\t');
                if (tab > 0 && isStagingName(line.substring(tab + 1))) {
                    ORPHANS.computeIfAbsent(line.substring(0, tab), ignored -> ConcurrentHashMap.newKeySet())
                            .add(line.substring(tab + 1));
                }
            }
        } catch (IOException | RuntimeException noLedger) {
            // Nothing was left behind, or the list is unreadable: the leftovers are then only clutter.
        }
    }

    /** A hidden {@code .name.random.editora-tmp} file: the only thing a ledger line may ask to delete. */
    static boolean isStagingName(String remotePath) {
        String name = remotePath.substring(remotePath.lastIndexOf('/') + 1);
        return name.length() > ".editora-tmp".length() + 1 && name.startsWith(".") && name.endsWith(".editora-tmp");
    }

    private static void storeLedger(Path ledgerDir) {
        if (ledgerDir == null) {
            return;
        }
        Path ledger = ledgerDir.resolve(LEDGER_NAME);
        try {
            List<String> lines = new java.util.ArrayList<>();
            ORPHANS.forEach((server, paths) -> paths.forEach(path -> lines.add(server + "\t" + path)));
            if (lines.isEmpty()) {
                Files.deleteIfExists(ledger);
            } else if (!lines.equals(Files.exists(ledger) ? Files.readAllLines(ledger) : List.of())) {
                Files.createDirectories(ledgerDir);
                java.util.Collections.sort(lines);
                Files.write(ledger, lines);
            }
        } catch (IOException | RuntimeException notStored) {
            // The list stays in memory for this run.
        }
    }

    /** Forgets what is held in memory, as a restart does. For tests. */
    static void forgetOrphansInMemory() {
        synchronized (ORPHANS) {
            ORPHANS.clear();
            LOADED_LEDGERS.clear();
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
