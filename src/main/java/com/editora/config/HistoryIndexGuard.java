package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Decides whether the Local History index on disk can be trusted as the complete list of live revisions.
 *
 * <p>Blob garbage collection deletes every stored revision body the index does not reference. That is only
 * safe when the index is the one that was written. When it is not — it would not load, or it is empty or gone
 * while bodies are still stored — collecting against it deletes all of them. Pure file-system queries, no
 * state.
 */
final class HistoryIndexGuard {

    /** {@code .v3.bak}, {@code .corrupt.bak}, and their numbered siblings ({@code .corrupt.bak.2}). */
    private static final Pattern BACKUP_SUFFIX = Pattern.compile("\\..*bak(\\.\\d+)?");

    private HistoryIndexGuard() {}

    /**
     * True when the index is zero-length or missing although revision bodies are stored: the index was lost
     * (a write the OS never flushed, a deleted file), which is different from "no history yet".
     */
    static boolean lostIndex(Path index, Path blobsDir) {
        try {
            if (Files.exists(index) && Files.size(index) > 0) {
                return false;
            }
        } catch (IOException unreadable) {
            return false; // the read that follows reports it
        }
        return hasBlobs(blobsDir);
    }

    /**
     * True while a backup of an index that did not load ({@code index.json.v<n>.bak},
     * {@code index.json.corrupt.bak}, …) sits beside {@code index}. The bodies that backup references are not
     * in the index written since, so they stay protected until the user restores or removes the backup.
     */
    static boolean backupPresent(Path index) {
        Path dir = index.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        String name = index.getFileName().toString();
        try (Stream<Path> siblings = Files.list(dir)) {
            return siblings.map(p -> p.getFileName().toString())
                    .anyMatch(n -> n.startsWith(name)
                            && BACKUP_SUFFIX.matcher(n.substring(name.length())).matches());
        } catch (IOException e) {
            return true; // cannot tell: keeping blobs is the safe answer
        }
    }

    /** Whether at least one revision body is stored under {@code blobsDir} (one level of shard directories). */
    static boolean hasBlobs(Path blobsDir) {
        if (!Files.isDirectory(blobsDir)) {
            return false;
        }
        try (Stream<Path> shards = Files.list(blobsDir)) {
            return shards.filter(Files::isDirectory).anyMatch(HistoryIndexGuard::hasFile);
        } catch (IOException e) {
            return true; // cannot tell: keeping blobs is the safe answer
        }
    }

    private static boolean hasFile(Path shard) {
        try (Stream<Path> files = Files.list(shard)) {
            return files.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            return true;
        }
    }
}
