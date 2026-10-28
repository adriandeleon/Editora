package com.editora.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which shell-language buffers are zsh: ShellCheck never checks them (Bash review D2, C14). */
class ShellDialectTest {

    @Test
    void zshByNameShebangOrDirective() {
        assertTrue(ShellDialect.isZsh("/home/u/script.zsh", "echo hi\n"));
        assertTrue(ShellDialect.isZsh("/home/u/.zshrc", ""));
        assertTrue(ShellDialect.isZsh("tool", "#!/bin/zsh\nprint -l *\n"));
        assertTrue(ShellDialect.isZsh("tool", "#!/usr/bin/env -S zsh -f\n"));
        assertTrue(ShellDialect.isZsh("lib.sh", "# shellcheck shell=zsh\n"));
        assertTrue(ShellDialect.isZsh(null, "#!/usr/bin/env zsh\n"));
    }

    @Test
    void notZsh() {
        assertFalse(ShellDialect.isZsh("deploy.sh", "#!/usr/bin/env bash\n"));
        assertFalse(ShellDialect.isZsh("script.zsh", "# shellcheck shell=bash\n"), "the directive wins");
        assertFalse(ShellDialect.isZsh("script.zsh", "#!/bin/bash\n"), "so does a shebang");
        assertFalse(ShellDialect.isZsh(null, null));
        assertFalse(ShellDialect.isZsh("a.bash", ""));
    }

    @Test
    void theDirectiveIsReadFromTheFirstLines() {
        assertEquals("bash", ShellDialect.directiveShell("#!/bin/sh\n# shellcheck disable=SC2034 shell=Bash\n"));
        assertNull(ShellDialect.directiveShell("echo shell=bash\n"));
        assertNull(ShellDialect.directiveShell("\n".repeat(30) + "# shellcheck shell=zsh\n"));
    }
}
