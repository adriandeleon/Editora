package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The toolbar's stand-in for a configuration: the last run, while none is selected (r/JavaFX re-review). */
class RunConfigRerunPromptTest {

    @Test
    void theLastRunStandsInOnlyWhileNothingIsSelected() {
        assertEquals("HelloApp", RunConfigurationCoordinator.rerunPrompt(false, "HelloApp"));
        assertNull(RunConfigurationCoordinator.rerunPrompt(true, "HelloApp"), "a selected configuration wins");
        assertNull(RunConfigurationCoordinator.rerunPrompt(false, null), "nothing has run yet");
    }
}
