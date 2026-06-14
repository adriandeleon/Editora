package com.editora.history;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private final Path blobsDir;

    public HistoryBlobStore(Path blobsDir) {
        this.blobsDir = blobsDir;
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
        if (Files.exists(file)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            byte[] gz = gzip(content);
            // Write to a temp file then move, so a crash mid-write can't leave a truncated blob.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, gz);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Deletes every stored blob whose sha is not in {@code live} (garbage collection). Best-effort. */
    public void deleteUnreferenced(Set<String> live) {
        if (!Files.isDirectory(blobsDir)) {
            return;
        }
        try (Stream<Path> shards = Files.list(blobsDir)) {
            shards.filter(Files::isDirectory).forEach(shard -> {
                try (Stream<Path> files = Files.list(shard)) {
                    files.forEach(f -> {
                        String name = f.getFileName().toString();
                        if (!name.endsWith(SUFFIX)) {
                            return;
                        }
                        String sha = name.substring(0, name.length() - SUFFIX.length());
                        if (live == null || !live.contains(sha)) {
                            try {
                                Files.deleteIfExists(f);
                            } catch (IOException ignored) {
                                // a blob we can't delete just lingers; harmless
                            }
                        }
                    });
                } catch (IOException ignored) {
                    // unreadable shard: skip
                }
            });
        } catch (IOException ignored) {
            // unreadable blobs dir: nothing to GC
        }
    }

    /** {@code <blobsDir>/<first-2-hex>/<sha>.txt.gz}; a too-short sha shards under {@code "_"}. */
    private Path pathFor(String sha) {
        String prefix = sha != null && sha.length() >= 2 ? sha.substring(0, 2) : "_";
        return blobsDir.resolve(prefix).resolve(sha + SUFFIX);
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
