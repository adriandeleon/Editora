package com.editora.markdown;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.editora.markdown.MarkdownLint.Diagnostic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure Markdown linter (one assertion group per rule). */
class MarkdownLintTest {

    private static Set<String> codes(String md) {
        return MarkdownLint.lint(md).stream().map(Diagnostic::code).collect(Collectors.toSet());
    }

    private static boolean has(String md, String code) {
        return MarkdownLint.lint(md).stream().anyMatch(d -> d.code().equals(code));
    }

    @Test
    void md009TrailingWhitespace() {
        List<Diagnostic> d = MarkdownLint.lint("ok line\ntrailing  \n");
        Diagnostic td =
                d.stream().filter(x -> x.code().equals("MD009")).findFirst().orElseThrow();
        assertEquals(2, td.line());
        assertEquals(9, td.column()); // "trailing" is 8 chars; first trailing space is column 9
    }

    @Test
    void md012MultipleBlankLines() {
        assertTrue(has("a\n\n\nb\n", "MD012"));
        assertFalse(has("a\n\nb\n", "MD012"));
    }

    @Test
    void md018And019HeadingSpacing() {
        assertTrue(has("#Heading\n", "MD018"));
        assertTrue(has("#   Heading\n", "MD019"));
        assertFalse(has("# Heading\n", "MD018"));
        assertFalse(has("# Heading\n", "MD019"));
    }

    @Test
    void md025MultipleTopLevelHeadings() {
        assertTrue(has("# One\n\n# Two\n", "MD025"));
        assertFalse(has("# One\n\n## Two\n", "MD025"));
    }

    @Test
    void md040FencedCodeNeedsLanguage() {
        assertTrue(has("```\ncode\n```\n", "MD040"));
        assertFalse(has("```java\ncode\n```\n", "MD040"));
    }

    @Test
    void md047FinalNewline() {
        assertTrue(has("# Title", "MD047")); // no trailing newline
        assertFalse(has("# Title\n", "MD047")); // exactly one
        assertTrue(has("# Title\n\n", "MD047")); // more than one
    }

    @Test
    void md052BrokenReferenceLink() {
        assertTrue(has("See [text][missing] here.\n", "MD052"));
        assertFalse(has("See [text][ok] here.\n\n[ok]: https://example.com\n", "MD052"));
    }

    @Test
    void noFalsePositivesInCleanDocument() {
        String md = "# Title\n\nSome text with a [link][ref].\n\n```java\nint x = 1;\n```\n\n[ref]: https://x.io\n";
        assertTrue(codes(md).isEmpty(), () -> "unexpected: " + codes(md));
    }

    @Test
    void emptyAndNull() {
        assertTrue(MarkdownLint.lint(null).isEmpty());
        assertTrue(MarkdownLint.lint("").isEmpty());
    }

    @Test
    void md001HeadingIncrement() {
        assertTrue(has("# A\n\n### C\n", "MD001"));
        assertFalse(has("# A\n\n## B\n", "MD001"));
    }

    @Test
    void md010HardTabs() {
        assertTrue(has("text\twith tab\n", "MD010"));
        assertFalse(has("text with spaces\n", "MD010"));
    }

    @Test
    void md022HeadingsSurroundedByBlanks() {
        assertTrue(has("# A\ntext\n", "MD022")); // heading not followed by a blank line
        assertFalse(has("# A\n\ntext\n", "MD022"));
    }

    @Test
    void md023HeadingIndent() {
        assertTrue(has("  ## Indented\n", "MD023"));
        assertFalse(has("## Flush\n", "MD023"));
    }

    @Test
    void md026HeadingTrailingPunctuation() {
        assertTrue(has("# Title.\n", "MD026"));
        assertTrue(has("## Done! ##\n", "MD026")); // closing-hash form
        assertFalse(has("# Title\n", "MD026"));
    }

    @Test
    void md031FencedCodeSurroundedByBlanks() {
        assertTrue(has("text\n\n```java\ncode\n```\ntext\n", "MD031")); // no blank after the fence
        assertFalse(has("text\n\n```java\ncode\n```\n\ntext\n", "MD031"));
    }

    @Test
    void md034BareUrl() {
        assertTrue(has("See https://example.com here.\n", "MD034"));
        assertFalse(has("See [x](https://example.com) here.\n", "MD034"));
        assertFalse(has("See <https://example.com> here.\n", "MD034"));
    }

