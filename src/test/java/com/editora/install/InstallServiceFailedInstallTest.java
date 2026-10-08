package com.editora.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.editora.io.LoopbackDownloads;
import com.editora.io.TestArchives;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an install that fails <em>after</em> its download leaves behind. The archive used to be unpacked
 * straight into the tool's folder, after deleting whatever was there: an archive that turned out to be bad
 * left a half-unpacked folder — which the editor's detection then took for an installed tool — and had
 * already destroyed the working version it was meant to replace.
 *
 * <p>An install is now unpacked beside its destination and swapped in only once it has passed its checks, so
 * a failure leaves the folder exactly as it was: absent on a first install, the previous version on a
 * reinstall. (The tarball flavours of the same cases are in {@link InstallServiceTarTest}.)
 */
class InstallServiceFailedInstallTest {

    @TempDir
    Path sandbox;

    private Path config;
    private LoopbackDownloads web;
    private InstallService service;

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

    private InstallService.Result installMaven() {
        return service.installSync(InstallCatalog.serverInstall("maven-pom").orElseThrow(), config, id -> {});
    }

    private Path mavenDir() {
        return config.resolve("plugins/lsp/maven");
    }

    private Path installedMavenServer() throws IOException {
        web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, TestArchives.zip("lemminx.jar", "v1", "lib.jar", "v1"));
        assertTrue(installMaven().ok());
        return mavenDir();
    }

    /** The names directly inside {@code dir} (empty when it does not exist). */
    private static List<String> children(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(dir)) {
            return list.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** Good entries first, so files are already on disk when the bad entry is reached. */
    private static byte[] zipThatGoesBadPartWay() {
        return TestArchives.zip("lemminx.jar", "v2", "lib.jar", "v2", "../../escaped.txt", "pwned", "late.jar", "v2");
    }

    @Test
    void anArchiveRefusedPartWayLeavesNoHalfInstalledFolder() throws IOException {
        web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, zipThatGoesBadPartWay());

        InstallService.Result result = installMaven();

        assertFalse(result.ok());
        assertNull(result.installedCommand());
        assertFalse(Files.exists(mavenDir()), "found: " + children(mavenDir()));
        assertEquals(List.of(), children(config.resolve("plugins/lsp")), "nor a staging folder beside it");
    }

    @Test
    void anArchiveRefusedPartWayLeavesTheInstalledVersionWorking() throws IOException {
        Path dest = installedMavenServer();

        web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, zipThatGoesBadPartWay());
        InstallService.Result result = installMaven();

        assertFalse(result.ok());
        assertEquals(List.of("lemminx.jar", "lib.jar"), children(dest));
        assertEquals("v1", Files.readString(dest.resolve("lemminx.jar")));
        assertEquals("v1", Files.readString(dest.resolve("lib.jar")));
        assertEquals(List.of("maven"), children(config.resolve("plugins/lsp")));
    }

    /**
     * A sign-in page or an error document served with status 200 is not a zip, and reading it as one yields
     * no entries and no error. That used to count as a successful install: the old server was deleted, an
     * empty folder took its place, and its launch command was saved to Settings.
     */
    @Test
    void aBodyThatIsNotAnArchiveIsNotAnInstall() throws IOException {
        Path dest = installedMavenServer();

        web.serve(
                InstallCatalog.LEMMINX_MAVEN_ZIP_URL,
                "<html><body>Sign in to continue</body></html>".getBytes(StandardCharsets.UTF_8));
        InstallService.Result result = installMaven();

        assertFalse(result.ok(), "an empty folder must not be reported as an installed server");
        assertTrue(result.message().startsWith("lemminx-maven: "), result.message());
        assertNull(result.installedCommand(), "no launch command for a server that is not there");
        assertEquals(List.of("lemminx.jar", "lib.jar"), children(dest));
    }

    /** The server is started as {@code java -cp "<dir>/*"}: a bundle with no jar at its top cannot run. */
    @Test
    void aBundleWithoutAJarAtItsTopIsNotAnInstall() throws IOException {
        web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, TestArchives.zip("README.txt", "docs", "nested/x.jar", "j"));

        InstallService.Result result = installMaven();

        assertFalse(result.ok());
        assertNull(result.installedCommand());
        assertFalse(Files.exists(mavenDir()));
    }

    @Test
    void aBinaryArchiveWithoutItsBinaryLeavesTheInstalledVersionWorking() throws IOException {
        var spec = InstallCatalog.archiveSpec("terraform").orElseThrow();
        String asset = "https://releases.hashicorp.com/terraform-ls/1/terraform-ls_"
                + spec.assetByPlatform().get(InstallCatalog.currentPlatform()) + ".zip";
        List<InstallCatalog.Step> steps =
                InstallCatalog.serverInstall("terraform").orElseThrow();
        web.serve(spec.apiUrl(), "\"" + asset + "\"");
        web.serve(asset, TestArchives.zip("terraform-ls", "v1"));
        InstallService.Result first = service.installSync(steps, config, id -> {});
        assertTrue(first.ok(), first.message());
        Path binary = config.resolve("plugins/lsp/terraform/terraform-ls");

        web.serve(asset, TestArchives.zip("README.md", "the binary moved to another download"));
        InstallService.Result second = service.installSync(steps, config, id -> {});

        assertFalse(second.ok());
        assertEquals("terraform: 'terraform-ls' not found after extraction", second.message());
        assertEquals("v1", Files.readString(binary), "the command saved in Settings still points at a server");
        assertEquals(List.of("terraform"), children(config.resolve("plugins/lsp")));
    }

    @Test
    void aSuccessfulInstallLeavesNoStagingFolderBehind() throws IOException {
        installedMavenServer();
        web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, TestArchives.zip("lemminx.jar", "v2"));
        assertTrue(installMaven().ok());

        assertEquals(List.of("maven"), children(config.resolve("plugins/lsp")));
        assertEquals(List.of("lemminx.jar"), children(mavenDir()));
    }

    /**
     * An install the app was killed in the middle of leaves its staging folder. The next install of the same
     * tool clears it — but only an old one: a fresh one may be another window's install, still running.
     */
    @Test
    void theRemainsOfAnInstallThatWasKilledAreClearedByTheNextOne() throws IOException {
        Path lsp = Files.createDirectories(config.resolve("plugins/lsp"));
        Path dead = Files.createDirectories(lsp.resolve(InstallService.STAGING_PREFIX + "maven-dead/lib"))
                .getParent();
        Files.writeString(dead.resolve("lib/half.jar"), "half");
        Files.setLastModifiedTime(
                dead,
                java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(3))));
        Path running = Files.createDirectories(lsp.resolve(InstallService.STAGING_PREFIX + "maven-running"));
        Path otherTool = Files.createDirectories(lsp.resolve(InstallService.STAGING_PREFIX + "java-dead"));
        Files.setLastModifiedTime(
                otherTool,
                java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(3))));

        installedMavenServer();

        assertFalse(Files.exists(dead));
        assertTrue(Files.exists(running), "a recent staging folder is left alone");
        assertTrue(Files.exists(otherTool), "another tool's staging folder is its own install's to clear");
        assertEquals(List.of("lemminx.jar", "lib.jar"), children(mavenDir()));
    }
}
