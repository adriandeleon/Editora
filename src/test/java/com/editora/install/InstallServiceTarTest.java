package com.editora.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.editora.install.InstallCatalog.Lang;
import com.editora.install.InstallCatalog.Step;
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
 * The installer's tarball steps (Eclipse JDT-LS, vscode-js-debug, the per-OS {@code .tar.gz}/{@code .tar.xz}
 * binaries), which hand extraction to the system {@code tar}. Downloads are answered by a loopback server and
 * everything lands in a JUnit temp dir.
 *
 * <p>Extraction is not Editora's code here, so the hostile-archive tests pin down the one thing that has to
 * hold whichever {@code tar} is installed (GNU tar on Linux, bsdtar on macOS): <b>nothing outside the install
 * folder is created or changed</b>. Whether {@code tar} skips the bad entry or fails the whole archive differs
 * between the two and is deliberately not asserted.
 */
@DisabledOnOs(OS.WINDOWS) // POSIX tar semantics and symbolic links; the zip steps cover Windows
class InstallServiceTarTest {

    @TempDir
    Path sandbox;

    private Path config;
    private Path outside;
    private LoopbackDownloads web;
    private InstallService service;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(!ProcessRunner.resolveExecutable(List.of("tar")).get(0).equals("tar"), "tar is not installed");
        config = Files.createDirectories(sandbox.resolve("config"));
        outside = Files.createDirectories(sandbox.resolve("outside"));
        Files.writeString(outside.resolve("victim.txt"), "original");
        web = new LoopbackDownloads();
        service = new InstallService(web.client(), 1024 * 1024);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
            web.close();
        }
    }

    private static Step jdtls() {
        return InstallCatalog.steps(Lang.JAVA).get(0);
    }

    private static Step jsDebug() {
        return InstallCatalog.steps(Lang.JAVASCRIPT).get(1);
    }

    private InstallService.Result install(Step step) {
        return service.installSync(List.of(step), config, id -> {});
    }

    private Path jdtlsDir() {
        return config.resolve("plugins/lsp/java");
    }

    /** Every path under the sandbox except the install folder itself, links not followed. */
    private List<String> everythingOutside(Path installDir) throws IOException {
        try (Stream<Path> walk = Files.walk(sandbox)) {
            return walk.filter(p -> !p.startsWith(installDir))
                    .map(p -> sandbox.relativize(p)
                            + (Files.isSymbolicLink(p) ? " (link)" : "")
                            + (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) ? " " + size(p) : ""))
                    .sorted()
                    .toList();
        }
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    // --- good archives ------------------------------------------------------------------------------

    @Test
    void theJavaLanguageServerTarballIsExtractedAndItsLauncherFound() throws IOException {
        web.serve(
                InstallCatalog.JDTLS_TARBALL_URL,
                TestArchives.tar()
                        .dir("bin")
                        .executable("bin/jdtls", "#!/usr/bin/env python3")
                        .file("bin/jdtls.py", "import sys")
                        .file("plugins/org.eclipse.equinox.launcher.jar", "jar")
                        .file("config_linux/config.ini", "osgi=1")
                        .gz());

        InstallService.Result result = install(jdtls());

        assertTrue(result.ok(), result.message());
        assertEquals("#!/usr/bin/env python3", Files.readString(jdtlsDir().resolve("bin/jdtls")));
        assertTrue(Files.isExecutable(jdtlsDir().resolve("bin/jdtls")), "tar keeps the archive's exec bit");
        assertEquals("jar", Files.readString(jdtlsDir().resolve("plugins/org.eclipse.equinox.launcher.jar")));
        assertEquals("osgi=1", Files.readString(jdtlsDir().resolve("config_linux/config.ini")));
        assertEquals(List.of(InstallCatalog.JDTLS_TARBALL_URL), web.requests());
    }

    /** The check that the download really was the server: the launcher the editor will run must be in it. */
    @Test
    void aTarballWithoutItsLauncherIsRejected() {
        web.serve(
                InstallCatalog.JDTLS_TARBALL_URL,
                TestArchives.tar()
                        .file("README.md", "not a language server")
                        .file("plugins/x.jar", "jar")
                        .gz());

        InstallService.Result result = install(jdtls());

        assertFalse(result.ok());
        assertEquals("jdtls: expected files not found after extraction", result.message());
    }

    @Test
    void theWindowsLauncherAloneSatisfiesTheCheck() {
        web.serve(
                InstallCatalog.JDTLS_TARBALL_URL,
                TestArchives.tar().file("bin/jdtls.bat", "@echo off").gz());

        assertTrue(install(jdtls()).ok());
    }

    /** js-debug: the asset URL comes from GitHub's release JSON, and the entry point sits in a subfolder. */
    @Test
    void theJavaScriptDebugAdapterIsFoundInTheReleaseMetadataAndInsideItsTarball() throws IOException {
        String asset =
                "https://github.com/microsoft/vscode-js-debug/releases/download/v1.105.0/js-debug-dap-v1.105.0.tar.gz";
        web.serve(
                jsDebug().apiUrl(),
                "{\"assets\":[{\"browser_download_url\":"
                        + "\"https://github.com/microsoft/vscode-js-debug/releases/download/v1.105.0/ms-vscode.js-debug.vsix\"},"
                        + "{\"browser_download_url\":\"" + asset + "\"}]}");
        web.serve(
                asset,
                TestArchives.tar()
                        .file("js-debug/package.json", "{}")
                        .file("js-debug/src/dapDebugServer.js", "server")
                        .gz());

        InstallService.Result result = install(jsDebug());

        assertTrue(result.ok(), result.message());
        assertEquals(
                "server", Files.readString(config.resolve("plugins/dap/javascript/js-debug/src/dapDebugServer.js")));
        assertEquals(List.of(jsDebug().apiUrl(), asset), web.requests());
    }

    @Test
    void aDebugAdapterTarballWithoutItsEntryPointIsRejected() {
        String asset =
                "https://github.com/microsoft/vscode-js-debug/releases/download/v1.105.0/js-debug-dap-v1.105.0.tar.gz";
        web.serve(jsDebug().apiUrl(), "{\"browser_download_url\":\"" + asset + "\"}");
        web.serve(asset, TestArchives.tar().file("js-debug/package.json", "{}").gz());

        InstallService.Result result = install(jsDebug());

        assertFalse(result.ok());
        assertEquals("js-debug: expected files not found after extraction", result.message());
    }

    @Test
    void aBinaryTarballIsExtractedAndItsBinaryBecomesTheServerCommand() throws IOException {
        var spec = InstallCatalog.archiveSpec("lua").orElseThrow();
        String mine = spec.assetByPlatform().get(InstallCatalog.currentPlatform());
        String asset =
                "https://github.com/LuaLS/lua-language-server/releases/download/3.15.0/lua-language-server-3.15.0-"
                        + mine + ".tar.gz";
        web.serve(spec.apiUrl(), "{\"assets\":[{\"browser_download_url\":\"" + asset + "\"}]}");
        web.serve(
                asset,
                TestArchives.tar()
                        .file("main.lua", "-- entry")
                        .executable("bin/lua-language-server", "ELF")
                        .gz());

        InstallService.Result result =
                service.installSync(InstallCatalog.serverInstall("lua").orElseThrow(), config, id -> {});

        assertTrue(result.ok(), result.message());
        Path binary = config.resolve("plugins/lsp/lua/bin/lua-language-server");
        assertEquals(binary.toString(), result.installedCommand());
        assertTrue(Files.isExecutable(binary));
        assertEquals("-- entry", Files.readString(config.resolve("plugins/lsp/lua/main.lua")));
    }

    /** The Typst CLI ships as {@code .tar.xz}; that branch lets {@code tar} detect the compression itself. */
    @Test
    void aTarballWithAnotherCompressionSuffixIsLeftToTarToDetect() throws IOException {
        var spec = InstallCatalog.archiveSpec("typst-cli").orElseThrow();
        String mine = spec.assetByPlatform().get(InstallCatalog.currentPlatform());
        String asset = "https://github.com/typst/typst/releases/download/v0.14.0/" + mine + ".tar.xz";
        web.serve(spec.apiUrl(), "{\"assets\":[{\"browser_download_url\":\"" + asset + "\"}]}");
        // No xz encoder in the JDK: a gzip body under the .tar.xz name still exercises "tar -xf" detection.
        web.serve(
                asset,
                TestArchives.tar()
                        .file(mine + "/LICENSE", "Apache")
                        .executable(mine + "/typst", "ELF")
                        .gz());

        InstallService.Result result = service.installSync(InstallCatalog.typstCliSteps(), config, id -> {});

        assertTrue(result.ok(), result.message());
        assertEquals(config.resolve("plugins/typst/" + mine + "/typst").toString(), result.installedCommand());
    }

    @Test
    void aBinaryTarballWithoutTheBinaryIsRejected() {
        var spec = InstallCatalog.archiveSpec("lua").orElseThrow();
        String mine = spec.assetByPlatform().get(InstallCatalog.currentPlatform());
        String asset = "https://github.com/LuaLS/lua-language-server/releases/download/3.15.0/lls-" + mine + ".tar.gz";
        web.serve(spec.apiUrl(), "\"" + asset + "\"");
        web.serve(asset, TestArchives.tar().file("README.md", "docs only").gz());

        InstallService.Result result =
                service.installSync(InstallCatalog.serverInstall("lua").orElseThrow(), config, id -> {});

        assertFalse(result.ok());
        assertEquals("lua: 'lua-language-server' not found after extraction", result.message());
    }

    /**
     * Links are ordinary in a tarball (a versioned binary behind a stable name). This also shows the link
     * entries the hostile-archive tests below are built from are ones {@code tar} really acts on.
     */
    @Test
    void linksThatStayInsideTheTreeAreExtracted() throws IOException {
        web.serve(
                InstallCatalog.JDTLS_TARBALL_URL,
                TestArchives.tar()
                        .executable("bin/jdtls-1.60", "launcher")
                        .symlink("bin/jdtls", "jdtls-1.60")
                        .symlink("current", "bin")
                        .hardlink("bin/jdtls-copy", "bin/jdtls-1.60")
                        .gz());

        InstallService.Result result = install(jdtls());

        assertTrue(result.ok(), result.message());
        assertTrue(Files.isSymbolicLink(jdtlsDir().resolve("bin/jdtls")));
        assertEquals("launcher", Files.readString(jdtlsDir().resolve("current/jdtls")));
        assertEquals("launcher", Files.readString(jdtlsDir().resolve("bin/jdtls-copy")));
    }

    // --- archives that try to write somewhere else ----------------------------------------------------

    /**
     * Serves {@code archive} as the JDT-LS tarball, installs it, and checks that everything outside the
     * install folder is exactly as it was — no new file, no changed file, no link.
     */
    private InstallService.Result installHostile(byte[] archive) throws IOException {
        Files.createDirectories(jdtlsDir().getParent()); // the folder an install is made in is expected
        List<String> before = everythingOutside(jdtlsDir());
        web.serve(InstallCatalog.JDTLS_TARBALL_URL, archive);

        InstallService.Result result = install(jdtls());

        assertEquals(before, everythingOutside(jdtlsDir()), "nothing outside the install folder may change");
        assertEquals("original", Files.readString(outside.resolve("victim.txt")));
        return result;
    }

    @Test
    void entriesThatClimbOutWithDotDotStayInside() throws IOException {
        // plugins/lsp/java is four levels below the sandbox: these names aim at the sandbox and at outside/.
        installHostile(TestArchives.tar()
                .executable("bin/jdtls", "launcher")
                .file("../escaped.txt", "pwned")
                .file("../../../../escaped.txt", "pwned")
                .file("../../../../outside/victim.txt", "overwritten")
                .file("bin/../../../../../outside/escaped.txt", "pwned")
                .gz());
    }

    @Test
    void entriesWithAbsoluteNamesStayInside() throws IOException {
        installHostile(TestArchives.tar()
                .executable("bin/jdtls", "launcher")
                .file(outside.resolve("escaped.txt").toString(), "pwned")
                .file(outside.resolve("victim.txt").toString(), "overwritten")
                .gz());
    }

    @Test
    void aFileWrittenThroughALinkToAnOutsideFolderStaysInside() throws IOException {
        installHostile(TestArchives.tar()
                .executable("bin/jdtls", "launcher")
                .symlink("away", "../../../../outside")
                .file("away/escaped.txt", "pwned")
                .file("away/victim.txt", "overwritten")
                .gz());
        installHostile(TestArchives.tar()
                .executable("bin/jdtls", "launcher")
                .symlink("away", outside.toString())
                .file("away/escaped.txt", "pwned")
                .file("away/victim.txt", "overwritten")
                .gz());
    }

    @Test
    void aFileThatReplacesALinkToAnOutsideFileDoesNotWriteThroughIt() throws IOException {
        installHostile(TestArchives.tar()
                .executable("bin/jdtls", "launcher")
                .symlink("config.ini", outside.resolve("victim.txt").toString())
                .file("config.ini", "overwritten")
                .gz());
        installHostile(TestArchives.tar()
                .executable("bin/jdtls", "launcher")
                .symlink("config.ini", "../../../../outside/victim.txt")
                .file("config.ini", "overwritten")
                .gz());
    }

    @Test
    void aHardLinkToAnOutsideFileDoesNotLetTheArchiveRewriteIt() throws IOException {
        installHostile(TestArchives.tar()
                .executable("bin/jdtls", "launcher")
                .hardlink("config.ini", outside.resolve("victim.txt").toString())
                .file("config.ini", "overwritten")
                .gz());
        installHostile(TestArchives.tar()
                .executable("bin/jdtls", "launcher")
                .hardlink("config.ini", "../../../../outside/victim.txt")
                .file("config.ini", "overwritten")
                .gz());
        assertEquals(1, Files.getAttribute(outside.resolve("victim.txt"), "unix:nlink"), "no second name for it");
    }

    @Test
    void linksNestedToWalkUpwardsStayInside() throws IOException {
        installHostile(TestArchives.tar()
                .executable("bin/jdtls", "launcher")
                .symlink("a", ".")
                .symlink("a/b", "..")
                .symlink("a/b/c", "..")
                .symlink("a/b/c/d", "..")
                .symlink("a/b/c/d/e", "..")
                .file("a/b/c/d/e/outside/escaped.txt", "pwned")
                .file("a/b/c/d/e/outside/victim.txt", "overwritten")
                .gz());
    }

    // --- reinstalling ---------------------------------------------------------------------------------

    @Test
    void reinstallingATarballReplacesThePreviousVersionCompletely() throws IOException {
        web.serve(
                InstallCatalog.JDTLS_TARBALL_URL,
                TestArchives.tar()
                        .executable("bin/jdtls", "v1")
                        .file("plugins/old-only.jar", "v1")
                        .gz());
        assertTrue(install(jdtls()).ok());
        Files.createSymbolicLink(jdtlsDir().resolve("shared"), outside);

        web.serve(
                InstallCatalog.JDTLS_TARBALL_URL,
                TestArchives.tar()
                        .executable("bin/jdtls", "v2")
                        .file("plugins/new-only.jar", "v2")
                        .gz());
        InstallService.Result result = install(jdtls());

        assertTrue(result.ok(), result.message());
        assertEquals("v2", Files.readString(jdtlsDir().resolve("bin/jdtls")));
        assertFalse(Files.exists(jdtlsDir().resolve("plugins/old-only.jar")), "the old version's files are gone");
        assertFalse(Files.exists(jdtlsDir().resolve("shared"), LinkOption.NOFOLLOW_LINKS));
        assertEquals("original", Files.readString(outside.resolve("victim.txt")), "the link's target is not ours");
    }
}
