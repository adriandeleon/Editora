package com.editora.vfs;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteEnvTest {

    @Test
    void readsTheValuesBetweenTheMarkersWhateverTheStartupFilesPrinted() {
        RemoteEnv env = RemoteEnv.parse("""
                Welcome to buildbox!
                __EDITORA_ENV_BEGIN__
                /home/ana/.sdkman/candidates/java/current/bin:/usr/bin
                /home/ana
                Linux
                es_MX.UTF-8

                C.UTF-8
                __EDITORA_ENV_END__
                """);

        assertEquals("/home/ana/.sdkman/candidates/java/current/bin:/usr/bin", env.path());
        assertEquals("/home/ana", env.home());
        assertEquals("Linux", env.os());
        assertEquals(
                Map.of("LANG", "es_MX.UTF-8", "LC_CTYPE", "C.UTF-8"), env.locale(), "an unset variable is left out");
    }

    @Test
    void aHostWithNoLocaleStillHasItsPath() {
        RemoteEnv env = RemoteEnv.parse("__EDITORA_ENV_BEGIN__\n/usr/bin\n/root\nDarwin\n\n\n\n__EDITORA_ENV_END__\n");

        assertEquals("/usr/bin", env.path());
        assertEquals("Darwin", env.os());
        assertTrue(env.locale().isEmpty());
    }

    @Test
    void anythingElseIsUnknown() {
        assertSame(RemoteEnv.UNKNOWN, RemoteEnv.parse(null));
        assertSame(RemoteEnv.UNKNOWN, RemoteEnv.parse(""));
        assertSame(RemoteEnv.UNKNOWN, RemoteEnv.parse("fish: Unknown command\n"));
        assertSame(RemoteEnv.UNKNOWN, RemoteEnv.parse("__EDITORA_ENV_BEGIN__\n/usr/bin\n/root\n"), "cut short");
        assertNull(RemoteEnv.UNKNOWN.path());
    }

    @Test
    void theProbeAsksAnInteractiveLoginShell() {
        String line = RemoteEnv.probeCommandLine();
        assertTrue(line.startsWith("exec \"${SHELL:-/bin/sh}\" -l -i -c '"), line);
        assertTrue(line.contains("\"$PATH\" \"$HOME\" \"$(uname -s)\" \"$LANG\" \"$LC_ALL\" \"$LC_CTYPE\""), line);
    }
}
