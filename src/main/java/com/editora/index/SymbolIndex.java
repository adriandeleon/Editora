package com.editora.index;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     */
    public List<Hit> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<Path, List<Symbol>> entry : byFile.entrySet()) {
            for (Symbol symbol : entry.getValue()) {
                FuzzyMatch.Match m = FuzzyMatch.of(symbol.name(), query);
                if (m != null) {
                    hits.add(new Hit(entry.getKey(), symbol, m.score()));
                }
            }
        }
        hits.sort(Comparator.comparingInt(Hit::score)
                .reversed()
                // Shorter names first among equals: for `list`, `list` is likelier wanted than `listAll`.
                .thenComparingInt((Hit h) -> h.symbol().name().length())
                .thenComparing(h -> h.symbol().name()));
        return hits.size() <= limit ? List.copyOf(hits) : List.copyOf(hits.subList(0, limit));
    }
}
