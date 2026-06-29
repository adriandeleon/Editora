package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTocTest {

    @Test
    void buildsNestedListOfHeadingLinks() {
        String md = "# Title\n\nintro\n\n## Setup\n\n### Details\n\n## Usage";
        assertEquals("""
                - [Title](#title)
                  - [Setup](#setup)
                    - [Details](#details)
                  - [Usage](#usage)
                """, MarkdownToc.build(md));
    }

    @Test
    void shallowestHeadingStartsFlushLeft() {
        // top heading is ## → it should not be indented
        String md = "## A\n\n### B";
        assertEquals("- [A](#a)\n  - [B](#b)\n", MarkdownToc.build(md));
    }

    @Test
    void slugIsGitHubStyleAndDeduped() {
        assertEquals("hello-world", MarkdownToc.slug("Hello, World!"));
        assertEquals("café-au_lait", MarkdownToc.slug("Café au_lait")); // letters kept (incl. accents), space → hyphen
        String md = "# Notes\n## Notes\n## Notes";
        assertEquals("- [Notes](#notes)\n  - [Notes](#notes-1)\n  - [Notes](#notes-2)\n", MarkdownToc.build(md));
    }

    @Test
    void skipsFencedCodeAndReturnsEmptyWhenNoHeadings() {
        assertEquals("", MarkdownToc.build("just text\n\nmore text"));
        assertEquals("- [Real](#real)\n", MarkdownToc.build("# Real\n\n```\n# not a heading\n```"));
    }

    @Test
    void respectsLevelRange() {
        String md = "# H1\n## H2\n### H3";
        assertEquals("- [H2](#h2)\n  - [H3](#h3)\n", MarkdownToc.build(md, 2, 6));
    }

    @Test
    void updatesExistingMarkerBlockInPlace() {
        String doc = "# Doc\n\n" + MarkdownToc.BEGIN + "\n- [stale](#stale)\n" + MarkdownToc.END + "\n\n## New\n";
        String out = MarkdownToc.updated(doc, 1, 6);
        assertTrue(out.contains(MarkdownToc.BEGIN + "\n- [Doc](#doc)\n  - [New](#new)\n" + MarkdownToc.END), out);
        // the stale entry is gone and there is exactly one marker block
        assertEquals(out.indexOf(MarkdownToc.BEGIN), out.lastIndexOf(MarkdownToc.BEGIN));
        assertTrue(!out.contains("stale"), out);
    }

    @Test
    void updatedReturnsNullWithoutMarkers() {
        assertNull(MarkdownToc.updated("# Doc\n\ntext", 1, 6));
        assertNull(MarkdownToc.updated(null, 1, 6));
    }
}
