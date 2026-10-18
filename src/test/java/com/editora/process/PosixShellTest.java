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
        assertTrue(line.contains("' editora '"), line);
        assertTrue(
                line.endsWith("exec \"$@\"\n' 'git' 'log' '--grep=it'\\''s'"),
                "the script, then each argument: " + line);
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
    void theLoginProfilesVariablesComeFirstSoEverythingElseWins() {
        Map<String, String> profile = new LinkedHashMap<>();
        profile.put("JAVA_HOME", "/opt/jdk-17");
        profile.put("MAVEN_OPTS", "-Xmx2g");
        profile.put("BAD NAME", "x"); // not a variable name: never exported
        String script = PosixShell.script(
                spec(ExecSpec.Locale.PARSE_STABLE, Map.of("JAVA_HOME", "/opt/jdk-21"), "mvn"),
                null,
                "/usr/bin",
                null,
                profile);

        assertEquals("""
                printf '\\036EDITORA %s\\n' "$$" >&2
                JAVA_HOME='/opt/jdk-17'; export JAVA_HOME
                MAVEN_OPTS='-Xmx2g'; export MAVEN_OPTS
                PATH='/usr/bin'; export PATH
                LC_ALL='C'; export LC_ALL
                JAVA_HOME='/opt/jdk-21'; export JAVA_HOME
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

    @Test
    void aTerminalScriptMarksItsPidStartsInTheFolderAndBecomesALoginShell() throws Exception {
        String script = PosixShell.terminalScript("/w/my app", true);
        org.junit.jupiter.api.Assertions.assertTrue(script.startsWith("printf '\\036EDITORA %s\\n' \"$$\"; "), script);
        org.junit.jupiter.api.Assertions.assertTrue(script.contains("cd -- '/w/my app' 2>/dev/null || cd; "), script);
        org.junit.jupiter.api.Assertions.assertTrue(script.endsWith("exec \"$s\" -l"), script);
        org.junit.jupiter.api.Assertions.assertTrue(
                !PosixShell.terminalScript(null, false).contains("cd --"), "home when there is no folder");
        org.junit.jupiter.api.Assertions.assertTrue(
                !PosixShell.terminalScript(null, false).contains("EDITORA"), "no pid line unless asked");
    }

    @Test
    void aTerminalScriptsPidLineIsTheMarkThenThePid() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isExecutable(java.nio.file.Path.of("/bin/sh")));
        // Up to the cd: replace the login shell with a plain exit so the script can be run here.
        String script = PosixShell.terminalScript(null, true).replaceFirst("s=\\$\\{SHELL.*$", "exit 0");
        Process p = new ProcessBuilder("/bin/sh", "-c", script).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        p.waitFor();
        org.junit.jupiter.api.Assertions.assertTrue(
                out.matches(java.util.regex.Pattern.quote(PosixShell.TERMINAL_PID_MARK) + "\\d+\n"), out);
    }

    @Test
    void aTerminalScriptStartsBashAndZshWithTheIntegrationWhenItIsThere() {
        String script = PosixShell.terminalScript("/w", true, "/home/ana/.cache/editora/shell-integration/abc");
        org.junit.jupiter.api.Assertions.assertTrue(
                script.contains("I='/home/ana/.cache/editora/shell-integration/abc'"), script);
        org.junit.jupiter.api.Assertions.assertTrue(script.contains("--init-file \"$I/editora.bash\" -i"), script);
        org.junit.jupiter.api.Assertions.assertTrue(script.contains("ZDOTDIR=\"$I/zsh\""), script);
        org.junit.jupiter.api.Assertions.assertTrue(
                script.endsWith("exec \"$s\" -l"), "the plain login shell is the fallback");
    }

    @Test
    void aProgramScriptRunsTheProgramUnderTheLoginShellKeepingTheMarkedPid(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path home) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isExecutable(java.nio.file.Path.of("/bin/sh")));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FOO", "a b'c");
        env.put("BAR", null);
        String script = PosixShell.terminalScript(
                home.toString(),
                true,
                TerminalSpec.program(
                        null,
                        80,
                        24,
                        List.of(
                                "/bin/sh",
                                "-c",
                                "echo \"pid=$$ foo=$FOO bar=${BAR-unset} arg=$1 dir=$(pwd -P)\"; exit 4",
                                "x",
                                "it's"),
                        env));
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", script);
        pb.environment().put("SHELL", "/bin/sh");
        pb.environment().put("HOME", home.toString());
        pb.environment().put("BAR", "set before");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(4, p.waitFor(), "the program's exit code is the channel's");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        java.util.regex.Pattern.quote(PosixShell.TERMINAL_PID_MARK) + "(\\d+)\n")
                .matcher(out);
        assertTrue(m.find(), out);
        assertTrue(
                out.contains("pid=" + m.group(1) + " foo=a b'c bar=unset arg=it's dir=" + home.toRealPath()),
                () -> "exec all the way down keeps the marked pid, env set and unset, arguments whole: " + out);
    }

    @Test
    void aProgramScriptPassesOverAShellThatCannotRunIt() {
        String script =
                PosixShell.terminalScript(null, false, TerminalSpec.program(null, 80, 24, List.of("ls"), Map.of()));
        assertTrue(script.contains("*) s=/bin/sh"), script);
        assertTrue(script.endsWith(" editora -- 'ls'"), script);
        assertThrows(
                IllegalArgumentException.class,
                () -> PosixShell.terminalScript(
                        null, false, TerminalSpec.program(null, 80, 24, List.of("ls"), Map.of("A-B", "x"))));
    }

    @Test
    void aProgramsVariablesWinOverTheLoginProfileAsInConsoleRun(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home)
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isExecutable(java.nio.file.Path.of("/bin/sh")));
        // A profile that sets what a run configuration also sets: Debian's /etc/profile sets PATH, an
        // installer's ~/.profile sets JAVA_HOME. The run configuration's values are the ones that count.
        java.nio.file.Files.writeString(
                home.resolve(".profile"), "export FOO=from-profile\nexport JAVA_HOME=/usr/lib/jvm/profile\n");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FOO", "from-run-config");
        env.put("JAVA_HOME", "/opt/jdk-from-run-config");
        String script = PosixShell.terminalScript(
                null,
                false,
                TerminalSpec.program(
                        null, 80, 24, List.of("/bin/sh", "-c", "echo \"foo=$FOO java=$JAVA_HOME\""), env));
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", script);
        pb.environment().put("SHELL", "/bin/sh");
        pb.environment().put("HOME", home.toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor());
        assertTrue(out.contains("foo=from-run-config java=/opt/jdk-from-run-config"), out);
    }

    @Test
    void aProgramWhoseFolderIsGoneDoesNotRunInTheHomeFolder(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home)
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isExecutable(java.nio.file.Path.of("/bin/sh")));
        String script = PosixShell.terminalScript(
                home.resolve("gone").toString(),
                false,
                TerminalSpec.program(null, 80, 24, List.of("/bin/sh", "-c", "echo ran in $(pwd)"), Map.of()));
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", script);
        pb.environment().put("SHELL", "/bin/sh");
        pb.environment().put("HOME", home.toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(126, p.waitFor(), "it fails, as console Run does");
        assertFalse(out.contains("ran in"), out);
    }

    @Test
    void aTerminalOnATargetWithNoLocaleGetsUtf8() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isExecutable(java.nio.file.Path.of("/bin/sh")));
        String script = PosixShell.terminalScript(
                null, false, TerminalSpec.program(null, 80, 24, List.of("/bin/sh", "-c", "echo lang=$LANG"), Map.of()));
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", script);
        pb.environment().remove("LANG");
        pb.environment().remove("LC_ALL");
        pb.environment().remove("LC_CTYPE");
        pb.environment().put("SHELL", "/bin/sh");
        pb.environment().put("HOME", "/nonexistent");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        p.waitFor();
        assertTrue(out.contains("lang=C.UTF-8"), out);
    }
}
