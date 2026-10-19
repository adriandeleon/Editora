package com.editora.install;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import com.editora.io.LoopbackDownloads;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Installing Metals: coursier's launcher is downloaded from its pinned URL and refused unless its SHA-256 matches,
 * {@code cs fetch} resolves the server's jars into the config dir (its cache inside the staging folder), and the
 * command runs them on {@code java -cp}. A loopback server answers the real URL, and the "launcher" is a shell
 * script that prints the jars a real {@code cs fetch} would; nothing is fetched from the network.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the stand-in coursier is a POSIX shell script")
class InstallServiceCoursierTest {

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

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    /** A coursier "release" for this platform serving {@code launcher}, hashed as the real one would be. */
    private InstallCatalog.PinnedTool release(byte[] served) {
        return release(served, InstallCatalog.Packing.GZ);
    }

    private InstallCatalog.PinnedTool release(byte[] served, InstallCatalog.Packing packing) {
        InstallCatalog.Platform platform = InstallCatalog.currentPlatform();
        InstallCatalog.PinnedAsset real = InstallCatalog.COURSIER_TOOL.assetFor(platform);
        web.serve(real.url(), served);
        return new InstallCatalog.PinnedTool(
                "coursier",
                InstallCatalog.COURSIER_VERSION,
                Map.of(platform, new InstallCatalog.PinnedAsset(real.url(), InstallCatalog.sha256Hex(served), packing)),
                "cs");
    }

    private InstallService.Result install() {
        return service.installSync(InstallCatalog.serverInstall("scala").orElseThrow(), config, started::add);
    }

    @Test
    void theJarsCoursierResolvesAreCopiedAndRunOnJava() throws IOException {
        Path repo = Files.createDirectories(sandbox.resolve("repo"));
        Path metals = Files.writeString(repo.resolve("metals_2.13-1.6.9.jar"), "metals");
        Path a = Files.writeString(Files.createDirectories(repo.resolve("a")).resolve("util-1.0.jar"), "a");
        Path b = Files.writeString(Files.createDirectories(repo.resolve("b")).resolve("util-1.0.jar"), "b");
        // The stand-in checks it was given coursier's cache and the artifact, then prints the classpath.
        String script = "#!/bin/sh\n"
                + "[ \"$1\" = fetch ] && [ \"$2\" = org.scalameta:metals_2.13:1.6.9 ] || exit 3\n"
                + "[ -d \"$COURSIER_CACHE\" ] || exit 4\n"
                + "echo '" + metals + "'\necho '" + a + "'\necho '" + b + "'\n";
        service.setCoursierForTest(
                release(gzip(script.getBytes(StandardCharsets.UTF_8))), id -> Optional.of(InstallCatalog.METALS));

        InstallService.Result result = install();

        assertTrue(result.ok(), result.message());
        Path dest = config.resolve("plugins/lsp/scala");
        Path lib = dest.resolve("lib");
        assertEquals("metals", Files.readString(lib.resolve("metals_2.13-1.6.9.jar")));
        assertEquals("a", Files.readString(lib.resolve("util-1.0.jar")));
        assertEquals("b", Files.readString(lib.resolve("2-util-1.0.jar")), "a second jar of one name is kept too");
        assertTrue(Files.isExecutable(dest.resolve("cs")), "coursier is kept beside the jars");
        assertFalse(Files.exists(dest.resolve(".coursier-cache")), "its cache goes once the jars are copied");
        assertEquals(InstallCatalog.coursierLspCommand(lib, InstallCatalog.METALS), result.installedCommand());
        assertEquals(List.of("metals"), started);
    }

    /** Windows' release is a zip holding {@code cs-x86_64-pc-win32.exe}: the launcher is found by its prefix. */
    @Test
    void aZippedLauncherNamedForItsPlatformIsFound() throws IOException {
        Path jar = Files.writeString(sandbox.resolve("metals.jar"), "metals");
        String script = "#!/bin/sh\necho '" + jar + "'\n";
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (var out = new java.util.zip.ZipOutputStream(zip)) {
            out.putNextEntry(new java.util.zip.ZipEntry("cs-x86_64-pc-linux"));
            out.write(script.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        service.setCoursierForTest(
                release(zip.toByteArray(), InstallCatalog.Packing.ZIP), id -> Optional.of(InstallCatalog.METALS));

        InstallService.Result result = install();

        assertTrue(result.ok(), result.message());
        assertTrue(Files.isExecutable(config.resolve("plugins/lsp/scala/cs")));
        assertEquals("metals", Files.readString(config.resolve("plugins/lsp/scala/lib/metals.jar")));
    }

    @Test
    void aLauncherThatDoesNotMatchItsPinnedHashIsRefused() {
        web.serve(
                InstallCatalog.COURSIER_TOOL
                        .assetFor(InstallCatalog.currentPlatform())
                        .url(),
                "not cs".getBytes());

        InstallService.Result result = install();

        assertFalse(result.ok());
        assertTrue(result.message().contains("checksum"), result.message());
        assertFalse(Files.exists(config.resolve("plugins/lsp/scala")));
    }

    @Test
    void aFailedResolveInstallsNothing() throws IOException {
        service.setCoursierForTest(
                release(gzip("#!/bin/sh\necho 'not found: metals' >&2\nexit 1\n".getBytes(StandardCharsets.UTF_8))),
                id -> Optional.of(InstallCatalog.METALS));

        InstallService.Result result = install();

        assertFalse(result.ok());
        assertTrue(result.message().contains("could not resolve"), result.message());
        assertFalse(Files.exists(config.resolve("plugins/lsp/scala/lib")));
    }
}
