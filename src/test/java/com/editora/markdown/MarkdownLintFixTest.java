package com.editora.markdown;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals("a  b\n", MarkdownLintFix.fix("a\tb\n", Set.of(), 2));
        // A leading tab makes the line indented code, and code is never rewritten.
        assertEquals("\tx\n", MarkdownLintFix.fix("\tx\n", Set.of(), 4));
        // Under paragraph text the same line is a continuation, so its tab goes (same column: 4 spaces).
        assertEquals("text\n    x\n", MarkdownLintFix.fix("text\n\tx\n", Set.of(), 4));
    }

    @Test
    void md010NeverTurnsTabIndentedCodeIntoAHeading() {
        // With a tab size below 4 the replacement would drop the line under the 4-column code threshold,
        // making `# install deps` an H1 (and MD023/MD026 would then rewrite it further). Leave it alone.
        String md = "Run:\n\n\t# install deps.\n\tnpm ci\n";
        assertEquals(md, MarkdownLintFix.fix(md, Set.of(), 2));
        assertEquals(md, MarkdownLintFix.fix(md, Set.of(), 3));
        // Tab size 4 would keep the column, but the lines are an indented code block: left exactly as written.
        assertEquals(md, MarkdownLintFix.fix(md, Set.of(), 4));
        // Two tabs at size 2 would be 4 spaces (8 columns → 4): a different nesting level, so skipped too.
        assertEquals("\t\t- deep\n", MarkdownLintFix.fix("\t\t- deep\n", Set.of(), 2));
    }

    @Test
    void md026StripsTheWholeTrailingPunctuationRun() {
        assertEquals("# Wait\n", fix("# Wait...\n"));
        assertEquals("## Done ##\n", fix("## Done!!! ##\n"));
        assertEquals("# Why?\n", fix("# Why?\n")); // the question mark is allowed
        String once = fix("# Wait...\n");
        assertEquals(once, fix(once));
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

    private static void assertUntouched(String md) {
        assertEquals(md, MarkdownLintFix.fix(md, Set.of(), 4), md);
        assertEquals(md, MarkdownLintFix.fix(md, Set.of(), 2), md);
    }

    // --- C1 / C6 / C9 / C10: only prose changes --------------------------------------------------------

    @Test
    void aFenceNestedInListsIsCopiedThrough() {
        assertUntouched("# T\n\n1. Step one\n    - Create the Makefile:\n\n        ```make\n        build:\n"
                + "        \tgcc -o app main.c   \n\n\n        #comment\n        ```\n\n2. Next\n");
        assertUntouched("# T\n\n1.  Run:\n\n    ```sh\n    #!/bin/sh\n    curl https://x.io\t| sh  \n    ```\n");
    }

    @Test
    void anIndentedCodeBlockIsCopiedThrough() {
        assertUntouched("# T\n\ntext\n\n\tcode\twith\ttab   \n\n\n    more   \n\ntext\n");
    }

    @Test
    void aFenceInABlockQuoteIsCopiedThrough() {
        assertUntouched("# T\n\n> ```text\n> a\tb   \n>\n>\n> #x\n> ```\n");
    }

    @Test
    void codeAfterAFenceLineWithAnInfoStringIsStillCode() {
        assertUntouched("# T\n\n```js\ncode\n```js\n#still code\t  \n```\n");
    }

    @Test
    void frontMatterIsCopiedThrough() {
        assertUntouched("+++\ntitle = \"X\"   \n#comment\n\n\n\tx = 1\n+++\n\n# T\n");
        assertUntouched("---\ntitle: X   \n#comment\n...\n\n# T\n");
    }

    @Test
    void htmlCommentsAndPreBlocksAreCopiedThrough() {
        assertUntouched("# T\n\n<pre>\na\tb   \n\n\n#x\n</pre>\n");
        assertUntouched("# T\n\n<!--\n#hidden   \n\n\n\tx\n-->\n");
    }

    @Test
    void proseAroundCodeIsStillFixed() {
        assertEquals(
                "# T\n\n- item\n\n  ```\n  a\tb   \n  ```\n\n- next\n",
                fix("# T   \n\n\n- item   \n\n  ```\n  a\tb   \n  ```\n\n- next\n"));
    }

    // --- C7 / C18 --------------------------------------------------------------------------------------

    @Test
    void md026NeverCutsAnEntity() {
        assertUntouched("# T\n\n## C &quot;\n\n## D &copy;\n");
        assertEquals("# T\n\n## D\n", fix("# T\n\n## D;\n"));
    }

    @Test
    void aHeadingInAListItemKeepsItsPlace() {
        assertUntouched("# T\n\n- item\n\n  ## In the item\n\n1. x\n\n   ## Also\n");
        assertEquals("# T\n\n- item\n\n  ## One too many\n", fix("# T\n\n- item\n\n   ## One too many.\n"));
    }

    // --- C19: line endings -----------------------------------------------------------------------------

    @Test
    void crlfDocumentsStayCrlf() {
        assertEquals("# T\r\n\r\nend\r\n", fix("# T\r\n\r\nend\r\n\r\n"));
        assertEquals("# T\r\n\r\ntext\r\n", fix("# T\r\n\r\ntext"));
        assertEquals("# T\r\n", fix("# T\r\n\r\n\r\n"));
        assertEquals("# T\r\n\r\nmore\r\n", fix("# T\r\n\r\n\r\nmore   \r\n"));
    }

    // --- C5 / C16: scope and line map ------------------------------------------------------------------

    @Test
    void aScopedFixTouchesOnlyThatIssue() {
        String md = "#A   \n\n\n\nb   \n";
        var first = MarkdownLint.lint(md).stream()
                .filter(d -> d.code().equals("MD009") && d.line() == 5)
                .findFirst()
                .orElseThrow();
        assertEquals(
                "#A   \n\n\n\nb\n",
                MarkdownLintFix.fix(md, Set.of(), 4, MarkdownLintFix.Scope.only(first))
                        .text());
        var blank = MarkdownLint.lint(md).stream()
                .filter(d -> d.code().equals("MD012") && d.line() == 3)
                .findFirst()
                .orElseThrow();
        assertEquals(
                "#A   \n\n\nb   \n",
                MarkdownLintFix.fix(md, Set.of(), 4, MarkdownLintFix.Scope.only(blank))
                        .text());
    }

    @Test
    void theLineMapFollowsCollapsedLines() {
        MarkdownLintFix.Result r = MarkdownLintFix.fix("a\n\n\n\nb\nc", Set.of(), 4, MarkdownLintFix.Scope.ALL);
        assertEquals("a\n\nb\nc\n", r.text());
        assertEquals(0, r.mapLine(0));
        assertEquals(1, r.mapLine(1));
        assertEquals(1, r.mapLine(2));
        assertEquals(1, r.mapLine(3));
        assertEquals(2, r.mapLine(4));
        assertEquals(3, r.mapLine(5));
        assertEquals(3, r.mapLine(99));
        assertEquals(
                0,
                MarkdownLintFix.fix("", Set.of(), 4, MarkdownLintFix.Scope.ALL).mapLine(3));
    }

    @Test
    void fixIsIdempotentOnAnAdversarialDocument() {
        String md =
                "x\n### h.\n#Bad:\n##  two\n  ## in\ntext\t  \n\n\n# A &amp;\n- l\n\n      code\t \n\n\n   ```\n\t x \n";
        String once = fix(md);
        assertEquals(once, fix(once));
        assertTrue(MarkdownLint.lint(once).stream().noneMatch(d -> MarkdownLint.fixable(d.code())), once);
    }
}
