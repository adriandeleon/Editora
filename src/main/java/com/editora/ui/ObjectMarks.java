package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Mark Object (IntelliJ's): a label given to one object of the program, shown beside it wherever its value appears —
 * the Variables tree, the watches, an evaluation's result, the hover popup, Debug History — so the same object is
 * recognized as it moves between variables, fields and collections. An object is told by what the adapter shows of
 * its identity: java-debug's object id ({@code ArrayList@12 size=3}), else an address ({@code <Box object at 0x7f…>}
 * in Python, a pointer in native code). A value with neither (js-debug's, a number, a string) cannot be marked. Marks
 * hold for one debug session: an id or address means nothing to the next.
 */
final class ObjectMarks {

    /** java-debug's {@code Type@id}: the id is the JVM's unique id of the object. */
    private static final Pattern JAVA_ID = Pattern.compile("(?<![\\w$])[\\w$.<>\\[\\]]*[\\w$\\]>]@(\\d+)\\b");

    /** An address: {@code 0x} and at least six hex digits (a small hex number is a value, not an address). */
    private static final Pattern ADDRESS = Pattern.compile("\\b0x([0-9a-fA-F]{6,})\\b");

    /** The longest label kept. */
    static final int MAX_LABEL = 40;

    /** Label by identity, in the order they were given. */
    private final Map<String, String> labels = new LinkedHashMap<>();

    /**
     * Pure: what tells the object {@code value} shows apart — {@code "id:12"} for a Java object, {@code "0x7f…"} for
     * an address (lower case) — or null when it shows neither. A string's text is never an identity.
     */
    static @Nullable String identity(@Nullable String value) {
        if (value == null || value.isEmpty() || value.startsWith("\"") || value.startsWith("'")) {
            return null;
        }
        Matcher objectId = JAVA_ID.matcher(value);
        if (objectId.find()) {
            return "id:" + objectId.group(1);
        }
        Matcher address = ADDRESS.matcher(value);
        return address.find() ? "0x" + address.group(1).toLowerCase(java.util.Locale.ROOT) : null;
    }

    /** {@code label} as kept: on one line, at most {@link #MAX_LABEL} characters; "" when blank. */
    static String tidy(@Nullable String label) {
        String one = label == null ? "" : label.replaceAll("\\s+", " ").strip();
        return one.length() > MAX_LABEL ? one.substring(0, MAX_LABEL).strip() : one;
    }

    /** Marks the object {@code value} shows with {@code label} (blank: unmarks it); false when it cannot be marked. */
    boolean mark(String value, String label) {
        String id = identity(value);
        if (id == null) {
            return false;
        }
        String tidy = tidy(label);
        if (tidy.isEmpty()) {
            labels.remove(id);
        } else {
            labels.put(id, tidy);
        }
        return true;
    }

    /** The label of the object {@code value} shows, or null when it has none. */
    @Nullable
    String labelOf(@Nullable String value) {
        if (labels.isEmpty()) {
            return null;
        }
        String id = identity(value);
        return id == null ? null : labels.get(id);
    }

    boolean isEmpty() {
        return labels.isEmpty();
    }

    /** Whether an object carries {@code label} (a breakpoint's instance filter names one by it). */
    boolean hasLabel(String label) {
        return labels.containsValue(tidy(label));
    }

    /** The labels given, in the order they were (the instance filter's choices). */
    java.util.List<String> labels() {
        return java.util.List.copyOf(new java.util.LinkedHashSet<>(labels.values()));
    }

    void clear() {
        labels.clear();
    }
}
