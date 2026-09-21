package com.editora.agent.runtime;

import java.nio.file.*;
import java.util.*;

import com.editora.agent.eval.AgentEvaluationCases;
import com.editora.process.ProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AgentValidationTest {
    @TempDir
    Path root;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void discoversBuildOperationsAndRejectsSelectorsAndEscapes() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        var workspace = new AgentWorkspace(root);
        var plan = AgentValidationProfile.plan(
                workspace,
                json.readTree(
                        "{\"type\":\"TARGETED_TEST\",\"test\":\"CodeTest#works\",\"isolation\":\"HOST_REDUCED\"}"));
        assertTrue(plan.argv().contains("-Dtest=CodeTest#works"));
        assertTrue(plan.argv().contains("-DskipTests=false"));
        assertEquals(
                "MAVEN",
                AgentValidationProfile.discover(workspace, ".")
                        .path("profiles")
                        .get(0)
                        .path("system")
                        .asText());
        assertThrows(
                Exception.class,
                () -> AgentValidationProfile.plan(
                        workspace, json.readTree("{\"type\":\"TARGETED_TEST\",\"test\":\"-DskipTests=true\"}")));
        assertThrows(
                Exception.class,
                () -> AgentValidationProfile.plan(workspace, json.readTree("{\"type\":\"TEST\",\"module\":\"..\"}")));
        Files.writeString(root.resolve("build.gradle"), "");
        assertThrows(
                Exception.class, () -> AgentValidationProfile.plan(workspace, json.readTree("{\"type\":\"TEST\"}")));
        assertTrue(AgentValidationProfile.plan(workspace, json.readTree("{\"type\":\"CHECK\",\"system\":\"GRADLE\"}"))
                .argv()
                .contains("check"));
    }

    @Test
    void onlyFreshReportsCountAndXmlCannotReadExternalFiles() throws Exception {
        var directory = Files.createDirectories(root.resolve("target/surefire-reports"));
        Path old =
                Files.writeString(directory.resolve("TEST-Old.xml"), "<testsuite><testcase name='old'/></testsuite>");
        var before = AgentValidationReports.stamps(root);
        var workspace = new AgentWorkspace(root);
        assertEquals(
                0,
                AgentValidationReports.read(workspace, root, before)
                        .path("tests")
                        .asInt());
        Files.writeString(
                directory.resolve("TEST-New.xml"),
                "<testsuite><testcase classname='ParserTest' name='empty'><failure type='AssertionError' message='expected empty'/></testcase></testsuite>");
        var fresh = AgentValidationReports.read(workspace, root, before);
        assertEquals(1, fresh.path("tests").asInt());
        assertEquals(1, fresh.path("failed").asInt());
        assertEquals("ParserTest", fresh.path("failures").get(0).path("class").asText());
        Files.writeString(
                directory.resolve("TEST-Param.xml"),
                "<testsuite><testcase classname='demo.SaveTest' name='preserves(int)[1]'/></testsuite>");
        var parsed = AgentValidationReports.readDetailed(workspace, root, before);
        var parameterized = parsed.cases().stream()
                .filter(t -> t.className().equals("demo.SaveTest"))
                .findFirst()
                .orElseThrow();
        assertEquals("target/surefire-reports/TEST-Param.xml", parameterized.report());
        assertEquals("preserves", AgentTestIdentity.from(parameterized).sourceMethod());
        Path secret = Files.writeString(root.resolve("private.txt"), "PRIVATE-CONTENT");
        Files.writeString(
                directory.resolve("TEST-Entity.xml"),
                "<!DOCTYPE testsuite [<!ENTITY x SYSTEM '" + secret.toUri()
                        + "'>]><testsuite><testcase>&x;</testcase></testsuite>");
        var refused = AgentValidationReports.read(workspace, root, before);
        assertEquals(1, refused.path("unreadableReports").asInt());
        assertFalse(refused.toString().contains("PRIVATE-CONTENT"));
    }

    @Test
    void isolatedMavenTestsRunOfflineWithFreshStructuredEvidence() throws Exception {
        assumeTrue(Boolean.getBoolean("agent.validation.integration"), "opt-in real Linux validation isolation");
        assumeTrue(AgentValidationSandbox.available());
        var task = AgentEvaluationCases.tasks().stream()
                .filter(t -> t.id().equals("ledger-tests"))
                .findFirst()
                .orElseThrow();
        AgentEvaluationCases.prepare(task, Path.of(".").toAbsolutePath(), root);
        var workspace = new AgentWorkspace(root);
        var plan =
                AgentValidationProfile.plan(workspace, json.readTree("{\"type\":\"TEST\",\"isolation\":\"ISOLATED\"}"));
        var argv = AgentValidationSandbox.command(workspace, plan);
        assertTrue(argv.contains("--unshare-all"));
        assertTrue(argv.contains("--clearenv"));
        var result = ProcessRunner.runRestricted(root, java.time.Duration.ofSeconds(90), argv);
        assertEquals(0, result.exit(), result.out() + result.err());
        var evidence = AgentValidationReports.read(workspace, root, Map.of());
        assertEquals(1, evidence.path("tests").asInt());
        assertEquals(0, evidence.path("failed").asInt());
        // Execute an isolated probe using the same mount/env policy, not a mock sandbox.
        int boundary = argv.indexOf("--");
        var probe = new ArrayList<>(argv.subList(0, boundary + 1));
        probe.addAll(List.of(
                "/bin/sh",
                "-c",
                "test ! -e '" + System.getProperty("user.home")
                        + "/.ssh' && test \"$HOME\" = /tmp/agent-home && test ! -e /etc/resolv.conf"));
        assertEquals(
                0,
                ProcessRunner.runRestricted(root, java.time.Duration.ofSeconds(10), probe)
                        .exit());
        try (var hostOnly = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Path file = root.resolve("NetworkProbe.java");
            Files.writeString(
                    file,
                    "class NetworkProbe {public static void main(String[] args) throws Exception {try(var s=new java.net.Socket()){try{s.connect(new java.net.InetSocketAddress(\"127.0.0.1\","
                            + hostOnly.getLocalPort()
                            + "),500);throw new AssertionError(\"host loopback reachable\");}catch(java.io.IOException expected){}}}}");
            var networkProbe = new ArrayList<>(argv.subList(0, boundary + 1));
            networkProbe.addAll(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(), file.toString()));
            var network = ProcessRunner.runRestricted(root, java.time.Duration.ofSeconds(15), networkProbe);
            assertEquals(0, network.exit(), network.out() + network.err());
        }
    }
}
