package com.editora.install;

import com.editora.install.LatestVersions.Registry;
import com.editora.install.LatestVersions.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatestVersionsTest {

    @Test
    void serversInstalledAtLatestHaveASourceAndPinnedOnesDoNot() {
        assertEquals(
                new Source(Registry.NPM, "pyright"),
                LatestVersions.forServer("python").orElseThrow());
        assertEquals(
                LatestVersions.forServer("json").orElseThrow(),
                LatestVersions.forServer("css").orElseThrow(),
                "one npm package serves JSON, HTML and CSS");
        assertEquals(Registry.GO, LatestVersions.forServer("go").orElseThrow().registry());
        assertEquals(
                Registry.HASHICORP,
                LatestVersions.forServer("terraform").orElseThrow().registry());
        for (PinnedVersions.Pin pin : PinnedVersions.all()) {
            if (pin.serverId() != null) {
                assertTrue(
                        LatestVersions.forServer(pin.serverId()).isEmpty(),
                        pin.serverId() + " is pinned: a release of Editora adopts its new versions");
            }
        }
        assertTrue(LatestVersions.forServer("rust").isEmpty(), "rustup updates rust-analyzer itself");
        assertTrue(LatestVersions.forServer(null).isEmpty());
    }

    @Test
    void eachRegistryIsAskedAtItsLatestEndpoint() {
        assertEquals("https://registry.npmjs.org/@taplo%2Fcli/latest", new Source(Registry.NPM, "@taplo/cli").url());
        assertEquals("https://pypi.org/pypi/debugpy/json", new Source(Registry.PYPI, "debugpy").url());
        assertEquals(
                "https://proxy.golang.org/golang.org/x/tools/gopls/@latest",
                new Source(Registry.GO, "golang.org/x/tools/gopls").url());
        assertEquals(
                "https://api.github.com/repos/clangd/clangd/releases/latest",
                new Source(Registry.GITHUB, "clangd/clangd").url());
        assertEquals(
                "https://api.releases.hashicorp.com/v1/releases/terraform-ls/latest",
                new Source(Registry.HASHICORP, "terraform-ls").url());
        assertEquals("npm:@taplo/cli", new Source(Registry.NPM, "@taplo/cli").key());
    }

    @Test
    void eachRegistrysAnswerGivesItsRelease() {
        assertEquals("1.1.414", LatestVersions.parse(Registry.NPM, "{\"name\":\"pyright\",\"version\":\"1.1.414\"}"));
        assertEquals("1.8.17", LatestVersions.parse(Registry.PYPI, "{\"info\":{\"version\":\"1.8.17\"}}"));
        assertEquals("v0.24.0", LatestVersions.parse(Registry.GO, "{\"Version\":\"v0.24.0\",\"Time\":\"2026-10-01\"}"));
        assertEquals("23.1.0", LatestVersions.parse(Registry.GITHUB, "{\"tag_name\":\"23.1.0\",\"draft\":false}"));
        assertEquals("0.39.0", LatestVersions.parse(Registry.HASHICORP, "{\"version\":\"0.39.0\"}"));
    }

    @Test
    void anythingButAReleaseIsNone() {
        assertNull(LatestVersions.parse(Registry.GITHUB, "{\"tag_name\":\"v2.0.0-rc1\",\"prerelease\":true}"));
        assertNull(LatestVersions.parse(Registry.GITHUB, "{\"message\":\"API rate limit exceeded\"}"));
        assertNull(LatestVersions.parse(Registry.NPM, "<html>Service Unavailable</html>"));
        assertNull(LatestVersions.parse(Registry.NPM, "{\"version\":\"latest\"}"), "no number, no version");
        assertNull(LatestVersions.parse(Registry.NPM, ""));
        assertNull(LatestVersions.parse(Registry.NPM, null));
    }

    @Test
    void newerComparesTheNumbersOnly() {
        assertTrue(LatestVersions.isNewer("1.1.414", "1.1.411"));
        assertTrue(LatestVersions.isNewer("v0.15.10", "tinymist 0.15.9"));
        assertFalse(LatestVersions.isNewer("v0.24.0", "golang.org/x/tools/gopls v0.24.0"));
        assertFalse(LatestVersions.isNewer("1.0.0", "1.1.0"), "a newer local build is not out of date");
        assertFalse(LatestVersions.isNewer("1.0.0", null));
        assertFalse(LatestVersions.isNewer(null, "1.0.0"));
        assertFalse(LatestVersions.isNewer("1.0.0", "development build"));
    }
}
