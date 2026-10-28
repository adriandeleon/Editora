package com.editora.text;

import com.editora.text.ShellScripts.Problem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a shell script's bytes must not be (CRLF, a BOM), and the EditorConfig rules it therefore ignores. */
class ShellScriptsTest {

    @Test
    void onlyAShellScriptHasAProblemWithCrlfOrABom() {
        assertEquals(Problem.CRLF, ShellScripts.problem("shell", "CRLF", "utf-8"));
        assertEquals(Problem.CRLF, ShellScripts.problem("shell", "CR", "utf-8"));
        assertEquals(Problem.BOM, ShellScripts.problem("shell", "LF", "utf-8-bom"));
        assertEquals(Problem.CRLF_AND_BOM, ShellScripts.problem("shell", "CRLF", "utf-8-bom"));
        assertEquals(Problem.NONE, ShellScripts.problem("shell", "LF", "utf-8"));
        assertEquals(Problem.NONE, ShellScripts.problem("batchfile", "CRLF", "utf-8-bom"));
        assertEquals(Problem.NONE, ShellScripts.problem(null, "CRLF", "utf-8"));
    }

    @Test
    void aShellBufferKeepsLfAndNoBomWhateverEditorConfigSays() {
        assertNull(ShellScripts.endOfLine("shell", "CRLF"));
        assertNull(ShellScripts.endOfLine("shell", "CR"));
        assertEquals("LF", ShellScripts.endOfLine("shell", "LF"));
        assertEquals("CRLF", ShellScripts.endOfLine("powershell", "CRLF"));
        assertNull(ShellScripts.charset("shell", "utf-8-bom"));
        assertEquals("latin1", ShellScripts.charset("shell", "latin1"));
        assertEquals("utf-8-bom", ShellScripts.charset("markdown", "utf-8-bom"));
        var rules = new com.editora.editorconfig.EditorConfigProperties(
                true, 2, null, "crlf", "utf-8-bom", true, true, 100);
        var shell = ShellScripts.honoured("shell", rules);
        assertNull(shell.endOfLine());
        assertNull(shell.charset());
        assertEquals(2, shell.indentSize(), "everything else still applies");
        assertEquals(100, shell.maxLineLength());
        assertEquals(rules, ShellScripts.honoured("python", rules));
    }

    @Test
    void aShellScriptIsKnownByItsNameOrItsShebang() {
        assertTrue(ShellScripts.isShellScript("deploy.sh", ""));
        assertTrue(ShellScripts.isShellScript(".bashrc", ""));
        assertTrue(ShellScripts.isShellScript("release", "#!/usr/bin/env bash\necho"));
        assertTrue(ShellScripts.isShellScript("release", "#!/bin/sh"));
        assertFalse(ShellScripts.isShellScript("release", "#!/usr/bin/env python3\n"));
        assertFalse(ShellScripts.isShellScript("notes.txt", "hello"));
        assertFalse(ShellScripts.isShellScript(null, null));
    }

    @Test
    void aPosixShebangGetsSingleBracketTests() {
        assertTrue(ShellScripts.isPosixShebang("#!/bin/sh"));
        assertTrue(ShellScripts.isPosixShebang("#!/usr/bin/env dash"));
        assertTrue(ShellScripts.isPosixShebang("#! /bin/ash -e"));
        assertFalse(ShellScripts.isPosixShebang("#!/bin/bash"));
        assertFalse(ShellScripts.isPosixShebang("#!/usr/bin/env zsh"));
        assertFalse(ShellScripts.isPosixShebang("echo hi"));
        String body = "if [[ ${1:condition} ]]; then\n\t${0}\nfi";
        assertEquals("if [ ${1:condition} ]; then\n\t${0}\nfi", ShellScripts.snippetBody(body, "#!/bin/sh"));
        assertEquals(body, ShellScripts.snippetBody(body, "#!/usr/bin/env bash"));
        assertEquals(body, ShellScripts.snippetBody(body, ""), "no shebang: a .sh is run by bash");
    }

    /** ShellCheck has no zsh mode: the counter says such a script is not checked (Bash review C14). */
    @Test
    void zshIsToldByItsNameItsShebangOrAShellcheckDirective() {
        assertTrue(ShellDialect.isZsh("prompt.zsh", ""));
        assertTrue(ShellDialect.isZsh("PROMPT.ZSH", null));
        assertTrue(ShellDialect.isZsh("tool", "#!/usr/bin/env zsh\necho\n"));
        assertTrue(ShellDialect.isZsh("tool", "#!/bin/zsh -f\n"));
        assertTrue(ShellDialect.isZsh("lib.sh", "#!/bin/sh\n# shellcheck shell=zsh\nsetopt x\n"));
        assertTrue(ShellDialect.isZsh("lib.sh", "# shellcheck disable=SC1090 shell=zsh\n"));
        assertFalse(ShellDialect.isZsh("deploy.sh", "#!/usr/bin/env bash\necho zsh\n"));
        assertFalse(ShellDialect.isZsh("deploy.sh", "#!/bin/bash\n# shellcheck shell=bash\n"));
        assertFalse(ShellDialect.isZsh("deploy.sh", "#!/bin/bash\necho '#!/bin/zsh'\n"), "only line 1");
        assertFalse(ShellDialect.isZsh(null, null));
    }
}
