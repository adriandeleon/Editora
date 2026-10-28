package com.editora.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.editora.install.InstallCatalog.PinnedAsset;
import com.editora.install.InstallCatalog.PinnedTool;
import com.editora.install.InstallCatalog.Platform;
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
 * Installing lemminx, the XML server (XML review A2, A3, A4, A9, A14): the pinned vscode-xml zip for this
 * platform is answered by a loopback server in the shape of the real release (the program named per platform,
 * with a sibling beside it), refused unless both the zip's and the program's SHA-256 match, unpacked under the
 * plain name {@code lemminx}, asked to answer a request before it is swapped in, and recorded with a receipt. A
 * program that cannot run here installs nothing, and falls back to the Java lemminx of an installed pom.xml
 * server. Nothing is fetched from the network and no real server runs: the smoke check is a test double.
 */
class InstallServiceLemminxTest {

    @TempDir
    Path sandbox;

    private Path config;
    private LoopbackDownloads web;
    private InstallService service;
    private final List<List<String>> smoked = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        config = Files.createDirectories(sandbox.resolve("config"));
        web = new LoopbackDownloads();
        service = new InstallService(web.client(), 1024 * 1024);
        service.setNativeLspSmokeForTest((argv, dir) -> {
            smoked.add(argv);
            return Optional.empty();
        });
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        web.close();
    }

    private InstallService.Result install() {
        return service.installSync(InstallCatalog.serverInstall("xml").orElseThrow(), config, id -> {});
    }

    private static PinnedAsset realAsset() {
        return InstallCatalog.pinnedTool(InstallCatalog.LEMMINX_STEP)
                .orElseThrow()
                .assetFor(InstallCatalog.currentPlatform());
    }

    /**
     * Serves {@code zip} at the real asset URL, with a recipe whose zip hash is {@code zip}'s and whose program hash
     * is {@code programHash}.
     */
    private void serve(byte[] zip, String programHash) {
        PinnedTool real = InstallCatalog.pinnedTool(InstallCatalog.LEMMINX_STEP).orElseThrow();
        Platform here = InstallCatalog.currentPlatform();
        PinnedAsset asset = realAsset();
        web.serve(asset.url(), zip);
        PinnedTool fake = new PinnedTool(
                real.id(),
                real.version(),
                Map.of(
                        here,
                        new PinnedAsset(
                                asset.url(),
                                InstallCatalog.sha256Hex(zip),
                                asset.packing(),
                                asset.member(),
                                programHash)),
                real.binaryName());
        service.setPinnedToolsForTest(t -> t.equals(real.id()) ? Optional.of(fake) : Optional.empty());
    }

    /** A zip shaped like the release's: the per-platform program, and a sibling whose name also starts "lemminx". */
    private static byte[] releaseZip(String program) {
        return TestArchives.zip("lemminx.log", "not the server", realAsset().member(), program);
    }

    private static String hash(String text) {
        return InstallCatalog.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    private Path installed() {
        return InstallCatalog.currentPlatform() == Platform.WIN_X64
                ? config.resolve("plugins/lsp/xml/lemminx.exe")
                : config.resolve("plugins/lsp/xml/lemminx");
    }

    @Test
    void theExactProgramIsInstalledAsLemminxCheckedAndRecorded() throws IOException {
        serve(releaseZip("the server"), hash("the server"));

        InstallService.Result result = install();

        assertTrue(result.ok(), result.message());
        assertEquals(installed().toString(), result.installedCommand());
        assertEquals("", result.message(), "nothing to report: the native server is in place");
        assertEquals("the server", Files.readString(installed()), "the program, not its lemminx.log sibling (A14)");
        try (var list = Files.list(installed().getParent())) {
            assertEquals(
                    List.of(InstallReceipt.FILE_NAME, installed().getFileName().toString()),
                    list.map(p -> p.getFileName().toString()).sorted().toList());
        }
        InstallReceipt receipt = InstallReceipt.read(installed().getParent());
        assertEquals(InstallCatalog.LEMMINX_STEP, receipt.id());
        assertEquals(InstallCatalog.LEMMINX_VERSION, receipt.version(), "the version is known afterwards (A3)");
        assertEquals(1, smoked.size(), "it was asked to answer a request before it was swapped in (A4)");
        assertTrue(smoked.get(0).get(0).endsWith(installed().getFileName().toString()));
        assertEquals(installed(), XmlServerInstall.ownCopy(config), "a blank XML command finds it (A9)");
    }

    @Test
    void aDownloadThatIsNotTheReleaseIsRefused() {
        // The real recipe, whose hashes are the release's: these bytes are not that release (A2).
        web.serve(realAsset().url(), releaseZip("#!/bin/sh\necho tampered\n"));

        InstallService.Result result = install();

        assertFalse(result.ok());
        assertTrue(result.message().contains("checksum"), result.message());
        assertFalse(Files.exists(config.resolve("plugins/lsp/xml")));
        assertTrue(smoked.isEmpty(), "a refused download is never run");
    }

    @Test
    void aProgramThatIsNotTheReleasesIsRefusedEvenInsideAMatchingZip() {
        serve(releaseZip("#!/bin/sh\necho tampered\n"), hash("the real server"));

        InstallService.Result result = install();

        assertFalse(result.ok());
        assertTrue(result.message().contains("unpacked program does not match"), result.message());
        assertFalse(Files.exists(installed()));
        assertTrue(smoked.isEmpty());
    }

    @Test
    void aProgramThatCannotRunHereInstallsNothingAndSaysWhy() throws IOException {
        Path old = Files.createDirectories(config.resolve("plugins/lsp/xml")).resolve("lemminx-linux-x86_64");
        Files.writeString(old, "an older copy that runs");
        serve(releaseZip("the server"), hash("the server"));
        service.setNativeLspSmokeForTest((argv, dir) -> Optional.of(
                "the server exited with code 1 before answering initialize: version" + " `GLIBC_2.34' not found"));

        InstallService.Result result = install();

        assertFalse(result.ok());
        assertTrue(result.message().contains("GLIBC_2.34"), result.message());
        assertTrue(result.message().contains("pom.xml"), "says what would let it run: " + result.message());
        assertEquals("an older copy that runs", Files.readString(old), "the copy that was there is kept");
        assertFalse(Files.exists(installed()));
        assertNull(InstallReceipt.read(old.getParent()), "no receipt claims a release that is not there");
    }

    @Test
    void aProgramThatCannotRunHereFallsBackToTheJavaLemminxOfThePomServer() throws IOException {
        Path maven = Files.createDirectories(config.resolve("plugins/lsp/maven"));
        for (String artifact : XmlServerInstall.PLAIN_LEMMINX_ARTIFACTS) {
            Files.writeString(maven.resolve(artifact + "-1.0.jar"), "");
        }
        for (String jar : List.of("lemminx-maven-0.12.0.jar", "maven-core-3.9.9.jar", "plexus-utils-3.5.1.jar")) {
            Files.writeString(maven.resolve(jar), "");
        }
        serve(releaseZip("the server"), hash("the server"));
        service.setNativeLspSmokeForTest((argv, dir) -> Optional.of("version `GLIBC_2.34' not found"));

        InstallService.Result result = install();

        assertTrue(result.ok(), result.message());
        assertEquals(XmlServerInstall.javaLemminxCommand(maven), result.installedCommand());
        // lemminx's own jars only: lemminx-maven on the classpath served untrusted poms as trusted (review 3, A21)
        assertFalse(com.editora.lsp.LemminxMavenGuard.loadsLemminxMaven(
                com.editora.run.ProgramArgs.commandLine(result.installedCommand()), true));
        assertFalse(result.installedCommand().contains("maven-core"), result.installedCommand());
        assertEquals("version `GLIBC_2.34' not found", result.message(), "the reason, for the status line");
        assertNull(XmlServerInstall.ownCopy(config), "nothing of the native server is left");
        // Only the note that XML runs on the Java lemminx: a blank command keeps it (XML/pom review 2, A8).
        try (var left = Files.list(config.resolve("plugins/lsp/xml"))) {
            assertEquals(
                    List.of(XmlServerInstall.JAVA_FALLBACK),
                    left.map(p -> p.getFileName().toString()).toList());
        }
        assertEquals(result.installedCommand(), XmlServerInstall.effectiveCommand("", config));
        assertFalse(XmlServerInstall.isNativeInstall(result.installedCommand()));
    }
}
