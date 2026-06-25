package com.editora.install;

import java.nio.file.Path;
import java.util.List;

import com.editora.install.InstallCatalog.Kind;
import com.editora.install.InstallCatalog.Lang;
import com.editora.install.InstallCatalog.Prereq;
import com.editora.install.InstallCatalog.Step;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                List.of("python3", "-m", "pip", "install", "--upgrade", "--target", "/tmp/dest", "debugpy"),
                InstallCatalog.pipInstallTargetArgv("python3", Path.of("/tmp/dest"), "debugpy"));
    }

    @Test
    void tarArgvExtractsGzipIntoDest() {
        assertEquals(
                List.of("tar", "-xzf", "/tmp/x.tar.gz", "-C", "/tmp/out"),
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
        assertEquals("dapDebugServer.js", jsDebug.verifyEntry());
        assertNull(jsDebug.directUrl());
    }

    @Test
    void mermaidStepIsMmdcNpmOnly() {
        List<Step> steps = InstallCatalog.steps(Lang.MERMAID);
        assertEquals(1, steps.size());
        assertEquals("mmdc", steps.get(0).id());
        assertEquals(Kind.NPM_GLOBAL, steps.get(0).kind());
        assertEquals(List.of("@mermaid-js/mermaid-cli"), steps.get(0).npmPackages());
        assertFalse(steps.get(0).extractJarOnly());
    }
}
