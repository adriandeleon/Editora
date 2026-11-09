package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a Ruby script runs under rdbg and a PHP one under Xdebug. */
class ScriptLaunchTest {

    @Test
    void aRubyScriptRunsUnderRdbgWhichIsTheAdapter() {
        Path script = Path.of("w", "shelf.rb").toAbsolutePath();
        Path dir = script.getParent();
        assertEquals(
                List.of("--", "ruby", script.toString(), "a", "b"), ScriptLaunch.rubyTail(script, List.of("a", "b")));
        Map<String, Object> launch = ScriptLaunch.rubyLaunch(script, List.of("a"), dir);
        assertEquals("rdbg", launch.get("type"));
        assertEquals("launch", launch.get("request"));
        assertEquals(script.toString(), launch.get("script"));
        assertEquals(dir.toString(), launch.get("cwd"));
        assertEquals(true, launch.get("localfs"), "the debuggee reads this machine's files");
        assertEquals(Map.of("type", "rdbg", "request", "attach", "localfs", true), ScriptLaunch.rubyAttach());
        List<String> command = DebugLanguages.RUBY.external().command();
        assertEquals("rdbg", command.getFirst());
        assertTrue(
                command.containsAll(List.of("--command", "--open", "--stop-at-load", "--port=0")), command.toString());
    }

    @Test
    void aPhpScriptRunsUnderPhpWithXdebugToldWhereToConnect() {
        Path program = Path.of("w", "shelf.php").toAbsolutePath();
        Map<String, Object> launch = ScriptLaunch.phpLaunch(program, List.of("x"), program.getParent());
        assertEquals("php", launch.get("type"));
        assertEquals(program.toString(), launch.get("program"));
        assertEquals(List.of("x"), launch.get("args"));
        assertEquals(0, launch.get("port"), "vscode-php-debug picks a free port");
        assertEquals("php", launch.get("runtimeExecutable"));
        @SuppressWarnings("unchecked")
        List<String> runtimeArgs = (List<String>) launch.get("runtimeArgs");
        assertTrue(runtimeArgs.contains("-dxdebug.mode=debug"), runtimeArgs.toString());
        assertTrue(runtimeArgs.contains("-dxdebug.client_port=${port}"), "the adapter puts its port there");
        assertEquals(List.of("phpDebug.js"), DebugLanguages.PHP.external().command());
    }
}
