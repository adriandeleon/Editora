package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure Emacs caret-navigation math: smart line start, back-to-indentation, paragraph + sentence motion. */
class TextNavTest {

    // --- smartLineStartColumn (C-a: first non-whitespace ↔ column 0 toggle) ---

    @Test
    void smartLineStartColumnGoesToFirstNonWhitespace() {
        assertEquals(4, TextNav.smartLineStartColumn("    foo", 7)); // caret in text → indent
        assertEquals(4, TextNav.smartLineStartColumn("    foo", 2)); // within indent → indent
        assertEquals(4, TextNav.smartLineStartColumn("    foo", 0)); // col 0 → indent
        assertEquals(0, TextNav.smartLineStartColumn("    foo", 4)); // already at indent → toggle to 0
    }

    @Test
    void smartLineStartColumnEdgeCases() {
        assertEquals(0, TextNav.smartLineStartColumn("\tbar", 1)); // one-tab indent, caret at text → 0
        assertEquals(2, TextNav.smartLineStartColumn("  baz", 1));
        assertEquals(0, TextNav.smartLineStartColumn("hello", 3)); // no indent
        assertEquals(0, TextNav.smartLineStartColumn("", 0));
    }

    // --- lineStart / smartLineStart / backToIndentation as absolute offsets in a multi-line doc ---

    @Test
    void lineStartFindsTheStartOfTheCaretsLine() {
        String t = "abc\n  def\nghi";
        assertEquals(0, TextNav.lineStart(t, 0));
        assertEquals(0, TextNav.lineStart(t, 3)); // end of line 0
        assertEquals(4, TextNav.lineStart(t, 4)); // start of line 1
        assertEquals(4, TextNav.lineStart(t, 9)); // within line 1
        assertEquals(10, TextNav.lineStart(t, 12)); // line 2
    }

    @Test
    void smartLineStartOnSecondLineTogglesAroundItsIndent() {
        String t = "abc\n  def"; // line 1 starts at offset 4, indent 2 → text at offset 6
        assertEquals(6, TextNav.smartLineStart(t, 9)); // caret in "def" → first non-ws
        assertEquals(4, TextNav.smartLineStart(t, 6)); // caret at text → toggle to true line start
        assertEquals(6, TextNav.smartLineStart(t, 5)); // caret inside indent → text start
    }

    @Test
    void backToIndentationOffset() {
        String t = "abc\n\t qux"; // line 1 start 4, indent "\t " (2 chars) → first non-ws at offset 6
        assertEquals(6, TextNav.backToIndentation(t, 9));
        assertEquals(0, TextNav.backToIndentation("foo", 2)); // no indent
        assertEquals(7, TextNav.backToIndentation("foo\n   ", 6)); // all-whitespace line → runs to its end
    }

    // --- paragraph motion (M-} / M-{): a "paragraph" is a run of non-blank lines, bounded by blanks ---

    @Test
    void forwardParagraphStopsAtTheBlankLineBelowTheBlock() {
        // lines: 0"a"(0) 1"b"(2) 2""(4) 3"c"(5)   offsets in parens
        String t = "a\nb\n\nc";
        assertEquals(4, TextNav.forwardParagraph(t, 0)); // from line 0 → the blank line at offset 4
        assertEquals(t.length(), TextNav.forwardParagraph(t, 5)); // from the last block → document end
        // From a blank line you're sitting on, skip to the end of the next block (then its trailing blank
        // or the doc end) — here the next block "c" runs to the end.
        assertEquals(t.length(), TextNav.forwardParagraph(t, 4));
    }

    @Test
    void backwardParagraphStopsAtTheBlankLineAboveTheBlock() {
        String t = "a\n\nb\nc"; // lines: 0"a"(0) 1""(2) 2"b"(3) 3"c"(5)
        assertEquals(2, TextNav.backwardParagraph(t, 5)); // from line 3 → blank line at offset 2
        assertEquals(0, TextNav.backwardParagraph(t, 0)); // already at start
    }

    // --- sentence motion (M-e / M-a) ---

