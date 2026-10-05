package com.editora.editops;

import java.util.List;

import com.editora.editor.TextNav;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression cases for the pure editing operations found in the second review round. */
class EditOpsRegressionTest {

    private static final String EMOJI = "😀"; // one supplementary code point, two UTF-16 units

    // --- fill-paragraph ---------------------------------------------------------------------------

    private static String fill(String text, int caret, int column, String lineComment) {
        Filler.Edit e = Filler.fillParagraph(text, caret, column, lineComment);
        return e == null ? text : text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    @Test
    void fillingACommentLeavesTheCodeBelowItAlone() {
        String text =
                "    // a long comment that goes on\n    // more comment text\n    int x = compute(5);\n    return x;\n";
        assertEquals(
                "    // a long comment that\n    // goes on more comment\n    // text\n    int x = compute(5);\n    return x;\n",
                fill(text, 8, 28, "//"));
    }

    @Test
    void fillingACommentInAFileWithNoBlankLinesKeepsItsMarkerAndTheCode() {
        String text =
                "class A {\n    void f() {\n        // comment one two three four five six\n        int x = 1;\n    }\n}";
        String out = fill(text, text.indexOf("comment"), 30, "//");
        assertTrue(out.startsWith("class A {\n    void f() {\n        // comment one two\n"), out);
        assertTrue(out.endsWith("        int x = 1;\n    }\n}"), out);
    }

    @Test
    void javadocDelimitersBoundTheFilledBlock() {
        String text = "/**\n * Some long text that should wrap here\n * more words\n */\nvoid f() {}";
        assertEquals(
                "/**\n * Some long text that\n * should wrap here more\n * words\n */\nvoid f() {}",
                fill(text, 8, 24, "//"));
        assertNull(Filler.fillParagraph(text, 1, 24, "//"), "the /** line itself is not text to fill");
    }

    @Test
    void aBulletWrapsUnderItsTextInsteadOfRepeatingTheBullet() {
        String text = "* a long bullet item that wraps\n* second";
        assertEquals("* a long bullet\n  item that wraps\n* second", fill(text, 3, 17, null));
    }

    @Test
    void docCommentMarkerIsKeptOnContinuationLines() {
        assertEquals("/// one two\n/// three", fill("/// one two three", 5, 12, "//"));
    }

    @Test
    void autoFillDoesNotBreakInsideTheFillPrefix() {
        assertNull(AutoFill.compute("> " + "x".repeat(75), 70, "> "));
        assertEquals(new AutoFill.Break(5, 1, "\n> "), AutoFill.compute("> abc " + "x".repeat(75), 70, "> "));
    }

    // --- indentation ------------------------------------------------------------------------------

    private static String enter(String line, String language) {
        return Indenter.enterEdit(line, line.length(), language, 4).insert();
    }

    @Test
    void openersContainingCommentLookalikesStillIndent() {
        assertEquals("\n    ", enter("for (int i = n - 1; i >= 0; i--) {", "java"));
        assertEquals("\n    ", enter("while (n-- > 0) {", "java"));
        assertEquals("\n    ", enter("if (this.#count > 0) {", "javascript"));
        assertEquals("\n    ", enter("#main {", "css"));
        assertEquals("\n    ", enter("if [ $# -eq 0 ]; then", "shell"));
        assertEquals("\n    ", enter("if git diff --quiet; then", "shell"));
        assertEquals("\n    ", enter("for i in range(n // 2):", "python"));
        assertEquals("\n    ", enter("for i = 1, #t do", "lua"));
    }

    @Test
    void realTrailingCommentsAreStillStripped() {
        assertEquals("\n    ", enter("if (x) { // why", "java"));
        assertEquals("\n", enter("foo(); // {", "java"));
        assertEquals("\n    ", enter("if true; then # why", "shell"));
        assertEquals("\n", enter("echo hi # then", "shell"));
        assertEquals("\n    ", enter("resource \"a\" \"b\" { # note", "terraform"));
        assertEquals("\n", enter("x = 1 -- do", "lua"));
        assertEquals("\n", enter("SELECT 1 -- (", "sql"));
    }

    @Test
    void htmlVoidElementsDoNotOpenABlock() {
        assertEquals("\n", enter("<meta charset=\"utf-8\">", "html"));
        assertEquals("\n", enter("<br>", "html"));
        assertEquals("\n    ", enter("<div class=\"a\">", "html"));
        assertEquals("\n    ", enter("<link>", "xml"));
    }

    @Test
    void rubyDefWhoseNameStartsWithEndOpensABlock() {
        assertEquals("\n    ", enter("def end_date", "ruby"));
        assertEquals("\n", enter("def x; 1 end", "ruby"));
    }

    @Test
    void shiftTabRemovesTheIndentUnitNotTheTabWidth() {
        String text = "        x";
        Indenter.TabEdit e = Indenter.smartTab(text, 9, 9, "java", 8, true, true, 4);
        assertEquals("    x", text.substring(0, e.from()) + e.replacement() + text.substring(e.to()));
    }

    @Test
    void closerIgnoresAPreprocessorLineAtColumnZero() {
        String text = "void f() {\n    if (x) {\n        a();\n#endif\n        }";
        assertEquals("    ", Indenter.closerAlignIndent(Indenter.Style.BRACES, text, text.length(), 4, "        "));
    }

    // --- comment toggle ---------------------------------------------------------------------------

    @Test
    void aSelectionBetweenTwoBlockCommentsIsNotAlreadyCommented() {
        String text = "/* a */\nfoo();\n/* b */";
        Commenter.Edit e = Commenter.toggle(text, 0, text.length(), Commenter.styleFor("java"));
        assertEquals("/* /* a */\nfoo();\n/* b */ */", e.replacement());
    }

    @Test
    void batchRemIsAWholeWordInAnyCase() {
        Commenter.CommentStyle bat = Commenter.styleFor("batchfile");
        assertEquals(
                "REM REMOTE_HOST=1",
                Commenter.toggle("REMOTE_HOST=1", 0, 0, bat).replacement());
        assertEquals("x", Commenter.toggle("rem x", 0, 0, bat).replacement());
    }

    @Test
    void togglingWithABareCaretLeavesABareCaretOnTheSameText() {
        String text = "class A {\n    foo();\n}";
        int caret = text.indexOf("foo") + 2;
        Commenter.Edit e = Commenter.toggle(text, caret, caret, Commenter.styleFor("java"));
        assertEquals("    // foo();", e.replacement());
        assertEquals(e.selStart(), e.selEnd(), "no selection: the next key must not replace the line");
        assertEquals(caret + 3, e.selStart());
        // …and a real selection is still re-selected.
        Commenter.Edit sel = Commenter.toggle(text, caret, caret + 1, Commenter.styleFor("java"));
        assertTrue(sel.selEnd() > sel.selStart());
    }

    // --- structural motion ------------------------------------------------------------------------

    @Test
    void sexpMotionCrossesOperatorsAndSeparators() {
        String t = "x = y + 1;";
        assertEquals(1, SexpNav.forward(t, 0));
        assertEquals(5, SexpNav.forward(t, 1)); // over " = y"
        assertEquals(9, SexpNav.forward(t, 5)); // over " + 1"
        assertEquals(10, SexpNav.forward(t, 9)); // over the ";" to the end
        assertEquals(8, SexpNav.backward(t, 10));
        assertEquals(4, SexpNav.backward(t, 8));
        assertEquals(0, SexpNav.backward(t, 4));
        assertEquals(1, SexpNav.forward("a, )", 1), "still a no-op before a closer");
    }

    @Test
    void paragraphMotionFromABlocksEdgeLineStopsAtTheNearestBlank() {
        String t = "a1\na2\n\nb1\nb2\n\nc1\n";
        assertEquals(6, TextNav.forwardParagraph(t, 0));
        assertEquals(6, TextNav.forwardParagraph(t, 3)); // from a2, the block's last line
        assertEquals(13, TextNav.forwardParagraph(t, 6));
        assertEquals(6, TextNav.backwardParagraph(t, 7)); // from b1, the block's first line
        assertEquals(6, TextNav.backwardParagraph(t, 10));
        assertEquals(0, TextNav.backwardParagraph(t, 6));
    }

    @Test
    void markParagraphOnABlankLineTakesTheParagraphBelow() {
        String t = "a1\na2\n\nb1\nb2\n";
        assertArrayEquals(new int[] {7, 12}, SexpNav.paragraphBounds(t, 6));
        assertArrayEquals(new int[] {0, 5}, SexpNav.paragraphBounds(t, 1));
        assertArrayEquals(new int[] {3, 3}, SexpNav.paragraphBounds("a\n\n\n", 3));
    }

    // --- surrogate pairs --------------------------------------------------------------------------

    private static void assertWellFormed(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)), "lone surrogate in " + s);
                i++;
            } else {
                assertFalse(Character.isLowSurrogate(c), "lone surrogate in " + s);
            }
        }
    }

    @Test
    void transposeCharsMovesASupplementaryCharacterWhole() {
        String text = "a" + EMOJI + "b";
        Transposer.Edit e = Transposer.transposeChars(text, 3); // between the emoji and b
        String out = text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
        assertEquals("ab" + EMOJI, out);
        assertEquals(4, e.caret());
        Transposer.Edit atEnd = Transposer.transposeChars("a" + EMOJI, 3);
        assertEquals(EMOJI + "a", atEnd.replacement());
        assertEquals("ba", Transposer.transposeChars("ab", 1).replacement());
        assertNull(Transposer.transposeChars("a\nb", 2));
    }

    @Test
    void rectangleEdgesNeverSplitASurrogatePair() {
        String text = "a" + EMOJI + "b\nabcd";
        Rectangle.Bounds b = new Rectangle.Bounds(0, 1, 1, 2);
        Rectangle.Edit del = Rectangle.delete(text, b);
        assertWellFormed(del.replacement());
        assertEquals("ab\nacd", del.replacement());
        assertWellFormed(Rectangle.clear(text, b).replacement());
        assertWellFormed(Rectangle.open(text, new Rectangle.Bounds(0, 1, 2, 3)).replacement());
        assertWellFormed(
                Rectangle.replace(text, new Rectangle.Bounds(0, 1, 2, 2), "|").replacement());
        assertWellFormed(Rectangle.yank(text, 2, List.of("x", "y")).replacement());
        for (String row : Rectangle.extract(text, b)) {
            assertWellFormed(row);
        }
    }

    // --- smaller edge cases -----------------------------------------------------------------------

    @Test
    void tagAutoCloseIgnoresAComparisonInInlineScript() {
        assertNull(TagAutoClose.closer("if (i<n && j", true));
        assertEquals("</Foo>", TagAutoClose.closer("<Foo bar={a && b}", false));
        assertEquals("</div>", TagAutoClose.closer("<div class=\"a\"", true));
    }

    @Test
    void aQuoteAfterACloserOrFullStopEndsAStringInsteadOfPairing() {
        assertEquals(
                AutoClose.Action.NONE,
                AutoClose.decide('"', ')', (char) 0, false).action());
        assertEquals(
                AutoClose.Action.NONE,
                AutoClose.decide('"', '.', (char) 0, false).action());
        assertEquals(
                AutoClose.Action.INSERT_PAIR,
                AutoClose.decide('"', '(', ')', false).action());
        assertEquals(
                AutoClose.Action.INSERT_PAIR,
                AutoClose.decide('"', ' ', (char) 0, false).action());
    }

    @Test
    void caseCycleIsNotStuckOnAOneWordToken() {
        assertEquals("FOO", StringCase.cycle("foo"));
        assertEquals("foo", StringCase.cycle("FOO"));
        assertEquals("foo_bar", StringCase.cycle("fooBar"));
    }

    @Test
    void renamingIgnoresATagStillBeingTyped() {
        // <b>… <br| …</b>: "br" is a new tag on its way, not <b> being renamed.
        String text = "<b>line one <br line two <i>x</i></b>";
        assertNull(TagRename.mirror(text, text.indexOf("<br") + 2, "", "r", true));
        String complete = "<divx>text</div>";
        assertEquals(new TagRename.Mirror(12, 15, "divx"), TagRename.mirror(complete, 4, "", "x", true));
    }
}
