package com.editora.markdown;

import java.util.List;

import com.editora.markdown.MarkdownBlocks.Fence;
import com.editora.markdown.MarkdownBlocks.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared Markdown block scanner: what is code, front matter or raw HTML, line by line. */
class MarkdownBlocksTest {

    private static final Kind T = Kind.TEXT;
    private static final Kind O = Kind.FENCE_OPEN;
    private static final Kind B = Kind.FENCE_BODY;
    private static final Kind C = Kind.FENCE_CLOSE;
    private static final Kind I = Kind.INDENTED_CODE;
    private static final Kind M = Kind.FRONT_MATTER;
    private static final Kind H = Kind.HTML_BLOCK;
    private static final Kind X = Kind.HTML_COMMENT;

    private static void assertKinds(String text, Kind... expected) {
        MarkdownBlocks b = MarkdownBlocks.scan(text);
        Kind[] actual = new Kind[b.lineCount()];
        for (int i = 0; i < actual.length; i++) {
            actual[i] = b.kind(i);
        }
        assertArrayEquals(expected, actual, text);
    }

    // --- fences ---------------------------------------------------------------------------------------

    @Test
    void backtickAndTildeFences() {
        assertKinds("a\n```js\nx\n```\nb", T, O, B, C, T);
        assertKinds("~~~\nx\n~~~", O, B, C);
        Fence f = MarkdownBlocks.scan("a\n```js\nx\n```\nb").fences().get(0);
        assertEquals(1, f.openLine());
        assertEquals(3, f.closeLine());
        assertEquals(3, f.lastLine());
        assertEquals('`', f.marker());
        assertEquals(3, f.length());
        assertEquals("js", f.info());
        assertTrue(f.closed());
    }

    @Test
    void theOtherFenceCharacterDoesNotClose() {
        assertKinds("~~~md\n```\n# not a heading\n```\n~~~\n", O, B, B, B, C, T);
    }

    @Test
    void aShorterRunDoesNotCloseALongerFence() {
        assertKinds("````md\n```js\nx\n```\n````", O, B, B, B, C);
        assertEquals(
                4,
                MarkdownBlocks.scan("````md\n```js\nx\n```\n````")
                        .fences()
                        .get(0)
                        .length());
    }

    @Test
    void aLongerRunCloses() {
        assertKinds("```\nx\n`````\ny", O, B, C, T);
    }

    @Test
    void aClosingFenceCarriesNoInfoString() {
        // "```js" inside an open block is code, not the end of it.
        assertKinds("```js\ncode\n```js\n# still code\n```\nafter", O, B, B, B, C, T);
        assertKinds("~~~\ncode\n~~~ \nafter", O, B, C, T); // trailing blanks are fine
    }

    @Test
    void aBacktickInTheInfoStringMeansNoFence() {
        assertKinds("```not a fence``` text\n# Heading", T, T);
        assertKinds("``` a`b\n# Heading", T, T);
        assertKinds("~~~ a`b\ncode\n~~~", O, B, C); // tildes have no such rule
    }

    @Test
    void anUnclosedFenceRunsToTheEnd() {
        MarkdownBlocks b = MarkdownBlocks.scan("# T\n\n```js\n# code\nmore");
        assertEquals(O, b.kind(2));
        assertEquals(B, b.kind(4));
        Fence f = b.fences().get(0);
        assertFalse(f.closed());
        assertEquals(-1, f.closeLine());
        assertEquals(4, f.lastLine());
    }

    @Test
    void upToThreeSpacesOfIndentIsStillAFence() {
        assertKinds("   ```\n   x\n   ```", O, B, C);
        assertKinds("text\n\n    ```\n    x\n    ```", T, T, I, I, I);
    }

    @Test
    void fenceInsideAListItem() {
        String text = "- item\n\n  ```js\n  # c\n  ```\n\n- next";
        assertKinds(text, T, T, O, B, C, T, T);
        Fence f = MarkdownBlocks.scan(text).fences().get(0);
        assertEquals(2, f.contentColumn());
        assertEquals(2, f.markerOffset());
    }

    @Test
    void fenceUnderAnOrderedItemWithFourColumnContent() {
        assertKinds(
                "1.  Run:\n\n    ```sh\n    #!/bin/sh\n    curl https://x.io\t| sh  \n    ```\n", T, T, O, B, B, C, T);
    }

    @Test
    void fenceNestedTwoListsDeep() {
        String text = "1. Step one\n"
                + "    - Create the Makefile:\n"
                + "\n"
                + "        ```make\n"
                + "        build:\n"
                + "        \tgcc -o app main.c   \n"
                + "\n"
                + "\n"
                + "        #comment\n"
                + "        ```\n"
                + "\n"
                + "2. Next\n";
        assertKinds(text, T, T, T, O, B, B, B, B, B, C, T, T, T);
        assertEquals(6, MarkdownBlocks.scan(text).fences().get(0).contentColumn());
    }

