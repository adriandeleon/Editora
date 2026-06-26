package com.editora.install;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
