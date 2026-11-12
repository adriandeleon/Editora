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
        LaunchRoute.Route firefox = LaunchRoute.of(config("type", "firefox"), none);
        assertEquals(LaunchRoute.Kind.UNSUPPORTED, firefox.kind());
        assertEquals("type \"firefox\"", firefox.reason());
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
        assertEquals(Path.of("/ws").toString(), cfg.workingDir(), "the workspace when no cwd");
        assertEquals("K='v w'", cfg.env());
        assertEquals(Path.of("/jdk").toString(), cfg.jdkHome());
        assertEquals("", LaunchRoute.words(null));
        assertEquals(
                "/c",
                LaunchRoute.javaRunConfiguration("x", config("cwd", "/c"), Path.of("/ws"))
                        .workingDir());
    }

    @Test
    void csharpRubyAndPhpConfigurationsGoToTheirPresets() {
        Map<String, DebugLanguage> none = Map.of();
        LaunchRoute.Route cs = LaunchRoute.of(
                config("type", "coreclr", "request", "launch", "program", "app.dll", "preLaunchTask", "build"), none);
        assertEquals(LaunchRoute.Kind.NETCOREDBG, cs.kind());
        assertSame(DebugLanguages.CSHARP, cs.profile());
        assertEquals(Map.of("type", "coreclr", "request", "launch", "program", "app.dll"), cs.request());
        assertTrue(LaunchRoute.of(config("type", "coreclr", "request", "attach", "processId", 7), none)
                .attach());
        assertEquals(
                LaunchRoute.Kind.UNSUPPORTED,
                LaunchRoute.of(config("type", "dotnet", "projectPath", "x.csproj"), none)
                        .kind(),
                "C# Dev Kit's own type");

        LaunchRoute.Route rb = LaunchRoute.of(config("type", "rdbg", "request", "launch", "script", "a.rb"), none);
        assertEquals(LaunchRoute.Kind.RDBG, rb.kind());
        assertSame(DebugLanguages.RUBY, rb.profile());
        assertEquals(true, rb.request().get("localfs"));
        assertEquals(
                LaunchRoute.Kind.UNSUPPORTED,
                LaunchRoute.of(config("type", "rdbg", "request", "launch"), none)
                        .kind(),
                "nothing to run");
        LaunchRoute.Route rbAttach =
                LaunchRoute.of(config("type", "rdbg", "request", "attach", "debugPort", "localhost:12345"), none);
        assertEquals(LaunchRoute.Kind.RDBG, rbAttach.kind());
        assertTrue(rbAttach.attach());
        assertEquals(
                LaunchRoute.Kind.UNSUPPORTED,
                LaunchRoute.of(config("type", "rdbg", "request", "attach", "debugPort", "/tmp/rdbg.sock"), none)
                        .kind(),
                "a socket path");

        LaunchRoute.Route php = LaunchRoute.of(config("type", "php", "request", "launch", "port", 9003), none);
        assertEquals(LaunchRoute.Kind.PHP_DEBUG, php.kind());
        assertSame(DebugLanguages.PHP, php.profile());
        assertEquals(9003, php.request().get("port"), "Listen for Xdebug: sent as written");
        assertEquals(
                LaunchRoute.Kind.UNSUPPORTED,
                LaunchRoute.of(config("type", "php", "request", "attach"), none).kind());
    }

    @Test
    void anRdbgLaunchRunsItsCommandAndScript() {
        assertEquals(
                List.of("--", "ruby", "app.rb", "-v"),
                LaunchRoute.rdbgTail(config("script", "app.rb", "args", List.of("-v"))));
        assertEquals(
                List.of("--", "bundle", "exec", "rake", "spec"),
                LaunchRoute.rdbgTail(config("command", "bundle exec rake", "script", "spec")));
        assertEquals(
                List.of("--", "bundle", "exec", "ruby", "app.rb", "a", "b"),
                LaunchRoute.rdbgTail(config("useBundler", true, "script", "app.rb", "args", "a b")));
        assertEquals(12345, LaunchRoute.rdbgPort(config("debugPort", "12345")));
        assertEquals(12345, LaunchRoute.rdbgPort(config("debugPort", 12345)));
        assertEquals(12345, LaunchRoute.rdbgPort(config("debugPort", "127.0.0.1:12345")));
        assertEquals(-1, LaunchRoute.rdbgPort(config("debugPort", "example.com:12345")), "another machine");
        assertEquals(-1, LaunchRoute.rdbgPort(config("debugPort", "0")));
        assertEquals(-1, LaunchRoute.rdbgPort(config()));
    }

    @Test
    void browserConfigurationsGoToJsDebugAsABrowser() {
        Map<String, DebugLanguage> none = Map.of();
        LaunchRoute.Route chrome = LaunchRoute.of(
                config("name", "c", "type", "chrome", "request", "launch", "url", "http://localhost:3000"), none);
        assertSame(LaunchRoute.Kind.BROWSER, chrome.kind());
        assertSame(DebugLanguages.JAVASCRIPT, chrome.profile());
        assertEquals("pwa-chrome", chrome.request().get("type"));
        assertFalse(chrome.request().containsKey("name"));

        LaunchRoute.Route edge = LaunchRoute.of(config("type", "msedge", "request", "attach", "port", 9222), none);
        assertSame(LaunchRoute.Kind.BROWSER, edge.kind());
        assertTrue(edge.attach());
        assertEquals("pwa-msedge", edge.request().get("type"));

        LaunchRoute.Route file =
                LaunchRoute.of(config("type", "pwa-chrome", "request", "launch", "file", "/w/index.html"), none);
        assertSame(LaunchRoute.Kind.BROWSER, file.kind());

        LaunchRoute.Route neither = LaunchRoute.of(config("type", "chrome", "request", "launch"), none);
        assertSame(LaunchRoute.Kind.UNSUPPORTED, neither.kind());
        assertEquals("a browser launch without a url or file", neither.reason());
    }
}
