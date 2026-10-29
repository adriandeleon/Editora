package com.editora.install;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTML review A2/A12: Editora's own pinned vscode-langservers-extracted, its TypeScript link, and Doctor's check. */
class WebServerInstallTest {

    static Path npmLayout(Path prefix, boolean hoisted) throws Exception {
        return InstallTestAccess.webServersLayout(prefix, hoisted);
    }

    @Test
    void theLinkPutsTypeScriptsLibrariesWhereTheHtmlServerLooks(@TempDir Path dir) throws Exception {
        Path prefix = dir.resolve("plugins/lsp/web");
        Path pkg = npmLayout(prefix, true);
        Path launcher = pkg.resolve("bin/vscode-html-language-server");
        assertTrue(WebServerInstall.typeScriptLibsMissing(launcher), "npm's own layout lacks them (A2)");

        WebServerInstall.linkTypeScriptLibs(prefix);

        // javascriptLibs.js: join(<pkg>/lib, '../../node_modules/typescript/lib')
        Path looked =
                pkg.resolve("lib").resolve("../../node_modules/typescript/lib").normalize();
        assertTrue(Files.isRegularFile(looked.resolve("lib.es2020.full.d.ts")), looked.toString());
        assertTrue(Files.isRegularFile(looked.resolve("lib.dom.d.ts")));
        assertFalse(WebServerInstall.typeScriptLibsMissing(launcher));
        WebServerInstall.linkTypeScriptLibs(prefix); // again: nothing to do, no failure
        assertFalse(WebServerInstall.typeScriptLibsMissing(launcher));
    }

    @Test
    void aNestedTypeScriptIsLinkedToo(@TempDir Path dir) throws Exception {
        Path pkg = npmLayout(dir, false);
        WebServerInstall.linkTypeScriptLibs(dir);
        assertFalse(WebServerInstall.typeScriptLibsMissing(pkg.resolve("bin/vscode-html-language-server")));
    }

