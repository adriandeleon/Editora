package com.editora.vfs;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(line.contains("exec \"${SHELL:-/bin/sh}\" -l -i -c '"), line);
        assertTrue(
                line.startsWith("printf '%s\\n' __EDITORA_BASE_ENV__; env;"), "a plain command's env first: " + line);
        assertTrue(line.contains("\"$PATH\" \"$HOME\" \"$(uname -s)\" \"$LANG\" \"$LC_ALL\" \"$LC_CTYPE\""), line);
    }

    @Test
    void whatTheProfileAddsOrChangesIsCarriedOverButNotTheSessionsOwn() {
        RemoteEnv env = RemoteEnv.parse("""
                __EDITORA_BASE_ENV__
                HOME=/home/ana
                PATH=/usr/bin
                LANG=C
                SSH_CONNECTION=1 2 3 4
                EDITOR=vi
                __EDITORA_BASE_ENV_END__
                Welcome!
                __EDITORA_ENV_BEGIN__
                /home/ana/bin:/usr/bin
                /home/ana
                Linux
                es_MX.UTF-8


                __EDITORA_ENV_END__
                __EDITORA_LOGIN_ENV__
                HOME=/home/ana
                PATH=/home/ana/bin:/usr/bin
                LANG=es_MX.UTF-8
                SSH_CONNECTION=1 2 3 4
                EDITOR=vi
                JAVA_HOME=/opt/jdk-21
                MAVEN_OPTS=-Xmx2g -Dmaven.repo.local=/data/m2
                PS1=\\u@\\h$
                SHLVL=2
                BASH_FUNC_nvm%%=() {  echo hi
                }
                MULTI=first
                second line
                __EDITORA_LOGIN_ENV_END__
                """);

        assertEquals("/home/ana/bin:/usr/bin", env.path());
        assertEquals(
                Map.of("JAVA_HOME", "/opt/jdk-21", "MAVEN_OPTS", "-Xmx2g -Dmaven.repo.local=/data/m2"),
                env.profile(),
                "only what the profile sets: no PATH or locale (handled apart), nothing the session already had, "
                        + "no prompt or shell level, no function, no value cut by a line break");
    }

    @Test
    void anOlderProbesOutputCarriesNoProfile() {
        RemoteEnv env = RemoteEnv.parse("__EDITORA_ENV_BEGIN__\n/usr/bin\n/root\nLinux\n\n\n\n__EDITORA_ENV_END__\n");

        assertEquals(Map.of(), env.profile());
    }

    @Test
    void theProfileIsKeptWithinTheLimitsOfACommandLine() {
        Map<String, String> login = new java.util.HashMap<>();
        login.put("HUGE", "x".repeat(RemoteEnv.MAX_VALUE + 1));
        for (int i = 0; i < RemoteEnv.MAX_VARIABLES + 50; i++) {
            login.put("V" + i, "v");
        }

        Map<String, String> profile = RemoteEnv.profile(Map.of(), login);

        assertFalse(profile.containsKey("HUGE"));
        assertEquals(RemoteEnv.MAX_VARIABLES, profile.size());
    }
}
