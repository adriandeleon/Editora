package com.editora.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Merges results from the separate things a user might be looking for — a command, a file, a symbol —
 * into one ranked list.
 *
 * <p>The hard part is not finding them; each source can already rank itself with {@link FuzzyMatch}. It is
 * that the sources are wildly different sizes. A project has tens of thousands of symbols, thousands of
 * files, and about five hundred commands, so a naive merge on raw score hands the entire list to whichever
 * source happens to be biggest and the other two effectively disappear. Grouping with a per-group cap is
 * what keeps all three reachable: each source gets a guaranteed share of the list, and the <em>groups</em>
 * compete on their best result rather than their bulk.
 *
 * <p>Pure and toolkit-free.
 */
public final class SearchEverywhere {

    private SearchEverywhere() {}

    /** Rows per source, so no one source can fill the list. */
    public static final int DEFAULT_PER_GROUP = 8;

    /** Rows overall — a picker shows a screenful. */
    public static final int DEFAULT_TOTAL = 24;

    /**
     * The cap to pass when only one source is in play — a sigil-scoped query, or the empty query that
     * lists commands alone. The caps exist so a big source cannot drown a small one; with nothing to
     * drown, trimming would only hide answers, and it would make a scoped search strictly worse than the
     * single-purpose picker it replaces.
     */
    public static final int UNCAPPED = Integer.MAX_VALUE;

    /** What a result is. The order here is the tie-break order when two groups score equally. */
    public enum Kind {
        /** A registered command. Listed first on a tie: it is the only source that acts rather than navigates. */
        COMMAND,
        /** A file in the project. */
        FILE,
        /** A declaration from the symbol index. */
        SYMBOL
    }

    /**
     * One result. {@code payload} is the caller's own object (a {@code Command}, a {@code Path}, a
     * {@code SymbolIndex.Hit}) carried through untouched, so this class needs to know nothing about them.
     *
     * <p>{@code description} is the longer explanation shown for the highlighted row only, so it costs one
     * label rather than a column. {@code enabled} is false for a row that is listed but cannot be acted on
     * — a command whose feature is switched off. Such a row is kept deliberately: in a picker that stands
     * in for the command palette, a grayed row with an explanation is how a user learns the command exists
     * and what would turn it on, which is exactly what hiding it destroys.
     */
    public record Item(
            Kind kind, String label, String detail, String description, int score, boolean enabled, Object payload) {

        /** An actionable result with no long description — what a file or symbol row is. */
        public Item(Kind kind, String label, String detail, int score, Object payload) {
            this(kind, label, detail, "", score, true, payload);
        }
    }

    /** A source's results, in rank order. */
    public record Group(Kind kind, List<Item> items) {}

    /** A query's scope: which single kind it restricts to ({@code null} = all), and the query minus its sigil. */
    public record Scope(Kind kind, String query) {}

    /**
     * Reads a leading sigil off {@code raw}: {@code >} commands, {@code #} files, {@code @} symbols.
     *
     * <p>Borrowed from VS Code rather than invented, because the muscle memory already exists. A sigil with
     * nothing after it is still a scope — it is what the user types before they start typing the name, and
     * treating it as an empty unscoped query would flash the full unfiltered list at them.
     */
    public static Scope scopeOf(String raw) {
        String q = raw == null ? "" : raw.strip();
        if (q.isEmpty()) {
            return new Scope(null, "");
        }
        Kind kind =
                switch (q.charAt(0)) {
                    case '>' -> Kind.COMMAND;
                    case '#' -> Kind.FILE;
                    case '@' -> Kind.SYMBOL;
                    default -> null;
                };
        return kind == null
                ? new Scope(null, q)
                : new Scope(kind, q.substring(1).strip());
    }

    /** As {@link #merge(List, int, int)} with the default caps. */
    public static List<Group> merge(List<Item> items) {
        return merge(items, DEFAULT_PER_GROUP, DEFAULT_TOTAL);
    }

    /**
     * Groups {@code items} by kind, ranks within each group, caps each group at {@code perGroup} and the
     * whole result at {@code total}, and orders the groups by their best item.
     *
     * <p>The total cap is applied to whole rows across the already-capped groups, so a group is trimmed
     * from its tail rather than dropped outright — losing a source entirely because another one scored
     * well is the failure this whole class exists to prevent.
     */
    public static List<Group> merge(List<Item> items, int perGroup, int total) {
        if (items == null || items.isEmpty() || perGroup <= 0 || total <= 0) {
            return List.of();
        }
        Map<Kind, List<Item>> byKind = new EnumMap<>(Kind.class);
        for (Item item : items) {
            if (item != null && item.kind() != null) {
                byKind.computeIfAbsent(item.kind(), k -> new ArrayList<>()).add(item);
            }
        }
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<Kind, List<Item>> e : byKind.entrySet()) {
            List<Item> ranked = new ArrayList<>(e.getValue());
            // Command Palette and Search Everywhere deliberately share one command-ordering function.
            // Commands therefore arrive pre-ranked and must retain that order here. Corpus sources are
            // still defensively ranked because callers may aggregate unsorted file/symbol candidates.
            if (e.getKey() != Kind.COMMAND) {
                ranked.sort(Comparator.comparing((Item i) -> !i.enabled())
                        .thenComparing(Comparator.comparingInt(Item::score).reversed())
                        .thenComparingInt((Item i) -> i.label().length())
                        .thenComparing(Item::label));
            }
            if (ranked.size() > perGroup) {
                ranked = new ArrayList<>(ranked.subList(0, perGroup));
            }
            groups.add(new Group(e.getKey(), List.copyOf(ranked)));
        }
        groups.sort(
                Comparator.comparingInt((Group g) -> bestScore(g)).reversed().thenComparing(Group::kind));

        int budget = total;
        List<Group> out = new ArrayList<>();
        for (Group g : groups) {
            if (budget <= 0) {
                break;
            }
            List<Item> kept = g.items().size() <= budget
                    ? g.items()
                    : List.copyOf(g.items().subList(0, budget));
            budget -= kept.size();
            out.add(new Group(g.kind(), kept));
        }
        return List.copyOf(out);
    }

    /**
     * The score a group competes on: its best <em>actionable</em> item. A group of nothing but disabled
     * rows still competes, on its best row, so it does not silently sink below an empty-handed source.
     */
    private static int bestScore(Group g) {
        for (Item i : g.items()) {
            if (i.enabled()) {
                return i.score();
            }
        }
        return g.items().isEmpty() ? Integer.MIN_VALUE : g.items().get(0).score();
    }

    /** Every item of every group, in display order — what the list widget actually renders under its headers. */
    public static List<Item> flatten(List<Group> groups) {
        List<Item> out = new ArrayList<>();
        for (Group g : groups) {
            out.addAll(g.items());
        }
        return List.copyOf(out);
    }
}
