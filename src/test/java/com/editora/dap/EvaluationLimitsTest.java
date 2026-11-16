package com.editora.dap;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The evaluation time limit as each debugger is told it ({@link EvaluationLimits}). */
class EvaluationLimitsTest {

    @Test
    void gdbIsToldOnItsCommandLine() {
        assertEquals(
                List.of(
                        "-iex",
                        "set direct-call-timeout 10",
                        "-iex",
                        "set indirect-call-timeout 10",
                        "-iex",
                        "set unwind-on-timeout on"),
                EvaluationLimits.gdbArguments(10));
        assertEquals(List.of(), EvaluationLimits.gdbArguments(0), "no limit: GDB as it is");
    }

    @Test
    void aPythonLaunchGetsItInTheProgramsEnvironment() {
        Map<String, Object> launch = new LinkedHashMap<>();
        launch.put("type", "python");
        launch.put("request", "launch");
        launch.put("env", Map.of("PYTHONPATH", "/src"));
        Map<String, Object> out = EvaluationLimits.decorate(launch, 7);
        assertEquals(Map.of("PYTHONPATH", "/src", "PYDEVD_INTERRUPT_THREAD_TIMEOUT", "7"), out.get("env"));
        assertEquals(Map.of("PYTHONPATH", "/src"), launch.get("env"), "the request given is not changed");

        Map<String, Object> bare = Map.of("type", "debugpy", "request", "launch");
        assertEquals(
                Map.of("PYDEVD_INTERRUPT_THREAD_TIMEOUT", "7"),
                EvaluationLimits.decorate(bare, 7).get("env"));
    }

    @Test
    void aLaunchJsonsOwnSettingIsKept() {
        Map<String, Object> launch =
                Map.of("type", "python", "request", "launch", "env", Map.of("PYDEVD_INTERRUPT_THREAD_TIMEOUT", "60"));
        assertEquals(
                Map.of("PYDEVD_INTERRUPT_THREAD_TIMEOUT", "60"),
                EvaluationLimits.decorate(launch, 7).get("env"));
    }

    @Test
    void otherRequestsAreSentAsTheyAre() {
        Map<String, Object> attach = Map.of("type", "python", "request", "attach", "connect", Map.of("port", 5678));
        assertSame(attach, EvaluationLimits.decorate(attach, 7), "a program attached to started without it");
        Map<String, Object> lldb = Map.of("type", "lldb", "request", "launch");
        assertSame(lldb, EvaluationLimits.decorate(lldb, 7), "CodeLLDB keeps to its own");
        Map<String, Object> java = Map.of("type", "java", "request", "launch");
        assertSame(java, EvaluationLimits.decorate(java, 7));
        Map<String, Object> off = Map.of("type", "python", "request", "launch");
        assertSame(off, EvaluationLimits.decorate(off, 0), "no limit");
    }

    @Test
    void editoraWaitsPastTheLimitAndNeverUnderThirtySeconds() {
        assertEquals(Duration.ofSeconds(30), EvaluationLimits.wait(0));
        assertEquals(Duration.ofSeconds(30), EvaluationLimits.wait(10));
        assertEquals(Duration.ofSeconds(65), EvaluationLimits.wait(60));
    }
}
