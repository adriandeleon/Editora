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
                LaunchRoute.of(config("type", "go", "mode", "remote"), none).kind(),
                "a remote attach needs the server's port");
        LaunchRoute.Route remote = LaunchRoute.of(
                config("type", "go", "request", "attach", "mode", "remote", "port", 2345, "preLaunchTask", "b"), none);
        assertEquals(LaunchRoute.Kind.DELVE_REMOTE, remote.kind());
        assertSame(DebugLanguages.GO, remote.profile());
        assertTrue(remote.attach());
        assertEquals(
                Map.of("type", "go", "request", "attach", "mode", "remote", "port", 2345),
                remote.request(),
                "VS Code's own out; the rest goes to Delve as written");
        LaunchRoute.Route py = LaunchRoute.of(config("type", "debugpy", "request", "attach"), none);
        assertEquals(LaunchRoute.Kind.DEBUGPY, py.kind());
        assertTrue(py.attach());
        assertEquals("python", py.request().get("type"));
        assertEquals(true, py.request().get("showReturnValue"), "VS Code's default");
        assertEquals(
                false,
                LaunchRoute.of(config("type", "debugpy", "request", "launch", "showReturnValue", false), none)
                        .request()
                        .get("showReturnValue"),
                "a configuration's own choice stays");
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
        LaunchRoute.Route firefox = LaunchRoute.of(config("type", "firefox", "request", "launch"), none);
        assertEquals(LaunchRoute.Kind.FIREFOX, firefox.kind(), "vscode-firefox-debug");
        assertSame(DebugLanguages.FIREFOX, firefox.profile());
        assertTrue(LaunchRoute.of(config("type", "firefox", "request", "attach"), none)
                .attach());
        LaunchRoute.Route safari = LaunchRoute.of(config("type", "safari"), none);
        assertEquals(LaunchRoute.Kind.UNSUPPORTED, safari.kind());
        assertEquals("type \"safari\"", safari.reason());
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
    void aDebugServersAddressMakesARemoteAttach() {
        LaunchRoute.Route gdb = LaunchRoute.of(
                config("type", "gdb", "request", "attach", "program", "/b/t", "target", "board:2345"), Map.of());
        assertEquals(RemoteNativeAttach.gdb("/b/t", "board:2345"), gdb.request(), "GDB's own target");
        LaunchRoute.Route cppdbg = LaunchRoute.of(
                config(
                        "type",
                        "cppdbg",
                        "request",
                        "launch",
                        "program",
                        "/b/t",
                        "miDebuggerServerAddress",
                        "board:2345"),
                Map.of());
        assertEquals(LaunchRoute.Kind.GDB, cppdbg.kind());
        assertTrue(cppdbg.attach());
        assertEquals(RemoteNativeAttach.gdb("/b/t", "board:2345"), cppdbg.request());
        LaunchRoute.Route lldb = LaunchRoute.of(
                config(
                        "type",
                        "cppdbg",
                        "request",
                        "launch",
                        "program",
                        "/b/t",
                        "MIMode",
                        "lldb",
                        "miDebuggerServerAddress",
                        "board:2345"),
                Map.of());
        assertEquals(LaunchRoute.Kind.CODELLDB, lldb.kind());
        assertEquals(RemoteNativeAttach.codelldb("/b/t", "board:2345", false), lldb.request());
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

        LaunchRoute.Route lua = LaunchRoute.of(
                config("type", "lua-local", "request", "launch", "program", Map.of("lua", "lua", "file", "a.lua")),
                none);
        assertEquals(LaunchRoute.Kind.LUA_DEBUG, lua.kind());
        assertSame(DebugLanguages.LUA, lua.profile());
        assertEquals(
                LaunchRoute.Kind.UNSUPPORTED,
                LaunchRoute.of(config("type", "lua-local", "request", "attach"), none)
                        .kind(),
                "local-lua-debugger only launches");

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

    @Test
    void everyAdapterTypeIsRoutedAndTheOnesEditoraCannotRunSayWhy() {
        Map<String, LaunchRoute.Kind> kinds = Map.of(
                "codelldb", LaunchRoute.Kind.CODELLDB,
                "pwa-node", LaunchRoute.Kind.JS_DEBUG,
                "pwa-msedge", LaunchRoute.Kind.BROWSER,
                "coreclr", LaunchRoute.Kind.NETCOREDBG,
                "debugpy", LaunchRoute.Kind.DEBUGPY);
        kinds.forEach((type, kind) -> assertEquals(
                kind,
                LaunchRoute.of(Map.of("type", type, "request", "attach"), Map.of())
                        .kind(),
                type));
        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("type", "go", "mode", "remote"),
                Map.of("type", "chrome", "request", "launch"),
                Map.of("type", "rdbg", "request", "attach", "debugPort", "/tmp/rdbg.sock"),
                Map.of("type", "rdbg", "request", "launch"),
                Map.of("type", "php", "request", "attach"),
                Map.of("type", "bashdb", "request", "attach"),
                Map.of("type", "dotnet"),
                Map.of("type", "java", "request", "attach"),
                Map.of("request", "launch"),
                Map.of("type", 3))) {
            LaunchRoute.Route route = LaunchRoute.of(body, Map.of());
            assertEquals(LaunchRoute.Kind.UNSUPPORTED, route.kind(), body.toString());
            assertFalse(route.reason().isBlank(), body.toString());
        }
        assertEquals(
                LaunchRoute.Kind.RDBG,
                LaunchRoute.of(Map.of("type", "rdbg", "request", "attach", "debugPort", 12345), Map.of())
                        .kind());
        assertEquals(
                LaunchRoute.Kind.BROWSER,
                LaunchRoute.of(Map.of("type", "chrome", "file", "/a.html"), Map.of())
                        .kind());
        assertEquals(
                DebugLanguages.RUST,
                LaunchRoute.of(Map.of("type", "lldb", "sourceLanguages", List.of("rust")), Map.of())
                        .profile());
    }

    @Test
    void rdbgsTailAndPortInTheirOtherForms() {
        assertEquals(
                List.of("--", "bundle", "exec", "rake", "spec", "a", "b"),
                LaunchRoute.rdbgTail(
                        Map.of("command", "rake", "useBundler", true, "script", "spec", "args", " a  b ")));
        assertEquals(
                List.of("--", "bundle", "exec", "ruby", "x.rb"),
                LaunchRoute.rdbgTail(
                        Map.of("command", "bundle exec ruby", "useBundler", true, "script", "x.rb", "args", " ")));
        assertEquals(12345, LaunchRoute.rdbgPort(Map.of("debugPort", "[::1]:12345")));
        assertEquals(-1, LaunchRoute.rdbgPort(Map.of("debugPort", "other:12345")));
        assertEquals(-1, LaunchRoute.rdbgPort(Map.of("debugPort", 70000)));
        assertEquals(-1, LaunchRoute.rdbgPort(Map.of()));
    }

    @Test
    void gdbAndCodeLldbRequestsFromCppdbgsFields() {
        Map<String, Object> attach = LaunchRoute.gdbRequest(Map.of("processId", " 42 ", "program", "/bin/app"), true);
        assertEquals(42L, attach.get("pid"));
        assertEquals("/bin/app", attach.get("program"));
        assertEquals("x", LaunchRoute.gdbRequest(Map.of("pid", "x"), true).get("pid"), "not a number: as written");
        assertEquals(0L, LaunchRoute.gdbRequest(Map.of(), true).get("pid"));
        assertEquals(
                List.of(),
                LaunchRoute.gdbRequest(Map.of("pid", List.of()), true).get("pid"));
        Map<String, Object> launch = LaunchRoute.gdbRequest(Map.of("program", "/a", "stopOnEntry", true), false);
        assertEquals(true, launch.get("stopAtBeginningOfMainSubprogram"));
        assertFalse(launch.containsKey("args"));
        assertFalse(launch.containsKey("cwd"));
        assertFalse(launch.containsKey("env"));
        assertEquals(
                true,
                LaunchRoute.gdbRequest(Map.of("stopAtBeginningOfMainSubprogram", true), false)
                        .get("stopAtBeginningOfMainSubprogram"));
        Map<String, Object> lldb = LaunchRoute.codelldbFromCppdbg(Map.of("program", "/a"), false);
        assertFalse(lldb.containsKey("args"));
        assertFalse(lldb.containsKey("env"));
        assertEquals(false, lldb.get("stopOnEntry"));
        java.util.Map<String, Object> env = new java.util.HashMap<>();
        env.put("A", null);
        java.util.Map<String, Object> unnamed = new java.util.HashMap<>();
        unnamed.put("value", "v");
        java.util.Map<String, Object> noValue = new java.util.HashMap<>();
        noValue.put("name", "B");
        noValue.put("value", null);
        assertEquals(
                Map.of("A", "", "B", ""),
                LaunchRoute.environment(Map.of("env", env, "environment", List.of(unnamed, noValue, "x"))));
    }

    @Test
    void cargoExecutablesAndJavaConfigurationsInTheirEdgeCases() {
        RustLaunch.Artifact bin =
                new RustLaunch.Artifact("app", List.of("bin"), Path.of("/s/main.rs"), Path.of("/t/app"), false);
        RustLaunch.Artifact lib =
                new RustLaunch.Artifact("app", List.of("lib"), Path.of("/s/lib.rs"), Path.of("/t/lib"), false);
        assertEquals(Path.of("/t/app"), LaunchRoute.cargoExecutable(List.of(bin, lib), false, null, "bin"));
        assertNull(LaunchRoute.cargoExecutable(List.of(bin, lib), false, null, null), "two left");
        com.editora.config.RunConfiguration config =
                LaunchRoute.javaRunConfiguration("x", Map.of("cwd", " ", "javaExec", "java", "args", 3), Path.of("/w"));
        assertEquals(Path.of("/w").toString(), config.workingDir());
        assertEquals("", config.jdkHome(), "a bare java names no JDK folder");
        assertEquals("", config.args());
        assertEquals(
                "",
                LaunchRoute.javaRunConfiguration("x", Map.of("javaExec", "/java"), Path.of("/w"))
                        .jdkHome());
    }
}
