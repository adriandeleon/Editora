package com.editora.history;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.editora.config.HistoryRevision;

/**
 * Pure: what happens to the Local File History index when a file changes its path. The index is keyed by
 * path, so a file renamed or moved inside Editora would otherwise leave its revisions under a name that no
 * longer exists and start again with none.
 *
 * <p>Nothing here ever drops a revision — entries are re-keyed, merged or copied — so the set of referenced
 * blobs is the same before and after, whenever a snapshot of the index is taken for blob collection.
 */
public final class HistoryMoves {

    private HistoryMoves() {}

    /**
     * Moves the history of {@code oldKey}, and of every file below it ({@code oldKey} followed by {@code
     * separator}: a renamed or moved folder), to the same place under {@code newKey}, in every project's
     * bucket. History already recorded at the new path — the name was used before — is kept and merged.
     *
     * @return whether anything moved
     */
    public static boolean rename(
            Map<String, Map<String, List<HistoryRevision>>> byProject, String oldKey, String newKey, String separator) {
        if (byProject == null || oldKey == null || newKey == null || oldKey.equals(newKey)) {
            return false;
        }
        boolean any = false;
        for (Map<String, List<HistoryRevision>> bucket : byProject.values()) {
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            Map<String, List<HistoryRevision>> moved = new LinkedHashMap<>();
            for (var it = bucket.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, List<HistoryRevision>> entry = it.next();
                String renamed = renamed(entry.getKey(), oldKey, newKey, separator);
                if (renamed != null) {
                    moved.put(renamed, entry.getValue());
                    it.remove();
                }
            }
            for (Map.Entry<String, List<HistoryRevision>> entry : moved.entrySet()) {
                bucket.put(entry.getKey(), merged(entry.getKey(), entry.getValue(), bucket.get(entry.getKey())));
                any = true;
            }
        }
        return any;
    }

    /**
     * Gives {@code newKey} the revisions of {@code oldKey} as well (Save As: the new file's past is the
     * file it was saved from, which keeps its own). The bodies are shared — they are stored by content hash.
     *
     * @return whether {@code newKey}'s history changed
     */
    public static boolean copy(Map<String, List<HistoryRevision>> bucket, String oldKey, String newKey) {
        List<HistoryRevision> source =
                bucket == null || oldKey == null || newKey == null || oldKey.equals(newKey) ? null : bucket.get(oldKey);
        if (source == null || source.isEmpty()) {
            return false;
        }
        List<HistoryRevision> existing = bucket.get(newKey);
        List<HistoryRevision> merged = merged(newKey, source, existing);
        if (existing != null && merged.size() == existing.size()) {
            return false;
        }
        bucket.put(newKey, merged);
        return true;
    }

    /** {@code key} as it reads after the rename, or null when it is neither {@code oldKey} nor below it. */
    public static String renamed(String key, String oldKey, String newKey, String separator) {
        if (key == null || oldKey == null || newKey == null) {
            return null;
        }
        if (key.equals(oldKey)) {
            return newKey;
        }
        boolean below = separator != null
                && !separator.isEmpty()
                && key.length() > oldKey.length() + separator.length()
                && key.startsWith(oldKey)
                && key.startsWith(separator, oldKey.length());
        return below ? newKey + key.substring(oldKey.length()) : null;
    }

    /** {@code revision} as a revision of the file at {@code path}. */
    public static HistoryRevision at(String path, HistoryRevision revision) {
        return path.equals(revision.path()) ? revision : revision.withPath(path);
    }

    /** Both lists as one history of {@code path}: newest first, a revision present in both listed once. */
    private static List<HistoryRevision> merged(
            String path, List<HistoryRevision> arriving, List<HistoryRevision> present) {
        Set<HistoryRevision> all = new LinkedHashSet<>();
        for (HistoryRevision revision : arriving) {
            all.add(at(path, revision));
        }
        if (present != null) {
            for (HistoryRevision revision : present) {
                all.add(at(path, revision));
            }
        }
        List<HistoryRevision> out = new ArrayList<>(all);
        out.sort(Comparator.comparingLong(HistoryRevision::timestamp).reversed()); // stable: ties keep their order
        return out;
    }
}
