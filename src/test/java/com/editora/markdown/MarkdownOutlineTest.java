package com.editora.markdown;

import java.util.List;

import com.editora.markdown.MarkdownOutline.Heading;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure Markdown heading-outline extractor. */
class MarkdownOutlineTest {

    @Test
    void atxHeadingsWithLevelsAndLines() {
        List<Heading> h = MarkdownOutline.headings("# Title\n\ntext\n\n## Sub\n### Deep\n");
        assertEquals(3, h.size());
        assertEquals(new Heading(1, "Title", 0), h.get(0));
        assertEquals(new Heading(2, "Sub", 4), h.get(1));
        assertEquals(new Heading(3, "Deep", 5), h.get(2));
    }

    @Test
    void stripsClosingHashes() {
        assertEquals("Title", MarkdownOutline.headings("## Title ##").get(0).title());
    }

    @Test
    void requiresSpaceAfterHashes() {
        // "#tag" is not a heading; "######" with >6 hashes isn't either.
        assertTrue(MarkdownOutline.headings("#notaheading\n").isEmpty());
        assertTrue(MarkdownOutline.headings("####### too many\n").isEmpty());
    }

    @Test
    void setextHeadings() {
        List<Heading> h = MarkdownOutline.headings("Title\n=====\n\nSub\n---\n");
        assertEquals(2, h.size());
        assertEquals(new Heading(1, "Title", 0), h.get(0));
        assertEquals(new Heading(2, "Sub", 3), h.get(1));
    }

    @Test
    void skipsHashesInsideFencedCode() {
        String md = "# Real\n\n```\n# not a heading\n## also not\n```\n\n## After\n";
        List<Heading> h = MarkdownOutline.headings(md);
        assertEquals(List.of(new Heading(1, "Real", 0), new Heading(2, "After", 7)), h);
    }

    @Test
    void skipsLeadingYamlFrontMatter() {
        String md = "---\ntitle: Hello\ntags: a\n---\n\n# Body\n";
        List<Heading> h = MarkdownOutline.headings(md);
        assertEquals(List.of(new Heading(1, "Body", 5)), h);
    }

    @Test
    void emptyAndNull() {
        assertTrue(MarkdownOutline.headings(null).isEmpty());
        assertTrue(MarkdownOutline.headings("").isEmpty());
        assertTrue(MarkdownOutline.headings("just text\nno headings\n").isEmpty());
    }

    /**
     * A setext underline may only underline a *paragraph*. After a list item, `---` is a thematic break —
     * CommonMark's own example ("- Foo\n---" is a list plus an <hr>) — but it was read as an H2 titled with
     * the list text, which then polluted the Structure outline and the generated TOC.
     */
    @Test
    void dashesAfterAListItemAreAThematicBreakNotASetextHeading() {
        assertEquals(
                List.of(new Heading(1, "Real", 3)), MarkdownOutline.headings("- item one\n- item two\n---\n# Real\n"));
        assertEquals(List.of(new Heading(1, "Real", 3)), MarkdownOutline.headings("1. one\n2. two\n---\n# Real\n"));
        assertEquals(List.of(new Heading(1, "Real", 2)), MarkdownOutline.headings("> quoted\n---\n# Real\n"));
    }

    /** The real setext case — a paragraph line underlined — must still be detected. */
    @Test
    void setextUnderAParagraphIsStillAHeading() {
        assertEquals(List.of(new Heading(2, "Title", 0)), MarkdownOutline.headings("Title\n---\n"));
        assertEquals(List.of(new Heading(1, "Title", 0)), MarkdownOutline.headings("Title\n===\n"));
    }
}
