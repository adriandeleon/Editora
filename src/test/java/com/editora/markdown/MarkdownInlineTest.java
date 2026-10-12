package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownInlineTest {

    @Test
    void wrapsSelection() {
        MarkdownEdit e = MarkdownInline.toggle("foo", 0, 3, "**");
        assertEquals(new MarkdownEdit(0, 3, "**foo**", 2, 5), e);
    }

    @Test
    void unwrapsWhenMarkersInsideSelection() {
        MarkdownEdit e = MarkdownInline.toggle("**foo**", 0, 7, "**");
        assertEquals(new MarkdownEdit(0, 7, "foo", 0, 3), e);
    }

    @Test
    void unwrapsWhenMarkersOutsideSelection() {
        // selection is just "foo" inside **foo**
        MarkdownEdit e = MarkdownInline.toggle("**foo**", 2, 5, "**");
        assertEquals(new MarkdownEdit(0, 7, "foo", 0, 3), e);
    }

    @Test
    void emptySelectionInsertsPairWithCaretBetween() {
        MarkdownEdit e = MarkdownInline.toggle("", 0, 0, "*");
        assertEquals(new MarkdownEdit(0, 0, "**", 1, 1), e);
    }

    @Test
    void codeAndStrikeMarkers() {
        assertEquals(new MarkdownEdit(0, 1, "`x`", 1, 2), MarkdownInline.toggle("x", 0, 1, "`"));
        assertEquals(new MarkdownEdit(0, 2, "~~hi~~", 2, 4), MarkdownInline.toggle("hi", 0, 2, "~~"));
    }

    @Test
    void linkWithEmptyUrlPlacesCaretInParens() {
        MarkdownEdit e = MarkdownInline.link("foo", 0, 3, "");
        assertEquals(new MarkdownEdit(0, 3, "[foo]()", 6, 6), e);
        assertEquals(')', e.replacement().charAt(6 - e.from()));
    }

    @Test
    void linkWithUrlSelectsText() {
        MarkdownEdit e = MarkdownInline.link("foo", 0, 3, "http://x");
        assertEquals(new MarkdownEdit(0, 3, "[foo](http://x)", 1, 4), e);
    }

    @Test
    void linkAroundFindsInlineAndBareUrls() {
        String inline = "see [a](http://x) end";
        assertEquals("http://x", MarkdownInline.linkAround(inline, 6));
        assertNull(MarkdownInline.linkAround(inline, 0));
        String bare = "go https://y/z now";
        assertEquals("https://y/z", MarkdownInline.linkAround(bare, 6));
        assertNull(MarkdownInline.linkAround(bare, 0));
    }

    private static String apply(String text, int selStart, int selEnd, String marker) {
        MarkdownEdit e = MarkdownInline.toggle(text, selStart, selEnd, marker);
        return e == null ? text : text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    /**
     * Toggling italic over bold matched the *inner* asterisk of each `**` and unwrapped it, so the bold was
     * silently lost ("**bold**" → "*bold*") where every other editor adds italics ("***bold***").
     */
    @Test
    void togglingItalicOverBoldAddsItalicRatherThanStrippingTheBold() {
        assertEquals("***bold***", apply("**bold**", 2, 6, "*"));
        assertEquals("***it***", apply("*it*", 1, 3, "**"), "the inverse already worked");
    }

    /** The unwrap path itself must still work — only a *longer run* of the marker char is off-limits. */
    @Test
    void unwrappingMatchingMarkersStillWorks() {
        assertEquals("it", apply("*it*", 1, 3, "*"));
        assertEquals("bold", apply("**bold**", 2, 6, "**"));
        assertEquals("s", apply("~~s~~", 2, 3, "~~"));
        assertEquals("c", apply("`c`", 1, 2, "`"));
    }

    /** {@code ‸} marks a collapsed caret, {@code «»} a selection; the result is marked the same way. */
    private static String toggled(String marked, String marker) {
        int caret = marked.indexOf('‸');
        int a = marked.indexOf('«');
        int b = marked.indexOf('»') - 1;
        String clean = marked.replace("‸", "").replace("«", "").replace("»", "");
        MarkdownEdit e = caret >= 0
                ? MarkdownInline.toggle(clean, caret, caret, marker)
                : MarkdownInline.toggle(clean, a, b, marker);
        if (e == null) {
            return marked;
        }
        StringBuilder out = new StringBuilder(clean.substring(0, e.from()) + e.replacement() + clean.substring(e.to()));
        if (e.selStart() == e.selEnd()) {
            out.insert(e.selStart(), '‸');
        } else {
            out.insert(e.selEnd(), '»').insert(e.selStart(), '«');
        }
        return out.toString();
    }

    @Test
    void whitespaceAtTheEndsOfTheSelectionStaysOutsideTheMarkers() {
        // "**word **" is not bold: a closing run cannot follow whitespace.
        assertEquals("a **«word»** here", toggled("a «word »here", "**"));
        assertEquals("a *«word»* here", toggled("a« word» here", "*"));
        assertEquals("a «  » b", toggled("a «  » b", "**"), "nothing but blanks: nothing to mark");
    }

    @Test
    void theRunLengthDecidesWhatIsThere() {
        assertEquals("*«x»*", toggled("***«x»***", "**"), "bold off, italic kept");
        assertEquals("**«x»**", toggled("***«x»***", "*"), "italic off, bold kept");
        assertEquals("***«x»***", toggled("«**x**»", "*"), "markers inside the selection: still bold, now italic too");
        assertEquals("«x»", toggled("«**x**»", "**"));
        assertEquals("~«x»~", toggled("~~~«x»~~~", "~~"));
    }

    @Test
    void twoSpansAreNotOneSpan() {
        // The outer markers of "*a* and *b*" belong to different spans; stripping them left "a* and *b".
        assertEquals("*«*a* and *b*»*", toggled("«*a* and *b*»", "*"));
        assertEquals("«a *b* c»", toggled("«*a *b* c*»", "*"), "one span holding another");
    }

    @Test
    void underscoreEmphasisIsRecognised() {
        assertEquals("«x»", toggled("_«x»_", "*"));
        assertEquals("«x»", toggled("__«x»__", "**"));
        assertEquals("snake_*«case»*_name", toggled("snake_«case»_name", "*"), "not inside a word");
    }

    @Test
    void aSelectionOverSeveralLinesIsMarkedLineByLine() {
        assertEquals("**«one**\n\n**two»**", toggled("«one\n\ntwo»", "**"));
        assertEquals("- **«a**\n- **b»**", toggled("«- a\n- b»", "**"), "behind the list markers");
        assertEquals("# *«T*\n> *q»*", toggled("«# T\n> q»", "*"));
        assertEquals("**«line»**\nnext", toggled("«line\n»next", "**"), "the newline is not part of it");
        assertEquals("«one\ntwo»", toggled("**«one**\n**two»**", "**"), "all marked: all unmarked");
        assertEquals("**«one**\n**two»**", toggled("**«one**\ntwo»", "**"), "some marked: the rest catch up");
    }

    @Test
    void inlineCodeIsFencedPastTheBackticksInside() {
        assertEquals("``«x`y»``", toggled("«x`y»", "`"));
        assertEquals("`` «`a» ``", toggled("«`a»", "`"), "a backtick at the edge is kept off the fence");
        assertEquals("«`a»", toggled("`` «`a» ``", "`"));
        assertEquals("«a»", toggled("«`a`»", "`"), "selected with its backticks: code off");
        assertEquals("«x`y»", toggled("``«x`y»``", "`"));
    }

    @Test
    void aCaretInsideAWordTogglesTheWord() {
        assertEquals("hello **wo‸rld** x", toggled("hello wo‸rld x", "**"));
        assertEquals("hello wo‸rld x", toggled("hello **wo‸rld** x", "**"));
        assertEquals("hello **‸** world", toggled("hello ‸ world", "**"));
        assertEquals("word**‸**", toggled("word‸", "**"), "at a word's end the caret starts new text");
        assertEquals("‸", toggled("**‸**", "**"), "and a second toggle takes the empty pair away");
        assertEquals("***‸***", toggled("**‸**", "*"));
    }

    @Test
    void isMarkedReadsTheSameRuns() {
        assertTrue(MarkdownInline.isMarked("**bold**", 2, 6, "**"));
        assertFalse(MarkdownInline.isMarked("**bold**", 2, 6, "*"));
        assertTrue(MarkdownInline.isMarked("***x***", 3, 4, "*"));
        assertTrue(MarkdownInline.isMarked("a `code` b", 4, 4, "`"));
        assertFalse(MarkdownInline.isMarked("plain", 2, 2, "**"));
    }

    @Test
    void typstMarkersAreSymmetric() {
        assertEquals(new MarkdownEdit(0, 1, "*x*", 1, 2), MarkdownInline.toggleSymmetric("x", 0, 1, "*"));
        assertEquals(new MarkdownEdit(0, 3, "x", 0, 1), MarkdownInline.toggleSymmetric("*x*", 1, 2, "*"));
        assertEquals("_*x*_", apply2("_x_", 1, 2, "*"), "an underscore is emphasis there, not bold");
    }

    private static String apply2(String text, int s, int e, String marker) {
        MarkdownEdit ed = MarkdownInline.toggleSymmetric(text, s, e, marker);
        return text.substring(0, ed.from()) + ed.replacement() + text.substring(ed.to());
    }

    @Test
    void linkTextEscapesOnlyStrayBrackets() {
        assertEquals("[[a]](u)", MarkdownInline.link("[a]", 0, 3, "u").replacement());
        assertEquals("[a\\] b](u)", MarkdownInline.link("a] b", 0, 4, "u").replacement());
        assertEquals("[\\[a](u)", MarkdownInline.link("[a", 0, 2, "u").replacement());
    }

    @Test
    void pastingAUrlWrapsOnlyPlainText() {
        assertTrue(MarkdownInline.wrapsAsLink("see docs now", 4, 8));
        assertFalse(MarkdownInline.wrapsAsLink("http://old.example", 0, 18), "a URL is replaced");
        assertFalse(MarkdownInline.wrapsAsLink("`code`", 1, 5), "inside inline code");
        assertFalse(MarkdownInline.wrapsAsLink("[docs](http://x)", 1, 5), "already a link's text");
        assertFalse(MarkdownInline.wrapsAsLink("a\nb", 0, 3));
        assertTrue(MarkdownInline.wrapsAsLink("[done] then docs", 12, 16));
        assertTrue(MarkdownInline.isUrl(" https://x.y/z "));
        assertFalse(MarkdownInline.isUrl("not a url"));
    }
}
