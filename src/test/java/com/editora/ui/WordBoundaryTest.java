package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WordBoundaryTest {

    @Test
    void nextWordSkipsSpacesThenWord() {
        // "hello world" — from caret 0, first word ends at index 5
        assertEquals(5, MainController.nextWordBoundary("hello world", 0));
        // from caret 5 (after "hello"), skip the space and "world" -> 11
        assertEquals(11, MainController.nextWordBoundary("hello world", 5));
    }

    @Test
    void nextWordAtEndStaysPut() {
        assertEquals(11, MainController.nextWordBoundary("hello world", 11));
    }

    @Test
    void nextWordSkipsLeadingPunctuation() {
        assertEquals(7, MainController.nextWordBoundary("...word!!", 0));
    }

    @Test
    void prevWordSkipsSpacesThenWord() {
        // from caret 11 (end), back through "world" to start of word -> 6
        assertEquals(6, MainController.prevWordBoundary("hello world", 11));
        // from caret 6 (start of "world"), back through the space and "hello" -> 0
        assertEquals(0, MainController.prevWordBoundary("hello world", 6));
    }

    @Test
    void prevWordAtStartStaysPut() {
        assertEquals(0, MainController.prevWordBoundary("hello world", 0));
    }
}
