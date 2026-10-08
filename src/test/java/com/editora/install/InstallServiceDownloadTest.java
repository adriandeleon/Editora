package com.editora.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.editora.install.InstallCatalog.Kind;
import com.editora.install.InstallCatalog.Lang;
import com.editora.install.InstallCatalog.Step;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The in-app installer end to end, for the steps that need no system tool: download, checks, zip/vsix
 * extraction and what is left on disk afterwards. The catalog's real URLs are answered by a loopback server
 * ({@link LoopbackDownloads}); nothing is fetched from the network and everything lands in a JUnit temp dir.
 * The tarball steps, which shell out to {@code tar}, are in {@link InstallServiceTarTest}.
 */
class InstallServiceDownloadTest {

    /** Small on purpose, so the oversize case needs a few kilobytes rather than 200 MB. */
    private static final long DOWNLOAD_CAP = 64 * 1024;

    private static final String OLD_DEBUG_JAR = "com.microsoft.java.debug.plugin-0.50.0.jar";
    private static final String NEW_DEBUG_JAR = "com.microsoft.java.debug.plugin-0.53.1.jar";
    private static final String VSIX_URL =
            "https://open-vsx.org/api/vscjava/vscode-java-debug/0.58.2/file/vscjava.vscode-java-debug-0.58.2.vsix";

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
        service = new InstallService(web.client(), DOWNLOAD_CAP);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        web.close();
    }

    private InstallService.Result install(List<Step> steps) {
        return service.installSync(steps, config, started::add);
    }

    private static List<Step> mavenPom() {
        return InstallCatalog.serverInstall("maven-pom").orElseThrow();
    }

    private static Step javaDebug() {
        return InstallCatalog.steps(Lang.JAVA).get(1);
    }

    /** A step that downloads {@code url} as a zip into {@code plugins/lsp/probe}. */
    private static Step zipFrom(String url) {
        return new Step(
                "probe", Kind.JVM_LSP_ZIP, Set.of(), null, null, null, null, url, false, "plugins/lsp/probe", null);
    }

    /** A step that runs {@code argv}, the way the go/gem/dotnet/rustup/composer installs do. */
    private static Step command(String id, List<String> argv) {
        return new Step(id, Kind.TOOL_COMMAND, Set.of(), argv, null, null, null, null, false, "", null);
    }

    /** This JVM's own launcher: a program that is certainly present, on every platform the tests run on. */
    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static List<String> names(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> !p.equals(dir))
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    // --- each archive kind lands where it should ----------------------------------------------------

    @Test
    void aJvmServerBundleIsExtractedAndItsLaunchCommandReturned() throws IOException {
        web.serve(
                InstallCatalog.LEMMINX_MAVEN_ZIP_URL, TestArchives.zip("lemminx.jar", "core", "lib/maven.jar", "ext"));

        InstallService.Result result = install(mavenPom());

        assertTrue(result.ok(), result.message());
        Path dest = config.resolve("plugins/lsp/maven");
        assertEquals("core", Files.readString(dest.resolve("lemminx.jar")));
        assertEquals("ext", Files.readString(dest.resolve("lib/maven.jar")));
        assertEquals(
                InstallCatalog.jvmClasspathCommand(dest, InstallCatalog.LEMMINX_MAIN_CLASS), result.installedCommand());
        assertEquals(List.of("lemminx-maven"), started, "the caller is told which tool is being installed");
        assertEquals(List.of(InstallCatalog.LEMMINX_MAVEN_ZIP_URL), web.requests());
    }

    /**
     * A per-OS binary archive: the release metadata is fetched, the asset for this platform is picked out of
     * it (not its checksum sibling, not another platform's), and the binary inside is found and made runnable.
     */
    @Test
    void aBinaryArchiveIsPickedForThisPlatformExtractedAndMadeExecutable() throws IOException {
        var spec = InstallCatalog.archiveSpec("terraform").orElseThrow();
        String mine = spec.assetByPlatform().get(InstallCatalog.currentPlatform());
        String base = "https://releases.hashicorp.com/terraform-ls/0.38.7/terraform-ls_0.38.7_";
        String asset = base + mine + ".zip";
        web.serve(
                spec.apiUrl(),
                "{\"builds\":[{\"url\":\"" + base + "plan9_mips.zip\"},{\"url\":\"" + base + mine
                        + ".sha256\"},{\"url\":\"" + asset + "\"}]}");
        web.serve(asset, TestArchives.zip("LICENSE.txt", "MPL", "terraform-ls", "the server binary"));

        InstallService.Result result =
                install(InstallCatalog.serverInstall("terraform").orElseThrow());

        assertTrue(result.ok(), result.message());
        Path binary = config.resolve("plugins/lsp/terraform/terraform-ls");
        assertEquals("the server binary", Files.readString(binary));
        assertEquals(InstallCatalog.binaryCommand(binary, " serve"), result.installedCommand());
        if (binary.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertTrue(Files.isExecutable(binary), "a zip carries no exec bit, so the installer sets it");
        }
        assertEquals(List.of(spec.apiUrl(), asset), web.requests(), "only this platform's archive is downloaded");
    }

    /** GitHub answers a release-asset URL with a redirect to its storage host; the download follows it. */
    @Test
    void aRedirectedDownloadIsFollowed() throws IOException {
        String asset = "https://github.com/eclipse/lemminx/releases/download/1.0/bundle.zip";
        String stored = "https://release-assets.githubusercontent.com/github-production-release-asset/1/bundle.zip";
        web.redirect(asset, stored);
        web.serve(stored, TestArchives.zip("server.jar", "bytes"));

        InstallService.Result result = install(List.of(zipFrom(asset)));

        assertTrue(result.ok(), result.message());
        assertEquals("bytes", Files.readString(config.resolve("plugins/lsp/probe/server.jar")));
    }

    /**
     * java-debug comes as a {@code .vsix} holding a whole VS Code extension; only the debug plugin jar is
     * wanted. A jar from an earlier install is replaced, and nothing else in the folder is touched.
     */
    @Test
    void theDebugAdapterInstallKeepsOnlyTheNewPluginJar() throws IOException {
        Path dest = Files.createDirectories(config.resolve("plugins/dap/java"));
        Files.writeString(dest.resolve(OLD_DEBUG_JAR), "old");
        Files.writeString(dest.resolve("notes.txt"), "the user's own file");
        web.serve(javaDebug().apiUrl(), "{\"files\":{\"download\":\"" + VSIX_URL + "\"}}");
        web.serve(
                VSIX_URL,
                TestArchives.zip(
                        "extension.vsixmanifest",
                        "<xml/>",
                        "extension/package.json",
                        "{}",
                        "extension/server/" + NEW_DEBUG_JAR,
                        "new",
                        "extension/server/other-library.jar",
                        "lib"));

        InstallService.Result result = install(List.of(javaDebug()));

        assertTrue(result.ok(), result.message());
        assertNull(result.installedCommand());
        assertEquals(List.of(NEW_DEBUG_JAR, "notes.txt"), names(dest));
        assertEquals("new", Files.readString(dest.resolve(NEW_DEBUG_JAR)));
    }

    @Test
    void aVsixWithoutTheDebugJarFailsAndLeavesTheInstalledJarInPlace() throws IOException {
        Path dest = Files.createDirectories(config.resolve("plugins/dap/java"));
        Files.writeString(dest.resolve(OLD_DEBUG_JAR), "old");
        web.serve(javaDebug().apiUrl(), "{\"files\":{\"download\":\"" + VSIX_URL + "\"}}");
        web.serve(VSIX_URL, TestArchives.zip("extension/package.json", "{}", "extension/server/other.jar", "lib"));

        InstallService.Result result = install(List.of(javaDebug()));

        assertFalse(result.ok());
        assertTrue(result.message().startsWith("java-debug: "), result.message());
        assertTrue(result.message().contains("plugin jar not found"), result.message());
        assertEquals(List.of(OLD_DEBUG_JAR), names(dest), "the adapter that was working still is");
    }

    @Test
    void aVsixWhoseMetadataListsNoDownloadIsReportedWithTheUrlThatWasAsked() {
        web.serve(javaDebug().apiUrl(), "{\"error\":\"extension not found\"}");

        InstallService.Result result = install(List.of(javaDebug()));

        assertFalse(result.ok());
        assertTrue(result.message().contains("no download asset found"), result.message());
        assertTrue(result.message().contains(javaDebug().apiUrl()), result.message());
        assertFalse(Files.exists(config.resolve("plugins")));
    }

    /** A {@code .vsix} step that is not jar-only installs the extension's whole tree. */
    @Test
    void aWholeVsixIsCopiedIntoItsFolder() throws IOException {
        Step whole = new Step(
                "extension", Kind.VSIX, Set.of(), List.of(), null, null, null, VSIX_URL, false, "plugins/ext", null);
        web.serve(VSIX_URL, TestArchives.zip("extension/package.json", "{}", "extension/out/main.js", "code"));

        InstallService.Result result = install(List.of(whole));

        assertTrue(result.ok(), result.message());
        assertEquals(
                List.of("extension", "extension/out", "extension/out/main.js", "extension/package.json"),
                names(config.resolve("plugins/ext")));
        assertEquals("code", Files.readString(config.resolve("plugins/ext/extension/out/main.js")));
    }

    // --- where a download may come from -------------------------------------------------------------

    @Test
    void aPlainHttpDownloadIsRefusedBeforeAnythingIsRequested() {
        InstallService.Result result = install(List.of(zipFrom("http://download.eclipse.org/tool.zip")));

        assertFalse(result.ok());
        assertTrue(result.message().contains("must be https"), result.message());
        assertEquals(List.of(), web.requests());
        assertFalse(Files.exists(config.resolve("plugins")));
    }

    @Test
    void aDownloadFromAHostTheCatalogDoesNotUseIsRefusedBeforeAnythingIsRequested() {
        InstallService.Result result = install(List.of(zipFrom("https://downloads.example.org/tool.zip")));

        assertFalse(result.ok());
        assertTrue(result.message().contains("not one the install catalog uses"), result.message());
        assertEquals(List.of(), web.requests());
        assertFalse(Files.exists(config.resolve("plugins")));
    }

    /**
     * The asset URL is picked out of fetched JSON by a pattern, and a release body is free text. Metadata
     * that names an archive on some other host must not send the download there — the archive would be
     * extracted and run.
     */
    @Test
    void releaseMetadataCannotSteerTheDownloadToAnotherHost() {
        var spec = InstallCatalog.archiveSpec("terraform").orElseThrow();
        String mine = spec.assetByPlatform().get(InstallCatalog.currentPlatform());
        String elsewhere = "https://mirror.example.net/terraform-ls_" + mine + ".zip";
        web.serve(spec.apiUrl(), "{\"body\":\"faster mirror: " + elsewhere + "\"}");
        web.serve(elsewhere, TestArchives.zip("terraform-ls", "not the real one"));

        InstallService.Result result =
                install(InstallCatalog.serverInstall("terraform").orElseThrow());

        assertFalse(result.ok());
        assertTrue(result.message().contains("not one the install catalog uses"), result.message());
        assertEquals(List.of(spec.apiUrl()), web.requests(), "the other host is never contacted");
        assertFalse(Files.exists(config.resolve("plugins")));
    }

    @Test
    void metadataWithNoArchiveForThisPlatformIsReported() {
        var spec = InstallCatalog.archiveSpec("terraform").orElseThrow();
        web.serve(spec.apiUrl(), "{\"builds\":[{\"url\":\"https://releases.hashicorp.com/x/x_plan9_mips.zip\"}]}");

        InstallService.Result result =
                install(InstallCatalog.serverInstall("terraform").orElseThrow());

        assertFalse(result.ok());
        assertTrue(result.message().startsWith("terraform: no download asset matched"), result.message());
        assertFalse(Files.exists(config.resolve("plugins")));
    }

    @Test
    void anArchiveStepWithNoRecipeIsReported() {
        Step unknown = new Step(
                "no-such-server", Kind.ARCHIVE, Set.of(), List.of(), null, null, null, null, false, "plugins/x", null);

        InstallService.Result result = install(List.of(unknown));

        assertFalse(result.ok());
        assertEquals("no-such-server: no archive recipe", result.message());
        assertEquals(List.of(), web.requests());
    }

    // --- downloads that fail --------------------------------------------------------------------------

    /** Installs a first, good version of the Maven server so a later failure has something to damage. */
    private Path installedMavenServer() throws IOException {
        web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, TestArchives.zip("lemminx.jar", "v1", "old-only.jar", "v1"));
        assertTrue(install(mavenPom()).ok());
        return config.resolve("plugins/lsp/maven");
    }

    @Test
    void aMissingDownloadIsReportedWithItsStatusAndInstallsNothing() {
        web.status(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, 404);

        InstallService.Result result = install(mavenPom());

        assertFalse(result.ok());
        assertEquals("HTTP 404 for " + InstallCatalog.LEMMINX_MAVEN_ZIP_URL, result.message());
        assertNull(result.installedCommand());
        assertFalse(Files.exists(config.resolve("plugins")), "not even an empty folder is left");
    }

    @Test
    void aFailedDownloadLeavesTheInstalledVersionAsItWas() throws IOException {
        Path dest = installedMavenServer();

        web.status(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, 503);
        InstallService.Result result = install(mavenPom());

        assertFalse(result.ok());
        assertTrue(result.message().contains("HTTP 503"), result.message());
        assertEquals(List.of("lemminx.jar", "old-only.jar"), names(dest));
        assertEquals("v1", Files.readString(dest.resolve("lemminx.jar")));
    }

    @Test
    void aTransferCutOffPartWayInstallsNothing() throws IOException {
        Path dest = installedMavenServer();
        byte[] whole = TestArchives.zip("lemminx.jar", "v2".repeat(2000), "new-only.jar", "v2");
        web.cutOff(
                InstallCatalog.LEMMINX_MAVEN_ZIP_URL, java.util.Arrays.copyOf(whole, whole.length / 2), whole.length);

        InstallService.Result result = install(mavenPom());

        assertFalse(result.ok());
        assertFalse(result.message().isBlank(), "the failure says something");
        assertEquals(List.of("lemminx.jar", "old-only.jar"), names(dest), "the half download was never unpacked");
        assertEquals("v1", Files.readString(dest.resolve("lemminx.jar")));
    }

    /** A response larger than the cap is abandoned, not buffered: nothing of it reaches the disk. */
    @Test
    void anOversizeDownloadIsAbandonedAtTheCap() throws IOException {
        Path dest = installedMavenServer();
        byte[] big = new byte[(int) DOWNLOAD_CAP + 1];
        java.util.Arrays.fill(big, (byte) 'x');
        web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, big);

        InstallService.Result result = install(mavenPom());

        assertFalse(result.ok());
        assertTrue(result.message().contains("exceeds " + DOWNLOAD_CAP + " bytes"), result.message());
        assertEquals(List.of("lemminx.jar", "old-only.jar"), names(dest));
    }

    @Test
    void aDownloadExactlyAtTheCapIsAccepted() throws IOException {
        // Stored (incompressible-looking) padding brings the archive to exactly the cap.
        byte[] small = TestArchives.zip("server.jar", "x");
        byte[] padded = java.util.Arrays.copyOf(small, (int) DOWNLOAD_CAP); // trailing zeros after the zip's end
        web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, padded);

        InstallService.Result result = install(mavenPom());

        assertTrue(result.ok(), result.message());
        assertEquals("x", Files.readString(config.resolve("plugins/lsp/maven/server.jar")));
    }

    // --- archives that try to write somewhere else ----------------------------------------------------

    @Test
    void aZipEntryThatClimbsOutOfTheFolderIsRefusedAndWritesNothingOutside() throws IOException {
        web.serve(
                InstallCatalog.LEMMINX_MAVEN_ZIP_URL,
                TestArchives.zip("lemminx.jar", "ok", "../../../escaped.txt", "pwned", "after.jar", "never"));

        InstallService.Result result = install(mavenPom());

        assertFalse(result.ok());
        assertTrue(result.message().contains("unsafe zip entry name"), result.message());
        assertFalse(Files.exists(config.resolve("escaped.txt")));
        assertFalse(Files.exists(config.resolve("plugins/escaped.txt")));
        assertFalse(Files.exists(sandbox.resolve("escaped.txt")));
        assertEquals(
                List.of("config"),
                names(sandbox).stream().filter(n -> !n.contains("/")).toList());
    }

    @Test
    void aZipEntryWithAnAbsoluteNameIsRefused() throws IOException {
        Path target = sandbox.resolve("absolute-target.txt");
        String absolute = target.toString().replace('\\', '/');
        web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, TestArchives.zip(absolute, "pwned"));

        InstallService.Result result = install(mavenPom());

        assertFalse(result.ok());
        assertTrue(result.message().contains("unsafe zip entry name"), result.message());
        assertFalse(Files.exists(target));
    }

    @Test
    void aHostileVsixCannotWriteOutsideItsFolder() throws IOException {
        Path dest = Files.createDirectories(config.resolve("plugins/dap/java"));
        Files.writeString(dest.resolve(OLD_DEBUG_JAR), "old");
        web.serve(javaDebug().apiUrl(), "\"" + VSIX_URL + "\"");
        web.serve(
                VSIX_URL,
                TestArchives.zip(
                        "extension/server/" + NEW_DEBUG_JAR, "new", "extension/../../../../escaped.jar", "pwned"));

        InstallService.Result result = install(List.of(javaDebug()));

        assertFalse(result.ok());
        assertEquals(List.of(OLD_DEBUG_JAR), names(dest), "a refused archive installs none of its files");
        try (Stream<Path> walk = Files.walk(sandbox)) {
            assertEquals(
                    List.of(),
                    walk.filter(p -> p.getFileName().toString().equals("escaped.jar"))
                            .toList());
        }
    }

    // --- reinstalling ---------------------------------------------------------------------------------

    @Test
    void reinstallingReplacesThePreviousVersionCompletely() throws IOException {
        Path dest = installedMavenServer();
        Files.writeString(dest.resolve("workspace-cache.bin"), "left by the running server");

        web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, TestArchives.zip("lemminx.jar", "v2", "new-only.jar", "v2"));
        InstallService.Result result = install(mavenPom());

        assertTrue(result.ok(), result.message());
        assertEquals(List.of("lemminx.jar", "new-only.jar"), names(dest), "nothing of the old version is mixed in");
        assertEquals("v2", Files.readString(dest.resolve("lemminx.jar")));
    }

    /**
     * Replacing an install deletes the old tree. A link inside it that points somewhere else — the user's
     * own, or one an earlier archive brought — is removed as a link; what it points at is not the installer's.
     */
    @Test
    void replacingAnInstallDoesNotFollowLinksOutOfIt() throws IOException {
        Path dest = installedMavenServer();
        Path outside = Files.createDirectories(sandbox.resolve("users-documents/nested"));
        Files.writeString(outside.resolve("thesis.txt"), "years of work");
        Files.writeString(sandbox.resolve("users-documents/notes.txt"), "notes");
        try {
            Files.createSymbolicLink(dest.resolve("shared"), sandbox.resolve("users-documents"));
            Files.createSymbolicLink(dest.resolve("one-file"), outside.resolve("thesis.txt"));
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are not available here: " + e);
        }

        web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, TestArchives.zip("lemminx.jar", "v2"));
        InstallService.Result result = install(mavenPom());

        assertTrue(result.ok(), result.message());
        assertEquals(List.of("lemminx.jar"), names(dest));
        assertEquals("years of work", Files.readString(outside.resolve("thesis.txt")));
        assertEquals("notes", Files.readString(sandbox.resolve("users-documents/notes.txt")));
    }

    // --- command steps and step sequencing ------------------------------------------------------------

    @Test
    void aToolchainCommandThatSucceedsIsAnInstall() {
        InstallService.Result result = install(List.of(command("probe", List.of(java(), "-version"))));

        assertTrue(result.ok(), result.message());
        assertNull(result.installedCommand());
        assertEquals("", result.message());
    }

    @Test
    void aToolchainCommandThatFailsReportsItsOutputUnderTheToolsName() {
        InstallService.Result result = install(List.of(command("probe", List.of(java(), "--editora-no-such-option"))));

        assertFalse(result.ok());
        assertTrue(result.message().startsWith("probe: "), result.message());
        assertTrue(result.message().length() > "probe: ".length(), "the tool's own complaint is passed on");
    }

    @Test
    void aCommandThatCannotBeStartedIsAFailureNotAnException() {
        InstallService.Result result = install(
                List.of(command("probe", List.of(sandbox.resolve("no-such-tool").toString()))));

        assertFalse(result.ok());
        assertTrue(result.message().startsWith("probe: "), result.message());
    }

    @Test
    void theFirstFailingStepStopsTheRun() {
        web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, TestArchives.zip("lemminx.jar", "core"));
        List<Step> steps = new ArrayList<>();
        steps.add(command("first", List.of(java(), "--editora-no-such-option")));
        steps.addAll(mavenPom());

        InstallService.Result result = install(steps);

        assertFalse(result.ok());
        assertEquals(List.of("first"), started);
        assertEquals(List.of(), web.requests(), "later steps are not attempted");
        assertFalse(Files.exists(config.resolve("plugins")));
    }

    @Test
    void theStepsRunInOrderAndTheResolvedCommandSurvivesLaterSteps() {
        web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, TestArchives.zip("lemminx.jar", "core"));
        List<Step> steps = new ArrayList<>(mavenPom());
        steps.add(command("after", List.of(java(), "-version")));

        InstallService.Result result = install(steps);

        assertTrue(result.ok(), result.message());
        assertEquals(List.of("lemminx-maven", "after"), started);
        assertEquals(
                InstallCatalog.jvmClasspathCommand(
                        config.resolve("plugins/lsp/maven"), InstallCatalog.LEMMINX_MAIN_CLASS),
                result.installedCommand());
    }

    @Test
    void theChromeStepRunsItsCommand() {
        Step chrome = new Step(
                "chrome-headless-shell",
                Kind.PUPPETEER_BROWSER,
                Set.of(),
                List.of(java(), "--editora-no-such-option"),
                null,
                null,
                null,
                null,
                false,
                "",
                null);

        InstallService.Result result = install(List.of(chrome));

        assertFalse(result.ok());
        assertTrue(result.message().startsWith("chrome-headless-shell: "), result.message());
    }
}
