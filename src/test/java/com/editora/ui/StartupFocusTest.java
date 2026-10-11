package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The show-time focus repeats nothing the startup path did; every later focus is a regain. */
class StartupFocusTest {

    @Test
    void aWindowOpeningOnWelcomeComputesItsRepositoryStateOnTheShowTimeFocus() {
        StartupFocus focus = new StartupFocus();
        assertEquals(StartupFocus.Action.STARTUP_REFRESH, focus.onFocusGained(), "nothing computed it yet");
        assertEquals(StartupFocus.Action.REGAIN, focus.onFocusGained(), "the next focus is a real regain");
        assertEquals(StartupFocus.Action.REGAIN, focus.onFocusGained());
    }

    @Test
    void aRestoredSessionsTabSwitchAlreadyComputedIt() {
        StartupFocus focus = new StartupFocus();
        focus.repositoryStateComputed(); // the tab-switch fan-out ran git status + build detection
        assertEquals(StartupFocus.Action.NONE, focus.onFocusGained(), "the show-time focus repeats nothing");
        assertEquals(StartupFocus.Action.REGAIN, focus.onFocusGained());
    }

    @Test
    void resetReplaysTheStartupSequence() {
        StartupFocus focus = new StartupFocus();
        focus.onFocusGained();
        focus.onFocusGained();
        focus.resetForTest();
        assertEquals(StartupFocus.Action.STARTUP_REFRESH, focus.onFocusGained());
    }
}
