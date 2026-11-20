package com.editora.toolchain;

import java.nio.file.Path;
import java.util.List;

import com.editora.process.ProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** jkit's {@code --json} documents (schema version 1, jkit's docs/json.md), read without running jkit. */
class JkitCliTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void readsListInstalls() throws Exception {
        String doc = """
                {"schemaVersion":1,"home":"/h/.jkit","global":{"jdk":null,"maven":"/h/.jkit/tools/maven/3.10.1"},
                 "installed":[
                  {"tool":"jdk","id":"25.0.5.1+2-corretto","version":"25.0.5.1+2","major":25,"vendor":"corretto",
                   "fx":false,"graalvm":false,"preRelease":false,"home":"/h/.jkit/tools/jdk/25.0.5.1+2-corretto",
                   "global":[],"activeInShell":[]},
                  {"tool":"maven","id":"3.10.1","version":"3.10.1","major":3,"vendor":null,"fx":false,
                   "home":"/h/.jkit/tools/maven/3.10.1","global":["maven"]},
                  {"tool":"jdk"}
                 ]}""";

        List<ToolInstall> installs = JkitCli.parseList(JSON.readTree(doc));

        assertEquals(2, installs.size()); // the incomplete one is skipped
        assertEquals("corretto", installs.get(0).vendor());
        assertEquals(
                Path.of("/h/.jkit/tools/jdk/25.0.5.1+2-corretto"),
                installs.get(0).home());
        assertNull(installs.get(1).vendor());
        assertEquals("25.0.5.1+2-corretto", installs.get(0).id());
    }

    @Test
    void readsSearchAndUpgrade() throws Exception {
        String search = """
                {"schemaVersion":1,"tool":"jdk","vendor":"temurin","vendorChosen":false,
                 "majors":[{"major":25,"lts":true,"maintained":true}],
                 "available":[{"tool":"jdk","version":"25.0.1+8","major":25,"vendor":"temurin","graalvm":false,
                   "fx":false,"lts":true,"maintained":true,"preRelease":false,"installed":true}]}""";
        JkitCli.Available a = JkitCli.parseSearch(JSON.readTree(search)).getFirst();
        assertTrue(a.lts());
        assertTrue(a.installed());
        assertEquals(List.of("install", "jdk@25.0.1+8", "temurin"), a.installArgs());

        String upgrade = """
                {"schemaVersion":1,"dryRun":true,"lines":[
                  {"tool":"jdk","major":21,"vendor":"temurin","fx":false,"from":"21.0.5+11","to":"21.0.9+10","status":"available"},
                  {"tool":"maven","major":3,"vendor":null,"fx":false,"from":"3.9.9","to":null,"status":"failed",
                   "error":{"message":"repo.maven.apache.org is having problems","hints":[]}}]}""";
        List<JkitCli.UpgradeLine> lines = JkitCli.parseUpgrade(JSON.readTree(upgrade));
        assertTrue(lines.get(0).available());
        assertEquals("21.0.9+10", lines.get(0).to());
        assertFalse(lines.get(1).available());
        assertEquals("repo.maven.apache.org is having problems", lines.get(1).error());
    }

    @Test
    void outcomesSayWhatWentWrong() {
        JkitCli.Outcome ok = JkitCli.outcome(new ProcessRunner.Result(0, "{\"schemaVersion\":1,\"installed\":[]}", ""));
        assertTrue(ok.ok());

        JkitCli.Outcome error = JkitCli.outcome(new ProcessRunner.Result(
                3,
                "{\"schemaVersion\":1,\"error\":{\"message\":\"no installed JDK matches 99\",\"hints\":[\"install it\"],\"exitCode\":3}}",
                "error: no installed JDK matches 99"));
        assertFalse(error.ok());
        assertEquals("no installed JDK matches 99\ninstall it", error.error());

        JkitCli.Outcome old =
                JkitCli.outcome(new ProcessRunner.Result(2, "", "Unknown option: '--json'\nUsage: jkit list"));
        assertTrue(old.error().contains("too old"));

        JkitCli.Outcome noJson = JkitCli.outcome(new ProcessRunner.Result(1, "", "error: something broke"));
        assertEquals("something broke", noJson.error());
    }

    @Test
    void targetsNameExactlyOneInstall() {
        ToolInstall fx = new ToolInstall("jdk", "21.0.9+10", "zulu", true, Path.of("/j"), "jkit", "21.0.9+10-zulu-fx");
        assertEquals(
                List.of("use", "jdk@21.0.9+10", "zulu", "--fx", "--global"), JkitCli.withTarget("use", fx, "--global"));
        ToolInstall maven = new ToolInstall("maven", "3.9.11", null, false, Path.of("/m"), "jkit", "3.9.11");
        assertEquals(List.of("remove", "maven@3.9.11"), JkitCli.withTarget("remove", maven, null));
    }
}
