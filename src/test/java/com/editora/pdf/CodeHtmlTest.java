package com.editora.pdf;

import java.util.Collection;
import java.util.List;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeHtmlTest {

    /** A one-span style covering the whole text with {@code classes} (empty = no style). */
    private static StyleSpans<Collection<String>> span(int length, String... classes) {
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        b.add(List.of(classes), length);
        return b.create();
    }

    /** Builds spans from consecutive (length, classes) segments. */
    private static StyleSpans<Collection<String>> spans(Object... segs) {
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        for (int i = 0; i < segs.length; i += 2) {
            int len = (int) segs[i];
            @SuppressWarnings("unchecked")
            List<String> cls = (List<String>) segs[i + 1];
            b.add(cls, len);
        }
        return b.create();
    }

    @Test
    void escapesHtmlMetacharacters() {
        assertEquals("a &amp; b &lt;c&gt;", CodeHtml.escape("a & b <c>"));
    }

    @Test
    void colorConvertsToSixDigitHex() {
        assertEquals("#24292f", CodeHtml.hex(PdfTheme.DEFAULT_FG));
        assertEquals("#ffffff", CodeHtml.hex(PdfTheme.BACKGROUND));
    }

    @Test
    void wrapsInAPreWithLightBackground() {
        String html = CodeHtml.toHtml("x", span(1), 4);
        assertTrue(html.startsWith("<pre "), "wrapped in <pre>");
        assertTrue(html.endsWith("</pre>"));
        assertTrue(html.contains("background-color:#ffffff"), "light background");
    }

    @Test
    void plainTextWithNoStyleHasNoSpans() {
        // default-colored, unstyled text should be emitted bare (no <span>), inheriting the <pre>
        String html = CodeHtml.toHtml("hello", span(5), 4);
        assertTrue(html.contains(">hello</pre>"), "bare text inside the pre: " + html);
        assertFalse(html.contains("<span"), "no span for default text");
    }

    @Test
    void aKeywordSpanGetsItsColourAndBold() {
        // "keyword" → #cf222e and bold in PdfTheme
        String html = CodeHtml.toHtml("if", span(2, "keyword"), 4);
        assertTrue(html.contains("<span style=\"color:#cf222e;font-weight:bold\">if</span>"), html);
    }

    @Test
    void aCommentSpanGetsItsColourAndItalic() {
        String html = CodeHtml.toHtml("//x", span(3, "comment"), 4);
        assertTrue(html.contains("color:#6e7781"), html);
        assertTrue(html.contains("font-style:italic"), html);
    }

    @Test
    void contentIsEscapedInsideSpans() {
        String html = CodeHtml.toHtml("a<b", span(3, "keyword"), 4);
        assertTrue(html.contains(">a&lt;b</span>"), html);
    }

    @Test
    void mixedSegmentsProduceOnlyStyledSpans() {
        // "let x" → "let" keyword (styled), " x" plain (bare)
        StyleSpans<Collection<String>> s = spans(3, List.of("keyword"), 2, List.of());
        String html = CodeHtml.toHtml("let x", s, 4);
        assertTrue(html.contains("<span style=\"color:#cf222e;font-weight:bold\">let</span>"), html);
        assertTrue(html.contains("</span> x</pre>"), "the plain tail is bare: " + html);
    }

    @Test
    void multipleLinesAreSeparatedByNewlines() {
        String html = CodeHtml.toHtml("a\nb", span(3), 4);
        assertTrue(html.contains(">a\nb</pre>"), html);
    }

    @Test
    void tabsAreExpandedToSpaces() {
        String html = CodeHtml.toHtml("\tx", span(2), 4);
        assertTrue(html.contains(">    x</pre>"), "tab → 4 spaces: " + html);
    }

    @Test
    void nullSpansYieldPlainEscapedText() {
        String html = CodeHtml.toHtml("a<b", null, 4);
        assertTrue(html.contains(">a&lt;b</pre>"), html);
        assertFalse(html.contains("<span"));
    }
}
