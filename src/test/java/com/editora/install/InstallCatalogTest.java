package com.editora.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.install.InstallCatalog.ArchiveSpec;
import com.editora.install.InstallCatalog.Kind;
import com.editora.install.InstallCatalog.Lang;
import com.editora.install.InstallCatalog.Platform;
import com.editora.install.InstallCatalog.Prereq;
import com.editora.install.InstallCatalog.Step;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallCatalogTest {

    @Test
    void openVsxUrlIsBuiltFromNamespaceAndName() {
        assertEquals(
                "https://open-vsx.org/api/vscjava/vscode-java-debug/latest",
                InstallCatalog.openVsxLatestUrl("vscjava", "vscode-java-debug"));
    }

    @Test
    void npmGlobalArgvPrependsInstallFlags() {
        assertEquals(
                List.of("npm", "install", "-g", "typescript-language-server", "typescript"),
                InstallCatalog.npmInstallGlobalArgv(List.of("typescript-language-server", "typescript")));
    }

    @Test
    void pipTargetArgvUsesUpgradeAndTarget() {
        assertEquals(
                List.of("python3", "-m", "pip", "install", "--upgrade", "--target", str("/tmp/dest"), "debugpy"),
                InstallCatalog.pipInstallTargetArgv("python3", Path.of("/tmp/dest"), "debugpy"));
    }

    @Test
    void tarArgvExtractsGzipIntoDest() {
        assertEquals(
                List.of("tar", "-xzf", str("/tmp/x.tar.gz"), "-C", str("/tmp/out")),
                InstallCatalog.tarExtractArgv(Path.of("/tmp/x.tar.gz"), Path.of("/tmp/out")));
    }

    @Test
    void firstMatchPullsTheVsixUrlFromJson() {
        String json = "{\"files\":{\"download\":\"https://open-vsx.org/api/x/y/1.0.0/file/x.y-1.0.0.vsix\"}}";
        assertEquals(
                "https://open-vsx.org/api/x/y/1.0.0/file/x.y-1.0.0.vsix",
                InstallCatalog.firstMatch(json, "https://[^\"\\s]+\\.vsix"));
        assertNull(InstallCatalog.firstMatch("no url here", "https://[^\"\\s]+\\.vsix"));
        assertNull(InstallCatalog.firstMatch(null, ".*"));
    }

    @Test
    void firstMatchPullsTheJsDebugTarballUrl() {
        String json =
                "...\"browser_download_url\":\"https://github.com/microsoft/vscode-js-debug/releases/download/v1.99.0/js-debug-dap-v1.99.0.tar.gz\"...";
        assertEquals(
                "https://github.com/microsoft/vscode-js-debug/releases/download/v1.99.0/js-debug-dap-v1.99.0.tar.gz",
                InstallCatalog.firstMatch(json, "https://[^\"\\s]+/js-debug-dap-v[^\"\\s]+\\.tar\\.gz"));
    }

    /**
     * TypeScript 7 ships no {@code tsserver}, so an unpinned {@code typescript} gave a language server that could
     * not start (Node review B1). Both servers that run a tsserver install the pinned major.
     */
    @Test
    void typescriptServersInstallTheTsserverMajor() {
        assertEquals("typescript@6", InstallCatalog.TYPESCRIPT_PACKAGE);
        assertEquals(
                List.of("typescript-language-server", "typescript@6"),
                InstallCatalog.steps(Lang.JAVASCRIPT).get(0).npmPackages());
        assertEquals(
                List.of("@astrojs/language-server", "typescript@6"),
                InstallCatalog.serverInstall("astro").orElseThrow().get(0).npmPackages());
    }

    @Test
    void bufferLanguagesMapToTheRightLang() {
        assertEquals(Lang.JAVA, InstallCatalog.forBufferLanguage("java").orElseThrow());
        assertEquals(Lang.PYTHON, InstallCatalog.forBufferLanguage("python").orElseThrow());
        assertEquals(
                Lang.JAVASCRIPT, InstallCatalog.forBufferLanguage("typescript").orElseThrow());
        assertEquals(
                Lang.JAVASCRIPT,
                InstallCatalog.forBufferLanguage("typescriptreact").orElseThrow());
        assertEquals(
                Lang.JAVASCRIPT, InstallCatalog.forBufferLanguage("javascript").orElseThrow());
        assertEquals(Lang.MERMAID, InstallCatalog.forBufferLanguage("mmd").orElseThrow());
        assertEquals(Lang.MERMAID, InstallCatalog.forBufferLanguage("mermaid").orElseThrow());
        assertTrue(InstallCatalog.forBufferLanguage("rust").isEmpty());
        assertTrue(InstallCatalog.forBufferLanguage(null).isEmpty());
    }

    /**
     * The jdtls tarball must stay a <b>pinned milestone</b>. It used to be the {@code snapshots/} nightly, which
     * hands two users installing a week apart different servers and ships an untested overnight build straight
     * to them — and Editora's jdtls behaviour is measured against a specific build (see {@code JDTLS_VERSION}).
     */
    @Test
    void jdtlsTarballIsAPinnedMilestoneNotTheNightly() {
        String url = InstallCatalog.JDTLS_TARBALL_URL;
        assertTrue(url.startsWith("https://download.eclipse.org/jdtls/milestones/"), url);
        assertFalse(url.contains("/snapshots/"), url);
        assertFalse(url.contains("latest"), url);
        assertTrue(
                url.endsWith("/jdt-language-server-" + InstallCatalog.JDTLS_VERSION + "-" + InstallCatalog.JDTLS_BUILD
                        + ".tar.gz"),
                url);
    }

    /**
     * The shell installer and the in-app installer must pin the <b>same</b> jdtls build. The version lives in two
     * files, and nothing but this test makes them agree — so a one-sided bump would silently hand script users and
     * in-app users different servers, which matters because Editora's documented jdtls behaviour is measured against
     * one build (see {@code InstallCatalog.JDTLS_VERSION}).
     */
    @Test
    void installScriptPinsTheSameJdtlsBuildAsTheCatalog() throws IOException {
        Path script = Path.of("scripts/install-jdtls.sh");
        assertTrue(Files.isRegularFile(script), "missing " + script.toAbsolutePath());
        String text = Files.readString(script);

        String bumpBoth = " — scripts/install-jdtls.sh and com.editora.install.InstallCatalog pin the jdtls build"
                + " separately and must be bumped together (and the behaviours in JDTLS_VERSION's javadoc re-checked).";
        assertEquals(
                InstallCatalog.JDTLS_VERSION, shellDefault(text, "JDTLS_VERSION"), "jdtls version differs" + bumpBoth);
        assertEquals(
                InstallCatalog.JDTLS_BUILD, shellDefault(text, "JDTLS_BUILD"), "jdtls build stamp differs" + bumpBoth);

        // The two variables only count if the download URL is still built from them.
        assertTrue(
                text.contains("$JDTLS_VERSION") && text.contains("$JDTLS_BUILD"),
                "scripts/install-jdtls.sh no longer builds its download URL from JDTLS_VERSION/JDTLS_BUILD,"
                        + bumpBoth);
    }

    @Test
    void javaStepsAreJdtlsThenJavaDebug() {
        List<Step> steps = InstallCatalog.steps(Lang.JAVA);
        assertEquals(2, steps.size());
        Step jdtls = steps.get(0);
        assertEquals("jdtls", jdtls.id());
        assertEquals(Kind.TARBALL, jdtls.kind());
        assertEquals(InstallCatalog.JDTLS_TARBALL_URL, jdtls.directUrl());
        assertEquals("plugins/lsp/java", jdtls.destSubpath());
        assertTrue(jdtls.prereqs().contains(Prereq.TAR));

        Step javaDebug = steps.get(1);
        assertEquals("java-debug", javaDebug.id());
        assertEquals(Kind.VSIX, javaDebug.kind());
        assertTrue(javaDebug.extractJarOnly());
        assertEquals("plugins/dap/java", javaDebug.destSubpath());
        assertNull(javaDebug.directUrl());
        assertEquals(InstallCatalog.openVsxLatestUrl("vscjava", "vscode-java-debug"), javaDebug.apiUrl());
    }

    @Test
    void pythonStepsAreNpmPyrightAndPipDebugpy() {
        List<Step> steps = InstallCatalog.steps(Lang.PYTHON);
        assertEquals(List.of("pyright", "debugpy"), steps.stream().map(Step::id).toList());
        assertEquals(Kind.NPM_GLOBAL, steps.get(0).kind());
        assertTrue(steps.get(0).prereqs().contains(Prereq.NPM));
        assertEquals(Kind.PIP_TARGET, steps.get(1).kind());
        assertEquals("debugpy", steps.get(1).pipPackage());
        assertEquals("plugins/dap/python", steps.get(1).destSubpath());
        assertTrue(steps.get(1).prereqs().contains(Prereq.PYTHON));
    }

    @Test
    void javascriptStepsAreTsServerAndJsDebugTarball() {
        List<Step> steps = InstallCatalog.steps(Lang.JAVASCRIPT);
        assertEquals(
                List.of("typescript-language-server", "js-debug"),
                steps.stream().map(Step::id).toList());
        assertEquals(Kind.NPM_GLOBAL, steps.get(0).kind());
        Step jsDebug = steps.get(1);
        assertEquals(Kind.TARBALL, jsDebug.kind());
        assertEquals(InstallCatalog.JS_DEBUG_RELEASES_API, jsDebug.apiUrl());
        // pinned to the tested release, not "latest" (review D16)
        assertTrue(jsDebug.apiUrl().endsWith("/releases/tags/v1.117.0"), jsDebug.apiUrl());
        assertEquals("dapDebugServer.js", jsDebug.verifyEntry());
        assertNull(jsDebug.directUrl());
    }

    @Test
    void mermaidStepsAreMmdcThenPuppeteerChrome() {
        List<Step> steps = InstallCatalog.steps(Lang.MERMAID);
        assertEquals(
                List.of("mmdc", "chrome-headless-shell"),
                steps.stream().map(Step::id).toList());
        assertEquals(Kind.NPM_GLOBAL, steps.get(0).kind());
        assertEquals(Kind.PUPPETEER_BROWSER, steps.get(1).kind());
        assertEquals(
                List.of("npx", "-y", "puppeteer", "browsers", "install", "chrome-headless-shell"),
                steps.get(1).npmPackages());
    }

    @Test
    void npmServersAreInstallableViaTheirPackages() {
        // json/html/css all ship in one npm package.
        for (String id : new String[] {"json", "html", "css"}) {
            List<Step> steps = InstallCatalog.serverInstall(id).orElseThrow();
            assertEquals(1, steps.size());
            assertEquals(Kind.NPM_GLOBAL, steps.get(0).kind());
            assertEquals(List.of("vscode-langservers-extracted"), steps.get(0).npmPackages());
            assertTrue(steps.get(0).prereqs().contains(Prereq.NPM));
        }
        // the Bash server is pinned (Bash review A6), under its bare package name as the step id
        Step bash = InstallCatalog.serverInstall("bash").orElseThrow().get(0);
        assertEquals(List.of("bash-language-server@" + InstallCatalog.BASH_LS_VERSION), bash.npmPackages());
        assertEquals("bash-language-server", bash.id());
        assertEquals(List.of("bash"), InstallCatalog.serversOfStep(bash.id()));
        assertEquals(
                List.of("@taplo/cli"),
                InstallCatalog.serverInstall("toml").orElseThrow().get(0).npmPackages());
    }

    @Test
    void platformKeyBucketsOsAndArch() {
        assertEquals(Platform.MAC_ARM, InstallCatalog.platformKey("Mac OS X", "aarch64"));
        assertEquals(Platform.MAC_X64, InstallCatalog.platformKey("Mac OS X", "x86_64"));
        assertEquals(Platform.LINUX_X64, InstallCatalog.platformKey("Linux", "amd64"));
        assertEquals(Platform.LINUX_ARM, InstallCatalog.platformKey("Linux", "aarch64"));
        assertEquals(Platform.WIN_X64, InstallCatalog.platformKey("Windows 11", "amd64"));
        assertEquals(Platform.WIN_X64, InstallCatalog.platformKey("Windows 11", "aarch64")); // win-arm → x64
    }

    /**
     * XML review A2/D10/A14/A15: lemminx is vscode-xml's pinned release, one zip per platform with GitHub's SHA-256
     * of the zip and the release's own {@code .sha256} of the program inside, named exactly; a zip needs no tar.
     */
    @Test
    void lemminxIsAPinnedReleaseWithBothHashesForEveryPlatform() {
        Step step = InstallCatalog.serverInstall("xml").orElseThrow().get(0);
        assertEquals(Kind.PINNED_TOOL, step.kind());
        assertEquals(InstallCatalog.LEMMINX_STEP, step.id());
        assertEquals("plugins/lsp/xml", step.destSubpath());
        assertTrue(step.prereqs().isEmpty(), "every asset is a zip, unpacked in-process: no tar (A15)");
        var tool = InstallCatalog.pinnedTool(InstallCatalog.LEMMINX_STEP).orElseThrow();
        assertEquals(InstallCatalog.LEMMINX_VERSION, tool.version());
        assertEquals("lemminx", tool.binaryName());
        java.util.Set<String> hashes = new java.util.HashSet<>();
        for (Platform p : Platform.values()) {
            var asset = tool.assetFor(p);
            assertNotNull(asset, "no lemminx for " + p);
            assertTrue(
                    asset.url()
                            .startsWith("https://github.com/redhat-developer/vscode-xml/releases/download/"
                                    + InstallCatalog.LEMMINX_VERSION + "/lemminx-"),
                    asset.url());
            assertTrue(asset.url().endsWith(".zip"), asset.url());
            assertEquals(InstallCatalog.Packing.ZIP, asset.packing());
            assertTrue(asset.sha256().matches("[0-9a-f]{64}"), p + " zip hash");
            assertTrue(asset.memberSha256().matches("[0-9a-f]{64}"), p + " program hash");
            assertTrue(asset.member().startsWith("lemminx-"), asset.member());
            assertTrue(InstallCatalog.isTrustedDownloadUrl(asset.url()), asset.url());
            assertTrue(hashes.add(asset.sha256()) && hashes.add(asset.memberSha256()), "hashes are distinct");
        }
        assertEquals("lemminx-win32.exe", tool.assetFor(Platform.WIN_X64).member());
        assertEquals("lemminx-linux-x86_64", tool.assetFor(Platform.LINUX_X64).member());
    }

    @Test
    void archiveSpecsCoverEveryPlatformAndTheRightBinary() {
        for (String id : new String[] {"clangd", "lua", "terraform"}) {
            // It's an ARCHIVE step with a recipe.
            assertEquals(
                    Kind.ARCHIVE,
                    InstallCatalog.serverInstall(id).orElseThrow().get(0).kind());
            ArchiveSpec spec = InstallCatalog.archiveSpec(id).orElseThrow();
            for (Platform p : Platform.values()) {
                assertNotNull(spec.assetByPlatform().get(p), id + " missing asset for " + p);
            }
        }
        assertEquals(
                "clangd", InstallCatalog.archiveSpec("clangd").orElseThrow().binaryName());
        assertEquals(
                " serve", InstallCatalog.archiveSpec("terraform").orElseThrow().commandSuffix());
        assertEquals(
                "darwin_arm64",
                InstallCatalog.archiveSpec("terraform")
                        .orElseThrow()
                        .assetByPlatform()
                        .get(Platform.MAC_ARM));
        assertTrue(InstallCatalog.archiveSpec("xml").isEmpty(), "lemminx is pinned, not releases/latest (A2)");
        assertTrue(InstallCatalog.archiveSpec("unknown").isEmpty());
        assertTrue(InstallCatalog.archiveSpec("kotlin").isEmpty(), "fwcd's server is no longer installed");
    }

    @Test
    void theKotlinServerIsJetBrainsPinnedForEveryPlatformAndSaysItsSize() {
        Step step = InstallCatalog.serverInstall("kotlin").orElseThrow().get(0);
        assertEquals(Kind.PINNED_SERVER, step.kind());
        assertEquals(InstallCatalog.KOTLIN_LSP_STEP, step.id());
        assertEquals(java.util.Set.of(InstallCatalog.Prereq.TAR), step.prereqs());
        assertEquals("plugins/lsp/kotlin", step.destSubpath());
        InstallCatalog.PinnedServer server =
                InstallCatalog.pinnedServer(step.id()).orElseThrow();
        assertEquals("intellij-server", server.launcher());
        assertEquals(" --stdio", server.arguments());
        for (Platform p : Platform.values()) {
            InstallCatalog.PinnedAsset asset = server.assetFor(p);
            assertNotNull(asset, "no asset for " + p);
            assertTrue(InstallCatalog.isTrustedDownloadUrl(asset.url()), asset.url());
            assertTrue(asset.url().contains("/" + InstallCatalog.KOTLIN_LSP_VERSION + "/"), asset.url());
            assertTrue(asset.sha256().matches("[0-9a-f]{64}"), asset.sha256());
        }
        assertTrue(server.assetFor(Platform.MAC_ARM).url().endsWith("-aarch64.sit"));
        assertTrue(server.assetFor(Platform.WIN_X64).url().endsWith(".win.zip"));
        assertTrue(InstallCatalog.isTrustedDownloadUrl("https://download-cdn.jetbrains.com/x.tar.gz"), "its CDN");
        assertArrayEquals(new int[] {370, 1200}, InstallCatalog.largeDownload("kotlin"));
        assertNull(InstallCatalog.largeDownload("lua"), "a small download is not asked about");
        assertTrue(InstallCatalog.installableServerIds().contains("kotlin"));
    }

    @Test
    void typstCliIsABinaryArchiveForEveryPlatform() {
        var steps = InstallCatalog.typstCliSteps();
        assertEquals(1, steps.size());
        assertEquals(Kind.ARCHIVE, steps.get(0).kind());
        assertEquals("typst-cli", steps.get(0).id());
        assertEquals("plugins/typst", steps.get(0).destSubpath());
        ArchiveSpec spec = InstallCatalog.archiveSpec("typst-cli").orElseThrow();
        assertEquals("typst", spec.binaryName());
        assertEquals("", spec.commandSuffix()); // the bare typst binary (renderer adds "compile …")
        for (Platform p : Platform.values()) {
            assertNotNull(spec.assetByPlatform().get(p), "typst-cli missing asset for " + p);
        }
        assertEquals("typst-aarch64-apple-darwin", spec.assetByPlatform().get(Platform.MAC_ARM));
    }

    @Test
    void tarExtractArgvAutoDetectsCompression() {
        assertEquals(
                java.util.List.of("tar", "-xf", str("/tmp/x.tar.xz"), "-C", str("/tmp/out")),
                InstallCatalog.tarExtractArgvAuto(Path.of("/tmp/x.tar.xz"), Path.of("/tmp/out")));
    }

    @Test
    void installableServerIdsAreAllServiceable() {
        for (String id : InstallCatalog.installableServerIds()) {
            assertTrue(InstallCatalog.serverInstall(id).isPresent(), id + " should have install steps");
        }
        // The Java/Python/JS bundles are handled by the Lang path, not the server-id path.
        assertTrue(InstallCatalog.serverInstall("java").isEmpty());
        assertTrue(InstallCatalog.serverInstall("typescript").isEmpty());
        assertTrue(InstallCatalog.serverInstall(null).isEmpty());
        assertEquals(
                List.of("@astrojs/language-server", "typescript@6"),
                InstallCatalog.serverInstall("astro").orElseThrow().get(0).npmPackages());
    }

    @Test
    void toolchainServersRunTheirPackageManager() {
        Step gopls = InstallCatalog.serverInstall("go").orElseThrow().get(0);
        assertEquals(Kind.TOOL_COMMAND, gopls.kind());
        assertEquals(List.of("go", "install", "golang.org/x/tools/gopls@latest"), gopls.npmPackages());
        assertTrue(gopls.prereqs().contains(Prereq.GO));

        assertEquals(
                List.of("gem", "install", "ruby-lsp"),
                InstallCatalog.serverInstall("ruby").orElseThrow().get(0).npmPackages());
        assertTrue(InstallCatalog.serverInstall("ruby")
                .orElseThrow()
                .get(0)
                .prereqs()
                .contains(Prereq.RUBY));
        assertEquals(
                List.of("rustup", "component", "add", "rust-analyzer"),
                InstallCatalog.serverInstall("rust").orElseThrow().get(0).npmPackages());
        assertEquals(
                List.of("dotnet", "tool", "install", "--global", "csharp-ls"),
                InstallCatalog.serverInstall("csharp").orElseThrow().get(0).npmPackages());
        assertEquals(
                List.of("composer", "global", "require", "phpactor/phpactor"),
                InstallCatalog.serverInstall("php").orElseThrow().get(0).npmPackages());
    }

    @Test
    void mermaidFirstStepIsMmdcNpm() {
        Step mmdc = InstallCatalog.steps(Lang.MERMAID).get(0);
        assertEquals("mmdc", mmdc.id());
        assertEquals(Kind.NPM_GLOBAL, mmdc.kind());
        assertEquals(List.of("@mermaid-js/mermaid-cli"), mmdc.npmPackages());
        assertFalse(mmdc.extractJarOnly());
    }

    @Test
    void mavenPomServerIsAJvmZipStepNeedingJava() {
        assertTrue(InstallCatalog.installableServerIds().contains("maven-pom"));
        Step s = InstallCatalog.serverInstall("maven-pom").orElseThrow().get(0);
        assertEquals(Kind.JVM_LSP_ZIP, s.kind());
        assertEquals(InstallCatalog.LEMMINX_MAVEN_STEP, s.id());
        assertEquals(java.util.Set.of(Prereq.JAVA), s.prereqs());
        assertEquals(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, s.directUrl());
        assertEquals("plugins/lsp/maven", s.destSubpath());
        // The bundle is served from Eclipse's own repo (not Maven Central / GitHub) and is a self-contained zip.
        assertTrue(s.directUrl().startsWith("https://repo.eclipse.org/"));
        assertTrue(s.directUrl().endsWith("-zip-with-dependencies.zip"));
    }

    @Test
    void clojureLspIsAPinnedNativeBinaryForEveryDesktopPlatform() {
        assertTrue(InstallCatalog.installableServerIds().contains("clojure"));
        Step s = InstallCatalog.serverInstall("clojure").orElseThrow().get(0);
        assertEquals(Kind.PINNED_TOOL, s.kind());
        assertEquals(java.util.Set.of(), s.prereqs(), "a zip, unpacked in-process: nothing to have installed");
        assertEquals("plugins/lsp/clojure", s.destSubpath());
        InstallCatalog.PinnedTool tool = InstallCatalog.pinnedTool(s.id()).orElseThrow();
        assertEquals(InstallCatalog.CLOJURE_LSP_VERSION, tool.version());
        assertEquals("clojure-lsp", tool.binaryName());
        assertFalse(InstallCatalog.PINNED_TOOL_IDS.contains(s.id()), "a language server, not a shell tool");
        for (Platform p : Platform.values()) {
            InstallCatalog.PinnedAsset asset = tool.assetFor(p);
            assertNotNull(asset, p.toString());
            assertTrue(
                    asset.url()
                            .startsWith("https://github.com/clojure-lsp/clojure-lsp/releases/download/"
                                    + InstallCatalog.CLOJURE_LSP_VERSION + "/clojure-lsp-native-"),
                    asset.url());
            assertFalse(asset.url().contains("static"), "the glibc build, as clojure-lsp's own installer picks");
            assertTrue(asset.sha256().matches("[0-9a-f]{64}"), p + " is pinned by its SHA-256");
            assertEquals(InstallCatalog.Packing.ZIP, asset.packing());
            assertTrue(InstallCatalog.isTrustedDownloadUrl(asset.url()));
        }
    }

    @Test
    void groovyServerIsAPinnedJarNeedingJava() {
        assertTrue(InstallCatalog.installableServerIds().contains("groovy"));
        Step s = InstallCatalog.serverInstall("groovy").orElseThrow().get(0);
        assertEquals(Kind.JVM_LSP_JAR, s.kind());
        assertEquals(java.util.Set.of(Prereq.JAVA), s.prereqs());
        assertEquals("plugins/lsp/groovy", s.destSubpath());
        InstallCatalog.JvmLspJar jar = InstallCatalog.jvmLspJar(s.id()).orElseThrow();
        assertEquals(jar.url(), s.directUrl());
        assertTrue(jar.url().startsWith("https://github.com/albertocavalcante/gvy/releases/download/v"));
        assertTrue(jar.sha256().matches("[0-9a-f]{64}"), "pinned by its SHA-256");
        assertEquals("gls-" + InstallCatalog.GLS_VERSION + ".jar", jar.fileName());
    }

    @Test
    void metalsIsResolvedByAPinnedCoursierAndRunOnJava() {
        assertTrue(InstallCatalog.installableServerIds().contains("scala"));
        Step s = InstallCatalog.serverInstall("scala").orElseThrow().get(0);
        assertEquals(Kind.COURSIER_LSP, s.kind());
        assertEquals(java.util.Set.of(Prereq.JAVA), s.prereqs());
        assertEquals("plugins/lsp/scala", s.destSubpath());
        InstallCatalog.CoursierLsp metals = InstallCatalog.coursierLsp(s.id()).orElseThrow();
        assertEquals("org.scalameta:metals_2.13:" + InstallCatalog.METALS_VERSION, metals.artifact());
        assertEquals("scala.meta.metals.Main", metals.mainClass());
        for (Platform p : Platform.values()) {
            InstallCatalog.PinnedAsset asset = InstallCatalog.COURSIER_TOOL.assetFor(p);
            assertNotNull(asset, "coursier launcher for " + p);
            assertTrue(asset.url()
                    .startsWith("https://github.com/coursier/coursier/releases/download/v"
                            + InstallCatalog.COURSIER_VERSION + "/cs-"));
            assertTrue(asset.sha256().matches("[0-9a-f]{64}"), "pinned by its SHA-256");
            assertEquals(
                    p == Platform.WIN_X64 ? InstallCatalog.Packing.ZIP : InstallCatalog.Packing.GZ, asset.packing());
        }
        assertEquals(
                List.of("/c/cs", "fetch", "org.scalameta:metals_2.13:" + InstallCatalog.METALS_VERSION),
                InstallCatalog.coursierFetchArgv("/c/cs", metals.artifact()));
    }

    @Test
    void metalsRunsWithItsJvmOptionsOverItsLibFolder() {
        java.nio.file.Path lib = java.nio.file.Path.of("/home/a user/.editora/plugins/lsp/scala/lib");
        String command = InstallCatalog.coursierLspCommand(lib, InstallCatalog.METALS);
        for (List<String> argv : tokenizedEverywhere(command)) {
            assertEquals(
                    List.of(
                            "java",
                            "-Xss4m",
                            "-Xms100m",
                            "-XX:+UseG1GC",
                            "-XX:+UseStringDeduplication",
                            "-cp",
                            lib + java.io.File.separator + "*",
                            "scala.meta.metals.Main"),
                    argv);
        }
    }

    @Test
    void aServerJarRunsWithJavaJarAndSurvivesASpaceInItsPath() {
        java.nio.file.Path jar = java.nio.file.Path.of("/home/a user/.editora/plugins/lsp/groovy/gls.jar");
        String command = InstallCatalog.jvmJarCommand(jar);
        assertTrue(command.startsWith("java -jar "));
        for (List<String> argv : tokenizedEverywhere(command)) {
            assertEquals(List.of("java", "-jar", jar.toString()), argv);
        }
    }

    // --- the stored command is a command LINE: a path with a space must come back as one argument ---

    /** Every tokenizer that reads a stored server/tool command back into argv. */
    private static List<List<String>> tokenizedEverywhere(String command) {
        return List.of(
                com.editora.lsp.LspServerRegistry.tokenize(command),
                com.editora.dap.DapServerRegistry.tokenize(command),
                com.editora.run.ProgramArgs.tokenize(command));
    }

    /**
     * The installer stored the extracted binary's path bare. With a config dir containing a space
     * ({@code C:\Users\Jane Doe\.editora}) the command split at the space, so the server it had just
     * installed was reported missing.
     */
    @Test
    void aBinaryUnderAConfigDirWithASpaceIsStoredSoItReadsBackAsOneArgument() {
        String windows = "C:\\Users\\Jane Doe\\.editora\\plugins\\lsp\\terraform\\terraform-ls.exe";
        String stored = InstallCatalog.quoteCommandPath(windows) + " serve";

        for (List<String> argv : tokenizedEverywhere(stored)) {
            assertEquals(List.of(windows, "serve"), argv, "backslashes are literal; the space stays inside");
        }

        Path unix = Path.of("/Users/John Smith/.editora/plugins/lsp/typst/tinymist");
        for (List<String> argv : tokenizedEverywhere(InstallCatalog.binaryCommand(unix, " lsp"))) {
            assertEquals(List.of(unix.toString(), "lsp"), argv);
        }
        // What the old code stored: the very same path, unquoted, is two arguments and names no file.
        String raw = unix.toString(); // rendered with the platform's separator
        int space = raw.indexOf(' ');
        assertEquals(
                List.of(raw.substring(0, space), raw.substring(space + 1), "lsp"),
                com.editora.lsp.LspServerRegistry.tokenize(unix + " lsp"));
    }

    @Test
    void anOrdinaryPathIsStoredExactlyAsBefore() {
        Path plain = Path.of("/home/u/.editora/plugins/lsp/clangd/bin/clangd");
        assertEquals(plain.toString(), InstallCatalog.binaryCommand(plain, ""));
        assertEquals(plain + " serve", InstallCatalog.binaryCommand(plain, " serve"));
        assertEquals(plain.toString(), InstallCatalog.binaryCommand(plain, null));
        assertEquals("", InstallCatalog.quoteCommandPath(""));
        assertEquals("", InstallCatalog.quoteCommandPath(null));
    }

    @Test
    void pathsContainingQuoteCharactersStillRoundTrip() {
        // The tokenizers do no escape processing, so a quote in the path is wrapped in the other kind — and a
        // path with both alternates styles; adjacent quoted runs join into one token.
        for (String path : List.of(
                "/home/o'brien/my tools/ls",
                "/home/u/a \"quoted\" dir/ls",
                "/home/o'brien/a \"quoted\" dir/ls",
                "/home/o'brien/ls",
                "/tab\there/ls")) {
            for (List<String> argv : tokenizedEverywhere(InstallCatalog.quoteCommandPath(path) + " --stdio")) {
                assertEquals(List.of(path, "--stdio"), argv, path);
            }
        }
    }

    @Test
    void jvmClasspathCommandQuotesTheWildcardDir() {
        Path dir = Path.of("/home/u/.editora/plugins/lsp/maven");
        String cmd = InstallCatalog.jvmClasspathCommand(dir, InstallCatalog.LEMMINX_MAIN_CLASS);
        String sep = java.io.File.separator;
        assertEquals("java -cp \"" + dir + sep + "*\" org.eclipse.lemminx.XMLServerLauncher", cmd);
        // Re-tokenizing (as LspServerRegistry does) keeps the quoted classpath as one argv element.
        assertEquals(
                List.of("java", "-cp", dir + sep + "*", "org.eclipse.lemminx.XMLServerLauncher"),
                com.editora.lsp.LspServerRegistry.tokenize(cmd));
    }

    /** {@code path} as the platform renders it — what an argv built from a {@link Path} carries. */
    private static String str(String path) {
        return Path.of(path).toString();
    }

    /** Extracts {@code <default>} from a {@code NAME="${NAME:-<default>}"} line of a shell script. */
    private static String shellDefault(String script, String var) {
        Matcher m = Pattern.compile("(?m)^" + var + "=\"\\$\\{" + var + ":-([^}\"]+)\\}\"")
                .matcher(script);
        assertTrue(
                m.find(),
                "scripts/install-jdtls.sh no longer defines " + var + " as " + var + "=\"${" + var
                        + ":-<default>}\"; update this test's extraction to match the script.");
        return m.group(1);
    }

    @Test
    void downloadsAreConfinedToTheHostsTheCatalogUses() {
        // every endpoint the catalog itself names…
        assertTrue(InstallCatalog.isTrustedDownloadUrl(InstallCatalog.JDTLS_TARBALL_URL));
        assertTrue(InstallCatalog.isTrustedDownloadUrl(InstallCatalog.LEMMINX_MAVEN_ZIP_URL));
        assertTrue(InstallCatalog.isTrustedDownloadUrl(InstallCatalog.JS_DEBUG_RELEASES_API));
        assertTrue(InstallCatalog.isTrustedDownloadUrl(InstallCatalog.openVsxLatestUrl("redhat", "java")));
        assertTrue(InstallCatalog.isTrustedDownloadUrl(
                "https://api.releases.hashicorp.com/v1/releases/terraform-ls/latest"));
        // …and the asset hosts their release metadata points at
        assertTrue(InstallCatalog.isTrustedDownloadUrl(
                "https://github.com/clangd/clangd/releases/download/1/clangd-linux-1.zip"));
        assertTrue(InstallCatalog.isTrustedDownloadUrl(
                "https://releases.hashicorp.com/terraform-ls/0.38.7/terraform-ls_0.38.7_linux_amd64.zip"));
        assertTrue(InstallCatalog.isTrustedDownloadUrl(
                "https://open-vsx.org/api/redhat/java/1.0.0/file/redhat.java-1.0.0.vsix"));
        assertTrue(InstallCatalog.isTrustedDownloadUrl("https://GitHub.com/x/y/releases/download/1/a.tar.gz"));
        // a URL pattern-matched out of a release body must not steer the download elsewhere
        assertFalse(InstallCatalog.isTrustedDownloadUrl("https://attacker.example/clangd-linux-1.zip"));
        assertFalse(InstallCatalog.isTrustedDownloadUrl("https://github.com.attacker.example/a.zip"));
        assertFalse(InstallCatalog.isTrustedDownloadUrl("https://attacker.example/github.com/a.zip"));
        assertFalse(InstallCatalog.isTrustedDownloadUrl("https://github.com@attacker.example/a.zip"));
        assertFalse(InstallCatalog.isTrustedDownloadUrl("https://github.com:8443/a.zip"));
        assertFalse(InstallCatalog.isTrustedDownloadUrl("http://github.com/a.zip"));
        assertFalse(InstallCatalog.isTrustedDownloadUrl("https://gist.githubusercontent.com/a/raw/a.zip"));
        assertFalse(InstallCatalog.isTrustedDownloadUrl("not a url"));
        assertFalse(InstallCatalog.isTrustedDownloadUrl(null));
    }

    /** ShellCheck and shfmt come from pinned, hash-checked upstream releases for every platform (C2). */
    @org.junit.jupiter.api.Test
    void theShellToolsArePinnedToTrustedReleaseAssetsWithHashes() {
        for (String id : InstallCatalog.PINNED_TOOL_IDS) {
            var tool = InstallCatalog.pinnedTool(id).orElseThrow();
            for (InstallCatalog.Platform platform : InstallCatalog.Platform.values()) {
                var asset = tool.assetFor(platform);
                org.junit.jupiter.api.Assertions.assertNotNull(asset, id + " has a build for " + platform);
                org.junit.jupiter.api.Assertions.assertTrue(
                        InstallCatalog.isTrustedDownloadUrl(asset.url()), asset.url());
                org.junit.jupiter.api.Assertions.assertTrue(
                        asset.url().contains("v" + tool.version()), asset.url() + " is the pinned version");
                org.junit.jupiter.api.Assertions.assertTrue(
                        asset.sha256().matches("[0-9a-f]{64}"), id + "/" + platform + " has a SHA-256");
            }
            var steps = InstallCatalog.toolInstall(id).orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals(
                    InstallCatalog.Kind.PINNED_TOOL, steps.get(0).kind());
        }
        org.junit.jupiter.api.Assertions.assertEquals(
                java.nio.file.Path.of("/cfg/plugins/tools/shfmt/shfmt"),
                java.nio.file.Path.of("/cfg/plugins/tools/shfmt")
                        .resolve(InstallCatalog.toolFileName("shfmt", InstallCatalog.Platform.LINUX_X64)));
        org.junit.jupiter.api.Assertions.assertEquals(
                "shellcheck.exe", InstallCatalog.toolFileName("shellcheck", InstallCatalog.Platform.WIN_X64));
        org.junit.jupiter.api.Assertions.assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                InstallCatalog.sha256Hex(new byte[0]));
    }

    /**
     * The three jars the lemminx-maven bundle leaves out (Maven review C1): pinned by version and SHA-256, on
     * Maven Central (a trusted download host), named like the jars they are, and only for that step.
     */
    @Test
    void theLemminxMavenExtraJarsArePinnedOnCentral() {
        List<InstallCatalog.JvmLspJar> jars = InstallCatalog.LEMMINX_MAVEN_EXTRA_JARS;
        assertEquals(
                List.of("guava-33.7.2-jre.jar", "commons-lang3-3.21.0.jar", "jsoup-1.23.2.jar"),
                jars.stream().map(InstallCatalog.JvmLspJar::fileName).toList());
        for (InstallCatalog.JvmLspJar jar : jars) {
            assertTrue(InstallCatalog.isTrustedDownloadUrl(jar.url()), jar.url());
            assertTrue(jar.url().startsWith(InstallCatalog.MAVEN_CENTRAL), jar.url());
            assertTrue(jar.url().endsWith("/" + jar.fileName()), jar.url());
            assertTrue(jar.sha256().matches("[0-9a-f]{64}"), jar.sha256());
        }
        assertEquals(
                "https://repo1.maven.org/maven2/com/google/guava/guava/33.7.2-jre/guava-33.7.2-jre.jar",
                jars.get(0).url());
        assertEquals(jars, InstallCatalog.jvmLspExtraJars(InstallCatalog.LEMMINX_MAVEN_STEP));
        assertEquals(List.of(), InstallCatalog.jvmLspExtraJars("gls"));
        assertEquals(List.of(), InstallCatalog.jvmLspExtraJars(null));
        // Every required-jar prefix detection checks for is either in the bundle or one of these.
        for (String prefix : MavenServerInstall.REQUIRED_JAR_PREFIXES) {
            boolean inBundle = prefix.equals("org.eclipse.lemminx-") || prefix.equals("lemminx-maven-");
            assertTrue(inBundle || jars.stream().anyMatch(j -> j.fileName().startsWith(prefix)), prefix);
        }
    }

    @Test
    void aStepNamesTheServersItProvides() {
        assertEquals(List.of("java"), InstallCatalog.serversOfStep("jdtls"));
        assertEquals(List.of("typescript"), InstallCatalog.serversOfStep("typescript-language-server"));
        assertEquals(
                List.of("json", "html", "css"),
                InstallCatalog.serversOfStep("vscode-langservers-extracted"),
                "one npm package serves three servers");
        assertEquals(List.of("kotlin"), InstallCatalog.serversOfStep(InstallCatalog.KOTLIN_LSP_STEP));
        assertEquals(List.of(), InstallCatalog.serversOfStep("js-debug"), "a debugger is no language server");
        assertEquals(List.of(), InstallCatalog.serversOfStep(null));
    }

    /**
     * Bash review A6/A12: the Bash server's install is pinned and wants Node.js 20 first; Doctor reads every
     * npm-installed server's version from the package the install names, without its pinned version.
     */
    @Test
    void theBashServerIsPinnedAndNeedsNode20() {
        assertEquals(VersionFloor.NODE_BASH_LS, InstallCatalog.nodeFloor("bash-language-server"));
        assertEquals("20", InstallCatalog.nodeFloor("bash-language-server").minimum());
        assertNull(InstallCatalog.nodeFloor("yaml-language-server"), "no floor where the catalog sets none");
        assertNull(InstallCatalog.nodeFloor(null));
        assertFalse(VersionFloor.NODE_BASH_LS.metBy("v18.19.0"), "Debian 12's Node.js is refused");
        assertTrue(VersionFloor.NODE_BASH_LS.metBy("v20.0.0"));
        assertTrue(VersionFloor.BASH_LANGUAGE_SERVER.metBy(InstallCatalog.BASH_LS_VERSION), "the pin is tested");
        assertFalse(VersionFloor.BASH_LANGUAGE_SERVER.metBy("5.6.0"), "5.6 predates the 5.7 fixes");

        assertEquals("bash-language-server", InstallCatalog.npmPackage("bash"));
        assertEquals("pyright", InstallCatalog.npmPackage("python"));
        assertEquals("typescript-language-server", InstallCatalog.npmPackage("typescript"));
        assertEquals("vscode-langservers-extracted", InstallCatalog.npmPackage("css"));
        assertEquals("@astrojs/language-server", InstallCatalog.npmPackage("astro"));
        assertNull(InstallCatalog.npmPackage("go"), "gopls is installed with go, not npm");
        assertNull(InstallCatalog.npmPackage(null));
        assertEquals("@scope/name", InstallCatalog.packageName("@scope/name@1.2.3"));
        assertEquals("@scope/name", InstallCatalog.packageName("@scope/name"));
        assertEquals("typescript", InstallCatalog.packageName("typescript@6"));
    }
}
