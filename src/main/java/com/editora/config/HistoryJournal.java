package com.editora.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jspecify.annotations.Nullable;

/**
 * The Local History index's journal, {@code history/index.journal}: what changed in the index since
 * {@code index.json} was last written whole, one line per publication.
 *
 * <p>Writing the whole index after every recorded save made each save cost the size of the history: serialize
 * it, read the file back to compare, write it and sync it — megabytes per save for a long-lived history. Once
 * the index is at least {@link #MIN_INDEX_BYTES}, a publication instead appends the files that changed (the
 * rows added to and removed from each) and syncs that line; the index is written whole again (and the journal
 * deleted) when the journal reaches {@link #MAX_JOURNAL_BYTES}, when a blob collection is due, when the change
 * is not about rows (the agreed limits changed), at quit, and whenever this process is not the only
 * one on the config directory (only the primary, alone, appends — so two writers never share the file).
 *
 * <p><b>Recovery.</b> A publication is reported durable once its line is synced, exactly as it is once a whole
 * index is; the revision body was synced before either. At load the lines are applied in order to the index
 * read from disk ({@link #replay}), so after a crash the index is what it was at the last synced line. A change
 * is rows added and rows removed, never "the list is now …", so applying a line to an index that already holds
 * it (a crash after a whole write and before the journal was deleted), or to an index another process has
 * written rows to, changes nothing that line did not change. Before the index is written whole the change is
 * appended too, so such a left-over journal leads to exactly the index that was written. A last line cut short
 * by a crash was never reported durable and is ignored (and cut off before the next append); a line this build
 * cannot read freezes the journal for the session — never appended to or deleted, its bodies kept by hash.
 *
 * <p>Thread-safety: {@link #replay} and {@link #loaded} run while the config loads, everything else on the
 * config writer thread; the state is guarded by {@code this} all the same.
 */
final class HistoryJournal {

    private static final Logger LOG = Logger.getLogger(HistoryJournal.class.getName());

    static final String FILE_NAME = "index.journal";

    /** Below this the index is written whole on every publication, as it always was. */
    static final long MIN_INDEX_BYTES = 256L * 1024;

    /** At this size the journal is folded into a whole write of the index. */
    static final long MAX_JOURNAL_BYTES = 1024L * 1024;

    /** The line format; a line with a higher one is not applied. */
    static final int FORMAT = 1;

    /** One file's change: the rows it gained and the rows it lost. */
    record Change(String project, String file, List<HistoryRevision> added, List<HistoryRevision> removed) {}

    /** One journal line. */
    record Batch(int format, int schemaVersion, List<Change> changes) {}

    /** What {@link #replay} found. */
    record Replayed(int applied, long goodBytes, boolean frozen, Set<String> keptHashes) {
        static final Replayed NONE = new Replayed(0, 0, false, Set.of());
    }

    /** What {@link #publish} did. */
    enum Outcome {
        /** The change is in the journal, synced. */
        APPENDED,
        /** Nothing changed since the index and journal on disk were written. */
        NOTHING,
        /** Not journalled: the caller writes the index whole (then calls {@link #compacted}). */
        WHOLE
    }

    private static final Pattern SHA = Pattern.compile("\"sha256\"\\s*:\\s*\"([0-9a-fA-F]{8,128})\"");

    private final Path file;
    private final ObjectMapper json;
    private final ObjectWriter lineWriter;

    /** What {@code index.json} plus the journal hold, as this process last wrote or read them. */
    private HistoryStore.@Nullable Snapshot onDisk;

    private long onDiskPublication;
    /** The size of the index as last written or read whole. */
    private long indexBytes;
    /** The length of the journal's good lines, as this process last wrote or read them. */
    private long journalBytes;
    /** A journal this build could not read in full: left as it is for the rest of the session. */
    private boolean frozen;

    HistoryJournal(Path index, ObjectMapper json) {
        this.file = index.resolveSibling(FILE_NAME);
        this.json = json;
        this.lineWriter = json.writer().without(SerializationFeature.INDENT_OUTPUT);
    }

    Path file() {
        return file;
    }

    // --- load ----------------------------------------------------------------------------------------------

