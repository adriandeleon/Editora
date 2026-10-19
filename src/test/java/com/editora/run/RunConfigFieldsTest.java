package com.editora.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunConfigFieldsTest {

    @Test
    void javaUsesTheJavaFieldsAndNoTarget() {
        assertTrue(RunConfigFields.usesJavaFields("java"));
        assertFalse(RunConfigFields.usesTarget("java"));
    }

    @Test
    void scriptTypesUseTheTargetAndNoJavaFields() {
        for (String type : new String[] {"python", "shell", "make", "npm"}) {
            assertTrue(RunConfigFields.usesTarget(type), type);
            assertFalse(RunConfigFields.usesJavaFields(type), type);
        }
    }

    @Test
    void anUnknownTypeLocksNothingAway() {
        assertTrue(RunConfigFields.usesTarget("gradle"));
        assertTrue(RunConfigFields.usesJavaFields("gradle"));
        assertTrue(RunConfigFields.usesTarget(null));
        assertTrue(RunConfigFields.usesJavaFields(null));
    }

    @Test
    void aNodeConfigurationTakesAScriptFileAnInterpreterAndNodeArguments() {
        assertTrue(RunConfigFields.usesTarget("node"));
        assertTrue(RunConfigFields.usesScriptFile("node"));
        assertTrue(RunConfigFields.usesInterpreter("node"));
        assertTrue(!RunConfigFields.usesJavaFields("node"));
        assertEquals("settings.runConfig.targetPromptNode", RunConfigPrompts.target("node"));
        assertEquals("settings.runConfig.targetPromptNpm", RunConfigPrompts.target("npm"));
        assertEquals("settings.runConfig.targetPrompt", RunConfigPrompts.target("make"));
        assertEquals("settings.runConfig.interpreterPromptNode", RunConfigPrompts.interpreter("node"));
        assertEquals("settings.runConfig.interpreterOptionsPromptNode", RunConfigPrompts.interpreterOptions("node"));
        assertEquals("settings.runConfig.workingDirPromptNode", RunConfigPrompts.workingDir("node"));
        assertTrue(RunConfigPrompts.browses("npm"));
        assertTrue(!RunConfigPrompts.browses("make"));
    }
}
