package com.editora.editops;

import java.util.List;

import com.editora.editops.StringCase.Style;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StringCaseTest {

    // --- words ---

    @Test
    void wordsSplitsCamelHumps() {
        assertEquals(List.of("foo", "Bar", "Baz"), StringCase.words("fooBarBaz"));
    }

    @Test
    void wordsSplitsSeparators() {
        assertEquals(List.of("foo", "bar", "baz"), StringCase.words("foo_bar-baz"));
        assertEquals(List.of("foo", "bar"), StringCase.words("foo.bar"));
        assertEquals(List.of("foo", "bar"), StringCase.words("foo  bar"));
    }

    @Test
    void wordsKeepsAcronymsTogether() {
        assertEquals(List.of("HTTP", "Server"), StringCase.words("HTTPServer"));
        assertEquals(List.of("parse", "XML", "Doc"), StringCase.words("parseXMLDoc"));
    }

    @Test
    void wordsKeepsDigitsAttached() {
        assertEquals(List.of("foo2", "Bar"), StringCase.words("foo2Bar"));
        assertEquals(List.of("utf8", "Name"), StringCase.words("utf8Name"));
    }

    @Test
    void wordsOfEmptyOrSeparatorsOnly() {
        assertEquals(List.of(), StringCase.words(""));
        assertEquals(List.of(), StringCase.words("__--"));
    }

    // --- to ---

    @Test
    void convertsBetweenAllStyles() {
        assertEquals("fooBarBaz", StringCase.to(Style.CAMEL, "foo_bar_baz"));
        assertEquals("FooBarBaz", StringCase.to(Style.PASCAL, "foo-bar-baz"));
        assertEquals("foo_bar_baz", StringCase.to(Style.SNAKE, "fooBarBaz"));
        assertEquals("FOO_BAR_BAZ", StringCase.to(Style.SCREAMING_SNAKE, "fooBarBaz"));
        assertEquals("foo-bar-baz", StringCase.to(Style.KEBAB, "FooBarBaz"));
        assertEquals("foo.bar.baz", StringCase.to(Style.DOT, "FOO_BAR_BAZ"));
    }

    @Test
    void convertingAnAcronymNormalizesIt() {
        assertEquals("httpServer", StringCase.to(Style.CAMEL, "HTTPServer"));
        assertEquals("http_server", StringCase.to(Style.SNAKE, "HTTPServer"));
    }

    @Test
    void emptyTokenIsReturnedAsIs() {
        assertEquals("", StringCase.to(Style.SNAKE, ""));
    }

    // --- detect / cycle ---

    @Test
    void detectsEachStyle() {
        assertEquals(Style.CAMEL, StringCase.detect("fooBar"));
        assertEquals(Style.PASCAL, StringCase.detect("FooBar"));
        assertEquals(Style.SNAKE, StringCase.detect("foo_bar"));
        assertEquals(Style.SCREAMING_SNAKE, StringCase.detect("FOO_BAR"));
        assertEquals(Style.KEBAB, StringCase.detect("foo-bar"));
        assertEquals(Style.DOT, StringCase.detect("foo.bar"));
    }

    @Test
    void cycleStepsThroughTheStyles() {
        assertEquals("foo_bar", StringCase.cycle("fooBar"));
        assertEquals("FOO_BAR", StringCase.cycle("foo_bar"));
        assertEquals("foo-bar", StringCase.cycle("FOO_BAR"));
        assertEquals("FooBar", StringCase.cycle("foo-bar"));
        assertEquals("fooBar", StringCase.cycle("FooBar"));
    }

    @Test
    void cycleFromDotReentersAtCamel() {
        assertEquals("fooBar", StringCase.cycle("foo.bar"));
    }

    // --- swapCase ---

    @Test
    void swapCaseFlipsLetters() {
        assertEquals("FOObAR", StringCase.swapCase("fooBar"));
        assertEquals("a1B_c", StringCase.swapCase("A1b_C"));
    }

    // --- tokenAt ---

    @Test
    void tokenAtExpandsOverIdentifierChars() {
        String text = "int foo_bar = 1;";
        assertArrayEquals(new int[] {4, 11}, StringCase.tokenAt(text, 6));
        // Caret at the token's start and end both resolve it.
        assertArrayEquals(new int[] {4, 11}, StringCase.tokenAt(text, 4));
        assertArrayEquals(new int[] {4, 11}, StringCase.tokenAt(text, 11));
    }

    @Test
    void tokenAtIncludesDashesButNotDots() {
        String text = "x foo-bar.baz y";
        assertArrayEquals(new int[] {2, 9}, StringCase.tokenAt(text, 5));
        assertArrayEquals(new int[] {10, 13}, StringCase.tokenAt(text, 11));
    }

    @Test
    void tokenAtReturnsNullOffTokens() {
        assertNull(StringCase.tokenAt("a  b", 2));
        assertNull(StringCase.tokenAt("", 0));
    }
}
