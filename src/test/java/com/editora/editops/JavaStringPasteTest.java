package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Text pasted into a Java string literal is escaped, as IntelliJ does (thirteenth review AK2). */
class JavaStringPasteTest {

    private static final String LINE = "    String s = \"\";\n";
    private static final int IN = LINE.indexOf("\"\"") + 1;

    @Test
    void aPathAndQuotesAreEscaped() {
        JavaStringPaste.copied(null, false);
        assertEquals("C:\\\\temp \\\"x\\\"", JavaStringPaste.forPaste(LINE, IN, "C:\\temp \"x\""));
        assertEquals("a\\tb", JavaStringPaste.forPaste(LINE, IN, "a\tb"));
    }

    @Test
    void lineBreaksGoOnInAConcatenatedLiteral() {
        JavaStringPaste.copied(null, false);
        assertEquals("line1\\n\" +\n            \"line2", JavaStringPaste.forPaste(LINE, IN, "line1\r\nline2"));
        assertEquals("last\\n", JavaStringPaste.forPaste(LINE, IN, "last\n"), "no empty literal after the last");
    }

    @Test
    void outsideALiteralOrFromOneItPastesAsItIs() {
        JavaStringPaste.copied(null, false);
        assertEquals("a\\b", JavaStringPaste.forPaste(LINE, 4, "a\\b"), "code");
        String block = "String t = \"\"\"\n    x\n    \"\"\";\n";
        assertFalse(JavaStringPaste.inLiteral(block, block.indexOf("x")), "a text block");
        String comment = "// say \"hi\n";
        assertFalse(JavaStringPaste.inLiteral(comment, comment.indexOf("hi")), "a comment");
        String chars = "char q = '\"'; String s = \"";
        assertTrue(JavaStringPaste.inLiteral(chars, chars.length()), "after a quote char literal");
        String copied = "C:\\\\temp";
        JavaStringPaste.copied(copied, true);
        assertEquals(copied, JavaStringPaste.forPaste(LINE, IN, copied), "escaped already");
        JavaStringPaste.copied(null, false);
    }

    /** In a text block only backslashes and a {@code """} are escaped; in a char literal its quote too (RJ4). */
    @Test
    void aTextBlockAndACharLiteralAreEscapedForThemselves() {
        JavaStringPaste.copied(null, false);
        String block = "String t = \"\"\"\n    x\n    \"\"\";\n";
        int in = block.indexOf("x");
        assertEquals(JavaStringPaste.Literal.TEXT_BLOCK, JavaStringPaste.literalAt(block, in));
        assertEquals("C:\\\\temp \"x\"\nnext", JavaStringPaste.forPaste(block, in, "C:\\temp \"x\"\nnext"));
        String ch = "char c = ' ';";
        int at = ch.indexOf("' '") + 1;
        assertEquals(JavaStringPaste.Literal.CHAR, JavaStringPaste.literalAt(ch, at));
        assertEquals("\\\\", JavaStringPaste.forPaste(ch, at, "\\"));
        assertEquals(JavaStringPaste.Literal.NONE, JavaStringPaste.literalAt(ch, ch.length()), "after it");
    }

    @Test
    void aSelectionWithinOneLiteralIsTold() {
        String text = "String s = \"a\\\"b\" + \"c\";";
        int a = text.indexOf('a');
        assertTrue(JavaStringPaste.withinLiteral(text, a, a + 4));
        assertFalse(JavaStringPaste.withinLiteral(text, a, text.indexOf('c')), "across two literals");
    }

    @Test
    void anEmptyPasteIsLeftAsItIs() {
        JavaStringPaste.copied(null, false);
        assertEquals("", JavaStringPaste.forPaste(LINE, IN, ""));
    }

