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
}
