package com.editora.dap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ScriptLaunchPhpTest {

    private static final Path DIR = Path.of("/site").toAbsolutePath();

    @Test
    void aPhpCommandRunsWithXdebugsSwitchesAfterPhpAndItsSettingsInTheEnvironment() {
        Map<String, Object> launch =
                ScriptLaunch.phpCommand(List.of("php", "-S", "127.0.0.1:8000", "-t", DIR.toString()), true, DIR);
        assertEquals("php", launch.get("type"));
        assertEquals("launch", launch.get("request"));
        assertEquals(0, launch.get("port"), "the adapter picks its port");
        assertEquals("php", launch.get("runtimeExecutable"));
        List<String> expected = new ArrayList<>(ScriptLaunch.XDEBUG_ARGS);
        expected.addAll(List.of("-S", "127.0.0.1:8000", "-t", DIR.toString()));
        assertEquals(expected, launch.get("runtimeArgs"));
        assertEquals(DIR.toString(), launch.get("cwd"));
        assertEquals(ScriptLaunch.XDEBUG_ENV, launch.get("env"));
        assertFalse(launch.containsKey("program"), "the command is the runtime's: php -S has no program");
    }

    @Test
    void aRunnerThatIsNotPhpRunsItselfWithXdebugInTheEnvironmentAlone() {
        Map<String, Object> launch = ScriptLaunch.phpCommand(List.of("/bin/phpunit", "CartTest.php"), false, DIR);
        assertEquals("/bin/phpunit", launch.get("runtimeExecutable"));
        assertEquals(List.of("CartTest.php"), launch.get("runtimeArgs"));
        assertEquals("debug", ScriptLaunch.XDEBUG_ENV.get("XDEBUG_MODE"));
        assertEquals("client_host=127.0.0.1 client_port=${port}", ScriptLaunch.XDEBUG_ENV.get("XDEBUG_CONFIG"));
    }

    @Test
    void listenForXdebugWaitsOnXdebugsPort() {
        assertEquals(
                Map.of("type", "php", "request", "launch", "name", "Listen for Xdebug", "port", 9003),
                ScriptLaunch.phpListen(ScriptLaunch.XDEBUG_PORT));
    }
}
