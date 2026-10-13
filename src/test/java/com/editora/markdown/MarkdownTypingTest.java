package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTypingTest {

    /** Types {@code keys} one by one at the {@code ‸} of {@code marked}; the result is marked the same way. */
    private static String type(String marked, String keys, boolean smart) {
        int col = marked.indexOf('‸');
        String line = marked.replace("‸", "");
        for (char c : keys.toCharArray()) {
            MarkdownEdit e = MarkdownTyping.typed(line, col, c, smart);
            if (e == null) {
                line = line.substring(0, col) + c + line.substring(col);
                col++;
            } else {
                line = line.substring(0, e.from()) + e.replacement() + line.substring(e.to());
                col = e.selStart();
            }
        }
        return line.substring(0, col) + "‸" + line.substring(col);
    }

    @Test
    void emphasisMarkersComeInPairs() {
        assertEquals("a *‸*", type("a ‸", "*", false));
        assertEquals("a **‸**", type("a ‸", "**", false), "a second one makes it bold");
        assertEquals("a **b**‸", type("a ‸", "**b**", false), "and the closing ones are typed over");
        assertEquals("a *b*‸ c", type("a ‸ c", "*b*", false));
        assertEquals("(_‸_)", type("(‸)", "_", false));
    }

    @Test
    void notWhereAMarkerIsAPlainCharacter() {
        assertEquals("* ‸", type("‸", "* ", false), "a list marker at the start of a line");
        assertEquals("  * item‸", type("  ‸", "* item", false));
        assertEquals("snake_case‸", type("snake‸", "_case", false), "inside a word");
        assertEquals("2 * 3‸", type("2 ‸", "* 3", false), "a space in the empty pair takes the twin away");
        assertEquals("`a*‸`", type("`a‸`", "*", false), "in a code span");
        assertEquals("see http://x.y/a_‸", type("see http://x.y/a‸", "_", false), "in a URL");
        assertEquals("<a href_‸", type("<a href‸", "_", false), "in an HTML tag");
    }

    @Test
    void smartQuotesDashesAndEllipsis() {
        assertEquals("“quoted” and ‘single’ and don’t‸", type("‸", "\"quoted\" and 'single' and don't", true));
        assertEquals("(“x”)‸", type("‸", "(\"x\")", true));
        assertEquals("wait – ok — yes‸", type("‸", "wait -- ok --- yes", true));
        assertEquals("and so on…‸", type("‸", "and so on...", true));
        assertEquals("a….‸", type("a‸", "....", true), "four dots: the first three only once");
    }

    @Test
    void typographyLeavesMarkdownSyntaxAndCodeAlone() {
        assertEquals("---‸", type("‸", "---", true), "a rule");
        assertEquals("| --- | --‸", type("| --- | ‸", "--", true), "a table's delimiter row");
        assertEquals("- -‸", type("- ‸", "-", true));
        assertEquals("`a \"b\" --‸`", type("`a ‸`", "\"b\" --", true), "a code span");
        assertEquals("<img alt=\"x\"‸", type("<img alt=‸", "\"x\"", true), "an HTML attribute");
        assertEquals("[t](http://a--b‸", type("[t](http://a‸", "--b", true), "a link destination");
        assertEquals("\"plain\" -- ...‸", type("‸", "\"plain\" -- ...", false), "off: nothing changes");
    }

    @Test
    void theCheapTest() {
        assertTrue(MarkdownTyping.interesting('*', false));
        assertTrue(MarkdownTyping.interesting(' ', false));
        assertFalse(MarkdownTyping.interesting('"', false));
        assertTrue(MarkdownTyping.interesting('"', true));
        assertFalse(MarkdownTyping.interesting('a', true));
    }
}
