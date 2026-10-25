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
}
