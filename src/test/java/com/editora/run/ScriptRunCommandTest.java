package com.editora.run;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptRunCommandTest {

    @Test
    void aPythonScriptRunsUnderPython3() {
        assertEquals(
                List.of("python3", "/w/app/main.py", "--verbose"),
                ScriptRunCommand.build("python", "/w/app/main.py", List.of("--verbose")));
    }

    @Test
    void aShellScriptRunsUnderBash() {
        assertEquals(List.of("bash", "/w/deploy.sh"), ScriptRunCommand.build("shell", "/w/deploy.sh", List.of()));
    }

    @Test
    void aMakeTargetRunsUnderMake() {
        assertEquals(List.of("make", "test"), ScriptRunCommand.build("make", "test", List.of()));
    }

    /**
     * A blank make target is not an incomplete configuration — a bare {@code make} runs the default goal,
     * which is a perfectly ordinary thing to want.
     */
    @Test
    void aBlankMakeTargetMeansTheDefaultGoal() {
        assertEquals(List.of("make"), ScriptRunCommand.build("make", "", List.of()));
        assertEquals(List.of("make"), ScriptRunCommand.build("make", "   ", List.of()));
        assertFalse(ScriptRunCommand.needsTarget("make"), "make is launchable without one");
    }

    /** A script type with no target has nothing to run; an empty argv tells the caller to say so. */
    @Test
    void aMissingScriptPathYieldsNothingToLaunch() {
        assertTrue(ScriptRunCommand.build("python", "", List.of()).isEmpty());
        assertTrue(ScriptRunCommand.build("shell", null, List.of()).isEmpty());
        assertTrue(ScriptRunCommand.needsTarget("python"));
        assertTrue(ScriptRunCommand.needsTarget("shell"));
    }

    /** Java takes the jdtls path, so this builder must decline it rather than invent an argv. */
    @Test
    void javaIsNotAScriptType() {
        assertFalse(ScriptRunCommand.isScript("java"));
        assertTrue(ScriptRunCommand.build("java", "com.example.App", List.of()).isEmpty());
    }

    @Test
    void anUnknownTypeLaunchesNothing() {
        assertTrue(ScriptRunCommand.build("perl", "/w/x.pl", List.of()).isEmpty());
        assertTrue(ScriptRunCommand.build(null, "/w/x", List.of()).isEmpty());
        assertFalse(ScriptRunCommand.isScript(null));
    }

    @Test
    void argumentsAreAppendedAfterTheTarget() {
        assertEquals(List.of("make", "build", "-j4"), ScriptRunCommand.build("make", "build", List.of("-j4")));
        assertEquals(
                List.of("bash", "/w/x.sh", "a", "b"), ScriptRunCommand.build("shell", "/w/x.sh", List.of("a", "b")));
    }
}