    @Test
    void md034IgnoresUrlsThatAreAlreadyLinks() {
        // The URL used as its own link text, and URLs in inline-HTML attributes, are not bare.
        assertFalse(has("# T\n\nSee [https://x.io](https://x.io) here.\n", "MD034"));
        assertFalse(has("# T\n\n<a href=\"https://x.io/a\">docs</a>\n", "MD034"));
        assertFalse(has("# T\n\n<img src=\"https://x.io/a.png\" alt=\"a\">\n", "MD034"));
        // …but a bare URL next to a link, or after a comparison, still is.
        assertTrue(has("# T\n\n[docs](https://x.io) and https://y.io\n", "MD034"));
        assertTrue(has("# T\n\nif a < b see https://y.io > c\n", "MD034"));
    }

    @Test
    void md052IgnoresIndexingInsideInlineCode() {
        assertFalse(has("# T\n\nRead `m[i][j]` first.\n", "MD052"));
        assertTrue(has("# T\n\nRead `x` then [a][missing].\n", "MD052"));
    }

    @Test
    void md041FirstLineHeading() {
        assertTrue(has("Not a heading\n", "MD041"));
        assertFalse(has("# Heading\n", "MD041"));
        assertFalse(has("---\ntitle: x\n---\n\n# Heading\n", "MD041")); // front matter is skipped
    }

    @Test
    void perRuleDisabledSuppressesEmission() {
        assertFalse(MarkdownLint.lint("#Heading\n", Set.of("MD018")).stream()
                .anyMatch(d -> d.code().equals("MD018")));
    }

    @Test
    void inlineDisableDirectiveSuppresses() {
        assertFalse(MarkdownLint.lint("<!-- markdownlint-disable MD018 -->\n#Heading\n").stream()
                .anyMatch(d -> d.code().equals("MD018")));
        assertFalse(MarkdownLint.lint("<!-- markdownlint-disable-next-line MD018 -->\n#Heading\n").stream()
                .anyMatch(d -> d.code().equals("MD018")));
        assertTrue(has("#Heading\n", "MD018")); // still flagged without the directive
    }

    /**
     * Two trailing spaces are a Markdown hard line break (<br>), which upstream markdownlint allows by
     * default (br_spaces: 2). MD009 flagged them and the fixer stripped them — silently reflowing addresses,
     * poems and signature blocks into one paragraph.
     */
    @Test
    void md009LeavesAHardLineBreakAlone() {
        assertTrue(
                MarkdownLint.lint("Roses are red  \nViolets are blue\n").stream()
                        .noneMatch(d -> d.code().equals("MD009")),
                "exactly two trailing spaces before a non-blank line is a <br>");
        assertEquals(
                "Roses are red  \nViolets are blue\n",
                MarkdownLintFix.fix("Roses are red  \nViolets are blue\n", java.util.Set.of(), 4),
                "the fixer must not destroy it");
    }

    /** Everything that isn't a hard break stays flagged — and stays fixed. */
    @Test
    void md009StillFlagsRealTrailingWhitespace() {
        assertTrue(
                MarkdownLint.lint("three spaces   \nnext\n").stream()
                        .anyMatch(d -> d.code().equals("MD009")),
                "three spaces is not a break");
        assertTrue(
                MarkdownLint.lint("a tab\t\nnext\n").stream()
                        .anyMatch(d -> d.code().equals("MD009")),
                "a tab is not a break");
        assertTrue(
                MarkdownLint.lint("dangling  \n\n").stream()
                        .anyMatch(d -> d.code().equals("MD009")),
                "two spaces before a blank line breaks nothing");
        assertTrue(
                MarkdownLint.lint("last line  \n").stream()
                        .anyMatch(d -> d.code().equals("MD009")),
                "two spaces at end of document breaks nothing");
        assertEquals("three spaces\nnext\n", MarkdownLintFix.fix("three spaces   \nnext\n", java.util.Set.of(), 4));
    }

    private static List<String> at(String md) {
        return MarkdownLint.lint(md).stream()
                .map(d -> d.code() + "@" + d.line() + ":" + d.column() + "+" + d.length())
                .toList();
    }

    // --- C1: code at any nesting is never linted -------------------------------------------------------

