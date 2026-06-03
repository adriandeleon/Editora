package com.editora.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DictionaryWordsTest {

    @Test
    void parseDicStripsCountFlagsAndMorphologyAndJunk() {
        List<String> lines = List.of(
                "6",                 // count header — dropped
                "apple/MNS",         // flags stripped -> apple
                "banana",            // plain
                "0th/pt",            // has digit -> dropped
                "a",                 // too short -> dropped
                "co-op",             // hyphen kept
                "don't/S",           // apostrophe kept, flags stripped
                "word\tpo:noun");    // morphology after tab stripped
        List<String> words = DictionaryWords.parseDic(lines);
        assertEquals(List.of("apple", "banana", "co-op", "don't", "word"), words);
    }

    @Test
    void parseDicWithoutCountHeaderKeepsFirstWord() {
        // If the first line isn't a pure number, it's a real word, not a header.
        List<String> words = DictionaryWords.parseDic(List.of("alpha", "beta"));
        assertEquals(List.of("alpha", "beta"), words);
    }

    @Test
    void matchPrefixIsCaseInsensitiveExcludesExactAndRespectsLimit() {
        String[] sorted = DictionaryWords.sortedUnique(
                List.of("Apple", "apply", "apt", "banana", "app"));
        // "app" prefix: Apple, apply match; "app" itself excluded; "apt" (only "ap") and banana out of range.
        List<String> m = DictionaryWords.matchPrefix(sorted, "app", 10);
        assertTrue(m.contains("Apple"), m.toString());
        assertTrue(m.contains("apply"), m.toString());
        assertFalse(m.contains("apt"), "apt does not start with app");
        assertFalse(m.contains("app"), "exact match must be excluded");
        assertFalse(m.contains("banana"), "out of prefix range");
        assertEquals(1, DictionaryWords.matchPrefix(sorted, "app", 1).size(), "limit honored");
    }

    @Test
    void matchPrefixEmptyForBlankPrefix() {
        String[] sorted = DictionaryWords.sortedUnique(List.of("alpha"));
        assertTrue(DictionaryWords.matchPrefix(sorted, "", 5).isEmpty());
    }

    @Test
    void startingWithMergesDictionaryAndUserWords() {
        DictionaryWords.clearCache();
        DictionaryWords.putForTest("en_US", List.of("alpha", "alpine", "beta"));
        List<String> out = DictionaryWords.startingWith("en_US", "alp", Set.of("alpaca"), 10);
        assertTrue(out.contains("alpha"), out.toString());
        assertTrue(out.contains("alpine"), out.toString());
        assertTrue(out.contains("alpaca"), "user word merged " + out);
        assertFalse(out.contains("beta"), out.toString());
        DictionaryWords.clearCache();
    }
}
