package com.editora.completion;

import java.nio.file.Path;
import java.util.List;

import com.editora.completion.Completion.Kind;
import com.editora.snippet.Snippet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionEngineTest {

    private static Snippet snip(String prefix) {
        return new Snippet(prefix + "Name", prefix, "body", "desc", "java");
    }

    @Test
    void snippetCompletionsPrefixMatchExcludesExactAndRanksShorterFirst() {
        List<Snippet> all = List.of(snip("for"), snip("fori"), snip("sout"), snip("fo"));
        List<Completion> out = CompletionEngine.snippetCompletions(all, "fo");
        List<String> inserts = out.stream().map(Completion::insert).toList();
        assertEquals(List.of("for", "fori"), inserts, "matched, exact 'fo' excluded, shorter first");
        assertTrue(out.stream().allMatch(c -> c.kind() == Kind.SNIPPET));
    }

    @Test
    void snippetCompletionsAreCaseInsensitive() {
        List<Completion> out = CompletionEngine.snippetCompletions(List.of(snip("Sysout")), "sys");
        assertEquals(1, out.size());
        assertEquals("Sysout", out.get(0).insert());
    }

    @Test
    void mergeDedupesByInsertSnippetWinsAndCaps() {
        List<Completion> snippets = List.of(Completion.ofSnippet(snip("for")));
        List<Completion> words = List.of(
                Completion.word("for", null), // dup of snippet -> dropped
                Completion.word("foreach", null),
                Completion.word("format", null));
        List<Completion> merged = CompletionEngine.merge(snippets, words, "fo", 2);
        assertEquals(2, merged.size(), "capped at 2");
        assertEquals(Kind.SNIPPET, merged.get(0).kind(), "snippet ranked first");
        assertEquals("for", merged.get(0).insert());
        long forWord = merged.stream()
                .filter(c -> c.insert().equals("for") && c.kind() == Kind.WORD)
                .count();
        assertEquals(0, forWord, "duplicate word 'for' removed in favor of the snippet");
    }

    @Test
    void rankComparePrefersExactCasePrefix() {
        // "For" (capital) vs "form" with typed prefix "fo": both start with "fo" case-insensitively,
        // but only "form" matches the exact case -> ranked first.
        assertTrue(CompletionEngine.rankCompare("form", "For", "fo") < 0);
    }

    @Test
    void mergeWordsOnlyWhenNoSnippets() {
        List<Completion> merged = CompletionEngine.merge(
                List.of(), List.of(Completion.word("alpha", null), Completion.word("alpine", null)), "alp", 12);
        assertEquals(2, merged.size());
        assertTrue(merged.stream().allMatch(c -> c.kind() == Kind.WORD));
        assertFalse(merged.isEmpty());
    }

    private static Completion lsp(String label, String sortText, boolean preselect) {
        return Completion.lsp(label, label, "", null, null, sortText, preselect, false, null);
    }

    @Test
    void relevanceSortPutsPreselectFirstThenSortTextThenLabel() {
        List<Completion> sorted = CompletionEngine.sortLspByRelevance(List.of(
                lsp("zebra", "0003", false),
                lsp("apple", "0001", false),
                lsp("preselected", "0009", true),
                lsp("mango", "0002", false)));
        assertEquals("preselected", sorted.get(0).label()); // preselect wins regardless of sortText
        assertEquals("apple", sorted.get(1).label()); // then by sortText ascending
        assertEquals("mango", sorted.get(2).label());
        assertEquals("zebra", sorted.get(3).label());
    }

    @Test
    void relevanceSortKeepsNullSortTextLastAndFallsBackToLabel() {
        List<Completion> sorted = CompletionEngine.sortLspByRelevance(
                List.of(lsp("noSort2", null, false), lsp("withSort", "0001", false), lsp("noSort1", null, false)));
        assertEquals("withSort", sorted.get(0).label()); // has sortText → before the nulls
        assertEquals("noSort1", sorted.get(1).label()); // null sortText → alphabetical fallback
        assertEquals("noSort2", sorted.get(2).label());
    }

    @Test
    void rankCompareIsAntisymmetric() {
        // The "very close match" nudge used to be decided from the first operand alone, so a close match and
        // a longer one each sorted before the other: rankCompare(a,b) and rankCompare(b,a) were both
        // negative, and the ranking came out however the snippets happened to be declared.
        assertTrue(CompletionEngine.rankCompare("abc", "abbb", "ab") < 0, "the close match ranks first");
        assertTrue(CompletionEngine.rankCompare("abbb", "abc", "ab") > 0, "...from either direction");
    }

    @Test
    void rankIsIndependentOfInputOrder() {
        List<Completion> a = CompletionEngine.merge(
                List.of(), List.of(Completion.word("abbb", null), Completion.word("abc", null)), "ab", 12);
        List<Completion> b = CompletionEngine.merge(
                List.of(), List.of(Completion.word("abc", null), Completion.word("abbb", null)), "ab", 12);
        assertEquals("abc", a.get(0).insert());
        assertEquals("abc", b.get(0).insert(), "same items, other declaration order → same ranking");
    }

    @Test
    void rankCompareObeysTheComparatorContractOnALargeSamePrefixRun() {
        // TimSort refuses to sort a run of >=32 with a comparator that contradicts itself ("Comparison
        // method violates its general contract!") — that would throw mid-keystroke on a snippet file with
        // many same-prefix entries.
        List<Completion> words = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            words.add(Completion.word(i % 2 == 0 ? "abc" + i : "ab" + "b".repeat(2 + i % 5) + i, null));
        }
        assertEquals(12, CompletionEngine.merge(List.of(), words, "ab", 12).size());
    }

    @Test
    void ghostSuffixOnlyCompletesWordsCasedLikeWhatWasTyped() {
        // Ghost text keeps the typed prefix and appends only the suffix, so an all-caps/mixed-case prefix
        // would splice into a non-word: "APP" + "le" = "APPle".
        assertEquals("le", CompletionEngine.ghostSuffix("apple", "app"));
        assertEquals("le", CompletionEngine.ghostSuffix("apple", "App"), "sentence-start capitalization");
        assertNull(CompletionEngine.ghostSuffix("apple", "APP"));
        assertNull(CompletionEngine.ghostSuffix("apple", "aPp"));
        assertNull(CompletionEngine.ghostSuffix("apple", "apple"), "no suffix to add");
        assertNull(CompletionEngine.ghostSuffix("apple", "banana"));
    }

    @Test
    void completeMatchesSnippetsOnTheWideTokenNotTheIdentifierPrefix(@org.junit.jupiter.api.io.TempDir Path dir) {
        // #446: the bundled c `#`-directive snippets (`#inc`, `#incl`, …) trigger on `#in`. The identifier walk
        // stops at `#`, so the popup only ever saw `in` — a prefix no snippet is registered under. Snippets must
        // match the wide non-whitespace token (`#in`) while words/keywords keep the identifier run (`in`).
        CompletionEngine engine = new CompletionEngine(
                new com.editora.snippet.SnippetManager(new com.editora.config.ConfigManager(dir)), null);

        List<Completion> wide = engine.complete("c", null, "in", "#in", false);
        assertTrue(
                wide.stream().anyMatch(c -> c.insert().startsWith("#in")),
                "the wide token `#in` reaches the `#in…` directive snippets");

        List<Completion> identifierOnly = engine.complete("c", null, "in", false);
        assertFalse(
                identifierOnly.stream().anyMatch(c -> c.insert().startsWith("#")),
                "the identifier-only prefix `in` never matched a `#`-directive snippet — this was the bug");
    }

    @Test
    void prefixOverlapFindsLongestSuffixThatIsAPrefixOfTheInsert() {
        // The phpactor case: typed "$" then accept "$user" — the overlap is "$" (1 char), so on accept the
        // editor replaces it instead of appending → "$user", not "$$user".
        assertEquals(1, CompletionEngine.prefixOverlap("<?php\n$", "$user"));
        // A whole identifier already typed overlaps entirely.
        assertEquals(3, CompletionEngine.prefixOverlap("foo.use", "user"));
        // No overlap at all.
        assertEquals(0, CompletionEngine.prefixOverlap("foo.", "$user"));
        assertEquals(0, CompletionEngine.prefixOverlap("xyz", "abc"));
        // Longest wins: "aa" is a longer suffix-of-before/prefix-of-insert overlap than "a".
        assertEquals(2, CompletionEngine.prefixOverlap("baa", "aab"));
        // Never crosses a line break.
        assertEquals(0, CompletionEngine.prefixOverlap("user\n", "user"));
        // Null/empty are safe.
        assertEquals(0, CompletionEngine.prefixOverlap(null, "x"));
        assertEquals(0, CompletionEngine.prefixOverlap("x", ""));
    }
}
