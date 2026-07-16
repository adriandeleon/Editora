package com.editora.snippet;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Unit tests for the pure snippet body parser (no toolkit). */
class SnippetParserTest {

    private static final SnippetParser.Variables NONE = name -> null;

    private static TabStop stop(ParsedSnippet p, int number) {
        return p.stops().stream().filter(s -> s.number() == number).findFirst().orElse(null);
    }

    @Test
    void plainTabStopsOrderedWithFinalLast() {
        ParsedSnippet p = SnippetParser.parse("a$1b$2c$0", NONE);
        assertEquals("abc", p.text());
        assertEquals(List.of(1, 2, 0), p.stops().stream().map(TabStop::number).toList());
        assertArrayEquals(new int[] {1, 1}, stop(p, 1).ranges().get(0));
        assertArrayEquals(new int[] {3, 3}, stop(p, 0).ranges().get(0));
    }

    @Test
    void placeholderDefault() {
        ParsedSnippet p = SnippetParser.parse("x${1:foo}y", NONE);
        assertEquals("xfooy", p.text());
        assertArrayEquals(new int[] {1, 4}, stop(p, 1).ranges().get(0));
        assertEquals("foo", stop(p, 1).placeholder());
    }

    @Test
    void mirrorReusesFirstText() {
        ParsedSnippet p = SnippetParser.parse("${1:a}-$1", NONE);
        assertEquals("a-a", p.text());
        TabStop s = stop(p, 1);
        assertEquals(2, s.ranges().size());
        assertArrayEquals(new int[] {0, 1}, s.ranges().get(0));
        assertArrayEquals(new int[] {2, 3}, s.ranges().get(1));
    }

    @Test
    void variableResolvedAndDefaulted() {
        SnippetParser.Variables vars = name -> name.equals("NAME") ? "Bob" : null;
        assertEquals("Bob!", SnippetParser.parse("$NAME!", vars).text());
        assertEquals("X", SnippetParser.parse("${BAR:def}", name -> "X").text());
        assertEquals("def", SnippetParser.parse("${FOO:def}", NONE).text());
        assertEquals("", SnippetParser.parse("$UNKNOWN", NONE).text());
    }

    @Test
    void escapes() {
        ParsedSnippet p = SnippetParser.parse("\\$1 \\} \\\\", NONE);
        assertEquals("$1 } \\", p.text());
        assertEquals(0, p.stops().size());
    }

    @Test
    void unterminatedDollarBraceAtEndIsLiteralNotACrash() {
        // "${" with nothing after it must not StringIndexOutOfBounds — treat it as literal text.
        assertEquals("foo${", SnippetParser.parse("foo${", NONE).text());
        assertEquals("${", SnippetParser.parse("${", NONE).text());
    }

    @Test
    void overlongTabStopNumberIsTreatedAsLiteral() {
        // A literal dollar amount: "$" + 11 digits overflows an int — must not throw NumberFormatException.
        ParsedSnippet p = SnippetParser.parse("Price: $12345678901", NONE);
        assertEquals("Price: $12345678901", p.text());
        assertEquals(0, p.stops().size());
        assertEquals(
                "${999999999999:x}",
                SnippetParser.parse("${999999999999:x}", NONE).text());
    }

    @Test
    void choiceUsesFirstOptionAndCapturesAll() {
        ParsedSnippet p = SnippetParser.parse("${1|alpha,beta,gamma|}", NONE);
        assertEquals("alpha", p.text());
        assertArrayEquals(new int[] {0, 5}, stop(p, 1).ranges().get(0));
        assertEquals(List.of("alpha", "beta", "gamma"), stop(p, 1).choices());
        assertEquals(true, stop(p, 1).hasChoices());
    }

    @Test
    void choiceHonorsEscapedComma() {
        ParsedSnippet p = SnippetParser.parse("${1|a\\,b,c|}", NONE);
        assertEquals(List.of("a,b", "c"), stop(p, 1).choices());
        assertEquals("a,b", p.text());
    }

    @Test
    void transformIsIgnoredNotCrashing() {
        ParsedSnippet p = SnippetParser.parse("${1:x}=${1/.*/UP/}", NONE);
        assertEquals("x=x", p.text()); // transform ignored, second occurrence mirrors "x"
        assertEquals(2, stop(p, 1).ranges().size());
    }

    @Test
    void bracedSimpleStop() {
        ParsedSnippet p = SnippetParser.parse("a${1}b", NONE);
        assertEquals("ab", p.text());
        assertNotNull(stop(p, 1));
        assertArrayEquals(new int[] {1, 1}, stop(p, 1).ranges().get(0));
    }

    @Test
    void multilineKeepsNewlines() {
        ParsedSnippet p = SnippetParser.parse("for {\n\t$0\n}", NONE);
        assertEquals("for {\n\t\n}", p.text());
        assertArrayEquals(new int[] {7, 7}, stop(p, 0).ranges().get(0));
    }
}
