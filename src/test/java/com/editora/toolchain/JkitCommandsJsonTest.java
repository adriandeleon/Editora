package com.editora.toolchain;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.process.ProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The documents of the jkit commands Editora drives beyond install and upgrade ({@code status}, {@code doctor},
 * {@code config}, {@code prune}, {@code sync}, {@code self-update}, {@code env}, {@code pin}), as jkit 0.4's
 * docs/json.md writes them, read without running jkit; and the project pins resolved from them.
 */
class JkitCommandsJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String STATUS = """
            {"schemaVersion": 1,
             "project": {"dir": "/src/app", "files": ["/src/app/jkit.toml"], "warnings": ["/src/app/jkit.toml:4: unknown key 'x', skipped"]},
             "projectError": null, "projectSkipped": null,
             "tools": [
               {"tool": "jdk", "source": "global", "home": "/h/.jkit/global/jdk", "installed": null, "version": "17",
                "pin": {"version": "21", "vendor": null, "fx": false},
                "pinInstalled": {"tool": "jdk", "id": "21.0.9+10-temurin", "version": "21.0.9+10", "major": 21,
                                 "vendor": "temurin", "fx": false, "home": "/h/.jkit/tools/jdk/21.0.9+10-temurin"},
                "wanted": {"source": "project", "home": "/h/.jkit/tools/jdk/21.0.9+10-temurin"},
                "global": {"target": "/h/.jkit/tools/jdk/17.0.13+11-zulu", "missing": false},
                "matches": false, "hint": "jkit.toml wants jdk 21; this shell has global: jkit sync"},
               {"tool": "maven", "pin": {"version": "3.9", "vendor": null, "fx": false}, "pinInstalled": null,
                "wanted": {"source": "project", "home": null}, "global": null, "hint": null},
               {"tool": "gradle", "pin": null, "pinInstalled": null, "wanted": {"source": "none", "home": null},
                "global": null, "hint": null},
               {"tool": "sbt", "pin": null, "pinInstalled": null, "wanted": {"source": "none", "home": null},
                "global": null, "hint": null},
               {"tool": "graalvm", "pin": {"version": "25", "vendor": "graalvm-ce", "fx": false}, "pinInstalled": null,
                "wanted": {"source": "project", "home": null}, "global": null, "hint": null}
             ]}
            """;

    @Test
    void readsStatus() throws Exception {
        JkitCli.Status status = JkitCli.parseStatus(JSON.readTree(STATUS));

        assertEquals(Path.of("/src/app"), status.projectDir());
        assertEquals("jkit.toml", status.fileNames());
        assertEquals(1, status.warnings().size());
        assertEquals(5, status.tools().size());
        JkitCli.StatusTool jdk = status.of("jdk");
        assertNotNull(jdk);
        assertEquals(new JkitCli.PinSpec("jdk", "21", null, false), jdk.pin());
        assertEquals(
                Path.of("/h/.jkit/tools/jdk/21.0.9+10-temurin"),
                jdk.pinInstalled().home());
        assertEquals("project", jdk.wantedSource());
        assertEquals("/h/.jkit/tools/jdk/17.0.13+11-zulu", jdk.global());
        assertTrue(jdk.hint().contains("jkit sync"));
        assertNull(status.of("gradle").pin());
        assertEquals("25 graalvm-ce", status.of("graalvm").pin().label());

        JkitCli.Status none = JkitCli.parseStatus(
                JSON.readTree("{\"schemaVersion\":1,\"project\":null,\"projectError\":\"bad TOML\",\"tools\":[]}"));
        assertNull(none.projectDir());
        assertEquals("bad TOML", none.projectError());
    }

    @Test
    void jkitsPinsResolveToWhatJkitPickedElseToTheInstalls() throws Exception {
        List<ProjectToolchain.FilePin> pins = ProjectToolchain.jkitPins(JkitCli.parseStatus(JSON.readTree(STATUS)));
        assertEquals(
                List.of("jdk", "maven", "graalvm"),
                pins.stream().map(p -> p.request().tool()).toList());

        ToolInstall sdkmanMaven =
                new ToolInstall("maven", "3.9.11", null, false, Path.of("/s/mvn"), "sdkman", "3.9.11");
        ToolInstall graal =
                new ToolInstall("jdk", "25.0.1", "graalvm-ce", false, Path.of("/s/graal"), "sdkman", "25.0.1-graalce");
        ToolInstall temurin25 = new ToolInstall("jdk", "25.0.1+8", "temurin", false, Path.of("/s/tem"), "jkit", "x");
        ProjectToolchain.Resolution r = ProjectToolchain.resolve(
                Map.of(),
                pins,
                new ProjectToolchain.Installs(List.of(graal, temurin25), List.of(sdkmanMaven), List.of()));

        assertEquals("/h/.jkit/tools/jdk/21.0.9+10-temurin", r.home("jdk"), "the install jkit picked");
        assertEquals(ProjectToolchain.Origin.JKIT, r.jdk().origin());
        assertEquals("jkit.toml", r.jdk().file());
        assertEquals("/s/mvn", r.home("maven"), "jkit has none: SDKMAN's Maven answers the pin");
        assertEquals("/s/graal", r.home("graalvm"), "only a GraalVM-based JDK answers the graalvm pin");
        assertTrue(r.missing().isEmpty());

        ProjectToolchain.Resolution own = ProjectToolchain.resolve(
                Map.of("jdk", "25"), pins, new ProjectToolchain.Installs(List.of(temurin25), List.of(), List.of()));
        assertEquals("/s/tem", own.home("jdk"), "the project setting wins over jkit.toml");
        assertEquals(ProjectToolchain.Origin.PROJECT_SETTINGS, own.jdk().origin());
        assertEquals(
                List.of("maven", "graalvm"),
                own.missing().stream().map(ProjectToolchain.Choice::tool).toList());
    }

    @Test
    void aPinWithoutAVersionIsTheNewestAndInstallsAsLatest() {
        ToolRequest latest = ToolRequest.of(new JkitCli.PinSpec("gradle", null, null, false));
        ToolInstall g8 = new ToolInstall("gradle", "8.14.3", null, false, Path.of("/g8"), "jkit", "8.14.3");
        ToolInstall g9 = new ToolInstall("gradle", "9.2.1", null, false, Path.of("/g9"), "jkit", "9.2.1");
        assertEquals(g9, latest.best(List.of(g8, g9)));
        assertEquals(List.of("install", "gradle@latest"), latest.jkitInstallArgs());

        ToolRequest fx = ToolRequest.of(new JkitCli.PinSpec("jdk", "21", "tem", true));
        assertEquals("temurin", fx.vendor(), "SDKMAN's and jkit's vendor names alike");
        assertEquals(Boolean.TRUE, fx.fx());
        assertNull(ToolRequest.of(new JkitCli.PinSpec("jdk", "21", null, false)).fx(), "fx = false asks nothing");
    }

    @Test
    void readsDoctorConfigPruneSyncSelfUpdateAndEnv() throws Exception {
        List<JkitCli.DoctorCheck> checks = JkitCli.parseDoctor(JSON.readTree("""
                {"schemaVersion":1,"checks":[
                  {"id":"home","level":"ok","message":"jkit 0.4.0","fix":null},
                  {"id":"links","level":"fail","message":"global maven is gone","fix":"jkit use maven@3.9.11 --global"}],
                 "summary":{"ok":1,"fail":1}}"""));
        assertEquals(2, checks.size());
        assertNull(checks.get(0).fix());
        assertEquals("fail", checks.get(1).level());

        List<JkitCli.ConfigSetting> config = JkitCli.parseConfig(JSON.readTree("""
                {"schemaVersion":1,"file":"/h/.jkit/config.toml","error":null,"settings":[
                  {"key":"jdk.vendor","value":"zulu","source":"file","file":"zulu","env":null,
                   "envVariable":"JKIT_JDK_VENDOR","default":"temurin","description":"the vendor jdk@… uses"},
                  {"key":"shell.auto_switch","value":"false","source":"default","file":null,"env":null,
                   "envVariable":"JKIT_AUTO_SWITCH","default":"false","description":"switch on cd"}]}"""));
        assertEquals("zulu", config.get(0).value());
        assertEquals("temurin", config.get(0).defaultValue());
        assertEquals("default", config.get(1).source());

        JkitCli.Prune prune = JkitCli.parsePrune(JSON.readTree("""
                {"schemaVersion":1,"dryRun":true,"builds":[{"install":{"tool":"jdk","id":"21.0.5+11-temurin",
                  "version":"21.0.5+11","vendor":"temurin","fx":false,"home":"/h/jdk21old"},"newer":"21.0.9+10",
                  "bytes":201326592}],"leftovers":["/h/.jkit/tmp/install-1"],"cache":[],"bytes":201330000}"""));
        assertEquals(1, prune.builds().size());
        assertEquals(201330000L, prune.bytes());
        assertFalse(prune.isEmpty());
        assertTrue(JkitCli.parsePrune(JSON.readTree("{\"schemaVersion\":1,\"builds\":[]}"))
                .isEmpty());

        List<JkitCli.SyncLine> sync = JkitCli.parseSync(JSON.readTree("""
                {"schemaVersion":1,"dryRun":true,"tools":[
                  {"tool":"jdk","status":"would-install","label":"21.0.9+10 temurin"},
                  {"tool":"maven","status":"would-refuse","label":"3.9.11"}]}"""));
        assertEquals("would-refuse", sync.get(1).status());

        JkitCli.SelfUpdate update = JkitCli.parseSelfUpdate(JSON.readTree("""
                {"schemaVersion":1,"current":"0.3.0","latest":"0.4.0","status":"managed","installedWith":"deb",
                 "binary":"/usr/bin/jkit","command":"sudo apt upgrade jkit"}"""));
        assertEquals("managed", update.status());
        assertEquals("sudo apt upgrade jkit", update.command());
        assertNull(JkitCli.parseSelfUpdate(null));

        Map<String, String> env = JkitCli.parseEnv(JSON.readTree("""
                {"schemaVersion":1,"variables":{"JAVA_HOME":"/j21"},"pathPrepend":["/j21/bin"],"path":"/j21/bin:/usr/bin"}"""));
        assertEquals(Map.of("JAVA_HOME", "/j21", "PATH", "/j21/bin:/usr/bin"), env);
    }

    @Test
    void pinArgumentsNameTheVersionVendorAndFolder() {
        assertEquals(
                List.of("pin", "jdk@21.0.9+10", "zulu", "--fx", "--here"),
                JkitCli.pinArgs(new JkitCli.PinSpec("jdk", "21.0.9+10", "zulu", true), true));
        assertEquals(
                List.of("pin", "maven@latest"),
                JkitCli.pinArgs(new JkitCli.PinSpec("maven", null, null, false), false));
    }

    @Test
    void anOlderJkitWithoutTheCommandSaysToUpdateIt() {
        JkitCli.Outcome old = JkitCli.outcome(new ProcessRunner.Result(
                2,
                "{\"schemaVersion\":1,\"error\":{\"message\":\"Unmatched arguments from index 0: 'config', '--json'\","
                        + "\"hints\":[\"did you mean: copyright?\"],\"exitCode\":2}}",
                ""));
        assertEquals(JkitCli.TOO_OLD, old.error());
    }

    @Test
    void graalvmIsAJdkFromAGraalvmVendor() {
        assertTrue(new ToolInstall("jdk", "25", "mandrel", false, Path.of("/m"), "jkit", "m").graalvm());
        assertFalse(new ToolInstall("jdk", "25", "temurin", false, Path.of("/t"), "jkit", "t").graalvm());
        assertEquals(
                "sbt 1.11.7", new ToolInstall("sbt", "1.11.7", null, false, Path.of("/s"), "jkit", "1.11.7").label());
        assertTrue(ProjectFiles.isProjectFile(Path.of("/p/.tool-versions")));
        assertFalse(ProjectFiles.isProjectFile(Path.of("/p/pom.xml")));
    }
}