    @Test
    void forwardSentenceLandsAfterTheTerminatorAndTrailingSpace() {
        String t = "One. Two. Three.";
        assertEquals(5, TextNav.forwardSentence(t, 0)); // start of "Two."
        assertEquals(10, TextNav.forwardSentence(t, 5)); // start of "Three."
        assertEquals(t.length(), TextNav.forwardSentence(t, 10)); // last sentence → end
    }

    @Test
    void forwardSentenceKeepsTrailingQuotesAndBracketsWithTheSentence() {
        String t = "He said \"hi.\" Then left.";
        // The '.' is followed by a closing quote, so the sentence ends after the quote, not at the period.
        int after = TextNav.forwardSentence(t, 0);
        assertEquals("Then left.", t.substring(after));
    }

    @Test
    void abbreviationDotMidWordIsNotASentenceEnd() {
        String t = "see e.g. this"; // the dots in "e.g." aren't followed by whitespace until after "g."
        // "e." is followed by "g" (not whitespace) so it's skipped; "g." is followed by a space → boundary.
        assertEquals("this", t.substring(TextNav.forwardSentence(t, 0)));
    }

    @Test
    void backwardSentenceGoesToTheStartOfTheCurrentSentence() {
        String t = "One. Two. Three.";
        assertEquals(10, TextNav.backwardSentence(t, 16)); // from end → start of "Three."
        assertEquals(5, TextNav.backwardSentence(t, 10)); // sitting at "Three." start → back to "Two."
        assertEquals(0, TextNav.backwardSentence(t, 4)); // within first sentence → 0
    }

    // --- subword navigation (camelCase / snake_case) ---

    /** Walks nextSubwordBoundary from 0, returning the pieces it steps over. */
    private static java.util.List<String> forwardParts(String text) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int next = TextNav.nextSubwordBoundary(text, i);
            if (next <= i) {
                break;
            }
            parts.add(text.substring(i, next));
            i = next;
        }
        return parts;
    }

    @Test
    void forwardSplitsCamelCase() {
        assertEquals(java.util.List.of("get", "User", "Name"), forwardParts("getUserName"));
    }

    @Test
    void forwardSplitsAcronyms() {
        assertEquals(java.util.List.of("HTML", "Parser"), forwardParts("HTMLParser"));
        assertEquals(java.util.List.of("parse", "XML", "Doc"), forwardParts("parseXMLDoc"));
    }

    @Test
    void forwardSplitsSnakeAndKebab() {
        // separators are skipped like whitespace and consumed at the start of the next step
        assertEquals(java.util.List.of("foo", "_bar", "_baz"), forwardParts("foo_bar_baz"));
        assertEquals(java.util.List.of("foo", "-bar"), forwardParts("foo-bar"));
    }

    @Test
    void forwardSplitsLetterDigitTransitions() {
        assertEquals(java.util.List.of("foo", "42", "bar"), forwardParts("foo42bar"));
    }

    @Test
    void forwardSkipsWhitespaceLikeAWord() {
        // from the end of "foo" (3), skip the space, then "bar" is one subword → 7
        assertEquals(7, TextNav.nextSubwordBoundary("foo bar", 3));
    }

    @Test
    void backwardMirrorsForward() {
        String t = "getUserName";
        assertEquals(7, TextNav.prevSubwordBoundary(t, 11)); // from end → start of "Name"
        assertEquals(3, TextNav.prevSubwordBoundary(t, 7)); // → start of "User"
        assertEquals(0, TextNav.prevSubwordBoundary(t, 3)); // → start of "get"
    }

    @Test
    void backwardAcronym() {
        assertEquals(4, TextNav.prevSubwordBoundary("HTMLParser", 10)); // → start of "Parser"
        assertEquals(0, TextNav.prevSubwordBoundary("HTMLParser", 4)); // → start of "HTML"
    }

    @Test
    void boundariesAtDocumentEdgesAreSafe() {
        assertEquals(0, TextNav.nextSubwordBoundary("", 0));
        assertEquals(0, TextNav.prevSubwordBoundary("", 0));
        assertEquals(3, TextNav.nextSubwordBoundary("abc", 3)); // already at end
        assertEquals(0, TextNav.prevSubwordBoundary("abc", 0));
        assertEquals(3, TextNav.nextSubwordBoundary("abc", 9)); // out-of-range clamps
    }
}
