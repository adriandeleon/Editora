package com.editora.install;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which jdtls is installed, and whether it is older than the pinned one (Java review D8). */
class JdtlsVersionTest {

    @Test
    void theCoreBundleNamesTheVersion() {
        JdtlsVersion snapshot = JdtlsVersion.ofBundle("org.eclipse.jdt.ls.core_1.61.0.202607301809.jar");
        assertEquals(new JdtlsVersion(1, 61, 0, 202607301809L), snapshot);
        assertEquals("1.61.0 (2026-07-30 build)", snapshot.label());
        assertNull(JdtlsVersion.ofBundle("org.eclipse.jdt.ls.filesystem_1.61.0.202607301809.jar"));
        assertTrue(snapshot.compareTo(JdtlsVersion.pinned()) < 0, "the 1.61 snapshot is older than the pin");
        assertTrue(new JdtlsVersion(1, 61, 0, 202609021200L).compareTo(snapshot) > 0, "same version, later build");
    }

    @Test
    void theInstalledVersionIsReadFromTheLauncherOrTheFolder(@TempDir Path home) throws Exception {
        Path plugins = Files.createDirectories(home.resolve("plugins"));
        Files.createFile(plugins.resolve("org.eclipse.jdt.ls.core_1.61.0.202607301809.jar"));
        Files.createFile(plugins.resolve("org.eclipse.jdt.ls.filesystem_1.61.0.202607301809.jar"));
        Path launcher =
                Files.createFile(Files.createDirectories(home.resolve("bin")).resolve("jdtls"));
        assertEquals(new JdtlsVersion(1, 61, 0, 202607301809L), JdtlsVersion.installed(home));
        assertEquals(new JdtlsVersion(1, 61, 0, 202607301809L), JdtlsVersion.installed(launcher));
        assertNull(JdtlsVersion.installed(home.resolve("nowhere")));
    }

    @Test
    void onlyEditorasOwnOutdatedCopyIsOneInstallCanReplace(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        Path own = jdtls(config.resolve("plugins/lsp/java"), "1.61.0.202607301809");
        Path elsewhere = jdtls(dir.resolve("opt/jdtls"), "1.61.0.202607301809");
        assertEquals(new JdtlsVersion(1, 61, 0, 202607301809L), JdtlsVersion.outdatedEditoraCopy(own, config));
        assertNull(JdtlsVersion.outdatedEditoraCopy(elsewhere, config), "Install… would not touch it (CN4)");
        assertNull(JdtlsVersion.outdatedEditoraCopy(own, null));
        Files.createFile(own.getParent().getParent().resolve("plugins/org.eclipse.jdt.ls.core_9.0.0.209912311200.jar"));
        assertNull(JdtlsVersion.outdatedEditoraCopy(own, config), "a current copy is not outdated");
    }

    private static Path jdtls(Path home, String version) throws Exception {
        Files.createFile(Files.createDirectories(home.resolve("plugins"))
                .resolve("org.eclipse.jdt.ls.core_" + version + ".jar"));
        return Files.createFile(Files.createDirectories(home.resolve("bin")).resolve("jdtls"));
    }
}
