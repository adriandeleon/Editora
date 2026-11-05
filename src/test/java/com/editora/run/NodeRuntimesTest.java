package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.editora.run.NodeRuntimes.Inputs;
import com.editora.run.NodeRuntimes.NodeRuntime;
import com.editora.run.NodeRuntimes.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRuntimesTest {

    @TempDir
    Path tmp;

    private Inputs in(Path file, Path root, String configured, String setting, Path home, String onPath) {
        return new Inputs(
                file,
                root,
                configured,
                setting,
                home,
                Files::isRegularFile,
                p -> {
                    try {
                        return Optional.of(Files.readString(p));
                    } catch (Exception e) {
                        return Optional.empty();
                    }
                },
                d -> {
                    try (var s = Files.list(d)) {
                        return s.toList();
                    } catch (Exception e) {
                        return List.of();
                    }
                },
                n -> Optional.ofNullable(onPath));
    }

    private Path nvmVersion(Path home, String version) throws Exception {
        Path bin = home.resolve(".nvm/versions/node/" + version + "/bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("node"), "");
        return bin.resolve("node");
    }

    private Path file(Path p) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "");
        return p;
    }

    @Test
    void theConfigurationWinsAndKeepsItsArguments() {
        NodeRuntime r =
                NodeRuntimes.resolve(in(tmp.resolve("a.js"), null, "node --no-warnings", "/opt/node", tmp, null));
        assertEquals(List.of("node", "--no-warnings"), r.command());
        assertEquals(Source.CONFIGURATION, r.source());
        assertEquals(List.of("--no-warnings"), r.args());
    }

    @Test
    void theSettingComesNext() {
        String node = tmp.resolve("opt/node20/bin/node").toString(); // absolute on every OS
        NodeRuntime r = NodeRuntimes.resolve(in(tmp.resolve("a.js"), null, "", node, tmp, "/usr/bin/node"));
        assertEquals(List.of(node), r.command());
        assertEquals(Source.SETTING, r.source());
        assertEquals(Optional.of(tmp.resolve("opt/node20/bin")), r.binDir());
    }

    @Test
    void anNvmrcPicksTheNewestInstalledMatchingVersion() throws Exception {
        Path home = tmp.resolve("home");
        nvmVersion(home, "v20.10.0");
        Path want = nvmVersion(home, "v20.11.1");
        nvmVersion(home, "v24.18.1");
        Path proj = tmp.resolve("proj");
        file(proj.resolve("package.json"));
        Files.writeString(proj.resolve(".nvmrc"), "20\n");
        Path script = file(proj.resolve("src/app.js"));
        NodeRuntime r = NodeRuntimes.resolve(in(script, null, "", "", home, "/usr/bin/node"));
        assertEquals(List.of(want.toString()), r.command());
        assertEquals(Source.VERSION_FILE, r.source());
        assertEquals(".nvmrc", r.versionFile());
    }

    @Test
    void aVersionFileNvmDoesNotHaveFallsBackToPath() throws Exception {
        Path home = tmp.resolve("home");
        nvmVersion(home, "v24.18.1");
        Path proj = tmp.resolve("proj");
        file(proj.resolve("package.json"));
        Files.writeString(proj.resolve(".node-version"), "18.19.0");
        Path script = file(proj.resolve("app.js"));
        NodeRuntime r = NodeRuntimes.resolve(in(script, null, "", "", home, "/usr/bin/node"));
        assertEquals(List.of("/usr/bin/node"), r.command());
        assertEquals(Source.PATH, r.source());
    }

    @Test
    void anAliasInTheVersionFileIsNotGuessed() throws Exception {
        Path home = tmp.resolve("home");
        nvmVersion(home, "v24.18.1");
        Path proj = tmp.resolve("proj");
        Files.createDirectories(proj);
        Files.writeString(proj.resolve(".nvmrc"), "lts/*");
        Path script = file(proj.resolve("app.js"));
        assertEquals(
                Source.PATH,
                NodeRuntimes.resolve(in(script, proj, "", "", home, null)).source());
    }

    @Test
    void aBareNodeIsTheLastResort() {
        NodeRuntime r = NodeRuntimes.resolve(in(null, null, "", "", null, null));
        assertEquals(List.of("node"), r.command());
        assertTrue(r.binDir().isEmpty());
    }

    @Test
    void thePackageRootIsTheNearestPackageJson() throws Exception {
        Path proj = tmp.resolve("proj");
        file(proj.resolve("package.json"));
        file(proj.resolve("packages/a/package.json"));
        Path script = file(proj.resolve("packages/a/src/x.js"));
        assertEquals(
                Optional.of(proj.resolve("packages/a")),
                NodeRuntimes.packageRoot(script, proj, null, Files::isRegularFile));
        assertEquals(
                proj.resolve("packages/a"),
                NodeRuntimes.packageRoot(script, proj, null, Files::isRegularFile)
                        .orElseThrow());
    }

    @Test
    void aFileInNodeModulesBelongsToThePackageAboveIt() throws Exception {
        Path proj = tmp.resolve("proj");
        file(proj.resolve("package.json"));
        file(proj.resolve("node_modules/dep/package.json"));
        Path inDep = file(proj.resolve("node_modules/dep/node_modules/inner/index.js"));
        assertEquals(Optional.of(proj), NodeRuntimes.packageRoot(inDep, null, null, Files::isRegularFile));
        assertTrue(NodeRuntimes.inNodeModules(inDep));
        assertFalse(NodeRuntimes.inNodeModules(proj.resolve("src/a.js")));
        assertEquals(proj, NodeRuntimes.outsideNodeModules(inDep));
    }

    @Test
    void theWalkStopsAtTheProjectRootAndNeverReachesHome() throws Exception {
        Path home = tmp.resolve("home");
        file(home.resolve("package.json"));
        Path script = file(home.resolve("scripts/tool.js"));
        assertTrue(NodeRuntimes.packageRoot(script, null, home, Files::isRegularFile)
                .isEmpty());
        Path proj = home.resolve("scripts");
        assertTrue(NodeRuntimes.packageRoot(script, proj, home, Files::isRegularFile)
                .isEmpty());
    }

    @Test
    void versionsAndTypeStripping() {
        assertTrue(NodeRuntimes.stripsTypes("v24.18.1"));
        assertTrue(NodeRuntimes.stripsTypes("Node.js 23.6.0"));
        assertTrue(NodeRuntimes.stripsTypes("22.18.0"));
        assertFalse(NodeRuntimes.stripsTypes("22.17.9"));
        assertFalse(NodeRuntimes.stripsTypes("20.11.1"));
        assertFalse(NodeRuntimes.stripsTypes("not a version"));
        assertTrue(NodeRuntimes.atLeast("v18.0.0", 18, 0));
        assertFalse(NodeRuntimes.atLeast("v16.20.2", 18, 0));
        assertNull(NodeRuntimes.parseVersion("x").orElse(null));
    }

    @Test
    void aProjectsVersionFileBeatsTheSetting() throws Exception {
        Path home = tmp.resolve("home");
        Path want = nvmVersion(home, "v20.11.1");
        Path proj = tmp.resolve("proj");
        file(proj.resolve("package.json"));
        Files.writeString(proj.resolve(".nvmrc"), "# pinned\nv20\n");
        Path script = file(proj.resolve("app.js"));
        NodeRuntime r = NodeRuntimes.resolve(in(script, null, "", "/opt/node24/bin/node", home, "/usr/bin/node"));
        assertEquals(List.of(want.toString()), r.command());
        assertEquals(Source.VERSION_FILE, r.source());
        // the run configuration's own Interpreter still beats the pin
        assertEquals(
                Source.CONFIGURATION,
                NodeRuntimes.resolve(in(script, null, "node", "", home, null)).source());
    }

    @Test
    void anUninstalledPinIsReportedAndTheSettingRuns() throws Exception {
        Path home = tmp.resolve("home");
        nvmVersion(home, "v24.18.1");
        Path proj = tmp.resolve("proj");
        file(proj.resolve("package.json"));
        Files.writeString(proj.resolve(".nvmrc"), "18\n");
        Path script = file(proj.resolve("app.js"));
        NodeRuntime viaSetting =
                NodeRuntimes.resolve(in(script, null, "", "/opt/node24/bin/node", home, "/usr/bin/node"));
        assertEquals(Source.SETTING, viaSetting.source());
        assertEquals("18", viaSetting.unmetVersion());
        assertEquals(".nvmrc", viaSetting.versionFile());
        NodeRuntime viaPath = NodeRuntimes.resolve(in(script, null, "", "", home, "/usr/bin/node"));
        assertEquals(Source.PATH, viaPath.source());
        assertEquals("18", viaPath.unmetVersion());
        assertNull(NodeRuntimes.resolve(in(tmp.resolve("x.js"), null, "", "", home, null))
                .unmetVersion());
    }

    @Test
    void nvmDirComesFromTheEnvironment() throws Exception {
        Path nvm = tmp.resolve("custom-nvm");
        Path bin = nvm.resolve("versions/node/v20.1.0/bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("node"), "");
        Path proj = tmp.resolve("proj");
        Files.createDirectories(proj);
        Files.writeString(proj.resolve(".node-version"), "20");
        Path script = file(proj.resolve("app.js"));
        Inputs base = in(script, proj, "", "", tmp.resolve("home"), null);
        Inputs withNvm = new Inputs(
                base.file(),
                base.projectRoot(),
                "",
                "",
                base.home(),
                base.isFile(),
                base.readFile(),
                base.listDir(),
                base.onPath(),
                nvm);
        assertEquals(
                List.of(bin.resolve("node").toString()),
                NodeRuntimes.resolve(withNvm).command());
        assertEquals(Source.PATH, NodeRuntimes.resolve(base).source(), "~/.nvm has no such version");

        assertEquals(Path.of("/x/nvm"), NodeRuntimes.nvmDir("/x/nvm", "/cfg", p -> true));
        assertEquals(Path.of("/cfg/nvm"), NodeRuntimes.nvmDir(null, "/cfg", p -> true));
        assertNull(NodeRuntimes.nvmDir(null, "/cfg", p -> false));
        assertNull(NodeRuntimes.nvmDir("", "", p -> true));
    }

    @Test
    void aVersionMissingFromNvmDirIsStillFoundInTheHomeFoldersNvm() throws Exception {
        // A CI image sets $NVM_DIR to its own nvm; a version installed under ~/.nvm still counts.
        Path home = tmp.resolve("home");
        Path bin = home.resolve(".nvm/versions/node/v12.22.12/bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("node"), "");
        Files.createDirectories(tmp.resolve("system-nvm/versions/node/v24.0.0/bin"));
        Path proj = tmp.resolve("legacy");
        Files.createDirectories(proj);
        Files.writeString(proj.resolve(".nvmrc"), "12\n");
        Path script = file(proj.resolve("app.js"));
        Inputs base = in(script, proj, "", "", home, null);
        Inputs withNvm = new Inputs(
                base.file(),
                base.projectRoot(),
                "",
                "",
                home,
                base.isFile(),
                base.readFile(),
                base.listDir(),
                base.onPath(),
                tmp.resolve("system-nvm"));
        assertEquals(
                List.of(bin.resolve("node").toString()),
                NodeRuntimes.resolve(withNvm).command());
        assertEquals(Source.VERSION_FILE, NodeRuntimes.resolve(withNvm).source());
    }

    @Test
    void aToolsNodeIgnoresTheSettingAndThePin() throws Exception {
        Path home = tmp.resolve("home");
        nvmVersion(home, "v16.20.0");
        Path newest = nvmVersion(home, "v22.3.0");
        nvmVersion(home, "v20.11.1");
        Inputs i = in(null, null, "", "/opt/old/node", home, "/usr/bin/node");
        assertEquals(
                List.of(newest.toString()), NodeRuntimes.resolveForTool(i, 18).command());
        assertEquals(
                List.of("/usr/bin/node"), NodeRuntimes.resolveForTool(i, 23).command(), "none new enough");
        assertEquals(
                List.of("node"),
                NodeRuntimes.resolveForTool(in(null, null, "", "", null, null), 18)
                        .command());
    }

    @Test
    void theVersionFilesFirstRealLineCounts() {
        assertEquals("20", NodeRuntimes.firstLine("\n# comment\n  20  \nlts/*\n"));
        assertEquals("", NodeRuntimes.firstLine("\n\n"));
    }

    @Test
    void aMonoreposPinAboveTheMembersPackageJsonCounts() throws Exception {
        Path home = tmp.resolve("home");
        Path want = nvmVersion(home, "v20.11.1");
        Path repo = tmp.resolve("repo");
        file(repo.resolve("package.json"));
        Files.writeString(repo.resolve(".nvmrc"), "20");
        file(repo.resolve("packages/a/package.json"));
        Path script = file(repo.resolve("packages/a/src/x.js"));
        assertEquals(
                List.of(want.toString()),
                NodeRuntimes.resolve(in(script, repo, "", "", home, null)).command());
    }

    @Test
    void aHostsNvmNodeIsPickedFromItsListing() {
        List<String> listed = List.of(
                "/opt/nvm/versions/node/v18.20.4/bin/node",
                "/opt/nvm/versions/node/v20.9.0/bin/node",
                "not a node path",
                "/home/me/.nvm/versions/node/v20.11.1/bin/node",
                "/home/me/.nvm/versions/node/v22.3.0/bin/node");
        assertEquals(
                Optional.of("/opt/nvm/versions/node/v20.9.0/bin/node"),
                NodeRuntimes.pickHostNvmNode("20", listed),
                "the first nvm folder with a match wins, as here");
        assertEquals(
                Optional.of("/home/me/.nvm/versions/node/v20.11.1/bin/node"),
                NodeRuntimes.pickHostNvmNode("v20.11", listed));
        assertEquals(
                Optional.of("/home/me/.nvm/versions/node/v22.3.0/bin/node"),
                NodeRuntimes.pickHostNvmNode("22.3.0", listed));
        assertEquals(Optional.empty(), NodeRuntimes.pickHostNvmNode("16", listed));
        assertEquals(Optional.empty(), NodeRuntimes.pickHostNvmNode("lts/*", listed));
        assertEquals(Optional.empty(), NodeRuntimes.pickHostNvmNode("2", listed), "20 is not 2");
    }

    /** The listing script itself, run by this machine's /bin/sh against a scratch home. */
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void theHostListingScriptFindsNvmNodes() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path other = Files.createDirectories(tmp.resolve("other-nvm"));
        Path inHome = fakeNode(home.resolve(".nvm"), "v20.11.1");
        Path inOther = fakeNode(other, "v22.3.0");
        Files.createDirectories(home.resolve(".nvm/versions/node/v19.0.0/bin")); // no node in it

        assertEquals(List.of(inHome.toString()), listing(home, null));
        assertEquals(List.of(inOther.toString(), inHome.toString()), listing(home, other), "$NVM_DIR first");
        assertEquals(Optional.of(inOther.toString()), NodeRuntimes.pickHostNvmNode("22", listing(home, other)));
    }

    private static Path fakeNode(Path nvm, String version) throws Exception {
        Path node = nvm.resolve("versions/node").resolve(version).resolve("bin/node");
        Files.createDirectories(node.getParent());
        Files.writeString(node, "#!/bin/sh\n");
        node.toFile().setExecutable(true);
        return node;
    }

    private static List<String> listing(Path home, Path nvmDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", NodeRuntimes.HOST_NVM_NODES_SCRIPT);
        pb.environment().clear();
        pb.environment().put("HOME", home.toString());
        pb.environment().put("PATH", "/usr/bin:/bin");
        if (nvmDir != null) {
            pb.environment().put("NVM_DIR", nvmDir.toString());
        }
        Process p = pb.redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        return out.lines().toList();
    }
}
