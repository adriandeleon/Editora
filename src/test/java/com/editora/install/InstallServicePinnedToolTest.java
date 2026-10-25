package com.editora.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.editora.install.InstallCatalog.Packing;
import com.editora.install.InstallCatalog.PinnedAsset;
import com.editora.install.InstallCatalog.PinnedTool;
import com.editora.install.InstallCatalog.Platform;
import com.editora.io.LoopbackDownloads;
import com.editora.io.TestArchives;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Installing ShellCheck and shfmt (shell review C2): the asset for this platform is downloaded from its pinned
 * URL, refused unless its SHA-256 matches the hash in the catalog, and the program ends up at
 * {@link InstallCatalog#installedToolPath}. The real URLs are answered by a loopback server with small fake
 * archives; nothing is fetched from the network.
 */
class InstallServicePinnedToolTest {

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

    private InstallService.Result install(String toolId) {
        return service.installSync(InstallCatalog.toolInstall(toolId).orElseThrow(), config, started::add);
    }

    /** The real recipe for {@code id}, with this platform's asset hash replaced by that of {@code body}. */
    private void serveAsRecipe(String id, byte[] body) {
        PinnedTool real = InstallCatalog.pinnedTool(id).orElseThrow();
        Platform here = InstallCatalog.currentPlatform();
        PinnedAsset asset = real.assetFor(here);
        web.serve(asset.url(), body);
        PinnedTool fake = new PinnedTool(
                id,
                real.version(),
                Map.of(here, new PinnedAsset(asset.url(), InstallCatalog.sha256Hex(body), asset.packing())),
                real.binaryName());
        service.setPinnedToolsForTest(t -> t.equals(id) ? Optional.of(fake) : Optional.empty());
    }

    @Test
    void shfmtIsDownloadedCheckedAndMadeRunnable() throws IOException {
        byte[] program = "#!/bin/sh\necho v3.14.1\n".getBytes();
        serveAsRecipe("shfmt", program);

        InstallService.Result result = install("shfmt");

        assertTrue(result.ok(), result.message());
        Path installed = InstallCatalog.installedToolPath(config, "shfmt");
        assertEquals(installed.toString(), result.installedCommand());
        assertEquals("#!/bin/sh\necho v3.14.1\n", Files.readString(installed));
        assertEquals(installed.toString(), InstallCatalog.installedToolIfPresent(config, "shfmt"));
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            assertTrue(Files.isExecutable(installed));
        }
        assertEquals(List.of("shfmt"), started);
    }

    @Test
    void aDownloadThatDoesNotMatchThePinnedHashIsRefusedAndNothingIsInstalled() {
        // The real recipe, whose hash is the published release's: these bytes are not that release.
        Platform here = InstallCatalog.currentPlatform();
        web.serve(
                InstallCatalog.pinnedTool("shfmt").orElseThrow().assetFor(here).url(), "not shfmt".getBytes());

        InstallService.Result result = install("shfmt");

        assertFalse(result.ok());
        assertTrue(result.message().contains("checksum"), result.message());
        assertFalse(Files.exists(InstallCatalog.installedToolPath(config, "shfmt")));
    }

    @Test
    void shellCheckIsTakenOutOfItsArchive() throws IOException {
        Platform here = InstallCatalog.currentPlatform();
        boolean zip = InstallCatalog.pinnedTool("shellcheck")
                        .orElseThrow()
                        .assetFor(here)
                        .packing()
                == Packing.ZIP;
        if (!zip) {
            assumeTrue(!ProcessRunner.resolveExecutable(List.of("tar")).get(0).equals("tar"), "tar is not installed");
        }
        String inner = "shellcheck-v0.11.0/" + (zip ? "shellcheck.exe" : "shellcheck");
        byte[] archive = zip
                ? TestArchives.zip("shellcheck-v0.11.0/README.txt", "readme", inner, "the checker")
                : TestArchives.tar()
                        .file("shellcheck-v0.11.0/README.txt", "readme")
                        .executable(inner, "the checker")
                        .gz();
        serveAsRecipe("shellcheck", archive);

        InstallService.Result result = install("shellcheck");

        assertTrue(result.ok(), result.message());
        Path installed = InstallCatalog.installedToolPath(config, "shellcheck");
        assertEquals("the checker", Files.readString(installed));
        try (var list = Files.list(installed.getParent())) {
            assertEquals(
                    List.of(InstallReceipt.FILE_NAME, installed.getFileName().toString()),
                    list.map(p -> p.getFileName().toString()).sorted().toList(),
                    "only the program is kept, with the receipt that says which release it is");
        }
    }

    @Test
    void anInstallRecordsThePinnedVersionItPutDown() throws IOException {
        serveAsRecipe("shfmt", "#!/bin/sh\necho v3.14.1\n".getBytes());

        assertTrue(install("shfmt").ok());

        Path dir = InstallCatalog.installedToolPath(config, "shfmt").getParent();
        InstallReceipt receipt = InstallReceipt.read(dir);
        assertEquals("shfmt", receipt.id());
        assertEquals(InstallCatalog.SHFMT_VERSION, receipt.version());
        assertTrue(receipt.installedAt() != null);
        PinnedVersions.Pin pin = PinnedVersions.forStep("shfmt").orElseThrow();
        assertEquals(PinnedVersions.Freshness.CURRENT, PinnedVersions.installedFreshness(pin, config));
    }

    @Test
    void aRefusedDownloadWritesNoReceipt() {
        serveAsRecipe("shfmt", "the real one".getBytes());
        service.setPinnedToolsForTest(t -> InstallCatalog.pinnedTool(t)); // the real hash: the body does not match

        assertFalse(install("shfmt").ok());

        assertEquals(null, InstallReceipt.read(config.resolve(InstallCatalog.toolDir("shfmt"))));
    }
}
