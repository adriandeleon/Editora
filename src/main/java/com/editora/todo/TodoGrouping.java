package com.editora.todo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure grouping of TODO matches for the tool window: turns the flat set of {@code (file, match)} entries into
 * ordered {@link Group}s by {@link GroupBy} — file, priority, tag, or keyword — the IntelliJ "Comment
 * Manager" grouping. No JavaFX, so it is unit-tested. The panel renders the groups (counts, "no priority" /
 * "no tag" labels, icons); this class only decides membership and order.
 */
public final class TodoGrouping {

    /** The grouping dimensions offered in the tool window. */
    public enum GroupBy {
        FILE,
        PRIORITY,
        TAG,
        KEYWORD
    }

    /** One {@code (file, match)} row. */
    public record Entry(Path file, TodoMatch match) {}

    /**
     * A group: its raw {@code label} ({@code ""} for the catch-all "no priority" / "no tag" bucket, which the
     * panel localizes), the {@code file} it belongs to (non-null only under {@link GroupBy#FILE}, so the
     * header can show the file icon + navigate), and the ordered {@code entries}.
     */
    public record Group(String label, Path file, List<Entry> entries) {}

    /** Keyword header order (defaults first), then anything else alphabetically. */
    private static final List<String> KEYWORD_ORDER = List.of("TODO", "FIXME", "HACK", "NOTE", "XXX", "DONE");

    private TodoGrouping() {}

    /**
     * Groups {@code entries} by {@code by}. Under {@link GroupBy#FILE} the {@code activeFile}'s group sorts
     * first (IDE convention) and the rest keep input order; the other dimensions sort their groups by a
     * dimension-specific order (priority by urgency, tag/keyword alphabetically with the catch-all bucket
     * last) and their rows by file name then line.
     */
    public static List<Group> group(List<Entry> entries, GroupBy by, Path activeFile) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return switch (by) {
            case FILE -> byFile(entries, activeFile);
            case PRIORITY -> byKey(entries, TodoGrouping::priorityLabel, priorityOrder());
            case TAG ->
                byKey(
                        entries,
                        e -> e.match().parsed() == null
                                ? ""
                                : nz(e.match().parsed().tag()),
                        null);
            case KEYWORD ->
                byKey(
                        entries,
                        e -> e.match().patternName() == null ? "" : e.match().patternName(),
                        keywordOrder());
        };
    }

    private static List<Group> byFile(List<Entry> entries, Path activeFile) {
        Map<Path, List<Entry>> byPath = new LinkedHashMap<>();
        for (Entry e : entries) {
            byPath.computeIfAbsent(e.file(), k -> new ArrayList<>()).add(e);
        }
        List<Group> groups = new ArrayList<>();
        for (var en : byPath.entrySet()) {
            Path p = en.getKey();
            String label =
                    p.getFileName() == null ? p.toString() : p.getFileName().toString();
            groups.add(new Group(label, p, en.getValue()));
        }
        // Active file first, otherwise stable (a stable sort preserves the walked order).
        groups.sort(Comparator.comparingInt(g -> samePath(g.file(), activeFile) ? 0 : 1));
        return groups;
    }

    /**
     * Groups by a string key, ordering groups by {@code rank} (or, when {@code rank} is null, alphabetically
     * with the empty-key catch-all bucket last) and rows within a group by file name then line.
     */
    private static List<Group> byKey(
            List<Entry> entries, java.util.function.Function<Entry, String> keyOf, Comparator<String> rank) {
        Map<String, List<Entry>> byKey = new LinkedHashMap<>();
        for (Entry e : entries) {
            byKey.computeIfAbsent(keyOf.apply(e), k -> new ArrayList<>()).add(e);
        }
        Comparator<String> order = rank != null
                ? rank
                : Comparator.comparingInt((String k) -> k.isEmpty() ? 1 : 0)
                        .thenComparing(k -> k.toLowerCase(java.util.Locale.ROOT));
        List<String> keys = new ArrayList<>(byKey.keySet());
        keys.sort(order);
        List<Group> groups = new ArrayList<>();
        for (String k : keys) {
            List<Entry> rows = byKey.get(k);
            rows.sort(Comparator.comparing((Entry e) -> fileName(e.file()))
                    .thenComparingInt(e -> e.match().line()));
            groups.add(new Group(k, null, rows));
        }
        return groups;
    }

    /** The priority-group key: the priority word, or {@code ""} when the match has none. */
    private static String priorityLabel(Entry e) {
        TodoComment p = e.match().parsed();
        return p == null ? "" : nz(p.priority());
    }

    /** Orders priority groups by urgency (critical → low), with the no-priority bucket last. */
    private static Comparator<String> priorityOrder() {
        return Comparator.comparingInt(TodoGrouping::priorityIndex);
    }

    private static int priorityIndex(String priority) {
        int i = priority == null || priority.isEmpty() ? -1 : TodoComment.PRIORITY_ORDER.indexOf(priority);
        return i < 0 ? TodoComment.PRIORITY_ORDER.size() : i;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String fileName(Path p) {
        return p == null
                ? ""
                : (p.getFileName() == null ? p.toString() : p.getFileName().toString());
    }

    private static boolean samePath(Path a, Path b) {
        return a != null && a.equals(b);
    }

    /** Keyword-order comparator: {@link #KEYWORD_ORDER} first (in that order), then the rest alphabetically. */
    public static Comparator<String> keywordOrder() {
        return Comparator.comparingInt((String k) -> {
                    int i = KEYWORD_ORDER.indexOf(k);
                    return i < 0 ? KEYWORD_ORDER.size() : i;
                })
                .thenComparing(k -> k.toLowerCase(java.util.Locale.ROOT));
    }
}
