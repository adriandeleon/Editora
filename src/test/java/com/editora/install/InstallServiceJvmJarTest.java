package com.editora.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.editora.io.LoopbackDownloads;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Installing the Groovy server (gvy's {@code gls} jar): downloaded from its pinned URL, refused unless its SHA-256
 * is the catalog's, kept under the config dir, and run as {@code java -jar}. A loopback server answers the real
 * URL; nothing is fetched from the network.
 */
class InstallServiceJvmJarTest {

    @TempDir
    Path sandbox;

    private Path config;
    private LoopbackDownloads web;
    private InstallService service;
    private final List<String> started = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        config = Files.createDirectories(sandbox.resolve("config"));
        web = new LoopbackDownloads();
        service = new InstallService(web.client(), 1024 * 1024);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        web.close();
    }

    private InstallService.Result install() {
        return service.installSync(InstallCatalog.serverInstall("groovy").orElseThrow(), config, started::add);
    }

    @Test
    void theJarIsDownloadedCheckedAndRunWithJava() throws IOException {
        byte[] jar = "PK fake gls".getBytes();
        InstallCatalog.JvmLspJar real = InstallCatalog.GLS_JAR;
        web.serve(real.url(), jar);
        service.setJvmLspJarsForTest(id -> "gls".equals(id)
                ? Optional.of(new InstallCatalog.JvmLspJar(real.url(), InstallCatalog.sha256Hex(jar), real.fileName()))
                : Optional.empty());

        InstallService.Result result = install();

        assertTrue(result.ok(), result.message());
        Path installed = config.resolve("plugins/lsp/groovy").resolve(real.fileName());
        assertArrayEquals(jar, Files.readAllBytes(installed));
        assertEquals(InstallCatalog.jvmJarCommand(installed), result.installedCommand());
        assertEquals(List.of("gls"), started);
    }

    @Test
    void aJarThatDoesNotMatchThePinnedHashIsRefusedAndNothingIsInstalled() {
        web.serve(InstallCatalog.GLS_JAR.url(), "not gls".getBytes());

        InstallService.Result result = install();

        assertFalse(result.ok());
        assertTrue(result.message().contains("checksum"), result.message());
        assertFalse(Files.exists(config.resolve("plugins/lsp/groovy")));
    }
}
