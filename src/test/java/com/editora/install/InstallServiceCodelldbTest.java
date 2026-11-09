package com.editora.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import com.editora.io.LoopbackDownloads;
import com.editora.io.TestArchives;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Installing CodeLLDB on Linux: its pinned {@code .vsix} (a zip) is streamed, refused unless its SHA-256 is the
 * catalog's, and unpacked by Editora — GNU tar reads no zip — with its programs made runnable, since a zip read in
 * Java keeps no file modes. A loopback server answers the real URL with a small zip of the same shape.
 */
@EnabledOnOs(OS.LINUX)
class InstallServiceCodelldbTest {

    @TempDir
    Path sandbox;

    private Path config;
    private LoopbackDownloads web;
    private InstallService service;

    @BeforeEach
    void setUp() throws IOException {
        config = Files.createDirectories(sandbox.resolve("config"));
        web = new LoopbackDownloads();
        service = new InstallService(web.client(), 1024);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        if (web != null) {
            web.close();
        }
    }

    private void serve(byte[] archive, String sha256) {
        InstallCatalog.PinnedServer real =
                InstallCatalog.pinnedServer(InstallCatalog.CODELLDB_STEP).orElseThrow();
        InstallCatalog.Platform platform = InstallCatalog.currentPlatform();
        String url = real.assetFor(platform).url();
        web.serve(url, archive);
        service.setPinnedServersForTest(id -> Optional.of(new InstallCatalog.PinnedServer(
                real.id(),
                real.version(),
                Map.of(platform, new InstallCatalog.PinnedAsset(url, sha256, InstallCatalog.Packing.ZIP)),
                real.launcher(),
                real.arguments(),
                real.downloadMegabytes(),
                real.installedMegabytes())));
    }

    private InstallService.Result install() {
        return service.installSync(
                InstallCatalog.debuggerInstall(InstallCatalog.CODELLDB_STEP).orElseThrow(), config, id -> {});
    }

    private static byte[] vsix() {
        return TestArchives.zip(
                "extension/package.json", "{}",
                "extension/adapter/codelldb", "#!/bin/sh\n",
                "extension/lldb/bin/lldb-server", "#!/bin/sh\n",
                "extension/lldb/lib/liblldb.so", "lib");
    }

    @Test
    void theVsixIsCheckedUnpackedAndItsProgramsRunnable() throws Exception {
        byte[] archive = vsix();
        serve(archive, sha256(archive));

        InstallService.Result result = install();

        assertTrue(result.ok(), result.message());
        Path root = config.resolve(InstallCatalog.CODELLDB_DIR);
        Path adapter = root.resolve("extension/adapter/codelldb");
        assertTrue(Files.isExecutable(adapter), "the adapter runs");
        assertTrue(Files.isExecutable(root.resolve("extension/lldb/bin/lldb-server")), "LLDB's server runs");
        assertFalse(Files.isExecutable(root.resolve("extension/lldb/lib/liblldb.so")), "a library is left as it is");
        assertEquals(
                Optional.of(adapter),
                com.editora.dap.DebugAdapterLocator.locateCodelldb(root.toString(), sandbox),
                "where the locator finds it");
    }

    @Test
    void aDownloadWithAnotherChecksumIsRefusedAndNothingIsInstalled() {
        serve(vsix(), "0".repeat(64));

        InstallService.Result result = install();

        assertFalse(result.ok());
        assertTrue(result.message().contains("does not match its published checksum"), result.message());
        assertFalse(Files.exists(config.resolve(InstallCatalog.CODELLDB_DIR)));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
