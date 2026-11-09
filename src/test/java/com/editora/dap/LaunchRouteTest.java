package com.editora.dap;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which debugger runs each launch.json configuration, and what it is sent. */
class LaunchRouteTest {

    private static Map<String, Object> config(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    @Test
    void eachTypeGoesToItsDebugger() {
        Map<String, DebugLanguage> none = Map.of();
        LaunchRoute.Route lldb = LaunchRoute.of(
                config(
                        "name",
                        "a",
                        "type",
                        "lldb",
                        "request",
                        "launch",
                        "program",
                        "/p",
                        "preLaunchTask",
                        "b",
                        "console",
                        "integratedTerminal"),
                none);
        assertEquals(LaunchRoute.Kind.CODELLDB, lldb.kind());
        assertSame(DebugLanguages.CPP, lldb.profile());
        assertEquals(Map.of("type", "lldb", "request", "launch", "program", "/p"), lldb.request(), "VS Code's own out");
        assertSame(
                DebugLanguages.RUST,
                LaunchRoute.of(config("type", "lldb", "cargo", Map.of()), none).profile(),
                "a cargo build is Rust's");
        assertSame(
                DebugLanguages.RUST,
                LaunchRoute.of(config("type", "lldb", "sourceLanguages", List.of("rust")), none)
                        .profile());
        assertEquals(
                LaunchRoute.Kind.DELVE,
                LaunchRoute.of(config("type", "go", "mode", "exec"), none).kind());
        assertEquals(
                LaunchRoute.Kind.UNSUPPORTED,
                LaunchRoute.of(config("type", "go", "mode", "remote"), none).kind());
        LaunchRoute.Route py = LaunchRoute.of(config("type", "debugpy", "request", "attach"), none);
        assertEquals(LaunchRoute.Kind.DEBUGPY, py.kind());
        assertTrue(py.attach());
        assertEquals("python", py.request().get("type"));
        assertEquals(
                "pwa-node",
                LaunchRoute.of(config("type", "node"), none).request().get("type"));
        assertEquals(
                LaunchRoute.Kind.JAVA,
                LaunchRoute.of(config("type", "java"), none).kind());
        assertEquals(
                LaunchRoute.Kind.UNSUPPORTED,
                LaunchRoute.of(config("type", "java", "request", "attach"), none)
                        .kind());
        LaunchRoute.Route chrome = LaunchRoute.of(config("type", "chrome"), none);
        assertEquals(LaunchRoute.Kind.UNSUPPORTED, chrome.kind());
        assertEquals("type \"chrome\"", chrome.reason());
        assertEquals(
                "a configuration without a type", LaunchRoute.of(config(), none).reason());
        DebugLanguage mine = ExternalAdapterTest.profile(
                DapServerRegistry.Kind.EXTERNAL,
                new ExternalAdapter(List.of("x"), ExternalAdapter.Transport.STDIO, null));
        LaunchRoute.Route custom = LaunchRoute.of(config("type", "mine"), Map.of("mine", mine));
        assertEquals(LaunchRoute.Kind.CUSTOM, custom.kind());
        assertSame(mine, custom.profile());
    }

    @Test
    void gdbAndCppdbgAreToldInGdbsOwnFields() {
        LaunchRoute.Route cppdbg = LaunchRoute.of(
                config(
                        "type", "cppdbg",
                        "request", "launch",
                        "MIMode", "gdb",
                        "program", "/p/app",
                        "args", List.of("-v"),
                        "cwd", "/p",
                        "stopAtEntry", true,
                        "environment", List.of(Map.of("name", "A", "value", "1"), Map.of("name", "B"))),
                Map.of());
        assertEquals(LaunchRoute.Kind.GDB, cppdbg.kind());
        assertSame(DebugLanguages.GDB, cppdbg.profile());
        assertEquals(
                Map.of(
                        "type", "gdb",
                        "request", "launch",
                        "program", "/p/app",
                        "args", List.of("-v"),
                        "cwd", "/p",
                        "env", Map.of("A", "1", "B", ""),
                        "stopAtBeginningOfMainSubprogram", true),
                cppdbg.request());
        LaunchRoute.Route attach = LaunchRoute.of(
                config("type", "gdb", "request", "attach", "processId", "4242", "program", "/p/app"), Map.of());
        assertEquals(Map.of("type", "gdb", "request", "attach", "pid", 4242L, "program", "/p/app"), attach.request());
        assertEquals(
                "${command:pickProcess}",
                LaunchRoute.gdbRequest(config("processId", "${command:pickProcess}"), true)
                        .get("pid"),
                "not a number: as it is");
        assertEquals(0L, LaunchRoute.gdbRequest(config(), true).get("pid"));

        LaunchRoute.Route lldb = LaunchRoute.of(
                config("type", "cppdbg", "MIMode", "lldb", "program", "/p/app", "env", Map.of("C", 3)), Map.of());
        assertEquals(LaunchRoute.Kind.CODELLDB, lldb.kind());
        assertEquals("lldb", lldb.request().get("type"));
        assertEquals(Map.of("C", "3"), lldb.request().get("env"));
        assertEquals(false, lldb.request().get("stopOnEntry"));
        assertEquals(List.of("cpp"), lldb.request().get("sourceLanguages"));
        assertEquals(
                Map.of("type", "lldb", "request", "attach", "pid", 7L),
                LaunchRoute.codelldbFromCppdbg(config("processId", 7), true));
        assertFalse(
                LaunchRoute.gdbRequest(config("program", "x"), false).containsKey("stopAtBeginningOfMainSubprogram"));
    }

    @Test
    void cargosExecutableIsTheOneTheFilterLeaves() {
        List<RustLaunch.Artifact> built = List.of(
                new RustLaunch.Artifact("app", List.of("bin"), Path.of("/p/src/main.rs"), Path.of("/t/app"), false),
                new RustLaunch.Artifact(
                        "tool", List.of("bin"), Path.of("/p/src/bin/tool.rs"), Path.of("/t/tool"), false),
                new RustLaunch.Artifact(
                        "app", List.of("bin"), Path.of("/p/src/main.rs"), Path.of("/t/app-test"), true));
        assertEquals(Path.of("/t/app"), LaunchRoute.cargoExecutable(built, false, "app", "bin"));
        assertEquals(Path.of("/t/app-test"), LaunchRoute.cargoExecutable(built, true, null, null));
        assertNull(LaunchRoute.cargoExecutable(built, false, null, null), "two programs: which is unsaid");
        assertNull(LaunchRoute.cargoExecutable(built, false, "none", null));
    }

    @Test
    void aJavaConfigurationIsARunConfiguration() {
        var cfg = LaunchRoute.javaRunConfiguration(
                "Main",
                config(
                        "mainClass", "com.example.Main",
                        "projectName", "app",
                        "args", List.of("a b", "c"),
                        "vmArgs", "-Xmx1g",
                        "env", Map.of("K", "v w"),
                        "javaExec", "/jdk/bin/java"),
                Path.of("/ws"));
        assertEquals("launch.json: Main", cfg.name());
        assertEquals("java", cfg.type());
        assertEquals("com.example.Main", cfg.mainClass());
        assertEquals("app", cfg.projectName());
        assertEquals("'a b' c", cfg.args());
        assertEquals("-Xmx1g", cfg.vmArgs());
        assertEquals("/ws", cfg.workingDir(), "the workspace when no cwd");
        assertEquals("K='v w'", cfg.env());
        assertEquals("/jdk", cfg.jdkHome());
        assertEquals("", LaunchRoute.words(null));
        assertEquals(
                "/c",
                LaunchRoute.javaRunConfiguration("x", config("cwd", "/c"), Path.of("/ws"))
                        .workingDir());
    }
}