    @Test
    void nothingIsReportedInsideAFenceNestedInLists() {
        String md = "# T\n\n1. Step one\n    - Create the Makefile:\n\n        ```make\n        build:\n"
                + "        \tgcc -o app main.c   \n\n\n        #comment http://x.io [a][b]\n        ```\n\n2. Next\n";
        assertEquals(List.of(), at(md));
    }

    @Test
    void nothingIsReportedInsideAFenceUnderAFourColumnListItem() {
        String md = "# T\n\n1.  Run:\n\n    ```sh\n    #!/bin/sh\n    curl https://example.com/i.sh\t| sh  \n    ```\n";
        assertEquals(List.of(), at(md));
    }

    @Test
    void nothingIsReportedInsideAnIndentedCodeBlock() {
        String md = "# T\n\nPara\n\n    # not a heading\n    http://x.io a[i][j]\n    a\tb   \n\n\n    more\n\nPara\n";
        assertEquals(List.of(), at(md));
    }

    @Test
    void nothingIsReportedInsideAFenceInABlockQuote() {
        assertEquals(List.of(), at("# T\n\n> ```text\n> # not a heading\n> http://x.io\n> ```\n"));
    }

    @Test
    void fenceRulesPointAtTheFenceWhereverItIsIndented() {
        assertEquals(List.of("MD040@5:3+3", "MD031@7:3+3"), at("# T\n\n- item\n\n  ```\n  code\n  ```\n  text\n"));
    }

    // --- C6: closing fences and info strings -----------------------------------------------------------

    @Test
    void aFenceLineWithAnInfoStringDoesNotCloseTheBlock() {
        assertEquals(List.of(), at("# T\n\n```js\ncode\n```js\n# still code\t\n```\n"));
    }

    @Test
    void aLineStartingWithAnInlineTripleBacktickSpanDoesNotOpenAFence() {
        String md = "# T\n\n```not a fence``` text\n\n# Second\n";
        assertEquals(List.of("MD025@5:1+1"), at(md));
    }

    // --- C8: code spans by run length ------------------------------------------------------------------

    @Test
    void multiBacktickSpansDoNotFlipCodeAndProse() {
        assertEquals(List.of(), at("# T\n\nUse `` ` `` then `[+-]?d[.d][e±n]` and `http://x.io` here\n"));
        assertEquals(List.of("MD034@3:17+11"), at("# T\n\nUse `` ` `` and http://x.io here\n"));
    }

    // --- C9: raw HTML ----------------------------------------------------------------------------------

    @Test
    void commentedOutTextIsNotLinted() {
        assertEquals(List.of(), at("# T\n\n<!--\n# hidden\nhttp://x.io\n[a][b]\n-->\n"));
        assertEquals(List.of(), at("# T\n\ntext <!-- http://x.io [a][b] --> more\n"));
    }

    @Test
    void preBlocksAreNotLinted() {
        assertEquals(List.of(), at("# T\n\n<pre>\na\tb   \n\n\n# x\n</pre>\n"));
    }

    @Test
    void urlsInAMultiLineTagOrInsideAnAnchorAreNotBare() {
        assertEquals(List.of(), at("# T\n\n<a\n  href=\"http://a.io\">x</a>\n"));
        assertEquals(List.of(), at("# T\n\n<img\n  alt=\"x\"\n  src=\"https://a.io/a.png\">\n"));
        assertEquals(List.of(), at("# T\n\n<a href=\"http://x.io\">http://x.io</a>\n"));
        assertEquals(List.of("MD034@4:19+11"), at("# T\n\n<a\n  href=\"/x\">x</a> http://b.io\n"));
    }

    // --- C10: front matter -----------------------------------------------------------------------------

    @Test
    void tomlFrontMatterIsSkipped() {
        assertEquals(List.of(), at("+++\ndraft = true\n# c\nurl = \"http://x.io\"   \n+++\n\n# T\n"));
    }

    @Test
    void yamlFrontMatterClosedByDotsAndFenceLookalikesInsideIt() {
        assertEquals(List.of(), at("---\ndraft: true\n...\n\n# T\n"));
        assertEquals(List.of(), at("---\na: |\n  ```\n---\n# T\n"));
    }

    // --- C2: MD041 / MD025 and titles ------------------------------------------------------------------

