package com.editora.markdown;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the pure Markdown-lint auto-fixer. */
class MarkdownLintFixTest {

    private static String fix(String md) {
        return MarkdownLintFix.fix(md, Set.of(), 4);
    }

    @Test
    void md009StripsTrailingWhitespace() {
        assertEquals("a\nb\n", fix("a   \nb\n"));
    }

    @Test
    void md010ConvertsTabsToSpaces() {
        assertEquals("  x\n", MarkdownLintFix.fix("\tx\n", Set.of(), 2));
    }

    @Test
    void md012CollapsesBlankRuns() {
        assertEquals("a\n\nb\n", fix("a\n\n\n\nb\n"));
    }

    @Test
    void md018And019FixHeadingSpacing() {
        assertEquals("# x\n", fix("#x\n"));
        assertEquals("# x\n", fix("#   x\n"));
    }

    @Test
    void md023DeIndentsHeadings() {
        assertEquals("# x\n", fix("   # x\n"));
    }

    @Test
    void md026StripsHeadingTrailingPunctuation() {
        assertEquals("# Title\n", fix("# Title.\n"));
    }

    @Test
    void md047EnsuresSingleFinalNewline() {
        assertEquals("# T\n", fix("# T"));
        assertEquals("# T\n", fix("# T\n\n\n"));
    }

    @Test
    void idempotent() {
        String once = fix("#x  \n\n\n##y.\n");
        assertEquals(once, fix(once));
    }

    @Test
    void respectsDisabledRules() {
        assertEquals("a   \n", MarkdownLintFix.fix("a   \n", Set.of("MD009"), 4));
    }

    @Test
    void leavesCodeBlocksUntouched() {
        String md = "# T\n\n```\n\tcode  \n```\n";
        // tabs/trailing-ws inside the fence are preserved verbatim.
        assertEquals(md, fix(md));
    }

    @Test
    void emptyAndNull() {
        assertEquals("", fix(""));
        assertEquals(null, MarkdownLintFix.fix(null, Set.of(), 4));
    }
}
