package com.editora.snippet;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void aFinalStopKeepsItsDefaultAsARange() {
        // jdtls sends exactly this for an import package proposal: the '*' is text to be selected, not a
        // bare caret position. The session relies on the range being non-empty to select rather than move.
        ParsedSnippet p = SnippetParser.parse("java.util.${0:*};", NONE);
        assertEquals("java.util.*;", p.text());
        assertArrayEquals(new int[] {10, 11}, stop(p, 0).ranges().get(0));
        assertEquals("*", stop(p, 0).placeholder());
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
    void transformOccurrenceIsDerivedFromTheValue() {
        ParsedSnippet p = SnippetParser.parse("${1:x}=${1/.*/UP/}", NONE);
        assertEquals("x=UP", p.text()); // the second occurrence is a transform of the value, not a mirror
        TabStop s = stop(p, 1);
        assertEquals(2, s.ranges().size());
        assertEquals(0, s.primaryIndex()); // the ${1:x} placeholder is the editable field
        assertNotNull(s.transformAt(1)); // the second occurrence carries the transform
    }

    @Test
    void transformBeforeTheValueDefiningPlaceholderStillResolves() {
        // the transform occurrence is emitted first, but the value comes from the later ${1:collection} (#642)
        ParsedSnippet p = SnippetParser.parse("${1/(.*)/$1Item/} in ${1:collection}", NONE);
        assertEquals("collectionItem in collection", p.text());
        TabStop s = stop(p, 1);
        assertEquals(1, s.primaryIndex()); // the editable field is the second (non-transform) occurrence
        assertEquals("collection", s.placeholder());
    }

    @Test
    void aLeadingBareStopNoLongerStealsTheValueSlot() {
        // $1 emitted before ${1:default}: the default must still define the value (#642)
        assertEquals(
                "default and default",
                SnippetParser.parse("$1 and ${1:default}", NONE).text());
    }

    @Test
    void malformedTransformFallsBackToAPlainMirror() {
        ParsedSnippet p = SnippetParser.parse("${1:x}=${1/unterminated}", NONE);
        assertEquals("x=x", p.text());
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

    // --- variables: transforms and defaults ---

    @Test
    void aVariableTransformRewritesTheValueInsteadOfLeakingTheRegex() {
        SnippetParser.Variables vars = name -> switch (name) {
            case "TM_DIRECTORY" -> "/home/u/proj/src";
            case "TM_FILENAME_BASE" -> "foo_bar";
            case "TM_FILENAME" -> "foo_bar.h";
            default -> null;
        };
        // The bundled C++ `#guard` line.
        ParsedSnippet p = SnippetParser.parse(
                "#ifndef INCLUDE${TM_DIRECTORY/.*[\\/\\\\](.*)/_${1:/upcase}/}${TM_FILENAME_BASE/(.*)/_${1:/upcase}/}"
                        + "${TM_FILENAME/.*\\.(.*)/_${1:/upcase}/}_\n$0",
                vars);
        assertEquals("#ifndef INCLUDE_SRC_FOO_BAR_H_\n", p.text());
        assertNull(stop(p, 1), "the transform's group reference is not a tab stop");
        assertNotNull(stop(p, 0));
    }

    @Test
    void aTransformOfAnUnknownVariableWorksOnTheEmptyString() {
        assertEquals("[]", SnippetParser.parse("[${NOPE/(.+)/<$1>/}]", NONE).text());
        assertEquals("[]", SnippetParser.parse("[${NOPE/unterminated}]", NONE).text());
    }

    @Test
    void theDefaultStandsInForAnEmptyVariableToo() {
        SnippetParser.Variables vars = name -> name.startsWith("TM_") ? "" : null;
        assertEquals(
                "class MyClass {}",
                SnippetParser.parse("class ${TM_FILENAME_BASE:MyClass} {}", vars)
                        .text());
        ParsedSnippet p = SnippetParser.parse("{\n\t${0:${TM_SELECTED_TEXT:body}}\n}", vars);
        assertEquals("{\n\tbody\n}", p.text());
        assertArrayEquals(new int[] {3, 7}, stop(p, 0).ranges().get(0));
        assertEquals(
                "class Foo {}",
                SnippetParser.parse("class ${TM_FILENAME_BASE:MyClass} {}", name -> "Foo")
                        .text());
    }

    @Test
    void spansRecordWhichStopContainsWhich() {
        // Identical offsets, different structure: $1 nested at the start of $2, versus $1 in front of $2.
        ParsedSnippet nested = SnippetParser.parse("${2:${1}foo}", NONE);
        ParsedSnippet adjacent = SnippetParser.parse("${1}${2:foo}", NONE);
        assertArrayEquals(
                stop(nested, 1).ranges().get(0), stop(adjacent, 1).ranges().get(0));
        assertArrayEquals(
                stop(nested, 2).ranges().get(0), stop(adjacent, 2).ranges().get(0));
        int[] in = stop(nested, 1).spans().get(0);
        int[] out = stop(nested, 2).spans().get(0);
        assertTrue(out[0] < in[0] && in[1] < out[1], "$2 opens before and closes after $1");
        int[] first = stop(adjacent, 1).spans().get(0);
        int[] second = stop(adjacent, 2).spans().get(0);
        assertTrue(first[1] < second[0], "$1 closes before $2 opens");
    }
}
