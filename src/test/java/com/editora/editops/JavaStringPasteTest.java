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

    @Test
    void aSelectionWithinOneLiteralIsTold() {
        String text = "String s = \"a\\\"b\" + \"c\";";
        int a = text.indexOf('a');
        assertTrue(JavaStringPaste.withinLiteral(text, a, a + 4));
        assertFalse(JavaStringPaste.withinLiteral(text, a, text.indexOf('c')), "across two literals");
    }
}
