package com.editora.completion;

import java.util.List;

import com.editora.completion.Completion.Kind;
import com.editora.snippet.Snippet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
