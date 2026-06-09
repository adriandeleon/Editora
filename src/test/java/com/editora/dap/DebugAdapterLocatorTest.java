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
}
