package com.editora.dap;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchConfigTest {

    @Test
    void launchEmitsRequiredFieldsAndOmitsBlankOptionals() {
        Map<String, Object> m =
                LaunchConfig.launch("com.example.Main", "", List.of(), List.of(), "", "", List.of(), false);
        assertEquals("java", m.get("type"));
        assertEquals("launch", m.get("request"));
        assertEquals("com.example.Main", m.get("mainClass"));
        assertEquals("internalConsole", m.get("console"));
        assertEquals(false, m.get("stopOnEntry"));
        // Blank/empty optionals are omitted entirely.
        assertFalse(m.containsKey("projectName"));
        assertFalse(m.containsKey("classPaths"));
        assertFalse(m.containsKey("modulePaths"));
        assertFalse(m.containsKey("javaExec"));
        assertFalse(m.containsKey("cwd"));
        assertFalse(m.containsKey("args"));
    }

    @Test
    void launchIncludesProvidedOptionals() {
        Map<String, Object> m = LaunchConfig.launch(
                "p.Main",
                "proj",
                List.of("/cp/a.jar", "/cp/b.jar"),
                List.of("/mp/m.jar"),
                "/usr/bin/java",
                "/work",
                List.of("--flag", "x"),
                true);
        assertEquals("proj", m.get("projectName"));
        assertEquals(List.of("/cp/a.jar", "/cp/b.jar"), m.get("classPaths"));
        assertEquals(List.of("/mp/m.jar"), m.get("modulePaths"));
        assertEquals("/usr/bin/java", m.get("javaExec"));
        assertEquals("/work", m.get("cwd"));
        assertEquals(List.of("--flag", "x"), m.get("args")); // the argv, not a joined string
        assertEquals(true, m.get("stopOnEntry"));
    }

    @Test
    void javaLaunchPassesArgvSoAnArgumentWithSpacesStaysOneArgument() {
        // `"hello world" second` — ProgramArgs.tokenize already resolved the quoting into 2 arguments, and
        // Run passes exactly those. Joining them back on a space is lossy: main() would receive 3. The
        // collision is the proof — [hello world, second] and [hello, world, second] join to one same string.
        List<String> argv = List.of("hello world", "second");
        Map<String, Object> m = LaunchConfig.launch("A", "", List.of(), List.of(), "", "", argv, false);
        assertEquals(argv, m.get("args"), "the debuggee must get the same argv the Run feature passes");

        // The same argv reaches debugpy/node the same way (this sibling was always correct).
        Map<String, Object> p = LaunchConfig.program("python", "/x/s.py", "/x", "python3", argv, false);
        assertEquals(argv, p.get("args"), "java and program(...) must agree");
    }

    @Test
    void attachDefaultsBlankHostToLocalhost() {
        Map<String, Object> m = LaunchConfig.attach("", 5005);
        assertEquals("java", m.get("type"));
        assertEquals("attach", m.get("request"));
        assertEquals("localhost", m.get("hostName"));
        assertEquals(5005, m.get("port"));
    }

    @Test
    void attachKeepsExplicitHost() {
        Map<String, Object> m = LaunchConfig.attach("10.0.0.5", 9000);
        assertEquals("10.0.0.5", m.get("hostName"));
        assertEquals(9000, m.get("port"));
        assertTrue(m.containsKey("request"));
    }

    @Test
    void programForPythonEmitsInterpreterAsPython() {
        Map<String, Object> m = LaunchConfig.program("python", "/work/app.py", "/work", "/usr/bin/python3", true);
        assertEquals("python", m.get("type"));
        assertEquals("launch", m.get("request"));
        assertEquals("/work/app.py", m.get("program"));
        assertEquals("/work", m.get("cwd"));
        assertEquals("/usr/bin/python3", m.get("python")); // interpreter under the "python" key
        assertFalse(m.containsKey("runtimeExecutable"));
        assertEquals("internalConsole", m.get("console"));
        assertEquals(true, m.get("stopOnEntry"));
    }

    @Test
    void programForNodeEmitsRuntimeExecutable() {
        Map<String, Object> m = LaunchConfig.program("pwa-node", "/work/app.js", "/work", "/usr/local/bin/node", false);
        assertEquals("pwa-node", m.get("type"));
        assertEquals("/work/app.js", m.get("program"));
        assertEquals("/usr/local/bin/node", m.get("runtimeExecutable")); // node under "runtimeExecutable"
        assertFalse(m.containsKey("python"));
        assertEquals(false, m.get("stopOnEntry"));
    }

    @Test
    void programOmitsBlankCwdAndInterpreter() {
        Map<String, Object> m = LaunchConfig.program("python", "/a.py", "", "", false);
        assertEquals("/a.py", m.get("program"));
        assertFalse(m.containsKey("cwd"));
        assertFalse(m.containsKey("python"));
        assertFalse(m.containsKey("runtimeExecutable"));
    }
}
