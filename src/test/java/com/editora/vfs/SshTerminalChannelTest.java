package com.editora.vfs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command line a terminal channel runs on the host. */
class SshTerminalChannelTest {

    @Test
    void itStartsInTheFolderThenBecomesTheLoginShell() {
        assertEquals(
                "exec /bin/sh -c 'export COLORTERM=truecolor TERM_PROGRAM=Editora; cd -- '\\''/srv/my app'\\'' "
                        + "2>/dev/null || cd; exec \"${SHELL:-/bin/sh}\" -l'",
                SshTerminalChannel.commandLine("/srv/my app"));
    }

    @Test
    void withoutAFolderItStartsAtHome() {
        String line = SshTerminalChannel.commandLine(null);
        assertTrue(line.startsWith("exec /bin/sh -c "), "wrapped for a fish or csh account: " + line);
        assertTrue(!line.contains("cd --"), line);
        assertTrue(line.contains("exec \"${SHELL:-/bin/sh}\" -l"), line);
    }

    @Test
    void aFolderWithAQuoteStaysOneArgument() {
        String line = SshTerminalChannel.commandLine("/tmp/it's here");
        assertTrue(line.contains("it'\\''\\'\\'''\\''s here"), line);
    }
}
