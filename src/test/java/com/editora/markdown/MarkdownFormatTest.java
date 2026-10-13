package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MarkdownFormatTest {

    private static String format(String text) {
        return MarkdownFormat.format(text, 4, MarkdownLintOptions.DEFAULT).text();
    }

    private static void assertStable(String formatted) {
        assertEquals(formatted, format(formatted), "formatting a formatted document changes nothing");
    }

    @Test
    void aTidyDocumentIsReturnedAsItIs() {
        String text =
                "# Title\n\nSome *text* and **more**.\n\n- one\n- two\n\n1. a\n2. b\n\n| a   | b   |\n| --- | --- |\n| 1   | 2   |\n";
        assertSame(
                text,
                MarkdownFormat.format(text, 4, MarkdownLintOptions.DEFAULT).text());
    }

    @Test
    void headingsBecomeAtxWithBlankLinesAround() {
        String out = format("Title\n=====\ntext\n##  Two  \nmore\n\nSub\n---\nend\n");
        assertEquals("# Title\n\ntext\n\n## Two\n\nmore\n\n## Sub\n\nend\n", out);
        assertStable(out);
    }

    @Test
    void listMarkersAndEmphasisAreMadeConsistent() {
        String out = format("* one\n- two\n+ three\n\nSome _em_ and *em* and __strong__ and **strong**.\n");
        assertEquals("* one\n* two\n* three\n\nSome _em_ and _em_ and __strong__ and __strong__.\n", out);
        assertStable(out);
    }

    @Test
    void orderedListsAreNumberedInSequence() {
        String out = format("3. a\n7. b\n   1. x\n   5. y\n9. c\n");
        assertEquals("3. a\n4. b\n   1. x\n   2. y\n5. c\n", out);
        assertStable(out);
    }

    @Test
    void tablesAreAlignedAndGetBlankLinesAround() {
        String out = format("text\n|a|bbbb|\n|-|:-:|\n|long cell|x|\n\nafter\n");
        assertEquals("text\n\n| a         | bbbb |\n| --------- | :--: |\n| long cell |  x   |\n\nafter\n", out);
        assertStable(out);
    }

    @Test
    void fencesGetBlankLinesAndWhitespaceIsTrimmed() {
        String out = format("text   \n```java\ncode   \n```\nafter\n\n\n\nlast");
        assertEquals("text\n\n```java\ncode   \n```\n\nafter\n\nlast\n", out);
        assertStable(out);
    }

    @Test
    void codeFrontMatterAndHtmlAreNeverTouched() {
        String text =
                "---\ntitle: x   \nTitle\n=====\n---\n\n```\nTitle\n=====\n* a\n- b\n|a|b|\n|-|-|\n__x__ *y*   \n```\n\n"
                        + "    Title\n    =====\n    3. a\n    9. b\n\n<div>\n* a\n- b\n__x__   \n</div>\n\n<!--\n3. a\n9. b\n-->\n";
        assertEquals(text, format(text));
    }

    @Test
    void nestedAndQuotedBlocksKeepTheirContainer() {
        String text = "- item\n  ```\n  code\n  ```\n  more\n\n> quote\n> ```\n> code\n> ```\n> end\n";
        assertEquals(text, format(text));
    }

    @Test
    void aSelectionFormatsOnlyItsLines() {
        String text = "Title\n=====\n* a\n- b\n\nSub\n---\n* c\n- d\n";
        MarkdownLintFix.Result r = MarkdownFormat.format(text, 4, MarkdownLintOptions.DEFAULT, 5, 6);
        assertEquals("Title\n=====\n* a\n- b\n\n## Sub\n\n* c\n- d\n", r.text());
        assertEquals(5, r.mapLine(5));
        assertEquals(5, r.mapLine(6), "the underline went with its heading");
        assertEquals(7, r.mapLine(7));
    }

    @Test
    void theLineMapFollowsInsertedAndMergedLines() {
        MarkdownLintFix.Result r =
                MarkdownFormat.format("Title\n=====\ntext\n## Two\nmore\n", 4, MarkdownLintOptions.DEFAULT);
        assertEquals("# Title\n\ntext\n\n## Two\n\nmore\n", r.text());
        assertEquals(0, r.mapLine(0));
        assertEquals(2, r.mapLine(2));
        assertEquals(4, r.mapLine(3));
        assertEquals(6, r.mapLine(4));
    }

    @Test
    void emptyAndNullTextAreLeftAlone() {
        assertEquals("", format(""));
        assertEquals(null, format(null));
    }
}