    @Test
    void fenceOnTheListMarkerLine() {
        assertKinds("- ```js\n  x\n  ```\n- b", O, B, C, T);
    }

    @Test
    void aFenceEndsWithItsListItem() {
        // The second item starts left of the first item's content column: the unclosed fence is over.
        String text = "- a\n\n  ```\n  code\n- b\n# H";
        assertKinds(text, T, T, O, B, T, T);
        Fence f = MarkdownBlocks.scan(text).fences().get(0);
        assertFalse(f.closed());
        assertEquals(3, f.lastLine());
    }

    @Test
    void fenceInsideABlockQuote() {
        String text = "> ```\n> # not a heading\n> http://x.io\n> ```\nafter";
        assertKinds(text, O, B, B, C, T);
        Fence f = MarkdownBlocks.scan(text).fences().get(0);
        assertEquals(1, f.quoteDepth());
        assertEquals(2, f.markerOffset());
    }

    @Test
    void aFenceEndsWithItsBlockQuote() {
        assertKinds("> ```\n> code\n\n# H", O, B, T, T);
        assertKinds("> ```\n> code\n# H", O, B, T);
    }

    @Test
    void aQuoteMarkerInsideATopLevelFenceIsCode() {
        assertKinds("```\n> not a quote\n```", O, B, C);
    }

    @Test
    void fenceInAListInsideABlockQuote() {
        assertKinds("> - item\n>\n>   ```\n>   x\n>   ```\n", T, T, O, B, C, T);
    }

    @Test
    void fenceAtFindsTheBlockOfAnyOfItsLines() {
        MarkdownBlocks b = MarkdownBlocks.scan("a\n```\nx\n```\nb");
        assertNull(b.fenceAt(0));
        assertNotNull(b.fenceAt(1));
        assertEquals(b.fenceAt(1), b.fenceAt(2));
        assertEquals(b.fenceAt(1), b.fenceAt(3));
        assertNull(b.fenceAt(4));
        assertNull(b.fenceAt(99));
    }

    // --- indented code --------------------------------------------------------------------------------

    @Test
    void indentedCodeNeedsAClosedParagraph() {
        assertKinds("Para\n\n    code\n    more\n\nPara", T, T, I, I, T, T);
        assertKinds("Para\n    continuation", T, T);
        assertKinds("# Heading\n    code", T, I);
        assertKinds("\tcode", I);
    }

    @Test
    void blankLinesInsideAnIndentedBlockBelongToIt() {
        assertKinds("# T\n\n    code\n\n\n    more\n\ntext", T, T, I, I, I, I, T, T);
    }

    @Test
    void indentedCodeInsideAListItem() {
        assertKinds("- item\n\n      code\n\n  text", T, T, I, T, T);
        assertKinds("- item\n\n    - nested", T, T, T); // four spaces under "- " is a nested item, not code
    }

    @Test
    void codeIsCodeAndVerbatim() {
        MarkdownBlocks b = MarkdownBlocks.scan("t\n\n    c\n\n```\nx\n```");
        assertFalse(b.isCode(0));
        assertTrue(b.isText(0));
        assertTrue(b.isCode(2));
        assertTrue(b.isCode(4));
        assertTrue(b.isCode(5));
        assertTrue(b.isCode(6));
        assertTrue(b.isVerbatim(5));
        assertFalse(b.isVerbatim(0));
    }

    // --- front matter ---------------------------------------------------------------------------------

    @Test
    void yamlFrontMatter() {
        assertKinds("---\ntitle: X\n# c\n---\n# T", M, M, M, M, T);
        assertEquals(3, MarkdownBlocks.scan("---\ntitle: X\n# c\n---\n# T").frontMatterEnd());
        assertKinds("---\ntitle: X\n...\n\n# T", M, M, M, T, T);
    }

    @Test
    void tomlFrontMatter() {
        assertKinds("+++\ntitle = \"X\"\n# c\n+++\n\n# T", M, M, M, M, T, T);
        assertKinds("+++\ntitle = 1\n---\n# T", T, T, T, T); // "---" does not close TOML
    }

    @Test
    void aFenceLookingLineInsideFrontMatterIsFrontMatter() {
        assertKinds("---\na: |\n  ```\n---\n# T", M, M, M, M, T);
    }

