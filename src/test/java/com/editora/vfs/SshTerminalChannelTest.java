package com.editora.vfs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command line a terminal channel runs on the host. */
class SshTerminalChannelTest {

    @Test
    void itStartsInTheFolderThenBecomesTheLoginShell() {
        String line = SshTerminalChannel.commandLine("/srv/my app");
        assertTrue(line.startsWith("exec /bin/sh -c '"), line);
        assertTrue(line.contains("cd -- '\\''/srv/my app'\\''"), line);
        assertTrue(line.contains("TERM_PROGRAM=Editora"), line);
        assertTrue(!line.contains("EDITORA %s"), "no pid line over SSH: " + line);
    }

    @Test
    void withoutAFolderItStartsAtHome() {
        String line = SshTerminalChannel.commandLine(null);
        assertTrue(line.startsWith("exec /bin/sh -c "), "wrapped for a fish or csh account: " + line);
        assertTrue(!line.contains("cd --"), line);
        assertTrue(line.endsWith("-l'"), line);
    }

    @Test
    void aFolderWithAQuoteStaysOneArgument() {
        String line = SshTerminalChannel.commandLine("/tmp/it's here");
        assertTrue(line.contains("it'\\''\\'\\'''\\''s here"), line);
    }
}
