package com.editora.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Three-way merge of a config store's JSON tree: what this process last knew ({@code base}), what it holds
 * now ({@code mine}) and what another writer has put on disk since ({@code theirs}). Pure.
 *
 * <p>The result is {@code theirs} with this process's own changes applied, so a store is never rewritten with
 * data this process did not itself change:
 *
 * <ul>
 *   <li>a value this process left alone keeps whatever the other writer made of it (including "removed");
 *   <li>a value only this process changed takes this process's value;
 *   <li>objects changed on both sides are merged key by key;
 *   <li>arrays changed on both sides are merged entry by entry when the store says how its entries are told
 *       apart ({@link Keys}); an entry the other writer added or edited survives, an entry this process added,
 *       edited or removed is applied;
 *   <li>anything else changed on both sides (a scalar, an array with no entry identity) is a real conflict
 *       and takes this process's value, the one the user just set here.
 * </ul>
 */
final class StoreMerge {

    private StoreMerge() {}

    /** How the entries of the array at a given path are identified, or that the array is one opaque value. */
    @FunctionalInterface
    interface Keys {
        /**
         * The fields that identify an entry of the array at {@code path} (object keys from the root; an empty
         * list means "the entry's own value", for arrays of strings), or {@code null} when the array has no
         * entry identity and is merged as a whole.
         */
        List<String> entryKey(List<String> path);

        /** Every array is one opaque value. */
        Keys NONE = path -> null;
    }

    /** The entry identities of {@code schema}'s arrays; {@link Keys#NONE} for a store with none. */
    static Keys keysFor(ConfigSchema schema) {
        if (schema == null) {
            return Keys.NONE;
        }
        return switch (schema) {
            case PROJECTS ->
                path -> path.equals(List.of("projects"))
                        ? List.of("id")
                        : path.equals(List.of("openProjectIds")) ? List.of() : null;
            case BOOKMARKS, BREAKPOINTS -> path -> bucketed(path) ? List.of("line") : null;
            case NOTES -> path -> bucketed(path) ? List.of("id") : null;
            case HISTORY -> path -> bucketed(path) ? List.of("timestamp", "sha256", "reason") : null;
            case CONNECTIONS -> path -> path.equals(List.of("connections")) ? List.of("host", "port", "user") : null;
            case MACROS -> path -> path.equals(List.of("macros")) ? List.of("id") : null;
            case ABBREVIATIONS -> path -> path.equals(List.of("abbreviations")) ? List.of("abbreviation") : null;
            case RECENT -> path -> path.equals(List.of("files")) ? List.of() : null;
            case SEARCH_HISTORY -> path -> path.equals(List.of("queries")) ? List.of() : null;
            case AGENT_SESSIONS -> path -> path.equals(List.of("sessions")) ? List.of("sessionId") : null;
            default -> Keys.NONE;
        };
    }

    /** {@code byProject / <project key> / <file path>}: the per-file list of a bucketed store. */
    private static boolean bucketed(List<String> path) {
        return path.size() == 3 && "byProject".equals(path.get(0));
    }

    /** Merges three trees; any of them may be {@code null} (absent). Returns {@code null} for "absent". */
    static JsonNode merge(JsonNode base, JsonNode mine, JsonNode theirs, Keys keys) {
        return merge(base, mine, theirs, keys == null ? Keys.NONE : keys, new ArrayList<>());
    }

    private static JsonNode merge(JsonNode base, JsonNode mine, JsonNode theirs, Keys keys, List<String> path) {
        if (same(mine, base)) {
            return theirs; // this process did not touch it
        }
        if (same(theirs, base) || same(theirs, mine)) {
            return mine; // only this process changed it, or both made the same change
        }
        if (mine != null && theirs != null) {
            if (mine.isObject() && theirs.isObject()) {
                return mergeObjects(base != null && base.isObject() ? base : null, mine, theirs, keys, path);
            }
            if (mine.isArray() && theirs.isArray()) {
                List<String> entryKey = keys.entryKey(List.copyOf(path));
                if (entryKey != null) {
                    JsonNode merged = mergeArrays(
                            base != null && base.isArray() ? base : null, mine, theirs, entryKey, keys, path);
                    if (merged != null) {
                        return merged;
                    }
                }
            }
        }
        return mine; // a real conflict: the value set here wins (for a removal here, that is "absent")
    }

    private static JsonNode mergeObjects(JsonNode base, JsonNode mine, JsonNode theirs, Keys keys, List<String> path) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        Set<String> names = new LinkedHashSet<>();
        mine.fieldNames().forEachRemaining(names::add);
        theirs.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            path.add(name);
            JsonNode merged = merge(base == null ? null : base.get(name), mine.get(name), theirs.get(name), keys, path);
            path.remove(path.size() - 1);
            if (merged != null) {
                out.set(name, merged);
            }
        }
        return out;
    }

    /**
     * Entry-wise merge, or {@code null} when the entries cannot be told apart (a missing key field, or two
     * entries with the same key) and the array must be treated as one value.
     */
    private static JsonNode mergeArrays(
            JsonNode base, JsonNode mine, JsonNode theirs, List<String> entryKey, Keys keys, List<String> path) {
        Map<JsonNode, JsonNode> baseByKey = index(base, entryKey);
        Map<JsonNode, JsonNode> mineByKey = index(mine, entryKey);
        Map<JsonNode, JsonNode> theirsByKey = index(theirs, entryKey);
        if (baseByKey == null || mineByKey == null || theirsByKey == null) {
            return null;
        }
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        path.add("[]");
        // This process's entries first, in its order; then what only the other writer has.
        for (Map.Entry<JsonNode, JsonNode> entry : mineByKey.entrySet()) {
            JsonNode merged =
                    merge(baseByKey.get(entry.getKey()), entry.getValue(), theirsByKey.get(entry.getKey()), keys, path);
            if (merged != null) {
                out.add(merged);
            }
        }
        for (Map.Entry<JsonNode, JsonNode> entry : theirsByKey.entrySet()) {
            if (mineByKey.containsKey(entry.getKey())) {
                continue;
            }
            JsonNode merged = merge(baseByKey.get(entry.getKey()), null, entry.getValue(), keys, path);
            if (merged != null) {
                out.add(merged);
            }
        }
        path.remove(path.size() - 1);
        return out;
    }

    /** {@code array}'s entries by identity in order, or {@code null} when identities are missing or repeat. */
    private static Map<JsonNode, JsonNode> index(JsonNode array, List<String> entryKey) {
        Map<JsonNode, JsonNode> byKey = new LinkedHashMap<>();
        if (array == null) {
            return byKey;
        }
        for (JsonNode entry : array) {
            JsonNode key = keyOf(entry, entryKey);
            if (key == null || byKey.put(key, entry) != null) {
                return null;
            }
        }
        return byKey;
    }

    private static JsonNode keyOf(JsonNode entry, List<String> entryKey) {
        if (entryKey.isEmpty()) {
            return entry.isValueNode() && !entry.isNull() ? entry : null;
        }
        if (!entry.isObject()) {
            return null;
        }
        ArrayNode key = JsonNodeFactory.instance.arrayNode();
        for (String field : entryKey) {
            JsonNode value = entry.get(field);
            if (value == null || value.isNull() || value.isContainerNode()) {
                return null;
            }
            key.add(value);
        }
        return key;
    }

    private static boolean same(JsonNode a, JsonNode b) {
        return a == null ? b == null : a.equals(b);
    }
}
