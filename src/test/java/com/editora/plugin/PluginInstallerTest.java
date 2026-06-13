package com.editora.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for the unzip guard, version compare, id safety, and the sha-256 helper (pure / temp-dir). */
class PluginInstallerTest {

    private static byte[] zip(String... nameThenContent) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (int i = 0; i < nameThenContent.length; i += 2) {
                z.putNextEntry(new ZipEntry(nameThenContent[i]));
                z.write(nameThenContent[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test
    void extractsAFlatArchive(@TempDir Path dir) throws IOException {
        byte[] data = zip("plugin.json", "{\"id\":\"x\"}", "snippets/md.json", "{}");
        Unzip.extract(new ByteArrayInputStream(data), dir);
        assertTrue(Files.isRegularFile(dir.resolve("plugin.json")));
        assertTrue(Files.isRegularFile(dir.resolve("snippets/md.json")));
        assertEquals("{\"id\":\"x\"}", Files.readString(dir.resolve("plugin.json")));
    }

    @Test
    void rejectsZipSlip(@TempDir Path dir) throws IOException {
        byte[] evil = zip("../escape.txt", "pwned");
        assertThrows(IOException.class, () -> Unzip.extract(new ByteArrayInputStream(evil), dir));
        // The escaping file must NOT have been written outside the destination.
        assertFalse(Files.exists(dir.getParent().resolve("escape.txt")));
    }

    @Test
    void safeEntryNameGuards() {
        assertTrue(Unzip.safeEntryName("plugin.json"));
        assertTrue(Unzip.safeEntryName("lib/foo.jar"));
        assertFalse(Unzip.safeEntryName("../x"));
        assertFalse(Unzip.safeEntryName("a/../../b"));
        assertFalse(Unzip.safeEntryName("/abs/path"));
        assertFalse(Unzip.safeEntryName("C:\\win"));
        assertFalse(Unzip.safeEntryName(""));
        assertFalse(Unzip.safeEntryName(null));
    }

    @Test
    void isUnderDirGuards(@TempDir Path dir) {
        assertTrue(Unzip.isUnderDir(dir, dir.resolve("a/b")));
        assertFalse(Unzip.isUnderDir(dir, dir.resolve("../sibling")));
    }

    @Test
    void compareVersionsIsNumericSegmentwise() {
        assertTrue(PluginInstaller.compareVersions("1.10.0", "1.9.0") > 0);
        assertTrue(PluginInstaller.compareVersions("1.0.0", "1.0.1") < 0);
        assertEquals(0, PluginInstaller.compareVersions("2.0", "2.0.0"));
        assertTrue(PluginInstaller.compareVersions("1.2.0", "1.2") == 0);
        assertTrue(PluginInstaller.compareVersions("", "0.0.0") == 0);
        // non-numeric falls back to string compare
        assertTrue(PluginInstaller.compareVersions("1.0-beta", "1.0-alpha") > 0);
    }

    @Test
    void isSafeIdGuards() {
        assertTrue(PluginInstaller.isSafeId("example"));
        assertTrue(PluginInstaller.isSafeId("my-plugin_2.0"));
        assertFalse(PluginInstaller.isSafeId(".."));
        assertFalse(PluginInstaller.isSafeId("a/b"));
        assertFalse(PluginInstaller.isSafeId("a\\b"));
        assertFalse(PluginInstaller.isSafeId("bad..id"));
        assertFalse(PluginInstaller.isSafeId(""));
    }

    @Test
    void sha256MatchesKnownValue() {
        // sha-256 of the empty input
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                PluginInstaller.sha256(new byte[0]));
    }
}
