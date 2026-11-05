package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebugTestRouteTest {

    @Test
    void aJUnitFileDebugsTheTestAtTheCaret() {
        assertEquals(DebugTestRoute.Route.TEST_AT_CARET, DebugTestRoute.route(true, false, true, false, true));
    }

    @Test
    void outsideATestTheWholeClassIsDebugged() {
        assertEquals(DebugTestRoute.Route.TEST_CLASS, DebugTestRoute.route(true, false, true, false, false));
    }

    @Test
    void aFileWithItsOwnMainIsLaunchedEvenWithTests() {
        assertEquals(DebugTestRoute.Route.LAUNCH, DebugTestRoute.route(true, false, true, true, true));
    }

    @Test
    void aFileWithoutTestsIsLaunched() {
        assertEquals(DebugTestRoute.Route.LAUNCH, DebugTestRoute.route(true, false, false, false, false));
    }

    @Test
    void compactSourcesAndOtherLanguagesAreLaunched() {
        assertEquals(DebugTestRoute.Route.LAUNCH, DebugTestRoute.route(true, true, true, false, true));
        assertEquals(DebugTestRoute.Route.LAUNCH, DebugTestRoute.route(false, false, true, false, true));
    }
}
