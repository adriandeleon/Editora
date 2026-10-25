package com.editora.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.io.LoopbackDownloads;
import com.editora.io.TestArchives;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Installing the Maven pom.xml server (Maven review C1): the upstream "zip with dependencies" plus the three
 * pinned jars it leaves out, each refused unless its SHA-256 is the catalog's, then one request to the extracted
 * server before it is swapped in. A loopback server answers the real URLs; nothing is fetched from the network;
 * the request is a seam here (the real one is {@link JvmLspSmokeTest}'s business).
 */
class InstallServiceJvmZipExtrasTest {

    @TempDir
    Path sandbox;

    private Path config;
    private LoopbackDownloads web;
    private InstallService service;
    private final List<String> started = new ArrayList<>();

    /** The extra-jar recipes with hashes of this test's own bytes, in the catalog's order. */
    private final List<InstallCatalog.JvmLspJar> recipes = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        config = Files.createDirectories(sandbox.resolve("config"));
        web = new LoopbackDownloads();
        service = new InstallService(web.client(), 1024 * 1024);
        web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, bundle("v1"));
        for (InstallCatalog.JvmLspJar real : InstallCatalog.LEMMINX_MAVEN_EXTRA_JARS) {
            byte[] jar = ("PK " + real.fileName()).getBytes();
            web.serve(real.url(), jar);
            recipes.add(new InstallCatalog.JvmLspJar(real.url(), InstallCatalog.sha256Hex(jar), real.fileName()));
        }
        service.setJvmLspExtraJarsForTest(id -> InstallCatalog.LEMMINX_MAVEN_STEP.equals(id) ? recipes : List.of());
        service.setJvmLspSmokeForTest((argv, dir) -> Optional.empty());
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        web.close();
    }

    private static byte[] bundle(String version) {
        return TestArchives.zip("org.eclipse.lemminx-0.30.1.jar", version, "lemminx-maven-0.12.0.jar", version);
    }

    private InstallService.Result install() {
        return service.installSync(InstallCatalog.serverInstall("maven-pom").orElseThrow(), config, started::add);
    }

    private Path dest() {
        return config.resolve("plugins/lsp/maven");
    }

    /** The jars installed, without the version receipt every pinned install leaves (InstallReceiptTest). */
    private List<String> installed() throws IOException {
        try (var list = Files.list(dest())) {
            return list.map(p -> p.getFileName().toString())
                    .filter(n -> !n.equals(InstallReceipt.FILE_NAME))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void theBundleAndTheThreeExtraJarsLandInOneFolderAndTheCommandIsItsWildcardClasspath() throws IOException {
        InstallService.Result result = install();

        assertTrue(result.ok(), result.message());
        assertEquals(
                List.of(
                        "commons-lang3-3.21.0.jar",
                        "guava-33.7.2-jre.jar",
                        "jsoup-1.23.2.jar",
                        "lemminx-maven-0.12.0.jar",
                        "org.eclipse.lemminx-0.30.1.jar"),
                installed());
        assertEquals("PK guava-33.7.2-jre.jar", Files.readString(dest().resolve("guava-33.7.2-jre.jar")));
        assertEquals(
                InstallCatalog.jvmClasspathCommand(dest(), InstallCatalog.LEMMINX_MAIN_CLASS),
                result.installedCommand());
        assertTrue(MavenServerInstall.complete(dest()), "what the install leaves is what detection requires");
        assertEquals(List.of("lemminx-maven"), started);
        assertEquals(
                4,
                web.requests().stream()
                        .filter(r -> r.contains("lemminx-maven") || r.contains("maven2"))
                        .count(),
                "the bundle and three jars, each once");
    }

    @Test
    void theRequestIsSentToTheStagedFolderWithEverythingInItBeforeTheSwap() {
        AtomicReference<Path> askedIn = new AtomicReference<>();
        AtomicReference<List<String>> askedWith = new AtomicReference<>();
        AtomicReference<Boolean> completeWhenAsked = new AtomicReference<>();
        service.setJvmLspSmokeForTest((argv, dir) -> {
            askedIn.set(dir);
            askedWith.set(argv);
            completeWhenAsked.set(MavenServerInstall.complete(dir));
            return Optional.empty();
        });

        assertTrue(install().ok());

        assertTrue(completeWhenAsked.get(), "the extra jars are in place before the server is asked");
        assertFalse(askedIn.get().equals(dest()), "asked in the staging folder, not the live one");
        assertEquals(Optional.of(askedIn.get()), MavenServerInstall.classpathDir(askedWith.get()));
        assertEquals("java", askedWith.get().get(0));
        assertEquals(
                InstallCatalog.LEMMINX_MAIN_CLASS,
                askedWith.get().get(askedWith.get().size() - 1));
    }

    @Test
    void anExtraJarThatDoesNotMatchItsPinnedHashRefusesTheWholeInstall() {
        InstallCatalog.JvmLspJar jsoup = recipes.get(2);
        web.serve(jsoup.url(), "not jsoup".getBytes());

        InstallService.Result result = install();

        assertFalse(result.ok());
        assertTrue(result.message().contains("jsoup-1.23.2.jar"), result.message());
        assertTrue(result.message().contains("checksum"), result.message());
        assertFalse(Files.exists(dest()), "nothing of a refused install is left behind");
    }

    @Test
    void aServerThatCannotAnswerARequestIsNotInstalledAndThePreviousInstallStays() throws IOException {
        assertTrue(install().ok());
        web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, bundle("v2"));
        service.setJvmLspSmokeForTest(
                (argv, dir) -> Optional.of(
                        "hover failed inside the server: -32603 Internal error.: java.lang.NoClassDefFoundError: com/google/common/cache/CacheBuilder"));

        InstallService.Result result = install();

        assertFalse(result.ok());
        assertTrue(
                result.message().startsWith("lemminx-maven: the server was extracted but could not answer a request ("),
                result.message());
        assertTrue(result.message().contains("NoClassDefFoundError"), result.message());
        assertEquals(
                "v1",
                Files.readString(dest().resolve("org.eclipse.lemminx-0.30.1.jar")),
                "the working version is kept");
        assertTrue(MavenServerInstall.complete(dest()));
    }

    @Test
    void aFirstInstallWhoseServerCannotAnswerLeavesNoFolder() {
        service.setJvmLspSmokeForTest((argv, dir) -> Optional.of("no answer to hover within 60 s"));

        InstallService.Result result = install();

        assertFalse(result.ok());
        assertTrue(result.message().contains("no answer to hover within 60 s"), result.message());
        assertFalse(Files.exists(dest()));
    }
}
