package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import com.editora.process.ExecTargets;
import com.editora.vfs.EmbeddedSftpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The java-debug jar a remote jdtls is handed: this machine's, copied once into the host's cache. */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class RemoteDebugBundlesFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    @TempDir
    Path local;

    private EmbeddedSftpFixture sftp;

    @BeforeEach
    void setUp() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        sftp = EmbeddedSftpFixture.startWithShell(base);
        Files.createDirectories(sftp.serverPath("app"));
    }

    @AfterEach
    void tearDown() throws Exception {
        sftp.close();
        ExecTargets.setRemoteTools(null);
        ExecTargets.setRemoteEnabled(null);
    }

    @Test
    void theLocalJarIsCopiedIntoTheHostCacheOnce() throws Exception {
        Path jar = Files.write(local.resolve("com.microsoft.java.debug.plugin-0.53.2.jar"), new byte[] {1, 2, 3, 4});
        Path root = sftp.remotePath("app");

        List<String> sent = LspManager.remoteDebugBundles(root, List.of(jar.toString()));

        Path copy = sftp.home().resolve(".cache/editora/java-debug").resolve(jar.getFileName());
        assertEquals(List.of(copy.toString()), sent, "named as the host names it");
        assertArrayEquals(Files.readAllBytes(jar), Files.readAllBytes(copy));
        Files.setLastModifiedTime(copy, FileTime.fromMillis(1_000_000));
        LspManager.remoteDebugBundles(root, List.of(jar.toString()));
        assertEquals(1_000_000, Files.getLastModifiedTime(copy).toMillis(), "the same jar is not sent again");
    }

    @Test
    void theHostsOwnPluginWins() {
        ExecTargets.setRemoteTools((id, tool) -> "dap.java".equals(tool) ? "/opt/java-debug/plugin.jar" : null);

        assertEquals(
                List.of("/opt/java-debug/plugin.jar"),
                LspManager.remoteDebugBundles(sftp.remotePath("app"), List.of("/local/plugin.jar")));
    }
}
