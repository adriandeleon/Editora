package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The headings the outline lists, through the one shared plain-text helper ({@link MarkdownText#plain}). */
class MarkdownTextHeadingsTest {

    @Test
    void inlineMarkupIsRemovedAndItsTextKept() {
        assertEquals(
                "Bold and code and link title", MarkdownText.plain("**Bold** and `code` and [link](http://x.y) title"));
        assertEquals("both struck under", MarkdownText.plain("***both*** ~~struck~~ __under__"));
        assertEquals("em and em", MarkdownText.plain("*em* and _em_"));
        assertEquals("ref text and collapsed", MarkdownText.plain("[ref text][label] and [collapsed][]"));
        assertEquals("the badge alt", MarkdownText.plain("[![the badge alt](img.svg)](https://ci.example)"));
        assertEquals("wiki page", MarkdownText.plain("[wiki page](https://x.org/A_(b) \"t\")"));
        assertEquals("see https://example.com now", MarkdownText.plain("see <https://example.com> now"));
        assertEquals("Press Ctrl now", MarkdownText.plain("Press <kbd>Ctrl</kbd> now"));
    }

    @Test
    void whatIsNotMarkupIsLeftAlone() {
        assertEquals("C#", MarkdownText.plain("C#"));
        assertEquals("snake_case_name and 2 * 3 * 4", MarkdownText.plain("snake_case_name and 2 * 3 * 4"));
        assertEquals("a < b and c > d", MarkdownText.plain("a < b and c > d"));
        assertEquals("*not emphasis* [not a link]", MarkdownText.plain("\\*not emphasis\\* \\[not a link\\]"));
        assertEquals("Ünïcödé & Symbols! (v1.2)", MarkdownText.plain("Ünïcödé & Symbols! (v1.2)"));
        assertEquals("[just brackets]", MarkdownText.plain("[just brackets]"));
    }

    @Test
    void codeSpansAreLiteral() {
        assertEquals("the **kwargs and a_b_c", MarkdownText.plain("the `**kwargs` and `a_b_c`"));
        assertEquals("a ` tick", MarkdownText.plain("`` a ` tick ``"));
        assertEquals("<b> and [x](y)", MarkdownText.plain("`<b>` and `[x](y)`"));
    }

    @Test
    void theResultIsStrippedAndNullIsEmpty() {
        assertEquals("a b", MarkdownText.plain("  a b  "));
        assertEquals("", MarkdownText.plain(null));
        assertEquals("", MarkdownText.plain(""));
    }
}
