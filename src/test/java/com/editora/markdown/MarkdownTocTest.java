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

    @Test
    void anEntryShowsTheHeadingsPlainTextAndLinksToItsAnchor() {
        // The raw title used to go in: a link nested in the entry's link, and an anchor made from the URL.
        assertEquals("- [A link b](#a-link-b)\n", MarkdownToc.build("## A [link](http://x) b"));
        assertEquals("- [code & em u](#code--em-u)\n", MarkdownToc.build("## `code` & *em* _u_"));
        assertEquals("- [html](#html)\n", MarkdownToc.build("## <b>html</b>"));
        assertEquals("- [C++ / C# & \\[brackets\\]](#c--c--brackets)\n", MarkdownToc.build("## C++ / C# & [brackets]"));
        assertEquals("- [pic alt](#pic-alt)\n", MarkdownToc.build("## pic ![alt](x.png)"));
    }

    @Test
    void entriesNestByTheHeadingsAboveThemNotByRawLevel() {
        // Two spaces per level skipped made an indented code block of "    - [a]" and of an H1 → H5 jump.
        assertEquals("- [a](#a)\n- [b](#b)\n  - [c](#c)\n", MarkdownToc.build("### a\n# b\n##### c\n"));
        assertEquals("- [a](#a)\n  - [b](#b)\n  - [c](#c)\n", MarkdownToc.build("# a\n#### b\n## c\n"));
    }

    @Test
    void anchorsCountEveryHeadingEvenTheOnesNotListed() {
        assertEquals("- [Notes](#notes-1)\n", MarkdownToc.build("# Notes\n## Notes", 2, 6));
    }

    @Test
    void theHeadingTheTocSitsUnderIsNotAnEntry() {
        String doc = "# Doc\n## Contents\n\n<!-- toc -->\n<!-- /toc -->\n## A\n";
        assertEquals(
                "# Doc\n## Contents\n\n<!-- toc -->\n- [Doc](#doc)\n  - [A](#a)\n<!-- /toc -->\n## A\n",
                MarkdownToc.updated(doc, 1, 6));
        String fresh = "# Doc\n\n## Contents\n\n## A\n";
        assertEquals(
                "<!-- toc -->\n- [Doc](#doc)\n  - [A](#a)\n<!-- /toc -->",
                MarkdownToc.insertion(fresh, fresh.indexOf("\n## A"), 1, 6));
        assertEquals("", MarkdownToc.insertion("text", 0, 1, 6));
    }

    @Test
    void aMarkerInAFencedBlockIsAnExample() {
        String doc = "# T\n```\n<!-- toc -->\nexample\n<!-- /toc -->\n```\n## S\n";
        assertNull(MarkdownToc.updated(doc, 1, 6));
        String both = doc + "<!-- toc -->\n<!-- /toc -->\n";
        assertTrue(
                MarkdownToc.updated(both, 1, 6).startsWith(doc + "<!-- toc -->\n- [T](#t)"), "the real one is updated");
    }

    @Test
    void markersMatchWhateverTheirCaseAndSpacing() {
        assertEquals(
                "<!-- toc -->\n- [T](#t)\n<!-- /toc -->\n# T",
                MarkdownToc.updated("<!--TOC-->\n<!--/toc -->\n# T", 1, 6));
    }

    @Test
    void anOpeningMarkerWithoutItsEndTakesTheListUnderItAsTheStaleToc() {
        assertEquals(
                "<!-- toc -->\n- [T](#t)\n<!-- /toc -->\n\n# T\n",
                MarkdownToc.updated("<!-- toc -->\n- [Old](#old)\n  - [Gone](#gone)\n\n# T\n", 1, 6));
    }

    @Test
    void theUpdateIsAnEditOfTheBlockOnly() {
        String doc = "intro\n<!-- toc -->\nold\n<!-- /toc -->\n# T\n";
        MarkdownEdit e = MarkdownToc.update(doc, 1, 6);
        assertEquals(6, e.from());
        assertEquals(doc.indexOf("\n# T"), e.to());
    }

    @Test
    void theTitleCanBeLeftOut() {
        MarkdownToc.Style noTitle = new MarkdownToc.Style(1, 6, true, false);
        assertEquals("- [A](#a)\n  - [B](#b)\n", MarkdownToc.build("# Doc\n## A\n### B\n", -1, noTitle));
        assertEquals(
                "- [One](#one)\n- [Two](#two)\n",
                MarkdownToc.build("# One\n# Two\n", -1, noTitle),
                "two level-1 headings: neither is the title");
        assertEquals(
                "- [A](#a)\n- [T](#t)\n", MarkdownToc.build("## A\n# T\n", -1, noTitle), "nor one that is not first");
    }

    @Test
    void entriesCanBeNumbered() {
        MarkdownToc.Style numbered = new MarkdownToc.Style(1, 6, false, true);
        assertEquals(
                "1. [A](#a)\n    1. [B](#b)\n    2. [C](#c)\n2. [D](#d)\n    1. [E](#e)\n",
                MarkdownToc.build("# A\n## B\n## C\n# D\n## E\n", -1, numbered));
        // A numbered block is recognised as the stale TOC under an opening marker without its end.
        assertEquals(
                "<!-- toc -->\n1. [T](#t)\n<!-- /toc -->\n\n# T\n",
                MarkdownToc.update("<!-- toc -->\n1. [Old](#old)\n\n# T\n", numbered)
                                .replacement() + "\n\n# T\n");
    }
}
