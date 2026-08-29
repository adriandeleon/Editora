package com.editora.index;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import com.editora.search.FuzzyMatch;

/**
 * An in-memory, per-file map of a project's declarations, queried by fuzzy name.
 *
 * <p>The store half of the server-free navigation index: {@link DeclarationScanner} produces symbols for
 * one file, this holds them for a project and answers "where is X". Keeping it per file is what makes the
 * index incremental — a saved file replaces exactly its own entry ({@link #put}) and a deleted one drops
 * it ({@link #remove}), with no rescan of anything else.
 *
 * <p>Pure and unsynchronised by design. Threading belongs to the coordinator that will drive this — the
 * established shape in this codebase is a daemon executor with a generation guard marshalling results back
 * with {@code Platform.runLater}, and baking a lock in here would both be the wrong layer and invite
 * callers to hold it across a file read.
 *
 * <p>Bounded: {@link #MAX_FILES} entries and {@link #MAX_SYMBOLS} symbols in total. An index that can grow
 * without limit in the background of an editor is a memory leak with a feature attached, and the caps are
 * enforced here rather than trusted to the caller.
 */
public final class SymbolIndex {

    /** Cap on indexed files. */
    public static final int MAX_FILES = 20_000;

    /** Cap on retained symbols across all files. */
    public static final int MAX_SYMBOLS = 400_000;

    /** Default cap on returned matches — a picker shows a screenful, not a corpus. */
    public static final int DEFAULT_LIMIT = 200;

    /** A symbol together with the file it was found in. */
    public record Hit(Path file, Symbol symbol, int score) {}

    // Insertion-ordered so a query's tie-break order is stable rather than hash-dependent.
    private final Map<Path, List<Symbol>> byFile = new LinkedHashMap<>();
    private int symbolCount;

    /** Replaces {@code file}'s symbols. Returns false when a cap refused the update. */
    public boolean put(Path file, List<Symbol> symbols) {
        if (file == null) {
            return false;
        }
        List<Symbol> incoming = symbols == null ? List.of() : List.copyOf(symbols);
        List<Symbol> previous = byFile.get(file);
        if (previous == null && byFile.size() >= MAX_FILES) {
            return false;
        }
        int delta = incoming.size() - (previous == null ? 0 : previous.size());
        if (symbolCount + delta > MAX_SYMBOLS) {
            return false;
        }
        if (incoming.isEmpty()) {
            // Keeping an empty entry would burn a file slot to record "nothing here".
            remove(file);
            return true;
        }
        byFile.put(file, incoming);
        symbolCount += delta;
        return true;
    }

    /** Drops {@code file}'s symbols. */
    public void remove(Path file) {
        List<Symbol> previous = byFile.remove(file);
        if (previous != null) {
            symbolCount -= previous.size();
        }
    }

    /** Forgets everything — a project switch, or a settings change that invalidates the corpus. */
    public void clear() {
        byFile.clear();
        symbolCount = 0;
    }

    public boolean contains(Path file) {
        return byFile.containsKey(file);
    }

    public int fileCount() {
        return byFile.size();
    }

    public int symbolCount() {
        return symbolCount;
    }

    /** The symbols indexed for {@code file}, in document order; empty when it is not indexed. */
    public List<Symbol> symbolsIn(Path file) {
        return byFile.getOrDefault(file, List.of());
    }

    /** As {@link #search(String, int)} with {@link #DEFAULT_LIMIT}. */
    public List<Hit> search(String query) {
        return search(query, DEFAULT_LIMIT);
    }

    /**
     * The best {@code limit} symbols whose name matches {@code query}, best first.
     *
     * <p>Matching is on the bare name rather than the qualified one: a query is nearly always a name, and
     * scoring the container too would let a long package path outweigh the thing actually being looked
     * for. A blank query returns nothing — "every symbol in the project" is not a useful answer, and the
     * caller has {@link #symbolsIn} when it wants a file's outline.
     *
     * <p><b>Bounded selection, not a full sort.</b> This runs on the FX thread on every keystroke, and a
     * short query matches a large fraction of the corpus — so sorting every match to then discard all but
     * {@code limit} of them was the single worst-scaling thing here: measured at 57–70% of the call for a
     * one- or two-character query, and growing with corpus size (#876). Instead a heap of at most
     * {@code limit} entries keeps the running best, so the cost is linear in the corpus with a
     * {@code log(limit)} factor on the few candidates good enough to get in.
     *
     * <p>The output is <b>identical</b> to sorting everything and truncating, which is the property that
     * makes this safe to swap in. That relies on {@link #RANK} being a <em>total</em> order: the display
     * keys (score, then name length, then name) leave genuine ties, and {@code List.sort} is stable, so
     * the old code broke those ties by scan order. {@code RANK} therefore ends in the scan index, which is
     * what a stable sort of the full list was doing implicitly. Drop that key and a tie is resolved by
     * heap accident instead — the results stay plausible, so nothing looks wrong, and the row order for a
     * broad query quietly stops being reproducible from one keystroke to the next.
     */
    public List<Hit> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        // Ordered worst-first, so the head is the entry a better candidate evicts.
        PriorityQueue<Ranked> keep = new PriorityQueue<>(RANK.reversed());
        int seq = 0;
        for (Map.Entry<Path, List<Symbol>> entry : byFile.entrySet()) {
            for (Symbol symbol : entry.getValue()) {
                // scoreOf, not of: the highlight ranges of() builds are discarded here (a Hit carries only
                // a score) and re-derived at render time for the handful of rows actually drawn.
                int score = FuzzyMatch.scoreOf(symbol.name(), query);
                if (score == FuzzyMatch.NO_SCORE) {
                    continue;
                }
                int at = seq++;
                // Once full, reject before allocating: the comparison needs only the score and the name,
                // both already in hand, and the overwhelming majority of matches lose it.
                if (keep.size() == limit && !beats(score, symbol.name(), at, keep.peek())) {
                    continue;
                }
                if (keep.size() == limit) {
                    keep.poll();
                }
                keep.add(new Ranked(entry.getKey(), symbol, score, at));
            }
        }
        List<Ranked> best = new ArrayList<>(keep);
        best.sort(RANK);
        List<Hit> out = new ArrayList<>(best.size());
        for (Ranked r : best) {
            out.add(new Hit(r.file(), r.symbol(), r.score()));
        }
        return List.copyOf(out);
    }

    /** A candidate mid-selection: a {@link Hit} plus the scan index that makes {@link #RANK} total. */
    private record Ranked(Path file, Symbol symbol, int score, int seq) {}

    /**
     * Display order, made a total order by the scan index — see {@link #search(String, int)} for why that
     * last key is load-bearing rather than tidiness.
     */
    private static final Comparator<Ranked> RANK = Comparator.comparingInt(Ranked::score)
            .reversed()
            // Shorter names first among equals: for `list`, `list` is likelier wanted than `listAll`.
            .thenComparingInt((Ranked r) -> r.symbol().name().length())
            .thenComparing(r -> r.symbol().name())
            .thenComparingInt(Ranked::seq);

    /** Whether a candidate outranks {@code worst}, without building a {@link Ranked} to ask. */
    private static boolean beats(int score, String name, int seq, Ranked worst) {
        if (score != worst.score()) {
            return score > worst.score();
        }
        int len = worst.symbol().name().length();
        if (name.length() != len) {
            return name.length() < len;
        }
        int byName = name.compareTo(worst.symbol().name());
        return byName != 0 ? byName < 0 : seq < worst.seq();
    }
}
