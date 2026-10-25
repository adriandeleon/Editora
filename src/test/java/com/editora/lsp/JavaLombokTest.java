package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaLombokTest {

    @TempDir
    Path tmp;

    private Path jar(String relative) throws Exception {
        Path jar = tmp.resolve("home").resolve(relative);
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");
        return jar;
    }

    @Test
    void aProjectWhoseBuildDeclaresLombokGetsTheNewestCachedJar() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path module = Files.createDirectories(project.resolve("core"));
        Files.writeString(project.resolve("pom.xml"), "<project><modules><module>core</module></modules></project>");
        Files.writeString(module.resolve("pom.xml"), "<dependency><groupId>org.projectlombok</groupId></dependency>");
        jar(".m2/repository/org/projectlombok/lombok/1.18.9/lombok-1.18.9.jar");
        Path newest = jar(".m2/repository/org/projectlombok/lombok/1.18.34/lombok-1.18.34.jar");

        assertEquals(newest, JavaLombok.agentFor(project, tmp.resolve("home")), "a module's dependency counts");
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        assertNull(JavaLombok.agentFor(project, tmp.resolve("home")), "no Lombok in the build: no agent");
    }

    @Test
    void gradlesCacheIsTheFallbackAndTheFreefairPluginCounts() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("g"));
        Files.writeString(
                project.resolve("build.gradle.kts"), "plugins { id(\"io.freefair.lombok\") version \"8.6\" }");
        assertNull(JavaLombok.agentFor(project, tmp.resolve("home")), "declared, but nothing downloaded yet");
        Path cached =
                jar(".gradle/caches/modules-2/files-2.1/org.projectlombok/lombok/1.18.30/abc123/lombok-1.18.30.jar");
        assertEquals(cached, JavaLombok.agentFor(project, tmp.resolve("home")));
    }

    @Test
    void theAgentGoesWhereTheLaunchCommandTakesAJvmOption() {
        Path jar = Path.of("/h/lombok-1.18.34.jar");
        assertEquals(
                List.of("/opt/jdtls/bin/jdtls", "--jvm-arg=-javaagent:" + jar, "-data", "/w"),
                JavaLombok.withAgent(List.of("/opt/jdtls/bin/jdtls", "-data", "/w"), jar, exe -> true));
        assertEquals(
                List.of("/usr/bin/java", "-javaagent:" + jar, "-jar", "launcher.jar"),
                JavaLombok.withAgent(List.of("/usr/bin/java", "-jar", "launcher.jar"), jar, exe -> false));
        List<String> own = List.of("/opt/jdtls/bin/jdtls", "--jvm-arg=-javaagent:/x/lombok.jar");
        assertEquals(own, JavaLombok.withAgent(own, jar, exe -> true), "the user's own agent is kept");
        assertEquals(List.of("my-wrapper"), JavaLombok.withAgent(List.of("my-wrapper"), jar, exe -> false));
        assertEquals(
                List.of("/opt/jdtls/bin/jdtls"),
                JavaLombok.withAgent(List.of("/opt/jdtls/bin/jdtls"), null, exe -> true));
    }

    @Test
    void versionsCompareByNumber() {
        assertTrue(JavaLombok.compareVersions("1.18.34", "1.18.9") > 0);
        assertTrue(JavaLombok.compareVersions("1.18", "1.18.2") < 0);
        assertEquals(0, JavaLombok.compareVersions("1.18.30", "1.18.30"));
        assertTrue(JavaLombok.compareVersions("edge-SNAPSHOT", "1.0") < 0);
        assertTrue(JavaLombok.mentionsLombok("compileOnly 'org.projectlombok:lombok:1.18.34'"));
        assertFalse(JavaLombok.mentionsLombok("implementation 'com.google.guava:guava:33.0'"));
    }
}
