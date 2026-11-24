package com.editora.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * How the Local History index differs from {@code history/index.json} as it is on disk: the rows added and the
 * rows removed, per project and file, and the retention limits when they changed. Kept in its own small file
 * beside the index ({@link HistoryIndexFiles}) so that a recorded save rewrites this, not the whole index.
 *
 * <p>The index is {@code (index.json ∪ added) − removed}, rows compared whole. Applying a delta is idempotent
 * and does not depend on the exact index it was taken against: a row it adds that is already listed is not
 * listed twice, a row it removes that is not listed is ignored. So a delta left beside an index that was
 * rewritten since (a crash between the two writes, an older build that wrote only the index) still gives the
 * right rows: what the other writer added stays, what this process removed stays removed.
 *
 * <p>Immutable; pure.
 */
record HistoryDelta(
        String baseIndexSha256,
        Map<String, Map<String, List<HistoryRevision>>> added,
        Map<String, Map<String, List<HistoryRevision>>> removed,
        HistoryStore.@Nullable Limits acknowledgedLimits) {

    /** Version of the delta file's own format. */
    static final int SCHEMA_VERSION = 1;

    private static final TypeReference<LinkedHashMap<String, LinkedHashMap<String, List<HistoryRevision>>>> ROWS =
            new TypeReference<>() {};

    static HistoryDelta empty(String baseIndexSha256) {
        return new HistoryDelta(baseIndexSha256, Map.of(), Map.of(), null);
    }

    boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && acknowledgedLimits == null;
    }

    /**
     * What turns {@code base} into {@code mine}. Costs the projects that changed between the two snapshots, not
     * the whole index: a project whose snapshot copy is shared ({@link HistoryStore#snapshot}) is skipped, and
     * within a project that changed, so is every file whose (immutable) list is the same object.
     */
    static HistoryDelta diff(HistoryStore.Snapshot base, HistoryStore.Snapshot mine, String baseIndexSha256) {
        Map<String, Map<String, List<HistoryRevision>>> added = new LinkedHashMap<>();
        Map<String, Map<String, List<HistoryRevision>>> removed = new LinkedHashMap<>();
        Set<String> projects = new LinkedHashSet<>(mine.byProject().keySet());
        projects.addAll(base.byProject().keySet());
        for (String project : projects) {
            Map<String, List<HistoryRevision>> was = base.byProject().getOrDefault(project, Map.of());
            Map<String, List<HistoryRevision>> now = mine.byProject().getOrDefault(project, Map.of());
            if (was == now) {
                continue;
            }
            Set<String> files = new LinkedHashSet<>(now.keySet());
            files.addAll(was.keySet());
            for (String file : files) {
                List<HistoryRevision> before = was.getOrDefault(file, List.of());
                List<HistoryRevision> after = now.getOrDefault(file, List.of());
                if (before == after) {
                    continue;
                }
                List<HistoryRevision> plus = minus(after, before);
                List<HistoryRevision> minus = minus(before, after);
                if (!plus.isEmpty()) {
                    added.computeIfAbsent(project, k -> new LinkedHashMap<>()).put(file, List.copyOf(plus));
                }
                if (!minus.isEmpty()) {
                    removed.computeIfAbsent(project, k -> new LinkedHashMap<>()).put(file, List.copyOf(minus));
                }
            }
        }
        HistoryStore.Limits limits =
                Objects.equals(base.acknowledgedLimits(), mine.acknowledgedLimits()) ? null : mine.acknowledgedLimits();
        return new HistoryDelta(baseIndexSha256, freeze(added), freeze(removed), limits);
    }

    /**
     * Whether {@link #diff} cannot say how {@code base} became {@code mine}: the limits were dropped, which a
     * delta has no way to write. The index is then written whole.
     */
    static boolean inexpressible(HistoryStore.Snapshot base, HistoryStore.Snapshot mine) {
        return base.acknowledgedLimits() != null && mine.acknowledgedLimits() == null;
    }

    /** {@code base} with this delta applied: a new snapshot sharing every project the delta does not touch. */
    HistoryStore.Snapshot applyTo(HistoryStore.Snapshot base) {
        if (isEmpty()) {
            return base;
        }
        Map<String, Map<String, List<HistoryRevision>>> projects = new LinkedHashMap<>(base.byProject());
        Set<String> touched = new LinkedHashSet<>(removed.keySet());
        touched.addAll(added.keySet());
        for (String project : touched) {
            Map<String, List<HistoryRevision>> files = new LinkedHashMap<>(projects.getOrDefault(project, Map.of()));
            Map<String, List<HistoryRevision>> plus = added.getOrDefault(project, Map.of());
            Map<String, List<HistoryRevision>> minus = removed.getOrDefault(project, Map.of());
            Set<String> names = new LinkedHashSet<>(minus.keySet());
            names.addAll(plus.keySet());
            for (String file : names) {
                List<HistoryRevision> present = files.get(file);
                List<HistoryRevision> rows = applyToFile(
                        present == null ? List.of() : present,
                        plus.getOrDefault(file, List.of()),
                        minus.getOrDefault(file, List.of()));
                if (rows.isEmpty()) {
                    if (present != null && !present.isEmpty()) {
                        files.remove(file); // every row of it was removed
                    }
                } else {
                    files.put(file, rows);
                }
            }
            if (files.isEmpty() && !projects.containsKey(project)) {
                continue;
            }
            projects.put(project, Collections.unmodifiableMap(files));
        }
        return new HistoryStore.Snapshot(
                base.schemaVersion(),
                Collections.unmodifiableMap(projects),
                acknowledgedLimits != null ? acknowledgedLimits : base.acknowledgedLimits());
    }

    /** One file's rows: {@code present} without {@code minus}, then whatever of {@code plus} is not listed yet. */
    static List<HistoryRevision> applyToFile(
            List<HistoryRevision> present, List<HistoryRevision> plus, List<HistoryRevision> minus) {
        List<HistoryRevision> kept = minus.isEmpty() ? present : minus(present, minus);
        List<HistoryRevision> fresh = minus(plus, kept);
        if (fresh.isEmpty()) {
            return kept == present ? present : List.copyOf(kept);
        }
        List<HistoryRevision> rows = new ArrayList<>(kept.size() + fresh.size());
        rows.addAll(kept);
        rows.addAll(fresh);
        rows.sort(Comparator.comparingLong(HistoryRevision::timestamp).reversed()); // stable: ties keep order
        return List.copyOf(rows);
    }

    /** {@code from} without one occurrence of each row of {@code take} (rows compared whole). */
    static List<HistoryRevision> minus(List<HistoryRevision> from, List<HistoryRevision> take) {
        if (take.isEmpty()) {
            return from;
        }
        Map<HistoryRevision, Integer> counts = new HashMap<>();
        take.forEach(row -> counts.merge(row, 1, Integer::sum));
        List<HistoryRevision> out = new ArrayList<>(from.size());
        for (HistoryRevision row : from) {
            Integer left = counts.get(row);
            if (left != null && left > 0) {
                counts.put(row, left - 1);
            } else {
                out.add(row);
            }
        }
        return out;
    }

    // --- the file ------------------------------------------------------------------------------------------

    /** The delta as written to its file. */
    ObjectNode toTree(ObjectMapper json) {
        ObjectNode root = json.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("baseIndexSha256", baseIndexSha256);
        root.putObject("added").set("byProject", json.valueToTree(added));
        root.putObject("removed").set("byProject", json.valueToTree(removed));
        if (acknowledgedLimits != null) {
            root.set("acknowledgedLimits", json.valueToTree(acknowledgedLimits));
        }
        return root;
    }

    byte[] toBytes(ObjectMapper json) throws IOException {
        return json.writeValueAsBytes(toTree(json));
    }

    /** A delta file written by a newer Editora, which this build must neither read nor replace. */
    static final class NewerDeltaException extends IOException {
        NewerDeltaException(int version) {
            super("the Local History delta was written by a newer Editora (schema " + version + ")");
        }
    }

    /**
     * Reads a delta file's tree.
     *
     * @throws NewerDeltaException when a newer build wrote it
     * @throws IOException when it is not a delta (damaged, or not one this build can read)
     */
    static HistoryDelta fromTree(JsonNode tree, ObjectMapper json) throws IOException {
        if (tree == null || !tree.isObject()) {
            throw new IOException("not a JSON object");
        }
        int version = tree.path("schemaVersion").asInt(0);
        if (version > SCHEMA_VERSION) {
            throw new NewerDeltaException(version);
        }
        if (version < 1 || !tree.path("baseIndexSha256").isTextual()) {
            throw new IOException("not a Local History delta");
        }
        try {
            HistoryStore.Limits limits = tree.hasNonNull("acknowledgedLimits")
                    ? json.treeToValue(tree.get("acknowledgedLimits"), HistoryStore.Limits.class)
                    : null;
            return new HistoryDelta(
                    tree.get("baseIndexSha256").asText(),
                    rows(tree.path("added").path("byProject"), json),
                    rows(tree.path("removed").path("byProject"), json),
                    limits);
        } catch (IllegalArgumentException | IOException bad) {
            throw new IOException("not a Local History delta: " + bad.getMessage(), bad);
        }
    }

    static HistoryDelta fromBytes(byte[] bytes, ObjectMapper json) throws IOException {
        return fromTree(json.readTree(com.editora.config.migration.ConfigMigrations.decodeText(bytes)), json);
    }

    private static Map<String, Map<String, List<HistoryRevision>>> rows(JsonNode node, ObjectMapper json)
            throws IOException {
        if (node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IOException("rows are not an object");
        }
        LinkedHashMap<String, LinkedHashMap<String, List<HistoryRevision>>> read = json.convertValue(node, ROWS);
        Map<String, Map<String, List<HistoryRevision>>> out = new LinkedHashMap<>();
        read.forEach((project, files) -> {
            if (project != null && files != null) {
                Map<String, List<HistoryRevision>> kept = new LinkedHashMap<>();
                files.forEach((file, list) -> {
                    if (file != null && list != null) {
                        List<HistoryRevision> rows =
                                list.stream().filter(Objects::nonNull).toList();
                        if (!rows.isEmpty()) {
                            kept.put(file, rows);
                        }
                    }
                });
                if (!kept.isEmpty()) {
                    out.put(project, kept);
                }
            }
        });
        return freeze(out);
    }

    private static Map<String, Map<String, List<HistoryRevision>>> freeze(
            Map<String, Map<String, List<HistoryRevision>>> rows) {
        Map<String, Map<String, List<HistoryRevision>>> out = new LinkedHashMap<>();
        rows.forEach((project, files) -> out.put(project, Collections.unmodifiableMap(new LinkedHashMap<>(files))));
        return Collections.unmodifiableMap(out);
    }

    /**
     * How a three-way merge of two writers' delta files ({@link StoreMerge}) tells rows apart: as in the index
     * itself, by time, body and reason, with repeats told apart by their order — under {@code added} and under
     * {@code removed}.
     */
    static final StoreMerge.Keys MERGE_KEYS = new StoreMerge.Keys() {
        @Override
        public @Nullable List<String> entryKey(List<String> path) {
            return rowList(path) ? List.of("timestamp", "sha256", "reason") : null;
        }

        @Override
        public boolean repeatsAllowed(List<String> path) {
            return rowList(path);
        }

        private boolean rowList(List<String> path) {
            return path.size() == 4
                    && ("added".equals(path.get(0)) || "removed".equals(path.get(0)))
                    && "byProject".equals(path.get(1));
        }
    };
}
