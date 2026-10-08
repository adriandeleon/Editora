package com.editora.run;

import org.junit.jupiter.api.Test;

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
}
