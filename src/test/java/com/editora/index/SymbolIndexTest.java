package com.editora.index;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import com.editora.search.FuzzyMatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolIndexTest {

    private static final Path A = Path.of("a.java");
    private static final Path B = Path.of("b.java");

    private static Symbol sym(String name) {
        return new Symbol(name, SymbolKind.METHOD, 0, 0);
    }

    @Test
    void putThenSearchFindsTheSymbol() {
        SymbolIndex index = new SymbolIndex();
        index.put(A, List.of(sym("loadConfig")));
        assertEquals(
                List.of("loadConfig"),
                index.search("load").stream().map(h -> h.symbol().name()).toList());
        assertEquals(A, index.search("load").get(0).file());
    }

    @Test
    void puttingAFileAgainReplacesItRatherThanAccumulating() {
        // This is what makes the index incremental: a saved file replaces exactly its own entry.
        SymbolIndex index = new SymbolIndex();
        index.put(A, List.of(sym("old"), sym("alsoOld")));
        index.put(A, List.of(sym("fresh")));
        assertEquals(1, index.symbolCount());
        assertEquals(1, index.fileCount());
        assertTrue(index.search("old").isEmpty(), "the previous scan's symbols must be gone");
        assertFalse(index.search("fresh").isEmpty());
    }

    @Test
    void removingAFileDropsItsSymbols() {
        SymbolIndex index = new SymbolIndex();
        index.put(A, List.of(sym("gone")));
        index.put(B, List.of(sym("kept")));
        index.remove(A);
        assertEquals(1, index.fileCount());
        assertEquals(1, index.symbolCount());
        assertTrue(index.search("gone").isEmpty());
    }

    @Test
    void anEmptyScanDoesNotBurnAFileSlot() {
        SymbolIndex index = new SymbolIndex();
        index.put(A, List.of());
        assertEquals(0, index.fileCount());
        assertFalse(index.contains(A));
    }

    @Test
    void symbolCountStaysCorrectAcrossReplacement() {
        SymbolIndex index = new SymbolIndex();
        index.put(A, List.of(sym("a"), sym("b"), sym("c")));
        index.put(A, List.of(sym("a")));
        index.put(B, List.of(sym("d"), sym("e")));
        assertEquals(3, index.symbolCount());
        index.clear();
        assertEquals(0, index.symbolCount());
        assertEquals(0, index.fileCount());
    }

    @Test
    void resultsAreRankedBestFirst() {
        SymbolIndex index = new SymbolIndex();
        index.put(A, List.of(sym("listAllTheThings"), sym("list"), sym("lazyIntervalSet")));
        assertEquals(
                List.of("list", "listAllTheThings", "lazyIntervalSet"),
                index.search("list").stream().map(h -> h.symbol().name()).toList());
    }

    @Test
    void searchIsCapped() {
        SymbolIndex index = new SymbolIndex();
        List<Symbol> many = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add(sym("handler" + i));
        }
        index.put(A, many);
        assertEquals(10, index.search("handler", 10).size());
    }

    @Test
    void aBlankQueryReturnsNothingRatherThanEverything() {
        SymbolIndex index = new SymbolIndex();
        index.put(A, List.of(sym("thing")));
        assertEquals(List.of(), index.search(""));
        assertEquals(List.of(), index.search("   "));
        assertEquals(List.of(), index.search(null));
    }

    @Test
    void symbolsInReturnsAFilesOwnSymbolsInOrder() {
        SymbolIndex index = new SymbolIndex();
        index.put(A, List.of(sym("first"), sym("second")));
        assertEquals(
                List.of("first", "second"),
                index.symbolsIn(A).stream().map(Symbol::name).toList());
        assertEquals(List.of(), index.symbolsIn(B));
    }

    @Test
    void aNullFileIsRefusedRatherThanStored() {
        SymbolIndex index = new SymbolIndex();
        assertFalse(index.put(null, List.of(sym("x"))));
        assertEquals(0, index.fileCount());
    }

    /**
     * {@link SymbolIndex#search} selects the top {@code limit} with a bounded heap instead of sorting every
     * match (#876). The whole case for that swap is that the output is <em>identical</em> — so this asserts
     * it differentially, against the algorithm it replaced, rather than asserting properties of the new one.
     *
     * <p>Randomised over corpora built to be adversarial for exactly the way this can break: a small
     * vocabulary and many duplicate names, so scores, name lengths and names all collide constantly and
     * nearly every comparison falls through to the tie-break the heap could get wrong. A corpus of distinct
     * names would pass with a broken comparator, which is the trap — ties are the only thing at risk here.
     *
     * <p>Limits are swept around the corpus size so the eviction path, the exactly-full path and the
     * never-full path are all exercised.
     */
    @Test
    void boundedSelectionReturnsExactlyWhatSortingEverythingWouldHave() {
        String[] vocab = {"get", "getAll", "set", "list", "listAll", "load", "loadAll", "run", "reset", "read"};
        String[] queries = {"g", "l", "a", "e", "ge", "la", "list", "load", "getall", "zz", "s", "r"};
        for (long seed = 0; seed < 40; seed++) {
            Random rnd = new Random(seed);
            SymbolIndex index = new SymbolIndex();
            int files = 1 + rnd.nextInt(6);
            List<Ref> flat = new ArrayList<>();
            for (int f = 0; f < files; f++) {
                Path file = Path.of("f" + f + ".java");
                List<Symbol> syms = new ArrayList<>();
                for (int i = 0, n = 1 + rnd.nextInt(8); i < n; i++) {
                    syms.add(sym(vocab[rnd.nextInt(vocab.length)]));
                }
                index.put(file, syms);
                for (Symbol sy : syms) {
                    flat.add(new Ref(file, sy));
                }
            }
            for (String q : queries) {
                for (int limit : new int[] {1, 2, 3, 5, 10, 40, 1000}) {
                    assertEquals(
                            reference(flat, q, limit),
                            index.search(q, limit).stream()
                                    .map(h -> h.file() + "#" + h.symbol().name() + "@" + h.score())
                                    .toList(),
                            "seed " + seed + " query '" + q + "' limit " + limit);
                }
            }
        }
    }

    /** A symbol together with the file it came from, in scan order. */
    private record Ref(Path file, Symbol symbol) {}

    /**
     * The pre-#876 algorithm: score everything, sort the whole list, truncate. Deliberately spelled out
     * here rather than shared with production, so a change to the real ranking cannot silently redefine
     * what this is comparing against — the point is to compare two independent implementations.
     */
    private static List<String> reference(List<Ref> flat, String query, int limit) {
        record H(Path file, Symbol symbol, int score) {}
        List<H> hits = new ArrayList<>();
        for (Ref r : flat) {
            FuzzyMatch.Match m = FuzzyMatch.of(r.symbol().name(), query);
            if (m != null) {
                hits.add(new H(r.file(), r.symbol(), m.score()));
            }
        }
        hits.sort(Comparator.comparingInt(H::score)
                .reversed()
                .thenComparingInt((H h) -> h.symbol().name().length())
                .thenComparing(h -> h.symbol().name()));
        return hits.subList(0, Math.min(limit, hits.size())).stream()
                .map(h -> h.file() + "#" + h.symbol().name() + "@" + h.score())
                .toList();
    }
}
