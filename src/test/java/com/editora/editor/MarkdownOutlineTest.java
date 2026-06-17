package com.editora.editor;

import java.util.List;

import com.editora.editor.MarkdownOutline.Heading;
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
}
