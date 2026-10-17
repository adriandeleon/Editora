package com.editora.ui;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static com.editora.ui.HttpResponseTab.BODY;
import static com.editora.ui.HttpResponseTab.HEADERS;
import static com.editora.ui.HttpResponseTab.NOTES;
import static com.editora.ui.HttpResponseTab.REQUEST;
import static com.editora.ui.HttpResponseTab.TESTS;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which tab a shown response opens on, and how the arrow keys move between tabs (decision 4 of the redesign). */
class HttpResponseTabTest {

    private static final Set<HttpResponseTab> ALL = EnumSet.allOf(HttpResponseTab.class);

    @Test
    void aFailedTestSwitchesToTestsWhateverWasPicked() {
        assertEquals(TESTS, HttpResponseTab.choose(BODY, true, ALL));
        assertEquals(TESTS, HttpResponseTab.choose(HEADERS, true, ALL));
    }

    @Test
    void otherwiseThePickedTabStaysWhileTheResponseHasIt() {
        assertEquals(HEADERS, HttpResponseTab.choose(HEADERS, false, ALL));
        assertEquals(
                NOTES, HttpResponseTab.choose(NOTES, false, ALL), "warnings never switch, but a picked Notes stays");
        assertEquals(BODY, HttpResponseTab.choose(TESTS, false, EnumSet.of(BODY, HEADERS, REQUEST)), "no tests: Body");
        assertEquals(BODY, HttpResponseTab.choose(null, false, ALL));
    }

    @Test
    void aFailureWithoutATestsTabStaysOnThePickedTab() {
        assertEquals(REQUEST, HttpResponseTab.choose(REQUEST, true, EnumSet.of(BODY, REQUEST)));
    }

    @Test
    void aRequestThatGotNoResponseOpensOnBodyWhereItsCardIs() {
        assertEquals(BODY, HttpResponseTab.choose(HEADERS, false, true, ALL), "decision 4: a send failure → the card");
        assertEquals(BODY, HttpResponseTab.choose(TESTS, true, true, ALL), "a pre-request script failure too");
        assertEquals(HEADERS, HttpResponseTab.choose(HEADERS, false, false, ALL), "a response keeps the picked tab");
    }

    @Test
    void theArrowsSkipHiddenTabsAndWrap() {
        Set<HttpResponseTab> shown = EnumSet.of(BODY, HEADERS, REQUEST);
        assertEquals(HEADERS, HttpResponseTab.step(BODY, 1, shown));
        assertEquals(REQUEST, HttpResponseTab.step(HEADERS, 1, shown), "Tests is hidden");
        assertEquals(BODY, HttpResponseTab.step(REQUEST, 1, shown), "wraps");
        assertEquals(REQUEST, HttpResponseTab.step(BODY, -1, shown));
        assertEquals(BODY, HttpResponseTab.step(BODY, 1, EnumSet.of(BODY)));
        assertEquals(BODY, HttpResponseTab.end(false, shown));
        assertEquals(REQUEST, HttpResponseTab.end(true, shown));
    }
}
