package com.editora.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.editora.io.LoopbackDownloads;
import com.editora.io.TestArchives;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Installing JetBrains' Kotlin LSP: the pinned distribution for this platform is streamed to disk, refused unless
 * its SHA-256 is the catalog's, unpacked by {@code tar}, and its {@code intellij-server} launcher becomes the
 * server command. A loopback server answers the real URL with a small archive of the same shape.
 */
@DisabledOnOs(OS.WINDOWS) // the stand-in archive is a .tar.gz; Windows gets a zip
class InstallServicePinnedServerTest {

    @TempDir
    Path sandbox;

    private Path config;
    private LoopbackDownloads web;
    private InstallService service;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(!ProcessRunner.resolveExecutable(List.of("tar")).get(0).equals("tar"), "tar is not installed");
        config = Files.createDirectories(sandbox.resolve("config"));
        web = new LoopbackDownloads();
        service = new InstallService(web.client(), 1024); // the in-memory cap does not apply: it streams
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

    private InstallService.Result install() {
        return service.installSync(InstallCatalog.serverInstall("kotlin").orElseThrow(), config, id -> {});
    }

    /** The real recipe with this platform's URL answering {@code archive}, pinned to {@code sha256}. */
    private void serve(byte[] archive, String sha256) {
        InstallCatalog.PinnedServer real =
                InstallCatalog.pinnedServer(InstallCatalog.KOTLIN_LSP_STEP).orElseThrow();
        InstallCatalog.Platform platform = InstallCatalog.currentPlatform();
        String url = real.assetFor(platform).url();
        web.serve(url, archive);
        service.setPinnedServersForTest(id -> Optional.of(new InstallCatalog.PinnedServer(
                real.id(),
                real.version(),
                Map.of(platform, new InstallCatalog.PinnedAsset(url, sha256, InstallCatalog.Packing.TAR_GZ)),
                real.launcher(),
                real.arguments(),
                real.downloadMegabytes(),
                real.installedMegabytes())));
    }

    @Test
    void theDistributionIsStreamedCheckedUnpackedAndItsLauncherBecomesTheCommand() throws IOException {
        byte[] archive = TestArchives.tar()
                .dir("kotlin-server-1/bin")
                .executable("kotlin-server-1/bin/intellij-server", "#!/bin/sh\n")
                .executable("kotlin-server-1/jbr/bin/java", "#!/bin/sh\n")
                .file("kotlin-server-1/lib/app.jar", "jar")
                .gz();
        serve(archive, InstallCatalog.sha256Hex(archive));

        InstallService.Result result = install();

        assertTrue(result.ok(), result.message());
        Path launcher = config.resolve("plugins/lsp/kotlin/kotlin-server-1/bin/intellij-server");
        assertTrue(Files.isExecutable(launcher));
        assertTrue(Files.isExecutable(config.resolve("plugins/lsp/kotlin/kotlin-server-1/jbr/bin/java")));
        assertEquals(InstallCatalog.binaryCommand(launcher, " --stdio"), result.installedCommand());
        try (var left = Files.list(config.resolve("plugins/lsp"))) {
            assertEquals(
                    List.of("kotlin"), left.map(p -> p.getFileName().toString()).toList(), "no download left");
        }
    }

    @Test
    void aDownloadThatDoesNotMatchThePinnedHashIsRefusedAndNothingIsInstalled() {
        byte[] archive = TestArchives.tar()
                .executable("kotlin-server-1/bin/intellij-server", "#!/bin/sh\n")
                .gz();
        serve(archive, "0".repeat(64));

        InstallService.Result result = install();

        assertFalse(result.ok());
        assertTrue(result.message().contains("checksum"), result.message());
        assertFalse(Files.exists(config.resolve("plugins/lsp/kotlin")));
    }

    @Test
    void anArchiveWithoutTheLauncherIsNotInstalled() {
        byte[] archive = TestArchives.tar().file("kotlin-server-1/README", "x").gz();
        serve(archive, InstallCatalog.sha256Hex(archive));

        InstallService.Result result = install();

        assertFalse(result.ok());
        assertTrue(result.message().contains("intellij-server"), result.message());
        assertFalse(Files.exists(config.resolve("plugins/lsp/kotlin")));
    }
}
