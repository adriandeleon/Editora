package com.editora.install;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.io.LoopbackDownloads;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lombok on request: the pinned Central jar, refused unless its SHA-256 is the recipe's, never left half-written. */
class InstallServiceLombokTest {

    @TempDir
    Path sandbox;

    private LoopbackDownloads web;
    private InstallService service;

    @BeforeEach
    void setUp() throws Exception {
        web = new LoopbackDownloads();
        service = new InstallService(web.client(), 4 * 1024 * 1024);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        web.close();
    }

    @Test
    void theCatalogPinIsAMavenCentralJarTheInstallerTrusts() {
        assertEquals(
                "https://repo1.maven.org/maven2/org/projectlombok/lombok/1.18.48/lombok-1.18.48.jar",
                InstallCatalog.LOMBOK.url());
        assertTrue(InstallCatalog.isTrustedDownloadUrl(InstallCatalog.LOMBOK.url()));
        assertEquals(64, InstallCatalog.LOMBOK.sha256().length());
    }

    @Test
    void aMatchingJarIsInstalledAndAMismatchIsRefused() throws Exception {
        byte[] jar = "PK lombok".getBytes();
        web.serve(InstallCatalog.LOMBOK.url(), jar);
        Path dir = sandbox.resolve("plugins/lsp/lombok");
        var recipe = new InstallCatalog.JvmLspJar(
                InstallCatalog.LOMBOK.url(), InstallCatalog.sha256Hex(jar), InstallCatalog.LOMBOK.fileName());

        Path installed = service.installLombok(dir, recipe);

        assertEquals(dir.resolve("lombok-1.18.48.jar"), installed);
        assertArrayEquals(jar, Files.readAllBytes(installed));
        assertFalse(Files.exists(dir.resolve("lombok-1.18.48.jar.part")));

        Path other = sandbox.resolve("other");
        assertThrows(Exception.class, () -> service.installLombok(other), "the bytes are not the pinned jar");
        assertFalse(Files.exists(other.resolve("lombok-1.18.48.jar")));
    }
}
