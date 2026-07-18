package com.editora.toolbar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure list operations over a toolbar layout — a list of tokens, each either a {@link
 * ToolbarCatalog#SEPARATOR} or a catalog item id. All methods return a fresh list and never mutate their
 * input, so they compose cleanly and are trivially unit-testable (no JavaFX).
 */
public final class ToolbarLayout {

    private ToolbarLayout() {}

    /**
     * Cleans a raw token list into a valid layout: drops unknown item ids (a removed/renamed command),
     * deduplicates real items (a widget is a single node — it can appear at most once), and collapses
     * separators (no leading, trailing, or consecutive separators). The relative order of surviving items is
     * preserved.
     */
    public static List<String> sanitize(List<String> tokens) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (tokens != null) {
            for (String t : tokens) {
                if (t == null) {
                    continue;
                }
                if (ToolbarCatalog.SEPARATOR.equals(t)) {
                    // Only keep a separator when there's a preceding visible item and the last token isn't
                    // already a separator (collapses leading + consecutive separators).
                    if (!out.isEmpty() && !ToolbarCatalog.SEPARATOR.equals(out.get(out.size() - 1))) {
                        out.add(ToolbarCatalog.SEPARATOR);
                    }
                } else if (ToolbarCatalog.isKnownId(t) && seen.add(t)) {
                    out.add(t);
                }
            }
        }
        // Drop a trailing separator.
        if (!out.isEmpty() && ToolbarCatalog.SEPARATOR.equals(out.get(out.size() - 1))) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /** Moves the token at {@code from} to {@code to} (both clamped), returning a new list. */
    public static List<String> move(List<String> tokens, int from, int to) {
        List<String> out = new ArrayList<>(tokens);
        if (from < 0 || from >= out.size()) {
            return out;
        }
        String t = out.remove(from);
        int dest = Math.max(0, Math.min(to, out.size()));
        out.add(dest, t);
        return out;
    }

    /** Removes the token at {@code index} (ignored if out of range), returning a new list. */
    public static List<String> remove(List<String> tokens, int index) {
        List<String> out = new ArrayList<>(tokens);
        if (index >= 0 && index < out.size()) {
            out.remove(index);
        }
        return out;
    }

    /** Inserts {@code token} at {@code index} (clamped to the ends), returning a new list. */
    public static List<String> insertAt(List<String> tokens, int index, String token) {
        List<String> out = new ArrayList<>(tokens);
        int dest = Math.max(0, Math.min(index, out.size()));
        out.add(dest, token);
        return out;
    }
}