    @Test
    void aFrontMatterTitleIsTheTopLevelHeading() {
        assertEquals(List.of(), at("---\ntitle: X\n---\n\nSome text.\n"));
        assertEquals(List.of(), at("+++\ntitle = \"X\"\n+++\n\nSome text.\n"));
        assertEquals(List.of("MD025@5:1+1"), at("---\ntitle: X\n---\n\n# T\n"));
        assertEquals(List.of("MD041@5:1+10"), at("---\ndraft: true\n---\n\nSome text.\n"));
    }

    @Test
    void aFrontMatterCorpusWithTitlesIsClean() {
        String[] bodies = {
            "Intro paragraph.\n\n## Section\n\nText.\n",
            "![cover](a.png)\n\nText.\n",
            "## Straight to a section\n\nText.\n",
            "<!-- note -->\n\nText with a [link](https://x.io).\n"
        };
        String[] fronts = {
            "---\ntitle: A page\ndescription: d\n---\n\n",
            "---\nlayout: post\ntitle: \"Quoted\"\ntags: [a, b]\n---\n",
            "+++\ntitle = \"Hugo\"\ndate = 2026-01-01\n+++\n\n",
            "---\n\"title\": json-ish\n...\n\n"
        };
        for (String front : fronts) {
            for (String body : bodies) {
                assertFalse(has(front + body, "MD041"), front + body);
                assertFalse(has(front + body, "MD025"), front + body);
            }
        }
    }

    @Test
    void setextAndHtmlTitlesAndLeadingCommentsSatisfyMd041() {
        assertEquals(List.of(), at("Title\n=====\n\ntext\n"));
        assertEquals(List.of(), at("<h1 align=\"center\">X</h1>\n\ntext\n"));
        assertEquals(List.of(), at("<!--\nlicense\n-->\n\n# T\n"));
        assertEquals(List.of("MD041@1:1+5"), at("Title\n-----\n\ntext\n")); // an H2 is not a title
    }

    // --- C7: MD026 -------------------------------------------------------------------------------------

    @Test
    void md026LeavesEntitiesAndFlagsFullWidthPunctuation() {
        assertFalse(has("# T\n\n## Entity &copy;\n\n## Entity &amp;\n\n## Num &#169;\n\n## Hex &#xA9;\n", "MD026"));
        assertTrue(has("# T\n\n## Not an entity;\n", "MD026"));
        assertEquals(List.of("MD026@3:8+1"), at("# T\n\n## Full。\n"));
        assertEquals(List.of("MD026@3:9+1"), at("# T\n\n## Colon：\n"));
    }

    // --- C18 -------------------------------------------------------------------------------------------

    @Test
    void md010ReportsEveryTabRun() {
        assertEquals(List.of("MD010@3:2+1", "MD010@3:4+2"), at("# T\n\na\tb\t\tc\n"));
    }

    @Test
    void aHashTagContinuingAParagraphIsNotAHeading() {
        assertEquals(List.of(), at("# T\n\nSee\n#123 for details\n#tag too\n"));
        assertEquals(List.of("MD018@3:1+1"), at("# T\n\n#Title\n")); // a line of its own still is
        assertEquals(
                "# T\n\nSee\n#123 for details\n", MarkdownLintFix.fix("# T\n\nSee\n#123 for details\n", Set.of(), 4));
    }

    @Test
    void aHeadingAtItsListItemsContentColumnIsNotIndented() {
        assertFalse(has("# T\n\n- item\n\n  ## In the item\n\n1. x\n\n   ## Also\n", "MD023"));
        assertEquals(List.of("MD023@5:3+1"), at("# T\n\n- item\n\n   ## One too many\n"));
    }

    @Test
    void md034RangeStopsBeforeSentencePunctuationAndKeepsBalancedParentheses() {
        assertEquals(List.of("MD034@3:5+11"), at("# T\n\nSee http://k.io.\n"));
        assertEquals(List.of("MD034@3:6+11"), at("# T\n\nSee \"http://j.io\", ok\n"));
        assertEquals(List.of("MD034@3:1+17"), at("# T\n\nhttp://q.io/a_(b) x\n"));
        assertEquals(List.of("MD034@3:5+11"), at("# T\n\nfoo http://x.io) bar\n"));
    }