    @Test
    void aLoneCarriageReturnBreaksTheLineAndAControlCharIsEscaped() {
        JavaStringPaste.copied(null, false);
        assertEquals("a\\n\" +\n            \"b", JavaStringPaste.forPaste(LINE, IN, "a\rb"), "an old Mac line end");
        assertEquals("a\\n", JavaStringPaste.forPaste(LINE, IN, "a\r"), "a trailing one: no empty literal after");
        assertEquals("\\b\\f", JavaStringPaste.forPaste(LINE, IN, "\b\f"));
    }

    @Test
    void theNextLiteralIsIndentedPastTheLinesOwnTabs() {
        JavaStringPaste.copied(null, false);
        String tabbed = "\tString s = \"\";\n";
        int in = tabbed.indexOf("\"\"") + 1;
        assertEquals("a\\n\" +\n\t        \"b", JavaStringPaste.forPaste(tabbed, in, "a\nb"), "the tab kept");
    }

    @Test
    void aSelectionOutsideALiteralOrBackwardsOrOverALineBreakIsNotWithinOne() {
        String text = "x = \"ab\ncd\"";
        int a = text.indexOf('a');
        assertFalse(JavaStringPaste.withinLiteral(text, 0, 2), "code");
        assertFalse(JavaStringPaste.withinLiteral(text, a + 1, a), "backwards");
        assertFalse(JavaStringPaste.withinLiteral(text, a, text.indexOf('c')), "an unclosed literal ends at the line");
        String open = "x = \"abc";
        assertTrue(JavaStringPaste.withinLiteral(open, open.indexOf('a'), 100), "to the end of the text");
    }

    @Test
    void theLiteralAnOffsetIsInIsFoundPastEscapesCommentsBlocksAndChars() {
        String escaped = "x = \"a\\\"b\" + y;";
        assertEquals(JavaStringPaste.Literal.STRING, JavaStringPaste.literalAt(escaped, escaped.indexOf('b')));
        assertEquals(
                JavaStringPaste.Literal.NONE,
                JavaStringPaste.literalAt(escaped, escaped.indexOf('\\') + 1),
                "between a backslash and the char it escapes");
        assertEquals(JavaStringPaste.Literal.NONE, JavaStringPaste.literalAt(escaped, escaped.indexOf('+')));
        String unclosed = "\"ab\ncd";
        assertEquals(
                JavaStringPaste.Literal.NONE,
                JavaStringPaste.literalAt(unclosed, unclosed.indexOf('d')),
                "a literal ends at its line");

        String lineComment = "// c\nx = \"";
        assertEquals(JavaStringPaste.Literal.STRING, JavaStringPaste.literalAt(lineComment, lineComment.length()));
        String blockComment = "/* \" */ s = \"";
        assertEquals(JavaStringPaste.Literal.STRING, JavaStringPaste.literalAt(blockComment, blockComment.length()));
        assertEquals(JavaStringPaste.Literal.NONE, JavaStringPaste.literalAt(blockComment, 4), "inside the comment");
        assertEquals(JavaStringPaste.Literal.NONE, JavaStringPaste.literalAt("/* \"", 4), "an unclosed comment");

        String afterBlock = "t = \"\"\"\nx\n\"\"\"; s = \"";
        assertEquals(JavaStringPaste.Literal.STRING, JavaStringPaste.literalAt(afterBlock, afterBlock.length()));
        assertEquals(
                JavaStringPaste.Literal.NONE,
                JavaStringPaste.literalAt("t = \"\"\"", 5),
                "between the quotes that open a text block");

        String escapedChar = "c = '\\''; s = \"";
        assertEquals(JavaStringPaste.Literal.STRING, JavaStringPaste.literalAt(escapedChar, escapedChar.length()));
        String brokenChar = "c = '\nx = \"";
        assertEquals(JavaStringPaste.Literal.STRING, JavaStringPaste.literalAt(brokenChar, brokenChar.length()));
        assertEquals(JavaStringPaste.Literal.CHAR, JavaStringPaste.literalAt("c = 'ab", 7), "to the end of the text");
    }
}
