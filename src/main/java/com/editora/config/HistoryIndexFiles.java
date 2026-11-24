package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;
import com.editora.config.migration.NewerThanSupportedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * Writes the Local History index as two files: {@code history/index.json}, the whole index, and beside it a
 * small {@link HistoryDelta} — what changed since {@code index.json} was last written. A recorded save rewrites
 * only the delta (one small file, synced); the index is written whole ("compacted") when it is small anyway,
 * when the delta has grown, before a blob collection, and at quit (perf finding F6: a 7 MB index used to be
 * serialized, read back, written and synced on every save).
 *
 * <p>Both files are whole JSON documents written atomically and synced, like every other store: there is no log
 * to replay, no torn line and no partial state. What a reader takes for the index is the index file with the
 * delta applied ({@link HistoryDelta#applyTo}), which is idempotent: a delta left beside an index rewritten since
 * (a crash between the two writes) gives the same rows.
 *
 * <p>Several processes on one config folder merge as for any store ({@link StoreSync}), with what each process
 * last published as the common ancestor. Under the lock across processes ({@link StoreSync#locked}):
 *
 * <ul>
 *   <li>while {@code index.json} is the one this process knows (its file attributes, and the hash every delta
 *       names it by), the delta files are merged three ways ({@link HistoryDelta#MERGE_KEYS});
 *   <li>otherwise, and for every compaction, the merge is of whole indexes: what this process last published,
 *       what it holds now, and what is on disk — the index file <em>with its delta applied</em>. So a row that
 *       only a delta lists is in every writer's ancestor, and another writer's purge of it is not undone.
 * </ul>
 *
 * The delta's name ends in {@code .bak} on purpose: every Editora since 0.25 protects the bodies listed by any
 * {@code index.json…bak} beside the index from blob collection ({@link HistoryIndexGuard#backupHashes}), so an
 * older build run after a crash cannot delete the bodies of revisions only the delta lists. This build leaves the
 * delta out of that scan.
 *
 * <p>All file work runs on the config writer thread; {@link #loaded} runs once, at load.
 */
final class HistoryIndexFiles {

    private static final Logger LOG = Logger.getLogger(HistoryIndexFiles.class.getName());

    /** The delta file's name after the index's own ({@code index.json.recent.bak}). */
    static final String DELTA_SUFFIX = ".recent.bak";

    /** Below this size the index is always written whole: a delta would save next to nothing. */
    static final long DELTA_MIN_INDEX_BYTES = 256L * 1024;

    /** A delta larger than this and than an eighth of the index is folded into the index. */
    static final long DELTA_MAX_BYTES = 64L * 1024;

    private final Path index;
    private final Path delta;
    private final ObjectMapper json;
    private final StoreSync sync;

    private volatile long deltaMinIndexBytes = DELTA_MIN_INDEX_BYTES;

    // --- what this process knows of the two files; guarded by `this` --------------------------------------

    /** {@code index.json} as this process last read or wrote it; {@code null} = not loaded. */
    private HistoryStore.@Nullable Snapshot indexKnown;
    /** Hash of {@code index.json}'s bytes as last seen; {@code null} = it did not exist. */
    private byte @Nullable [] indexHash;
    /** Its attributes as last seen, a cheap check that nobody has replaced it since. */
    private @Nullable Stat indexStat;

    private long indexBytes;
    /** The bytes on disk are not what this build writes for {@link #indexKnown}: write it whole once. */
    private boolean indexNormalize;

    /** The index as this process last published it: the common ancestor of a merge. */
    private HistoryStore.@Nullable Snapshot published;
    /** {@code diff(indexKnown, published)}, and its bytes: the ancestor of a merge of delta files. */
    private @Nullable HistoryDelta deltaKnown;

    private byte @Nullable [] deltaKnownBytes;
    /** Hash of the delta file's bytes as last seen; {@code null} = it did not exist. */
    private byte @Nullable [] deltaHash;
    /** The delta file could not be read at load: what it lists is unknown until a publication reads it. */
    private volatile boolean deltaUnknown;
    /**
     * The files on disk, together, hold exactly {@link #published} — nothing another writer put there. Only
     * then may a publication write without reading and merging.
     */
    private boolean pure;

    private record Stat(
            long size, long modifiedNanos, @Nullable Object fileKey) {}

    HistoryIndexFiles(Path index, ObjectMapper json, StoreSync sync) {
        this.index = index;
        this.delta = deltaFile(index);
        this.json = json;
        this.sync = sync;
    }

    static Path deltaFile(Path index) {
        return index.resolveSibling(index.getFileName() + DELTA_SUFFIX);
    }

    /** For tests: use a delta for an index of any size ({@code 0}), or never ({@link Long#MAX_VALUE}). */
    void deltaMinIndexBytes(long bytes) {
        deltaMinIndexBytes = bytes;
    }

    /** Whether the delta could be read (or there is none): collection must wait until it can. */
    boolean deltaReadable() {
        return !deltaUnknown;
    }

    // --- load ----------------------------------------------------------------------------------------------

    /** What {@link #loaded} found in the delta. */
    record LoadedDelta(
            @Nullable HistoryDelta delta,
            boolean unreadable,
            @Nullable Path damagedCopy) {}

    /**
     * Records that {@code index.json} was just read into {@code raw} (as read, before any re-ordering), then
     * reads the delta and returns it for the caller to apply. Call once, at load, before the index changes.
     */
    synchronized LoadedDelta loaded(HistoryStore.Snapshot raw) {
        byte[] disk;
        try {
            disk = StoreSync.readIfPresent(index);
        } catch (IOException unreadable) {
            disk = new byte[0]; // whatever the load made of it, it is not what is on disk
        }
        indexKnown = raw;
        indexHash = StoreSync.hash(disk);
        indexStat = stat(index);
        indexBytes = disk == null ? 0 : disk.length;
        try {
            indexNormalize = disk != null && !Arrays.equals(disk, json.writeValueAsBytes(raw));
        } catch (IOException e) {
            indexNormalize = true;
        }
        HistoryDelta read = null;
        boolean unreadable = false;
        Path copy = null;
        byte[] deltaBytes = null;
        try {
            deltaBytes = Files.readAllBytes(delta);
        } catch (NoSuchFileException absent) {
            deltaBytes = null;
        } catch (IOException e) {
            // Cannot be read now (permissions, a sync tool holding it): what it lists is unknown, and it is
            // neither replaced nor collected against until a publication reads it.
            LOG.log(Level.WARNING, "Could not read the Local History delta " + delta, e);
            unreadable = true;
        }
        if (deltaBytes != null) {
            try {
                read = HistoryDelta.fromBytes(deltaBytes, json);
            } catch (IOException damaged) {
                // Damaged, or from a newer build: keep its bytes — the copy's hashes keep its bodies
                // (HistoryIndexGuard.backupHashes) — and go on without it. Its rows are missing from this session.
                copy = ConfigMigrations.keepCopy(delta);
                LOG.log(
                        Level.WARNING,
                        "The Local History delta {0} cannot be read ({1}); kept a copy at {2}",
                        new Object[] {delta, damaged.getMessage(), copy});
            }
        }
        deltaUnknown = unreadable;
        deltaHash = unreadable ? new byte[0] : StoreSync.hash(deltaBytes);
        return new LoadedDelta(read, unreadable, copy);
    }

    /**
     * Records the index as loaded, the delta applied, before anything else changed it: the first common
     * ancestor. {@code deltaOnDisk} is what {@link #loaded} returned.
     */
    synchronized void loadedIndex(HistoryStore.Snapshot index, @Nullable HistoryDelta deltaOnDisk) {
        HistoryStore.Snapshot known = Objects.requireNonNull(indexKnown);
        published = index;
        deltaKnown = HistoryDelta.diff(known, index, hex(indexHash));
        try {
            deltaKnownBytes = deltaKnown.toBytes(json);
        } catch (IOException e) {
            deltaKnownBytes = null;
        }
        // Pure when the delta on disk is exactly what this build would write for it (or there is none and
        // nothing to say), and it was taken against this very index file.
        boolean deltaAsWritten = deltaOnDisk == null
                ? deltaHash == null && deltaKnown.isEmpty()
                : deltaHash != null && Arrays.equals(deltaHash, StoreSync.hash(deltaKnownBytes));
        pure = !deltaUnknown && deltaAsWritten;
    }

    // --- publish -------------------------------------------------------------------------------------------

    /** What one publication did. */
    record Published(
            boolean written,
            boolean sawDisk,
            boolean compacted,
            @Nullable Set<String> foreignInIndex,
            Set<String> foreignInDelta,
            Map<String, Map<String, List<HistoryRevision>>> foreignRows) {

        static Published nothing(boolean sawDisk) {
            return new Published(false, sawDisk, false, null, Set.of(), Map.of());
        }
    }

    /**
     * Makes the files on disk hold {@code mine} without undoing another writer's changes. On the writer thread.
     *
     * @param compact write the index whole (and empty the delta), so that the publication reads both files —
     *     a blob collection behind it then knows every row on disk
     */
    Published publish(HistoryStore.Snapshot mine, boolean compact) throws IOException {
        return sync.locked(() -> {
            synchronized (this) {
                return publishLocked(mine, compact);
            }
        });
    }

    private Published publishLocked(HistoryStore.Snapshot mine, boolean compactWanted) throws IOException {
        byte[] deltaBytes = StoreSync.readIfPresent(delta); // unreadable: fail now, retried later
        boolean deltaChanged = deltaUnknown || !Arrays.equals(StoreSync.hash(deltaBytes), deltaHash);
        HistoryDelta deltaDisk = null;
        boolean deltaDamaged = false;
        if (deltaBytes != null) {
            try {
                deltaDisk = HistoryDelta.fromBytes(deltaBytes, json);
            } catch (HistoryDelta.NewerDeltaException newer) {
                throw newer; // a newer build is writing it now: do not replace what only it knows
            } catch (IOException damaged) {
                deltaDamaged = true;
            }
        }
        Stat statNow = stat(index);
        boolean indexChanged = !Objects.equals(statNow, indexStat)
                || (deltaDisk != null
                        && deltaChanged
                        && !deltaDisk.baseIndexSha256().equals(hex(indexHash)));
        HistoryStore.Snapshot known = indexKnown;
        if (known == null
                || compactWanted
                || indexNormalize
                || indexHash == null
                || indexBytes < deltaMinIndexBytes
                || HistoryDelta.inexpressible(known, mine)) {
            return writeWhole(mine, true, deltaBytes, deltaDisk, deltaChanged);
        }
        HistoryDelta mineDelta = HistoryDelta.diff(known, mine, hex(indexHash));
        byte[] mineDeltaBytes = mineDelta.toBytes(json);
        boolean compact = mineDeltaBytes.length > Math.max(DELTA_MAX_BYTES, indexBytes / 8);
        if (!compact && !indexChanged && !deltaDamaged) {
            return writeDelta(mine, mineDelta, mineDeltaBytes, deltaDisk, deltaChanged, statNow);
        }
        return writeWhole(mine, compact, deltaBytes, deltaDisk, deltaChanged);
    }

    /** The index file is the one this process knows: only the delta files are merged and written. */
    private Published writeDelta(
            HistoryStore.Snapshot mine,
            HistoryDelta mineDelta,
            byte[] mineDeltaBytes,
            @Nullable HistoryDelta deltaDisk,
            boolean deltaChanged,
            @Nullable Stat statNow)
            throws IOException {
        indexStat = statNow;
        if (!deltaChanged && pure && Arrays.equals(mineDeltaBytes, deltaKnownBytes)) {
            return Published.nothing(false); // nothing changed here; whatever is on disk stays
        }
        byte[] out = mineDeltaBytes;
        Set<String> foreign = Set.of();
        Map<String, Map<String, List<HistoryRevision>>> foreignRows = Map.of();
        if ((deltaChanged || !pure) && deltaDisk != null) {
            HistoryDelta base = deltaKnown != null ? deltaKnown : HistoryDelta.empty(mineDelta.baseIndexSha256());
            JsonNode mineTree = mineDelta.toTree(json);
            JsonNode merged = mergeRows(
                    base.toTree(json), mineTree, deltaDisk.toTree(json), HistoryDelta.MERGE_KEYS, DELTA_BUCKETS);
            if (merged != null && !merged.equals(mineTree)) {
                HistoryDelta mergedDelta = HistoryDelta.fromTree(merged, json);
                out = mergedDelta.toBytes(json);
                foreignRows = rowsNotIn(mergedDelta.added(), mine);
                foreign = hashes(foreignRows);
            }
        }
        ConfigWriter.writeAtomicChecked(delta, out);
        deltaHash = StoreSync.hash(out);
        deltaUnknown = false;
        published = mine;
        deltaKnown = mineDelta;
        deltaKnownBytes = mineDeltaBytes;
        pure = out == mineDeltaBytes;
        return new Published(true, false, false, null, foreign, foreignRows);
    }

    /**
     * The index file changed underneath (another writer compacted, an older build wrote it), or this
     * publication compacts: the merge is of whole indexes, each file with its delta applied.
     */
    private Published writeWhole(
            HistoryStore.Snapshot mine,
            boolean compact,
            byte @Nullable [] deltaBytes,
            @Nullable HistoryDelta deltaDisk,
            boolean deltaChanged)
            throws IOException {
        byte[] indexDisk = StoreSync.readIfPresent(index);
        byte[] indexDiskHash = StoreSync.hash(indexDisk);
        boolean indexChanged = !Arrays.equals(indexDiskHash, indexHash);
        if (!indexChanged) {
            indexStat = stat(index); // the same bytes (touched, copied back): the cheap check may trust it again
        }
        HistoryStore.Snapshot theirsIndex = null;
        boolean indexDamaged = false;
        if (indexDisk != null && !indexChanged && indexKnown != null) {
            theirsIndex = indexKnown; // the bytes this process last read or wrote: no need to parse them again
        } else if (indexDisk != null) {
            try {
                theirsIndex = parseIndex(indexDisk);
            } catch (NewerThanSupportedException newer) {
                throw new IOException(
                        index.getFileName() + " was written by a newer Editora (schema " + newer.storedVersion()
                                + "); not overwriting it",
                        newer);
            } catch (IOException | RuntimeException damaged) {
                indexDamaged = true;
            }
        }
        if (theirsIndex != null) {
            theirsIndex = share(theirsIndex, mine);
        }
        boolean merge = indexChanged || deltaChanged || !pure;
        HistoryStore.Snapshot out = mine;
        JsonNode mergedTree = null;
        if (merge && !indexDamaged && published != null) {
            HistoryStore.Snapshot theirs = theirsIndex != null ? theirsIndex : emptyLike(mine);
            if (deltaDisk != null) {
                theirs = deltaDisk.applyTo(theirs);
            }
            JsonNode mineTree = json.valueToTree(mine);
            JsonNode theirsTree = json.valueToTree(theirs);
            JsonNode baseTree = json.valueToTree(published);
            if (!theirsTree.equals(baseTree) && !theirsTree.equals(mineTree)) {
                JsonNode merged = mergeRows(
                        baseTree, mineTree, theirsTree, StoreMerge.keysFor(ConfigSchema.HISTORY), INDEX_BUCKETS);
                if (merged != null && !merged.equals(mineTree)) {
                    mergedTree = merged;
                    out = share(parseIndex(merged), mine);
                }
            }
        }
        if (indexDamaged) {
            // Damaged since this process last saw it: nothing to merge with. Keep the bytes, write ours.
            Path kept = ConfigMigrations.keepCopy(index);
            LOG.log(
                    Level.WARNING,
                    "Local History index {0} changed on disk and cannot be read; kept a copy at {1}",
                    new Object[] {index, kept});
        }
        if (deltaBytes != null && deltaDisk == null && (deltaChanged || deltaUnknown)) {
            // A damaged delta: its bytes are kept (and its bodies protected by the copy) before it is replaced.
            ConfigMigrations.keepCopy(delta);
        }
        Set<String> foreignInIndex = mergedTree == null ? Set.of() : SharedConfig.foreignHashes(mergedTree, mine);
        Map<String, Map<String, List<HistoryRevision>>> foreignRows =
                mergedTree == null ? Map.of() : rowsNotIn(out.byProject(), mine);

        if (!compact && theirsIndex != null && indexDisk != null && !indexDamaged) {
            // Another writer replaced the index: take it as the new base, and say how ours differs from it.
            String baseHash = hex(indexDiskHash);
            HistoryDelta newDelta = HistoryDelta.diff(theirsIndex, out, baseHash);
            byte[] deltaOut = newDelta.toBytes(json);
            if (deltaOut.length <= Math.max(DELTA_MAX_BYTES, indexDisk.length / 8L)) {
                ConfigWriter.writeAtomicChecked(delta, deltaOut);
                indexKnown = theirsIndex;
                indexHash = indexDiskHash;
                indexStat = stat(index);
                indexBytes = indexDisk.length;
                indexNormalize = false;
                deltaHash = StoreSync.hash(deltaOut);
                finishWhole(mine, mergedTree == null);
                return new Published(true, true, false, foreignInIndex, Set.of(), foreignRows);
            }
        }

        byte[] indexOut = mergedTree != null ? json.writeValueAsBytes(mergedTree) : json.writeValueAsBytes(mine);
        byte[] indexOutHash = StoreSync.hash(indexOut);
        boolean deltaEmptyOnDisk = deltaBytes == null || (deltaDisk != null && deltaDisk.isEmpty());
        if (!merge && !indexNormalize && Arrays.equals(indexOutHash, indexDiskHash) && deltaEmptyOnDisk) {
            return Published.nothing(true); // both files already hold this
        }
        ConfigWriter.writeAtomicChecked(index, indexOut);
        indexKnown = out;
        indexHash = indexOutHash;
        indexStat = stat(index);
        indexBytes = indexOut.length;
        indexNormalize = false;
        if (deltaBytes != null || deltaHash != null) {
            // Emptied, not deleted: the hash it names tells every other writer that the index was replaced.
            byte[] emptied = HistoryDelta.empty(hex(indexOutHash)).toBytes(json);
            ConfigWriter.writeAtomicChecked(delta, emptied);
            deltaHash = StoreSync.hash(emptied);
        } else {
            deltaHash = null;
        }
        finishWhole(mine, mergedTree == null);
        return new Published(true, true, true, foreignInIndex, Set.of(), foreignRows);
    }

    /** After a whole-index merge: {@code mine} is the new ancestor; pure when nothing of another writer's was kept. */
    private void finishWhole(HistoryStore.Snapshot mine, boolean nothingMerged) throws IOException {
        HistoryStore.Snapshot known = Objects.requireNonNull(indexKnown);
        published = mine;
        deltaKnown = HistoryDelta.diff(known, mine, hex(indexHash));
        deltaKnownBytes = deltaKnown.toBytes(json);
        deltaUnknown = false;
        pure = nothingMerged;
    }

    // --- helpers -------------------------------------------------------------------------------------------
    /** Where an index tree keeps its rows: {@code byProject / <project> / <file>}. */
    private static final List<List<String>> INDEX_BUCKETS = List.of(List.of("byProject"));

    /** Where a delta tree keeps its rows. */
    private static final List<List<String>> DELTA_BUCKETS =
            List.of(List.of("added", "byProject"), List.of("removed", "byProject"));

    /**
     * A three-way merge ({@link StoreMerge}) of trees that keep rows under {@code <bucket>/<project>/<file>},
     * where a project or file that one side does not have counts as one with no rows.
     *
     * <p>A file whose last row was dropped leaves the tree, and so does a project. To a plain merge, a file that
     * the other writer took out while this process changed it is a conflict, which this process wins whole:
     * every row of it, including the ones the other writer had dropped, came back. Read as "no rows", the same
     * change merges row by row: the other writer's purge stands, this process's new rows are kept. What this
     * leaves empty and this process did not have is taken out again.
     */
    static @Nullable JsonNode mergeRows(
            JsonNode base, JsonNode mine, JsonNode theirs, StoreMerge.Keys keys, List<List<String>> buckets) {
        JsonNode b = base.deepCopy();
        JsonNode m = mine.deepCopy();
        JsonNode t = theirs.deepCopy();
        Map<List<String>, Map<String, Set<String>>> mineHad = new LinkedHashMap<>();
        for (List<String> bucket : buckets) {
            ObjectNode bb = objectAt(b, bucket);
            ObjectNode mb = objectAt(m, bucket);
            ObjectNode tb = objectAt(t, bucket);
            if (bb == null || mb == null || tb == null) {
                continue; // not the shape this expects: merged as it is
            }
            Map<String, Set<String>> had = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> project : mb.properties()) {
                Set<String> files = new HashSet<>();
                project.getValue().fieldNames().forEachRemaining(files::add);
                had.put(project.getKey(), files);
            }
            mineHad.put(bucket, had);
            List<ObjectNode> sides = List.of(bb, mb, tb);
            Set<String> projects = new LinkedHashSet<>();
            sides.forEach(side -> side.fieldNames().forEachRemaining(projects::add));
            for (String project : projects) {
                List<ObjectNode> files = new ArrayList<>();
                for (ObjectNode side : sides) {
                    JsonNode node = side.get(project);
                    if (node == null) {
                        files.add(side.putObject(project));
                    } else if (node instanceof ObjectNode object) {
                        files.add(object);
                    }
                }
                if (files.size() != sides.size()) {
                    continue; // a value of another shape: merged as it is
                }
                Set<String> names = new LinkedHashSet<>();
                files.forEach(side -> side.fieldNames().forEachRemaining(names::add));
                for (String name : names) {
                    for (ObjectNode side : files) {
                        if (!side.has(name)) {
                            side.putArray(name);
                        }
                    }
                }
            }
        }
        JsonNode merged = StoreMerge.merge(b, m, t, keys);
        if (merged == null) {
            return null;
        }
        mineHad.forEach((bucket, had) -> {
            ObjectNode out = objectAt(merged, bucket);
            if (out == null) {
                return;
            }
            Iterator<Map.Entry<String, JsonNode>> projects = out.properties().iterator();
            while (projects.hasNext()) {
                Map.Entry<String, JsonNode> project = projects.next();
                Set<String> files = had.get(project.getKey());
                if (!(project.getValue() instanceof ObjectNode rows)) {
                    continue;
                }
                rows.properties()
                        .removeIf(file -> file.getValue().isArray()
                                && file.getValue().isEmpty()
                                && (files == null || !files.contains(file.getKey())));
                if (rows.isEmpty() && files == null) {
                    projects.remove();
                }
            }
        });
        return merged;
    }

    private static @Nullable ObjectNode objectAt(JsonNode tree, List<String> path) {
        JsonNode node = tree;
        for (String key : path) {
            node = node.get(key);
            if (node == null) {
                return null;
            }
        }
        return node instanceof ObjectNode object ? object : null;
    }

    private HistoryStore.Snapshot parseIndex(byte[] bytes) throws IOException {
        return parseIndex(json.readTree(ConfigMigrations.decodeText(bytes)));
    }

    private HistoryStore.Snapshot parseIndex(JsonNode tree) throws IOException {
        if (tree == null || !tree.isObject()) {
            throw new IOException("not a JSON object");
        }
        JsonNode upgraded = ConfigMigrations.upgrade(ConfigSchema.HISTORY, tree, json);
        HistoryStore store = json.readerForUpdating(new HistoryStore()).readValue(upgraded);
        return store.snapshot();
    }

    /**
     * {@code from}, with every project and file list that equals {@code mine}'s replaced by {@code mine}'s own
     * object. A snapshot read from disk shares nothing with the one in memory, and {@link HistoryDelta#diff}
     * skips only what is shared: without this, every save after a merge compared every row of the index.
     */
    static HistoryStore.Snapshot share(HistoryStore.Snapshot from, HistoryStore.Snapshot mine) {
        Map<String, Map<String, List<HistoryRevision>>> projects = new LinkedHashMap<>();
        from.byProject().forEach((project, files) -> {
            Map<String, List<HistoryRevision>> own = mine.byProject().get(project);
            if (own == null) {
                projects.put(project, files);
            } else if (own.equals(files)) {
                projects.put(project, own);
            } else {
                Map<String, List<HistoryRevision>> shared = new LinkedHashMap<>();
                files.forEach((file, rows) -> {
                    List<HistoryRevision> ownRows = own.get(file);
                    shared.put(file, ownRows != null && ownRows.equals(rows) ? ownRows : rows);
                });
                projects.put(project, java.util.Collections.unmodifiableMap(shared));
            }
        });
        return new HistoryStore.Snapshot(
                from.schemaVersion(), java.util.Collections.unmodifiableMap(projects), from.acknowledgedLimits());
    }

    private static HistoryStore.Snapshot emptyLike(HistoryStore.Snapshot mine) {
        return new HistoryStore.Snapshot(mine.schemaVersion(), Map.of(), null);
    }

    /** The rows of {@code rows} that {@code mine} does not list for the same project and file. */
    private static Map<String, Map<String, List<HistoryRevision>>> rowsNotIn(
            Map<String, Map<String, List<HistoryRevision>>> rows, HistoryStore.Snapshot mine) {
        Map<String, Map<String, List<HistoryRevision>>> out = new LinkedHashMap<>();
        rows.forEach((project, files) -> {
            Map<String, List<HistoryRevision>> own = mine.byProject().getOrDefault(project, Map.of());
            files.forEach((file, list) -> {
                List<HistoryRevision> own2 = own.getOrDefault(file, List.of());
                if (list == own2) {
                    return;
                }
                List<HistoryRevision> extra = HistoryDelta.minus(list, own2);
                if (!extra.isEmpty()) {
                    out.computeIfAbsent(project, k -> new LinkedHashMap<>()).put(file, extra);
                }
            });
        });
        return out;
    }

    private static Set<String> hashes(Map<String, Map<String, List<HistoryRevision>>> rows) {
        Set<String> out = new java.util.HashSet<>();
        rows.values().forEach(files -> files.values().forEach(list -> list.forEach(r -> out.add(r.sha256()))));
        return Set.copyOf(out);
    }

    private static String hex(byte @Nullable [] hash) {
        return hash == null ? "" : HexFormat.of().formatHex(hash);
    }

    private static @Nullable Stat stat(Path file) {
        try {
            // Through a link, as the writes go (ConfigWriter writes a linked config file's target).
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return new Stat(
                    attributes.size(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), attributes.fileKey());
        } catch (IOException absent) {
            return null;
        }
    }
}
