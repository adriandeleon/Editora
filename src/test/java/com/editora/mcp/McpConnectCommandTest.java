package com.editora.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link McpConnectCommand}: the commands Settings → MCP Server copies. Pure. */
class McpConnectCommandTest {

    @Test
    void theConnectCommandStartsThisLauncherOnceForEveryProject() {
        assertEquals(
                "claude mcp add --scope user editora -- /opt/editora/bin/Editora mcp",
                McpConnectCommand.stdio(List.of("/opt/editora/bin/Editora"), null, false));
        assertEquals(
                "claude mcp add --scope user editora -- '/Applications/My Editora.app/Contents/MacOS/Editora' mcp"
                        + " --config-dir /home/me/.editora-dev",
                McpConnectCommand.stdio(
                        List.of("/Applications/My Editora.app/Contents/MacOS/Editora"),
                        Path.of("/home/me/.editora-dev"),
                        false));
    }

    @Test
    void theLauncherIsOneThatOutlivesThisRunAndCanWriteToStandardOutput() {
        // An AppImage runs from a mount that changes every launch: the file itself.
        assertEquals(
                List.of("/home/me/Editora.AppImage"),
                McpConnectCommand.launcher(
                        Optional.of("/tmp/.mount_EdXyz/bin/Editora"), "/home/me/Editora.AppImage", false, p -> true));
        assertEquals(
                List.of("/opt/editora/bin/Editora"),
                McpConnectCommand.launcher(Optional.of("/opt/editora/bin/Editora"), null, false, p -> true));
        // From java (mvn javafx:run, a fat jar) the launcher can't be told.
        assertEquals(
                List.of("editora"),
                McpConnectCommand.launcher(Optional.of("/usr/lib/jvm/jdk/bin/java"), "", false, p -> true));
        assertEquals(List.of("editora"), McpConnectCommand.launcher(Optional.empty(), null, false, p -> true));
        // Windows: Editora.exe is a window program, so its console twin beside it.
        String exe = "C:\\Program Files\\Editora\\Editora.exe";
        String cli = "C:\\Program Files\\Editora\\editora-cli.exe";
        assertEquals(List.of(cli), McpConnectCommand.launcher(Optional.of(exe), null, true, p -> true));
        assertEquals(List.of("editora-cli"), McpConnectCommand.launcher(Optional.of(exe), null, true, p -> false));
        assertEquals(
                List.of("editora-cli"),
                McpConnectCommand.launcher(Optional.of("C:\\jdk\\bin\\javaw.exe"), null, true, p -> true));
    }

    @Test
    void theDefaultConfigFolderIsLeftOut() {
        Path home = Path.of("/home/me/.editora");
        assertNull(McpConnectCommand.configDirArg(Path.of("/home/me/./.editora"), home));
        assertEquals(Path.of("/srv/cfg"), McpConnectCommand.configDirArg(Path.of("/srv/cfg"), home));
    }

    @Test
    void wordsAreQuotedForTheShellTheyArePastedInto() {
        assertEquals("plain-word_1.0", McpConnectCommand.quote("plain-word_1.0", false));
        assertEquals("'it'\\''s here'", McpConnectCommand.quote("it's here", false));
        assertEquals("'a\\b'", McpConnectCommand.quote("a\\b", false), "a backslash means something to sh");
        assertEquals("C:\\Editora\\editora-cli.exe", McpConnectCommand.quote("C:\\Editora\\editora-cli.exe", true));
        assertEquals("\"C:\\Program Files\\x.exe\"", McpConnectCommand.quote("C:\\Program Files\\x.exe", true));
        assertEquals("''", McpConnectCommand.quote("", false));
    }

    @Test
    void theHttpCommandIsTheOneThatWasCopiedBefore() {
        assertEquals(
                "claude mcp add --transport http editora http://127.0.0.1:1/mcp --header \"Authorization: Bearer t\"",
                McpConnectCommand.http("http://127.0.0.1:1/mcp", "t"));
    }
}
