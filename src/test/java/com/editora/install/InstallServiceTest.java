package com.editora.install;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallServiceTest {

    @Test
    void pickArchiveUrlSkipsTheChecksumSibling() {
        // The lemminx release lists a .sha256 next to each .zip, both containing the platform substring.
        String json =
                "\"url\":\"https://github.com/x/vscode-xml/releases/download/0.29.3/lemminx-osx-aarch_64.sha256\","
                        + "\"url\":\"https://github.com/x/vscode-xml/releases/download/0.29.3/lemminx-osx-aarch_64.zip\"";
        assertEquals(
                "https://github.com/x/vscode-xml/releases/download/0.29.3/lemminx-osx-aarch_64.zip",
                InstallService.pickArchiveUrl(json, "osx-aarch_64"));
    }

    @Test
    void pickArchiveUrlMatchesTarballAndZip() {
        String tgz = "\"https://github.com/LuaLS/.../lua-language-server-3.18.2-darwin-arm64.tar.gz\"";
        assertEquals(tgz.replace("\"", ""), InstallService.pickArchiveUrl(tgz, "darwin-arm64"));
        String zip = "\"https://releases.hashicorp.com/terraform-ls/0.38.7/terraform-ls_0.38.7_darwin_arm64.zip\"";
        assertEquals(zip.replace("\"", ""), InstallService.pickArchiveUrl(zip, "darwin_arm64"));
    }

    @Test
    void pickArchiveUrlReturnsNullWhenNoArchiveMatches() {
        assertNull(InstallService.pickArchiveUrl("\"https://x/y/foo-linux.sha256\"", "linux"));
        assertNull(InstallService.pickArchiveUrl(null, "x"));
    }

    @Test
    void findBinaryByExactNameIgnoringWindowsExtensionAndPreferringShortestPath(@TempDir Path dir) throws Exception {
        // A nested copy and a top-level copy of the binary; the shortest path wins.
        Files.createDirectories(dir.resolve("deeply/nested/sub"));
        Files.createFile(dir.resolve("deeply/nested/sub/gopls"));
        Path top = dir.resolve("gopls");
        Files.createFile(top);
        assertEquals(top, InstallService.findBinary(dir, "gopls", false));

        // The ".exe" suffix is stripped before matching the requested name.
        Path winDir = Files.createDirectories(dir.resolve("win"));
        Path exe = winDir.resolve("clangd.exe");
        Files.createFile(exe);
        assertEquals(exe, InstallService.findBinary(winDir, "clangd", false));
    }

    @Test
    void findBinaryByPrefixAndMissingReturnsNull(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("lemminx-osx-aarch_64"));
        assertEquals(
                dir.resolve("lemminx-osx-aarch_64"), InstallService.findBinary(dir, "lemminx", true)); // prefix match
        assertNull(InstallService.findBinary(dir, "lemminx", false)); // exact-name match fails
        assertNull(InstallService.findBinary(dir, "nonesuch", true));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // POSIX exec bit; no-op (and not asserted) on Windows
    void makeExecutableSetsTheExecBit(@TempDir Path dir) throws Exception {
        Path bin = dir.resolve("server");
        Files.writeString(bin, "#!/bin/sh\n");
        InstallService.makeExecutable(bin);
        assertTrue(Files.isExecutable(bin));
    }

    @Test
    void resultConvenienceCtorLeavesInstalledCommandNull() {
        InstallService.Result r = new InstallService.Result(true, "done");
        assertTrue(r.ok());
        assertEquals("done", r.message());
        assertNull(r.installedCommand());
        assertEquals("/opt/clangd", new InstallService.Result(true, "", "/opt/clangd").installedCommand());
    }
}
