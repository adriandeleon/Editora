package com.editora.ui;

import java.util.List;

import com.editora.dap.DapManager.State;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What F5, Debug File and Debug Test do for the launch there is (review A106: a launch still starting is a session
 * too; review 3, C202–C204, A208, A209: one check for every way to start).
 */
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

    /** Review 3, C202: the editor menu's Debug File ended a paused session in another file without asking. */
    @Test
    void debugFileNeverContinuesAndAsksBeforeReplacingAnyLiveLaunch() {
        assertEquals(DebugStartChoice.LAUNCH, DebugStartChoice.forFile(false, State.INACTIVE, false, true));
        assertEquals(DebugStartChoice.ASK_RETARGET, DebugStartChoice.forFile(true, State.SUSPENDED, false, true));
        assertEquals(DebugStartChoice.ASK_RETARGET, DebugStartChoice.forFile(true, State.RUNNING, false, true));
        assertEquals(DebugStartChoice.ASK_RETARGET, DebugStartChoice.forFile(false, State.INACTIVE, true, true));
        assertEquals(DebugStartChoice.ASK_RETARGET, DebugStartChoice.forFile(false, State.STARTING, false, true));
        // its own launch: said, not continued and not started twice
        assertEquals(DebugStartChoice.ALREADY_RUNNING, DebugStartChoice.forFile(true, State.SUSPENDED, false, false));
        assertEquals(DebugStartChoice.ALREADY_RUNNING, DebugStartChoice.forFile(false, State.INACTIVE, true, false));
    }

    /** Review 3, A209 / C204: a launch still building or showing its picker was said to be "running". */
    @Test
    void theWordingFollowsHowFarTheLaunchHasGot() {
        assertEquals(DebugStartChoice.Phase.STARTING, DebugStartChoice.Phase.of(false, State.INACTIVE, false, true));
        assertEquals(DebugStartChoice.Phase.STARTING, DebugStartChoice.Phase.of(false, State.STARTING, false, false));
        assertEquals(DebugStartChoice.Phase.STARTING, DebugStartChoice.Phase.of(true, State.STARTING, false, false));
        assertEquals(DebugStartChoice.Phase.RUNNING, DebugStartChoice.Phase.of(true, State.RUNNING, false, false));
        assertEquals(DebugStartChoice.Phase.PAUSED, DebugStartChoice.Phase.of(true, State.SUSPENDED, false, false));
        assertEquals(DebugStartChoice.Phase.PAUSED, DebugStartChoice.Phase.of(true, State.RUNNING, true, false));
        for (DebugStartChoice.Phase phase : DebugStartChoice.Phase.values()) {
            for (String key : List.of(phase.busyKey, phase.retargetKey)) {
                String text = tr(key, "Main.java", "Other.java");
                assertTrue(text.startsWith("Main.java "), key + ": " + text);
            }
        }
        assertTrue(tr(DebugStartChoice.Phase.STARTING.busyKey, "Main.java").contains("still starting"));
    }
}
