package com.editora.config;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The Local File History index, serialized as JSON to {@code history/index.json} in the config dir.
 * Modeled on {@link BreakpointStore}: kept in its own file but <em>scoped per project</em> — revisions
 * are bucketed by project key ({@code ""} = the global session / no project, otherwise the project id),
 * so switching projects shows only that project's file history.
 *
 * <p>This holds only revision <em>metadata</em> ({@link HistoryRevision}); the gzip'd revision bodies
 * live in {@code history/blobs/}, content-addressed by sha. A plain Jackson POJO; the
 * {@code com.editora.config} package is already opened to jackson.databind in {@code module-info.java}.
 *
 * <p><b>The maps this store hands out keep its books.</b> {@link #getByProject()} and {@link #bucket} are
 * ordinary {@code Map}s to their callers, but every change made through them — a put, a remove, an iterator's
 * remove, an entry's {@code setValue} — is seen here, which is what lets a save cost what changed rather than
 * the size of the history:
 *
 * <ul>
 *   <li>a file's revision list is stored as an <em>immutable</em> copy (null rows dropped). A list is replaced,
 *       never edited in place: {@code bucket.get(path).add(…)} throws instead of changing the index behind the
 *       store's back;
 *   <li>the body hashes the index refers to are counted as rows come and go ({@link HistoryHashLedger});
 *   <li>{@link #snapshot()} — the immutable copy handed to the writer thread — reuses the copy of every
 *       project that has not changed since the last one.
 * </ul>
 *
 * The index belongs to the FX thread, as before; only the ledger is read from elsewhere.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"schemaVersion", "byProject", "acknowledgedLimits"})
public class HistoryStore {

    /**
     * Current on-disk schema version of {@code history/index.json}. (v1→v2: added the per-revision label;
     * v2→v3: a pre-delete revision records its file's charset, byte-order mark and line ending; v3→v4: the
     * retention limits the user last agreed to, {@link #getAcknowledgedLimits()}.)
     */
    public static final int SCHEMA_VERSION = 4;

    /**
     * The retention limits the user has agreed to (see {@code HistoryService.effectivePolicy}): the three
     * settings as they were when a limit was last adopted or confirmed. Stored with the index because it is a
     * statement about the index — "nothing here was deleted by a limit stricter than these".
     */
    public record Limits(int maxPerFile, long maxAgeMillis, long maxTotalBytesPerProject) {}

    private int schemaVersion = SCHEMA_VERSION;

    private Limits acknowledgedLimits;

    private final HistoryHashLedger ledger = new HistoryHashLedger();

    /** Project key ({@code ""} = no project) -> (absolute file path -> revisions, newest-first). */
    private final Projects byProject = new Projects();

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    /** The limits last agreed to, or {@code null} when none were recorded yet (an index from before v4). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Limits getAcknowledgedLimits() {
        return acknowledgedLimits;
    }

    public void setAcknowledgedLimits(Limits acknowledgedLimits) {
        this.acknowledgedLimits = acknowledgedLimits;
    }

    public Map<String, Map<String, List<HistoryRevision>>> getByProject() {
        return byProject;
    }

    public void setByProject(Map<String, Map<String, List<HistoryRevision>>> replacement) {
        if (replacement == byProject) {
            return;
        }
        byProject.clear();
        if (replacement != null) {
            replacement.forEach((project, files) -> {
                if (files != null) {
                    byProject.put(project == null ? "" : project, files);
                }
            });
        }
    }

    /** The history map for one project key, creating an empty bucket if absent. */
    public Map<String, List<HistoryRevision>> bucket(String projectKey) {
        return byProject.computeIfAbsent(projectKey == null ? "" : projectKey, k -> new LinkedHashMap<>());
    }

    /** The body hashes this index refers to, kept current as it changes. */
    @JsonIgnore
    HistoryHashLedger ledger() {
        return ledger;
    }

