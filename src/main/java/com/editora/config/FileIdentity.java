package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Identity of a file beyond its path, so a {@link PersonalNote} can re-attach to the same file even after
 * it's renamed or moved outside Editora. Stores the original path, the canonical (resolved/normalized)
 * path, size, last-modified time, and a content hash. Matching follows the priority canonical path →
 * content hash → similar path (same name + size), per the SDD.
 *
 * <p>A Jackson-serialized record ({@code com.editora.config} is opened to jackson.databind). Hashing reads
 * the file, so compute it off the FX thread; files larger than {@link #MAX_HASH_BYTES} are not hashed
 * (empty hash) and fall back to path/size matching.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FileIdentity(String path, String canonicalPath, long size, long lastModified, String hash) {

    /** Files larger than this are not content-hashed (path/size matching is used instead). */
    public static final long MAX_HASH_BYTES = 5L * 1024 * 1024;

    /** Strength of a match between two identities; higher ordinals win. */
    public enum Match {
        NONE,
        SIMILAR_PATH,
        CONTENT_HASH,
        CANONICAL_PATH
    }

    public FileIdentity {
        path = path == null ? "" : path;
        canonicalPath = canonicalPath == null ? "" : canonicalPath;
        hash = hash == null ? "" : hash;
    }

    /** Builds an identity for {@code p}, hashing its content when readable and within {@link #MAX_HASH_BYTES}. */
    public static FileIdentity of(Path p) {
        if (p == null) {
            return new FileIdentity("", "", 0, 0, "");
        }
        String path = p.toString();
        String canonical = canonicalize(p);
        long size = 0;
        long modified = 0;
        try {
            size = Files.size(p);
            modified = Files.getLastModifiedTime(p).toMillis();
        } catch (IOException ignored) {
            // unreadable attributes ⇒ leave zero
        }
        String hash = (size > 0 && size <= MAX_HASH_BYTES) ? sha256(p) : "";
        return new FileIdentity(path, canonical, size, modified, hash);
    }

    private static String canonicalize(Path p) {
        try {
            return p.toRealPath().toString();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize().toString();
        }
    }

    /** SHA-256 of the file's bytes as lowercase hex, or {@code ""} on any error. */
    static String sha256(Path p) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(p));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException | OutOfMemoryError e) {
            return "";
        }
    }

    /**
     * How strongly {@code a} and {@code b} refer to the same file (pure): canonical path equality wins,
     * then a non-empty equal content hash, then a "similar path" (same file name + same non-zero size).
     */
    public static Match match(FileIdentity a, FileIdentity b) {
        if (a == null || b == null) {
            return Match.NONE;
        }
        if (!a.canonicalPath.isBlank() && a.canonicalPath.equals(b.canonicalPath)) {
            return Match.CANONICAL_PATH;
        }
        if (!a.hash.isBlank() && a.hash.equals(b.hash)) {
            return Match.CONTENT_HASH;
        }
        if (a.size > 0 && a.size == b.size && fileName(a.path).equals(fileName(b.path))) {
            return Match.SIMILAR_PATH;
        }
        return Match.NONE;
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
