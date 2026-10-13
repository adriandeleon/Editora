package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MarkdownHeadingNumbersTest {

    @Test
    void sectionsUnderATitleAreNumberedAndTheTitleIsNot() {
        String text = "# Guide\n\n## Install\n\n### Linux\n\n### macOS\n\n## Use\n\n### Run\n";
        String numbered = MarkdownHeadingNumbers.number(text);
        assertEquals(
                "# Guide\n\n## 1. Install\n\n### 1.1. Linux\n\n### 1.2. macOS\n\n## 2. Use\n\n### 2.1. Run\n",
                numbered);
        assertSame(numbered, MarkdownHeadingNumbers.number(numbered), "already up to date");
        assertEquals(text, MarkdownHeadingNumbers.remove(numbered));
        assertSame(text, MarkdownHeadingNumbers.remove(text));
    }

    @Test
    void numbersAreBroughtUpToDateAfterSectionsMoved() {
        String moved = "# T\n\n## 2. Use\n\n### 2.1. Run\n\n## 1. Install\n\n## New\n";
        assertEquals(
                "# T\n\n## 1. Use\n\n### 1.1. Run\n\n## 2. Install\n\n## 3. New\n",
                MarkdownHeadingNumbers.number(moved));
    }

    @Test
    void severalTopHeadingsAreAllSections() {
        assertEquals("# 1. A\n\n## 1.1. a\n\n# 2. B\n", MarkdownHeadingNumbers.number("# A\n\n## a\n\n# B\n"));
        assertEquals("# 1. Only\n", MarkdownHeadingNumbers.number("# Only\n"));
    }

    @Test
    void setextHeadingsCodeAndFrontMatterAreHandled() {
        String text =
                "---\ntitle: x\n---\n\nTitle\n=====\n\nPart\n----\n\n```\n## not a heading\n```\n\n## Other ##\n\n#### Deep\n";
        assertEquals(
                "---\ntitle: x\n---\n\nTitle\n=====\n\n## 1. Part\n\n```\n## not a heading\n```\n\n## 2. Other ##\n\n#### 2.1.1. Deep\n",
                MarkdownHeadingNumbers.number(text));
    }

    @Test
    void emptyAndHeadinglessTextIsLeftAlone() {
        assertEquals("", MarkdownHeadingNumbers.number(""));
        assertEquals(null, MarkdownHeadingNumbers.number(null));
        String prose = "just text\n1. a list\n";
        assertSame(prose, MarkdownHeadingNumbers.number(prose));
    }
}
