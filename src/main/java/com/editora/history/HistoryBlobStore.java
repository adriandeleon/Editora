package com.editora.history;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Content-addressed, gzip'd storage for Local File History revision bodies. Each unique content is
 * written once to {@code <blobsDir>/<2-hex-prefix>/<sha256>.txt.gz} (sharded by the hash prefix so a
 * single directory never holds thousands of files) and read back on demand. Because the key is the
 * content hash, identical content across files/revisions is stored once for free.
 *
 * <p>Only {@code java.util.zip} + {@code java.security} (both in {@code java.base}) — no new dependency.
 * All methods are I/O; the pure {@link #sha256} helper is unit-tested alongside round-trips.
 */
public final class HistoryBlobStore {

    private static final String SUFFIX = ".txt.gz";
    private static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = PosixFilePermissions.fromString("rwx------");

    /** A staging file older than this was left by a write that died; a younger one may still be in flight. */
    static final long STALE_STAGING_MILLIS = 10 * 60_000L;

    /** Makes a written body durable before it is moved into place; a seam so a test can observe the order. */
    @FunctionalInterface
    interface Forcer {
        void force(FileChannel channel, Path staging) throws IOException;
    }

    private final Path blobsDir;
    private final Forcer forcer;

    public HistoryBlobStore(Path blobsDir) {
        this(blobsDir, (channel, staging) -> channel.force(true));
    }

    HistoryBlobStore(Path blobsDir, Forcer forcer) {
        this.blobsDir = blobsDir;
        this.forcer = forcer;
    }

    /** Lower-case hex sha256 of {@code content}'s UTF-8 bytes. Pure. */
    public static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // every JRE ships it
        }
    }

    /** Computes the sha, writes the blob if absent, and returns the sha. */
    public String put(String content) {
        String sha = sha256(content);
        put(content, sha);
        return sha;
    }

    /** Writes the gzip'd blob for {@code sha} if it does not already exist (idempotent). */
    public void put(String content, String sha) {
        Path file = pathFor(sha);
        if (Files.exists(file) && get(sha) != null) {
            harden(file, OWNER_FILE);
            return;
        }
        // Absent — or present but unreadable (truncated by a crash before the data reached the disk): the
        // correct content is in hand, so write it again. Returning on mere existence left every revision
        // with this sha "unavailable" for good.
        try {
            createPrivateDirectories(file.getParent());
            harden(blobsDir, OWNER_DIRECTORY);
            harden(file.getParent(), OWNER_DIRECTORY);
            byte[] gz = gzip(content);
            // Write to a temp file then move, so a crash mid-write can't leave a truncated blob — and sync it
            // first. The index that will reference this body is synced when it is written; without the same
            // here a power cut could leave a durable index row pointing at an empty or half-written file.
            Path tmp = createOwnerOnlyTemp(file);
            try {
                try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                    ByteBuffer bytes = ByteBuffer.wrap(gz);
                    while (bytes.hasRemaining()) {
                        channel.write(bytes);
                    }
                    forcer.force(channel, tmp);
                }
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnsupported) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write history blob " + file, e);
        }
    }

    /** Reads and gunzips the blob for {@code sha}, or {@code null} if it's missing/unreadable. */
    public String get(String sha) {
        Path file = pathFor(sha);
        if (sha == null || sha.isEmpty() || !Files.exists(file)) {
            return null;
        }
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return sha.equals(sha256(content)) ? content : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Deletes every stored blob whose sha is not in {@code live} (garbage collection). Best-effort. Also
     * clears what a killed write left behind — a staging file older than {@link #STALE_STAGING_MILLIS} — and
     * shard folders that hold nothing any more; neither was ever removed, and a stray file among the bodies
     * reads as "bodies are stored" to the index guard.
     */
    public void deleteUnreferenced(Set<String> live) {
        deleteUnreferenced(live, System.currentTimeMillis());
    }

    void deleteUnreferenced(Set<String> live, long now) {
        if (!Files.isDirectory(blobsDir)) {
            return;
        }
        harden(blobsDir, OWNER_DIRECTORY);
        try (Stream<Path> shards = Files.list(blobsDir)) {
            shards.filter(Files::isDirectory).forEach(shard -> {
                harden(shard, OWNER_DIRECTORY);
                try (Stream<Path> files = Files.list(shard)) {
                    files.forEach(f -> {
                        String name = f.getFileName().toString();
                        if (!isBodyFileName(name)) {
                            deleteIfStaleStaging(f, name, now);
                            return;
                        }
                        String sha = name.substring(0, name.length() - SUFFIX.length());
                        if (live == null || !live.contains(sha)) {
                            try {
                                Files.deleteIfExists(f);
                            } catch (IOException ignored) {
                                // a blob we can't delete just lingers; harmless
                            }
                        } else {
                            harden(f, OWNER_FILE);
                        }
                    });
                } catch (IOException ignored) {
                    return; // unreadable shard: skip
                }
                try {
                    Files.delete(shard); // only succeeds when nothing is left in it
                } catch (IOException notEmpty) {
                    // still in use
                }
            });
        } catch (IOException ignored) {
            // unreadable blobs dir: nothing to GC
        }
    }

    /** Whether {@code name} is a body file ({@code <sha>.txt.gz}) rather than a staging file or a stray. */
    public static boolean isBodyFileName(String name) {
        return name != null && name.endsWith(SUFFIX) && !name.startsWith(".");
    }

    private static void deleteIfStaleStaging(Path file, String name, long now) {
        if (!name.startsWith(".") || !name.endsWith(".tmp")) {
            return; // not ours: leave it
        }
        try {
            if (now - Files.getLastModifiedTime(file).toMillis() > STALE_STAGING_MILLIS) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
            // it lingers until the next collection
        }
    }

    /** Tightens permissions left by older versions without changing or deleting any stored revision. */
    void hardenExisting() {
        if (!Files.isDirectory(blobsDir)) {
            return;
        }
        harden(blobsDir, OWNER_DIRECTORY);
        try (Stream<Path> shards = Files.list(blobsDir)) {
            shards.forEach(shard -> {
                if (Files.isDirectory(shard)) {
                    harden(shard, OWNER_DIRECTORY);
                    try (Stream<Path> files = Files.list(shard)) {
                        files.forEach(file -> harden(file, OWNER_FILE));
                    } catch (IOException ignored) {
                        // Best effort: another shard can still be hardened.
                    }
                } else {
                    harden(shard, OWNER_FILE);
                }
            });
        } catch (IOException ignored) {
            // Best effort on an unreadable store.
        }
    }

    /** {@code <blobsDir>/<first-2-hex>/<sha>.txt.gz}; a too-short sha shards under {@code "_"}. */
    private Path pathFor(String sha) {
        String prefix = sha != null && sha.length() >= 2 ? sha.substring(0, 2) : "_";
        return blobsDir.resolve(prefix).resolve(sha + SUFFIX);
    }

    private static Path createOwnerOnlyTemp(Path file) throws IOException {
        Path parent = file.getParent();
        String prefix = "." + file.getFileName() + "-";
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try {
                return Files.createTempFile(parent, prefix, ".tmp", PosixFilePermissions.asFileAttribute(OWNER_FILE));
            } catch (UnsupportedOperationException ignored) {
                // Fall through to the portable creation path.
            }
        }
        return Files.createTempFile(parent, prefix, ".tmp");
    }

    private static void createPrivateDirectories(Path directory) throws IOException {
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(OWNER_DIRECTORY));
                return;
            } catch (UnsupportedOperationException ignored) {
                // Fall through to the portable creation path.
            }
        }
        Files.createDirectories(directory);
    }

    private static void harden(Path path, Set<PosixFilePermission> permissions) {
        if (path == null || !path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Best effort on filesystems that report POSIX support but reject a chmod operation.
        }
    }

    private static byte[] gzip(String content) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }

    // Retained for symmetry/testing: gunzip a byte[] (not used by the live read path).
    static String gunzip(byte[] data) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