    @Test
    void md034KnowsLinkDestinationsAndDefinitionsWrittenLoosely() {
        assertEquals(List.of(), at("# T\n\n[text](  http://s.io ) [t](<http://t.io>)\n"));
        assertEquals(List.of(), at("# T\n\n[label]:\n  http://b.io\n"));
    }

    @Test
    void md052EscapesCollapsedReferencesAndWhereDefinitionsCount() {
        assertEquals(List.of(), at("# T\n\n\\[not\\][ref] is literal\n"));
        assertEquals(List.of("MD052@3:1+5"), at("# T\n\n[c][] collapsed\n"));
        assertEquals(List.of(), at("# T\n\n[c][] collapsed\n\n[c]: /u\n"));
        assertEquals(List.of("MD052@3:1+6"), at("# T\n\n[a][b]\n\n```text\n[b]: http://x.io\n```\n"));
        assertEquals(List.of(), at("# T\n\n[x][y]\n\n[y]:\n  /multi\n"));
    }

    @Test
    void aFileThatIsOnlyANewlineIsClean() {
        assertEquals(List.of(), at("\n"));
        assertEquals("\n", MarkdownLintFix.fix("\n", Set.of(), 4));
        assertEquals("", MarkdownLintFix.fix("\n\n\n", Set.of(), 4));
    }

    // --- C13: ranges -----------------------------------------------------------------------------------

    @Test
    void structuralDiagnosticsCoverSomethingVisible() {
        assertEquals(List.of("MD041@1:1+11"), at("Not a title\n"));
        assertEquals(List.of("MD001@3:1+3"), at("# A\n\n### C\n"));
        assertEquals(List.of("MD025@3:1+1"), at("# A\n\n# B\n"));
        assertEquals(
                List.of("MD025@4:1+7"),
                at("# A\n\n\nTitle 2\n=======\n").stream()
                        .filter(s -> s.startsWith("MD025"))
                        .toList());
        assertEquals(List.of("MD047@1:3+1"), at("# T")); // at the end of the last line
    }

    // --- C15: catalog keys -----------------------------------------------------------------------------

    @Test
    void everyDiagnosticCarriesACatalogKeyAndItsEnglishText() {
        String md = "x\n### h.\n#Bad\n##  two\n  ## in\ntext\t  \n\n\n# A\n### deep\n# B\n```\n```\nhttp://x.io [a][b]";
        List<Diagnostic> all = MarkdownLint.lint(md);
        Set<String> seen = all.stream().map(Diagnostic::code).collect(Collectors.toSet());
        assertEquals(MarkdownLint.CODES, seen, "the sample trips every rule");
        for (Diagnostic d : all) {
            assertTrue(MarkdownLint.messageKeys().contains(d.key()), d.toString());
            assertFalse(d.message().isBlank());
        }
    }

    @Test
    void namesResolveToRules() {
        assertEquals(Set.of("MD009"), MarkdownLint.resolve("no-trailing-spaces"));
        assertEquals(Set.of("MD009"), MarkdownLint.resolve(" md009 "));
        assertEquals(Set.of("MD009", "MD010", "MD012"), MarkdownLint.resolve("whitespace"));
        assertEquals(Set.of("MD033"), MarkdownLint.resolve("MD033")); // not implemented, still only itself
        assertEquals(Set.of(), MarkdownLint.resolve("no-inline-html"));
        assertEquals(Set.of(), MarkdownLint.resolve(null));
        assertTrue(MarkdownLint.fixable("MD009"));
        assertFalse(MarkdownLint.fixable("MD034"));
        assertFalse(MarkdownLint.fixable("MD999"));
    }

    // --- C17: long lines stay linear -------------------------------------------------------------------

    @Test
    void pathologicalLongLinesLintQuickly() {
        String spans = "# T\n\n" + "`a` http://x.io [a][b] ".repeat(20_000) + "\n";
        String brackets = "# T\n\n" + "[".repeat(50_000) + "\n";
        String tags = "# T\n\n" + "<a http://x.io ".repeat(20_000) + "\n";
        long start = System.nanoTime();
        assertEquals(40_001, MarkdownLint.lint(spans).size()); // + the trailing space
        MarkdownLint.lint(brackets);
        MarkdownLint.lint(tags);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 3_000, "took " + ms + " ms (was ~10 s)");
    }
}
