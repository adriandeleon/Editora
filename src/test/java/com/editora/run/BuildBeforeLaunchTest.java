package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.build.BuildTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The build before a launch (review D2) and the symbolic before-launch step (review D3), as pure decisions. */
class BuildBeforeLaunchTest {

    @Test
    void theToolOfARootIsReadFromItsMarkerFile(@TempDir Path dir) throws Exception {
        assertNull(BuildBeforeLaunch.toolAt(dir), "a plain folder");
        assertNull(BuildBeforeLaunch.toolAt(null));
        Files.writeString(dir.resolve("build.gradle.kts"), "");
        assertEquals(BuildTool.GRADLE, BuildBeforeLaunch.toolAt(dir));
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        assertEquals(
                BuildTool.MAVEN, BuildBeforeLaunch.toolAt(dir), "a pom wins, as the launch's classpath comes from it");
    }

    @Test
    void theCompileCommandFollowsTheToolAndTheSourceTree() {
        assertEquals(
                List.of("./mvnw", "-q", "compile"),
                BuildBeforeLaunch.compileArgv(BuildTool.MAVEN, List.of("./mvnw"), false));
        assertEquals(
                List.of("mvn", "-q", "test-compile"),
                BuildBeforeLaunch.compileArgv(BuildTool.MAVEN, List.of("mvn"), true));
        assertEquals(
                List.of("/p/gradlew", "-q", "compileJava"),
                BuildBeforeLaunch.compileArgv(BuildTool.GRADLE, List.of("/p/gradlew"), false));
        assertEquals(
                List.of("gradle", "-q", "compileTestJava"),
                BuildBeforeLaunch.compileArgv(BuildTool.GRADLE, List.of("gradle"), true));
        Path root = Path.of("/proj");
        assertTrue(BuildBeforeLaunch.isTestSource(root.resolve("src/test/java/a/T.java"), root));
        assertFalse(BuildBeforeLaunch.isTestSource(root.resolve("src/main/java/a/A.java"), root));
    }

    /** A Maven compile carries the module's reactor selection and the tasks tree's profiles (reviews D5, D13). */
    @Test
    void aMavenCompileAppendsTheReactorSelectionAndTheToggles() {
        Path repo = Path.of("/repo").toAbsolutePath().normalize();
        com.editora.maven.MavenReactor.Reactor reactor =
                new com.editora.maven.MavenReactor.Reactor(repo, repo.resolve("pom.xml"), "modules/b");
        List<String> extra = BuildBeforeLaunch.mavenExtraArgs(
                reactor, null, List.of("-f", "/elsewhere/pom.xml", "-pl", "app", "-am", "-Pdev", "-o"));
        assertEquals(
                List.of("-f", repo.resolve("pom.xml").toString(), "-pl", "modules/b", "-am", "-Pdev", "-o"), extra);
        assertEquals(
                List.of(
                        "mvn",
                        "-q",
                        "compile",
                        "-f",
                        repo.resolve("pom.xml").toString(),
                        "-pl",
                        "modules/b",
                        "-am",
                        "-Pdev",
                        "-o"),
                BuildBeforeLaunch.compileArgv(BuildTool.MAVEN, List.of("mvn"), false, extra));
        assertEquals(List.of("-Pdev"), BuildBeforeLaunch.mavenExtraArgs(null, null, List.of("-Pdev")), "no reactor");
        assertEquals(
                List.of("-f", "/host/pom.xml", "-pl", "modules/b", "-am"),
                BuildBeforeLaunch.mavenExtraArgs(reactor, "/host/pom.xml", null));
        assertEquals(
                List.of("mvn", "-q", "compile"),
                BuildBeforeLaunch.compileArgv(BuildTool.MAVEN, List.of("mvn"), false, null));
    }

    @Test
    void aSymbolicStepNamesAToolAndItsGoals() {
        BuildBeforeLaunch.Symbolic step = BuildBeforeLaunch.symbolic("maven:compile");
        assertEquals(BuildTool.MAVEN, step.tool());
        assertEquals(List.of("compile"), step.goals());
        assertEquals("maven:compile", step.text());

        BuildBeforeLaunch.Symbolic several = BuildBeforeLaunch.symbolic("  Gradle: compileJava processResources ");
        assertEquals(BuildTool.GRADLE, several.tool());
        assertEquals(List.of("compileJava", "processResources"), several.goals());

        assertEquals("maven:compile", BuildBeforeLaunch.seededStep(BuildTool.MAVEN));
    }

    /** A plain command line keeps working: only a leading {@code <tool id>:} is symbolic. */
    @Test
    void aPlainCommandIsNotSymbolic() {
        assertNull(BuildBeforeLaunch.symbolic("mvn -q compile"));
        assertNull(BuildBeforeLaunch.symbolic("C:\\tools\\build.cmd compile"), "a Windows drive is not a tool");
        assertNull(BuildBeforeLaunch.symbolic("make all:clean"), "the colon is in an argument");
        assertNull(BuildBeforeLaunch.symbolic("nosuchtool:compile"));
        assertNull(BuildBeforeLaunch.symbolic("maven:"), "no goals");
        assertNull(BuildBeforeLaunch.symbolic(""));
        assertNull(BuildBeforeLaunch.symbolic(null));
    }

    @Test
    void theAutomaticBuildWantsTheSettingNoOwnStepAndABuildTool() {
        assertTrue(BuildBeforeLaunch.wanted(true, "", BuildTool.MAVEN));
        assertTrue(BuildBeforeLaunch.wanted(true, null, BuildTool.GRADLE));
        assertFalse(BuildBeforeLaunch.wanted(false, "", BuildTool.MAVEN), "turned off");
        assertFalse(
                BuildBeforeLaunch.wanted(true, "mvn -q compile", BuildTool.MAVEN),
                "the configuration's own step, never doubled");
        assertFalse(BuildBeforeLaunch.wanted(true, "maven:compile", BuildTool.MAVEN));
        assertFalse(BuildBeforeLaunch.wanted(true, "", null), "no build tool to ask");
    }

    @Test
    void theMissingClassHintMatchesTheJvmsTwoWordings() {
        assertTrue(BuildBeforeLaunch.missingClass(
                "Error: Could not find or load main class com.example.Main", "com.example.Main"));
        assertTrue(BuildBeforeLaunch.missingClass(
                "Caused by: java.lang.ClassNotFoundException: com.example.Main", "com.example.Main"));
        assertFalse(BuildBeforeLaunch.missingClass("Could not find or load main class other.Main", "com.example.Main"));
        assertFalse(BuildBeforeLaunch.missingClass("Shelf demo", "com.example.Main"));
        assertFalse(BuildBeforeLaunch.missingClass(null, "com.example.Main"));
        assertFalse(BuildBeforeLaunch.missingClass("ClassNotFoundException: ", ""));
    }
}