    @Test
    void anInstallWithoutTypeScriptFails(@TempDir Path dir) throws Exception {
        Path pkg = npmLayout(dir, true);
        try (var files = Files.walk(dir.resolve("node_modules/typescript"))) {
            for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
        assertThrows(java.io.IOException.class, () -> WebServerInstall.linkTypeScriptLibs(dir));
        assertTrue(WebServerInstall.typeScriptLibsMissing(pkg.resolve("bin/vscode-html-language-server")));
    }

    @Test
    void aGlobalInstallsLinkedLauncherIsFollowedToItsPackage(@TempDir Path dir) throws Exception {
        // npm -g: <prefix>/lib/node_modules/<pkg>, launcher <prefix>/bin/<name> → ../lib/node_modules/<pkg>/bin/<name>
        Path global = dir.resolve("lib");
        Path pkg = npmLayout(global, true);
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path link = bin.resolve("vscode-html-language-server");
        try {
            Files.createSymbolicLink(link, bin.relativize(pkg.resolve("bin/vscode-html-language-server")));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("no symbolic links here");
        }
        assertEquals(pkg.toRealPath(), WebServerInstall.packageOf(link));
        assertTrue(WebServerInstall.typeScriptLibsMissing(link), "the package's global install has the hole too");
        // a Windows .cmd shim sits in the prefix, beside node_modules
        Path cmd = Files.writeString(global.resolve("vscode-html-language-server.cmd"), "@node %*\r\n");
        assertEquals(pkg.toRealPath(), WebServerInstall.packageOf(cmd));
    }

    @Test
    void anotherServersLauncherIsNotFlagged(@TempDir Path dir) throws Exception {
        Path own = Files.writeString(dir.resolve("superhtml"), "#!/bin/sh\n");
        assertNull(WebServerInstall.packageOf(own));
        assertFalse(WebServerInstall.typeScriptLibsMissing(own));
        assertFalse(WebServerInstall.typeScriptLibsMissing(dir.resolve("missing")));
    }

    @Test
    void aBlankOrDefaultCommandRunsEditorasCopyFirst(@TempDir Path config) throws Exception {
        assertEquals("", WebServerInstall.effectiveCommand("html", "", config), "not installed: PATH's default");
        npmLayout(WebServerInstall.folder(config), true);
        Path launcher = WebServerInstall.ownLauncher(config, "html");
        assertTrue(launcher != null && launcher.startsWith(config), String.valueOf(launcher));
        String own = InstallCatalog.quoteCommandPath(launcher.toString()) + " --stdio";
        assertEquals(own, WebServerInstall.effectiveCommand("html", "", config));
        assertEquals(own, WebServerInstall.effectiveCommand("html", "vscode-html-language-server  --stdio", config));
        assertTrue(WebServerInstall.effectiveCommand("css", null, config).contains("vscode-css-language-server"));
        assertTrue(WebServerInstall.effectiveCommand("json", "", config).contains("vscode-json-language-server"));
        assertEquals(
                "node /opt/x/htmlServerNodeMain.js --stdio",
                WebServerInstall.effectiveCommand("html", "node /opt/x/htmlServerNodeMain.js --stdio", config),
                "the user's own command is kept");
        assertEquals("", WebServerInstall.effectiveCommand("yaml", "", config), "another server passes through");
        assertEquals("", WebServerInstall.effectiveCommand("html", "", null));
    }

    @Test
    void windowsRunsTheCmdShim(@TempDir Path config) throws Exception {
        npmLayout(WebServerInstall.folder(config), true);
        Path cmd = WebServerInstall.ownLauncher(config, "html", true);
        assertTrue(cmd != null && cmd.getFileName().toString().endsWith(".cmd"), String.valueOf(cmd));
        assertNull(WebServerInstall.ownLauncher(config, "yaml", false));
    }

    @Test
    void theInstalledVersionAndTheServersLogLine(@TempDir Path dir) throws Exception {
        assertNull(WebServerInstall.installedVersion(dir));
        npmLayout(dir, true);
        assertEquals("4.10.0", WebServerInstall.installedVersion(dir));
        assertEquals("4.10.0", PinnedVersions.fingerprint(InstallCatalog.WEB_LS_PACKAGE, dir));
        assertTrue(WebServerInstall.unableToLoadLibrary(
                "Unable to load library lib.es2020.full.d.ts at /x/node_modules/node_modules/typescript/lib"));
        assertFalse(
                WebServerInstall.unableToLoadLibrary("Unable to load library jquery at /x/lib/lib/jquery.d.ts"),
                "jquery.d.ts is missing everywhere but in VS Code, and harmless");
        assertFalse(WebServerInstall.unableToLoadLibrary(null));
    }

    @Test
    void theCatalogInstallsThePinIntoTheSharedPrefix() {
        for (String id : List.of("json", "html", "css")) {
            var steps = InstallCatalog.serverInstall(id).orElseThrow();
            assertEquals(1, steps.size());
            var step = steps.get(0);
            assertEquals(InstallCatalog.Kind.NPM_PREFIX, step.kind());
            assertEquals(InstallCatalog.WEB_LS_PACKAGE, step.id());
            assertEquals(List.of("vscode-langservers-extracted@4.10.0"), step.npmPackages());
            assertEquals(WebServerInstall.DEST, step.destSubpath(), "one prefix for the three");
            assertEquals(InstallCatalog.WEB_LS_PACKAGE, InstallCatalog.npmPackage(id));
        }
        assertEquals(
                List.of(
                        "npm",
                        "install",
                        "--prefix",
                        Path.of("/c/plugins/lsp/web").toString(),
                        "--no-audit",
                        "--no-fund",
                        "vscode-langservers-extracted@4.10.0"),
                InstallCatalog.npmInstallPrefixArgv(
                        Path.of("/c/plugins/lsp/web"), List.of("vscode-langservers-extracted@4.10.0")));
        assertEquals(
                "4.10.0",
                PinnedVersions.fromVersionOutput(InstallCatalog.WEB_LS_PACKAGE, "vscode-langservers-extracted 4.10.0"));
        assertNull(PinnedVersions.fromVersionOutput(InstallCatalog.WEB_LS_PACKAGE, "pyright 1.1.400"));
    }
}
