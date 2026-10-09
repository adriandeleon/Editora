package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.editora.history.HistoryBlobStore;

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

    /** A {@code "sha256":"…"} member, wherever it stands — in a whole index or in what is left of a torn one. */
    private static final Pattern HASH_MEMBER = Pattern.compile("\"sha256\"\\s*:\\s*\"([^\"\\\\]+)\"");

    /**
     * The names of the index backups beside {@code index} (see {@link #backupPresent}), or {@code null} when
     * the folder cannot be listed.
     */
    static Set<String> backupNames(Path index) {
        Path dir = index.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return Set.of();
        }
        String name = index.getFileName().toString();
        try (Stream<Path> siblings = Files.list(dir)) {
            return siblings.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(name)
                            && BACKUP_SUFFIX.matcher(n.substring(name.length())).matches())
                    .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        } catch (IOException e) {
            return null;
        }
    }

    /** For how long a backup that is not a whole index keeps every body safe (see {@link BackupHashes}). */
    static final long INCOMPLETE_BACKUP_GRACE_MILLIS = 30L * 86_400_000L;

    /**
     * What the backups beside the index say a collection must keep.
     *
     * @param hashes every body hash a backup refers to
     * @param incompleteSince the modification time (epoch millis) of the newest backup that is <em>not a whole
     *     index</em> — torn, empty, unrecognisable — or {@code Long.MIN_VALUE} when every backup is whole. Rows
     *     past the tear are gone, so their bodies are in no index and no backup: the only way back to that
     *     text is the body files themselves. They are all kept for
     *     {@link #INCOMPLETE_BACKUP_GRACE_MILLIS} after such a backup was made — time to notice and recover —
     *     and collected normally after that.
     */
    record BackupHashes(Set<String> hashes, long incompleteSince) {

        /** Whether collection may run at {@code now}: no incomplete backup, or its grace period is over. */
        boolean allowsCollection(long now) {
            return incompleteSince == Long.MIN_VALUE || now - incompleteSince > INCOMPLETE_BACKUP_GRACE_MILLIS;
        }
    }

    /**
     * The body hashes the backups beside {@code index} refer to — what a collection has to keep for as long
     * as those backups are on disk — or {@code null} when a backup cannot be read at all, and nothing may be
     * collected.
     *
     * <p>A backup is an index that did not load: torn by a crash, written by a newer build, holding a value of
     * the wrong type. Its rows are still legible for the one thing needed here, so the hashes are read by
     * pattern rather than by parsing — half an index yields the hashes of every row that made it to disk.
     * Refusing all collection while any backup existed kept those bodies safe too, but for good: one damaged
     * write, and no limit freed disk space and no purge removed content in any later session.
     */
    static BackupHashes backupHashes(Path index) {
        Set<String> names = backupNames(index);
        if (names == null) {
            return null;
        }
        Set<String> hashes = new java.util.HashSet<>();
        long incompleteSince = Long.MIN_VALUE;
        for (String name : names) {
            Path backup = index.resolveSibling(name);
            String text;
            long modified;
            try {
                text = new String(Files.readAllBytes(backup), java.nio.charset.StandardCharsets.UTF_8);
                modified = Files.getLastModifiedTime(backup).toMillis();
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
            java.util.regex.Matcher member = HASH_MEMBER.matcher(text);
            while (member.find()) {
                hashes.add(member.group(1));
            }
            if (!isWholeObject(text)) {
                incompleteSince = Math.max(incompleteSince, modified);
            }
        }
        return new BackupHashes(hashes, incompleteSince);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Whether {@code text} is one complete JSON object — an index of some version, all of it. */
    private static boolean isWholeObject(String text) {
        try {
            com.fasterxml.jackson.databind.JsonNode tree = JSON.readTree(text);
            return tree != null && tree.isObject();
        } catch (IOException | RuntimeException torn) {
            return false;
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

    /** A body, not a staging file a killed write left behind: that one is no evidence of lost history. */
    private static boolean hasFile(Path shard) {
        try (Stream<Path> files = Files.list(shard)) {
            return files.anyMatch(f -> Files.isRegularFile(f)
                    && HistoryBlobStore.isBodyFileName(f.getFileName().toString()));
        } catch (IOException e) {
            return true;
        }
    }
}
