package com.editora.dap;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link DapManager.HostPython}: a configured interpreter command split the way debugpy takes it. */
class HostPythonTest {

    @Test
    void aBareInterpreterIsJustThat() {
        var python = DapManager.HostPython.parse(List.of("python3.12"));

        assertEquals("python3.12", python.executable());
        assertEquals(List.of(), python.args());
        assertEquals(Map.of(), python.env());
    }

    @Test
    void envAssignmentsAndInterpreterArgumentsAreSeparated() {
        var python = DapManager.HostPython.parse(
                List.of("env", "PYTHONPATH=/opt/site", "DEBUG=1", "/usr/bin/python3", "-X", "dev"));

        assertEquals("/usr/bin/python3", python.executable());
        assertEquals(List.of("-X", "dev"), python.args());
        assertEquals(Map.of("PYTHONPATH", "/opt/site", "DEBUG", "1"), python.env());
        assertEquals(
                List.of("/usr/bin/python3", "-X", "dev", "-m", "debugpy.adapter"),
                python.argv(List.of("-m", "debugpy.adapter")));
    }

    @Test
    void anEnvWithNothingAfterItFallsBackToPython3() {
        assertEquals(
                "python3", DapManager.HostPython.parse(List.of("env", "A=1")).executable());
    }
}
