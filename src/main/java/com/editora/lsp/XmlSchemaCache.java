package com.editora.lsp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * The XML server's schema cache ({@link XmlServerSettings#cacheDir}): what it holds and emptying it, for Doctor
 * (XML review D4). lemminx downloads a grammar again the next time a file needs it, so clearing is always safe.
 * Blocking I/O: call off the FX thread. Links are never followed.
 */
public final class XmlSchemaCache {

    private XmlSchemaCache() {}

    /** Total size of the regular files under {@code dir}; 0 when it is absent or unreadable. */
    public static long size(Path dir) {
        if (dir == null || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        try (var paths = Files.walk(dir)) {
            return paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        } catch (IOException | UncheckedIOException e) {
            return 0;
        }
    }

    /**
     * Deletes everything under {@code dir}, the folder itself kept; returns the bytes the deleted files held. A file
     * that cannot be deleted (held open on Windows) is left, and the rest still goes.
     */
    public static long clear(Path dir) {
        if (dir == null || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        List<Path> paths;
        try (var walk = Files.walk(dir)) {
            paths = walk.filter(p -> !p.equals(dir))
                    .sorted(Comparator.reverseOrder()) // children before their folder
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            return 0;
        }
        long freed = 0;
        for (Path p : paths) {
            try {
                long size = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) ? Files.size(p) : 0;
                Files.deleteIfExists(p);
                freed += size;
            } catch (IOException e) {
                // in use, or a folder whose undeletable file kept it: leave it
            }
        }
        return freed;
    }
}