    /**
     * Puts every file's list in newest-first order (by timestamp, ties keeping their order) and reports
     * whether any was not. Everything that reads a list — the unchanged-content check, the per-file cap, the
     * sweep — takes row 0 for the newest; a list merged from two writers on disk is this process's rows
     * followed by the other's, whatever their times.
     */
    boolean sortNewestFirst() {
        boolean changed = false;
        Comparator<HistoryRevision> newestFirst =
                Comparator.comparingLong(HistoryRevision::timestamp).reversed();
        for (Map<String, List<HistoryRevision>> bucket : byProject.values()) {
            for (Map.Entry<String, List<HistoryRevision>> file : bucket.entrySet()) {
                List<HistoryRevision> list = file.getValue();
                boolean ordered = true;
                for (int i = 1; i < list.size() && ordered; i++) {
                    ordered = list.get(i - 1).timestamp() >= list.get(i).timestamp();
                }
                if (!ordered) {
                    List<HistoryRevision> sorted = new ArrayList<>(list);
                    sorted.sort(newestFirst); // stable
                    file.setValue(sorted);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * This index as it is now, for another thread to serialize: immutable, and sharing with the previous
     * snapshot the copy of every project that has not changed since.
     */
    Snapshot snapshot() {
        Map<String, Map<String, List<HistoryRevision>>> projects = new LinkedHashMap<>();
        byProject.map.forEach((project, bucket) -> projects.put(project, ((Bucket) bucket).frozen()));
        return new Snapshot(schemaVersion, Collections.unmodifiableMap(projects), acknowledgedLimits);
    }

    /** What is written to {@code index.json}: the same properties, in the same order, as this class. */
    @JsonPropertyOrder({"schemaVersion", "byProject", "acknowledgedLimits"})
    record Snapshot(
            int schemaVersion,
            Map<String, Map<String, List<HistoryRevision>>> byProject,
            @JsonInclude(JsonInclude.Include.NON_NULL) Limits acknowledgedLimits) {}

    // --- the maps ------------------------------------------------------------------------------------------

    /**
     * A {@code Map<String, V>} over a {@link LinkedHashMap} in which every way of changing it goes through
     * {@link #adopt}, {@link #entered} and {@link #left}. {@code AbstractMap} routes {@code keySet()},
     * {@code values()} and the default methods through {@link #entrySet()}, {@link #put} and {@link #remove},
     * so those are the only doors.
     */
    private abstract static class Tracked<V> extends AbstractMap<String, V> {
        final LinkedHashMap<String, V> map = new LinkedHashMap<>();

        /** The value as it is stored (never null). */
        abstract V adopt(String key, V value);

        abstract void entered(V value);

        abstract void left(V value);

        void changed() {}

        @Override
        public V put(String key, V value) {
            V stored = adopt(key, java.util.Objects.requireNonNull(value, "value"));
            V old = map.put(key, stored);
            if (old != stored) {
                entered(stored);
                if (old != null) {
                    left(old);
                }
                changed();
            }
            return old;
        }

        @Override
        public V get(Object key) {
            return map.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return map.containsKey(key);
        }

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public V remove(Object key) {
            V old = map.remove(key);
            if (old != null) {
                left(old);
                changed();
            }
            return old;
        }

        @Override
        public void clear() {
            if (map.isEmpty()) {
                return;
            }
            List<V> gone = new ArrayList<>(map.values());
            map.clear();
            gone.forEach(this::left);
            changed();
        }

        /** As the default, but answers the value that was <em>stored</em>, not the one the function made. */
        @Override
        public V computeIfAbsent(String key, Function<? super String, ? extends V> mapping) {
            V present = map.get(key);
            if (present != null) {
                return present;
            }
            V made = mapping.apply(key);
            if (made == null) {
                return null;
            }
            put(key, made);
            return map.get(key);
        }

        @Override
        public Set<Map.Entry<String, V>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public int size() {
                    return map.size();
                }

                @Override
                public Iterator<Map.Entry<String, V>> iterator() {
                    Iterator<Map.Entry<String, V>> inner = map.entrySet().iterator();
                    return new Iterator<>() {
                        private Map.Entry<String, V> last;

                        @Override
                        public boolean hasNext() {
                            return inner.hasNext();
                        }

                        @Override
                        public Map.Entry<String, V> next() {
                            last = inner.next();
                            Map.Entry<String, V> entry = last;
                            return new Map.Entry<>() {
                                @Override
                                public String getKey() {
                                    return entry.getKey();
                                }

                                @Override
                                public V getValue() {
                                    return entry.getValue();
                                }

                                @Override
                                public V setValue(V value) {
                                    V stored = adopt(entry.getKey(), java.util.Objects.requireNonNull(value, "value"));
                                    V old = entry.setValue(stored);
                                    if (old != stored) {
                                        entered(stored);
                                        left(old);
                                        changed();
                                    }
                                    return old;
                                }

                                @Override
                                public boolean equals(Object other) {
                                    return entry.equals(other);
                                }

                                @Override
                                public int hashCode() {
                                    return entry.hashCode();
                                }

                                @Override
                                public String toString() {
                                    return entry.toString();
                                }
                            };
                        }

                        @Override
                        public void remove() {
                            V gone = last.getValue();
                            inner.remove();
                            left(gone);
                            changed();
                        }
                    };
                }
            };
        }
    }

    /** Project key → that project's {@link Bucket}. */
    private final class Projects extends Tracked<Map<String, List<HistoryRevision>>> {

        @Override
        Map<String, List<HistoryRevision>> adopt(String key, Map<String, List<HistoryRevision>> value) {
            if (value == map.get(key)) {
                return value; // put(k, get(k)): already this store's bucket for that project
            }
            Bucket bucket = new Bucket();
            value.forEach((file, revisions) -> {
                if (file != null && revisions != null) {
                    bucket.put(file, revisions);
                }
            });
            return bucket;
        }

        @Override
        void entered(Map<String, List<HistoryRevision>> value) {
            ((Bucket) value).attach();
        }

        @Override
        void left(Map<String, List<HistoryRevision>> value) {
            ((Bucket) value).detach();
        }
    }

    /** File path → its revisions, newest-first, as an immutable list. */
    private final class Bucket extends Tracked<List<HistoryRevision>> {
        /** Whether this bucket is in the index: only then do its rows count in the ledger. */
        private boolean attached;
        /** The copy the last {@link #snapshot()} took, until this bucket changes again. */
        private Map<String, List<HistoryRevision>> frozen;

        @Override
        List<HistoryRevision> adopt(String key, List<HistoryRevision> value) {
            try {
                return List.copyOf(value); // itself, when it already is an immutable copy
            } catch (NullPointerException nullRow) {
                return value.stream().filter(java.util.Objects::nonNull).toList();
            }
        }

        @Override
        void entered(List<HistoryRevision> value) {
            if (attached) {
                value.forEach(r -> ledger.added(r.sha256()));
            }
        }

        @Override
        void left(List<HistoryRevision> value) {
            if (attached) {
                value.forEach(r -> ledger.removed(r.sha256()));
            }
        }

        @Override
        void changed() {
            frozen = null;
        }

        void attach() {
            if (!attached) {
                attached = true;
                map.values().forEach(this::entered);
            }
        }

        void detach() {
            if (attached) {
                map.values().forEach(this::left);
                attached = false;
            }
        }

        Map<String, List<HistoryRevision>> frozen() {
            if (frozen == null) {
                frozen = Collections.unmodifiableMap(new LinkedHashMap<>(map));
            }
            return frozen;
        }
    }
}
