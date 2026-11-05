package com.editora.mermaid;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MaidLocator} against an in-memory file system: the default {@code npx -y @probelabs/maid} is found by
 * its files (a global install beside the Settings Node.js or node on PATH, an npm prefix, a {@code maid} on PATH
 * that is this package, npx's cache) — and nothing else counts, so no npx run is ever needed to ask.
 */
class MaidLocatorTest {

    private static final Path HOME = Path.of("/home/ada");

    /** A fake machine: files with their text, and bare command names PATH resolves. */
    private static final class Machine {
        final Map<Path, String> files = new HashMap<>();
        final Map<String, String> path = new HashMap<>();
        final Map<String, String> env = new HashMap<>();
        final Map<Path, Path> links = new HashMap<>();
        String nodeSetting = "";
        boolean windows;

        Machine maid(Path packageDir, String version) {
            files.put(
                    packageDir.resolve("package.json"),
                    "{\n  \"name\": \"@probelabs/maid\",\n  \"version\": \"" + version
                            + "\",\n  \"bin\": {\"maid\": \"dist/cli.js\"}\n}\n");
            return this;
        }

        MaidLocator.Inputs inputs() {
            return new MaidLocator.Inputs(
                    HOME,
                    windows,
                    env::get,
                    nodeSetting,
                    name -> Optional.ofNullable(path.get(name)),
                    p -> Optional.ofNullable(files.get(p)),
                    dir -> {
                        List<Path> out = new ArrayList<>();
                        for (Path f : files.keySet()) {
                            for (Path p = f; p != null; p = p.getParent()) {
                                if (dir.equals(p.getParent()) && !out.contains(p)) {
                                    out.add(p);
                                }
                            }
                        }
                        return out;
                    },
                    p -> links.getOrDefault(p, p));
        }

        Optional<MaidLocator.Found> locate() {
            return MaidLocator.locate(inputs());
        }
    }

    private static Path modules(Path prefix) {
        return prefix.resolve("lib")
                .resolve("node_modules")
                .resolve("@probelabs")
                .resolve("maid");
    }

    @Test
    void theDefaultCommandIsTheOneDecidedByFiles() {
        assertTrue(MaidLocator.isDefault(List.of("npx", "-y", "@probelabs/maid")));
        assertTrue(MaidLocator.isDefault(Mermaid.command("", MermaidService.DEFAULT_MAID)));
        assertFalse(MaidLocator.isDefault(List.of("maid")));
        assertFalse(MaidLocator.isDefault(List.of("npx", "-y", "@probelabs/maid@0.0.20")));
        assertFalse(MaidLocator.isDefault(List.of("/opt/maid/bin/maid")));
    }

    @Test
    void nothingInstalledIsNothingFound() {
        Machine m = new Machine();
        m.path.put("node", "/home/ada/.nvm/versions/node/v24.18.1/bin/node");
        assertTrue(m.locate().isEmpty());
    }

    @Test
    void aGlobalInstallBesideNodeOnPathIsFound() {
        Path prefix = Path.of("/home/ada/.nvm/versions/node/v24.18.1");
        Machine m = new Machine().maid(modules(prefix), "0.0.29");
        m.path.put("node", prefix.resolve("bin").resolve("node").toString());
        MaidLocator.Found found = m.locate().orElseThrow();
        assertEquals(modules(prefix), found.packageDir());
        assertEquals("0.0.29", found.version());
        assertEquals("0.0.29 · " + modules(prefix), found.detail());
    }

    @Test
    void theSettingsNodeJsGlobalsAreLookedAtFirst() {
        Path setting = Path.of("/opt/node-22");
        Path onPath = Path.of("/usr");
        Machine m = new Machine().maid(modules(setting), "0.0.28").maid(modules(onPath), "0.0.20");
        m.nodeSetting = setting.resolve("bin").resolve("node").toString();
        m.path.put("node", onPath.resolve("bin").resolve("node").toString());
        assertEquals("0.0.28", m.locate().orElseThrow().version());
    }

    @Test
    void anNpmPrefixFromTheEnvironmentOrNpmrcCounts() {
        Path envPrefix = Path.of("/srv/npm-global");
        Machine env = new Machine().maid(modules(envPrefix), "0.0.27");
        env.env.put("npm_config_prefix", envPrefix.toString());
        assertEquals("0.0.27", env.locate().orElseThrow().version());

        Machine rc = new Machine().maid(modules(HOME.resolve(".npm-global")), "0.0.26");
        rc.files.put(HOME.resolve(".npmrc"), "; mine\nprefix=~/.npm-global\n");
        assertEquals("0.0.26", rc.locate().orElseThrow().version());
    }

    @Test
    void windowsKeepsGlobalsInTheAppDataPrefix() {
        Path appData = Path.of("C:/Users/ada/AppData/Roaming");
        Path pkg = appData.resolve("npm")
                .resolve("node_modules")
                .resolve("@probelabs")
                .resolve("maid");
        Machine m = new Machine().maid(pkg, "0.0.29");
        m.windows = true;
        m.env.put("APPDATA", appData.toString());
        assertEquals(pkg, m.locate().orElseThrow().packageDir());
    }

    @Test
    void npxsOwnCacheCounts() {
        Path pkg = HOME.resolve(".npm/_npx/88fc23db429cb005/node_modules/@probelabs/maid");
        Machine m = new Machine().maid(pkg, "0.0.29");
        assertEquals(pkg, m.locate().orElseThrow().packageDir());

        Path custom = Path.of("/cache/npm/_npx/abc/node_modules/@probelabs/maid");
        Machine moved = new Machine().maid(custom, "0.0.25");
        moved.env.put("npm_config_cache", "/cache/npm");
        assertEquals(custom, moved.locate().orElseThrow().packageDir());
    }

    @Test
    void aMaidOnPathCountsOnlyWhenItIsThisPackage() {
        Path pkg = Path.of("/usr/local/share/maid-pkg");
        Machine m = new Machine().maid(pkg, "0.0.29");
        m.path.put("maid", "/usr/local/bin/maid");
        m.links.put(Path.of("/usr/local/bin/maid"), pkg.resolve("dist").resolve("cli.js"));
        assertEquals(pkg, m.locate().orElseThrow().packageDir());

        // Another program called maid (a Ruby gem of that name exists) is not the linter.
        Machine other = new Machine();
        other.path.put("maid", "/usr/bin/maid");
        other.files.put(Path.of("/usr/package.json"), "{\"name\": \"something-else\", \"version\": \"1.0.0\"}");
        assertTrue(other.locate().isEmpty());
    }

    @Test
    void aPackageJsonOfAnotherNameIsNotMaid() {
        Path prefix = Path.of("/usr/local");
        Machine m = new Machine();
        m.files.put(
                modules(prefix).resolve("package.json"), "{\"name\": \"@probelabs/maid-fork\", \"version\": \"9\"}");
        assertTrue(m.locate().isEmpty());
    }

    @Test
    void npmrcValuesAreReadAsNpmDoes() {
        assertEquals("/a", MaidLocator.npmrcValue("prefix=/a", "prefix"));
        assertEquals("/b", MaidLocator.npmrcValue("prefix = /a\n# c\nPREFIX=\"/b\"\n", "prefix"));
        assertNull(MaidLocator.npmrcValue("; prefix=/a\ncache=/c", "prefix"));
        assertEquals(HOME.resolve(".npm"), MaidLocator.npmCache(new Machine().inputs()));
    }
}