    @Test
    void frontMatterMustStartOnLineOneAndBeClosed() {
        assertKinds("\n---\ntitle: X\n---", T, T, T, T);
        assertEquals(-1, MarkdownBlocks.scan("---\ntitle: X\n").frontMatterEnd());
        assertEquals(-1, MarkdownBlocks.scan("").frontMatterEnd());
    }

    @Test
    void crlfLinesAreHandled() {
        assertKinds("---\r\nt: 1\r\n---\r\n```\r\nx\r\n```\r\n", M, M, M, O, B, C, T);
        assertEquals("```", MarkdownBlocks.scan("```\r\n").line(0));
    }

    // --- HTML -----------------------------------------------------------------------------------------

    @Test
    void multiLineHtmlComment() {
        String text = "# T\n\n<!--\n# hidden\n[a][b]\n-->\nafter";
        assertKinds(text, T, T, X, X, X, X, T);
        MarkdownBlocks b = MarkdownBlocks.scan(text);
        assertTrue(b.inHtmlComment(3, 0));
        assertArrayEquals(new int[] {0, 3}, b.htmlCommentRanges(5));
        assertFalse(b.inHtmlComment(6, 0));
    }

    @Test
    void singleLineAndInlineComments() {
        MarkdownBlocks b =
                MarkdownBlocks.scan("<!-- c -->\ntext <!-- a --> more <!-- b -->\nx <!-- open\nstill --> y\nz");
        assertEquals(X, b.kind(0));
        assertEquals(T, b.kind(1));
        assertArrayEquals(new int[] {5, 15, 21, 31}, b.htmlCommentRanges(1));
        assertTrue(b.inHtmlComment(1, 6));
        assertFalse(b.inHtmlComment(1, 16));
        assertEquals(T, b.kind(2));
        assertArrayEquals(new int[] {2, 11}, b.htmlCommentRanges(2));
        assertEquals(X, b.kind(3));
        assertArrayEquals(new int[] {0, 9}, b.htmlCommentRanges(3));
        assertEquals(T, b.kind(4));
    }

    @Test
    void aCommentOpenerInACodeSpanOrCodeBlockIsNotAComment() {
        assertKinds("`<!--` is how a comment starts\n# Heading", T, T);
        assertKinds("```html\n<!--\n```\n# Heading", O, B, C, T);
    }

    @Test
    void verbatimHtmlBlocks() {
        assertKinds("<pre>\na\tb   \n\n\n# x\n</pre>\nafter", H, H, H, H, H, H, T);
        assertKinds("<script>\nlet a = `x`;\n</script>\n# T", H, H, H, T);
        assertKinds("<STYLE type=\"text/css\">\n# id { }\n</style>", H, H, H);
        assertKinds("<pre>one line</pre>\n# T", H, T);
        assertKinds("<pretty>\n# T", T, T); // not a <pre>
    }

    @Test
    void blockLevelElementsRunToTheNextBlankLine() {
        assertKinds("<div>\n# not a heading\n</div>\n\n# T", H, H, H, T, T);
        assertKinds("text\n<table>\n<tr><td>x</td></tr>\n</table>\n\nafter", T, H, H, H, T, T);
        assertKinds("<p align=\"center\">\n  <img src=\"http://x.io/a.png\">\n</p>\n\n# T", H, H, H, T, T);
        assertKinds("<hr/>\n\n</div>\ntext", H, T, H, H);
        assertKinds("<H2>Title</H2>\n# T", H, H);
    }

    @Test
    void markdownResumesInsideDetailsAfterABlankLine() {
        // The usual collapsible section: the tags are HTML, the body between blank lines is Markdown.
        assertKinds(
                "<details>\n<summary>More</summary>\n\n## Inside\n\n```sh\nls\n```\n\n</details>\n",
                H,
                H,
                T,
                T,
                T,
                O,
                B,
                C,
                T,
                H,
                T);
    }

    @Test
    void inlineAndUnknownTagsDoNotOpenAnHtmlBlock() {
        assertKinds("<span>inline</span> text\n# T", T, T);
        assertKinds("<img src=\"a.png\">\n# T", T, T);
        assertKinds("<divider>\n# T", T, T);
        assertKinds("a < b and <div> later\n# T", T, T); // only at the start of the line
        assertKinds("    <div>\n", I, T); // four columns in: indented code
    }

    @Test
    void anHtmlBlockInAListItemOrABlockQuote() {
        assertKinds("- item\n\n  <div>\n  # x\n  </div>\n\n- next", T, T, H, H, H, T, T);
        assertKinds("> <div>\n> # x\n\n# T", H, H, T, T);
        assertKinds("> <div>\n> # x\n# T", H, H, T); // the quote ended, and the block with it
    }

    // --- container content ----------------------------------------------------------------------------

