package com.editora.index;

import java.nio.file.Path;
import java.util.List;

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
}
