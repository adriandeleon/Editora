package com.editora.markdown;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkdownAnchorsTest {

    private static final String DOC = "---\ntitle: x\n---\n\n# Guide\n\n## Set up\n\n```\n# not a heading\n```\n\n"
            + "## Set up\n\nSetext Title\n============\n\n#\n\n## Ünïcödé & Symbols! (v1.2)\n\n"
            + "<a name=\"legacy\"></a>\ntext <span id='inline-id'>x</span>\n";

    @Test
    void aFragmentNamesTheHeadingWithThatSlug() {
        assertEquals(4, MarkdownAnchors.lineOf(DOC, "guide"));
        assertEquals(4, MarkdownAnchors.lineOf(DOC, "#guide"));
        assertEquals(4, MarkdownAnchors.lineOf(DOC, "Guide"), "anchors are matched without regard to case");
        assertEquals(6, MarkdownAnchors.lineOf(DOC, "set-up"));
        assertEquals(12, MarkdownAnchors.lineOf(DOC, "set-up-1"), "the second heading of the same name");
        assertEquals(14, MarkdownAnchors.lineOf(DOC, "setext-title"));
        assertEquals(19, MarkdownAnchors.lineOf(DOC, "ünïcödé--symbols-v12"));
    }

    @Test
    void anHtmlAnchorIsFoundWhenNoHeadingMatches() {
        assertEquals(21, MarkdownAnchors.lineOf(DOC, "legacy"));
        assertEquals(22, MarkdownAnchors.lineOf(DOC, "inline-id"));
    }

    @Test
    void anUnknownOrEmptyFragmentIsNotFound() {
        assertEquals(-1, MarkdownAnchors.lineOf(DOC, "nowhere"));
        assertEquals(-1, MarkdownAnchors.lineOf(DOC, "not-a-heading"));
        assertEquals(-1, MarkdownAnchors.lineOf(DOC, "#"));
        assertEquals(-1, MarkdownAnchors.lineOf(DOC, ""));
        assertEquals(-1, MarkdownAnchors.lineOf(null, "guide"));
        assertEquals(-1, MarkdownAnchors.lineOf(DOC, null));
    }

    /** The point of sharing the slug sequence: every entry of the generated table of contents resolves. */
    @Test
    void everyEntryOfTheGeneratedTableOfContentsResolvesToItsHeading() {
        String toc = MarkdownToc.build(DOC);
        Matcher m = Pattern.compile("\\(#([^)]*)\\)\\n").matcher(toc);
        int entries = 0;
        int last = -1;
        while (m.find()) {
            int line = MarkdownAnchors.lineOf(DOC, m.group(1));
            assertEquals(true, line > last, "entry #" + m.group(1) + " resolves, in document order");
            last = line;
            entries++;
        }
        assertEquals(5, entries);
    }

    /** A heading with markup is reachable by the anchor of its source text and by the anchor of what is read. */
    @Test
    void aHeadingWithMarkupAnswersToBothOfItsAnchors() {
        String doc = "# Plain\n\n## The `code` and [link](http://x.y) part\n";
        assertEquals(2, MarkdownAnchors.lineOf(doc, "the-code-and-linkhttpxy-part"), "as the TOC has written it");
        assertEquals(2, MarkdownAnchors.lineOf(doc, "the-code-and-link-part"), "as GitHub and the exports name it");
    }
}
