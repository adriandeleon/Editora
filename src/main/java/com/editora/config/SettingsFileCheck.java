package com.editora.config;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What {@code settings.json}, as written, says that the loaded {@link Settings} does not use: a key no setting
 * has (dropped at the next save), a value of the wrong type (its default is used), or a value the setting
 * changed (pulled into its range, a choice it does not know replaced). A hand edit that "did nothing" can then
 * be told why. Pure.
 */
public final class SettingsFileCheck {

    /** One top-level value not used as written. {@code written}/{@code used} are JSON text; null when absent. */
    public record Issue(String key, Kind kind, String written, String used) {}

    public enum Kind {
        /** No setting has this name; it is ignored and dropped at the next save. */
        UNKNOWN,
        /** The value has the wrong type; the default is used. */
        WRONG_TYPE,
        /** The value was read but changed: clamped to its range, or replaced by a known choice. */
        CHANGED
    }

    /** Keys the file may hold that are bookkeeping, not settings someone edits. */
    private static final List<String> IGNORED = List.of("schemaVersion");

    private SettingsFileCheck() {}

    /**
     * Compares the file's tree with what the loaded settings serialize to.
     *
     * @param written the file as parsed (an object node), or null when it could not be read
     * @param used the loaded settings, serialized back to a tree
     * @param skipped the keys the load skipped for having the wrong type
     */
    public static List<Issue> issues(JsonNode written, JsonNode used, List<String> skipped) {
        List<Issue> out = new ArrayList<>();
        if (written == null || !written.isObject() || used == null) {
            return out;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = written.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            if (IGNORED.contains(key)) {
                continue;
            }
            JsonNode now = used.get(key);
            if (now == null) {
                out.add(new Issue(key, Kind.UNKNOWN, e.getValue().toString(), null));
            } else if (skipped.contains(key)) {
                out.add(new Issue(key, Kind.WRONG_TYPE, e.getValue().toString(), now.toString()));
            } else if (!sameValue(e.getValue(), now)) {
                out.add(new Issue(key, Kind.CHANGED, e.getValue().toString(), now.toString()));
            }
        }
        return out;
    }

    /**
     * Whether {@code used} holds what {@code written} says: numbers by value (14 = 14.0), an object by the fields it
     * was written with (fields the file leaves out take their defaults), an array element by element.
     */
    public static boolean sameValue(JsonNode written, JsonNode used) {
        if (written.isNumber() && used.isNumber()) {
            return written.decimalValue().compareTo(used.decimalValue()) == 0;
        }
        if (written.isObject() && used.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = written.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode u = used.get(e.getKey());
                if (u == null || !sameValue(e.getValue(), u)) {
                    return false;
                }
            }
            return true;
        }
        if (written.isArray() && used.isArray()) {
            if (written.size() != used.size()) {
                return false;
            }
            for (int i = 0; i < written.size(); i++) {
                if (!sameValue(written.get(i), used.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return written.equals(used);
    }
}
