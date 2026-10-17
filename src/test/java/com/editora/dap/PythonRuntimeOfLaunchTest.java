package com.editora.dap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PythonRuntimeOfLaunchTest {

    @Test
    void theInterpreterAndItsArgumentsAreWhatTheRuntimeChipAsks() {
        Map<String, Object> launch = new LinkedHashMap<>();
        launch.put("python", "/w/.venv/bin/python");
        launch.put("pythonArgs", List.of("-X", "dev"));
        assertEquals(List.of("/w/.venv/bin/python", "-X", "dev"), DapManager.pythonRuntime(launch));
    }

    @Test
    void aLaunchWithoutAnInterpreterHasNoRuntimeToAsk() {
        assertEquals(List.of(), DapManager.pythonRuntime(Map.of("program", "app.py")));
        assertEquals(List.of(), DapManager.pythonRuntime(Map.of("python", " ")));
    }
}
