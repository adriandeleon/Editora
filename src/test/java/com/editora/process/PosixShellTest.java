package com.editora.process;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PosixShellTest {

    private static ExecSpec spec(ExecSpec.Locale locale, Map<String, String> env, String... argv) {
        return ExecSpec.of(List.of(argv), null, env, locale);
    }

    @Test
    void quotesAWordSoNothingInItIsSpecial() {
        assertEquals("''", PosixShell.quote(""));
        assertEquals("'plain'", PosixShell.quote("plain"));
        assertEquals("'a b $HOME `id` \\n'", PosixShell.quote("a b $HOME `id` \\n"));
        assertEquals("'it'\\''s'", PosixShell.quote("it's"));
    }

    @Test
    void theArgumentsFollowTheScriptAsSeparateWords() {
        String line = PosixShell.commandLine(
                spec(ExecSpec.Locale.PARSE_STABLE, Map.of(), "git", "log", "--grep=it's"), null, null, null);

        assertTrue(line.startsWith("exec /bin/sh -c '"), line);
        assertTrue(line.endsWith("' editora 'git' 'log' '--grep=it'\\''s'"), line);
    }

    @Test
    void aParseStableChildGetsTheCLocaleAndAUserChildTheProbedOne() {
        String stable =
                PosixShell.script(spec(ExecSpec.Locale.PARSE_STABLE, Map.of(), "git"), null, null, Map.of("LANG", "x"));
        assertTrue(stable.contains("LC_ALL='C'; export LC_ALL\n"), stable);
        assertFalse(stable.contains("LANG="), stable);

        String user = PosixShell.script(
                spec(ExecSpec.Locale.USER, Map.of(), "mvn"), null, null, Map.of("LANG", "es_MX.UTF-8"));
        assertTrue(user.contains("LANG='es_MX.UTF-8'; export LANG\n"), user);
        assertFalse(user.contains("LC_ALL"), user);
    }

    @Test
    void theCallersVariablesComeLastAndANullValueUnsetsOne() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", "/opt/jdk/bin:/usr/bin");
        env.put("GIT_DIR", null);
        String script =
                PosixShell.script(spec(ExecSpec.Locale.PARSE_STABLE, env, "git"), "/srv/my app", "/usr/bin", null);

        assertEquals("""
                printf '\\036EDITORA %s\\n' "$$" >&2
                cd -- '/srv/my app' || exit 126
                PATH='/usr/bin'; export PATH
                LC_ALL='C'; export LC_ALL
                PATH='/opt/jdk/bin:/usr/bin'; export PATH
                unset GIT_DIR
                exec "$@"
                """, script);
    }

    @Test
    void discardedStreamsAreClosedAfterThePidLine() {
        String script = PosixShell.script(
                spec(ExecSpec.Locale.USER, Map.of(), "node").withDiscardedOutput(true, true), null, null, null);

        assertTrue(script.indexOf("EDITORA") < script.indexOf("exec 2>/dev/null"), script);
        assertTrue(script.endsWith("exec >/dev/null\nexec 2>/dev/null\nexec \"$@\"\n"), script);
    }

    @Test
    void aVariableNameThatIsNotAnIdentifierIsRefused() {
        ExecSpec injected = spec(ExecSpec.Locale.USER, Map.of("X; rm -rf ~", "1"), "true");
        assertThrows(IllegalArgumentException.class, () -> PosixShell.commandLine(injected, null, null, null));
    }

    @Test
    void onlyALongLivedChildGetsTheWatchdog() {
        ExecSpec oneShot = spec(ExecSpec.Locale.PARSE_STABLE, Map.of(), "git");
        assertFalse(PosixShell.script(oneShot, null, null, null).contains("PPID"));

        String script = PosixShell.script(oneShot.withLongLived(true), "/srv", null, null);
        assertTrue(script.indexOf("EDITORA") < script.indexOf("$PPID"), script);
        assertTrue(script.indexOf("$PPID") < script.indexOf("cd -- "), script);
        assertTrue(script.contains("kill -s TERM -- -$$"), "the form dash accepts");
    }

    @Test
    void theKillCommandSignalsTheGroupAndFallsBackToTheProcess() {
        assertEquals(
                "exec /bin/sh -c 'kill -s TERM -- -4242 2>/dev/null || kill -s TERM 4242'",
                PosixShell.killCommandLine(4242, false));
        assertTrue(PosixShell.killCommandLine(4242, true).contains("kill -s KILL -- -4242"));
        assertThrows(IllegalArgumentException.class, () -> PosixShell.killCommandLine(1, true));
        assertThrows(IllegalArgumentException.class, () -> PosixShell.killCommandLine(-1, true));
    }
}