    @Test
    void contentStartsAfterTheContainerPrefix() {
        MarkdownBlocks b =
                MarkdownBlocks.scan("- # in list\n\n  # continuation\n> # quote\n  # top level\n1. a\n\n   ## h");
        assertEquals("# in list", b.content(0));
        assertEquals("# continuation", b.content(2));
        assertEquals("# quote", b.content(3));
        assertEquals(2, b.contentStart(3));
        assertEquals("  # top level", b.content(4)); // a lazy line: no container, its own indent kept
        assertEquals("## h", b.content(7));
        assertTrue(b.isBlank(1));
        assertTrue(b.isBlank(99));
    }

    @Test
    void blankLinesInsideAQuoteAreBlank() {
        MarkdownBlocks b = MarkdownBlocks.scan("> a\n>\n> b");
        assertTrue(b.isBlank(1));
        assertFalse(b.isBlank(2));
    }

    @Test
    void thematicBreaksAndEmphasisAreNotListItems() {
        MarkdownBlocks b = MarkdownBlocks.scan("* * *\n\n    code\n\n**bold** text\n\n    code\n\n---\n\n    code");
        assertEquals(I, b.kind(2));
        assertEquals(I, b.kind(6));
        assertEquals(I, b.kind(10));
        assertEquals("**bold** text", b.content(4));
    }

    // --- code spans -----------------------------------------------------------------------------------

    @Test
    void codeSpansMatchByRunLength() {
        assertArrayEquals(new int[] {0, 3}, MarkdownBlocks.codeSpans("`a` b"));
        assertArrayEquals(new int[] {4, 11, 17, 25}, MarkdownBlocks.codeSpans("Use `` ` `` then `[a][b]` here"));
        assertArrayEquals(new int[] {0, 14}, MarkdownBlocks.codeSpans("` ```mermaid ` fences"));
        assertArrayEquals(new int[] {2, 9}, MarkdownBlocks.codeSpans("a ``b`c`` d"));
    }

    @Test
    void unmatchedAndEscapedBackticksAreLiteral() {
        assertArrayEquals(new int[0], MarkdownBlocks.codeSpans("no code here"));
        assertArrayEquals(new int[0], MarkdownBlocks.codeSpans("a ` lonely backtick"));
        assertArrayEquals(new int[] {9, 12}, MarkdownBlocks.codeSpans("a `` and `b`")); // `` has no partner
        assertArrayEquals(new int[] {7, 10}, MarkdownBlocks.codeSpans("a \\` b `c`"));
        assertArrayEquals(new int[0], MarkdownBlocks.codeSpans(null));
    }

    @Test
    void codeSpansAreAskedPerLineAndOnlyForText() {
        MarkdownBlocks b = MarkdownBlocks.scan("a `b` c\n```\n`x`\n```");
        assertTrue(b.inCodeSpan(0, 2));
        assertTrue(b.inCodeSpan(0, 4));
        assertFalse(b.inCodeSpan(0, 5));
        assertFalse(b.inCodeSpan(0, 0));
        assertArrayEquals(new int[] {2, 5}, b.codeSpans(0));
        assertArrayEquals(new int[0], b.codeSpans(2)); // a code block has no spans
        assertFalse(b.inCodeSpan(42, 0));
    }

    @Test
    void codeSpanScanIsLinearOnALongLine() {
        String line = "`a` http://x.io [a][b] ".repeat(40_000);
        long start = System.nanoTime();
        int[] spans = MarkdownBlocks.codeSpans(line);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(80_000, spans.length);
        assertTrue(ms < 2_000, "took " + ms + " ms");
    }

    // --- misc -----------------------------------------------------------------------------------------

    @Test
    void emptyAndNullDocuments() {
        assertEquals(1, MarkdownBlocks.scan("").lineCount());
        assertEquals(1, MarkdownBlocks.scan((String) null).lineCount());
        assertEquals(0, MarkdownBlocks.scan((String[]) null).lineCount());
        assertEquals(T, MarkdownBlocks.scan("").kind(0));
        assertEquals(T, MarkdownBlocks.scan("").kind(-1));
        assertEquals(List.of(), MarkdownBlocks.scan("plain").fences());
    }

    @Test
    void inRangesIsHalfOpen() {
        int[] r = {2, 5, 9, 10};
        assertFalse(MarkdownBlocks.inRanges(r, 1));
        assertTrue(MarkdownBlocks.inRanges(r, 2));
        assertTrue(MarkdownBlocks.inRanges(r, 4));
        assertFalse(MarkdownBlocks.inRanges(r, 5));
        assertTrue(MarkdownBlocks.inRanges(r, 9));
        assertFalse(MarkdownBlocks.inRanges(r, 10));
    }
}
