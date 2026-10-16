package com.editora.dap;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PythonLaunch}: the debugpy launch request for a script or a module. */
class PythonLaunchTest {

    private static DapManager.HostPython python(String... command) {
        return DapManager.HostPython.parse(List.of(command));
    }

    @Test
    void aScriptRunsUnderTheInterpreterTakenApartWithoutDebuggingItsChildren() {
        Map<String, Object> m = PythonLaunch.request(
                PythonLaunch.Target.script("/p/app.py", List.of("a b"), "/p"),
                python("env", "K=v", "/venv/bin/python", "-X", "dev"),
                List.of(),
                "",
                ":",
                false);

        assertEquals("python", m.get("type"));
        assertEquals("/p/app.py", m.get("program"));
        assertFalse(m.containsKey("module"));
        assertEquals("/p", m.get("cwd"));
        assertEquals(List.of("a b"), m.get("args"));
        assertEquals("/venv/bin/python", m.get("python"));
        assertEquals(List.of("-X", "dev"), m.get("pythonArgs"));
        assertEquals(Map.of("K", "v", "PYTHONPATH", ""), m.get("env"));
        assertEquals("internalConsole", m.get("console"));
        assertEquals(false, m.get("subProcess"));
    }

    @Test
    void aModuleReplacesTheProgram() {
        Map<String, Object> m = PythonLaunch.request(
                PythonLaunch.Target.module("pytest", List.of("tests/test_a.py::test_x"), "/p"),
                python("python3"),
                List.of(),
                "",
                ":",
                false);

        assertEquals("pytest", m.get("module"));
        assertFalse(m.containsKey("program"));
        assertFalse(m.containsKey("pythonArgs"));
    }

    @Test
    void theProjectGoesFirstOnPythonPathAndTheUsersOwnIsKept() {
        Map<String, String> env =
                PythonLaunch.environment(python("python3"), List.of("/p", "/p/src"), "/mine:/p", ":", false);

        assertEquals("/p:/p/src:/mine", env.get("PYTHONPATH"));
    }

    @Test
    void theCommandsOwnPythonPathStandsForTheInheritedOne() {
        Map<String, String> env = PythonLaunch.environment(
                python("env", "PYTHONPATH=/site", "python3"), List.of("/p"), "/inherited", ":", false);

        assertEquals("/p:/site", env.get("PYTHONPATH"));
    }

    @Test
    void anUnknownHostPythonPathIsLeftAloneWhenThereIsNothingToAdd() {
        assertFalse(PythonLaunch.environment(python("python3"), List.of(), null, ":", false)
                .containsKey("PYTHONPATH"));
        assertEquals(
                "/p",
                PythonLaunch.environment(python("python3"), List.of("/p"), null, ":", false)
                        .get("PYTHONPATH"));
    }

    @Test
    void theProgramConsoleMakesOutputUnbuffered() {
        Map<String, Object> m = PythonLaunch.request(
                PythonLaunch.Target.script("/p/app.py", List.of(), "/p"), python("python3"), List.of(), "", ":", true);

        assertEquals("integratedTerminal", m.get("console"));
        @SuppressWarnings("unchecked")
        Map<String, String> env = (Map<String, String>) m.get("env");
        assertEquals("1", env.get("PYTHONUNBUFFERED"));
    }

    @Test
    void windowsPathListsUseSemicolons() {
        assertEquals("C:\\p;C:\\p\\src;D:\\x", PythonLaunch.joinPath(List.of("C:\\p", "C:\\p\\src"), "D:\\x;", ";"));
        assertTrue(PythonLaunch.joinPath(List.of(), "", ":").isEmpty());
    }

    @Test
    void aTimeoutSaysWhichInterpreterDidNotRun() {
        String text = DapManager.launchFailure(
                new java.util.concurrent.CompletionException(new java.util.concurrent.TimeoutException()),
                Map.of("python", "/usr/bin/env"));

        assertTrue(text.contains("/usr/bin/env"), text);
        assertFalse(text.contains("TimeoutException"), text);
    }
}
