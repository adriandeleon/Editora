package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bash debugging on vscode-bash-debug: which scripts, the launch, the programs it names ({@link ScriptLaunch}). */
class ScriptLaunchBashTest {

    @Test
    void aBashOrShScriptIsDebuggedAnotherShellsIsNot() {
        assertTrue(ScriptLaunch.isBashScript(Path.of("deploy.sh"), "#!/bin/bash\necho hi\n"));
        assertTrue(ScriptLaunch.isBashScript(Path.of("deploy.sh"), "#!/usr/bin/env bash\necho hi\n"));
        assertTrue(ScriptLaunch.isBashScript(Path.of("deploy.sh"), "#!/usr/bin/env -S bash -e\necho hi\n"));
        assertTrue(ScriptLaunch.isBashScript(Path.of("run"), "#!/bin/sh\nset -e\n"), "sh runs under bash here");
        assertTrue(ScriptLaunch.isBashScript(Path.of("lib.sh"), "helper() { :; }\n"), "no shebang: a .sh file");
        assertTrue(ScriptLaunch.isBashScript(null, "echo untitled\n"));
        assertFalse(ScriptLaunch.isBashScript(Path.of("prompt.zsh"), "autoload -U colors\n"), "a .zsh file");
        assertFalse(ScriptLaunch.isBashScript(Path.of("conf.fish"), "set -x PATH\n"));
        assertFalse(ScriptLaunch.isBashScript(Path.of("tool"), "#!/usr/bin/env zsh\necho hi\n"));
        assertFalse(ScriptLaunch.isBashScript(Path.of("tool"), "#!/bin/dash\necho hi\n"));
    }

    @Test
    void theLaunchAndTheProgramsItNames() {
        Map<String, Object> launch = ScriptLaunch.bashLaunch(Path.of("/w/loop.sh"), List.of("a b"), Path.of("/w"));
        assertEquals("bashdb", launch.get("type"));
        assertEquals("launch", launch.get("request"));
        assertEquals(Path.of("/w/loop.sh").toString(), launch.get("program"));
        assertEquals(List.of("a b"), launch.get("args"));
        assertEquals("debugConsole", launch.get("terminalKind"), "the script's output in the Debug console");

        Map<String, Object> tools = ScriptLaunch.withBashTools(
                launch, Path.of("/opt/bash/extension/out/bashDebug.js"), name -> "/usr/bin/" + name);
        assertEquals(
                Path.of("/opt/bash/extension/bashdb_dir/bashdb")
                        .toAbsolutePath()
                        .toString(),
                tools.get("pathBashdb"));
        assertEquals(Path.of("/opt/bash/extension/bashdb_dir").toAbsolutePath().toString(), tools.get("pathBashdbLib"));
        assertEquals("/usr/bin/bash", tools.get("pathBash"));
        assertEquals("/usr/bin/mkfifo", tools.get("pathMkfifo"));
        assertEquals("/usr/bin/pkill", tools.get("pathPkill"));

        Map<String, Object> own = new java.util.LinkedHashMap<>(launch);
        own.put("pathBash", "/opt/bash5/bin/bash");
        assertEquals(
                "/opt/bash5/bin/bash",
                ScriptLaunch.withBashTools(own, Path.of("/x/out/bashDebug.js"), n -> "/usr/bin/" + n)
                        .get("pathBash"),
                "a launch.json's own is kept");
    }

    @Test
    void aVariableIsNamedBareAsBashdbReadsIt() {
        assertEquals("total", ScriptLaunch.bashName("$total"));
        assertEquals("total", ScriptLaunch.bashName(" ${total} "));
        assertEquals("total", ScriptLaunch.bashName("total"));
        assertEquals("$((total + 1))", ScriptLaunch.bashName("$((total + 1))"), "an expression as it is");
        assertEquals("${arr[0]}", ScriptLaunch.bashName("${arr[0]}"));
    }

    @Test
    void eachScriptLanguagesPlan() {
        Path script = Path.of("/w/x");
        assertEquals(
                DebugLanguages.BASH,
                ScriptLaunch.plan("bash", script, List.of(), Path.of("/w")).language());
        assertEquals(
                DebugLanguages.RUBY,
                ScriptLaunch.plan("ruby", script, List.of(), Path.of("/w")).language());
        assertEquals(
                List.of("--", "ruby", script.toString()),
                ScriptLaunch.plan("ruby", script, List.of(), Path.of("/w")).tail());
        assertEquals(
                DebugLanguages.PHP,
                ScriptLaunch.plan("php", script, List.of(), Path.of("/w")).language());
        assertEquals(DebugLanguages.BASH, DebugLanguages.forLanguage("shell"), "a shell file's debugger");
    }
}
