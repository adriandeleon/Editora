package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.editora.config.Breakpoint;
import com.editora.dap.DapModels;
import com.editora.vfs.EmbeddedSftpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Breakpoints of a remote file are stored under its {@code sftp://} URI: not under the bare path a local
 * file of the same name has, and under the same key after a reconnect gives the file a new {@link Path}.
 */
@Tag("fx")
class RemoteBreakpointKeysFxTest {

    @TempDir
    Path base;

    private EmbeddedSftpFixture sftp;

    @BeforeEach
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        sftp = EmbeddedSftpFixture.start(base);
        Files.writeString(sftp.serverPath("main.py"), "a = 1\nb = 2\n");
    }

    @AfterEach
    void tearDown() throws Exception {
        sftp.close();
    }

    @Test
    void aRemoteFileIsKeyedByItsHostAndKeepsItsKeyAcrossAReconnect() throws Exception {
        Path remote = sftp.remotePath("main.py");
        String key = DebugCoordinator.key(remote);

        assertTrue(key.startsWith("sftp://"), key);
        assertEquals("/tmp/x.py", DebugCoordinator.key(Path.of("/tmp/x.py")), "a local key is unchanged");

        sftp.disconnect();
        Path again = sftp.reconnect().resolve("main.py");
        assertEquals(key, DebugCoordinator.key(again));
    }

    @Test
    void aClosedRemoteFilesBreakpointsAreSentOnlyWhileItsHostIsConnected() throws Exception {
        String key = DebugCoordinator.key(sftp.remotePath("main.py"));
        Map<String, List<Breakpoint>> store = Map.of(key, List.of(new Breakpoint(1, null, null, true, null)));

        List<DapModels.FileBreakpoints> connected =
                DebugCoordinator.closedFileBreakpoints(store, Set.of(), p -> true, p -> null);
        assertEquals(1, connected.size());
        assertEquals(sftp.remotePath("main.py"), connected.get(0).file(), "read back as the remote file");

        sftp.disconnect();
        assertEquals(List.of(), DebugCoordinator.closedFileBreakpoints(store, Set.of(), p -> true, p -> null));
    }
}
