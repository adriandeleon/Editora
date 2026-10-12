package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutlineTitlesTest {

    @Test
    void inlineMarkupIsRemovedAndItsTextKept() {
        assertEquals(
                "Bold and code and link title",
                OutlineTitles.plain("**Bold** and `code` and [link](http://x.y) title"));
        assertEquals("both struck under", OutlineTitles.plain("***both*** ~~struck~~ __under__"));
        assertEquals("em and em", OutlineTitles.plain("*em* and _em_"));
        assertEquals("ref text and collapsed", OutlineTitles.plain("[ref text][label] and [collapsed][]"));
        assertEquals("the badge alt", OutlineTitles.plain("[![the badge alt](img.svg)](https://ci.example)"));
        assertEquals("wiki A_(b) page", OutlineTitles.plain("[wiki A_(b) page](https://x.org/A_(b) \"t\")"));
        assertEquals("see https://example.com now", OutlineTitles.plain("see <https://example.com> now"));
        assertEquals("Press Ctrl now", OutlineTitles.plain("Press <kbd>Ctrl</kbd> now"));
    }

    @Test
    void whatIsNotMarkupIsLeftAlone() {
        assertEquals("C#", OutlineTitles.plain("C#"));
        assertEquals("snake_case_name and 2 * 3 * 4", OutlineTitles.plain("snake_case_name and 2 * 3 * 4"));
        assertEquals("a < b and c > d", OutlineTitles.plain("a < b and c > d"));
        assertEquals("*not emphasis* [not a link]", OutlineTitles.plain("\\*not emphasis\\* \\[not a link\\]"));
        assertEquals("Ünïcödé & Symbols! (v1.2)", OutlineTitles.plain("Ünïcödé & Symbols! (v1.2)"));
        assertEquals("[just brackets]", OutlineTitles.plain("[just brackets]"));
    }

    @Test
    void codeSpansAreLiteral() {
        assertEquals("the **kwargs and a_b_c", OutlineTitles.plain("the `**kwargs` and `a_b_c`"));
        assertEquals("a ` tick", OutlineTitles.plain("`` a ` tick ``"));
        assertEquals("<b> and [x](y)", OutlineTitles.plain("`<b>` and `[x](y)`"));
    }

    @Test
    void whitespaceIsCollapsedAndNullIsEmpty() {
        assertEquals("a b", OutlineTitles.plain("  a \t b  "));
        assertEquals("", OutlineTitles.plain(null));
        assertEquals("", OutlineTitles.plain(""));
    }
}
