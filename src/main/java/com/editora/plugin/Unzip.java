package com.editora.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A small, hardened zip extractor for plugin archives. Guards against the classic <b>zip-slip</b> path
 * traversal (an entry named {@code ../../etc/passwd} escaping the destination) and bounds the work with a
 * per-entry size cap, a total uncompressed-size cap, and an entry-count cap (zip-bomb defense). The
 * decision helpers ({@link #safeEntryName}, {@link #isUnderDir}) are pure and unit-tested; {@link #extract}
 * does the I/O.
 */
public final class Unzip {

    /** Max uncompressed bytes for a single entry. */
    public static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    /** Max total uncompressed bytes across the archive. */
    public static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    /** Max number of entries. */
    public static final int MAX_ENTRIES = 10_000;

    private Unzip() {}

    /**
     * Rejects an entry name that can't be safely joined to a destination dir: blank, absolute, a Windows
     * drive/backslash path, or one containing a {@code ..} segment. Pure — unit-tested.
     */
    public static boolean safeEntryName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = name.replace('\\', '/');
        if (n.startsWith("/") || n.contains(":")) {
            return false; // absolute / drive-qualified
        }
        for (String seg : n.split("/")) {
            if (seg.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code target} (after normalization) stays inside {@code dir}. Pure — unit-tested. */
    public static boolean isUnderDir(Path dir, Path target) {
        Path d = dir.toAbsolutePath().normalize();
        Path t = target.toAbsolutePath().normalize();
        return t.startsWith(d);
    }

    /**
     * Extracts a zip stream into {@code dest} (created if needed), enforcing the zip-slip guard + size caps.
     * Throws {@link IOException} on a malicious/oversized archive or any I/O error.
     */
    public static void extract(InputStream in, Path dest) throws IOException {
        extract(in, dest, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES, MAX_ENTRIES);
    }

    /**
     * As {@link #extract(InputStream, Path)} but with explicit caps. The installer path passes generous caps
     * because native language-server binaries (e.g. clangd, ~130 MB uncompressed) exceed the small plugin
     * defaults; the zip-slip guard is unchanged.
     */
    public static void extract(InputStream in, Path dest, long maxEntryBytes, long maxTotalBytes, int maxEntries)
            throws IOException {
        Files.createDirectories(dest);
        long total = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > maxEntries) {
                    throw new IOException("archive has too many entries");
                }
                if (!safeEntryName(entry.getName())) {
                    throw new IOException("unsafe zip entry name: " + entry.getName());
                }
                Path target = dest.resolve(entry.getName()).normalize();
                if (!isUnderDir(dest, target)) {
                    throw new IOException("zip entry escapes destination: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                long written = 0;
                try (var out = Files.newOutputStream(target)) {
                    int n;
                    while ((n = zip.read(buf)) > 0) {
                        written += n;
                        total += n;
                        if (written > maxEntryBytes) {
                            throw new IOException("zip entry too large: " + entry.getName());
                        }
                        if (total > maxTotalBytes) {
                            throw new IOException("archive too large");
                        }
                        out.write(buf, 0, n);
                    }
                }
            }
        }
    }
}