    /** Applies the journal's lines, in order, to {@code store} (the index just read from disk). */
    Replayed replay(HistoryStore store) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException absent) {
            return Replayed.NONE;
        } catch (IOException unreadable) {
            LOG.log(Level.WARNING, "Could not read the Local History journal " + file, unreadable);
            return new Replayed(0, 0, true, Set.of()); // cannot say what it holds: keep it, and every body
        }
        int applied = 0;
        boolean stuck = false;
        Set<String> kept = new HashSet<>();
        int start = 0;
        long good = 0;
        while (start < bytes.length) {
            int end = indexOf(bytes, (byte) '\n', start);
            if (end < 0) {
                break; // a last line cut short: never reported durable
            }
            String line = new String(bytes, start, end - start, StandardCharsets.UTF_8);
            start = end + 1;
            good = start;
            if (line.isBlank()) {
                continue;
            }
            Batch batch = stuck ? null : parse(line);
            if (batch == null) {
                stuck = true; // what follows may depend on it: keep it all, unapplied
                Matcher sha = SHA.matcher(line);
                while (sha.find()) {
                    kept.add(sha.group(1));
                }
                continue;
            }
            for (Change change : batch.changes()) {
                apply(store, change);
            }
            applied++;
        }
        return new Replayed(applied, good, stuck, Set.copyOf(kept));
    }

    private @Nullable Batch parse(String line) {
        try {
            Batch batch = json.readValue(line, Batch.class);
            if (batch == null
                    || batch.format() > FORMAT
                    || batch.schemaVersion() > HistoryStore.SCHEMA_VERSION
                    || batch.changes() == null) {
                return null;
            }
            for (Change change : batch.changes()) {
                if (change == null || change.project() == null || change.file() == null) {
                    return null;
                }
            }
            return batch;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** {@code change} applied to {@code store}: its removed rows dropped, its added rows listed, newest first. */
    static void apply(HistoryStore store, Change change) {
        List<HistoryRevision> added = change.added() == null ? List.of() : change.added();
        List<HistoryRevision> removed = change.removed() == null ? List.of() : change.removed();
        Map<String, List<HistoryRevision>> bucket = store.getByProject().get(change.project());
        if (bucket == null) {
            if (added.isEmpty()) {
                return;
            }
            bucket = store.bucket(change.project());
        }
        List<HistoryRevision> present = bucket.getOrDefault(change.file(), List.of());
        LinkedHashSet<HistoryRevision> rows = new LinkedHashSet<>(present);
        boolean changed = rows.removeAll(removed);
        for (HistoryRevision row : added) {
            changed |= row != null && rows.add(row);
        }
        if (!changed) {
            return;
        }
        if (rows.isEmpty()) {
            bucket.remove(change.file());
            return;
        }
        List<HistoryRevision> list = new ArrayList<>(rows);
        list.sort(Comparator.comparingLong(HistoryRevision::timestamp).reversed()); // stable
        bucket.put(change.file(), list);
    }

    /**
     * The index and journal on disk now hold {@code snapshot} (the index as loaded, the journal applied):
     * {@code indexBytes} long, with {@code replayed} read from the journal.
     */
    synchronized void loaded(HistoryStore.Snapshot snapshot, long indexBytes, Replayed replayed) {
        this.onDisk = snapshot;
        this.onDiskPublication = 0;
        this.indexBytes = indexBytes;
        this.journalBytes = replayed.goodBytes();
        this.frozen = replayed.frozen();
    }

    /** Whether the journal holds anything a whole write of the index would fold in. */
    synchronized boolean hasLines() {
        return journalBytes > 0 && !frozen;
    }

    // --- publication -----------------------------------------------------------------------------------------

    /**
     * Records {@code snapshot} (publication {@code publication}) in the journal when it may: this process is
     * the only one here ({@code alone}), the index is big enough, the journal not too big, no whole write is
     * asked for ({@code whole}), and the change is per file. Otherwise answers {@link Outcome#WHOLE} and the
     * caller writes the index whole.
     */
    synchronized Outcome publish(HistoryStore.Snapshot snapshot, long publication, boolean alone, boolean whole) {
        HistoryStore.Snapshot from = onDisk;
        if (whole
                || !alone
                || frozen
                || from == null
                || publication <= onDiskPublication
                || indexBytes < MIN_INDEX_BYTES
                || journalBytes >= MAX_JOURNAL_BYTES) {
            return Outcome.WHOLE;
        }
        List<Change> changes = diff(from, snapshot);
        if (changes == null) {
            return Outcome.WHOLE;
        }
        if (changes.isEmpty()) {
            onDisk = snapshot;
            onDiskPublication = publication;
            return Outcome.NOTHING;
        }
        try {
            append(changes);
        } catch (IOException failed) {
            LOG.log(Level.WARNING, "Could not append to the Local History journal; writing the index whole", failed);
            return Outcome.WHOLE;
        }
        onDisk = snapshot;
        onDiskPublication = publication;
        return Outcome.APPENDED;
    }

    /**
     * Before a whole write of {@code snapshot}: when the journal has lines this process may delete afterwards,
     * the change is appended to it too, so that a journal left behind by a crash between the write and the
     * deletion leads to exactly the index written. Best effort: a failure here only widens that window.
     */
    synchronized void beforeWhole(HistoryStore.Snapshot snapshot, boolean mayDelete) {
        HistoryStore.Snapshot from = onDisk;
        if (!mayDelete || frozen || from == null || journalBytes == 0) {
            return;
        }
        List<Change> changes = diff(from, snapshot);
        if (changes == null || changes.isEmpty()) {
            return; // a change that is not per file: the deletion follows the write closely anyway
        }
        try {
            append(changes);
        } catch (IOException failed) {
            LOG.log(Level.FINE, "Could not append to the Local History journal before a whole write", failed);
        }
    }

    /**
     * The index was just written whole ({@code indexBytes} long) with {@code snapshot}. When {@code mayDelete}
     * (this process is the primary, so no one else appends), the journal is folded in and deleted.
     */
    synchronized void compacted(HistoryStore.Snapshot snapshot, long publication, long indexBytes, boolean mayDelete) {
        this.onDisk = snapshot;
        this.onDiskPublication = Math.max(onDiskPublication, publication);
        this.indexBytes = indexBytes;
        if (!mayDelete || frozen) {
            return;
        }
        try {
            long size = Files.size(file);
            if (size < journalBytes) {
                return; // not the journal this process knows: leave it
            }
            Files.delete(file);
            journalBytes = 0;
        } catch (NoSuchFileException absent) {
            journalBytes = 0;
        } catch (IOException failed) {
            LOG.log(Level.FINE, "Could not delete the Local History journal " + file, failed);
        }
    }

    /** Appends one synced line; a tail a failed append left is cut off first. */
    private void append(List<Change> changes) throws IOException {
        byte[] line = lineWriter.writeValueAsBytes(new Batch(FORMAT, HistoryStore.SCHEMA_VERSION, changes));
        boolean created = !Files.exists(file);
        if (created) {
            ConfigWriter.createOwnerOnly(file);
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            long size = channel.size();
            if (size < journalBytes) {
                throw new IOException("The Local History journal is shorter than written");
            }
            if (size > journalBytes) {
                channel.truncate(journalBytes); // a line cut short: it was never reported durable
            }
            ByteBuffer out = ByteBuffer.allocate(line.length + 1);
            out.put(line).put((byte) '\n').flip();
            channel.position(journalBytes);
            while (out.hasRemaining()) {
                channel.write(out);
            }
            channel.force(true);
            journalBytes += line.length + 1;
        }
        if (created) {
            syncDirectory(file.getParent());
        }
    }

    private static void syncDirectory(@Nullable Path dir) {
        if (dir == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException notSupported) {
            // Windows cannot open a directory; there the entry is made durable with the file.
        }
    }

    // --- the change between two snapshots ---------------------------------------------------------------------

    /**
     * What changed from {@code from} to {@code to}, file by file (a project dropped is every row of it
     * removed), or {@code null} when the change is not about rows (the schema or the agreed limits changed). Projects whose copy is shared by both
     * snapshots, and lists that are the same object, are skipped without a look at their rows.
     */
    static @Nullable List<Change> diff(HistoryStore.Snapshot from, HistoryStore.Snapshot to) {
        if (from.schemaVersion() != to.schemaVersion()
                || !java.util.Objects.equals(from.acknowledgedLimits(), to.acknowledgedLimits())) {
            return null;
        }
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<HistoryRevision>>> project :
                from.byProject().entrySet()) {
            if (!to.byProject().containsKey(project.getKey())) { // dropped: every row of it goes
                project.getValue().forEach((file, rows) -> change(project.getKey(), file, rows, List.of(), changes));
            }
        }
        for (Map.Entry<String, Map<String, List<HistoryRevision>>> project :
                to.byProject().entrySet()) {
            Map<String, List<HistoryRevision>> before = from.byProject().getOrDefault(project.getKey(), Map.of());
            Map<String, List<HistoryRevision>> after = project.getValue();
            if (before == after) {
                continue;
            }
            for (Map.Entry<String, List<HistoryRevision>> file : after.entrySet()) {
                List<HistoryRevision> was = before.getOrDefault(file.getKey(), List.of());
                if (was != file.getValue()) {
                    change(project.getKey(), file.getKey(), was, file.getValue(), changes);
                }
            }
            for (Map.Entry<String, List<HistoryRevision>> file : before.entrySet()) {
                if (!after.containsKey(file.getKey())) {
                    change(project.getKey(), file.getKey(), file.getValue(), List.of(), changes);
                }
            }
        }
        return changes;
    }

    private static void change(
            String project, String file, List<HistoryRevision> was, List<HistoryRevision> now, List<Change> into) {
        Set<HistoryRevision> before = new HashSet<>(was);
        Set<HistoryRevision> after = new HashSet<>(now);
        List<HistoryRevision> added =
                now.stream().filter(r -> !before.contains(r)).toList();
        List<HistoryRevision> removed =
                was.stream().filter(r -> !after.contains(r)).toList();
        if (!added.isEmpty() || !removed.isEmpty()) {
            into.add(new Change(project, file, added, removed));
        }
    }

    private static int indexOf(byte[] bytes, byte b, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == b) {
                return i;
            }
        }
        return -1;
    }
}
