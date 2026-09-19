package com.editora.completion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompletionSessionTest {
    @Test
    void typingAndBackspaceReuseTheOriginalMemberScopeAndRebaseUtf16Ranges() {
        var session = new CompletionSession(16, 2, 16, "", 4);
        session.changed(16, "", "prin", 5);
        session.changed(19, "n", "", 6);
        session.changed(18, "i", "", 7);
        assertTrue(session.matches(18, 7));
        assertEquals("pr", session.prefix());
        assertEquals(
                new Completion.ReplaceRange(2, 16, 2, 23), session.range(new Completion.ReplaceRange(2, 16, 2, 21)));
    }

    @Test
    void deletingBelowTheRequestedPrefixRequiresARequest() {
        var session = new CompletionSession(13, 0, 13, "pr", 1);
        session.changed(12, "r", "", 2);
        assertFalse(session.matches(12, 2));
    }

    @Test
    void unrelatedEditEvenAtTheSameCaretInvalidatesCoordinates() {
        var session = new CompletionSession(13, 0, 13, "pr", 1);
        session.changed(0, "S", "s", 2);
        assertFalse(session.matches(13, 2));
    }

    @Test
    void anotherMemberAccessEndsTheOldScope() {
        var session = new CompletionSession(11, 0, 11, "", 1);
        session.changed(11, "", "getFoo().", 2);
        assertFalse(session.matches(20, 2));
    }

    @Test
    void filterTextAndFuzzyAlternativesPreserveServerRelevanceWithinTiers() {
        var hidden = Completion.lsp("irrelevant display", "replacement", "")
                .withProtocol(new Completion.Protocol("println", null, null));
        var fuzzy = Completion.lsp("getPrintStream", "getPrintStream", "");
        var folded = Completion.lsp("PrintWriter", "PrintWriter", "");
        assertEquals(
                java.util.List.of(hidden, folded, fuzzy),
                CompletionEngine.filterLsp(java.util.List.of(fuzzy, folded, hidden), "pr"));
        assertEquals(3, CompletionEngine.matchTier(hidden, "irr"));
    }

    @Test
    void missingSortTextUsesLabelAndEqualKeysKeepServerOrder() {
        var first = Completion.lsp("zebra", "zebra", "", null, CompletionIconKind.CLASS, "same", false, false, null);
        var second = Completion.lsp("alpha", "alpha", "", null, CompletionIconKind.CLASS, "same", false, false, null);
        assertEquals(
                java.util.List.of(first, second),
                CompletionEngine.sortLspByRelevance(java.util.List.of(first, second)));
        var explicit = Completion.lsp("first", "first", "", null, CompletionIconKind.CLASS, "zzz", false, false, null);
        var fallback = Completion.lsp("alpha", "alpha", "");
        assertEquals(
                java.util.List.of(fallback, explicit),
                CompletionEngine.sortLspByRelevance(java.util.List.of(explicit, fallback)));
    }

    @Test
    void explicitEditRangeControlsFilteringInsteadOfGuessingAWordBoundary() {
        var item = Completion.lsp("utility", "java.util.*;", "")
                .withProtocol(new Completion.Protocol("java.util", null, null))
                .withReplaceRange(new Completion.ReplaceRange(0, 7, 0, 15));
        assertEquals("java.ut", CompletionEngine.filterQuery(item, "ut", 0, 14, "import java.ut"));
        assertEquals(
                java.util.List.of(item),
                CompletionEngine.filterLsp(java.util.List.of(item), "ut", 0, 14, "import java.ut"));
        var insertion = Completion.lsp("add imports", "insertion", "")
                .withReplaceRange(new Completion.ReplaceRange(0, 14, 0, 14));
        assertEquals(
                java.util.List.of(insertion),
                CompletionEngine.filterLsp(java.util.List.of(insertion), "ut", 0, 14, "import java.ut"));
    }
}
