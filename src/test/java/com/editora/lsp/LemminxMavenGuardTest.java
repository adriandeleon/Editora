package com.editora.lsp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plain XML server's start backstop (XML/pom review 3, A21): a command that would load lemminx-maven — which
 * resolves an untrusted pom and runs its plugins — is recognised whether it names the jar or a folder holding it.
 */
class LemminxMavenGuardTest {

    private static final String MAIN = "org.eclipse.lemminx.XMLServerLauncher";

    @Test
    void aWildcardFolderHoldingLemminxMavenLoadsIt(@TempDir Path dir) throws Exception {
        Path bundle = Files.createDirectories(dir.resolve("plugins/lsp/maven"));
        Files.writeString(bundle.resolve("org.eclipse.lemminx-0.30.1.jar"), "");
        Path plain = Files.createDirectories(dir.resolve("plain"));
        Files.writeString(plain.resolve("org.eclipse.lemminx-0.30.1.jar"), "");
        assertFalse(LemminxMavenGuard.loadsLemminxMaven(List.of("java", "-cp", bundle + "/*", MAIN), true));

        Files.writeString(bundle.resolve("lemminx-maven-0.12.0.jar"), "");
        assertTrue(LemminxMavenGuard.loadsLemminxMaven(List.of("java", "-cp", bundle + "/*", MAIN), true));
        assertTrue(LemminxMavenGuard.loadsLemminxMaven(
                List.of(
                        "/jdk/bin/java",
                        "-Duser.home=/h",
                        "-classpath",
                        plain + "/*" + File.pathSeparator + bundle + "/*",
                        MAIN),
                true));
        assertTrue(LemminxMavenGuard.loadsLemminxMaven(List.of("java", "--class-path=" + bundle + "/*", MAIN), true));
        assertFalse(
                LemminxMavenGuard.loadsLemminxMaven(List.of("java", "-cp", bundle + "/*", MAIN), false),
                "a remote host's folder is not listed here");
        assertFalse(LemminxMavenGuard.loadsLemminxMaven(List.of("java", "-cp", plain + "/*", MAIN), true));
    }

    @Test
    void aCommandNamingTheJarLoadsIt() {
        assertTrue(LemminxMavenGuard.loadsLemminxMaven(
                List.of(
                        "java",
                        "-cp",
                        "/b/org.eclipse.lemminx-0.30.1.jar" + File.pathSeparator + "/b/lemminx-maven-0.12.0.jar",
                        MAIN),
                false));
        assertTrue(LemminxMavenGuard.loadsLemminxMaven(List.of("java", "-jar", "/b/LEMMINX-MAVEN-all.jar"), false));
        assertFalse(LemminxMavenGuard.loadsLemminxMaven(List.of("/opt/lemminx/lemminx"), true));
        assertFalse(LemminxMavenGuard.loadsLemminxMaven(List.of(), true));
    }
}
