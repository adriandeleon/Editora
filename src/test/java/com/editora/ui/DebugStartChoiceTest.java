package com.editora.ui;

import com.editora.dap.DapManager.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What F5 does for the session there is (review A106: a launch still starting is a session too). */
class DebugStartChoiceTest {

    @Test
    void withNoSessionF5Launches() {
        assertEquals(DebugStartChoice.LAUNCH, DebugStartChoice.of(false, State.INACTIVE, false, false, true, true));
    }

    @Test
    void aPausedOrSteppingSessionIsContinued() {
        assertEquals(DebugStartChoice.CONTINUE, DebugStartChoice.of(true, State.SUSPENDED, false, false, true, true));
        assertEquals(DebugStartChoice.CONTINUE, DebugStartChoice.of(true, State.RUNNING, true, false, true, true));
    }

    @Test
    void anotherDebuggableFileAsksBeforeReplacingARunningSession() {
        assertEquals(DebugStartChoice.ASK_RETARGET, DebugStartChoice.of(true, State.RUNNING, false, false, true, true));
        assertEquals(
                DebugStartChoice.ALREADY_RUNNING, DebugStartChoice.of(true, State.RUNNING, false, false, false, true));
        assertEquals(
                DebugStartChoice.ALREADY_RUNNING, DebugStartChoice.of(true, State.RUNNING, false, false, true, false));
    }

    @Test
    void aLaunchStillStartingOrBuildingCountsAsASession() {
        // compiling, waiting for jdtls, or showing the main-class picker: no connection yet
        assertEquals(
                DebugStartChoice.ASK_RETARGET, DebugStartChoice.of(false, State.STARTING, false, false, true, true));
        assertEquals(
                DebugStartChoice.ALREADY_RUNNING,
                DebugStartChoice.of(false, State.STARTING, false, false, false, true));
        // the jdtls build before a project launch
        assertEquals(
                DebugStartChoice.ASK_RETARGET, DebugStartChoice.of(false, State.INACTIVE, false, true, true, true));
    }
}
