package com.editora.run;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElectronAppsTest {

    private static final Path ROOT = Path.of("/work/shelf").toAbsolutePath();

    private static final Path PROGRAM = ROOT.resolve("node_modules/electron/dist/electron");

    /** A disk with {@code files} (path → text). */
    private static Optional<ElectronApps.App> of(Path file, String text, Map<Path, String> files) {
        return ElectronApps.of(file, text, files::containsKey, files::get);
    }

    private static Map<Path, String> app(String packageJson) {
        Map<Path, String> files = new HashMap<>();
        files.put(ROOT.resolve("package.json"), packageJson);
        files.put(ROOT.resolve("node_modules/electron/path.txt"), "electron");
        files.put(PROGRAM, "");
        return files;
    }

    @Test
    void thePackagesMainStartsTheAppOnElectronsProgram() {
        Map<Path, String> files = app("{\"main\": \"src/main.js\", \"devDependencies\": {\"electron\": \"^45.0.0\"}}");
        assertEquals(
                Optional.of(new ElectronApps.App(ROOT, PROGRAM)),
                of(ROOT.resolve("src/main.js"), "app.whenReady()", files));
    }

    @Test
    void aFileThatLoadsElectronStartsItAndOthersDoNot() {
        Map<Path, String> files = app("{\"dependencies\": {\"electron\": \"45.4.1\"}}");
        assertTrue(of(ROOT.resolve("window.js"), "const { BrowserWindow } = require('electron');", files)
                .isPresent());
        assertTrue(of(ROOT.resolve("preload.mjs"), "import { contextBridge } from \"electron\";", files)
                .isPresent());
        assertTrue(of(ROOT.resolve("index.js"), "", files).isPresent(), "main defaults to index.js");
        assertEquals(Optional.empty(), of(ROOT.resolve("renderer.js"), "document.title = 'x';", files), "a page's");
        assertEquals(Optional.empty(), of(ROOT.resolve("scripts/build.js"), "require('fs')", files));
        assertEquals(Optional.empty(), of(ROOT.resolve("index.html"), "", files), "not a script");
    }

    @Test
    void noAppWithoutElectronInTheNearestPackageOrInstalled() {
        assertEquals(Optional.empty(), of(ROOT.resolve("index.js"), "", app("{\"dependencies\": {\"x\": \"1\"}}")));
        Map<Path, String> notInstalled = new HashMap<>();
        notInstalled.put(ROOT.resolve("package.json"), "{\"devDependencies\": {\"electron\": \"1\"}}");
        assertEquals(Optional.empty(), of(ROOT.resolve("index.js"), "", notInstalled), "npm install not run");
        Map<Path, String> broken = app("{ not json");
        assertEquals(Optional.empty(), of(ROOT.resolve("index.js"), "", broken));
        Map<Path, String> nested = app("{\"devDependencies\": {\"electron\": \"1\"}}");
        nested.put(ROOT.resolve("tools/package.json"), "{\"name\": \"tools\"}");
        assertEquals(
                Optional.empty(),
                of(ROOT.resolve("tools/index.js"), "require('electron')", nested),
                "the nearest package decides");
        assertEquals(
                Optional.empty(),
                of(ROOT.resolve("node_modules/electron/index.js"), "require('electron')", nested),
                "a dependency's own file");
    }

    @Test
    void electronInstalledInAWorkspacesRootNodeModulesIsFound() {
        Path app = ROOT.resolve("packages/desktop");
        Map<Path, String> files = new HashMap<>();
        files.put(app.resolve("package.json"), "{\"devDependencies\": {\"electron\": \"1\"}}");
        files.put(ROOT.resolve("node_modules/electron/path.txt"), "Electron.app/Contents/MacOS/Electron\n");
        Path mac = ROOT.resolve("node_modules/electron/dist/Electron.app/Contents/MacOS/Electron");
        files.put(mac, "");
        assertEquals(Optional.of(new ElectronApps.App(app, mac)), of(app.resolve("index.js"), "", files));
    }

    @Test
    void theAppRunsOnItsFolderItsRendererPortFirst() {
        assertEquals(
                List.of("--remote-debugging-port=9223", ".", "--no-sandbox"),
                ElectronApps.args(9223, List.of("--no-sandbox")));
        assertEquals(List.of("."), ElectronApps.args(0, List.of()), "Run: no port");
    }
}
