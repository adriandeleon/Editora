package com.editora.dap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebugAdapterLocatorTest {

    private static final String J0 = "com.microsoft.java.debug.plugin-0.53.0.jar";
    private static final String J1 = "com.microsoft.java.debug.plugin-0.53.1.jar";
    private static final String J2 = "com.microsoft.java.debug.plugin-0.100.0.jar";

    @Test
    void matchesOnlyPluginJars() {
        assertTrue(DebugAdapterLocator.matches(J0));
        assertTrue(DebugAdapterLocator.matches(J2));
        assertFalse(DebugAdapterLocator.matches("org.eclipse.jdt.ls.core-1.0.jar"));
        assertFalse(DebugAdapterLocator.matches("com.microsoft.java.debug.plugin-.jar")); // no version
        assertFalse(DebugAdapterLocator.matches("com.microsoft.java.debug.plugin-0.53.0.zip"));
        assertFalse(DebugAdapterLocator.matches(null));
    }

    @Test
    void versionOfExtractsVersion() {
        assertEquals("0.53.1", DebugAdapterLocator.versionOf(J1));
        assertEquals("", DebugAdapterLocator.versionOf("not-a-plugin.jar"));
    }

    @Test
    void compareVersionsIsNumericNotLexical() {
        assertTrue(DebugAdapterLocator.compareVersions("0.100.0", "0.53.1") > 0); // 100 > 53 numerically
        assertTrue(DebugAdapterLocator.compareVersions("0.53.1", "0.53.0") > 0);
        assertEquals(0, DebugAdapterLocator.compareVersions("1.2.3", "1.2.3"));
    }

    @Test
    void selectNewestPicksHighestVersionAndIgnoresNonMatches() {
        String newest = DebugAdapterLocator.selectNewest(List.of(
                "/x/" + J0, "/y/" + J2, "/z/" + J1, "/q/random.jar"));
        assertEquals("/y/" + J2, newest);
        assertEquals(null, DebugAdapterLocator.selectNewest(List.of("random.jar")));
    }

    @Test
    void locateUsesConfiguredJarDirectly(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve(J1);
        Files.createFile(jar);
        Optional<Path> found = DebugAdapterLocator.locate(jar.toString(), dir);
        assertEquals(Optional.of(jar), found);
    }

    @Test
    void locateScansConfiguredDirForNewest(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve(J0));
        Files.createFile(dir.resolve(J1));
        Optional<Path> found = DebugAdapterLocator.locate(dir.toString(), dir);
        assertEquals(Optional.of(dir.resolve(J1)), found);
    }

    @Test
    void locateAutoDetectsAcrossInstallLocationsAndPicksNewest(@TempDir Path home) throws IOException {
        // VS Code "Debugger for Java" extension server dir with an older jar
        Path vscode = home.resolve(".vscode/extensions/vscjava.vscode-java-debug-0.58.0/server");
        Files.createDirectories(vscode);
        Files.createFile(vscode.resolve(J0));
        // mason install with a newer jar
        Path mason = home.resolve(".local/share/nvim/mason/packages/java-debug-adapter/extension/server");
        Files.createDirectories(mason);
        Files.createFile(mason.resolve(J2));

        Optional<Path> found = DebugAdapterLocator.locate("", home);
        assertEquals(Optional.of(mason.resolve(J2)), found); // newest across all locations
    }

    @Test
    void locateFindsEditoraPluginDir(@TempDir Path home) throws IOException {
        // Where scripts/install-java-debug.sh drops the jar — auto-detected with no configured path.
        Path editora = home.resolve(".editora/plugins/dap/java");
        Files.createDirectories(editora);
        Files.createFile(editora.resolve(J1));
        assertEquals(Optional.of(editora.resolve(J1)), DebugAdapterLocator.locate("", home));
    }

    @Test
    void locateReturnsEmptyWhenNothingFound(@TempDir Path home) {
        assertTrue(DebugAdapterLocator.locate("", home).isEmpty());
        assertTrue(DebugAdapterLocator.locate(home.resolve("nope.jar").toString(), home).isEmpty());
    }

    // --- vscode-js-debug -----------------------------------------------------------------------

    @Test
    void pathVersionExtractsFirstVersion() {
        assertEquals("1.96.0", DebugAdapterLocator.pathVersion("/x/js-debug-v1.96.0/js-debug/src/x.js"));
        assertEquals("1.96", DebugAdapterLocator.pathVersion("ms-vscode.js-debug-1.96/y"));
        assertEquals("", DebugAdapterLocator.pathVersion("/no/version/here"));
    }

    @Test
    void locateJsDebugUsesConfiguredEntryDirectly(@TempDir Path dir) throws IOException {
        Path entry = dir.resolve("dapDebugServer.js");
        Files.createFile(entry);
        assertEquals(Optional.of(entry),
                DebugAdapterLocator.locateJsDebugServer(entry.toString(), dir));
    }

    @Test
    void locateJsDebugSearchesConfiguredDir(@TempDir Path dir) throws IOException {
        Path nested = dir.resolve("js-debug/src");
        Files.createDirectories(nested);
        Path entry = nested.resolve("dapDebugServer.js");
        Files.createFile(entry);
        assertEquals(Optional.of(entry), DebugAdapterLocator.locateJsDebugServer(dir.toString(), dir));
    }

    @Test
    void locateJsDebugAutoDetectsEditoraPluginDir(@TempDir Path home) throws IOException {
        Path src = home.resolve(".editora/plugins/dap/javascript/js-debug/src");
        Files.createDirectories(src);
        Path entry = src.resolve("dapDebugServer.js");
        Files.createFile(entry);
        assertEquals(Optional.of(entry), DebugAdapterLocator.locateJsDebugServer("", home));
    }

    @Test
    void locateJsDebugPicksNewestByPathVersion(@TempDir Path home) throws IOException {
        Path oldExt = home.resolve(".vscode/extensions/ms-vscode.js-debug-1.80.0/src");
        Path newExt = home.resolve(".vscode/extensions/ms-vscode.js-debug-1.96.0/src");
        Files.createDirectories(oldExt);
        Files.createDirectories(newExt);
        Files.createFile(oldExt.resolve("dapDebugServer.js"));
        Path newest = newExt.resolve("dapDebugServer.js");
        Files.createFile(newest);
        assertEquals(Optional.of(newest), DebugAdapterLocator.locateJsDebugServer("", home));
    }

    @Test
    void locateJsDebugEmptyWhenNothingFound(@TempDir Path home) {
        assertTrue(DebugAdapterLocator.locateJsDebugServer("", home).isEmpty());
    }

    // --- debugpy -------------------------------------------------------------------------------

    @Test
    void locateDebugpyFindsEditoraPluginDir(@TempDir Path home) throws IOException {
        Path target = home.resolve(".editora/plugins/dap/python");
        Files.createDirectories(target.resolve("debugpy"));
        assertEquals(Optional.of(target), DebugAdapterLocator.locateDebugpy("", home));
    }

    @Test
    void locateDebugpyUsesConfiguredDirWithPackage(@TempDir Path home) throws IOException {
        Path custom = home.resolve("custom-pythonpath");
        Files.createDirectories(custom.resolve("debugpy"));
        assertEquals(Optional.of(custom), DebugAdapterLocator.locateDebugpy(custom.toString(), home));
    }

    @Test
    void locateDebugpyEmptyWhenNoPackage(@TempDir Path home) throws IOException {
        // A dir that exists but has no debugpy package, and no editora install → empty (caller falls back
        // to a configured/PATH python importing debugpy directly).
        Path custom = home.resolve("empty");
        Files.createDirectories(custom);
        assertTrue(DebugAdapterLocator.locateDebugpy(custom.toString(), home).isEmpty());
        assertTrue(DebugAdapterLocator.locateDebugpy("", home).isEmpty());
    }
}
